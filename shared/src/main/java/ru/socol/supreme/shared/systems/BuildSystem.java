package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.EntitySystem;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.utils.ImmutableArray;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.BuildOrderComponent;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.HashMap;
import java.util.Map;

/**
 * Обрабатывает приказы "строить это здание": пока цель дальше
 * buildRadius строителя (UnitDefinitions.buildRadiusFor) — юнит идёт к
 * ней (перезаписывая DirectionComponent, тем же приёмом, что и
 * CombatSystem при погоне за целью атаки); как только в радиусе —
 * останавливается (BuildOrderComponent.inRange = true) и реально вносит
 * вклад в стройку. Если цель разрушена, или стройка уже завершилась
 * как-то иначе (ConstructionComponent снят) — приказ снимается сам, юнит
 * останавливается.
 *
 * Именно поэтому здание "не достраивается само": remaining уменьшает
 * ТОЛЬКО эта система, и только пока у какого-то строителя есть активный
 * BuildOrderComponent на него и тот стоит в радиусе — если строителя
 * уничтожить или дать ему другой приказ (MoveUnitRequest/AttackUnitRequest
 * снимают BuildOrderComponent, как и положено новому приказу отменять
 * старый), время просто перестаёт идти, никакая другая система его не
 * подхватывает.
 *
 * Несколько строителей на одно здание работают НЕ линейно: не extends
 * IteratingSystem с независимым processEntity на каждого (так было бы
 * по 100% скорости с каждого — вместо этого явный update() в два прохода:
 * первый решает у КАЖДОГО строителя "в пути или на месте" и, если на
 * месте, добавляет его в подсчёт buildersInRangeByTarget; второй, уже
 * зная итоговое число строителей на каждую цель, применяет к ней ОДИН
 * общий множитель скорости разом — первый строитель даёт 100% (обычная
 * скорость), КАЖДЫЙ следующий добавляет ещё EXTRA_BUILDER_SPEED_BONUS
 * (10%), а не ещё 100%.
 *
 * Стоимость здания (BuildingDefinitions.ironCostFor/electricityCostFor)
 * списывается РАВНОМЕРНО за время постройки, тем же приёмом, каким уже
 * тратится стоимость юнита в ProductionSystem, а не разом при
 * подтверждении размещения, как было раньше: за прогресс progressThisTick
 * (deltaTime * speedMultiplier — быстрее строят несколько строителей,
 * быстрее и тратятся ресурсы, но суммарно к завершению уйдёт ровно
 * ironCostFor/electricityCostFor, независимо от их числа, см. её же
 * комментарий выше) тратится ironCost * progressThisTick /
 * construction.totalTime каждого ресурса. Если на очередной тик не
 * хватает ХОТЯ БЫ ОДНОГО из двух — remaining в этот тик не уменьшается
 * вовсе (как и у юнита — прогресс просто не растёт, а не уходит в минус
 * и не теряется); сама постройка при этом уже стоит и НЕ проверяется на
 * старте — GameServer.handlePlaceIronMine/handlePlaceBuilding больше не
 * отклоняют размещение из-за нехватки ресурсов, здание просто появится и
 * будет ждать, как и юнит в очереди с недостающими ресурсами.
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
public class BuildSystem extends EntitySystem {

    private static final Family FAMILY =
            Family.all(BuildOrderComponent.class, PositionComponent.class, DirectionComponent.class).get();

    /** Первый строитель на здании — 100% (обычная скорость, множитель 1.0). Каждый следующий добавляет ещё столько. */
    private static final float EXTRA_BUILDER_SPEED_BONUS = 0.1f;

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

    /** Тот же реестр playerId -> PlayerResources, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, PlayerResources> resourcesByPlayer;

    /** Переиспользуемый вектор для точки подхода — не аллоцируем новый каждый тик на каждого строителя. */
    private final Vector2 approachPoint = new Vector2();

    /** targetBuildingUnitId -> сколько строителей сейчас в радиусе этой цели — пересчитывается заново в начале каждого update(), не накапливается между тиками. */
    private final Map<Integer, Integer> buildersInRangeByTarget = new HashMap<>();

    private ImmutableArray<Entity> builders;

    public BuildSystem(Map<Integer, Entity> unitsById, Map<Integer, PlayerResources> resourcesByPlayer) {
        super(1);
        this.unitsById = unitsById;
        this.resourcesByPlayer = resourcesByPlayer;
    }

    @Override
    public void addedToEngine(Engine engine) {
        builders = engine.getEntitiesFor(FAMILY);
    }

    @Override
    public void update(float deltaTime) {
        buildersInRangeByTarget.clear();

        // Первый проход — у каждого строителя решаем "в пути или на месте".
        for (Entity builder : builders) {
            updateBuilder(builder);
        }

        // Второй проход — зная итоговое число строителей на каждую цель,
        // применяем к ней ОДИН общий множитель скорости разом, списывая
        // долю стоимости здания, пропорциональную сделанному за этот тик
        // прогрессу.
        for (Map.Entry<Integer, Integer> entry : buildersInRangeByTarget.entrySet()) {
            advanceConstruction(entry.getKey(), entry.getValue(), deltaTime);
        }
    }

    /** Продвигает стройку ОДНОГО здания на этот тик — общий множитель скорости и списание стоимости, пропорциональное сделанному прогрессу. */
    private void advanceConstruction(int targetBuildingUnitId, int builderCount, float deltaTime) {
        Entity target = unitsById.get(targetBuildingUnitId);
        ConstructionComponent construction = target != null ? target.getComponent(ConstructionComponent.class) : null;
        if (construction == null) {
            return; // не должно происходить — цель уже проверена в updateBuilder — но на всякий случай не падаем
        }

        float speedMultiplier = 1f + (builderCount - 1) * EXTRA_BUILDER_SPEED_BONUS;
        float progressThisTick = deltaTime * speedMultiplier;

        BuildingComponent buildingMarker = target.getComponent(BuildingComponent.class);
        BuildingType buildingType = buildingMarker != null ? buildingMarker.type : BuildingType.HOME;
        int totalIronCost = BuildingDefinitions.ironCostFor(buildingType);
        int totalElectricityCost = BuildingDefinitions.electricityCostFor(buildingType);

        if (totalIronCost > 0 || totalElectricityCost > 0) {
            OwnerComponent owner = target.getComponent(OwnerComponent.class);
            PlayerResources resources = owner != null ? resourcesByPlayer.get(owner.playerId) : null;

            float tickIron = totalIronCost * progressThisTick / construction.totalTime;
            float tickElectricity = totalElectricityCost * progressThisTick / construction.totalTime;

            if (resources == null || resources.iron < tickIron || resources.electricity < tickElectricity) {
                return; // не хватает ресурсов на этот тик — ждём, прогресс не растёт, но и не теряется
            }
            resources.iron -= tickIron;
            resources.electricity -= tickElectricity;
        }

        construction.remaining -= progressThisTick;
    }

    /** Решает для одного строителя: цель ещё актуальна? Идти к ней или уже на месте? Если на месте — засчитывает его в buildersInRangeByTarget, саму скорость стройки тут не трогает. */
    private void updateBuilder(Entity builder) {
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
            order.inRange = false;
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

        // В радиусе — останавливаемся и засчитываемся в подсчёт для второго прохода.
        direction.moving = false;
        order.inRange = true;
        buildersInRangeByTarget.merge(order.targetBuildingUnitId, 1, Integer::sum);
    }
}
