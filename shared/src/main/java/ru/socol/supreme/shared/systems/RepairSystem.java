package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.EntitySystem;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.utils.ImmutableArray;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.RepairComponent;
import ru.socol.supreme.shared.components.RepairOrderComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.HashMap;
import java.util.Map;

/**
 * Обрабатывает приказы "чинить это здание" — устроена зеркально
 * BuildSystem (стройка), только цель другая: там ConstructionComponent
 * ещё строящегося здания, тут RepairComponent уже готового, но
 * повреждённого (см. её javadoc и GameServer.assignBuilderToRepair, где
 * компонент заводится и его стоимость/время фиксируются один раз). Пока
 * цель дальше buildRadius строителя — юнит идёт к ней; как только в
 * радиусе — останавливается (RepairOrderComponent.inRange = true) и
 * реально чинит. Если цель разрушена или ремонт уже завершился как-то
 * иначе (RepairComponent снят) — приказ снимается сам, юнит
 * останавливается. Ровно тот же принцип "не чинится само" — remaining
 * уменьшает ТОЛЬКО эта система и только пока рядом активно работает
 * строитель.
 *
 * Несколько строителей на одно здание — тот же приём в два прохода, что
 * и в BuildSystem (см. её подробный javadoc): первый решает у каждого
 * строителя "в пути или на месте", второй уже одним общим множителем
 * скорости (GameConstants.EXTRA_BUILDER_SPEED_BONUS — та же константа,
 * что и у стройки) продвигает саму цель.
 *
 * В отличие от ConstructionSystem, отдельной системы, завершающей ремонт
 * тиком позже, тут нет — RepairComponent снимается прямо в
 * advanceRepair, как только remaining дошёл до 0: ремонтируемое здание
 * и так всё это время полностью работоспособно (ProductionComponent/
 * ResourceExtractorComponent уже есть), в отличие от ещё строящегося,
 * добавлять по завершении просто нечего.
 *
 * Приоритет 1 — тот же, что и у BuildSystem: обе системы читают/пишут
 * непересекающиеся множества сущностей (строитель либо строит, либо
 * чинит, никогда оба сразу — см. javadoc RepairOrderComponent), так что
 * порядок между ними не важен, а важно лишь то же самое, что и для
 * стройки — успеть до MovementSystem (10) в этом же тике.
 *
 * Живёт в shared (как и BuildSystem), но реально используется только
 * сервером.
 */
public class RepairSystem extends EntitySystem {

    private static final Family FAMILY =
            Family.all(RepairOrderComponent.class, PositionComponent.class, DirectionComponent.class).get();

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<DirectionComponent> DIRECTION =
            ComponentMapper.getFor(DirectionComponent.class);
    private static final ComponentMapper<RepairOrderComponent> REPAIR_ORDER =
            ComponentMapper.getFor(RepairOrderComponent.class);
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

    /** Поиск пути этого матча — см. javadoc Pathfinding, почему не статический. */
    private final Pathfinding pathfinding;

    public RepairSystem(Map<Integer, Entity> unitsById, Map<Integer, PlayerResources> resourcesByPlayer,
                        Pathfinding pathfinding) {
        super(1);
        this.pathfinding = pathfinding;
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

        for (Entity builder : builders) {
            updateBuilder(builder);
        }

        for (Map.Entry<Integer, Integer> entry : buildersInRangeByTarget.entrySet()) {
            advanceRepair(entry.getKey(), entry.getValue(), deltaTime);
        }
    }

    /** Продвигает ремонт ОДНОГО здания на этот тик — тот же приём, что и BuildSystem.advanceConstruction, см. её подробный комментарий про зажим прогресса и почему. */
    private void advanceRepair(int targetBuildingUnitId, int builderCount, float deltaTime) {
        Entity target = unitsById.get(targetBuildingUnitId);
        RepairComponent repair = target != null ? target.getComponent(RepairComponent.class) : null;
        if (repair == null) {
            return; // не должно происходить — цель уже проверена в updateBuilder — но на всякий случай не падаем
        }

        float speedMultiplier = 1f + (builderCount - 1) * GameConstants.EXTRA_BUILDER_SPEED_BONUS;
        float progressThisTick = Math.min(deltaTime * speedMultiplier, repair.remaining);

        if (repair.totalIronCost > 0 || GameConstants.REPAIR_ELECTRICITY_COST > 0) {
            OwnerComponent owner = target.getComponent(OwnerComponent.class);
            PlayerResources resources = owner != null ? resourcesByPlayer.get(owner.playerId) : null;

            float tickIron = repair.totalIronCost * progressThisTick / repair.totalTime;
            float tickElectricity = GameConstants.REPAIR_ELECTRICITY_COST * progressThisTick / repair.totalTime;

            if (resources == null || resources.iron < tickIron - GameConstants.RESOURCE_EPSILON
                    || resources.electricity < tickElectricity - GameConstants.RESOURCE_EPSILON) {
                return; // не хватает ресурсов на этот тик — ждём, прогресс не растёт, но и не теряется
            }
            resources.iron -= tickIron;
            resources.electricity -= tickElectricity;
        }

        repair.remaining -= progressThisTick;

        HealthComponent health = target.getComponent(HealthComponent.class);
        if (health != null) {
            float progressFraction = MathUtils.clamp(1f - repair.remaining / repair.totalTime, 0f, 1f);
            health.currentHealth = Math.round(repair.startHealth
                    + (health.maxHealth - repair.startHealth) * progressFraction);
        }

        if (repair.remaining <= 0f) {
            // Ремонт завершён прямо в этом тике — в отличие от стройки, тут
            // не нужна отдельная система на следующий тик (см. javadoc
            // класса, почему): здание и так уже полностью рабочее, снимаем
            // компонент и на всякий случай (защита от накопленной ошибки
            // округления в progressFraction выше) выставляем здоровье
            // ТОЧНО в maxHealth, а не в "почти максимум".
            if (health != null) {
                health.currentHealth = health.maxHealth;
            }
            target.remove(RepairComponent.class);
        }
    }

    /** Решает для одного строителя: цель ещё актуальна? Идти к ней или уже на месте? Тот же приём, что и BuildSystem.updateBuilder — см. её подробный javadoc про точку подхода и почему она считается лениво, не каждый тик. */
    private void updateBuilder(Entity builder) {
        RepairOrderComponent order = REPAIR_ORDER.get(builder);
        Entity target = unitsById.get(order.targetBuildingUnitId);
        RepairComponent repair = target != null ? target.getComponent(RepairComponent.class) : null;

        if (target == null || repair == null) {
            builder.remove(RepairOrderComponent.class);
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
            // Пересчитываем точку подхода не только лениво (первый раз),
            // но и если строитель уже "дошёл" до старой (moving стало
            // false), а дистанция всё ещё больше buildRange — та же
            // защита от зависания и тот же отступ на ARRIVE_THRESHOLD
            // внутрь границы, что и в BuildSystem.updateBuilder; см. её
            // подробный javadoc про то, почему точка подхода считается
            // именно так (округление float у самой границы buildRange
            // могло навсегда застревать строителя в сотых долях юнита от
            // цели, не проходя проверку distance > buildRange).
            if (!order.hasApproachPoint || !direction.moving) {
                float approachDistance = Math.max(0f, buildRange - GameConstants.ARRIVE_THRESHOLD);
                approachPoint.set(myPosition.position).sub(targetPosition.position).nor()
                        .scl(approachDistance).add(targetPosition.position);
                order.hasApproachPoint = true;
                order.approachX = approachPoint.x;
                order.approachY = approachPoint.y;
            }
            pathfinding.setDestination(builder, myPosition, direction, order.approachX, order.approachY);
            return;
        }

        order.hasApproachPoint = false;
        direction.moving = false;
        order.inRange = true;
        buildersInRangeByTarget.merge(order.targetBuildingUnitId, 1, Integer::sum);
    }
}
