package ru.socol.supreme;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.ResourceType;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.components.DebugPathComponent;
import ru.socol.supreme.components.InterpolationComponent;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;
import ru.socol.supreme.shared.components.UnitComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.network.messages.PathPoint;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Синхронизирует клиентский Ashley-движок с последним WorldSnapshot от
 * сервера: создаёт сущности для новых юнитов/зданий, обновляет здоровье и
 * цель интерполяции у существующих, и удаляет то, чего больше нет в
 * снапшоте (юнит умер в бою, здание разрушено, игрок отключился).
 *
 * Сама позиция (PositionComponent.position) больше не устанавливается тут
 * напрямую для юнитов — только InterpolationComponent.targetPosition.
 * Фактическое движение к этой цели каждый кадр делает InterpolationSystem.
 * Здания неподвижны, поэтому InterpolationComponent им вообще не выдаётся —
 * их позиция один раз выставляется при создании и больше не трогается.
 */
public class EntityFactory {

    private final Engine engine;
    private final Map<Integer, Entity> entitiesByUnitId = new HashMap<>();

    public EntityFactory(Engine engine) {
        this.engine = engine;
    }

    public void applySnapshot(Iterable<UnitSnapshot> units) {
        Set<Integer> seenUnitIds = new HashSet<>();

        for (UnitSnapshot snapshot : units) {
            seenUnitIds.add(snapshot.unitId);

            Entity entity = entitiesByUnitId.get(snapshot.unitId);
            if (entity == null) {
                entity = createEntity(snapshot);
                entitiesByUnitId.put(snapshot.unitId, entity);
                engine.addEntity(entity);
                continue;
            }

            entity.getComponent(HealthComponent.class).currentHealth = snapshot.health;

            ProductionComponent production = entity.getComponent(ProductionComponent.class);
            if (production != null) {
                production.queuedCount = snapshot.queuedCount;
                production.progress = snapshot.buildProgress;
                // producesUnitType не меняется у здания после создания — обновлять не нужно.
            }

            updateResourceBuildingState(entity, snapshot);

            InterpolationComponent interpolation = entity.getComponent(InterpolationComponent.class);
            if (interpolation != null) {
                // Точка отправления лерпа — там, где юнит нарисован ПРЯМО
                // СЕЙЧАС (а не старая цель), иначе при приходе снапшота
                // будет заметный скачок. У зданий этого компонента нет —
                // им обновлять нечего, они не двигаются.
                interpolation.previousPosition.set(entity.getComponent(PositionComponent.class).position);
                interpolation.targetPosition.set(snapshot.x, snapshot.y);
                interpolation.elapsed = 0f;
            }

            DebugPathComponent debugPath = entity.getComponent(DebugPathComponent.class);
            if (debugPath != null) {
                // Всегда перезаписываем целиком, включая пустой список —
                // именно так с экрана пропадает линия маршрута, когда юнит
                // доходит до цели и сервер перестаёт присылать точки.
                debugPath.points.clear();
                for (PathPoint point : snapshot.pathPoints) {
                    debugPath.points.add(new Vector2(point.x, point.y));
                }
            }
        }

        entitiesByUnitId.entrySet().removeIf(entry -> {
            if (seenUnitIds.contains(entry.getKey())) {
                return false;
            }
            engine.removeEntity(entry.getValue());
            return true;
        });
    }

    public Entity getEntity(int unitId) {
        return entitiesByUnitId.get(unitId);
    }

    private Entity createEntity(UnitSnapshot snapshot) {
        Entity entity = new Entity();

        PositionComponent position = new PositionComponent();
        position.position.set(snapshot.x, snapshot.y);
        entity.add(position);

        entity.add(new UnitComponent(snapshot.unitId));
        entity.add(new OwnerComponent(snapshot.ownerId));

        // maxHealth приходит с сервера явно (а не выводится из snapshot.building
        // локально) — теперь, когда у зданий бывает разный максимум (дом 500,
        // казарма стрелков 70, здание добычи 70), угадывать его на клиенте
        // было бы неверно.
        HealthComponent health = new HealthComponent(snapshot.maxHealth);
        health.currentHealth = snapshot.health;
        entity.add(health);

        if (snapshot.building) {
            entity.add(new BuildingComponent());

            if (snapshot.resourceBuilding) {
                addResourceBuildingComponent(entity, snapshot);
            } else {
                UnitType type = UnitType.values()[snapshot.unitType];
                ProductionComponent production = new ProductionComponent();
                production.queuedCount = snapshot.queuedCount;
                production.progress = snapshot.buildProgress;
                production.producesUnitType = type;
                entity.add(production);
            }
        } else {
            UnitType type = UnitType.values()[snapshot.unitType];
            entity.add(new UnitTypeComponent(type));

            InterpolationComponent interpolation = new InterpolationComponent();
            interpolation.previousPosition.set(snapshot.x, snapshot.y);
            interpolation.targetPosition.set(snapshot.x, snapshot.y);
            interpolation.elapsed = GameConstants.SNAPSHOT_RATE; // сразу "на месте", без наезда с (0,0)
            entity.add(interpolation);

            DebugPathComponent debugPath = new DebugPathComponent();
            for (PathPoint point : snapshot.pathPoints) {
                debugPath.points.add(new Vector2(point.x, point.y));
            }
            entity.add(debugPath);
        }

        return entity;
    }

    /**
     * Здание добычи ресурса (шахта железа или электростанция) не имеет
     * ProductionComponent вовсе (не производит юнитов) — вместо него на
     * клиенте, как и на сервере, либо ConstructionComponent (ещё
     * строится), либо ResourceExtractorComponent (уже добывает). Какое
     * именно это здание — решает snapshot.resourceType, а не отдельный
     * флаг. RenderSystem по наличию одного из компонентов решает, как
     * рисовать (см. BuildingSizes — та же логика решает и размер).
     */
    private void addResourceBuildingComponent(Entity entity, UnitSnapshot snapshot) {
        ResourceType resourceType = ResourceType.values()[snapshot.resourceType];
        if (snapshot.underConstruction) {
            entity.add(constructionComponentFor(snapshot.constructionProgress, resourceType));
        } else {
            entity.add(new ResourceExtractorComponent(resourceType));
        }
    }

    /** Меняет то, какой из двух компонентов стоит на здании добычи, когда стройка на сервере завершается между снапшотами. */
    private void updateResourceBuildingState(Entity entity, UnitSnapshot snapshot) {
        if (!snapshot.resourceBuilding) {
            return;
        }

        ResourceType resourceType = ResourceType.values()[snapshot.resourceType];
        ConstructionComponent construction = entity.getComponent(ConstructionComponent.class);
        if (snapshot.underConstruction) {
            if (construction != null) {
                construction.remaining = (1f - snapshot.constructionProgress) * GameConstants.RESOURCE_BUILDING_BUILD_TIME;
            } else {
                entity.add(constructionComponentFor(snapshot.constructionProgress, resourceType));
            }
        } else if (construction != null) {
            // Стройка завершилась между снапшотами — снимаем "стройку", ставим "добычу".
            entity.remove(ConstructionComponent.class);
            entity.add(new ResourceExtractorComponent(resourceType));
        }
    }

    /**
     * remaining/totalTime восстановлены из готовой доли прогресса, а не
     * настоящего отсчёта времени (у клиента его и не может быть — он не
     * ведёт стройку сам, только показывает то, что посчитал сервер).
     * RenderSystem считает долю той же формулой (1 - remaining/totalTime),
     * что и сервер, поэтому этого достаточно для одинакового прогресс-бара.
     */
    private ConstructionComponent constructionComponentFor(float progressFraction, ResourceType resourceType) {
        ConstructionComponent construction = new ConstructionComponent(GameConstants.RESOURCE_BUILDING_BUILD_TIME, resourceType);
        construction.remaining = (1f - progressFraction) * GameConstants.RESOURCE_BUILDING_BUILD_TIME;
        return construction;
    }
}
