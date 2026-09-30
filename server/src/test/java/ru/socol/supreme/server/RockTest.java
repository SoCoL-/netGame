package ru.socol.supreme.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.map.GameMap;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Скалы из карты assets/maps/default.json: непроходимы, сквозь них нельзя
 * стрелять прямой наводкой, на них нельзя строить. Используется узкая
 * скала 100x100 в клетках (12..13, 10..11) — мир x 600..700, y 500..600,
 * вокруг неё трава. Рядом — база игрока 1 (дом в 625, 415), поэтому
 * турель в тесте стрельбы — его: по своему дому она не стреляет, и
 * единственная цель в радиусе — строитель игрока 0.
 */
class RockTest {

    private static final float ROCK_X = 650f;
    private static final float ROCK_Y = 550f;
    /** Турель (игрока 1) слева от скалы. */
    private static final float TURRET_X = 525f;
    private static final float TURRET_Y = 550f;
    /** Справа от скалы — в радиусе турели (200), но за скалой. */
    private static final float BEHIND_ROCK_X = 715f;
    private static final float BEHIND_ROCK_Y = 550f;
    /** Над турелью на том же расстоянии — по открытой траве. */
    private static final float IN_THE_OPEN_X = 525f;
    private static final float IN_THE_OPEN_Y = 740f;

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

    @Test
    void layoutIsAsExpected() {
        GameMap map = GameMap.current();
        assertTrue(map.isRockAt(ROCK_X, ROCK_Y), "в центре — скала");
        assertFalse(map.isRockAt(TURRET_X, TURRET_Y));
        assertFalse(map.isRockAt(BEHIND_ROCK_X, BEHIND_ROCK_Y));
        assertFalse(map.lineOfFireClear(TURRET_X, TURRET_Y, BEHIND_ROCK_X, BEHIND_ROCK_Y), "скала на линии огня");
        assertTrue(map.lineOfFireClear(TURRET_X, TURRET_Y, IN_THE_OPEN_X, IN_THE_OPEN_Y), "по траве линия чистая");
    }

    @Test
    void rockIsImpassableEvenForBuilder() {
        assertTrue(Pathfinding.isInsideRock(ROCK_X, ROCK_Y));
        assertTrue(game.pathfinding().isBlocked(ROCK_X, ROCK_Y, true), "и для умеющего в воду");
    }

    @Test
    void cannotBuildOnRock() {
        PlaceBuildingRequest request = new PlaceBuildingRequest();
        request.buildingType = BuildingType.IRON_STORAGE.ordinal();
        request.x = ROCK_X;
        request.y = ROCK_Y;
        game.handlePlaceBuilding(player0, request);
        assertTrue(find(0, true, BuildingType.IRON_STORAGE.ordinal()) == null, "склад на скале не поставлен");
        assertNotNull(player0.lastSent(ErrorResponse.class));
    }

    @Test
    void turretDoesNotShootThroughRock() {
        placeFinishedTurret(player1, 1);
        UnitSnapshot enemy = find(0, false, UnitType.BUILDER.ordinal());
        int fullHealth = UnitDefinitions.healthFor(UnitType.BUILDER);

        teleport(enemy.unitId, BEHIND_ROCK_X, BEHIND_ROCK_Y);
        tickFor(3f);
        assertEquals(fullHealth, health(enemy.unitId), "за скалой — не попадает");

        teleport(enemy.unitId, IN_THE_OPEN_X, IN_THE_OPEN_Y);
        tickFor(3f);
        assertTrue(health(enemy.unitId) < fullHealth, "на том же расстоянии по траве — стреляет");
    }

    @Test
    void unitGoesAroundRockToShootTargetBehindIt() {
        UnitSnapshot mine = find(0, false, UnitType.BUILDER.ordinal());
        UnitSnapshot enemy = find(1, false, UnitType.BUILDER.ordinal());
        teleport(mine.unitId, TURRET_X, TURRET_Y);
        teleport(enemy.unitId, BEHIND_ROCK_X, BEHIND_ROCK_Y);

        AttackUnitRequest request = new AttackUnitRequest();
        request.unitId = mine.unitId;
        request.targetUnitId = enemy.unitId;
        game.handleAttackUnit(player0, request);
        tickFor(8f);

        assertTrue(health(enemy.unitId) < UnitDefinitions.healthFor(UnitType.BUILDER), "обошёл скалу и попал");
        PositionComponent position = game.unitById(mine.unitId).getComponent(PositionComponent.class);
        assertFalse(GameMap.current().isRockAt(position.position.x, position.position.y), "сквозь скалу не прошёл");
    }

    // ---- Помощники ----

    private void placeFinishedTurret(FakeConnection owner, int ownerId) {
        PlaceBuildingRequest request = new PlaceBuildingRequest();
        request.buildingType = BuildingType.TURRET.ordinal();
        request.x = TURRET_X;
        request.y = TURRET_Y;
        game.handlePlaceBuilding(owner, request);
        UnitSnapshot turret = find(ownerId, true, BuildingType.TURRET.ordinal());
        assertNotNull(turret, "турель поставлена рядом со скалой");
        game.unitById(turret.unitId).getComponent(ConstructionComponent.class).remaining = 0f;
        game.tick(0.01f);
        HealthComponent health = game.unitById(turret.unitId).getComponent(HealthComponent.class);
        health.currentHealth = health.maxHealth;
    }

    private void teleport(int unitId, float x, float y) {
        game.unitById(unitId).getComponent(PositionComponent.class).position.set(x, y);
    }

    private int health(int unitId) {
        return game.unitById(unitId).getComponent(HealthComponent.class).currentHealth;
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

    private void tickFor(float seconds) {
        float step = 0.05f;
        for (float elapsed = 0f; elapsed < seconds; elapsed += step) {
            game.tick(step);
        }
    }
}
