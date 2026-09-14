package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;
import ru.socol.supreme.shared.ResourceType;

/**
 * Здание достроено и добывает ресурс — добавляется ConstructionSystem
 * (сервер) взамен снятого ConstructionComponent, когда постройка
 * завершена. ResourceExtractionSystem (сервер) прибавляет владельцу
 * здания в PlayerResources ровно rate * deltaTime за тик — раньше тут
 * копился дробный progress до целой единицы, но с тех пор как
 * PlayerResources стала float, копить нечего, можно зачислять сразу
 * дробно. На клиенте компонент не используется для расчётов — только
 * чтобы RenderSystem знал, что это функционирующее здание добычи, а не
 * дом/казарма (у них вместо этого ProductionComponent) и не стройка (у
 * неё — ConstructionComponent).
 */
public class ResourceExtractorComponent implements Component, Pool.Poolable {

    public ResourceType resourceType = ResourceType.IRON;

    public ResourceExtractorComponent() {
    }

    public ResourceExtractorComponent(ResourceType resourceType) {
        this.resourceType = resourceType;
    }

    @Override
    public void reset() {
        resourceType = ResourceType.IRON;
    }
}
