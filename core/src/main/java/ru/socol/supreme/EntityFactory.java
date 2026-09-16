package ru.socol.supreme;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.ResourceType;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.components.BuildBeamComponent;
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
 *
 * Какие компоненты повесить на здание, решает не сам снапшот напрямую, а
 * BuildingDefinitions по snapshot.buildingType — те же данные из
 * buildings.json, что использует и сервер (см. её javadoc про то, почему
 * это теперь важно и клиенту, не только серверу).
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
                production.hasRallyPoint = snapshot.hasRallyPoint;
                production.rallyX = snapshot.rallyX;
                production.rallyY = snapshot.rallyY;
                // Какие типы юнитов это здание умеет производить — не меняется
                // после создания здания (решает BuildingDefinitions по типу
                // здания), обновлять не нужно; сама очередь (queue) клиенту не
                // нужна вовсе, только queuedCount, см. javadoc ProductionComponent.
            }

            if (snapshot.building) {
                updateBuildingConstructionState(entity, snapshot);
            } else {
                updateBuildBeam(entity, snapshot);
            }

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
        // локально) — у зданий разный максимум по типу (BuildingDefinitions
        // .maxHealthFor), угадывать его на клиенте было бы неверно.
        HealthComponent health = new HealthComponent(snapshot.maxHealth);
        health.currentHealth = snapshot.health;
        entity.add(health);

        if (snapshot.building) {
            BuildingType type = BuildingType.values()[snapshot.buildingType];
            entity.add(new BuildingComponent(type));
            addBuildingBehaviorComponent(entity, type, snapshot);
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

            if (snapshot.buildTargetUnitId != 0) {
                entity.add(new BuildBeamComponent(snapshot.buildTargetUnitId));
            }
        }

        return entity;
    }

    /**
     * Какой компонент повесить на свежесозданное здание — решает не
     * snapshot напрямую, а BuildingDefinitions по типу: под стройкой
     * (underConstruction) — ConstructionComponent; иначе — ProductionComponent,
     * если это здание производит юнитов, или ResourceExtractorComponent,
     * если добывает ресурс (BuildingDefinitions.producesUnitTypesFor/
     * resourceTypeFor — одно из двух не пусто/не null, второе всегда
     * пусто/null, у каждого типа здания ровно одна роль).
     */
    private void addBuildingBehaviorComponent(Entity entity, BuildingType type, UnitSnapshot snapshot) {
        if (snapshot.underConstruction) {
            entity.add(constructionComponentFor(type, snapshot.constructionProgress));
            return;
        }

        if (BuildingDefinitions.producesUnitTypesFor(type).length > 0) {
            ProductionComponent production = new ProductionComponent();
            production.queuedCount = snapshot.queuedCount;
            production.progress = snapshot.buildProgress;
            production.hasRallyPoint = snapshot.hasRallyPoint;
            production.rallyX = snapshot.rallyX;
            production.rallyY = snapshot.rallyY;
            entity.add(production);
            return;
        }

        ResourceType resourceType = BuildingDefinitions.resourceTypeFor(type);
        if (resourceType != null) {
            entity.add(new ResourceExtractorComponent(resourceType));
        }
    }

    /** Добавляет/обновляет/снимает BuildBeamComponent по snapshot.buildTargetUnitId — актуально только для юнитов, только для строителей, которые СЕЙЧАС реально строят (не просто идут к цели). */
    private void updateBuildBeam(Entity entity, UnitSnapshot snapshot) {
        BuildBeamComponent beam = entity.getComponent(BuildBeamComponent.class);
        if (snapshot.buildTargetUnitId == 0) {
            if (beam != null) {
                entity.remove(BuildBeamComponent.class);
            }
            return;
        }
        if (beam == null) {
            entity.add(new BuildBeamComponent(snapshot.buildTargetUnitId));
        } else {
            beam.targetBuildingUnitId = snapshot.buildTargetUnitId;
        }
    }

    /** Меняет то, какой компонент поведения стоит на здании, когда стройка на сервере завершается между снапшотами. */
    private void updateBuildingConstructionState(Entity entity, UnitSnapshot snapshot) {
        BuildingType type = entity.getComponent(BuildingComponent.class).type;
        ConstructionComponent construction = entity.getComponent(ConstructionComponent.class);

        if (snapshot.underConstruction) {
            if (construction != null) {
                construction.remaining = (1f - snapshot.constructionProgress) * BuildingDefinitions.buildTimeFor(type);
            } else {
                entity.add(constructionComponentFor(type, snapshot.constructionProgress));
            }
            return;
        }

        if (construction != null) {
            // Стройка завершилась между снапшотами — снимаем "стройку", ставим рабочий компонент.
            entity.remove(ConstructionComponent.class);
            addBuildingBehaviorComponent(entity, type, snapshot);
        }
    }

    /**
     * remaining/totalTime восстановлены из готовой доли прогресса, а не
     * настоящего отсчёта времени (у клиента его и не может быть — он не
     * ведёт стройку сам, только показывает то, что посчитал сервер).
     * RenderSystem считает долю той же формулой (1 - remaining/totalTime),
     * что и сервер, поэтому этого достаточно для одинакового прогресс-бара.
     */
    private ConstructionComponent constructionComponentFor(BuildingType type, float progressFraction) {
        float totalTime = BuildingDefinitions.buildTimeFor(type);
        ConstructionComponent construction = new ConstructionComponent(totalTime);
        construction.remaining = (1f - progressFraction) * totalTime;
        return construction;
    }
}
