package ru.socol.supreme.shared;

import com.badlogic.ashley.core.Entity;
import ru.socol.supreme.shared.components.BuildingComponent;

/**
 * Единая точка расчёта half-width/half-height здания — просто читает
 * BuildingComponent.type и смотрит его размер в BuildingDefinitions
 * (buildings.json). Раньше, до появления type на BuildingComponent, это
 * приходилось решать косвенно, по тому, какие ещё компоненты стоят на
 * сущности — теперь не нужно, у каждого здания есть прямой ответ на
 * вопрос "какое я".
 *
 * Используется и на сервере (CollisionSystem — выталкивание юнитов из
 * здания), и на клиенте (RenderSystem — отрисовка, GameScreen — радиус
 * клика) — единственное место, которое обязано знать про все типы сразу,
 * чтобы они не могли разойтись.
 */
public final class BuildingSizes {

    private BuildingSizes() {
    }

    public static float halfWidth(Entity building) {
        return BuildingDefinitions.halfWidthFor(typeOf(building));
    }

    public static float halfHeight(Entity building) {
        return BuildingDefinitions.halfHeightFor(typeOf(building));
    }

    /** Добывает ли это здание ресурс (шахта, электростанция) — в отличие от дома/казармы, которые производят юнитов. */
    public static boolean isResourceBuilding(Entity building) {
        return BuildingDefinitions.resourceTypeFor(typeOf(building)) != null;
    }

    private static BuildingType typeOf(Entity building) {
        BuildingComponent marker = building.getComponent(BuildingComponent.class);
        return marker != null ? marker.type : BuildingType.HOME;
    }
}
