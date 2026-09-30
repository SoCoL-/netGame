package ru.socol.supreme;

import com.badlogic.ashley.core.Entity;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitComponent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Память о чужих зданиях под туманом войны: последнее увиденное состояние, забывание только на виду. */
class FogMemoryTest {

    private static final int ME = 0;
    private static final int ENEMY = 1;
    private static final float X = 1050f;
    private static final float Y = 2050f;

    private final FogMemory memory = new FogMemory();
    private final List<Entity> world = new ArrayList<>();

    @Test
    void enemyBuildingIsRememberedAsLastSeen() {
        Entity plant = building(7, ENEMY, BuildingType.POWER_PLANT, 100);
        world.add(plant);

        memory.update(world, fog(true), ME);
        assertTrue(memory.hiddenBuildings(fog(true)).isEmpty(), "на виду — рисуется живое здание, не память");

        plant.getComponent(HealthComponent.class).currentHealth = 10; // урон под туманом
        memory.update(world, fog(false), ME);
        Collection<Entity> hidden = memory.hiddenBuildings(fog(false));
        assertEquals(1, hidden.size());
        Entity ghost = hidden.iterator().next();
        assertEquals(100, ghost.getComponent(HealthComponent.class).currentHealth, "под туманом — последнее увиденное здоровье");
        assertEquals(BuildingType.POWER_PLANT, ghost.getComponent(BuildingComponent.class).type);
        assertEquals(X, ghost.getComponent(PositionComponent.class).position.x, 0.001f);

        memory.update(world, fog(true), ME);
        memory.update(world, fog(false), ME);
        assertEquals(10, memory.hiddenBuildings(fog(false)).iterator().next().getComponent(HealthComponent.class).currentHealth,
                "снова увидели — память обновилась");
    }

    @Test
    void destroyedBuildingIsForgottenOnlyWhenItsPlaceIsSeen() {
        world.add(building(7, ENEMY, BuildingType.HOME, 500));
        memory.update(world, fog(true), ME);

        world.clear(); // разрушено, пока было под туманом
        memory.update(world, fog(false), ME);
        assertEquals(1, memory.hiddenBuildings(fog(false)).size(), "под туманом о сносе не знаем");

        memory.update(world, fog(true), ME);
        assertEquals(0, memory.size(), "увидели пустое место — забыли");
    }

    @Test
    void ownBuildingsAndWrecksAreNotRemembered() {
        world.add(building(1, ME, BuildingType.HOME, 500));
        world.add(building(2, GameConstants.NEUTRAL_OWNER_ID, BuildingType.WRECK, 50));
        memory.update(world, fog(true), ME);
        assertEquals(0, memory.size());
    }

    @Test
    void nothingIsRememberedBeforeFogArrives() {
        world.add(building(7, ENEMY, BuildingType.HOME, 500));
        memory.update(world, null, ME);
        assertEquals(0, memory.size());
    }

    private static Entity building(int unitId, int ownerId, BuildingType type, int health) {
        Entity entity = new Entity();
        PositionComponent position = new PositionComponent();
        position.position.set(X, Y);
        entity.add(position);
        entity.add(new UnitComponent(unitId));
        entity.add(new OwnerComponent(ownerId));
        HealthComponent healthComponent = new HealthComponent(health);
        healthComponent.currentHealth = health;
        entity.add(healthComponent);
        entity.add(new BuildingComponent(type));
        return entity;
    }

    /** Маска тумана, где вся карта либо видна, либо нет. */
    private static boolean[] fog(boolean revealed) {
        boolean[] cells = new boolean[GameConstants.FOG_GRID_WIDTH * GameConstants.FOG_GRID_HEIGHT];
        Arrays.fill(cells, revealed);
        return cells;
    }
}
