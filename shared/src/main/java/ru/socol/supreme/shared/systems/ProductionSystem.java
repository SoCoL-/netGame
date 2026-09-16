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
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.Map;

/**
 * Двигает очередь производства юнитов у зданий, и заодно — непрерывное
 * потребление ресурсов самим зданием (пока — только у казармы стрелков,
 * см. BuildingDefinitions.consumesResourceTypeFor): оба процесса завязаны
 * на одно и то же состояние (production.queue), так что держать их в
 * разных системах означало бы дважды спрашивать "простаивает здание или
 * работает".
 *
 * Очередь — список типов юнита (не счётчик одного фиксированного типа):
 * здание может уметь производить несколько разных типов сразу (сейчас —
 * только дом, воин и строитель), и строится в данный момент именно
 * первый элемент очереди, FIFO.
 *
 * Потребление здания безусловно — идёт всегда, пока здание существует,
 * по ставке idleConsumptionRateFor (очередь пуста) или
 * activeConsumptionRateFor (очередь не пуста), не зависит от того,
 * хватает ли ресурсов на самого производимого юнита. Ресурс не уходит в
 * минус — при нехватке зажимается на 0, не блокирует ничего другого.
 *
 * Стоимость самого юнита (UnitDefinitions.ironCostFor/electricityCostFor,
 * по типу ПЕРВОГО элемента очереди) списывается РАВНОМЕРНО за
 * UNIT_BUILD_TIME, а не разом: за один тик длительности deltaTime
 * тратится cost * deltaTime / UNIT_BUILD_TIME каждого ресурса — так что
 * за весь UNIT_BUILD_TIME спишется ровно cost. Если на очередной тик не
 * хватает ХОТЯ БЫ ОДНОГО из двух — прогресс просто не растёт (как и при
 * нехватке места под юнита, MAX_TOTAL_UNITS), а не уходит в минус и не
 * теряется.
 *
 * Фактическое создание сущности юнита делегируется обратно в GameServer
 * через UnitFactory — у этой системы (как и у всех shared-систем) нет
 * доступа к unitIdSequence/PooledEngine.createComponent для конкретно
 * GameServer'а, а дублировать логику создания юнита здесь означало бы
 * держать два места, которые легко могут разойтись. UnitFactory
 * возвращает созданную сущность именно для точки сбора — если у
 * ProductionComponent.hasRallyPoint выставлен, свежесозданный юнит сразу
 * получает Pathfinding.setDestination к ней, тем же путём, каким сервер
 * обрабатывает обычный ручной приказ на движение.
 *
 * Приоритет 2 — после BuildSystem (1), до ConstructionSystem (3).
 *
 * Работает только на сервере, как и остальные gameplay-системы.
 */
public class ProductionSystem extends IteratingSystem {

    /** Единственная точка создания юнита — реализует GameServer, у которого есть unitIdSequence/engine. Возвращает созданную сущность — нужна ProductionSystem, чтобы сразу отправить юнита к точке сбора, если она задана. */
    public interface UnitFactory {
        Entity createUnit(int playerId, float x, float y, UnitType type);
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
                PositionComponent.class, OwnerComponent.class).get(), 2);
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
            boolean active = !production.queue.isEmpty();
            float rate = active
                    ? BuildingDefinitions.activeConsumptionRateFor(buildingType)
                    : BuildingDefinitions.idleConsumptionRateFor(buildingType);
            drain(resources, consumedType, rate * deltaTime);
        }

        if (production.queue.isEmpty()) {
            return;
        }

        if (unitsById.size() >= GameConstants.MAX_TOTAL_UNITS) {
            return; // лимит юнитов исчерпан — ждём, прогресс не растёт, но и не теряется
        }

        UnitType buildingUnitType = production.queue.get(0); // первый в очереди — тот, что строится сейчас

        // Зажимаем оставшимся временем постройки, а не просто deltaTime —
        // та же причина, что и в BuildSystem у стоимости здания (см. её
        // комментарий): иначе на последнем тике списывалась бы стоимость
        // ЦЕЛОГО тика, даже если доделать осталось меньше, и при впритык
        // хватающих ресурсах постройка юнита зависала бы на этом самом
        // тике навсегда.
        float progressThisTick = Math.min(deltaTime, GameConstants.UNIT_BUILD_TIME - production.progress);

        float tickIron = UnitDefinitions.ironCostFor(buildingUnitType) * progressThisTick / GameConstants.UNIT_BUILD_TIME;
        float tickElectricity = UnitDefinitions.electricityCostFor(buildingUnitType) * progressThisTick / GameConstants.UNIT_BUILD_TIME;

        if (resources == null || resources.iron < tickIron - GameConstants.RESOURCE_EPSILON
                || resources.electricity < tickElectricity - GameConstants.RESOURCE_EPSILON) {
            return; // не хватает ресурсов на этот тик — ждём, прогресс не растёт, но и не теряется
        }
        resources.iron -= tickIron;
        resources.electricity -= tickElectricity;

        production.progress += progressThisTick;
        if (production.progress < GameConstants.UNIT_BUILD_TIME) {
            return;
        }

        PositionComponent position = POSITION.get(entity);
        Vector2 spawnPoint = computeSpawnPoint(position.position, buildingType);
        Entity newUnit = unitFactory.createUnit(owner.playerId, spawnPoint.x, spawnPoint.y, buildingUnitType);

        if (production.hasRallyPoint) {
            Pathfinding.setDestination(newUnit, newUnit.getComponent(PositionComponent.class),
                    newUnit.getComponent(DirectionComponent.class), production.rallyX, production.rallyY);
        }

        production.queue.remove(0);
        production.progress = 0f;
    }

    /** Точка появления готового юнита — чуть в стороне от здания, по направлению к центру карты. */
    /** Точка появления готового юнита — чуть в стороне от здания, по направлению к центру карты (BuildingDefinitions.spawnPointNear — та же формула для начального строителя, GameServer.spawnHomeAndBuilder). */
    private Vector2 computeSpawnPoint(Vector2 buildingPosition, BuildingType buildingType) {
        return BuildingDefinitions.spawnPointNear(buildingType, buildingPosition);
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
