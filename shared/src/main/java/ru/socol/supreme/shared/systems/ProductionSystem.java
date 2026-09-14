package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;

import java.util.Map;

/**
 * Двигает очередь производства юнитов у зданий: пока в очереди есть хотя бы
 * один юнит, копит прогресс постройки текущего; по достижении
 * UNIT_BUILD_TIME — создаёт юнита рядом со зданием и снимает его из
 * очереди. Если общий лимит юнитов (MAX_TOTAL_UNITS) уже исчерпан —
 * прогресс просто перестаёт расти (не пропадает, не переполняется), пока
 * не освободится место.
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
    private final UnitFactory unitFactory;

    public ProductionSystem(Map<Integer, Entity> unitsById, UnitFactory unitFactory) {
        super(Family.all(BuildingComponent.class, ProductionComponent.class,
                PositionComponent.class, OwnerComponent.class).get(), 1);
        this.unitsById = unitsById;
        this.unitFactory = unitFactory;
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        ProductionComponent production = PRODUCTION.get(entity);
        if (production.queuedCount <= 0) {
            return;
        }

        if (unitsById.size() >= GameConstants.MAX_TOTAL_UNITS) {
            return; // лимит юнитов исчерпан — ждём, прогресс не растёт, но и не теряется
        }

        production.progress += deltaTime;
        if (production.progress < GameConstants.UNIT_BUILD_TIME) {
            return;
        }

        PositionComponent position = POSITION.get(entity);
        OwnerComponent owner = OWNER.get(entity);
        Vector2 spawnPoint = computeSpawnPoint(position.position, BUILDING.get(entity).type);
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
}
