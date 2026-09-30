package ru.socol.supreme.server;

import com.badlogic.ashley.core.Entity;
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
import ru.socol.supreme.shared.components.FillCraterOrderComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.craters.Crater;
import ru.socol.supreme.shared.network.messages.ArtilleryFireRequest;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.FillCraterRequest;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;
import ru.socol.supreme.shared.network.messages.WorldSnapshot;

import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Воронки — шрамы на карте: появляются от снарядов артиллерии и взрывов
 * электростанций, углубляются повторными попаданиями, замедляют наземные
 * юниты, запрещают стройку, пока строитель их не засыплет, и зарастают
 * сами. Тот же подход, что в GameServerTest/ArtilleryTest.
 */
class CraterTest {

    private static final float SPOT_X = 1500f;
    private static final float SPOT_Y = 1500f;
    /**
     * Открытая трава, где на 500 единиц вправо нет ни скал, ни воды: сюда
     * переставляется строитель в тестах, где он куда-то едет. У самого
     * дома (точка старта с карты) может быть что угодно — например, скалы.
     */
    private static final float OPEN_GROUND_X = 1500f;
    private static final float OPEN_GROUND_Y = 1000f;

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
        game.setArtilleryRandom(new Random() {
            @Override
            public float nextFloat() {
                return 0f; // без разброса — снаряд падает точно в цель
            }
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        server.dispose();
    }

    // ---- Помощники ----

    private int placeFinished(BuildingType type, float x, float y) {
        PlaceBuildingRequest request = new PlaceBuildingRequest();
        request.buildingType = type.ordinal();
        request.x = x;
        request.y = y;
        game.handlePlaceBuilding(player0, request);
        for (UnitSnapshot unit : game.buildWorldSnapshot().units) {
            if (unit.building && unit.buildingType == type.ordinal() && unit.x == x && unit.y == y) {
                Entity building = game.unitById(unit.unitId);
                // Стройку завершает ConstructionSystem — она же выдаёт башне ArtilleryComponent.
                building.getComponent(ConstructionComponent.class).remaining = 0f;
                game.tick(0.01f);
                HealthComponent health = building.getComponent(HealthComponent.class);
                health.currentHealth = health.maxHealth;
                return unit.unitId;
            }
        }
        return -1;
    }

    private int builderOf(int playerId) {
        for (UnitSnapshot unit : game.buildWorldSnapshot().units) {
            if (!unit.building && unit.ownerId == playerId && unit.unitType == UnitType.BUILDER.ordinal()) {
                return unit.unitId;
            }
        }
        throw new AssertionError("нет строителя");
    }

    private Entity entity(int unitId) {
        return game.unitById(unitId);
    }

    private void tickFor(float seconds) {
        for (float elapsed = 0f; elapsed < seconds; elapsed += 0.05f) {
            game.tick(0.05f);
        }
    }

    /** Строит артиллерию на SPOT, стреляет в (x, y) и ждёт падения снаряда. */
    private void shellAt(int towerId, float x, float y) {
        ArtilleryComponent artillery = entity(towerId).getComponent(ArtilleryComponent.class);
        artillery.shells = Math.max(artillery.shells, 1);
        game.resourcesOf(0).electricity = 10000f;
        ArtilleryFireRequest request = new ArtilleryFireRequest();
        request.buildingUnitId = towerId;
        request.x = x;
        request.y = y;
        game.handleArtilleryFire(player0, request);
        for (int i = 0; i < 300 && (!artillery.pendingTargets.isEmpty() || game.artilleryShellsInFlight() > 0); i++) {
            game.tick(0.05f);
        }
        assertEquals(0, game.artilleryShellsInFlight(), "снаряд упал");
    }

    // ---- Появление ----

    @Test
    void artilleryShellLeavesCraterAtImpactPoint() {
        int towerId = placeFinished(BuildingType.ARTILLERY, SPOT_X, SPOT_Y);
        float targetX = SPOT_X + 1000f;
        float targetY = SPOT_Y + 1000f;

        shellAt(towerId, targetX, targetY);

        assertEquals(1, game.craterField().all().size());
        Crater crater = game.craterField().all().get(0);
        assertEquals(targetX, crater.x, 0.001f);
        assertEquals(BuildingDefinitions.craterRadiusFor(BuildingType.ARTILLERY), crater.radius, 0.001f);

        WorldSnapshot snapshot = game.buildWorldSnapshot();
        assertEquals(1, snapshot.craters.size(), "клиенты видят воронку");
        assertEquals(crater.id, snapshot.craters.get(0).id);
    }

    @Test
    void repeatedHitDeepensTheSameCraterInsteadOfAddingNewOne() {
        int towerId = placeFinished(BuildingType.ARTILLERY, SPOT_X, SPOT_Y);
        float targetX = SPOT_X + 1000f;
        float targetY = SPOT_Y + 1000f;

        shellAt(towerId, targetX, targetY);
        tickFor(10f);
        float ageBefore = game.craterField().all().get(0).age;
        shellAt(towerId, targetX + 20f, targetY);

        assertEquals(1, game.craterField().all().size(), "повторное попадание не плодит новую воронку");
        Crater crater = game.craterField().all().get(0);
        assertEquals(BuildingDefinitions.craterRadiusFor(BuildingType.ARTILLERY) + GameConstants.CRATER_GROWTH_PER_HIT,
                crater.radius, 0.001f, "воронка углубилась");
        assertTrue(crater.age < ageBefore, "зарастание началось заново");
    }

    @Test
    void destroyedPowerPlantLeavesCrater() {
        int plantId = placeFinished(BuildingType.POWER_PLANT, SPOT_X, SPOT_Y);
        entity(plantId).getComponent(HealthComponent.class).currentHealth = 1;
        int attackerId = builderOf(1);
        entity(attackerId).getComponent(PositionComponent.class).position.set(SPOT_X, SPOT_Y - 65f);
        AttackUnitRequest attack = new AttackUnitRequest();
        attack.unitId = attackerId;
        attack.targetUnitId = plantId;
        game.handleAttackUnit(player1, attack);
        for (int i = 0; i < 100 && entity(plantId) != null; i++) {
            game.tick(0.05f);
        }
        assertNull(entity(plantId));

        Crater crater = game.craterField().craterAt(SPOT_X, SPOT_Y);
        assertNotNull(crater, "на месте станции — воронка");
        assertEquals(BuildingDefinitions.craterRadiusFor(BuildingType.POWER_PLANT), crater.radius, 0.001f);
    }

    // ---- Жизнь воронки ----

    @Test
    void craterOvergrowsOnItsOwn() {
        game.craterField().addExplosion(SPOT_X, SPOT_Y, 60f);
        for (float t = 0f; t < GameConstants.CRATER_LIFETIME_SECONDS - 5f; t += 1f) {
            game.tick(1f);
        }
        assertEquals(1, game.craterField().all().size(), "ещё не заросла");
        for (int i = 0; i < 10; i++) {
            game.tick(1f);
        }
        assertTrue(game.craterField().all().isEmpty(), "заросла");
    }

    @Test
    void groundUnitsAreSlowedInsideCrater() {
        int builderId = builderOf(0);
        PositionComponent position = entity(builderId).getComponent(PositionComponent.class);
        position.position.set(OPEN_GROUND_X, OPEN_GROUND_Y);
        float startX = position.position.x;
        float startY = position.position.y;
        float speed = UnitDefinitions.speedFor(UnitType.BUILDER);

        MoveUnitRequest move = new MoveUnitRequest();
        move.unitId = builderId;
        move.targetX = startX + 400f;
        move.targetY = startY;
        game.craterField().addExplosion(startX + 100f, startY, GameConstants.CRATER_MAX_RADIUS);
        game.handleMoveUnit(player0, move);
        tickFor(0.5f);

        float travelled = position.position.x - startX;
        assertEquals(speed * GameConstants.CRATER_SPEED_MULTIPLIER * 0.5f, travelled, speed * 0.06f,
                "в воронке строитель едет медленнее");
    }

    // ---- Стройка ----

    @Test
    void cannotBuildOnCraterUntilItIsGone() {
        Crater crater = game.craterField().addExplosion(SPOT_X, SPOT_Y, 60f);
        PlaceBuildingRequest request = new PlaceBuildingRequest();
        request.buildingType = BuildingType.POWER_PLANT.ordinal();
        request.x = SPOT_X;
        request.y = SPOT_Y;

        game.handlePlaceBuilding(player0, request);
        assertNotNull(player0.lastSent(ErrorResponse.class), "на воронке строить нельзя");
        assertEquals(-1, findBuilding(BuildingType.POWER_PLANT));

        game.craterField().remove(crater);
        game.handlePlaceBuilding(player0, request);
        assertTrue(findBuilding(BuildingType.POWER_PLANT) > 0, "после засыпки — можно");
    }

    private int findBuilding(BuildingType type) {
        for (UnitSnapshot unit : game.buildWorldSnapshot().units) {
            if (unit.building && unit.buildingType == type.ordinal()) {
                return unit.unitId;
            }
        }
        return -1;
    }

    @Test
    void builderFillsCraterAndOrderEnds() {
        int builderId = builderOf(0);
        PositionComponent position = entity(builderId).getComponent(PositionComponent.class);
        position.position.set(OPEN_GROUND_X, OPEN_GROUND_Y);
        Crater crater = game.craterField().addExplosion(position.position.x + 400f, position.position.y, 60f);

        FillCraterRequest request = new FillCraterRequest();
        request.builderUnitId = builderId;
        request.craterId = crater.id;
        game.handleFillCrater(player0, request);
        assertNotNull(entity(builderId).getComponent(FillCraterOrderComponent.class));

        tickFor(1f);
        assertEquals(1, game.craterField().all().size(), "засыпка — не мгновенно");

        tickFor(crater.fillTime() + 3f);
        assertTrue(game.craterField().all().isEmpty(), "строитель засыпал воронку");
        assertNull(entity(builderId).getComponent(FillCraterOrderComponent.class), "приказ снят");
    }

    @Test
    void anyOtherOrderCancelsFilling() {
        int builderId = builderOf(0);
        PositionComponent position = entity(builderId).getComponent(PositionComponent.class);
        Crater crater = game.craterField().addExplosion(position.position.x + 400f, position.position.y, 60f);
        FillCraterRequest fill = new FillCraterRequest();
        fill.builderUnitId = builderId;
        fill.craterId = crater.id;
        game.handleFillCrater(player0, fill);

        MoveUnitRequest move = new MoveUnitRequest();
        move.unitId = builderId;
        move.targetX = position.position.x - 200f;
        move.targetY = position.position.y;
        game.handleMoveUnit(player0, move);

        assertNull(entity(builderId).getComponent(FillCraterOrderComponent.class));
    }

    @Test
    void onlyOwnBuildersCanFill() {
        Crater crater = game.craterField().addExplosion(SPOT_X, SPOT_Y, 60f);
        FillCraterRequest request = new FillCraterRequest();
        request.builderUnitId = builderOf(1);
        request.craterId = crater.id;
        game.handleFillCrater(player0, request);
        assertFalse(entity(builderOf(1)).getComponent(FillCraterOrderComponent.class) != null, "чужому строителю приказать нельзя");
    }
}
