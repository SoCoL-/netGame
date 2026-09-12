package ru.socol.supreme.shared;

import com.badlogic.ashley.core.Entity;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;

/**
 * Единая точка расчёта half-width/half-height здания — раньше это решал
 * только ProductionComponent.producesUnitType (дом/казарма), но здание
 * добычи железа юнитов не производит и этого компонента не имеет вовсе.
 * Различаем не по общему enum "тип здания", а по НАЛИЧИЮ компонента —
 * ConstructionComponent (ещё строится) или ResourceExtractorComponent
 * (уже добывает) значат "это здание добычи", иначе — обычная логика
 * по ProductionComponent, как и раньше для дома/казармы.
 *
 * Используется и на сервере (CollisionSystem — выталкивание юнитов из
 * здания), и на клиенте (RenderSystem — отрисовка, GameScreen — радиус
 * клика) — единственное место, которое обязано знать про оба случая
 * сразу, чтобы они не могли разойтись.
 */
public final class BuildingSizes {

    private BuildingSizes() {
    }

    public static float halfWidth(Entity building) {
        if (isResourceBuilding(building)) {
            return GameConstants.IRON_MINE_HALF_SIZE;
        }
        return GameConstants.buildingHalfWidthFor(producesUnitTypeOf(building));
    }

    public static float halfHeight(Entity building) {
        if (isResourceBuilding(building)) {
            return GameConstants.IRON_MINE_HALF_SIZE;
        }
        return GameConstants.buildingHalfHeightFor(producesUnitTypeOf(building));
    }

    public static boolean isResourceBuilding(Entity building) {
        return building.getComponent(ConstructionComponent.class) != null
                || building.getComponent(ResourceExtractorComponent.class) != null;
    }

    private static UnitType producesUnitTypeOf(Entity building) {
        ProductionComponent production = building.getComponent(ProductionComponent.class);
        return production != null ? production.producesUnitType : UnitType.WARRIOR;
    }
}
