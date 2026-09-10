package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitComponent;

import java.util.Map;

/**
 * Автоагрессия: юнит без активного приказа атаковать (нет AttackComponent),
 * у которого в радиусе AGGRO_RADIUS оказался враг, сам получает приказ
 * атаковать ближайшего из них — дальше этим приказом, как и приказом,
 * выданным вручную через AttackUnitRequest, занимается CombatSystem
 * (погоня, стрельба, добивание).
 *
 * Это "агрессивная стойка" по умолчанию и безусловно для всех юнитов:
 * приказ на движение прерывается, если по пути подвернулся враг — стоек
 * "не атаковать" / "удерживать позицию" в игре нет.
 *
 * Family намеренно ИСКЛЮЧАЕТ AttackComponent — уже атакующие юниты цель не
 * пересматривают каждый тик, этим занимается только CombatSystem. Здания
 * тоже автоматически не участвуют: у них нет DirectionComponent, а он
 * обязателен для этой Family (как и для CombatSystem-атакующего).
 *
 * Приоритет -10 — раньше CombatSystem (0) и MovementSystem (10), чтобы
 * свежедобавленный в этот тик AttackComponent сразу подхватила CombatSystem
 * в этом же цикле обновления, без задержки на тик.
 *
 * Живёт в shared (как и CombatSystem/MovementSystem), но реально
 * используется только сервером.
 */
public class AggroSystem extends IteratingSystem {

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<OwnerComponent> OWNER =
            ComponentMapper.getFor(OwnerComponent.class);

    /** Тот же реестр unitId -> Entity, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, Entity> unitsById;

    private Engine engine;

    public AggroSystem(Map<Integer, Entity> unitsById) {
        super(Family.all(PositionComponent.class, OwnerComponent.class, DirectionComponent.class)
                .exclude(AttackComponent.class).get(), -10);
        this.unitsById = unitsById;
    }

    @Override
    public void addedToEngine(Engine engine) {
        super.addedToEngine(engine);
        this.engine = engine;
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        PositionComponent position = POSITION.get(entity);
        OwnerComponent owner = OWNER.get(entity);

        Entity nearestEnemy = null;
        float nearestDistanceSq = GameConstants.AGGRO_RADIUS * GameConstants.AGGRO_RADIUS;

        for (Entity other : unitsById.values()) {
            if (other == entity) {
                continue;
            }

            OwnerComponent otherOwner = OWNER.get(other);
            if (otherOwner == null || otherOwner.playerId == owner.playerId) {
                continue; // свой юнит/здание — не цель
            }

            float distanceSq = position.position.dst2(POSITION.get(other).position);
            if (distanceSq <= nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
                nearestEnemy = other;
            }
        }

        if (nearestEnemy == null) {
            return;
        }

        // Как и в GameServer.handleAttackUnit: PooledEngine переиспользует
        // объекты компонентов, а AttackComponent не реализует Poolable —
        // явно обнуляем cooldown, чтобы не унаследовать "грязное" значение.
        AttackComponent attack = engine.createComponent(AttackComponent.class);
        attack.cooldown = 0f;
        attack.targetUnitId = nearestEnemy.getComponent(UnitComponent.class).unitId;
        entity.add(attack);
    }
}
