package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingSizes;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.BuildingRubbleComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.components.WreckComponent;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Гибель юнита или здания от урона — общая для всех источников урона
 * (CombatSystem — обычная стрельба, ArtillerySystem — взрыв снаряда), чтобы
 * последствия везде были одинаковыми: у юнита остаются обломки с частью
 * железа, у здания — именные руины, здание снимается с препятствий A*.
 * Здание с destructionBlastDamage > 0 (электростанция) при этом ещё и
 * взрывается, повреждая соседние здания — и так по цепочке.
 */
final class EntityDestruction {

    private EntityDestruction() {
    }

    static void destroy(Engine engine, Map<Integer, Entity> unitsById, Pathfinding pathfinding,
                        Entity target, int targetUnitId,
                        CombatSystem.UnitDestroyedListener unitDestroyedListener,
                        CombatSystem.BuildingDestroyedListener buildingDestroyedListener) {
        if (unitsById.get(targetUnitId) != target) {
            return; // уже уничтожена раньше в этом же тике (например, цепным взрывом)
        }
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
        BuildingType destroyedType = wasBuilding ? targetBuilding.type : null;
        float x = targetPosition.position.x;
        float y = targetPosition.position.y;
        engine.removeEntity(target);
        unitsById.remove(targetUnitId);
        if (wasBuilding) {
            pathfinding.removeBuildingObstacle(targetUnitId);
        }

        if (destroyedType != null && BuildingDefinitions.destructionBlastDamageFor(destroyedType) > 0) {
            blast(engine, unitsById, pathfinding, x, y,
                    BuildingDefinitions.destructionBlastDamageFor(destroyedType),
                    BuildingDefinitions.destructionBlastRadiusFor(destroyedType),
                    unitDestroyedListener, buildingDestroyedListener);
        }
    }

    /**
     * Взрыв разрушенного здания: урон всем зданиям и руинам зданий, до
     * ближайшей точки которых от (x, y) не дальше radius. Юнитов и обломки
     * юнитов не задевает. У руин "здоровье" — это остаток железа (см.
     * WreckComponent): взрыв его уменьшает, при нуле руины исчезают.
     * Собственные руины взорвавшегося здания (они уже созданы слушателем в
     * его центре, (x, y)) не трогаем — иначе они сгорали бы в том же
     * взрыве, и отстроиться на их месте со скидкой было бы невозможно.
     * Погибшие здания уничтожаются тем же destroy — и если среди них есть
     * взрывающиеся, цепочка продолжается.
     */
    private static void blast(Engine engine, Map<Integer, Entity> unitsById, Pathfinding pathfinding,
                              float x, float y, int damage, float radius,
                              CombatSystem.UnitDestroyedListener unitDestroyedListener,
                              CombatSystem.BuildingDestroyedListener buildingDestroyedListener) {
        // Сначала урон и список погибших, уничтожение — отдельным проходом:
        // destroy удаляет из unitsById, по которому мы сейчас итерируемся.
        List<Entity> killed = new ArrayList<>();
        for (Entity entity : unitsById.values()) {
            if (entity.getComponent(BuildingComponent.class) == null) {
                continue; // юниты взрыв не задевает
            }
            boolean isWreck = entity.getComponent(WreckComponent.class) != null;
            boolean isRuins = entity.getComponent(BuildingRubbleComponent.class) != null;
            if (isWreck && !isRuins) {
                continue; // обломки юнитов не задевает — только здания и их руины
            }
            PositionComponent position = entity.getComponent(PositionComponent.class);
            HealthComponent health = entity.getComponent(HealthComponent.class);
            if (position == null || health == null) {
                continue;
            }
            if (isRuins && position.position.x == x && position.position.y == y) {
                continue; // собственные руины взорвавшегося здания
            }
            float halfWidth = BuildingSizes.halfWidth(entity);
            float halfHeight = BuildingSizes.halfHeight(entity);
            float closestX = MathUtils.clamp(x, position.position.x - halfWidth, position.position.x + halfWidth);
            float closestY = MathUtils.clamp(y, position.position.y - halfHeight, position.position.y + halfHeight);
            if (Vector2.dst2(x, y, closestX, closestY) > radius * radius) {
                continue;
            }
            // Не ниже нуля — у руин это остаток железа, отрицательным он не бывает.
            health.currentHealth = Math.max(0, health.currentHealth - damage);
            if (health.currentHealth <= 0) {
                killed.add(entity);
            }
        }
        for (Entity entity : killed) {
            UnitComponent unitComponent = entity.getComponent(UnitComponent.class);
            if (unitComponent != null) {
                destroy(engine, unitsById, pathfinding, entity, unitComponent.unitId,
                        unitDestroyedListener, buildingDestroyedListener);
            }
        }
    }
}
