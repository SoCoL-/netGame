package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.ResourceType;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;

/**
 * Продвигает постройку зданий, поставленных игроком (сейчас — шахта
 * железа и электростанция, см. GameServer.spawnBuilding): пока у здания
 * есть ConstructionComponent, оно просто существует и ничего не делает —
 * RenderSystem на клиенте рисует его как стройку. По достижении
 * remaining <= 0 компонент снимается, а какой компонент добавить взамен
 * — решает BuildingComponent.type той же сущности через
 * BuildingDefinitions: если здание производит юнитов
 * (producesUnitTypeFor != null) — добавляется ProductionComponent, если
 * добывает ресурс (resourceTypeFor != null) — ResourceExtractorComponent.
 * Дом и казарма сейчас никогда не проходят через этот путь (buildTime=0
 * у них, GameServer создаёт их сразу готовыми), но система написана так,
 * что от этого не пострадает, если однажды у них тоже появится время
 * стройки.
 *
 * Приоритет 2 — после ProductionSystem (1), до MovementSystem (10); не
 * взаимодействует с движением напрямую, точный порядок не критичен.
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
        super(Family.all(ConstructionComponent.class, BuildingComponent.class).get(), 2);
    }

    @Override
    public void addedToEngine(Engine engine) {
        super.addedToEngine(engine);
        this.engine = engine;
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        ConstructionComponent construction = CONSTRUCTION.get(entity);
        construction.remaining -= deltaTime;

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

        UnitType producesUnitType = BuildingDefinitions.producesUnitTypeFor(buildingType);
        if (producesUnitType != null) {
            ProductionComponent production = engine.createComponent(ProductionComponent.class);
            production.queuedCount = 0;
            production.progress = 0f;
            production.producesUnitType = producesUnitType;
            entity.add(production);
        }
    }
}
