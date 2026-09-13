package ru.socol.supreme.shared;

import com.badlogic.ashley.core.Entity;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;

/**
 * Единая точка расчёта half-width/half-height здания — раньше это решал
 * только ProductionComponent.producesUnitType (дом/казарма), но здания
 * добычи ресурсов юнитов не производят и этого компонента не имеют вовсе.
 * Различаем не по общему enum "тип здания", а по НАЛИЧИЮ компонента —
 * ConstructionComponent (ещё строится) или ResourceExtractorComponent
 * (уже добывает) значат "это здание добычи ресурса", а какое именно
 * (шахта железа или электростанция) — по их полю resourceType, а не
 * отдельным типом. Иначе — обычная логика по ProductionComponent, как и
 * раньше для дома/казармы.
 *
 * Используется и на сервере (CollisionSystem — выталкивание юнитов из
 * здания), и на клиенте (RenderSystem — отрисовка, GameScreen — радиус
 * клика) — единственное место, которое обязано знать про все случаи
 * сразу, чтобы они не могли разойтись.
 */
public final class BuildingSizes {

    private BuildingSizes() {
    }

    public static float halfWidth(Entity building) {
        ResourceType resourceType = resourceBuildingTypeOf(building);
        if (resourceType != null) {
            return resourceType == ResourceType.ELECTRICITY ? GameConstants.POWER_PLANT_HALF_SIZE : GameConstants.IRON_MINE_HALF_SIZE;
        }
        return GameConstants.buildingHalfWidthFor(producesUnitTypeOf(building));
    }

    public static float halfHeight(Entity building) {
        ResourceType resourceType = resourceBuildingTypeOf(building);
        if (resourceType != null) {
            return resourceType == ResourceType.ELECTRICITY ? GameConstants.POWER_PLANT_HALF_SIZE : GameConstants.IRON_MINE_HALF_SIZE;
        }
        return GameConstants.buildingHalfHeightFor(producesUnitTypeOf(building));
    }

    public static boolean isResourceBuilding(Entity building) {
        return resourceBuildingTypeOf(building) != null;
    }

    /** Какой ресурс это здание добывает/строится, чтобы добывать — null, если это вообще не здание добычи (дом/казарма). */
    private static ResourceType resourceBuildingTypeOf(Entity building) {
        ConstructionComponent construction = building.getComponent(ConstructionComponent.class);
        if (construction != null) {
            return construction.resourceType;
        }
        ResourceExtractorComponent extractor = building.getComponent(ResourceExtractorComponent.class);
        if (extractor != null) {
            return extractor.resourceType;
        }
        return null;
    }

    private static UnitType producesUnitTypeOf(Entity building) {
        ProductionComponent production = building.getComponent(ProductionComponent.class);
        return production != null ? production.producesUnitType : UnitType.WARRIOR;
    }
}
