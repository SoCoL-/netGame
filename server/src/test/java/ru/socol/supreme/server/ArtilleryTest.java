package ru.socol.supreme.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.ArtilleryComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.network.messages.ArtilleryFireRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.FogSnapshot;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Артиллерийская башня: стройка снарядов с расходом электричества,
 * содержание при полном запасе, проверки выстрела (снаряды, электричество,
 * дальность — но не видимость) и взрыв при падении. Тот же подход, что и в
 * GameServerTest: без сети, симуляция шагается вручную, числа баланса — из
 * BuildingDefinitions.
 */
class ArtilleryTest {

    private static final BuildingType ARTILLERY = BuildingType.ARTILLERY;
    /** Свободное место на стороне игрока 0 — как FREE_SPOT в GameServerTest. */
    private static final float TOWER_X = 1500f;
    private static final float TOWER_Y = 1500f;

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

    // ---- Помощники ----

    private UnitSnapshot unitById(int unitId) {
        for (UnitSnapshot unit : game.buildWorldSnapshot().units) {
            if (unit.unitId == unitId) {
                return unit;
            }
        }
        return null;
    }

    private UnitSnapshot find(int ownerId, boolean building, int typeOrdinal) {
        for (UnitSnapshot unit : game.buildWorldSnapshot().units) {
            if (unit.ownerId == ownerId && unit.building == building
                    && (building ? unit.buildingType : unit.unitType) == typeOrdinal) {
                return unit;
            }
        }
        return null;
    }

    private int placeTower() {
        PlaceBuildingRequest request = new PlaceBuildingRequest();
        request.buildingType = ARTILLERY.ordinal();
        request.x = TOWER_X;
        request.y = TOWER_Y;
        game.handlePlaceBuilding(player0, request);
        UnitSnapshot tower = find(0, true, ARTILLERY.ordinal());
        assertNotNull(tower, "башню можно поставить");
        return tower.unitId;
    }

    /** Ставит башню и мгновенно "достраивает" её — сама стройка строителем проверяется в других тестах. */
    private int buildTower() {
        int towerId = placeTower();
        game.unitById(towerId).getComponent(ConstructionComponent.class).remaining = 0f;
        game.tick(0.01f);
        assertNull(game.unitById(towerId).getComponent(ConstructionComponent.class), "стройка завершена");
        return towerId;
    }

    private ArtilleryComponent artilleryOf(int towerId) {
        return game.unitById(towerId).getComponent(ArtilleryComponent.class);
    }

    private PlayerResources resources() {
        return game.resourcesOf(0);
    }

    private void fire(int towerId, float x, float y) {
        ArtilleryFireRequest request = new ArtilleryFireRequest();
        request.buildingUnitId = towerId;
        request.x = x;
        request.y = y;
        game.handleArtilleryFire(player0, request);
    }

    private void tickFor(float seconds) {
        float step = 0.05f;
        for (float elapsed = 0f; elapsed < seconds; elapsed += step) {
            game.tick(step);
        }
    }

    // ---- Постройка ----

    @Test
    void definitionMatchesRequestedStats() {
        assertEquals(1000, BuildingDefinitions.ironCostFor(ARTILLERY));
        assertEquals(1500, BuildingDefinitions.electricityCostFor(ARTILLERY));
        assertEquals(200, BuildingDefinitions.maxHealthFor(ARTILLERY));
        assertEquals(GameConstants.MAP_WIDTH / 3f, BuildingDefinitions.artilleryRangeFor(ARTILLERY), 1f);
        assertEquals(10, BuildingDefinitions.shellCapacityFor(ARTILLERY));
        assertEquals(5f, BuildingDefinitions.shellBuildTimeFor(ARTILLERY), 0.001f);
        assertEquals(10f, BuildingDefinitions.activeConsumptionRateFor(ARTILLERY), 0.001f);
        assertEquals(5f, BuildingDefinitions.idleConsumptionRateFor(ARTILLERY), 0.001f);
        assertEquals(200, BuildingDefinitions.shotElectricityCostFor(ARTILLERY));
    }

    @Test
    void towerHasNoShellsUntilBuiltThenStartsEmpty() {
        int towerId = placeTower();
        assertEquals(-1, unitById(towerId).artilleryShells, "недостроенная башня не стреляет и снаряды не строит");

        game.unitById(towerId).getComponent(ConstructionComponent.class).remaining = 0f;
        game.tick(0.01f);
        assertEquals(0, unitById(towerId).artilleryShells);
    }

    // ---- Снаряды и электричество ----

    @Test
    void shellIsBuiltInShellBuildTimeConsumingElectricityPerSecond() {
        int towerId = buildTower();
        resources().electricity = 1000f;
        float buildTime = BuildingDefinitions.shellBuildTimeFor(ARTILLERY);

        tickFor(buildTime - 0.5f);
        assertEquals(0, artilleryOf(towerId).shells, "снаряд ещё не готов");

        tickFor(0.6f);
        assertEquals(1, artilleryOf(towerId).shells);
        float expectedSpent = BuildingDefinitions.activeConsumptionRateFor(ARTILLERY) * buildTime;
        assertEquals(1000f - expectedSpent, resources().electricity, 1.5f);
    }

    @Test
    void shellBuildingStallsWithoutElectricity() {
        int towerId = buildTower();
        resources().electricity = 0f;

        tickFor(BuildingDefinitions.shellBuildTimeFor(ARTILLERY) + 1f);

        assertEquals(0, artilleryOf(towerId).shells);
        assertEquals(0f, artilleryOf(towerId).shellProgress, 0.001f, "прогресс без электричества не растёт");
    }

    @Test
    void fullTowerStopsBuildingAndOnlyPaysUpkeep() {
        int towerId = buildTower();
        int capacity = BuildingDefinitions.shellCapacityFor(ARTILLERY);
        artilleryOf(towerId).shells = capacity;
        resources().electricity = 100f;

        tickFor(2f);

        assertEquals(capacity, artilleryOf(towerId).shells, "больше максимума не строит");
        float expectedUpkeep = BuildingDefinitions.idleConsumptionRateFor(ARTILLERY) * 2f;
        assertEquals(100f - expectedUpkeep, resources().electricity, 0.5f);
    }

    // ---- Выстрел ----

    @Test
    void cannotFireWithoutShells() {
        int towerId = buildTower();
        resources().electricity = 1000f;
        fire(towerId, TOWER_X + 500f, TOWER_Y);
        assertNotNull(player0.lastSent(ErrorResponse.class));
        assertEquals(0, game.artilleryShellsInFlight());
    }

    @Test
    void cannotFireWithoutElectricityForTheShot() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = BuildingDefinitions.shotElectricityCostFor(ARTILLERY) - 1f;

        fire(towerId, TOWER_X + 500f, TOWER_Y);

        assertNotNull(player0.lastSent(ErrorResponse.class));
        assertEquals(1, artilleryOf(towerId).shells, "снаряд не потрачен");
    }

    @Test
    void cannotFireBeyondRange() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        float range = BuildingDefinitions.artilleryRangeFor(ARTILLERY);

        fire(towerId, TOWER_X + range + 50f, TOWER_Y);

        assertNotNull(player0.lastSent(ErrorResponse.class));
        assertEquals(1, artilleryOf(towerId).shells);
    }

    @Test
    void canFireIntoFogAndPaysShellAndElectricity() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 3;
        resources().electricity = 1000f;
        float targetX = TOWER_X + BuildingDefinitions.artilleryRangeFor(ARTILLERY) - 100f;
        float targetY = TOWER_Y;
        assertFalse(isRevealedForPlayer0(targetX, targetY), "цель — в неисследованной области");

        fire(towerId, targetX, targetY);

        assertNull(player0.lastSent(ErrorResponse.class));
        assertEquals(2, artilleryOf(towerId).shells);
        assertEquals(1000f - BuildingDefinitions.shotElectricityCostFor(ARTILLERY), resources().electricity, 0.001f);
        assertEquals(1, game.artilleryShellsInFlight());
        for (FakeConnection connection : new FakeConnection[]{player0, player1}) {
            ProjectileFiredEvent event = connection.lastSent(ProjectileFiredEvent.class);
            assertNotNull(event);
            assertTrue(event.artillery);
            assertEquals(targetX, event.toX, 0.001f);
            assertTrue(event.flightTime > 0f);
        }
    }

    @Test
    void cannotFireEnemyTower() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        ArtilleryFireRequest request = new ArtilleryFireRequest();
        request.buildingUnitId = towerId;
        request.x = TOWER_X + 500f;
        request.y = TOWER_Y;
        game.handleArtilleryFire(player1, request);
        assertEquals(1, artilleryOf(towerId).shells);
    }

    // ---- Взрыв ----

    @Test
    void shellDamagesTargetsInSplashOnlyWhenItLands() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 2;
        resources().electricity = 1000f;
        UnitSnapshot builder = find(0, false, UnitType.BUILDER.ordinal()); // взрыв задевает и своих
        int maxHealth = UnitDefinitions.healthFor(UnitType.BUILDER);
        int damage = BuildingDefinitions.shellDamageFor(ARTILLERY);

        fire(towerId, builder.x, builder.y);
        assertEquals(maxHealth, unitById(builder.unitId).health, "урон — при падении, а не при выстреле");

        tickFor(flightTimeTo(builder) + 0.1f);

        assertEquals(0, game.artilleryShellsInFlight());
        assertEquals(maxHealth - damage, game.unitById(builder.unitId).getComponent(HealthComponent.class).currentHealth);
    }

    @Test
    void killedByShellUnitLeavesWreck() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        UnitSnapshot builder = find(0, false, UnitType.BUILDER.ordinal());
        game.unitById(builder.unitId).getComponent(HealthComponent.class).currentHealth = 1;

        fire(towerId, builder.x, builder.y);
        tickFor(flightTimeTo(builder) + 0.1f);

        assertNull(game.unitById(builder.unitId), "строитель погиб");
        assertNotNull(find(GameConstants.NEUTRAL_OWNER_ID, true, BuildingType.WRECK.ordinal()), "остались обломки");
    }

    @Test
    void shellMissesTargetsOutsideSplash() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        UnitSnapshot builder = find(0, false, UnitType.BUILDER.ordinal());
        float farX = builder.x + BuildingDefinitions.shellSplashRadiusFor(ARTILLERY) * 3f;

        fire(towerId, farX, builder.y);
        tickFor(3f);

        assertEquals(UnitDefinitions.healthFor(UnitType.BUILDER),
                game.unitById(builder.unitId).getComponent(HealthComponent.class).currentHealth);
    }

    private float flightTimeTo(UnitSnapshot target) {
        float dx = target.x - TOWER_X;
        float dy = target.y - TOWER_Y;
        return (float) Math.sqrt(dx * dx + dy * dy) / BuildingDefinitions.shellSpeedFor(ARTILLERY);
    }

    private boolean isRevealedForPlayer0(float x, float y) {
        for (FogSnapshot fog : game.buildWorldSnapshot().fog) {
            if (fog.playerId == 0) {
                int cellX = (int) (x / GameConstants.FOG_GRID_CELL_SIZE);
                int cellY = (int) (y / GameConstants.FOG_GRID_CELL_SIZE);
                return fog.revealed[cellY * GameConstants.FOG_GRID_WIDTH + cellX];
            }
        }
        return false;
    }
}
