package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.ResourceType;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.network.messages.PlayerResources;

import java.util.Map;

/**
 * Двигает очередь производства юнитов у зданий, и заодно — непрерывное
 * потребление ресурсов самим зданием (пока — только у казармы стрелков,
 * см. BuildingDefinitions.consumesResourceTypeFor): оба процесса завязаны
 * на одно и то же состояние (production.queuedCount), так что держать их
 * в разных системах означало бы дважды спрашивать "простаивает здание
 * или работает".
 *
 * Потребление здания безусловно — идёт всегда, пока здание существует,
 * по ставке idleConsumptionRateFor (очередь пуста) или
 * activeConsumptionRateFor (очередь не пуста), не зависит от того,
 * хватает ли ресурсов на самого производимого юнита. Ресурс не уходит в
 * минус — при нехватке зажимается на 0, не блокирует ничего другого.
 *
 * Стоимость самого юнита (UnitDefinitions.ironCostFor/electricityCostFor)
 * списывается РАВНОМЕРНО за UNIT_BUILD_TIME, а не разом: за один тик
 * длительности deltaTime тратится cost * deltaTime / UNIT_BUILD_TIME
 * каждого ресурса — так что за весь UNIT_BUILD_TIME спишется ровно cost.
 * Если на очередной тик не хватает ХОТЯ БЫ ОДНОГО из двух — прогресс
 * просто не растёт (как и при нехватке места под юнита, MAX_TOTAL_UNITS),
 * а не уходит в минус и не теряется.
 *
 * Фактическое создание сущности юнита делегируется обратно в GameServer
 * через UnitFactory — у этой системы (как и у всех shared-систем) нет
 * доступа к unitIdSequence/PooledEngine.createComponent для конкретно
 * GameServer'а, а дублировать логику создания юнита здесь означало бы
 * держать два места, которые легко могут разойтись.
 *
 * Работает только на сервере, как и остальные gameplay-системы.
 */
public class ProductionSystem extends IteratingSystem {

    /** Единственная точка создания юнита — реализует GameServer, у которого есть unitIdSequence/engine. */
    public interface UnitFactory {
        void createUnit(int playerId, float x, float y, UnitType type);
    }

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<OwnerComponent> OWNER =
            ComponentMapper.getFor(OwnerComponent.class);
    private static final ComponentMapper<ProductionComponent> PRODUCTION =
            ComponentMapper.getFor(ProductionComponent.class);
    private static final ComponentMapper<BuildingComponent> BUILDING =
            ComponentMapper.getFor(BuildingComponent.class);

    private final Map<Integer, Entity> unitsById;
    private final Map<Integer, PlayerResources> resourcesByPlayer;
    private final UnitFactory unitFactory;

    public ProductionSystem(Map<Integer, Entity> unitsById, Map<Integer, PlayerResources> resourcesByPlayer, UnitFactory unitFactory) {
        super(Family.all(BuildingComponent.class, ProductionComponent.class,
                PositionComponent.class, OwnerComponent.class).get(), 1);
        this.unitsById = unitsById;
        this.resourcesByPlayer = resourcesByPlayer;
        this.unitFactory = unitFactory;
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        ProductionComponent production = PRODUCTION.get(entity);
        BuildingType buildingType = BUILDING.get(entity).type;
        OwnerComponent owner = OWNER.get(entity);
        PlayerResources resources = resourcesByPlayer.get(owner.playerId);

        ResourceType consumedType = BuildingDefinitions.consumesResourceTypeFor(buildingType);
        if (consumedType != null && resources != null) {
            boolean active = production.queuedCount > 0;
            float rate = active
                    ? BuildingDefinitions.activeConsumptionRateFor(buildingType)
                    : BuildingDefinitions.idleConsumptionRateFor(buildingType);
            drain(resources, consumedType, rate * deltaTime);
        }

        if (production.queuedCount <= 0) {
            return;
        }

        if (unitsById.size() >= GameConstants.MAX_TOTAL_UNITS) {
            return; // лимит юнитов исчерпан — ждём, прогресс не растёт, но и не теряется
        }

        float tickIron = UnitDefinitions.ironCostFor(production.producesUnitType) * deltaTime / GameConstants.UNIT_BUILD_TIME;
        float tickElectricity = UnitDefinitions.electricityCostFor(production.producesUnitType) * deltaTime / GameConstants.UNIT_BUILD_TIME;

        if (resources == null || resources.iron < tickIron || resources.electricity < tickElectricity) {
            return; // не хватает ресурсов на этот тик — ждём, прогресс не растёт, но и не теряется
        }
        resources.iron -= tickIron;
        resources.electricity -= tickElectricity;

        production.progress += deltaTime;
        if (production.progress < GameConstants.UNIT_BUILD_TIME) {
            return;
        }

        PositionComponent position = POSITION.get(entity);
        Vector2 spawnPoint = computeSpawnPoint(position.position, buildingType);
        unitFactory.createUnit(owner.playerId, spawnPoint.x, spawnPoint.y, production.producesUnitType);

        production.queuedCount--;
        production.progress = 0f;
    }

    /** Точка появления готового юнита — чуть в стороне от здания, по направлению к центру карты. */
    private Vector2 computeSpawnPoint(Vector2 buildingPosition, BuildingType buildingType) {
        Vector2 towardCenter = new Vector2(GameConstants.MAP_WIDTH / 2f, GameConstants.MAP_HEIGHT / 2f)
                .sub(buildingPosition)
                .nor();
        return new Vector2(buildingPosition).mulAdd(towardCenter, BuildingDefinitions.productionSpawnDistanceFor(buildingType));
    }

    /** Безусловное потребление здания — не уходит в минус, зажимается на 0. */
    private void drain(PlayerResources resources, ResourceType type, float amount) {
        switch (type) {
            case IRON:
                resources.iron = Math.max(0f, resources.iron - amount);
                break;
            case ELECTRICITY:
                resources.electricity = Math.max(0f, resources.electricity - amount);
                break;
        }
    }
}
