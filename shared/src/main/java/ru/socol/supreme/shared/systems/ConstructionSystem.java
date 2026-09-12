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
 * Продвигает постройку зданий, поставленных игроком (сейчас — только
 * здание добычи железа, см. GameServer.handlePlaceIronMine): пока у
 * здания есть ConstructionComponent, оно просто существует и ничего не
 * делает — RenderSystem на клиенте рисует его как стройку. По достижении
 * remaining <= 0 компонент снимается и добавляется тот, что делает
 * здание функциональным.
 *
 * Единственный вид постройки сейчас — здание добычи, поэтому что именно
 * добавлять по завершении, решено простым присваиванием, а не фабрикой
 * (как ProductionSystem.UnitFactory для юнитов). Если появится второй
 * constructible-тип здания (например, электростанция), этот switch стоит
 * обобщить — сейчас обобщать было бы преждевременно, обобщать нечего.
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

        entity.remove(ConstructionComponent.class);

        ResourceExtractorComponent extractor = engine.createComponent(ResourceExtractorComponent.class);
        extractor.resourceType = ResourceType.IRON;
        extractor.progress = 0f;
        entity.add(extractor);
    }
}
