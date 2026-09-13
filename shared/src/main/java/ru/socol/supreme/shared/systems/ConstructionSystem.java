package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import ru.socol.supreme.shared.ResourceType;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;

/**
 * Продвигает постройку зданий, поставленных игроком (сейчас — шахта
 * железа и электростанция, см. GameServer.handlePlaceIronMine /
 * handlePlacePowerPlant): пока у здания есть ConstructionComponent, оно
 * просто существует и ничего не делает — RenderSystem на клиенте рисует
 * его как стройку. По достижении remaining <= 0 компонент снимается и
 * добавляется ResourceExtractorComponent с тем же resourceType, что нёс
 * ConstructionComponent — какое именно здание строилось, решает не эта
 * система, а то, что записали в ConstructionComponent при постройке;
 * здесь достаточно просто перенести значение.
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

    private Engine engine;

    public ConstructionSystem() {
        super(Family.all(ConstructionComponent.class).get(), 2);
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

        ResourceType resourceType = construction.resourceType;
        entity.remove(ConstructionComponent.class);

        ResourceExtractorComponent extractor = engine.createComponent(ResourceExtractorComponent.class);
        extractor.resourceType = resourceType;
        extractor.progress = 0f;
        entity.add(extractor);
    }
}
