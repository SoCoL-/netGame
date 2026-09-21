package ru.socol.supreme.server;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.PooledEngine;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Server;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingPlacement;
import ru.socol.supreme.shared.BuildingSizes;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.QueuedOrder;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.AircraftComponent;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.BuildOrderComponent;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.CollectOrderComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OrderQueueComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PathComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.RepairComponent;
import ru.socol.supreme.shared.components.RepairOrderComponent;
import ru.socol.supreme.shared.components.TurretComponent;
import ru.socol.supreme.shared.components.UnitComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.components.WreckComponent;
import ru.socol.supreme.shared.network.NetworkRegistration;
import ru.socol.supreme.shared.pathfinding.Pathfinding;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.BuildOrderRequest;
import ru.socol.supreme.shared.network.messages.CollectOrderRequest;
import ru.socol.supreme.shared.network.messages.DemolishBuildingRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.FogSnapshot;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.PathPoint;
import ru.socol.supreme.shared.network.messages.PlaceIronMineRequest;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.QueuedOrderPoint;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;
import ru.socol.supreme.shared.network.messages.RepairOrderRequest;
import ru.socol.supreme.shared.network.messages.SetRallyPointRequest;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;
import ru.socol.supreme.shared.network.messages.WorldSnapshot;
import ru.socol.supreme.shared.pathfinding.Pathfinding;
import ru.socol.supreme.shared.pathfinding.SpatialHashGrid;
import ru.socol.supreme.shared.systems.AggroSystem;
import ru.socol.supreme.shared.systems.AircraftMovementSystem;
import ru.socol.supreme.shared.systems.BuildSystem;
import ru.socol.supreme.shared.systems.CollisionSystem;
import ru.socol.supreme.shared.systems.CombatSystem;
import ru.socol.supreme.shared.systems.ConstructionSystem;
import ru.socol.supreme.shared.systems.MovementSystem;
import ru.socol.supreme.shared.systems.OrderQueueSystem;
import ru.socol.supreme.shared.systems.ProductionSystem;
import ru.socol.supreme.shared.systems.RepairSystem;
import ru.socol.supreme.shared.systems.ResourceExtractionSystem;
import ru.socol.supreme.shared.systems.ScavengeSystem;
import ru.socol.supreme.shared.systems.TurretAimSystem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Авторитетный игровой сервер: владеет Ashley-движком, симулирует движение и
 * бой, применяет правила игры (максимум 2 игрока, максимум 300 юнитов на
 * всю игру суммарно, карта 8000x8000) и с фиксированной частотой рассылает
 * снапшоты мира всем клиентам. Как только у одного из игроков уничтожен дом
 * (не казарма стрелков — см. spawnHomeAndBuilder) — объявляет победителя и
 * замораживает симуляцию.
 *
 * Это "каркас" — минимальный, но рабочий скелет. Он не занимается
 * аутентификацией, реконнектом с сохранением состояния, unreliable-каналом
 * для снапшотов и т.п. — см. README для списка того, что стоит добавить
 * перед реальным продакшеном.
 */
public class GameServer {

    private final Server server = new Server(GameConstants.NETWORK_WRITE_BUFFER_SIZE, GameConstants.NETWORK_OBJECT_BUFFER_SIZE);
    private final Engine engine = new PooledEngine();

    /** connectionId -> playerId (0 или 1) */
    private final Map<Integer, Integer> connectionToPlayer = new HashMap<>();

    /**
     * unitId -> Entity, единственный реестр живых юнитов И зданий (здание —
     * это просто неподвижная, неатакующая сущность с тем же id-пространством
     * и в той же карте — см. BuildingComponent). Передаётся по ссылке в
     * CombatSystem, чтобы находить цель атаки по id и убирать убитых.
     */
    private final Map<Integer, Entity> unitsById = new HashMap<>();

    /** playerId -> unitId их ДОМА (не казармы стрелков!). Нужно, чтобы проверять условие победы. */
    private final Map<Integer, Integer> buildingIdByPlayer = new HashMap<>();

    /**
     * playerId -> его ресурсы. Авторитетное состояние — меняет
     * ResourceExtractionSystem по мере работы зданий добычи (шахта
     * железа, электростанция), рассылается клиенту через
     * WorldSnapshot.playerResources.
     */
    private final Map<Integer, PlayerResources> resourcesByPlayer = new HashMap<>();

    /**
     * playerId -> {iron, electricity} на момент ПРЕДЫДУЩЕЙ рассылки
     * снапшота — только для того, чтобы посчитать PlayerResources
     * .ironRate/electricityRate (см. её javadoc) как реально измеренную
     * разницу, а не отдельную "теоретическую" формулу из состояния
     * зданий, рискующую разойтись с ProductionSystem/ResourceExtractionSystem.
     */
    private final Map<Integer, float[]> previousResourceValues = new HashMap<>();

    private final boolean[] playerSlotUsed = new boolean[GameConstants.MAX_PLAYERS];

    private final AtomicInteger unitIdSequence = new AtomicInteger(1);

    private float snapshotAccumulator = 0f;

    /**
     * Сколько РЕАЛЬНОГО времени прошло с прошлого расчёта ставки ресурсов
     * — не то же самое, что snapshotAccumulator: тот сбрасывается в 0 по
     * достижении порога (излишек "перелёта" через SNAPSHOT_RATE просто
     * выбрасывается), а этот нужен ТОЧНО для той величины, на которую
     * реально успели измениться resources.iron/electricity — Thread.sleep
     * не гарантирует точность (особенно на Windows, где таймер обычно
     * грубее запрошенных ~33мс), так что настоящий интервал между
     * рассылками снапшота гуляет вокруг номинального SNAPSHOT_RATE — то 2
     * тика, то 3. Делить на номинальную константу вместо этого давало
     * скачущую, неверную ставку (баг, воспроизводился как "у одной
     * электростанции доход скачет между +151 и +225 вместо стабильных
     * +150").
     */
    private float timeSinceLastResourceRateUpdate = 0f;

    /**
     * Реально прошедшее время с последнего пересчёта тумана войны — та же
     * причина, что и у timeSinceLastResourceRateUpdate чуть выше: считаем
     * его не в каждом тике симуляции, а раз в SNAPSHOT_RATE (заодно с
     * рассылкой снапшота, см. broadcastSnapshot), но на реально
     * прошедшее время, а не на номинальный интервал.
     */
    private float timeSinceLastFogUpdate = 0f;

    /**
     * На игрока — плоская сетка (см. GameConstants.FOG_GRID_WIDTH/HEIGHT)
     * "сколько секунд прошло с тех пор, как эту клетку последний раз видел
     * хотя бы один юнит или здание этого игрока". Изначально всё в тумане
     * (см. updateFogOfWar, где массив заводится) — Float.MAX_VALUE, а не 0,
     * иначе клетка казалась бы видна прямо со старта партии.
     */
    private final Map<Integer, float[]> fogTimeSinceVisible = new HashMap<>();

    private volatile boolean gameOver = false;

    public GameServer() {
        // Приоритет: AggroSystem (-10) до CombatSystem (0) до ProductionSystem (1)
        // до ConstructionSystem (2) до ResourceExtractionSystem (3) до
        // MovementSystem (10) до CollisionSystem (20) — см. их конструкторы.
        //
        // Обе системы ищут соседей через SpatialHashGrid вместо перебора
        // unitsById целиком (O(n) на юнита вместо O(n²) на всех) — у каждой
        // своя сетка со своим размером ячейки, потому что типичный радиус
        // запроса у них совсем разный: агрессия ищет в радиусе дальности
        // атаки (у стрелка 210), коллизии — в радиусе здания/юнита (порядка
        // 60-80). Один общий размер ячейки одинаково плохо подошёл бы обеим.
        SpatialHashGrid aggroGrid = new SpatialHashGrid(UnitDefinitions.maxAttackRadius());
        SpatialHashGrid collisionGrid = new SpatialHashGrid(BuildingDefinitions.maxInteractionRadius());

        engine.addSystem(new AggroSystem(unitsById, aggroGrid));
        engine.addSystem(new CombatSystem(unitsById, this::handleShotFired, this::spawnWreck));
        engine.addSystem(new TurretAimSystem(unitsById));
        engine.addSystem(new BuildSystem(unitsById, resourcesByPlayer));
        engine.addSystem(new RepairSystem(unitsById, resourcesByPlayer));
        engine.addSystem(new ScavengeSystem(unitsById, resourcesByPlayer));
        engine.addSystem(new ProductionSystem(unitsById, resourcesByPlayer, this::createUnit));
        engine.addSystem(new ConstructionSystem());
        engine.addSystem(new ResourceExtractionSystem(unitsById, resourcesByPlayer));
        engine.addSystem(new MovementSystem());
        engine.addSystem(new AircraftMovementSystem(unitsById));
        engine.addSystem(new OrderQueueSystem(this::startAttackOrder, this::assignBuilderToBuild,
                this::assignBuilderToRepair, this::assignBuilderToCollect));
        engine.addSystem(new CollisionSystem(unitsById, collisionGrid));
    }

    public void start() throws IOException {
        NetworkRegistration.register(server);
        server.addListener(new ServerNetworkListener(this));
        // TCP-only: сейчас всё (включая снапшоты) шлётся через sendTCP, а
        // udpPort в bind()/connect() добавляет клиенту лишнюю UDP-handshake
        // стадию без какой-либо пользы — см. комментарий в GameClient.connect().
        // Если добавите частые снапшоты по UDP, возвращайте
        // server.bind(TCP_PORT, UDP_PORT) и udpPort в GameClient.connect().
        server.bind(GameConstants.TCP_PORT);
        server.start();

        System.out.println("Server started on TCP " + GameConstants.TCP_PORT);

        runLoop();
    }

    private void runLoop() {
        long lastTimeNanos = System.nanoTime();
        while (true) {
            long now = System.nanoTime();
            float deltaTime = (now - lastTimeNanos) / 1_000_000_000f;
            lastTimeNanos = now;

            // synchronized: CombatSystem/ProductionSystem внутри engine.update()
            // мутируют unitsById — тот же объект, что меняют handleQueueUnit /
            // handleMoveUnit / handleAttackUnit / handleDisconnect из сетевого
            // потока KryoNet. Без этой синхронизации это гонка данных.
            synchronized (this) {
                if (!gameOver) {
                    engine.update(deltaTime);
                    checkGameOver();

                    snapshotAccumulator += deltaTime;
                    timeSinceLastResourceRateUpdate += deltaTime;
                    timeSinceLastFogUpdate += deltaTime;
                    if (snapshotAccumulator >= GameConstants.SNAPSHOT_RATE) {
                        snapshotAccumulator = 0f;
                        broadcastSnapshot();
                    }
                }
            }

            sleep(GameConstants.SERVER_TICK_RATE);
        }
    }

    private void sleep(float seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- Жизненный цикл игрока ----

    synchronized JoinResponse handleJoin(Connection connection, JoinRequest request) {
        int playerId = -1;
        for (int i = 0; i < GameConstants.MAX_PLAYERS; i++) {
            if (!playerSlotUsed[i]) {
                playerId = i;
                break;
            }
        }

        JoinResponse response = new JoinResponse();
        if (playerId == -1) {
            response.accepted = false;
            response.message = "Server full (" + GameConstants.MAX_PLAYERS + " players max)";
            return response;
        }

        playerSlotUsed[playerId] = true;
        connectionToPlayer.put(connection.getID(), playerId);
        resourcesByPlayer.put(playerId, startingResources(playerId));
        spawnHomeAndBuilder(playerId);

        response.accepted = true;
        response.playerId = playerId;
        response.message = "Welcome, player " + playerId;
        return response;
    }

    synchronized void handleDisconnect(Connection connection) {
        Integer playerId = connectionToPlayer.remove(connection.getID());
        if (playerId == null) {
            return;
        }

        int finalPlayerId = playerId;
        unitsById.values().removeIf(unit -> {
            OwnerComponent owner = unit.getComponent(OwnerComponent.class);
            boolean owned = owner != null && owner.playerId == finalPlayerId;
            if (owned) {
                engine.removeEntity(unit);
                if (unit.getComponent(BuildingComponent.class) != null) {
                    Pathfinding.removeBuildingObstacle(unit.getComponent(UnitComponent.class).unitId);
                }
            }
            return owned;
        });

        buildingIdByPlayer.remove(playerId);
        resourcesByPlayer.remove(playerId);
        previousResourceValues.remove(playerId);
        playerSlotUsed[playerId] = false;
    }

    /**
     * Стартовый запас — ровно на постройку одной шахты железа и одной
     * электростанции (BuildingDefinitions.ironCostFor/electricityCostFor),
     * не отдельные захардкоженные числа: если стоимость этих двух зданий
     * потом перебалансируют в buildings.json, стартовый запас
     * автоматически пересчитается вместе с ними, не может разойтись.
     */
    private PlayerResources startingResources(int playerId) {
        PlayerResources resources = new PlayerResources(playerId);
        resources.iron = BuildingDefinitions.ironCostFor(BuildingType.IRON_MINE)
                + BuildingDefinitions.ironCostFor(BuildingType.POWER_PLANT);
        resources.electricity = BuildingDefinitions.electricityCostFor(BuildingType.IRON_MINE)
                + BuildingDefinitions.electricityCostFor(BuildingType.POWER_PLANT);
        return resources;
    }

    // ---- Спавн зданий ----

    /**
     * Дом (HQ) ставится в противоположных углах карты — это единственная
     * причина, почему у GameServer вообще есть представление о том, где
     * "начало" у каждого игрока. Единственное здание, которое регистрируется
     * в buildingIdByPlayer — его разрушение заканчивает игру, см. checkGameOver.
     * Единственное здание, которое сервер ставит сам при входе игрока —
     * казарма стрелков, шахта железа и электростанция теперь строятся
     * самим игроком из меню постройки (клавиша B), см. handlePlaceBuilding
     * /handlePlaceIronMine. Вместе с домом игрок сразу получает и одного
     * строителя — без него некому было бы строить ни шахту, ни станцию,
     * ни вообще что-либо (см. BuildSystem — здание не достраивается само).
     */
    private void spawnHomeAndBuilder(int playerId) {
        float[] home = BuildingDefinitions.homeSpawnPoint(playerId);
        int homeUnitId = spawnBuilding(playerId, BuildingType.HOME, home[0], home[1]);
        buildingIdByPlayer.put(playerId, homeUnitId);

        Vector2 builderSpawn = BuildingDefinitions.spawnPointNear(BuildingType.HOME, new Vector2(home[0], home[1]));
        createUnit(playerId, builderSpawn.x, builderSpawn.y, UnitType.BUILDER);
    }

    /**
     * Единая точка создания ЛЮБОГО здания — все свойства (размер, здоровье,
     * время постройки, что производит/добывает) решает BuildingDefinitions
     * по type, а не параметры этой функции. Если buildTimeFor(type) > 0
     * (сейчас — шахта, электростанция, казарма, авиазавод и турель)
     * здание появляется ещё строящимся, с ConstructionComponent —
     * ConstructionSystem сама доведёт его до рабочего состояния. Иначе
     * (дом) — сразу готовым, с ProductionComponent, если это здание
     * производит юнитов. Регистрирует здание как препятствие для A*
     * (Pathfinding.addBuildingObstacle) сразу, ещё до завершения стройки
     * — см. её javadoc, почему это верно и для строящихся зданий тоже.
     * TURRET — единственный тип, для которого ниже дополнительно
     * добавляются DirectionComponent/TurretComponent/UnitTypeComponent —
     * см. комментарий прямо перед этой веткой, почему.
     * Возвращает unitId созданного здания.
     */
    private int spawnBuilding(int playerId, BuildingType type, float x, float y) {
        Entity building = engine.createEntity();

        PositionComponent position = engine.createComponent(PositionComponent.class);
        position.position.set(x, y);

        int unitId = unitIdSequence.getAndIncrement();
        UnitComponent unitComponent = engine.createComponent(UnitComponent.class);
        unitComponent.unitId = unitId;

        OwnerComponent owner = engine.createComponent(OwnerComponent.class);
        owner.playerId = playerId;

        int maxHealth = BuildingDefinitions.maxHealthFor(type);
        float buildTime = BuildingDefinitions.buildTimeFor(type);
        HealthComponent health = engine.createComponent(HealthComponent.class);
        health.maxHealth = maxHealth;
        // Пока не достроено — здоровье растёт вместе с прогрессом
        // (BuildSystem.advanceConstruction), начинаем не с нуля, а с 1 HP
        // — сущность с буквально нулевым здоровьем выглядела бы как уже
        // мёртвая. Готовым зданиям (buildTime == 0, сейчас только дом)
        // полное здоровье сразу, строить их некому.
        health.currentHealth = buildTime > 0f ? 1 : maxHealth;

        BuildingComponent buildingMarker = engine.createComponent(BuildingComponent.class);
        buildingMarker.type = type;

        // Обычно без DirectionComponent — здание неподвижно, и этого
        // достаточно, чтобы MovementSystem и (как атакующего) CombatSystem
        // автоматически его игнорировали за счёт своих Family-фильтров.
        // Единственное исключение — TURRET чуть ниже: единственное здание,
        // которое умеет атаковать.
        building.add(position).add(unitComponent).add(owner).add(health).add(buildingMarker);

        if (type == BuildingType.TURRET) {
            // Сознательно нарушаем инвариант из комментария выше: турели
            // нужны те же компоненты, что и наземному юниту, чтобы её
            // подхватили те же generic-системы боя — AggroSystem
            // (автоагрессия по ближайшему врагу), CombatSystem (сама
            // стрельба), TurretAimSystem (доворот пушки на цель), — вместо
            // того чтобы писать для здания-стрелка отдельную копию всей
            // этой логики. AggroSystem дополнительно исключает
            // ConstructionComponent из своей Family — недостроенная турель
            // не должна стрелять, как и не работает недостроенная шахта.
            // CollisionSystem и BuildingPlacement её с юнитом не путают —
            // обе явно проверяют BuildingComponent раньше DirectionComponent
            // (см. их javadoc), так что коллизии её саму не толкают, а
            // размещение других построек видит в ней здание, не юнита.
            //
            // speed = 0 — турель никогда не двигается. Даже если CombatSystem
            // погонится за отступившим за дальность атаки врагом
            // (Pathfinding.setDestination выставит moving = true), MovementSystem
            // всё равно не сдвинет её ни на пиксель — умножение смещения на
            // speed = 0 всегда даёт 0.
            //
            // Урон/дальность атаки/скорострельность — из UnitDefinitions по
            // отдельному UnitType.TURRET (units.json), не из
            // BuildingDefinition — там таких полей нет: здание и боевая
            // роль остаются двумя независимыми "половинами" одной сущности.
            DirectionComponent turretDirection = engine.createComponent(DirectionComponent.class);
            turretDirection.speed = 0f;
            building.add(turretDirection);
            building.add(engine.createComponent(TurretComponent.class));
            UnitTypeComponent turretUnitType = engine.createComponent(UnitTypeComponent.class);
            turretUnitType.type = UnitType.TURRET;
            building.add(turretUnitType);
        }

        if (buildTime > 0f) {
            ConstructionComponent construction = engine.createComponent(ConstructionComponent.class);
            construction.totalTime = buildTime;
            construction.remaining = buildTime;
            building.add(construction);
        } else if (BuildingDefinitions.producesUnitTypesFor(type).length > 0) {
            ProductionComponent production = engine.createComponent(ProductionComponent.class);
            building.add(production);
        }

        engine.addEntity(building);
        unitsById.put(unitId, building);
        Pathfinding.addBuildingObstacle(unitId, x, y, type);
        return unitId;
    }

    // ---- Команды по юнитам ----

    /**
     * Единственное место, где реально создаётся сущность юнита. Вызывается
     * только из ProductionSystem (через UnitFactory), когда у здания
     * подходит очередь — handleQueueUnit ниже только ставит в очередь,
     * саму сущность не создаёт.
     */
    private Entity createUnit(int playerId, float x, float y, UnitType type) {
        Entity unit = engine.createEntity();

        PositionComponent position = engine.createComponent(PositionComponent.class);
        position.position.set(x, y);

        DirectionComponent direction = engine.createComponent(DirectionComponent.class);
        direction.speed = UnitDefinitions.speedFor(type);
        // moving/direction/target уже сброшены в false/0 — DirectionComponent
        // теперь реализует Pool.Poolable, PooledEngine вызывает reset() сама
        // при возврате компонента в пул, вручную обнулять не нужно.

        int unitId = unitIdSequence.getAndIncrement();
        UnitComponent unitComponent = engine.createComponent(UnitComponent.class);
        unitComponent.unitId = unitId;

        OwnerComponent owner = engine.createComponent(OwnerComponent.class);
        owner.playerId = playerId;

        HealthComponent health = engine.createComponent(HealthComponent.class);
        health.maxHealth = UnitDefinitions.healthFor(type);
        health.currentHealth = health.maxHealth;

        UnitTypeComponent unitType = engine.createComponent(UnitTypeComponent.class);
        unitType.type = type;

        unit.add(position).add(direction).add(unitComponent).add(owner).add(health).add(unitType);

        float turnRadius = UnitDefinitions.turnRadiusFor(type);
        if (turnRadius > 0f) {
            // Авиация — физически поворот ограничен, см. AircraftMovementSystem.
            // turnRate = speed/turnRadius (угловая скорость кругового
            // движения) считается один раз тут, не каждый тик.
            AircraftComponent aircraft = engine.createComponent(AircraftComponent.class);
            aircraft.turnRate = direction.speed / turnRadius;
            aircraft.canHover = UnitDefinitions.canHoverFor(type);
            unit.add(aircraft);
        } else {
            // Наземный юнит — своя башня, доворачивающаяся на цель отдельно
            // от корпуса (см. TurretComponent/TurretAimSystem). У авиации
            // её нет вовсе — орудие жёстко смотрит по курсу.
            unit.add(engine.createComponent(TurretComponent.class));
        }

        engine.addEntity(unit);

        unitsById.put(unitId, unit);
        return unit;
    }

    synchronized void handleQueueUnit(Connection connection, QueueUnitRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        Entity building = unitsById.get(request.buildingUnitId);
        if (building == null) {
            return;
        }

        OwnerComponent owner = building.getComponent(OwnerComponent.class);
        if (owner == null || owner.playerId != playerId) {
            return; // не ваше здание
        }

        ProductionComponent production = building.getComponent(ProductionComponent.class);
        if (production == null) {
            return; // не здание с производством
        }

        if (request.unitType < 0 || request.unitType >= UnitType.values().length) {
            return; // некорректный индекс — либо баг клиента, либо модифицированный клиент
        }
        UnitType unitType = UnitType.values()[request.unitType];

        BuildingType buildingType = building.getComponent(BuildingComponent.class).type;
        boolean canProduceThisType = false;
        for (UnitType producible : BuildingDefinitions.producesUnitTypesFor(buildingType)) {
            if (producible == unitType) {
                canProduceThisType = true;
                break;
            }
        }
        if (!canProduceThisType) {
            return; // это здание не умеет производить такой юнит — не доверяем клиенту слепо
        }

        if (unitsById.size() >= GameConstants.MAX_TOTAL_UNITS) {
            server.sendToTCP(connection.getID(),
                    new ErrorResponse("Unit limit reached (" + GameConstants.MAX_TOTAL_UNITS + " total)"));
            return;
        }

        production.queue.add(unitType);
    }

    /**
     * Точка сбора — куда идёт каждый только что произведённый юнит этого
     * здания (см. ProductionSystem). Клиент присылает её, когда игрок
     * кликает правой кнопкой по карте при выделенном СВОЁМ здании (см.
     * GameScreen.touchDown) — координаты не проверяются на валидность
     * (не в воде/не в здании) специально: Pathfinding.setDestination сам
     * молча проигнорирует недостижимую точку, как и при обычном ручном
     * приказе на движение — отдельной проверки тут не нужно.
     */
    synchronized void handleSetRallyPoint(Connection connection, SetRallyPointRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        Entity building = unitsById.get(request.buildingUnitId);
        if (building == null) {
            return;
        }

        OwnerComponent owner = building.getComponent(OwnerComponent.class);
        if (owner == null || owner.playerId != playerId) {
            return;
        }

        ProductionComponent production = building.getComponent(ProductionComponent.class);
        if (production == null) {
            return; // здание не производит юнитов — точке сбора тут нечего значить
        }

        production.hasRallyPoint = true;
        production.rallyX = request.x;
        production.rallyY = request.y;
    }

    /**
     * Шахта железа — встаёт только на месторождение (индекс в
     * GameConstants.IRON_DEPOSITS). Клиент уже проверил, что курсор был
     * "прилипшим" к месторождению (см. GameScreen), но решение всегда за
     * сервером: индекс в границах и место ещё не занято.
     */
    synchronized void handlePlaceIronMine(Connection connection, PlaceIronMineRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        if (request.depositIndex < 0 || request.depositIndex >= GameConstants.IRON_DEPOSITS.length) {
            return; // некорректный индекс — либо баг клиента, либо модифицированный клиент
        }

        if (isDepositOccupied(request.depositIndex)) {
            server.sendToTCP(connection.getID(), new ErrorResponse("This iron deposit is already occupied"));
            return;
        }

        float[] deposit = GameConstants.IRON_DEPOSITS[request.depositIndex];
        int minedBuildingUnitId = spawnBuilding(playerId, BuildingType.IRON_MINE, deposit[0], deposit[1]);
        assignBuildersToNewBuilding(playerId, minedBuildingUnitId, request.builderUnitIds, request.queue);
    }

    /**
     * Казарма стрелков или электростанция — в отличие от шахты, не
     * привязаны к фиксированной точке: игрок сам выбирает, где строить,
     * поэтому шлёт координаты и тип, а не индекс месторождения. Проверка
     * размещения (границы карты, вода, юниты, другие здания) — общая с
     * клиентским превью, см. BuildingPlacement.canPlaceBuilding. Дом сюда
     * не попадает вообще (строит только сервер при входе игрока), шахта —
     * своим отдельным handlePlaceIronMine (индекс месторождения, не
     * координаты).
     */
    synchronized void handlePlaceBuilding(Connection connection, PlaceBuildingRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        if (request.buildingType < 0 || request.buildingType >= BuildingType.values().length) {
            return; // некорректный индекс — либо баг клиента, либо модифицированный клиент
        }
        BuildingType type = BuildingType.values()[request.buildingType];
        if (type == BuildingType.HOME || type == BuildingType.IRON_MINE) {
            return; // дом строит только сервер, шахта — только через handlePlaceIronMine
        }

        if (!BuildingPlacement.canPlaceBuilding(type, unitsById.values(), request.x, request.y)) {
            server.sendToTCP(connection.getID(), new ErrorResponse("Cannot place building there"));
            return;
        }

        int newBuildingUnitId = spawnBuilding(playerId, type, request.x, request.y);
        assignBuildersToNewBuilding(playerId, newBuildingUnitId, request.builderUnitIds, request.queue);
    }

    /** Занято ли месторождение — ищем среди unitsById здание добычи (строящееся или уже готовое) точно в этой точке. */
    private boolean isDepositOccupied(int depositIndex) {
        float[] deposit = GameConstants.IRON_DEPOSITS[depositIndex];
        for (Entity entity : unitsById.values()) {
            if (!BuildingSizes.isResourceBuilding(entity)) {
                continue;
            }
            PositionComponent position = entity.getComponent(PositionComponent.class);
            // Здание добычи всегда ставится ТОЧНО в координаты месторождения
            // (клиент прилипает курсор к нему) — небольшой эпсилон только на
            // случай погрешности float, не для "рядом, но не совсем".
            if (position.position.dst2(deposit[0], deposit[1]) < 1f) {
                return true;
            }
        }
        return false;
    }

    /**
     * Обычный (не-queue) приказ — очередь целиком очищает и заменяет
     * текущее действие; queue=true (shift-клик, см. GameScreen) —
     * добавляет в конец очереди отложенных приказов, не трогая то, что
     * юнит делает прямо сейчас (см. enqueueOrder/OrderQueueComponent).
     */
    synchronized void handleMoveUnit(Connection connection, MoveUnitRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        Entity unit = unitsById.get(request.unitId);
        if (unit == null) {
            return;
        }

        OwnerComponent owner = unit.getComponent(OwnerComponent.class);
        if (owner == null || owner.playerId != playerId) {
            return; // нельзя двигать чужой юнит
        }

        DirectionComponent direction = unit.getComponent(DirectionComponent.class);
        if (direction == null) {
            return; // здание — двигаться не может
        }

        float targetX = clamp(request.targetX, 0f, GameConstants.MAP_WIDTH);
        float targetY = clamp(request.targetY, 0f, GameConstants.MAP_HEIGHT);

        if (request.queue) {
            QueuedOrder order = new QueuedOrder();
            order.type = QueuedOrder.Type.MOVE;
            order.x = targetX;
            order.y = targetY;
            enqueueOrder(unit, order);
            return;
        }

        clearOrderQueue(unit);

        // Ручной приказ на движение отменяет текущую атаку, как в большинстве RTS.
        if (unit.getComponent(AttackComponent.class) != null) {
            unit.remove(AttackComponent.class);
        }
        // И текущую стройку, ремонт или сбор обломков — если это был строитель.
        if (unit.getComponent(BuildOrderComponent.class) != null) {
            unit.remove(BuildOrderComponent.class);
        }
        if (unit.getComponent(RepairOrderComponent.class) != null) {
            unit.remove(RepairOrderComponent.class);
        }
        if (unit.getComponent(CollectOrderComponent.class) != null) {
            unit.remove(CollectOrderComponent.class);
        }

        PositionComponent position = unit.getComponent(PositionComponent.class);

        // Pathfinding сама решает: прямая линия свободна — идём напрямую,
        // как раньше; если по пути препятствие (вода/здание) — обойдёт его.
        Pathfinding.setDestination(unit, position, direction, targetX, targetY);
    }

    /** Добавляет приказ в конец очереди юнита, создавая OrderQueueComponent при первом обращении — см. её javadoc. */
    private void enqueueOrder(Entity unit, QueuedOrder order) {
        OrderQueueComponent orderQueue = unit.getComponent(OrderQueueComponent.class);
        if (orderQueue == null) {
            orderQueue = engine.createComponent(OrderQueueComponent.class);
            unit.add(orderQueue);
        }
        orderQueue.queue.add(order);
    }

    /** Любой немедленный (не-queue) приказ отменяет всё, что было отложено — так и ожидается от "замены" приказа. */
    private void clearOrderQueue(Entity unit) {
        OrderQueueComponent orderQueue = unit.getComponent(OrderQueueComponent.class);
        if (orderQueue != null) {
            orderQueue.queue.clear();
        }
    }

    /**
     * Обычный (не-queue) приказ — очередь целиком очищает и заменяет
     * текущее действие; queue=true — добавляет в конец очереди, см.
     * javadoc handleMoveUnit про то же самое.
     */
    synchronized void handleAttackUnit(Connection connection, AttackUnitRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        if (request.queue) {
            Entity attacker = unitsById.get(request.unitId);
            if (attacker == null) {
                return;
            }
            OwnerComponent owner = attacker.getComponent(OwnerComponent.class);
            if (owner == null || owner.playerId != playerId) {
                return; // не ваш юнит
            }
            if (attacker.getComponent(DirectionComponent.class) == null) {
                return; // здание атаковать не может
            }

            QueuedOrder order = new QueuedOrder();
            order.type = QueuedOrder.Type.ATTACK;
            order.targetUnitId = request.targetUnitId;
            enqueueOrder(attacker, order);
            return;
        }

        Entity attacker = unitsById.get(request.unitId);
        if (attacker != null) {
            clearOrderQueue(attacker);
        }
        startAttackOrder(playerId, request.unitId, request.targetUnitId);
    }

    /**
     * Запускает атаку прямо сейчас — общая логика для немедленного
     * приказа (handleAttackUnit) и для разбора очереди (OrderQueueSystem,
     * куда передаётся как OrderExecutor через метод-ссылку). Возвращает
     * false, если приказ невалиден прямо сейчас (атакующий/цель пропали,
     * цель своя, атакующий — здание) — вызывающий код очереди просто
     * отбрасывает этот результат: юнит останется бездействовать до
     * следующего тика и попробует взять уже СЛЕДУЮЩИЙ элемент очереди.
     */
    private boolean startAttackOrder(int playerId, int attackerUnitId, int targetUnitId) {
        Entity attacker = unitsById.get(attackerUnitId);
        Entity target = unitsById.get(targetUnitId);
        if (attacker == null || target == null) {
            return false;
        }

        OwnerComponent attackerOwner = attacker.getComponent(OwnerComponent.class);
        if (attackerOwner == null || attackerOwner.playerId != playerId) {
            return false; // не ваш юнит
        }

        if (attacker.getComponent(DirectionComponent.class) == null) {
            return false; // здание атаковать не может
        }

        OwnerComponent targetOwner = target.getComponent(OwnerComponent.class);
        if (targetOwner == null || targetOwner.playerId == playerId) {
            return false; // нельзя атаковать своих (свои здания — тоже)
        }

        if (target.getComponent(WreckComponent.class) != null) {
            // Обломки нейтральны (playerId == GameConstants.NEUTRAL_OWNER_ID
            // — см. её javadoc), поэтому формально прошли бы проверку выше
            // (никогда не равны playerId атакующего), но атаковать их
            // нельзя — это не боевая цель, а источник железа для
            // ScavengeSystem. Тот же клиентский запрет см. в
            // GameScreen.isEnemy — тут вторая, серверная проверка на
            // случай модифицированного клиента.
            return false;
        }

        // Ручной приказ на атаку отменяет текущую стройку, ремонт или сбор обломков — как и обычный приказ на движение.
        if (attacker.getComponent(BuildOrderComponent.class) != null) {
            attacker.remove(BuildOrderComponent.class);
        }
        if (attacker.getComponent(RepairOrderComponent.class) != null) {
            attacker.remove(RepairOrderComponent.class);
        }
        if (attacker.getComponent(CollectOrderComponent.class) != null) {
            attacker.remove(CollectOrderComponent.class);
        }

        AttackComponent attack = attacker.getComponent(AttackComponent.class);
        if (attack == null) {
            // cooldown уже 0 — AttackComponent реализует Pool.Poolable,
            // PooledEngine вызывает reset() сама при возврате в пул, так что
            // свежий объект из createComponent никогда не приходит "грязным".
            attack = engine.createComponent(AttackComponent.class);
            attacker.add(attack);
        }
        // Если AttackComponent уже был (юнит переключает цель на лету) —
        // cooldown намеренно не трогаем: юнит не должен получать
        // "бесплатный" мгновенный выстрел просто от смены цели.
        attack.targetUnitId = targetUnitId;
        return true;
    }

    /**
     * Приказ строителю строить (или продолжить строить) конкретное
     * здание — по правому клику на своё недостроенное здание при
     * выделенных строителях (см. GameScreen.issueBuildOrder). Обработка
     * зеркальна handleAttackUnit: та же проверка владения, тот же приём
     * "если компонент уже был — не сбрасываем его состояние зря", только
     * цель не убить, а достроить.
     */
    /**
     * Обычный (не-queue) приказ — очередь целиком очищает и заменяет
     * текущее действие; queue=true — добавляет в конец очереди, см.
     * javadoc handleMoveUnit про то же самое.
     */
    synchronized void handleBuildOrder(Connection connection, BuildOrderRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        if (request.queue) {
            Entity builder = unitsById.get(request.builderUnitId);
            if (builder == null) {
                return;
            }
            OwnerComponent owner = builder.getComponent(OwnerComponent.class);
            if (owner == null || owner.playerId != playerId) {
                return; // не ваш юнит
            }

            QueuedOrder order = new QueuedOrder();
            order.type = QueuedOrder.Type.BUILD;
            order.targetBuildingUnitId = request.targetBuildingUnitId;
            enqueueOrder(builder, order);
            return;
        }

        Entity builder = unitsById.get(request.builderUnitId);
        if (builder != null) {
            clearOrderQueue(builder);
        }
        assignBuilderToBuild(playerId, request.builderUnitId, request.targetBuildingUnitId);
    }

    /**
     * Назначает одного строителя строить конкретное здание — общая логика
     * для двух путей: ручной приказ по правому клику (handleBuildOrder) и
     * автоматическое назначение сразу после размещения только что
     * поставленного здания тем строителям, что были выделены в момент
     * подтверждения (handlePlaceIronMine/handlePlaceBuilding, см. javadoc
     * PlaceIronMineRequest.builderUnitIds). Молча ничего не делает при
     * любой проверке владения/типа, которая не проходит — не ваш юнит, не
     * строитель, чужое или уже достроенное здание.
     */
    private void assignBuilderToBuild(int playerId, int builderUnitId, int targetBuildingUnitId) {
        Entity builder = unitsById.get(builderUnitId);
        Entity targetBuilding = unitsById.get(targetBuildingUnitId);
        if (builder == null || targetBuilding == null) {
            return;
        }

        OwnerComponent builderOwner = builder.getComponent(OwnerComponent.class);
        if (builderOwner == null || builderOwner.playerId != playerId) {
            return; // не ваш юнит
        }

        UnitTypeComponent builderType = builder.getComponent(UnitTypeComponent.class);
        if (builderType == null || builderType.type != UnitType.BUILDER) {
            return; // строить может только строитель
        }

        OwnerComponent targetOwner = targetBuilding.getComponent(OwnerComponent.class);
        if (targetOwner == null || targetOwner.playerId != playerId) {
            return; // строить можно только своё — чужое недостроенное здание не трогаем
        }

        if (targetBuilding.getComponent(ConstructionComponent.class) == null) {
            return; // уже достроено (или не здание вовсе) — нечего строить
        }

        // Ручной приказ на стройку отменяет текущую атаку, текущий ремонт и текущий сбор обломков — как и обычный приказ на движение.
        if (builder.getComponent(AttackComponent.class) != null) {
            builder.remove(AttackComponent.class);
        }
        if (builder.getComponent(RepairOrderComponent.class) != null) {
            builder.remove(RepairOrderComponent.class);
        }
        if (builder.getComponent(CollectOrderComponent.class) != null) {
            builder.remove(CollectOrderComponent.class);
        }

        BuildOrderComponent order = builder.getComponent(BuildOrderComponent.class);
        if (order == null) {
            order = engine.createComponent(BuildOrderComponent.class);
            builder.add(order);
        }
        order.targetBuildingUnitId = targetBuildingUnitId;
        // Любой (пере)выданный приказ — новый или тот же самый заново —
        // заставляет BuildSystem посчитать точку подхода заново от
        // текущей позиции строителя на следующем тике, а не тащить
        // устаревшую (возможно, уже недостижимую) точку с прошлого
        // захода. См. её же javadoc, почему пересчёт НЕ на каждом тике,
        // а именно так — лениво, при (пере)выдаче приказа.
        order.hasApproachPoint = false;
    }

    /**
     * Автоматически отправляет строить только что поставленное здание
     * всех строителей, что были выделены в момент подтверждения его
     * размещения (см. PlaceIronMineRequest.builderUnitIds) — по одному
     * assignBuilderToBuild (или enqueueOrder, если queue=true) на
     * каждого, та же проверка владения/типа, что и у ручного приказа, так
     * что чужой или невалидный id в массиве просто молча пропускается, не
     * ломая ничего.
     *
     * queue — тот же shift-модификатор, что и у BuildOrderRequest.queue
     * (см. handleBuildOrder): без него приказ немедленный и заменяет всё,
     * чем строитель занимался (в том числе стройку другого здания) — то
     * же самое clearOrderQueue + assignBuilderToBuild, что и там; с ним
     * приказ просто добавляется в конец очереди строителя через
     * enqueueOrder, а не выполняется сразу — полная проверка (владение,
     * тип юнита, состояние здания-цели) в этом случае откладывается до
     * момента, когда OrderQueueSystem реально возьмёт этот приказ из
     * очереди и вызовет assignBuilderToBuild сама, тем же путём, что и
     * очередь BUILD-приказов из handleBuildOrder — здесь достаточно
     * проверить только владение строителем, чтобы не поставить приказ в
     * чужую очередь.
     */
    private void assignBuildersToNewBuilding(int playerId, int newBuildingUnitId, int[] builderUnitIds, boolean queue) {
        if (builderUnitIds == null) {
            return;
        }
        for (int builderUnitId : builderUnitIds) {
            if (queue) {
                Entity builder = unitsById.get(builderUnitId);
                if (builder == null) {
                    continue;
                }
                OwnerComponent owner = builder.getComponent(OwnerComponent.class);
                if (owner == null || owner.playerId != playerId) {
                    continue; // не ваш юнит
                }
                QueuedOrder order = new QueuedOrder();
                order.type = QueuedOrder.Type.BUILD;
                order.targetBuildingUnitId = newBuildingUnitId;
                enqueueOrder(builder, order);
                continue;
            }

            Entity builder = unitsById.get(builderUnitId);
            if (builder != null) {
                clearOrderQueue(builder);
            }
            assignBuilderToBuild(playerId, builderUnitId, newBuildingUnitId);
        }
    }

    /**
     * Приказ строителю ремонтировать (или продолжить ремонтировать)
     * конкретное здание — по правому клику на своё повреждённое, но уже
     * достроенное здание при выделенных строителях (см.
     * GameScreen.issueRepairOrder). Обработка зеркальна handleBuildOrder
     * — та же логика queue/не-queue, см. её же javadoc.
     */
    synchronized void handleRepairOrder(Connection connection, RepairOrderRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        if (request.queue) {
            Entity builder = unitsById.get(request.builderUnitId);
            if (builder == null) {
                return;
            }
            OwnerComponent owner = builder.getComponent(OwnerComponent.class);
            if (owner == null || owner.playerId != playerId) {
                return; // не ваш юнит
            }

            QueuedOrder order = new QueuedOrder();
            order.type = QueuedOrder.Type.REPAIR;
            order.targetBuildingUnitId = request.targetBuildingUnitId;
            enqueueOrder(builder, order);
            return;
        }

        Entity builder = unitsById.get(request.builderUnitId);
        if (builder != null) {
            clearOrderQueue(builder);
        }
        assignBuilderToRepair(playerId, request.builderUnitId, request.targetBuildingUnitId);
    }

    /**
     * Назначает одного строителя ремонтировать конкретное (уже
     * достроенное) здание — общая логика для обоих путей выдачи приказа
     * (ручной приказ handleRepairOrder и разбор очереди через
     * OrderQueueSystem). Молча ничего не делает при любой проверке
     * владения/типа, которая не проходит — не ваш юнит, не строитель,
     * чужое здание, здание ещё строится (это BuildOrderRequest, не этот
     * приказ) или не повреждено вовсе — нечего чинить.
     *
     * Если здание УЖЕ ремонтируется (RepairComponent уже есть — другой
     * строитель начал раньше, или тот же строитель переприказан на ту же
     * цель) — компонент переиспользуется как есть, стоимость и время
     * ремонта НЕ пересчитываются заново от текущего процента повреждений:
     * они зафиксированы один раз, в момент первого назначения — см.
     * javadoc RepairComponent, почему.
     */
    private void assignBuilderToRepair(int playerId, int builderUnitId, int targetBuildingUnitId) {
        Entity builder = unitsById.get(builderUnitId);
        Entity targetBuilding = unitsById.get(targetBuildingUnitId);
        if (builder == null || targetBuilding == null) {
            return;
        }

        OwnerComponent builderOwner = builder.getComponent(OwnerComponent.class);
        if (builderOwner == null || builderOwner.playerId != playerId) {
            return; // не ваш юнит
        }

        UnitTypeComponent builderType = builder.getComponent(UnitTypeComponent.class);
        if (builderType == null || builderType.type != UnitType.BUILDER) {
            return; // ремонтировать может только строитель
        }

        OwnerComponent targetOwner = targetBuilding.getComponent(OwnerComponent.class);
        if (targetOwner == null || targetOwner.playerId != playerId) {
            return; // ремонтировать можно только своё
        }

        if (targetBuilding.getComponent(ConstructionComponent.class) != null) {
            return; // ещё строится — это обычная стройка (BuildOrderRequest), не ремонт
        }

        HealthComponent health = targetBuilding.getComponent(HealthComponent.class);
        if (health == null || health.currentHealth >= health.maxHealth) {
            return; // не повреждено — нечего ремонтировать
        }

        // Ручной приказ на ремонт отменяет текущую атаку, текущую стройку и текущий сбор обломков — как и обычный приказ на движение.
        if (builder.getComponent(AttackComponent.class) != null) {
            builder.remove(AttackComponent.class);
        }
        if (builder.getComponent(BuildOrderComponent.class) != null) {
            builder.remove(BuildOrderComponent.class);
        }
        if (builder.getComponent(CollectOrderComponent.class) != null) {
            builder.remove(CollectOrderComponent.class);
        }

        RepairComponent repair = targetBuilding.getComponent(RepairComponent.class);
        if (repair == null) {
            repair = engine.createComponent(RepairComponent.class);
            float damagePercent = (1f - (float) health.currentHealth / health.maxHealth) * 100f;
            repair.totalIronCost = Math.round(damagePercent);
            repair.totalTime = damagePercent * GameConstants.REPAIR_SECONDS_PER_PERCENT_DAMAGE;
            repair.remaining = repair.totalTime;
            repair.startHealth = health.currentHealth;
            targetBuilding.add(repair);
        }

        RepairOrderComponent order = builder.getComponent(RepairOrderComponent.class);
        if (order == null) {
            order = engine.createComponent(RepairOrderComponent.class);
            builder.add(order);
        }
        order.targetBuildingUnitId = targetBuildingUnitId;
        // Тот же приём, что и в assignBuilderToBuild — (пере)выдача
        // приказа заставляет RepairSystem посчитать точку подхода заново
        // на следующем тике, не тащить устаревшую с прошлого захода.
        order.hasApproachPoint = false;
    }

    /**
     * Оставляет обломки на месте погибшего ЮНИТА (не здания — см. javadoc
     * CombatSystem.UnitDestroyedListener) с частью потраченного на него
     * железа, которую потом сможет собрать строитель (ScavengeSystem).
     * Передаётся в CombatSystem как UnitDestroyedListener — вызывается
     * оттуда сразу, как только там решили, что цель погибла, и до того,
     * как её саму удалят из движка (см. её же вызов).
     *
     * Реальное количество железа — случайная доля (WRECK_IRON_PERCENT_MIN
     * .. _MAX) от UnitDefinitions.ironCostFor(destroyedType), округлённая
     * до целого; если получилось 0 (например, у совсем дешёвого юнита) —
     * обломки не создаются вовсе, собирать было бы нечего. Обломки — не
     * настоящее здание: без владельца (OwnerComponent.playerId ==
     * GameConstants.NEUTRAL_OWNER_ID — см. её javadoc, почему это само по
     * себе не делает их "врагом") и без ConstructionComponent — появляются
     * сразу полностью "готовыми".
     *
     * Если (x, y) внутри прямоугольника воды (GameConstants.WATER_MIN/MAX
     * X/Y — тот же прямоугольник, что и в CollisionSystem/Pathfinding) —
     * обломки помечаются WreckComponent.underwater, сейчас только для
     * отрисовки другим оттенком на клиенте. Строители всё равно не могут
     * доехать до воды (Pathfinding/CollisionSystem считают её препятствием
     * наравне со зданием), так что подводные обломки физически
     * недостижимы, пока не появится отдельная поддержка "строитель умеет
     * заходить в воду" — само появление обломков в воде это не меняет.
     */
    private void spawnWreck(UnitType destroyedType, float x, float y) {
        int originalIronCost = UnitDefinitions.ironCostFor(destroyedType);
        int ironAmount = Math.round(originalIronCost
                * MathUtils.random(GameConstants.WRECK_IRON_PERCENT_MIN, GameConstants.WRECK_IRON_PERCENT_MAX));
        if (ironAmount <= 0) {
            return; // нечего оставлять — например, у юнита не было стоимости железом вовсе
        }

        Entity wreck = engine.createEntity();

        PositionComponent position = engine.createComponent(PositionComponent.class);
        position.position.set(x, y);

        int unitId = unitIdSequence.getAndIncrement();
        UnitComponent unitComponent = engine.createComponent(UnitComponent.class);
        unitComponent.unitId = unitId;

        OwnerComponent owner = engine.createComponent(OwnerComponent.class);
        owner.playerId = GameConstants.NEUTRAL_OWNER_ID;

        // См. javadoc WreckComponent — currentHealth/maxHealth тут значат
        // "сколько железа осталось / было изначально", не HP.
        HealthComponent ironStock = engine.createComponent(HealthComponent.class);
        ironStock.maxHealth = ironAmount;
        ironStock.currentHealth = ironAmount;

        BuildingComponent buildingMarker = engine.createComponent(BuildingComponent.class);
        buildingMarker.type = BuildingType.WRECK;

        WreckComponent wreckMarker = engine.createComponent(WreckComponent.class);
        wreckMarker.underwater = x >= GameConstants.WATER_MIN_X && x <= GameConstants.WATER_MAX_X
                && y >= GameConstants.WATER_MIN_Y && y <= GameConstants.WATER_MAX_Y;

        wreck.add(position).add(unitComponent).add(owner).add(ironStock).add(buildingMarker).add(wreckMarker);

        engine.addEntity(wreck);
        unitsById.put(unitId, wreck);
        Pathfinding.addBuildingObstacle(unitId, x, y, BuildingType.WRECK);
    }

    synchronized void handleCollectOrder(Connection connection, CollectOrderRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        if (request.queue) {
            Entity builder = unitsById.get(request.builderUnitId);
            if (builder == null) {
                return;
            }
            OwnerComponent owner = builder.getComponent(OwnerComponent.class);
            if (owner == null || owner.playerId != playerId) {
                return; // не ваш юнит
            }

            QueuedOrder order = new QueuedOrder();
            order.type = QueuedOrder.Type.COLLECT;
            order.targetBuildingUnitId = request.targetWreckUnitId;
            enqueueOrder(builder, order);
            return;
        }

        Entity builder = unitsById.get(request.builderUnitId);
        if (builder != null) {
            clearOrderQueue(builder);
        }
        assignBuilderToCollect(playerId, request.builderUnitId, request.targetWreckUnitId);
    }

    /**
     * Назначает одного строителя собирать железо с конкретных обломков —
     * общая логика для обоих путей выдачи приказа (ручной приказ
     * handleCollectOrder и разбор очереди через OrderQueueSystem). Молча
     * ничего не делает при любой проверке, которая не проходит — не ваш
     * юнит, не строитель, цель не существует или не обломки. В отличие
     * от assignBuilderToBuild/assignBuilderToRepair владение ЦЕЛЬЮ не
     * проверяется — у обломков нет владельца, собрать их может строитель
     * любого игрока.
     */
    private void assignBuilderToCollect(int playerId, int builderUnitId, int targetWreckUnitId) {
        Entity builder = unitsById.get(builderUnitId);
        Entity targetWreck = unitsById.get(targetWreckUnitId);
        if (builder == null || targetWreck == null) {
            return;
        }

        OwnerComponent builderOwner = builder.getComponent(OwnerComponent.class);
        if (builderOwner == null || builderOwner.playerId != playerId) {
            return; // не ваш юнит
        }

        UnitTypeComponent builderType = builder.getComponent(UnitTypeComponent.class);
        if (builderType == null || builderType.type != UnitType.BUILDER) {
            return; // собирать может только строитель
        }

        if (targetWreck.getComponent(WreckComponent.class) == null) {
            return; // не обломки — нечего собирать
        }

        // Ручной приказ на сбор отменяет текущую атаку, текущую стройку и текущий ремонт — как и обычный приказ на движение.
        if (builder.getComponent(AttackComponent.class) != null) {
            builder.remove(AttackComponent.class);
        }
        if (builder.getComponent(BuildOrderComponent.class) != null) {
            builder.remove(BuildOrderComponent.class);
        }
        if (builder.getComponent(RepairOrderComponent.class) != null) {
            builder.remove(RepairOrderComponent.class);
        }

        CollectOrderComponent order = builder.getComponent(CollectOrderComponent.class);
        if (order == null) {
            order = engine.createComponent(CollectOrderComponent.class);
            builder.add(order);
        }
        order.targetWreckUnitId = targetWreckUnitId;
        order.hasApproachPoint = false;
    }

    /**
     * Игрок добровольно сносит своё же здание — по кнопке "Demolish" в
     * панели выделенного здания (см. GameScreen.drawBuildingInfoPanel), для
     * любого своего здания, не только производящего. Снос идёт тем же
     * путём, каким CombatSystem убирает юнита/здание, погибшее в бою
     * (engine.removeEntity + unitsById.remove) — специально, а не
     * какой-то отдельной логикой: если снесли свой же HQ, обычная
     * checkGameOver() в основном цикле сама заметит его пропажу из
     * unitsById и засчитает поражение, ничего дополнительного тут для
     * этого случая делать не нужно.
     */
    synchronized void handleDemolishBuilding(Connection connection, DemolishBuildingRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        Entity building = unitsById.get(request.buildingUnitId);
        if (building == null) {
            return;
        }

        OwnerComponent owner = building.getComponent(OwnerComponent.class);
        if (owner == null || owner.playerId != playerId) {
            return; // не ваше здание
        }

        if (building.getComponent(BuildingComponent.class) == null) {
            return; // не здание вовсе (защита от модифицированного клиента)
        }

        engine.removeEntity(building);
        unitsById.remove(request.buildingUnitId);
        Pathfinding.removeBuildingObstacle(request.buildingUnitId);
    }

    // ---- Визуальный эффект полёта стрелы (см. CombatSystem.ShotFiredListener) ----

    private void handleShotFired(UnitType attackerType, float fromX, float fromY, float toX, float toY) {
        if (attackerType != UnitType.ARCHER) {
            return; // визуальный эффект нужен только лучникам — у воина видимого снаряда нет
        }
        ProjectileFiredEvent event = new ProjectileFiredEvent();
        event.fromX = fromX;
        event.fromY = fromY;
        event.toX = toX;
        event.toY = toY;
        server.sendToAllTCP(event);
    }

    // ---- Условие победы ----

    private void checkGameOver() {
        List<Integer> defeatedPlayers = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : buildingIdByPlayer.entrySet()) {
            if (!unitsById.containsKey(entry.getValue())) {
                defeatedPlayers.add(entry.getKey());
            }
        }
        if (defeatedPlayers.isEmpty()) {
            return;
        }

        gameOver = true;
        broadcastSnapshot(); // финальный кадр — уничтоженного здания в нём уже нет

        GameOverMessage message = new GameOverMessage();
        if (defeatedPlayers.size() >= GameConstants.MAX_PLAYERS) {
            message.draw = true; // оба дома погибли в один тик
        } else {
            int defeatedPlayerId = defeatedPlayers.get(0);
            for (int i = 0; i < GameConstants.MAX_PLAYERS; i++) {
                if (i != defeatedPlayerId) {
                    message.winnerPlayerId = i;
                    break;
                }
            }
        }
        server.sendToAllTCP(message);
    }

    // ---- Рассылка снапшота ----

    private void broadcastSnapshot() {
        updateFogOfWar(timeSinceLastFogUpdate);
        timeSinceLastFogUpdate = 0f;

        WorldSnapshot snapshot = new WorldSnapshot();
        for (Entity unit : unitsById.values()) {
            PositionComponent position = unit.getComponent(PositionComponent.class);
            DirectionComponent direction = unit.getComponent(DirectionComponent.class);
            UnitComponent unitComponent = unit.getComponent(UnitComponent.class);
            OwnerComponent owner = unit.getComponent(OwnerComponent.class);
            HealthComponent health = unit.getComponent(HealthComponent.class);

            UnitSnapshot unitSnapshot = new UnitSnapshot();
            unitSnapshot.unitId = unitComponent.unitId;
            unitSnapshot.ownerId = owner.playerId;
            unitSnapshot.x = position.position.x;
            unitSnapshot.y = position.position.y;
            // У зданий нет DirectionComponent — оставляем направление нулевым,
            // клиенту оно для зданий и не нужно (они не двигаются).
            if (direction != null) {
                unitSnapshot.dirX = direction.direction.x;
                unitSnapshot.dirY = direction.direction.y;
                unitSnapshot.moving = direction.moving;
            }
            // Только у наземных юнитов есть TurretComponent (см.
            // createUnit) — у зданий и авиации остаётся (0, 0), клиент
            // туда для них и не смотрит (см. javadoc UnitSnapshot
            // .turretDirX).
            TurretComponent turret = unit.getComponent(TurretComponent.class);
            if (turret != null) {
                unitSnapshot.turretDirX = MathUtils.cos(turret.angleRadians);
                unitSnapshot.turretDirY = MathUtils.sin(turret.angleRadians);
            }
            unitSnapshot.health = health.currentHealth;
            unitSnapshot.maxHealth = health.maxHealth;
            BuildingComponent buildingMarker = unit.getComponent(BuildingComponent.class);
            unitSnapshot.building = buildingMarker != null;

            if (buildingMarker != null) {
                unitSnapshot.buildingType = buildingMarker.type.ordinal();
                if (buildingMarker.type == BuildingType.WRECK) {
                    WreckComponent wreckMarker = unit.getComponent(WreckComponent.class);
                    unitSnapshot.wreckUnderwater = wreckMarker != null && wreckMarker.underwater;
                }
            } else {
                UnitTypeComponent unitTypeComponent = unit.getComponent(UnitTypeComponent.class);
                if (unitTypeComponent != null) {
                    unitSnapshot.unitType = unitTypeComponent.type.ordinal();
                }
                BuildOrderComponent buildOrder = unit.getComponent(BuildOrderComponent.class);
                if (buildOrder != null && buildOrder.inRange) {
                    unitSnapshot.buildTargetUnitId = buildOrder.targetBuildingUnitId;
                }
                // То же самое поле, что и для стройки — с точки зрения
                // клиентского луча (GameScreen.drawBuildBeams) ремонт,
                // сбор обломков и стройка неотличимы, все три означают
                // "строитель что-то делает вон с той сущностью" (см.
                // javadoc RepairOrderComponent.inRange/
                // CollectOrderComponent.inRange). Строитель не может
                // одновременно заниматься несколькими из них, так что при
                // срабатывании они не перезапишут друг друга неверно.
                RepairOrderComponent repairOrder = unit.getComponent(RepairOrderComponent.class);
                if (repairOrder != null && repairOrder.inRange) {
                    unitSnapshot.buildTargetUnitId = repairOrder.targetBuildingUnitId;
                }
                CollectOrderComponent collectOrder = unit.getComponent(CollectOrderComponent.class);
                if (collectOrder != null && collectOrder.inRange) {
                    unitSnapshot.buildTargetUnitId = collectOrder.targetWreckUnitId;
                }
            }

            ProductionComponent production = unit.getComponent(ProductionComponent.class);
            if (production != null) {
                unitSnapshot.queuedCount = production.queue.size();
                unitSnapshot.buildProgress = production.progress;
                if (!production.queue.isEmpty()) {
                    unitSnapshot.producingUnitType = production.queue.get(0).ordinal();
                }
                unitSnapshot.hasRallyPoint = production.hasRallyPoint;
                unitSnapshot.rallyX = production.rallyX;
                unitSnapshot.rallyY = production.rallyY;
            }

            ConstructionComponent construction = unit.getComponent(ConstructionComponent.class);
            if (construction != null) {
                unitSnapshot.underConstruction = true;
                unitSnapshot.constructionProgress = 1f - construction.remaining / construction.totalTime;
            }

            // Только для отладочной отрисовки маршрута на клиенте (клавиша `
            // в GameScreen) — если юнит не движется, список остаётся пустым.
            if (direction != null && direction.moving) {
                unitSnapshot.pathPoints.add(new PathPoint(direction.target.x, direction.target.y));
                PathComponent path = unit.getComponent(PathComponent.class);
                if (path != null) {
                    for (Vector2 waypoint : path.waypoints) {
                        unitSnapshot.pathPoints.add(new PathPoint(waypoint.x, waypoint.y));
                    }
                }
            }

            // Только для отрисовки цепочки очереди на клиенте (GameScreen
            // .drawOrderQueue) — см. javadoc QueuedOrderPoint, почему тут
            // координаты, а не id цели.
            OrderQueueComponent orderQueue = unit.getComponent(OrderQueueComponent.class);
            if (orderQueue != null) {
                for (QueuedOrder order : orderQueue.queue) {
                    float px;
                    float py;
                    if (order.type == QueuedOrder.Type.MOVE) {
                        px = order.x;
                        py = order.y;
                    } else {
                        int targetId = order.type == QueuedOrder.Type.ATTACK ? order.targetUnitId : order.targetBuildingUnitId;
                        Entity orderTarget = unitsById.get(targetId);
                        PositionComponent targetPosition = orderTarget != null ? orderTarget.getComponent(PositionComponent.class) : null;
                        if (targetPosition == null) {
                            continue; // цель уже пропала — эта точка сама скоро отвалится из очереди на сервере, просто не показываем её сейчас
                        }
                        px = targetPosition.position.x;
                        py = targetPosition.position.y;
                    }
                    unitSnapshot.queuedOrders.add(new QueuedOrderPoint(px, py, order.type.ordinal()));
                }
            }

            snapshot.units.add(unitSnapshot);
        }
        // Ставка изменения ресурса — реально измеренная разница с прошлой
        // рассылки снапшота (см. javadoc PlayerResources.ironRate), а не
        // отдельно вычисленная "теоретическая" формула. Делим на РЕАЛЬНО
        // прошедшее время (timeSinceLastResourceRateUpdate), а не на
        // номинальный GameConstants.SNAPSHOT_RATE — см. её javadoc, почему
        // это раньше давало скачущую ставку вместо стабильного числа.
        for (PlayerResources resources : resourcesByPlayer.values()) {
            float[] previous = previousResourceValues.get(resources.playerId);
            if (previous != null && timeSinceLastResourceRateUpdate > 0f) {
                resources.ironRate = (resources.iron - previous[0]) / timeSinceLastResourceRateUpdate;
                resources.electricityRate = (resources.electricity - previous[1]) / timeSinceLastResourceRateUpdate;
            }
            previousResourceValues.put(resources.playerId, new float[]{resources.iron, resources.electricity});
        }
        timeSinceLastResourceRateUpdate = 0f;
        snapshot.playerResources.addAll(resourcesByPlayer.values());

        for (Map.Entry<Integer, float[]> entry : fogTimeSinceVisible.entrySet()) {
            FogSnapshot fogSnapshot = new FogSnapshot(entry.getKey());
            float[] timeSinceVisible = entry.getValue();
            fogSnapshot.revealed = new boolean[timeSinceVisible.length];
            for (int i = 0; i < timeSinceVisible.length; i++) {
                fogSnapshot.revealed[i] = timeSinceVisible[i] < GameConstants.FOG_REVEAL_GRACE_PERIOD;
            }
            snapshot.fog.add(fogSnapshot);
        }

        server.sendToAllTCP(snapshot);
    }

    /**
     * Пересчитывает туман войны для каждого игрока — какие клетки сетки
     * (GameConstants.FOG_GRID_WIDTH/HEIGHT) видны прямо сейчас хотя бы
     * одному его юниту/зданию (markVisibleCircle), и обновляет
     * fogTimeSinceVisible: 0 для только что увиденных клеток, плюс
     * deltaTime для всех остальных. Сама рассылка "видно/не видно"
     * (revealed = timeSinceVisible < FOG_REVEAL_GRACE_PERIOD) считается
     * отдельно, в broadcastSnapshot — тут только обновление счётчиков.
     */
    private void updateFogOfWar(float deltaTime) {
        for (Integer playerId : resourcesByPlayer.keySet()) {
            float[] timeSinceVisible = fogTimeSinceVisible.get(playerId);
            if (timeSinceVisible == null) {
                timeSinceVisible = new float[GameConstants.FOG_GRID_WIDTH * GameConstants.FOG_GRID_HEIGHT];
                Arrays.fill(timeSinceVisible, Float.MAX_VALUE); // изначально всё в тумане — партия только начинается
                fogTimeSinceVisible.put(playerId, timeSinceVisible);
            }

            boolean[] visibleNow = new boolean[timeSinceVisible.length];
            for (Entity entity : unitsById.values()) {
                OwnerComponent owner = entity.getComponent(OwnerComponent.class);
                if (owner == null || owner.playerId != playerId) {
                    continue;
                }
                PositionComponent position = entity.getComponent(PositionComponent.class);
                if (position == null) {
                    continue;
                }
                float sightRadius = sightRadiusForEntity(entity);
                if (sightRadius > 0f) {
                    markVisibleCircle(visibleNow, position.position.x, position.position.y, sightRadius);
                }
            }

            for (int i = 0; i < timeSinceVisible.length; i++) {
                timeSinceVisible[i] = visibleNow[i] ? 0f : timeSinceVisible[i] + deltaTime;
            }
        }
    }

    /** Дальность обзора сущности — здание или юнит, по своему набору данных (BuildingDefinitions/UnitDefinitions). 0, если ни то, ни другое (не должно происходить). */
    private float sightRadiusForEntity(Entity entity) {
        BuildingComponent building = entity.getComponent(BuildingComponent.class);
        if (building != null) {
            return BuildingDefinitions.sightRadiusFor(building.type);
        }
        UnitTypeComponent unitType = entity.getComponent(UnitTypeComponent.class);
        if (unitType != null) {
            return UnitDefinitions.sightRadiusFor(unitType.type);
        }
        return 0f;
    }

    /**
     * Отмечает true все клетки сетки в радиусе radius вокруг (cx, cy) —
     * перебирает только клетки в пределах ограничивающего квадрата
     * (не всю сетку целиком на каждый юнит), с точной проверкой
     * расстояния от центра клетки до (cx, cy) внутри.
     */
    private void markVisibleCircle(boolean[] visible, float cx, float cy, float radius) {
        int cellRadius = (int) Math.ceil(radius / GameConstants.FOG_GRID_CELL_SIZE) + 1;
        int centerCellX = (int) (cx / GameConstants.FOG_GRID_CELL_SIZE);
        int centerCellY = (int) (cy / GameConstants.FOG_GRID_CELL_SIZE);
        float radiusSquared = radius * radius;

        for (int dx = -cellRadius; dx <= cellRadius; dx++) {
            int cellX = centerCellX + dx;
            if (cellX < 0 || cellX >= GameConstants.FOG_GRID_WIDTH) {
                continue;
            }
            for (int dy = -cellRadius; dy <= cellRadius; dy++) {
                int cellY = centerCellY + dy;
                if (cellY < 0 || cellY >= GameConstants.FOG_GRID_HEIGHT) {
                    continue;
                }
                float cellCenterX = (cellX + 0.5f) * GameConstants.FOG_GRID_CELL_SIZE;
                float cellCenterY = (cellY + 0.5f) * GameConstants.FOG_GRID_CELL_SIZE;
                float deltaX = cellCenterX - cx;
                float deltaY = cellCenterY - cy;
                if (deltaX * deltaX + deltaY * deltaY <= radiusSquared) {
                    visible[cellY * GameConstants.FOG_GRID_WIDTH + cellX] = true;
                }
            }
        }
    }
}
