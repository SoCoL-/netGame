package ru.socol.supreme.server;

import com.badlogic.gdx.math.Vector2;
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
import ru.socol.supreme.shared.network.messages.DemolishBuildingRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.FogSnapshot;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;

import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Артиллерийская башня: стройка снарядов с расходом электричества,
 * содержание при полном запасе, проверки приказа на выстрел (снаряды,
 * электричество, дальность — но не видимость), доворот ствола до конуса
 * стрельбы перед выстрелом и взрыв при падении. Тот же подход, что и в
 * GameServerTest: без сети, симуляция шагается вручную, числа баланса — из
 * BuildingDefinitions.
 */
class ArtilleryTest {

    private static final BuildingType ARTILLERY = BuildingType.ARTILLERY;
    /** Свободное место на стороне игрока 0 — как FREE_SPOT в GameServerTest. */
    private static final float TOWER_X = 1500f;
    private static final float TOWER_Y = 1500f;
    /**
     * Точка по направлению, куда ствол смотрит сразу после постройки (к
     * центру карты, 45°) — в неё башня стреляет без доворота. Далеко от
     * своих юнитов и зданий, поэтому ещё не разведана.
     */
    private static final float AHEAD_X = TOWER_X + 1000f;
    private static final float AHEAD_Y = TOWER_Y + 1000f;

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

    /** Разброс выключен: nextFloat() == 0 даёт нулевой радиус отклонения — снаряд падает точно в цель. */
    private void disableSpread() {
        game.setArtilleryRandom(new Random() {
            @Override
            public float nextFloat() {
                return 0f;
            }
        });
    }

    /** Отдаёт приказ и ждёт, пока башня довернётся, выстрелит и снаряд упадёт. */
    private void fireAndWaitForImpact(int towerId, float x, float y) {
        fire(towerId, x, y);
        for (int i = 0; i < 400 && (!artilleryOf(towerId).pendingTargets.isEmpty() || game.artilleryShellsInFlight() > 0); i++) {
            game.tick(0.05f);
        }
        assertTrue(artilleryOf(towerId).pendingTargets.isEmpty(), "башня выстрелила");
        assertEquals(0, game.artilleryShellsInFlight(), "снаряд упал");
    }

    private float barrelErrorDegrees(int towerId, float x, float y) {
        float desired = (float) Math.atan2(y - TOWER_Y, x - TOWER_X);
        float difference = desired - artilleryOf(towerId).barrelAngle;
        while (difference > Math.PI) {
            difference -= 2 * Math.PI;
        }
        while (difference <= -Math.PI) {
            difference += 2 * Math.PI;
        }
        return (float) Math.toDegrees(Math.abs(difference));
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
        assertEquals(250f, BuildingDefinitions.shellSpreadRadiusFor(ARTILLERY), 0.001f, "радиус круга разброса");
        assertEquals(1.5f, BuildingDefinitions.shotCooldownFor(ARTILLERY), 0.001f);
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
        fire(towerId, AHEAD_X, AHEAD_Y);
        assertNotNull(player0.lastSent(ErrorResponse.class));
        assertTrue(artilleryOf(towerId).pendingTargets.isEmpty());
    }

    @Test
    void cannotFireWithoutElectricityForTheShot() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = BuildingDefinitions.shotElectricityCostFor(ARTILLERY) - 1f;

        fire(towerId, AHEAD_X, AHEAD_Y);

        assertNotNull(player0.lastSent(ErrorResponse.class));
        assertTrue(artilleryOf(towerId).pendingTargets.isEmpty(), "приказ не принят");
    }

    @Test
    void cannotFireBeyondRange() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        float range = BuildingDefinitions.artilleryRangeFor(ARTILLERY);

        fire(towerId, TOWER_X + range + 50f, TOWER_Y);

        assertNotNull(player0.lastSent(ErrorResponse.class));
        assertTrue(artilleryOf(towerId).pendingTargets.isEmpty());
    }

    @Test
    void orderIntoFogIsAcceptedAndShotPaysShellAndElectricity() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 3;
        resources().electricity = 1000f;
        assertFalse(isRevealedForPlayer0(AHEAD_X, AHEAD_Y), "цель — в неисследованной области");
        disableSpread();

        fire(towerId, AHEAD_X, AHEAD_Y);

        assertNull(player0.lastSent(ErrorResponse.class));
        assertEquals(1, artilleryOf(towerId).pendingTargets.size(), "приказ принят, стреляет уже система");
        assertEquals(3, artilleryOf(towerId).shells);
        assertEquals(1000f, resources().electricity, 0.001f);

        game.tick(0.01f); // цель прямо по стволу — выстрел на ближайшем тике

        assertEquals(2, artilleryOf(towerId).shells);
        assertTrue(artilleryOf(towerId).pendingTargets.isEmpty());
        assertEquals(1000f - BuildingDefinitions.shotElectricityCostFor(ARTILLERY), resources().electricity, 0.5f);
        assertEquals(1, game.artilleryShellsInFlight());
        for (FakeConnection connection : new FakeConnection[]{player0, player1}) {
            ProjectileFiredEvent event = connection.lastSent(ProjectileFiredEvent.class);
            assertNotNull(event);
            assertTrue(event.artillery);
            assertEquals(AHEAD_X, event.toX, 0.001f);
            assertEquals(AHEAD_Y, event.toY, 0.001f);
            assertTrue(event.flightTime > 0f);
        }
    }

    @Test
    void ordersCannotExceedReadyShells() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 2;
        resources().electricity = 1000f;

        fire(towerId, AHEAD_X, AHEAD_Y);
        fire(towerId, AHEAD_X + 50f, AHEAD_Y);
        assertNull(player0.lastSent(ErrorResponse.class));
        fire(towerId, AHEAD_X + 100f, AHEAD_Y);

        assertNotNull(player0.lastSent(ErrorResponse.class), "третий приказ — снарядов уже нет");
        assertEquals(2, artilleryOf(towerId).pendingTargets.size());
    }

    @Test
    void queuedOrdersAreFiredOneByOne() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 3;
        resources().electricity = 1000f;
        fire(towerId, AHEAD_X, AHEAD_Y);
        fire(towerId, AHEAD_X + 100f, AHEAD_Y);
        fire(towerId, AHEAD_X, AHEAD_Y + 100f);

        tickFor(2 * BuildingDefinitions.shotCooldownFor(ARTILLERY) + 0.5f);

        assertTrue(artilleryOf(towerId).pendingTargets.isEmpty());
        assertEquals(0, artilleryOf(towerId).shells);
        assertEquals(3, player0.sentOf(ProjectileFiredEvent.class).size());
    }

    @Test
    void shotsAreSeparatedByCooldown() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 2;
        resources().electricity = 1000f;
        float cooldown = BuildingDefinitions.shotCooldownFor(ARTILLERY);
        fire(towerId, AHEAD_X, AHEAD_Y);
        fire(towerId, AHEAD_X, AHEAD_Y); // та же точка — доворачиваться не нужно

        game.tick(0.01f);
        assertEquals(1, player0.sentOf(ProjectileFiredEvent.class).size(), "первый выстрел сразу");

        tickFor(cooldown - 0.2f);
        assertEquals(1, player0.sentOf(ProjectileFiredEvent.class).size(), "второй ждёт перезарядку");

        tickFor(0.3f);
        assertEquals(2, player0.sentOf(ProjectileFiredEvent.class).size(), "после перезарядки — второй выстрел");
    }

    // ---- Конус стрельбы и поворот ствола ----

    @Test
    void towerTurnsUntilTargetIsInsideFiringConeBeforeFiring() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        // 90° левее начального направления ствола (45° -> 135°).
        float targetX = TOWER_X - 700f;
        float targetY = TOWER_Y + 700f;
        float halfCone = BuildingDefinitions.firingConeDegreesFor(ARTILLERY) / 2f;
        float turnSpeed = BuildingDefinitions.barrelTurnSpeedFor(ARTILLERY);
        float timeToCone = (90f - halfCone) / turnSpeed;

        fire(towerId, targetX, targetY);
        tickFor(timeToCone - 0.15f);
        assertEquals(1, artilleryOf(towerId).pendingTargets.size(), "цель ещё вне конуса — не стреляем");
        assertTrue(barrelErrorDegrees(towerId, targetX, targetY) > halfCone);

        tickFor(0.3f);
        assertTrue(artilleryOf(towerId).pendingTargets.isEmpty(), "довернулись до конуса — выстрел");
        assertTrue(barrelErrorDegrees(towerId, targetX, targetY) <= halfCone + 0.01f);
    }

    @Test
    void halfTurnFromEdgeToEdgeTakesThreeSeconds() {
        int towerId = buildTower();
        // Цель прямо позади ствола (45° -> 225°): максимальный разворот, 180°.
        float targetX = TOWER_X - 700f;
        float targetY = TOWER_Y - 700f;
        artilleryOf(towerId).shells = 0; // без снарядов — чтобы видеть чистый поворот
        artilleryOf(towerId).pendingTargets.add(new Vector2(targetX, targetY));
        resources().electricity = 0f;

        tickFor(2.8f);
        assertTrue(barrelErrorDegrees(towerId, targetX, targetY) > 5f, "за 2.8 с ещё не развернулись");

        tickFor(0.3f);
        assertTrue(barrelErrorDegrees(towerId, targetX, targetY) < 0.5f, "за ~3 с развернулись на 180°");
    }

    @Test
    void aimedTowerWaitsForElectricityInsteadOfDroppingOrder() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        fire(towerId, AHEAD_X, AHEAD_Y);
        resources().electricity = 0f;

        tickFor(1f);
        assertEquals(1, artilleryOf(towerId).pendingTargets.size(), "нечем платить за выстрел — ждём");
        assertEquals(1, artilleryOf(towerId).shells);

        resources().electricity = 1000f;
        game.tick(0.01f);
        assertTrue(artilleryOf(towerId).pendingTargets.isEmpty());
        assertEquals(0, artilleryOf(towerId).shells);
    }

    @Test
    void snapshotCarriesBarrelDirectionAndPendingTargets() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 0f;
        artilleryOf(towerId).pendingTargets.add(new Vector2(AHEAD_X, AHEAD_Y));

        UnitSnapshot tower = unitById(towerId);

        float expected = (float) Math.sqrt(0.5);
        assertEquals(expected, tower.turretDirX, 0.01f, "ствол к центру карты");
        assertEquals(expected, tower.turretDirY, 0.01f);
        assertEquals(1, tower.artilleryTargets.size());
        assertEquals(AHEAD_X, tower.artilleryTargets.get(0).x, 0.001f);
    }

    @Test
    void cannotFireEnemyTower() {
        int towerId = buildTower();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        ArtilleryFireRequest request = new ArtilleryFireRequest();
        request.buildingUnitId = towerId;
        request.x = AHEAD_X;
        request.y = AHEAD_Y;
        game.handleArtilleryFire(player1, request);
        assertTrue(artilleryOf(towerId).pendingTargets.isEmpty());
    }

    // ---- Разброс ----

    @Test
    void shellsLandWithinSpreadCircleAroundTarget() {
        game.setArtilleryRandom(new Random(42));
        int towerId = buildTower();
        int shots = BuildingDefinitions.shellCapacityFor(ARTILLERY);
        artilleryOf(towerId).shells = shots;
        resources().electricity = 100000f;
        float maxDeviation = BuildingDefinitions.shellSpreadRadiusFor(ARTILLERY);

        for (int i = 0; i < shots; i++) {
            fire(towerId, AHEAD_X, AHEAD_Y);
        }
        tickFor(shots * BuildingDefinitions.shotCooldownFor(ARTILLERY) + 1f);

        assertEquals(shots, player0.sentOf(ProjectileFiredEvent.class).size());
        float largestDeviation = 0f;
        for (ProjectileFiredEvent event : player0.sentOf(ProjectileFiredEvent.class)) {
            float deviation = (float) Math.hypot(event.toX - AHEAD_X, event.toY - AHEAD_Y);
            assertTrue(deviation <= maxDeviation + 0.01f,
                    "отклонение " + deviation + " больше радиуса разброса " + maxDeviation);
            largestDeviation = Math.max(largestDeviation, deviation);
        }
        assertTrue(largestDeviation > 1f, "разброс реально есть — снаряды падают не точно в цель");
    }

    // ---- Взрыв ----

    @Test
    void shellDamagesTargetsInSplashOnlyWhenItLands() {
        int towerId = buildTower();
        disableSpread();
        artilleryOf(towerId).shells = 2;
        resources().electricity = 1000f;
        UnitSnapshot builder = find(0, false, UnitType.BUILDER.ordinal()); // взрыв задевает и своих
        int maxHealth = UnitDefinitions.healthFor(UnitType.BUILDER);
        int damage = BuildingDefinitions.shellDamageFor(ARTILLERY);

        fire(towerId, builder.x, builder.y);
        for (int i = 0; i < 200 && game.artilleryShellsInFlight() == 0; i++) {
            game.tick(0.05f); // доворот до цели
        }
        assertEquals(1, game.artilleryShellsInFlight(), "выстрелили");
        assertEquals(maxHealth, unitById(builder.unitId).health, "урон — при падении, а не при выстреле");

        tickFor(flightTimeTo(builder) + 0.1f);

        assertEquals(0, game.artilleryShellsInFlight());
        assertEquals(maxHealth - damage, game.unitById(builder.unitId).getComponent(HealthComponent.class).currentHealth);
    }

    @Test
    void killedByShellUnitLeavesWreck() {
        int towerId = buildTower();
        disableSpread();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        UnitSnapshot builder = find(0, false, UnitType.BUILDER.ordinal());
        game.unitById(builder.unitId).getComponent(HealthComponent.class).currentHealth = 1;

        fireAndWaitForImpact(towerId, builder.x, builder.y);

        assertNull(game.unitById(builder.unitId), "строитель погиб");
        assertNotNull(find(GameConstants.NEUTRAL_OWNER_ID, true, BuildingType.WRECK.ordinal()), "остались обломки");
    }

    @Test
    void shellMissesTargetsOutsideSplash() {
        int towerId = buildTower();
        disableSpread();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        UnitSnapshot builder = find(0, false, UnitType.BUILDER.ordinal());
        float farX = builder.x + BuildingDefinitions.shellSplashRadiusFor(ARTILLERY) * 3f;

        fireAndWaitForImpact(towerId, farX, builder.y);

        assertEquals(UnitDefinitions.healthFor(UnitType.BUILDER),
                game.unitById(builder.unitId).getComponent(HealthComponent.class).currentHealth);
    }

    /** Ставит своё здание type в (x, y) сразу достроенным и с полным здоровьем. */
    private int placeFinished(BuildingType type, float x, float y) {
        PlaceBuildingRequest request = new PlaceBuildingRequest();
        request.buildingType = type.ordinal();
        request.x = x;
        request.y = y;
        game.handlePlaceBuilding(player0, request);
        for (UnitSnapshot unit : game.buildWorldSnapshot().units) {
            if (unit.building && unit.buildingType == type.ordinal() && unit.x == x && unit.y == y) {
                game.unitById(unit.unitId).remove(ConstructionComponent.class);
                HealthComponent health = game.unitById(unit.unitId).getComponent(HealthComponent.class);
                health.currentHealth = health.maxHealth;
                return unit.unitId;
            }
        }
        throw new AssertionError("здание " + type + " не поставлено в (" + x + ", " + y + ")");
    }

    /** Сносит своё здание — на его месте остаются именные руины. */
    private void demolish(int buildingId) {
        DemolishBuildingRequest request = new DemolishBuildingRequest();
        request.buildingUnitId = buildingId;
        game.handleDemolishBuilding(player0, request);
    }

    /** Руины здания type в точке (x, y), или null. */
    private UnitSnapshot ruinsAt(BuildingType type, float x, float y) {
        for (UnitSnapshot unit : game.buildWorldSnapshot().units) {
            if (unit.rubbleOriginalBuildingType == type.ordinal() && unit.x == x && unit.y == y) {
                return unit;
            }
        }
        return null;
    }

    // ---- Руины ----

    @Test
    void shellTakesIronFromRuinsAndCanWipeThemOut() {
        int towerId = buildTower();
        disableSpread();
        artilleryOf(towerId).shells = 2;
        resources().electricity = 1000f;
        int damage = BuildingDefinitions.shellDamageFor(ARTILLERY);
        // Богатые руины (другой артиллерии) переживут один снаряд, бедные (турели) — нет.
        demolish(placeFinished(ARTILLERY, AHEAD_X, AHEAD_Y));
        float turretX = AHEAD_X + 800f;
        demolish(placeFinished(BuildingType.TURRET, turretX, AHEAD_Y - 800f));
        int richIron = ruinsAt(ARTILLERY, AHEAD_X, AHEAD_Y).health;
        assertTrue(richIron > damage, "в руинах артиллерии железа больше, чем урон снаряда");
        assertTrue(ruinsAt(BuildingType.TURRET, turretX, AHEAD_Y - 800f).health <= damage);

        fireAndWaitForImpact(towerId, AHEAD_X, AHEAD_Y);
        fireAndWaitForImpact(towerId, turretX, AHEAD_Y - 800f);

        assertEquals(richIron - damage, ruinsAt(ARTILLERY, AHEAD_X, AHEAD_Y).health, "снаряд отнял железо у руин");
        assertNull(ruinsAt(BuildingType.TURRET, turretX, AHEAD_Y - 800f), "бедные руины выбиты насовсем");
    }

    @Test
    void shellThatDestroysBuildingDoesNotHitItsFreshRuins() {
        int towerId = buildTower();
        disableSpread();
        artilleryOf(towerId).shells = 1;
        resources().electricity = 1000f;
        int turretId = placeFinished(BuildingType.TURRET, AHEAD_X, AHEAD_Y);
        game.unitById(turretId).getComponent(HealthComponent.class).currentHealth = 1;

        fireAndWaitForImpact(towerId, AHEAD_X, AHEAD_Y);

        assertNull(game.unitById(turretId), "турель уничтожена");
        UnitSnapshot ruins = ruinsAt(BuildingType.TURRET, AHEAD_X, AHEAD_Y);
        assertNotNull(ruins, "её руины остались");
        assertEquals(ruins.maxHealth, ruins.health, "руины от этого же снаряда урона не получили");
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
