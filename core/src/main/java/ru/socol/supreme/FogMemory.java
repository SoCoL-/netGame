package ru.socol.supreme;

import com.badlogic.ashley.core.Entity;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.components.TurretDisplayComponent;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Память о чужих зданиях под туманом войны: игрок видит здание таким,
 * каким оно было в последний раз, когда его клетку было видно, — место,
 * тип, стройку, здоровье, угол ствола. Пока клетка в тумане, изменения
 * (урон, достройка, снос, гибель) до игрока не доходят; как только клетку
 * снова видно, память обновляется, а если здания там уже нет — забывается.
 *
 * Сервер шлёт снапшот со всеми сущностями, туман скрывает чужое только на
 * клиенте, так что память целиком клиентская: каждое запомненное здание —
 * отдельная сущность-копия (не в движке) с копиями компонентов, нужных
 * для отрисовки. RenderSystem рисует её тем же кодом, что и живое здание.
 *
 * Запоминаются только настоящие чужие здания — не свои (их видно всегда),
 * не юниты (ходят, их положение устаревает сразу) и не обломки.
 */
final class FogMemory {

    private final Map<Integer, Entity> remembered = new HashMap<>();
    private final Set<Integer> present = new HashSet<>();
    private final List<Entity> hidden = new ArrayList<>();

    /**
     * Обновляет память по текущему состоянию мира (entities — все живые
     * сущности клиента) и маске видимости fogRevealed. Без маски (она ещё
     * не пришла) ничего не делает.
     */
    void update(Iterable<Entity> entities, boolean[] fogRevealed, int localPlayerId) {
        if (fogRevealed == null) {
            return;
        }
        present.clear();
        for (Entity entity : entities) {
            BuildingComponent building = entity.getComponent(BuildingComponent.class);
            OwnerComponent owner = entity.getComponent(OwnerComponent.class);
            UnitComponent unit = entity.getComponent(UnitComponent.class);
            if (building == null || building.type == BuildingType.WRECK || owner == null || unit == null
                    || owner.playerId == localPlayerId) {
                continue;
            }
            present.add(unit.unitId);
            PositionComponent position = entity.getComponent(PositionComponent.class);
            if (isRevealed(fogRevealed, position.position.x, position.position.y)) {
                remember(unit.unitId, entity);
            }
        }
        // Здание пропало (снесено, уничтожено) — узнаём об этом, только когда видим его место.
        remembered.entrySet().removeIf(entry -> !present.contains(entry.getKey())
                && isRevealed(fogRevealed, positionOf(entry.getValue()).x, positionOf(entry.getValue()).y));
    }

    /** Запомненные здания, чьё место сейчас под туманом, — их и рисуют вместо живых (те скрыты туманом). */
    Collection<Entity> hiddenBuildings(boolean[] fogRevealed) {
        hidden.clear();
        for (Entity ghost : remembered.values()) {
            if (!isRevealed(fogRevealed, positionOf(ghost).x, positionOf(ghost).y)) {
                hidden.add(ghost);
            }
        }
        return hidden;
    }

    int size() {
        return remembered.size();
    }

    /** Видна ли сейчас клетка тумана с точкой (x, y). Без маски — видно всё. */
    static boolean isRevealed(boolean[] fogRevealed, float x, float y) {
        if (fogRevealed == null) {
            return true;
        }
        int cellX = (int) (x / GameConstants.FOG_GRID_CELL_SIZE);
        int cellY = (int) (y / GameConstants.FOG_GRID_CELL_SIZE);
        if (cellX < 0 || cellX >= GameConstants.FOG_GRID_WIDTH || cellY < 0 || cellY >= GameConstants.FOG_GRID_HEIGHT) {
            return false;
        }
        int index = cellY * GameConstants.FOG_GRID_WIDTH + cellX;
        return index < fogRevealed.length && fogRevealed[index];
    }

    private static Vector2 positionOf(Entity ghost) {
        return ghost.getComponent(PositionComponent.class).position;
    }

    /** Копирует в сущность-память то, что нужно для отрисовки здания. */
    private void remember(int unitId, Entity source) {
        Entity ghost = remembered.get(unitId);
        if (ghost == null) {
            ghost = new Entity();
            ghost.add(new PositionComponent());
            ghost.add(new OwnerComponent());
            ghost.add(new HealthComponent());
            ghost.add(new BuildingComponent());
            remembered.put(unitId, ghost);
        }
        ghost.getComponent(PositionComponent.class).position.set(source.getComponent(PositionComponent.class).position);
        ghost.getComponent(OwnerComponent.class).playerId = source.getComponent(OwnerComponent.class).playerId;
        HealthComponent health = source.getComponent(HealthComponent.class);
        HealthComponent ghostHealth = ghost.getComponent(HealthComponent.class);
        ghostHealth.currentHealth = health.currentHealth;
        ghostHealth.maxHealth = health.maxHealth;
        ghost.getComponent(BuildingComponent.class).type = source.getComponent(BuildingComponent.class).type;

        ConstructionComponent construction = source.getComponent(ConstructionComponent.class);
        if (construction == null) {
            ghost.remove(ConstructionComponent.class);
        } else {
            ConstructionComponent ghostConstruction = ghost.getComponent(ConstructionComponent.class);
            if (ghostConstruction == null) {
                ghostConstruction = new ConstructionComponent();
                ghost.add(ghostConstruction);
            }
            ghostConstruction.totalTime = construction.totalTime;
            ghostConstruction.remaining = construction.remaining;
        }

        TurretDisplayComponent turret = source.getComponent(TurretDisplayComponent.class);
        if (turret == null) {
            ghost.remove(TurretDisplayComponent.class);
        } else {
            TurretDisplayComponent ghostTurret = ghost.getComponent(TurretDisplayComponent.class);
            if (ghostTurret == null) {
                ghostTurret = new TurretDisplayComponent();
                ghost.add(ghostTurret);
            }
            ghostTurret.dirX = turret.dirX;
            ghostTurret.dirY = turret.dirY;
        }
    }
}
