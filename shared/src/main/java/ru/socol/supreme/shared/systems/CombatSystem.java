package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.AircraftComponent;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.Map;

/**
 * Обрабатывает приказы на атаку: пока цель дальше дальности атаки (зависит
 * от типа юнита — см. UnitDefinitions.attackRadiusFor) — юнит идёт к ней
 * (перезаписывая DirectionComponent, как обычный приказ на движение,
 * только цель "живая" и обновляется каждый тик); как только цель в
 * радиусе — юнит останавливается и стреляет по кулдауну FIRE_INTERVAL,
 * снимая урон (UnitDefinitions.damageFor, тоже по типу атакующего)
 * здоровья. При 0 HP цель удаляется из движка и из общего реестра юнитов
 * сервера. Если ЦЕЛЬ — турель (UnitType.TURRET), этот урон домножается на
 * GameConstants.TURRET_DAMAGE_MULTIPLIER (см. её javadoc) — от типа
 * атакующего это не зависит вовсе, множитель только для того, ПО КОМУ бьют.
 *
 * У авиации (AircraftComponent) есть ещё одно условие для выстрела — цель
 * должна быть в конусе ±firingArcDegrees впереди по курсу (своя половина
 * угла у каждого типа авиации — UnitDefinitions.firingArcDegreesFor,
 * например ±45° у разведчика и ±55° у штурмовика), не сбоку и не сзади:
 * самолёт не может повернуть мгновенно (AircraftMovementSystem), так что
 * остановка в радиусе атаки не значит "можно стрелять прямо сейчас" —
 * приходится ждать, пока курс довернётся сам, обычно за счёт кружения
 * вокруг цели (или просто зависания, если умеет — см. AircraftComponent
 * .canHover). Наземных юнитов это не касается — им ориентация не важна
 * никогда.
 *
 * Приоритет 0 — раньше MovementSystem (приоритет 10) — чтобы направление
 * погони, выставленное здесь, в этом же тике подхватила MovementSystem.
 *
 * Живёт в shared (как и MovementSystem), но реально используется только
 * сервером: клиент не запускает эту систему у себя, он лишь показывает
 * результат из снапшотов.
 */
public class CombatSystem extends IteratingSystem {

    /**
     * Чтобы сообщить GameServer о выстреле (для рассылки чисто косметического
     * ProjectileFiredEvent клиентам, см. его javadoc) — урон уже применён к
     * этому моменту, слушатель нужен только для визуализации полёта стрелы.
     */
    public interface ShotFiredListener {
        void onShotFired(UnitType attackerType, float fromX, float fromY, float toX, float toY);
    }

    /**
     * Уведомляет о гибели ЮНИТА (не здания — см. её единственный вызов
     * ниже) в бою — GameServer.spawnWreck реагирует на это, оставляя на
     * его месте обломки с частью потраченного на него железа. Не
     * настоящее здание, потому что снос/разрушение построек уже устроено
     * иначе (BuildingComponent, ConstructionComponent) и обломки для них
     * пока не запрошены — только для юнитов.
     */
    public interface UnitDestroyedListener {
        void onUnitDestroyed(UnitType destroyedType, float x, float y);
    }

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<DirectionComponent> DIRECTION =
            ComponentMapper.getFor(DirectionComponent.class);
    private static final ComponentMapper<AttackComponent> ATTACK =
            ComponentMapper.getFor(AttackComponent.class);
    private static final ComponentMapper<HealthComponent> HEALTH =
            ComponentMapper.getFor(HealthComponent.class);
    private static final ComponentMapper<UnitTypeComponent> UNIT_TYPE =
            ComponentMapper.getFor(UnitTypeComponent.class);

    /** Тот же реестр unitId -> Entity, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, Entity> unitsById;
    private final ShotFiredListener shotFiredListener;
    private final UnitDestroyedListener unitDestroyedListener;

    /** Переиспользуемый вектор для точки подхода при погоне — не аллоцируем новый каждый тик на каждого атакующего. */
    private final Vector2 approachPoint = new Vector2();

    /** Переиспользуемый вектор для направления на цель — только для проверки конуса стрельбы авиации, см. её ниже. */
    private final Vector2 toTargetDirection = new Vector2();

    private Engine engine;

    public CombatSystem(Map<Integer, Entity> unitsById, ShotFiredListener shotFiredListener,
                         UnitDestroyedListener unitDestroyedListener) {
        super(Family.all(AttackComponent.class, PositionComponent.class, DirectionComponent.class).get(), 0);
        this.unitsById = unitsById;
        this.shotFiredListener = shotFiredListener;
        this.unitDestroyedListener = unitDestroyedListener;
    }

    @Override
    public void addedToEngine(Engine engine) {
        super.addedToEngine(engine);
        this.engine = engine;
    }

    @Override
    protected void processEntity(Entity attacker, float deltaTime) {
        AttackComponent attack = ATTACK.get(attacker);
        Entity target = unitsById.get(attack.targetUnitId);

        if (target == null) {
            // Цель уже мертва/отключилась — приказ на атаку больше не актуален.
            attacker.remove(AttackComponent.class);
            DIRECTION.get(attacker).moving = false;
            return;
        }

        UnitTypeComponent attackerTypeComponent = UNIT_TYPE.get(attacker);
        UnitType attackerType = attackerTypeComponent != null ? attackerTypeComponent.type : UnitType.WARRIOR;
        float attackRange = UnitDefinitions.attackRadiusFor(attackerType);

        PositionComponent myPosition = POSITION.get(attacker);
        PositionComponent targetPosition = POSITION.get(target);
        DirectionComponent direction = DIRECTION.get(attacker);

        float distance = myPosition.position.dst(targetPosition.position);

        if (distance > attackRange) {
            // Идём не в точный центр цели, а в точку на отрезке между нами
            // и целью, на расстоянии attackRange от неё — туда, откуда уже
            // можно стрелять. Это не только естественно (не нужно доходить
            // вплотную), но и обязательно для зданий: их собственный центр
            // всегда лежит "внутри" них самих, а значит формально заблокирован
            // для Pathfinding (см. PATH_CLEARANCE) — атакующий, направленный
            // прямо в центр здания, просто никуда не пошёл бы. Точка в
            // attackRange от цели гарантированно снаружи любого препятствия,
            // потому что attackRange (140 у воина, 210 у стрелка) всегда
            // больше BUILDING_HALF_SIZE + PATH_CLEARANCE.
            // targetPosition читается заново каждый тик — если цель
            // сдвинулась в другую клетку сетки, путь пересчитается сам.
            //
            // ВАЖНО про units.json: attackRadius в файле должен оставаться
            // больше, чем самая большая раздутая (на PATH_CLEARANCE)
            // половина здания (сейчас максимум — 62, у дома), иначе точка
            // подхода будет попадать ВНУТРЬ заблокированной зоны здания, и
            // Pathfinding.setDestination будет её игнорировать — юнит с
            // слишком маленьким attackRadius не сможет атаковать здания
            // издалека вообще.
            approachPoint.set(myPosition.position).sub(targetPosition.position).nor()
                    .scl(attackRange).add(targetPosition.position);
            Pathfinding.setDestination(attacker, myPosition, direction, approachPoint.x, approachPoint.y);
            return;
        }

        // В радиусе атаки — останавливаемся, но стрелять может быть ещё
        // рано (см. проверку конуса стрельбы ниже).
        direction.moving = false;

        // Авиация стреляет только вперёд по курсу, не вбок и не назад —
        // наземных юнитов это не касается вовсе, им ориентация никогда не
        // была важна. direction.direction у авиации каждый тик
        // поддерживает AircraftMovementSystem (её реальный текущий курс,
        // не мгновенно довёрнутый на цель — самолёт не может повернуть
        // мгновенно), так что сравниваем именно его, не считаем угол
        // заново. Цель вне конуса — не стреляем и не тикаем кулдаун:
        // ждём, пока курс довернётся сам по себе (в первую очередь —
        // кружением, см. AircraftMovementSystem.loitering), а не жжём
        // впустую время перезарядки на то, что всё равно не могли бы
        // применить.
        if (attacker.getComponent(AircraftComponent.class) != null) {
            toTargetDirection.set(targetPosition.position).sub(myPosition.position);
            if (toTargetDirection.len2() > 0.0001f) {
                toTargetDirection.nor();
                float cosAngle = direction.direction.dot(toTargetDirection);
                float firingCosThreshold = (float) Math.cos(Math.toRadians(UnitDefinitions.firingArcDegreesFor(attackerType)));
                if (cosAngle < firingCosThreshold) {
                    return;
                }
            }
        }

        attack.cooldown -= deltaTime;

        if (attack.cooldown > 0f) {
            return;
        }

        attack.cooldown = UnitDefinitions.fireIntervalFor(attackerType);

        HealthComponent targetHealth = HEALTH.get(target);
        UnitTypeComponent targetTypeComponent = UNIT_TYPE.get(target);
        int damage = UnitDefinitions.damageFor(attackerType);
        if (targetTypeComponent != null && targetTypeComponent.type == UnitType.TURRET) {
            // Турель как ЦЕЛЬ получает усиленный урон от любой атаки,
            // независимо от типа атакующего — см. javadoc
            // GameConstants.TURRET_DAMAGE_MULTIPLIER, почему.
            damage = Math.round(damage * GameConstants.TURRET_DAMAGE_MULTIPLIER);
        }
        targetHealth.currentHealth -= damage;

        if (shotFiredListener != null) {
            shotFiredListener.onShotFired(attackerType,
                    myPosition.position.x, myPosition.position.y,
                    targetPosition.position.x, targetPosition.position.y);
        }

        if (targetHealth.currentHealth <= 0) {
            BuildingComponent targetBuilding = target.getComponent(BuildingComponent.class);
            // Обломки оставляют только настоящие юниты, не здания (снос
            // построек уже устроен отдельно, см. javadoc
            // UnitDestroyedListener) — и, разумеется, не сами обломки
            // (WreckComponent), если их вдруг умудрились "добить" боем,
            // хотя по-хорошему их здоровье должна трогать только
            // ScavengeSystem: тут её не видно, потому что targetBuilding
            // != null уже отсеивает любую сущность с BuildingComponent,
            // а обломки — это именно такая сущность.
            if (targetBuilding == null && unitDestroyedListener != null && targetTypeComponent != null) {
                unitDestroyedListener.onUnitDestroyed(targetTypeComponent.type,
                        targetPosition.position.x, targetPosition.position.y);
            }

            engine.removeEntity(target);
            unitsById.remove(attack.targetUnitId);
            if (targetBuilding != null) {
                Pathfinding.removeBuildingObstacle(attack.targetUnitId);
            }
            attacker.remove(AttackComponent.class);
        }
    }
}
