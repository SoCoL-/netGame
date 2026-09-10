package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.Map;

/**
 * Обрабатывает приказы на атаку: пока цель дальше дальности атаки (зависит
 * от типа юнита — см. GameConstants.attackRangeFor) — юнит идёт к ней
 * (перезаписывая DirectionComponent, как обычный приказ на движение,
 * только цель "живая" и обновляется каждый тик); как только цель в
 * радиусе — юнит останавливается и стреляет по кулдауну FIRE_INTERVAL,
 * снимая DAMAGE_PER_SHOT здоровья. При 0 HP цель удаляется из движка и
 * из общего реестра юнитов сервера.
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

    /** Переиспользуемый вектор для точки подхода при погоне — не аллоцируем новый каждый тик на каждого атакующего. */
    private final Vector2 approachPoint = new Vector2();

    private Engine engine;

    public CombatSystem(Map<Integer, Entity> unitsById, ShotFiredListener shotFiredListener) {
        super(Family.all(AttackComponent.class, PositionComponent.class, DirectionComponent.class).get(), 0);
        this.unitsById = unitsById;
        this.shotFiredListener = shotFiredListener;
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
        float attackRange = GameConstants.attackRangeFor(attackerType);

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
            // потому что attackRange (70 у воина, 210 у стрелка) всегда
            // больше BUILDING_HALF_SIZE + PATH_CLEARANCE.
            // targetPosition читается заново каждый тик — если цель
            // сдвинулась в другую клетку сетки, путь пересчитается сам.
            approachPoint.set(myPosition.position).sub(targetPosition.position).nor()
                    .scl(attackRange).add(targetPosition.position);
            Pathfinding.setDestination(attacker, myPosition, direction, approachPoint.x, approachPoint.y);
            return;
        }

        // В радиусе атаки — останавливаемся и стреляем по кулдауну.
        direction.moving = false;
        attack.cooldown -= deltaTime;

        if (attack.cooldown > 0f) {
            return;
        }

        attack.cooldown = GameConstants.FIRE_INTERVAL;

        HealthComponent targetHealth = HEALTH.get(target);
        targetHealth.currentHealth -= GameConstants.DAMAGE_PER_SHOT;

        if (shotFiredListener != null) {
            shotFiredListener.onShotFired(attackerType,
                    myPosition.position.x, myPosition.position.y,
                    targetPosition.position.x, targetPosition.position.y);
        }

        if (targetHealth.currentHealth <= 0) {
            engine.removeEntity(target);
            unitsById.remove(attack.targetUnitId);
            attacker.remove(AttackComponent.class);
        }
    }
}
