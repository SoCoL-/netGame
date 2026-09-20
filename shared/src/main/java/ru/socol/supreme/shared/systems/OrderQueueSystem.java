package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import ru.socol.supreme.shared.QueuedOrder;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.BuildOrderComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.OrderQueueComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.RepairOrderComponent;
import ru.socol.supreme.shared.components.UnitComponent;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

/**
 * Как только юнит освобождается (не атакует, не строит и не в пути) и в
 * его OrderQueueComponent есть отложенный приказ — забирает первый
 * элемент очереди и запускает его тем же путём, каким запускается обычный
 * немедленный приказ. Юнит без очереди (пустой OrderQueueComponent, или
 * его нет вовсе — Family требует компонент, но не то, что он непуст)
 * просто пропускается каждый тик почти бесплатно (queue.isEmpty()).
 *
 * MOVE запускается прямо здесь (Pathfinding.setDestination — статический
 * метод, доступен без обратного вызова); ATTACK, BUILD и REPAIR требуют
 * валидации и доступа к GameServer-специфичным вещам (engine
 * .createComponent для AttackComponent, assignBuilderToBuild/
 * assignBuilderToRepair) — для них три обратных вызова (тот же приём,
 * что и ProductionSystem.UnitFactory или CombatSystem.ShotFiredListener),
 * чтобы не тащить в shared-систему ничего специфичного для конкретной
 * реализации сервера.
 *
 * Если очередной приказ из очереди оказался невалиден прямо сейчас
 * (цель погибла, здание уже снесено кем-то ещё) — обратный вызов просто
 * ничего не делает, юнит останется бездействовать до следующего тика,
 * когда снова попробует взять СЛЕДУЮЩИЙ элемент очереди (тот, что был
 * невалиден, уже удалён — не застревает в очереди навсегда).
 *
 * Приоритет 15 — после MovementSystem (10, чтобы "юнит только что дошёл"
 * успело примениться в этом же тике), до CollisionSystem (20).
 *
 * Живёт в shared, но реально используется только сервером — как и
 * остальные gameplay-системы.
 */
public class OrderQueueSystem extends IteratingSystem {

    /** playerId, actorUnitId, targetId — та же тройка аргументов что для атаки (targetUnitId), что для стройки (targetBuildingUnitId). */
    public interface OrderExecutor {
        void execute(int playerId, int actorUnitId, int targetId);
    }

    private static final ComponentMapper<OrderQueueComponent> ORDER_QUEUE =
            ComponentMapper.getFor(OrderQueueComponent.class);
    private static final ComponentMapper<DirectionComponent> DIRECTION =
            ComponentMapper.getFor(DirectionComponent.class);
    private static final ComponentMapper<OwnerComponent> OWNER =
            ComponentMapper.getFor(OwnerComponent.class);
    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<UnitComponent> UNIT =
            ComponentMapper.getFor(UnitComponent.class);

    private final OrderExecutor attackExecutor;
    private final OrderExecutor buildExecutor;
    private final OrderExecutor repairExecutor;

    public OrderQueueSystem(OrderExecutor attackExecutor, OrderExecutor buildExecutor, OrderExecutor repairExecutor) {
        super(Family.all(OrderQueueComponent.class, DirectionComponent.class,
                OwnerComponent.class, PositionComponent.class, UnitComponent.class).get(), 15);
        this.attackExecutor = attackExecutor;
        this.buildExecutor = buildExecutor;
        this.repairExecutor = repairExecutor;
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        OrderQueueComponent orderQueue = ORDER_QUEUE.get(entity);
        if (orderQueue.queue.isEmpty()) {
            return;
        }

        boolean idle = entity.getComponent(AttackComponent.class) == null
                && entity.getComponent(BuildOrderComponent.class) == null
                && entity.getComponent(RepairOrderComponent.class) == null
                && !DIRECTION.get(entity).moving;
        if (!idle) {
            return;
        }

        QueuedOrder next = orderQueue.queue.remove(0);
        int playerId = OWNER.get(entity).playerId;
        int actorUnitId = UNIT.get(entity).unitId;

        switch (next.type) {
            case MOVE:
                PositionComponent position = POSITION.get(entity);
                DirectionComponent direction = DIRECTION.get(entity);
                Pathfinding.setDestination(entity, position, direction, next.x, next.y);
                break;
            case ATTACK:
                attackExecutor.execute(playerId, actorUnitId, next.targetUnitId);
                break;
            case BUILD:
                buildExecutor.execute(playerId, actorUnitId, next.targetBuildingUnitId);
                break;
            case REPAIR:
                repairExecutor.execute(playerId, actorUnitId, next.targetBuildingUnitId);
                break;
        }
    }
}
