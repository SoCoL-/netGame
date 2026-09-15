package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.BuildOrderComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.Map;

/**
 * Обрабатывает приказы "строить это здание": пока цель дальше
 * buildRadius строителя (UnitDefinitions.buildRadiusFor) — юнит идёт к
 * ней (перезаписывая DirectionComponent, тем же приёмом, что и
 * CombatSystem при погоне за целью атаки); как только в радиусе —
 * останавливается и уменьшает ConstructionComponent.remaining цели на
 * deltaTime каждый тик, пока приказ действует. Если цель разрушена, или
 * стройка уже завершилась как-то иначе (ConstructionComponent снят) —
 * приказ снимается сам, юнит останавливается.
 *
 * Именно поэтому здание "не достраивается само": remaining уменьшает
 * ТОЛЬКО эта система, и только пока у какого-то строителя есть активный
 * BuildOrderComponent на него и тот стоит в радиусе — если строителя
 * уничтожить или дать ему другой приказ (MoveUnitRequest/AttackUnitRequest
 * снимают BuildOrderComponent, как и положено новому приказу отменять
 * старый), время просто перестаёт идти, никакая другая система его не
 * подхватывает.
 *
 * Если строителей на одно здание несколько и все в радиусе одновременно —
 * каждый вносит свой вклад отдельно за тот же тик, стройка идёт быстрее
 * пропорционально их числу. Не мешает намеренно — это разумное поведение
 * для RTS, а не то, о чём отдельно просили ограничить.
 *
 * Приоритет 1 — сразу после CombatSystem (0), до ProductionSystem (2) и
 * ConstructionSystem (3): направление погони, выставленное здесь, должно
 * успеть попасть в тот же тик MovementSystem (10), а remaining должно
 * уменьшиться раньше, чем ConstructionSystem в этом же тике проверит,
 * не пора ли считать стройку завершённой.
 *
 * Живёт в shared (как и CombatSystem), но реально используется только
 * сервером.
 */
public class BuildSystem extends IteratingSystem {

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<DirectionComponent> DIRECTION =
            ComponentMapper.getFor(DirectionComponent.class);
    private static final ComponentMapper<BuildOrderComponent> BUILD_ORDER =
            ComponentMapper.getFor(BuildOrderComponent.class);
    private static final ComponentMapper<UnitTypeComponent> UNIT_TYPE =
            ComponentMapper.getFor(UnitTypeComponent.class);

    /** Тот же реестр unitId -> Entity, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, Entity> unitsById;

    /** Переиспользуемый вектор для точки подхода — не аллоцируем новый каждый тик на каждого строителя. */
    private final Vector2 approachPoint = new Vector2();

    public BuildSystem(Map<Integer, Entity> unitsById) {
        super(Family.all(BuildOrderComponent.class, PositionComponent.class, DirectionComponent.class).get(), 1);
        this.unitsById = unitsById;
    }

    @Override
    protected void processEntity(Entity builder, float deltaTime) {
        BuildOrderComponent order = BUILD_ORDER.get(builder);
        Entity target = unitsById.get(order.targetBuildingUnitId);
        ConstructionComponent construction = target != null ? target.getComponent(ConstructionComponent.class) : null;

        if (target == null || construction == null) {
            // Здание разрушено, или стройка уже завершилась (например,
            // другой строитель успел её докончить первым) — приказ больше
            // не актуален.
            builder.remove(BuildOrderComponent.class);
            DIRECTION.get(builder).moving = false;
            return;
        }

        UnitTypeComponent builderTypeComponent = UNIT_TYPE.get(builder);
        UnitType builderType = builderTypeComponent != null ? builderTypeComponent.type : UnitType.BUILDER;
        float buildRange = UnitDefinitions.buildRadiusFor(builderType);

        PositionComponent myPosition = POSITION.get(builder);
        PositionComponent targetPosition = POSITION.get(target);
        DirectionComponent direction = DIRECTION.get(builder);

        float distance = myPosition.position.dst(targetPosition.position);

        if (distance > buildRange) {
            // Та же логика точки подхода, что и в CombatSystem — см. её
            // комментарий про PATH_CLEARANCE/BUILDING_HALF_SIZE, почему
            // buildRadius обязан быть больше самой раздутой половины
            // здания, иначе точка подхода попадёт в заблокированную зону
            // и Pathfinding её проигнорирует.
            approachPoint.set(myPosition.position).sub(targetPosition.position).nor()
                    .scl(buildRange).add(targetPosition.position);
            Pathfinding.setDestination(builder, myPosition, direction, approachPoint.x, approachPoint.y);
            return;
        }

        // В радиусе — останавливаемся и строим.
        direction.moving = false;
        construction.remaining -= deltaTime;
    }
}
