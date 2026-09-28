package ru.socol.supreme.server;

import com.badlogic.ashley.core.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.BuildingExplosionEvent;
import ru.socol.supreme.shared.network.messages.DemolishBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Электростанция взрывается, когда её разрушают: 500 урона всем зданиям
 * в радиусе взрыва (75 от центра — на 25 дальше её стен), юнитов не
 * задевает, соседняя электростанция детонирует по цепочке, а
 * добровольный снос взрыва не вызывает.
 */
class PowerPlantExplosionTest {

    private static final float PLANT_X = 1500f;
    private static final float PLANT_Y = 1500f;
    private static final float PLANT_HALF = 50f;
    private static final float STORAGE_HALF = 25f;

    private FakeConnection player0;
    private FakeConnection player1;
    private RecordingServer server;
    private GameServer game;

    @BeforeEach
    void setUp() {
        player0 = new FakeConnection(100);
        player1 = new FakeConnection(200);
        server = new RecordingServer(player0, player1);
        game = new GameServer(server, new FakeConnection[]{player0, player1}, null);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.dispose();
    }

    private int placeFinished(BuildingType type, float x, float y) {
        PlaceBuildingRequest request = new PlaceBuildingRequest();
        request.buildingType = type.ordinal();
        request.x = x;
        request.y = y;
        game.handlePlaceBuilding(player0, request);
        int unitId = -1;
        for (UnitSnapshot unit : game.buildWorldSnapshot().units) {
            if (unit.building && unit.buildingType == type.ordinal()
                    && unit.x == x && unit.y == y) {
                unitId = unit.unitId;
            }
        }
        assertTrue(unitId > 0, "здание " + type + " поставлено в (" + x + ", " + y + ")");
        Entity building = game.unitById(unitId);
        building.remove(ConstructionComponent.class);
        HealthComponent health = building.getComponent(HealthComponent.class);
        health.currentHealth = health.maxHealth;
        return unitId;
    }

    private int healthOf(int unitId) {
        return game.unitById(unitId).getComponent(HealthComponent.class).currentHealth;
    }

    private int builderOf(int playerId) {
        for (UnitSnapshot unit : game.buildWorldSnapshot().units) {
            if (!unit.building && unit.ownerId == playerId && unit.unitType == UnitType.BUILDER.ordinal()) {
                return unit.unitId;
            }
        }
        throw new AssertionError("нет строителя игрока " + playerId);
    }

    /** Разрушает электростанцию настоящим боем: вражеский строитель вплотную добивает её последний 1 HP. */
    private void destroyInCombat(int plantId) {
        game.unitById(plantId).getComponent(HealthComponent.class).currentHealth = 1;
        int attackerId = builderOf(1);
        game.unitById(attackerId).getComponent(PositionComponent.class).position.set(PLANT_X, PLANT_Y - PLANT_HALF - 15f);
        AttackUnitRequest attack = new AttackUnitRequest();
        attack.unitId = attackerId;
        attack.targetUnitId = plantId;
        game.handleAttackUnit(player1, attack);
        for (int i = 0; i < 100 && game.unitById(plantId) != null; i++) {
            game.tick(0.05f);
        }
        assertNull(game.unitById(plantId), "электростанция разрушена");
    }

    @Test
    void definitionMatchesRequestedBlast() {
        assertEquals(500, BuildingDefinitions.destructionBlastDamageFor(BuildingType.POWER_PLANT));
        assertEquals(PLANT_HALF * 1.5f, BuildingDefinitions.destructionBlastRadiusFor(BuildingType.POWER_PLANT), 0.001f);
        assertEquals(0, BuildingDefinitions.destructionBlastDamageFor(BuildingType.IRON_STORAGE), "взрывается только электростанция");
    }

    @Test
    void destroyedPowerPlantDamagesBuildingsInBlastRadiusOnly() {
        int plantId = placeFinished(BuildingType.POWER_PLANT, PLANT_X, PLANT_Y);
        // Ближнее хранилище — в 10 от стены станции (внутри радиуса), дальнее — в 40 (снаружи).
        int nearId = placeFinished(BuildingType.IRON_STORAGE, PLANT_X + PLANT_HALF + STORAGE_HALF + 10f, PLANT_Y);
        int farId = placeFinished(BuildingType.IRON_STORAGE, PLANT_X - PLANT_HALF - STORAGE_HALF - 40f, PLANT_Y);
        int farHealth = healthOf(farId);

        destroyInCombat(plantId);

        assertNull(game.unitById(nearId), "ближнее здание уничтожено взрывом (500 урона)");
        assertEquals(farHealth, healthOf(farId), "дальнее здание не задето");
        for (FakeConnection connection : new FakeConnection[]{player0, player1}) {
            BuildingExplosionEvent event = connection.lastSent(BuildingExplosionEvent.class);
            assertNotNull(event, "клиенты получают картинку взрыва");
            assertEquals(PLANT_X, event.x, 0.001f);
        }
    }

    @Test
    void blastDoesNotDamageUnits() {
        int plantId = placeFinished(BuildingType.POWER_PLANT, PLANT_X, PLANT_Y);
        int ownBuilderId = builderOf(0);
        game.unitById(ownBuilderId).getComponent(PositionComponent.class).position.set(PLANT_X + PLANT_HALF + 15f, PLANT_Y);
        int ownBuilderHealth = healthOf(ownBuilderId);

        destroyInCombat(plantId);

        assertEquals(ownBuilderHealth, healthOf(ownBuilderId));
    }

    @Test
    void neighbouringPowerPlantDetonatesInChain() {
        int firstId = placeFinished(BuildingType.POWER_PLANT, PLANT_X, PLANT_Y);
        float secondX = PLANT_X + 2 * PLANT_HALF + 10f;
        int secondId = placeFinished(BuildingType.POWER_PLANT, secondX, PLANT_Y);
        // Хранилище рядом со ВТОРОЙ станцией, но вне радиуса первой.
        int storageId = placeFinished(BuildingType.IRON_STORAGE, secondX + PLANT_HALF + STORAGE_HALF + 10f, PLANT_Y);

        destroyInCombat(firstId);

        assertNull(game.unitById(secondId), "вторая станция взорвалась от первой");
        assertNull(game.unitById(storageId), "и её взрыв уничтожил своё соседнее здание");
        assertEquals(2, player0.sentOf(BuildingExplosionEvent.class).size());
        int rubble = 0;
        for (UnitSnapshot unit : game.buildWorldSnapshot().units) {
            if (unit.ownerId == GameConstants.NEUTRAL_OWNER_ID
                    && unit.rubbleOriginalBuildingType == BuildingType.POWER_PLANT.ordinal()) {
                rubble++;
            }
        }
        assertEquals(2, rubble, "обе станции оставили именные руины");
    }

    @Test
    void demolishingPowerPlantDoesNotExplode() {
        int plantId = placeFinished(BuildingType.POWER_PLANT, PLANT_X, PLANT_Y);
        int nearId = placeFinished(BuildingType.IRON_STORAGE, PLANT_X + PLANT_HALF + STORAGE_HALF + 10f, PLANT_Y);
        int nearHealth = healthOf(nearId);

        DemolishBuildingRequest demolish = new DemolishBuildingRequest();
        demolish.buildingUnitId = plantId;
        game.handleDemolishBuilding(player0, demolish);
        game.tick(0.05f);

        assertNull(game.unitById(plantId));
        assertEquals(nearHealth, healthOf(nearId), "снос — не разрушение, взрыва нет");
        assertNull(player0.lastSent(BuildingExplosionEvent.class));
    }
}
