package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.ResourceType;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;

/**
 * Продвигает постройку зданий, поставленных игроком (шахта железа,
 * казарма стрелков, электростанция, оба хранилища). По достижении
 * remaining <= 0 у ConstructionComponent компонент снимается, а какой
 * компонент добавить взамен — решает BuildingComponent.type той же
 * сущности через BuildingDefinitions: если здание производит юнитов
 * (producesUnitTypesFor непусто) — добавляется ProductionComponent (с
 * пустой очередью — какие юниты в неё добавлять, решает игрок), если
 * добывает ресурс (resourceTypeFor != null) — ResourceExtractorComponent.
 *
 * Сама remaining этой системой больше НЕ уменьшается — с тех пор как
 * появились строители, за это отвечает BuildSystem, и только пока рядом
 * активно работает строитель (см. её javadoc, почему здание "не
 * достраивается само"). Эта система лишь следит за моментом завершения и
 * переводит здание в рабочее состояние.
 *
 * Приоритет 3 — после BuildSystem (1, уменьшает remaining) и
 * ProductionSystem (2), до MovementSystem (10).
 *
 * Живёт в shared (как и остальные gameplay-системы), но реально
 * используется только сервером.
 */
public class ConstructionSystem extends IteratingSystem {

    private static final ComponentMapper<ConstructionComponent> CONSTRUCTION =
            ComponentMapper.getFor(ConstructionComponent.class);
    private static final ComponentMapper<BuildingComponent> BUILDING =
            ComponentMapper.getFor(BuildingComponent.class);

    private Engine engine;

    public ConstructionSystem() {
        super(Family.all(ConstructionComponent.class, BuildingComponent.class).get(), 3);
    }

    @Override
    public void addedToEngine(Engine engine) {
        super.addedToEngine(engine);
        this.engine = engine;
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        ConstructionComponent construction = CONSTRUCTION.get(entity);

        if (construction.remaining > 0f) {
            return;
        }

        BuildingType buildingType = BUILDING.get(entity).type;
        entity.remove(ConstructionComponent.class);

        ResourceType resourceType = BuildingDefinitions.resourceTypeFor(buildingType);
        if (resourceType != null) {
            ResourceExtractorComponent extractor = engine.createComponent(ResourceExtractorComponent.class);
            extractor.resourceType = resourceType;
            entity.add(extractor);
        }

        if (BuildingDefinitions.producesUnitTypesFor(buildingType).length > 0) {
            ProductionComponent production = engine.createComponent(ProductionComponent.class);
            entity.add(production);
        }
    }
}
