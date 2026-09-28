package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.Map;

/**
 * Гибель юнита или здания от урона — общая для всех источников урона
 * (CombatSystem — обычная стрельба, ArtillerySystem — взрыв снаряда), чтобы
 * последствия везде были одинаковыми: у юнита остаются обломки с частью
 * железа, у здания — именные руины, здание снимается с препятствий A*.
 */
final class EntityDestruction {

    private EntityDestruction() {
    }

    static void destroy(Engine engine, Map<Integer, Entity> unitsById, Pathfinding pathfinding,
                        Entity target, int targetUnitId,
                        CombatSystem.UnitDestroyedListener unitDestroyedListener,
                        CombatSystem.BuildingDestroyedListener buildingDestroyedListener) {
        BuildingComponent targetBuilding = target.getComponent(BuildingComponent.class);
        UnitTypeComponent targetType = target.getComponent(UnitTypeComponent.class);
        PositionComponent targetPosition = target.getComponent(PositionComponent.class);
        // Обычные обломки юнита — только для настоящих юнитов, не для
        // зданий (у зданий свои, именные — см. ветку ниже) и не для самих
        // обломков: у них тоже BuildingComponent, так что первая ветка их
        // отсеивает, а вторая пропускает по типу WRECK.
        if (targetBuilding == null && unitDestroyedListener != null && targetType != null) {
            unitDestroyedListener.onUnitDestroyed(targetType.type,
                    targetPosition.position.x, targetPosition.position.y);
        } else if (targetBuilding != null && targetBuilding.type != BuildingType.WRECK
                && buildingDestroyedListener != null) {
            // Настоящее здание погибло — GameServer.spawnBuildingRubble
            // оставляет на его месте именные обломки (второй, не связанный с
            // боем триггер того же метода — добровольный снос, см. GameServer
            // .handleDemolishBuilding).
            buildingDestroyedListener.onBuildingDestroyed(targetBuilding.type,
                    targetPosition.position.x, targetPosition.position.y);
        }

        boolean wasBuilding = targetBuilding != null;
        engine.removeEntity(target);
        unitsById.remove(targetUnitId);
        if (wasBuilding) {
            pathfinding.removeBuildingObstacle(targetUnitId);
        }
    }
}
