package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.EntitySystem;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.utils.ImmutableArray;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.BuildOrderComponent;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
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
        // Зажимаем оставшимся временем стройки, а не просто deltaTime *
        // speedMultiplier — иначе на последнем тике списывалась бы
        // стоимость ЦЕЛОГО тика, даже если реально доделать осталось
        // меньше. При впритык хватающих ресурсах (например, стартовый
        // запас — ровно на одну шахту) это могло попросить чуть больше,
        // чем нужно было для завершения, и стройка зависала на этом самом
        // последнем тике навсегда — resources и remaining оба чуть-чуть не
        // дотягивали, но и не двигались с места ни на следующем тике,
        // ни через тик после него.
        float progressThisTick = Math.min(deltaTime * speedMultiplier, construction.remaining);

        BuildingComponent buildingMarker = target.getComponent(BuildingComponent.class);
        BuildingType buildingType = buildingMarker != null ? buildingMarker.type : BuildingType.HOME;
        int totalIronCost = BuildingDefinitions.ironCostFor(buildingType);
        int totalElectricityCost = BuildingDefinitions.electricityCostFor(buildingType);

        if (totalIronCost > 0 || totalElectricityCost > 0) {
            OwnerComponent owner = target.getComponent(OwnerComponent.class);
            PlayerResources resources = owner != null ? resourcesByPlayer.get(owner.playerId) : null;

            float tickIron = totalIronCost * progressThisTick / construction.totalTime;
            float tickElectricity = totalElectricityCost * progressThisTick / construction.totalTime;

            if (resources == null || resources.iron < tickIron - GameConstants.RESOURCE_EPSILON
                    || resources.electricity < tickElectricity - GameConstants.RESOURCE_EPSILON) {
                return; // не хватает ресурсов на этот тик — ждём, прогресс не растёт, но и не теряется
            }
            resources.iron -= tickIron;
            resources.electricity -= tickElectricity;
        }

        construction.remaining -= progressThisTick;

        // Здоровье растёт вместе с прогрессом стройки — от почти нуля
        // (см. GameServer.spawnBuilding, где оно и стартует) до полного,
        // ровно к моменту завершения. Обновляем именно тут, там же, где
        // реально двигается remaining — значит, здоровье тоже не растёт
        // само по себе, только пока строитель действительно работает.
        HealthComponent health = target.getComponent(HealthComponent.class);
        if (health != null) {
            float progressFraction = MathUtils.clamp(1f - construction.remaining / construction.totalTime, 0f, 1f);
            health.currentHealth = Math.max(1, Math.round(health.maxHealth * progressFraction));
        }
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
            // Точка подхода считается ОДИН раз (пока hasApproachPoint
            // false — первый тик, когда стало ясно, что строитель ещё не
            // в радиусе) и дальше переиспользуется, а НЕ пересчитывается
            // заново каждый тик от текущей (постоянно меняющейся по мере
            // движения) позиции — здание, в отличие от цели атаки в
            // CombatSystem, никогда не двигается, пересчитывать некуда.
            //
            // Пересчёт каждый тик был реальным багом: направление
            // "откуда идёт строитель" чуть-чуть меняется на каждом шаге
            // движения, и пересчитанная от него точка подхода иногда
            // попадала в СОСЕДНЮЮ клетку сетки A* по сравнению с
            // предыдущим тиком. Pathfinding.setDestination считает
            // условием пропустить пересчёт пути именно "цель — та же
            // клетка, что и раньше"; при таком дрожании между двумя
            // соседними клетками строитель мог застрять, гоняясь за
            // постоянно чуть смещающейся точкой и ни разу не попав в ту
            // же клетку дважды подряд — особенно заметно при подходе по
            // диагонали (из угла здания), где чувствительность
            // направления к малейшему смещению позиции выше всего.
            //
            // Та же логика самой точки, что и в CombatSystem — см. её
            // комментарий про PATH_CLEARANCE/BUILDING_HALF_SIZE, почему
            // buildRadius обязан быть больше самой раздутой половины
            // здания, иначе точка подхода попадёт в заблокированную зону
            // и Pathfinding её проигнорирует.
            if (!order.hasApproachPoint) {
                approachPoint.set(myPosition.position).sub(targetPosition.position).nor()
                        .scl(buildRange).add(targetPosition.position);
                order.hasApproachPoint = true;
                order.approachX = approachPoint.x;
                order.approachY = approachPoint.y;
            }
            Pathfinding.setDestination(builder, myPosition, direction, order.approachX, order.approachY);
            return;
        }

        // В радиусе — останавливаемся и засчитываемся в подсчёт для
        // второго прохода. Сбрасываем точку подхода — если строителя
        // потом что-то отбросит от здания (например, атакующий соседний
        // юнит случайно задел), подход нужно будет посчитать заново от
        // новой позиции, а не тащить устаревшую точку с прошлого захода.
        order.hasApproachPoint = false;
        direction.moving = false;
        order.inRange = true;
        buildersInRangeByTarget.merge(order.targetBuildingUnitId, 1, Integer::sum);
    }
}
