package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.AircraftComponent;
import ru.socol.supreme.shared.components.AttackComponent;
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
 * ПВО (UnitType.ANTI_AIR) — единственный тип со своим множителем урона В
 * ЗАВИСИМОСТИ ОТ ЦЕЛИ (а не только от того, кто атакует, или кого атакуют
 * — как TURRET_DAMAGE_MULTIPLIER ниже): UnitDefinitions.canTarget пускает
 * его на любую цель, воздушную или наземную (это его игровая роль —
 * специализация по воздуху, а не запрет), но по наземной он бьёт вдвое
 * слабее. Считается там же, где и TURRET_DAMAGE_MULTIPLIER, — оба
 * множителя независимы и перемножаются, если совпали разом (ПВО добивает
 * вражескую турель).
 *
 * Приоритет 0 — раньше MovementSystem (приоритет 10) — чтобы направление
 * погони, выставленное здесь, в этом же тике подхватила MovementSystem.
 *
 * Разделение целей по стихиям (наземный/воздушный, см. UnitDefinitions
 * .canTarget) эта система заново не проверяет — раз AttackComponent уже
 * стоит на атакующем, цель считается допустимой: это проверено один раз
 * при её назначении (AggroSystem или GameServer.startAttackOrder), а
 * воздушная/наземная природа юнита за время боя не меняется, перепроверять
 * каждый тик нечего. Единственное исключение — вода: строитель, в отличие
 * от домена "воздух/земля", МОЖЕТ зайти под воду уже посреди погони (см.
 * processEntity, самое начало) — эта одна проверка каждый тик всё-таки
 * остаётся, отменяя атаку, если цель туда спряталась.
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

    /**
     * Уведомляет о гибели ЗДАНИЯ (не обломков — см. проверку ниже) в бою —
     * GameServer.spawnBuildingRubble реагирует, оставляя на его месте
     * именные обломки (BuildingRubbleComponent) с частью потраченного на
     * него железа и с правом отстроиться на этом месте со скидкой. Второй,
     * не связанный с этим триггер того же spawnBuildingRubble —
     * добровольный снос (GameServer.handleDemolishBuilding), не бой.
     */
    public interface BuildingDestroyedListener {
        void onBuildingDestroyed(BuildingType destroyedType, float x, float y);
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
    private final BuildingDestroyedListener buildingDestroyedListener;

    /** Переиспользуемый вектор для точки подхода при погоне — не аллоцируем новый каждый тик на каждого атакующего. */
    private final Vector2 approachPoint = new Vector2();

    /** Переиспользуемый вектор для направления на цель — только для проверки конуса стрельбы авиации, см. её ниже. */
    private final Vector2 toTargetDirection = new Vector2();

    private Engine engine;

    /** Поиск пути этого матча — см. javadoc Pathfinding, почему не статический. */
    private final Pathfinding pathfinding;

    public CombatSystem(Map<Integer, Entity> unitsById, ShotFiredListener shotFiredListener,
                         UnitDestroyedListener unitDestroyedListener,
                         BuildingDestroyedListener buildingDestroyedListener, Pathfinding pathfinding) {
        super(Family.all(AttackComponent.class, PositionComponent.class, DirectionComponent.class).get(), 0);
        this.pathfinding = pathfinding;
        this.unitsById = unitsById;
        this.shotFiredListener = shotFiredListener;
        this.unitDestroyedListener = unitDestroyedListener;
        this.buildingDestroyedListener = buildingDestroyedListener;
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

        PositionComponent targetPosition = POSITION.get(target);

        // Единственное исключение из правила "стихия цели за время боя не
        // меняется" (см. javadoc класса) — строитель может ЗАЙТИ под воду
        // уже ПОСЛЕ того, как его назначили целью (AggroSystem/GameServer
        // .startAttackOrder не пускают под воду только В МОМЕНТ назначения,
        // а сам он может уйти туда посреди погони). Проверяем это здесь, на
        // каждом тике — так же, как и "цель пропала" чуть выше, отменяя
        // приказ, а не просто пропуская тик: иначе атакующий так и стоял бы
        // с бесполезным AttackComponent, пытаясь подойти к недостижимой
        // (Pathfinding.isBlocked для него самого) точке подхода у уреза воды.
        if (Pathfinding.isInsideWater(targetPosition.position.x, targetPosition.position.y)) {
            attacker.remove(AttackComponent.class);
            DIRECTION.get(attacker).moving = false;
            return;
        }

        UnitTypeComponent attackerTypeComponent = UNIT_TYPE.get(attacker);
        UnitType attackerType = attackerTypeComponent != null ? attackerTypeComponent.type : UnitType.WARRIOR;
        float attackRange = UnitDefinitions.attackRadiusFor(attackerType);

        PositionComponent myPosition = POSITION.get(attacker);
        DirectionComponent direction = DIRECTION.get(attacker);

        float distance = myPosition.position.dst(targetPosition.position);

        if (distance > attackRange) {
            // Идём не в точный центр цели, а в точку на отрезке между нами
            // и целью, на расстоянии attackRange от неё (с небольшим
            // отступом — см. approachDistance ниже) — туда, откуда уже
            // можно стрелять. Это не только естественно (не нужно доходить
            // вплотную), но и обязательно для зданий: их собственный центр
            // всегда лежит "внутри" них самих, а значит формально заблокирован
            // для Pathfinding (см. PATH_CLEARANCE) — атакующий, направленный
            // прямо в центр здания, просто никуда не пошёл бы. Точка в
            // attackRange от цели гарантированно снаружи любого препятствия,
            // потому что attackRange (140 у воина, 210 у стрелка) всегда
            // больше BUILDING_HALF_SIZE + PATH_CLEARANCE.
            // targetPosition читается заново КАЖДЫЙ ТИК, пока distance >
            // attackRange, в отличие от BuildSystem/RepairSystem/
            // ScavengeSystem, которые считают точку подхода один раз и
            // кешируют (hasApproachPoint) — там цель (здание/обломки)
            // никогда не двигается, пересчитывать неоткуда, а тут цель
            // атаки вполне может уйти, и погоня должна доворачивать на неё
            // каждый тик, а не бежать в устаревшую точку.
            //
            // Отступ на ARRIVE_THRESHOLD — та же причина и тот же фикс,
            // что и в BuildSystem.updateBuilder (см. её развёрнутый
            // комментарий): без него approachPoint лежит МАТЕМАТИЧЕСКИ
            // ровно на границе attackRange, но MovementSystem прилипает к
            // ней точно, а этот distance тут же на СЛЕДУЮЩЕМ тике меряется
            // заново через dst() — при погрешности округления float эти
            // два вычисления иногда чуть расходятся, и юнит, только что
            // "прибывший", снова видит distance > attackRange, снова
            // получает новую approachPoint (почти в той же точке, что и
            // была) и снова "прибывает" — видимое на экране мелкое
            // подёргивание вперёд-назад на месте вместо того, чтобы просто
            // остановиться и стрелять. Отступ с запасом (2 юнита при
            // attackRange от 70 и выше) перекрывает эту погрешность, не
            // нарушая инвариант ниже про PATH_CLEARANCE.
            //
            // ВАЖНО про units.json: attackRadius в файле должен оставаться
            // больше, чем самая большая раздутая (на PATH_CLEARANCE)
            // половина здания (сейчас максимум — 62, у дома) ПЛЮС этот
            // отступ, иначе точка подхода будет попадать ВНУТРЬ
            // заблокированной зоны здания, и Pathfinding.setDestination
            // будет её игнорировать — юнит с слишком маленьким
            // attackRadius не сможет атаковать здания издалека вообще.
            float approachDistance = Math.max(0f, attackRange - GameConstants.ARRIVE_THRESHOLD);
            approachPoint.set(myPosition.position).sub(targetPosition.position).nor()
                    .scl(approachDistance).add(targetPosition.position);
            pathfinding.setDestination(attacker, myPosition, direction, approachPoint.x, approachPoint.y);
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
        float damageMultiplier = 1f;

        // ПВО специализирован по воздуху — по наземной цели (включая
        // здания: у них targetTypeComponent == null, кроме турели, но она
        // тоже не воздушная) его урон вдвое слабее, чем заявлен в
        // UnitDefinitions.damageFor (см. её же javadoc и javadoc класса).
        boolean targetIsAirForDamage = targetTypeComponent != null && UnitDefinitions.isAirUnit(targetTypeComponent.type);
        if (attackerType == UnitType.ANTI_AIR && !targetIsAirForDamage) {
            damageMultiplier *= 0.5f;
        }
        if (targetTypeComponent != null && targetTypeComponent.type == UnitType.TURRET) {
            // Турель как ЦЕЛЬ получает усиленный урон от любой атаки,
            // независимо от типа атакующего — см. javadoc
            // GameConstants.TURRET_DAMAGE_MULTIPLIER, почему. Независимо
            // от множителя ПВО выше — если он бьёт по вражеской турели,
            // оба множителя перемножаются.
            damageMultiplier *= GameConstants.TURRET_DAMAGE_MULTIPLIER;
        }
        int damage = Math.round(UnitDefinitions.damageFor(attackerType) * damageMultiplier);
        targetHealth.currentHealth -= damage;

        if (shotFiredListener != null) {
            shotFiredListener.onShotFired(attackerType,
                    myPosition.position.x, myPosition.position.y,
                    targetPosition.position.x, targetPosition.position.y);
        }

        if (targetHealth.currentHealth <= 0) {
            EntityDestruction.destroy(engine, unitsById, pathfinding, target, attack.targetUnitId,
                    unitDestroyedListener, buildingDestroyedListener);
            attacker.remove(AttackComponent.class);
        }
    }
}
