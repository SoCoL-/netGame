package ru.socol.supreme.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.QueuedOrder;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.RepairComponent;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.CollectOrderRequest;
import ru.socol.supreme.shared.network.messages.DemolishBuildingRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.FogSnapshot;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.PatrolPoint;
import ru.socol.supreme.shared.network.messages.PatrolUnitRequest;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlaceIronMineRequest;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;
import ru.socol.supreme.shared.network.messages.QueuedOrderPoint;
import ru.socol.supreme.shared.network.messages.RepairOrderRequest;
import ru.socol.supreme.shared.network.messages.SetRallyPointRequest;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;
import ru.socol.supreme.shared.network.messages.WorldSnapshot;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GameServer — авторитетная симуляция одного матча. Эти тесты гоняют её
 * без сети и без игрового потока: соединения игроков — FakeConnection
 * (всё отправленное складывается в список), симуляция шагается вручную
 * через tick(deltaTime), а состояние мира читается через
 * buildWorldSnapshot() — ровно то, что увидел бы клиент.
 *
 * Как и в клиентских тестах (EntityFactoryTest), баланс не хардкодится:
 * стоимость, время постройки и т.п. берутся из UnitDefinitions/
 * BuildingDefinitions, так что тесты проверяют правила сервера (кто
 * кому может приказать, что отбрасывается, когда кончается игра), а не
 * конкретные числа из units.json/buildings.json.
 */
class GameServerTest {

    /** Свободное место для постройки на стороне игрока 0 — далеко от дома, стартового строителя, воды и краёв карты. */
    private static final float FREE_SPOT_X = 1500f;
    private static final float FREE_SPOT_Y = 1500f;

    private FakeConnection player0;
    private FakeConnection player1;
    private RecordingServer server;
    private GameServer game;
    private boolean gameEndListenerCalled;

    @BeforeEach
    void setUp() {
        player0 = new FakeConnection(100);
        player1 = new FakeConnection(200);
        server = new RecordingServer(player0, player1);
        game = new GameServer(server, new FakeConnection[]{player0, player1}, () -> gameEndListenerCalled = true);
    }

    /**
     * Pathfinding хранит препятствия-здания в статическом реестре по
     * unitId, а у каждого GameServer нумерация unitId начинается заново с
     * 1 — без очистки здания одного теста остались бы препятствиями в
     * следующем.
     */
    @AfterEach
    void tearDown() throws IOException {
        for (int unitId = 1; unitId < 1000; unitId++) {
            Pathfinding.removeBuildingObstacle(unitId);
        }
        server.dispose();
    }

    // ---- Поиск в снапшоте ----

    private WorldSnapshot snapshot() {
        return game.buildWorldSnapshot();
    }

    private UnitSnapshot unitById(int unitId) {
        for (UnitSnapshot unit : snapshot().units) {
            if (unit.unitId == unitId) {
                return unit;
            }
        }
        return null;
    }

    private List<UnitSnapshot> unitsOf(int ownerId, UnitType type) {
        List<UnitSnapshot> result = new ArrayList<>();
        for (UnitSnapshot unit : snapshot().units) {
            if (!unit.building && unit.ownerId == ownerId && unit.unitType == type.ordinal()) {
                result.add(unit);
            }
        }
        return result;
    }

    private List<UnitSnapshot> buildingsOf(int ownerId, BuildingType type) {
        List<UnitSnapshot> result = new ArrayList<>();
        for (UnitSnapshot unit : snapshot().units) {
            if (unit.building && unit.ownerId == ownerId && unit.buildingType == type.ordinal()) {
                result.add(unit);
            }
        }
        return result;
    }

    private UnitSnapshot home(int playerId) {
        List<UnitSnapshot> homes = buildingsOf(playerId, BuildingType.HOME);
        assertEquals(1, homes.size(), "у каждого игрока ровно один дом");
        return homes.get(0);
    }

    private UnitSnapshot builder(int playerId) {
        List<UnitSnapshot> builders = unitsOf(playerId, UnitType.BUILDER);
        assertEquals(1, builders.size(), "у каждого игрока ровно один стартовый строитель");
        return builders.get(0);
    }

    private PlayerResources resourcesOf(int playerId) {
        for (PlayerResources resources : snapshot().playerResources) {
            if (resources.playerId == playerId) {
                return resources;
            }
        }
        return null;
    }

    /** Тип текущего (первого) приказа юнита по снапшоту, или -1, если приказа нет. */
    private int currentOrderType(int unitId) {
        UnitSnapshot unit = unitById(unitId);
        assertNotNull(unit);
        return unit.queuedOrders.isEmpty() ? -1 : unit.queuedOrders.get(0).type;
    }

    private void tickFor(float seconds) {
        float step = 0.05f;
        for (float elapsed = 0f; elapsed < seconds; elapsed += step) {
            game.tick(step);
        }
    }

    // ---- Запросы ----

    private static QueueUnitRequest queueUnit(int buildingUnitId, UnitType type) {
        QueueUnitRequest request = new QueueUnitRequest();
        request.buildingUnitId = buildingUnitId;
        request.unitType = type.ordinal();
        return request;
    }

    private static PlaceBuildingRequest placeBuilding(BuildingType type, float x, float y, int... builderIds) {
        PlaceBuildingRequest request = new PlaceBuildingRequest();
        request.buildingType = type.ordinal();
        request.x = x;
        request.y = y;
        request.builderUnitIds = builderIds;
        return request;
    }

    private static MoveUnitRequest move(int unitId, float x, float y, boolean queue) {
        MoveUnitRequest request = new MoveUnitRequest();
        request.unitId = unitId;
        request.targetX = x;
        request.targetY = y;
        request.queue = queue;
        return request;
    }

    private static AttackUnitRequest attack(int unitId, int targetUnitId) {
        AttackUnitRequest request = new AttackUnitRequest();
        request.unitId = unitId;
        request.targetUnitId = targetUnitId;
        return request;
    }

    private static DemolishBuildingRequest demolish(int buildingUnitId) {
        DemolishBuildingRequest request = new DemolishBuildingRequest();
        request.buildingUnitId = buildingUnitId;
        return request;
    }

    /** Ставит электростанцию игрока 0 на FREE_SPOT и возвращает её unitId. */
    private int placePowerPlantForPlayer0(int... builderIds) {
        game.handlePlaceBuilding(player0, placeBuilding(BuildingType.POWER_PLANT, FREE_SPOT_X, FREE_SPOT_Y, builderIds));
        List<UnitSnapshot> plants = buildingsOf(0, BuildingType.POWER_PLANT);
        assertEquals(1, plants.size());
        return plants.get(0).unitId;
    }

    // ---- Старт матча ----

    @Test
    void eachPlayerStartsWithFinishedHomeAndOneBuilderInOwnCorner() {
        for (int playerId = 0; playerId < GameConstants.MAX_PLAYERS; playerId++) {
            UnitSnapshot home = home(playerId);
            float[] expectedPosition = BuildingDefinitions.homeSpawnPoint(playerId);
            assertEquals(expectedPosition[0], home.x, 0.001f);
            assertEquals(expectedPosition[1], home.y, 0.001f);
            assertFalse(home.underConstruction, "дом появляется сразу готовым");
            assertEquals(BuildingDefinitions.maxHealthFor(BuildingType.HOME), home.health);

            UnitSnapshot builder = builder(playerId);
            assertEquals(UnitDefinitions.healthFor(UnitType.BUILDER), builder.health);
        }
    }

    @Test
    void startingResourcesCoverExactlyOneIronMineAndOnePowerPlant() {
        PlayerResources resources = resourcesOf(0);
        assertNotNull(resources);
        assertEquals(BuildingDefinitions.ironCostFor(BuildingType.IRON_MINE)
                + BuildingDefinitions.ironCostFor(BuildingType.POWER_PLANT), resources.iron, 0.001f);
        assertEquals(BuildingDefinitions.electricityCostFor(BuildingType.IRON_MINE)
                + BuildingDefinitions.electricityCostFor(BuildingType.POWER_PLANT), resources.electricity, 0.001f);
    }

    // ---- Производство ----

    @Test
    void queueUnitAcceptsOnlyTypesThisBuildingProduces() {
        int homeId = home(0).unitId;

        game.handleQueueUnit(player0, queueUnit(homeId, UnitType.ARCHER)); // дом лучников не производит
        assertEquals(0, unitById(homeId).queuedCount);

        game.handleQueueUnit(player0, queueUnit(homeId, UnitType.WARRIOR));
        UnitSnapshot home = unitById(homeId);
        assertEquals(1, home.queuedCount);
        assertEquals(UnitType.WARRIOR.ordinal(), home.producingUnitType);
    }

    @Test
    void queueUnitInEnemyBuildingOrWithInvalidTypeIsIgnored() {
        int enemyHomeId = home(1).unitId;
        game.handleQueueUnit(player0, queueUnit(enemyHomeId, UnitType.WARRIOR));
        assertEquals(0, unitById(enemyHomeId).queuedCount);

        int ownHomeId = home(0).unitId;
        QueueUnitRequest invalid = new QueueUnitRequest();
        invalid.buildingUnitId = ownHomeId;
        invalid.unitType = 999;
        game.handleQueueUnit(player0, invalid);
        assertEquals(0, unitById(ownHomeId).queuedCount);
    }

    @Test
    void queuedUnitAppearsAfterItsBuildTime() {
        UnitType type = UnitType.WARRIOR;
        int homeId = home(0).unitId;
        game.handleQueueUnit(player0, queueUnit(homeId, type));
        assertTrue(unitsOf(0, type).isEmpty());

        tickFor(UnitDefinitions.buildTimeFor(type) + 1f);

        assertEquals(1, unitsOf(0, type).size(), "юнит произведён");
        assertEquals(0, unitById(homeId).queuedCount, "очередь освободилась");
    }

    @Test
    void rallyPointIsStoredOnlyForOwnProducingBuilding() {
        int homeId = home(0).unitId;
        SetRallyPointRequest request = new SetRallyPointRequest();
        request.buildingUnitId = homeId;
        request.x = 1234f;
        request.y = 2345f;

        game.handleSetRallyPoint(player1, request); // чужой дом
        assertFalse(unitById(homeId).hasRallyPoint);

        game.handleSetRallyPoint(player0, request);
        UnitSnapshot home = unitById(homeId);
        assertTrue(home.hasRallyPoint);
        assertEquals(1234f, home.rallyX, 0.001f);
        assertEquals(2345f, home.rallyY, 0.001f);
    }

    // ---- Постройка ----

    @Test
    void placedBuildingStartsUnderConstructionAndSelectedBuilderIsSentToBuildIt() {
        int builderId = builder(0).unitId;
        int plantId = placePowerPlantForPlayer0(builderId);

        UnitSnapshot plant = unitById(plantId);
        assertTrue(plant.underConstruction);
        assertEquals(0f, plant.constructionProgress, 0.001f);
        assertEquals(1, plant.health, "недостроенное здание начинает с 1 HP");

        assertEquals(QueuedOrder.Type.BUILD.ordinal(), currentOrderType(builderId));
    }

    @Test
    void enemyBuilderIdsInPlaceRequestAreIgnored() {
        int enemyBuilderId = builder(1).unitId;
        placePowerPlantForPlayer0(enemyBuilderId);
        assertEquals(-1, currentOrderType(enemyBuilderId), "чужой строитель не получил приказ");
    }

    @Test
    void cannotPlaceBuildingInWaterOrOnTopOfAnotherBuilding() {
        float waterCenterX = (GameConstants.WATER_MIN_X + GameConstants.WATER_MAX_X) / 2f;
        float waterCenterY = (GameConstants.WATER_MIN_Y + GameConstants.WATER_MAX_Y) / 2f;
        game.handlePlaceBuilding(player0, placeBuilding(BuildingType.POWER_PLANT, waterCenterX, waterCenterY));
        assertTrue(buildingsOf(0, BuildingType.POWER_PLANT).isEmpty());
        assertNotNull(player0.lastSent(ErrorResponse.class));

        placePowerPlantForPlayer0();
        player0.sent.clear();
        game.handlePlaceBuilding(player0, placeBuilding(BuildingType.IRON_STORAGE, FREE_SPOT_X, FREE_SPOT_Y));
        assertTrue(buildingsOf(0, BuildingType.IRON_STORAGE).isEmpty());
        assertNotNull(player0.lastSent(ErrorResponse.class));
    }

    @Test
    void homeAndIronMineCannotBePlacedThroughGenericRequest() {
        game.handlePlaceBuilding(player0, placeBuilding(BuildingType.HOME, FREE_SPOT_X, FREE_SPOT_Y));
        game.handlePlaceBuilding(player0, placeBuilding(BuildingType.IRON_MINE, FREE_SPOT_X, FREE_SPOT_Y));
        assertEquals(1, buildingsOf(0, BuildingType.HOME).size());
        assertTrue(buildingsOf(0, BuildingType.IRON_MINE).isEmpty());
    }

    @Test
    void ironMineIsPlacedExactlyOnDepositAndDepositCannotBeTakenTwice() {
        PlaceIronMineRequest request = new PlaceIronMineRequest();
        request.depositIndex = 0;
        game.handlePlaceIronMine(player0, request);

        List<UnitSnapshot> mines = buildingsOf(0, BuildingType.IRON_MINE);
        assertEquals(1, mines.size());
        assertEquals(GameConstants.IRON_DEPOSITS[0][0], mines.get(0).x, 0.001f);
        assertEquals(GameConstants.IRON_DEPOSITS[0][1], mines.get(0).y, 0.001f);

        game.handlePlaceIronMine(player1, request);
        assertTrue(buildingsOf(1, BuildingType.IRON_MINE).isEmpty());
        assertNotNull(player1.lastSent(ErrorResponse.class));

        PlaceIronMineRequest outOfRange = new PlaceIronMineRequest();
        outOfRange.depositIndex = GameConstants.IRON_DEPOSITS.length;
        game.handlePlaceIronMine(player0, outOfRange);
        assertEquals(1, buildingsOf(0, BuildingType.IRON_MINE).size());
    }

    @Test
    void demolishingLeavesNamedRubbleOnTheSameSpot() {
        int plantId = placePowerPlantForPlayer0();
        game.handleDemolishBuilding(player0, demolish(plantId));
        assertNull(unitById(plantId));

        List<UnitSnapshot> rubble = buildingsOf(GameConstants.NEUTRAL_OWNER_ID, BuildingType.WRECK);
        assertEquals(1, rubble.size());
        assertEquals(BuildingType.POWER_PLANT.ordinal(), rubble.get(0).rubbleOriginalBuildingType);
        assertEquals(FREE_SPOT_X, rubble.get(0).x, 0.001f, "обломки на месте здания, а не в (0, 0)");
        assertEquals(FREE_SPOT_Y, rubble.get(0).y, 0.001f);
    }

    @Test
    void rebuildingOnMatchingRubbleConsumesItAndIsDiscounted() {
        int plantId = placePowerPlantForPlayer0();
        game.handleDemolishBuilding(player0, demolish(plantId));

        int rebuiltId = placePowerPlantForPlayer0();

        assertTrue(buildingsOf(GameConstants.NEUTRAL_OWNER_ID, BuildingType.WRECK).isEmpty(),
                "обломки поглощены новой стройкой");
        ConstructionComponent construction = game.unitById(rebuiltId).getComponent(ConstructionComponent.class);
        assertEquals(BuildingDefinitions.buildTimeFor(BuildingType.POWER_PLANT) * GameConstants.BUILDING_RUBBLE_REBUILD_DISCOUNT,
                construction.totalTime, 0.001f);
        assertEquals(GameConstants.BUILDING_RUBBLE_REBUILD_DISCOUNT, construction.costMultiplier, 0.001f);
    }

    // ---- Приказы юнитам ----

    @Test
    void moveOrderStartsMovementOfOwnUnitOnly() {
        int ownBuilderId = builder(0).unitId;
        int enemyBuilderId = builder(1).unitId;

        game.handleMoveUnit(player0, move(enemyBuilderId, 7000f, 7000f, false));
        assertFalse(unitById(enemyBuilderId).moving, "чужим юнитом командовать нельзя");

        game.handleMoveUnit(player0, move(ownBuilderId, 1200f, 1300f, false));
        UnitSnapshot builder = unitById(ownBuilderId);
        assertTrue(builder.moving);
        QueuedOrderPoint current = builder.queuedOrders.get(0);
        assertEquals(QueuedOrder.Type.MOVE.ordinal(), current.type);
        assertEquals(1200f, current.x, 0.001f);
        assertEquals(1300f, current.y, 0.001f);
    }

    @Test
    void queuedMoveIsAppendedAndClampedToMap() {
        int builderId = builder(0).unitId;
        game.handleMoveUnit(player0, move(builderId, 1200f, 1300f, false));
        game.handleMoveUnit(player0, move(builderId, -500f, GameConstants.MAP_HEIGHT + 500f, true));

        List<QueuedOrderPoint> orders = unitById(builderId).queuedOrders;
        assertEquals(2, orders.size(), "текущий приказ + один в очереди");
        QueuedOrderPoint queued = orders.get(1);
        assertEquals(0f, queued.x, 0.001f);
        assertEquals(GameConstants.MAP_HEIGHT, queued.y, 0.001f);
    }

    @Test
    void unitMovesTowardTargetOverTime() {
        UnitSnapshot before = builder(0);
        game.handleMoveUnit(player0, move(before.unitId, before.x + 300f, before.y, false));

        tickFor(0.5f);

        UnitSnapshot after = unitById(before.unitId);
        assertTrue(after.x > before.x + 1f, "строитель сдвинулся к цели");
    }

    @Test
    void attackOrderIsAcceptedOnEnemyAndRejectedOnOwnUnits() {
        int builderId = builder(0).unitId;

        game.handleAttackUnit(player0, attack(builderId, home(0).unitId));
        assertEquals(-1, currentOrderType(builderId), "свой дом атаковать нельзя");

        game.handleAttackUnit(player0, attack(builderId, builder(1).unitId));
        assertEquals(QueuedOrder.Type.ATTACK.ordinal(), currentOrderType(builderId));
    }

    @Test
    void moveOrderCancelsAttack() {
        int builderId = builder(0).unitId;
        game.handleAttackUnit(player0, attack(builderId, builder(1).unitId));
        game.handleMoveUnit(player0, move(builderId, 1200f, 1300f, false));
        assertEquals(QueuedOrder.Type.MOVE.ordinal(), currentOrderType(builderId));
    }

    @Test
    void repairOrderRequiresDamagedOwnBuilding() {
        int builderId = builder(0).unitId;
        int homeId = home(0).unitId;
        RepairOrderRequest request = new RepairOrderRequest();
        request.builderUnitId = builderId;
        request.targetBuildingUnitId = homeId;

        game.handleRepairOrder(player0, request);
        assertEquals(-1, currentOrderType(builderId), "целое здание чинить нечего");

        HealthComponent health = game.unitById(homeId).getComponent(HealthComponent.class);
        health.currentHealth = health.maxHealth / 2;
        game.handleRepairOrder(player0, request);

        assertEquals(QueuedOrder.Type.REPAIR.ordinal(), currentOrderType(builderId));
        RepairComponent repair = game.unitById(homeId).getComponent(RepairComponent.class);
        assertNotNull(repair);
        assertEquals(50, repair.totalIronCost, "стоимость ремонта = процент повреждений");
    }

    @Test
    void collectOrderTargetsWrecksOnly() {
        int builderId = builder(0).unitId;
        CollectOrderRequest request = new CollectOrderRequest();
        request.builderUnitId = builderId;
        request.targetWreckUnitId = home(1).unitId; // не обломки
        game.handleCollectOrder(player0, request);
        assertEquals(-1, currentOrderType(builderId));

        int plantId = placePowerPlantForPlayer0();
        game.handleDemolishBuilding(player0, demolish(plantId));
        request.targetWreckUnitId = buildingsOf(GameConstants.NEUTRAL_OWNER_ID, BuildingType.WRECK).get(0).unitId;
        game.handleCollectOrder(player0, request);
        assertEquals(QueuedOrder.Type.COLLECT.ordinal(), currentOrderType(builderId));
    }

    @Test
    void patrolIsSetUpAndCancelledByAnyOtherOrder() {
        int builderId = builder(0).unitId;
        PatrolUnitRequest patrol = new PatrolUnitRequest();
        patrol.unitId = builderId;
        patrol.waypoints = new ArrayList<>(Arrays.asList(new PatrolPoint(1200f, 1200f), new PatrolPoint(1600f, 1200f)));
        game.handlePatrolUnit(player0, patrol);

        UnitSnapshot builder = unitById(builderId);
        assertEquals(2, builder.patrolPoints.size());
        assertTrue(builder.moving);

        game.handleMoveUnit(player0, move(builderId, 1000f, 1000f, true)); // даже приказ в очередь отменяет патруль
        assertTrue(unitById(builderId).patrolPoints.isEmpty());
    }

    @Test
    void emptyPatrolRouteIsIgnored() {
        int builderId = builder(0).unitId;
        PatrolUnitRequest patrol = new PatrolUnitRequest();
        patrol.unitId = builderId;
        patrol.waypoints = new ArrayList<>();
        game.handlePatrolUnit(player0, patrol);
        assertTrue(unitById(builderId).patrolPoints.isEmpty());
        assertFalse(unitById(builderId).moving);
    }

    // ---- Снос и конец игры ----

    @Test
    void cannotDemolishEnemyBuilding() {
        int enemyHomeId = home(1).unitId;
        game.handleDemolishBuilding(player0, demolish(enemyHomeId));
        assertNotNull(unitById(enemyHomeId));
    }

    @Test
    void losingHomeEndsGameWithOtherPlayerAsWinner() {
        game.handleDemolishBuilding(player0, demolish(home(0).unitId));
        assertFalse(game.isGameOver(), "конец игры определяется на следующем тике");

        game.tick(0.01f);

        assertTrue(game.isGameOver());
        for (FakeConnection connection : new FakeConnection[]{player0, player1}) {
            GameOverMessage message = connection.lastSent(GameOverMessage.class);
            assertNotNull(message, "GameOverMessage получают оба игрока");
            assertFalse(message.draw);
            assertEquals(1, message.winnerPlayerId);
        }
    }

    @Test
    void ordersAreIgnoredAfterGameOver() {
        int builderId = builder(1).unitId;
        game.handleDemolishBuilding(player0, demolish(home(0).unitId));
        game.tick(0.01f);

        game.handleMoveUnit(player1, move(builderId, 7000f, 7000f, false));
        assertFalse(unitById(builderId).moving);
    }

    @Test
    void disconnectRemovesAllPlayerEntitiesAndOpponentWins() {
        UnitSnapshot enemyHome = home(1);
        assertTrue(Pathfinding.isBlocked(enemyHome.x, enemyHome.y, false), "дом — препятствие для A*");

        game.handleDisconnect(player1);
        for (UnitSnapshot unit : snapshot().units) {
            assertTrue(unit.ownerId != 1, "у отключившегося игрока не осталось ни юнитов, ни зданий");
        }
        assertFalse(Pathfinding.isBlocked(enemyHome.x, enemyHome.y, false), "препятствие снятого дома убрано");

        player1.connected = false;
        game.tick(0.01f);

        assertTrue(game.isGameOver());
        GameOverMessage message = player0.lastSent(GameOverMessage.class);
        assertNotNull(message);
        assertEquals(0, message.winnerPlayerId);
        assertNull(player1.lastSent(GameOverMessage.class), "в отключённое соединение не пишем");
    }

    @Test
    void startInvokesGameEndListenerAfterGameOver() {
        game.handleDemolishBuilding(player0, demolish(home(0).unitId));
        game.tick(0.01f);
        // runLoop сразу выйдет из while (gameOver уже true) и подождёт
        // только POST_GAME_OVER_DELAY_SECONDS перед вызовом слушателя.
        game.start();
        assertTrue(gameEndListenerCalled);
    }

    // ---- Туман войны ----

    @Test
    void fogRevealsAreaAroundOwnUnitsButNotEnemyBase() {
        FogSnapshot fog = null;
        for (FogSnapshot candidate : snapshot().fog) {
            if (candidate.playerId == 0) {
                fog = candidate;
            }
        }
        assertNotNull(fog);
        assertEquals(GameConstants.FOG_GRID_WIDTH * GameConstants.FOG_GRID_HEIGHT, fog.revealed.length);

        UnitSnapshot ownBuilder = builder(0);
        assertTrue(fog.revealed[fogCell(ownBuilder.x, ownBuilder.y)], "клетка под своим строителем видна");

        UnitSnapshot enemyHome = home(1);
        assertFalse(fog.revealed[fogCell(enemyHome.x, enemyHome.y)], "база противника в тумане");
    }

    private static int fogCell(float x, float y) {
        int cellX = (int) (x / GameConstants.FOG_GRID_CELL_SIZE);
        int cellY = (int) (y / GameConstants.FOG_GRID_CELL_SIZE);
        return cellY * GameConstants.FOG_GRID_WIDTH + cellX;
    }
}
