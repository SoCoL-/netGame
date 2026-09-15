package ru.socol.supreme.server;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.PooledEngine;
import com.badlogic.gdx.math.Vector2;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Server;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingPlacement;
import ru.socol.supreme.shared.BuildingSizes;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PathComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.UnitComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.network.NetworkRegistration;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.PathPoint;
import ru.socol.supreme.shared.network.messages.PlaceIronMineRequest;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;
import ru.socol.supreme.shared.network.messages.SetRallyPointRequest;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;
import ru.socol.supreme.shared.network.messages.WorldSnapshot;
import ru.socol.supreme.shared.pathfinding.Pathfinding;
import ru.socol.supreme.shared.pathfinding.SpatialHashGrid;
import ru.socol.supreme.shared.systems.AggroSystem;
import ru.socol.supreme.shared.systems.CollisionSystem;
import ru.socol.supreme.shared.systems.CombatSystem;
import ru.socol.supreme.shared.systems.ConstructionSystem;
import ru.socol.supreme.shared.systems.MovementSystem;
import ru.socol.supreme.shared.systems.ProductionSystem;
import ru.socol.supreme.shared.systems.ResourceExtractionSystem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Авторитетный игровой сервер: владеет Ashley-движком, симулирует движение и
 * бой, применяет правила игры (максимум 2 игрока, максимум 300 юнитов на
 * всю игру суммарно, карта 2000x2000) и с фиксированной частотой рассылает
 * снапшоты мира всем клиентам. Как только у одного из игроков уничтожен дом
 * (не казарма стрелков — см. spawnHome) — объявляет победителя и
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
        engine.addSystem(new CombatSystem(unitsById, this::handleShotFired));
        engine.addSystem(new ProductionSystem(unitsById, resourcesByPlayer, this::createUnit));
        engine.addSystem(new ConstructionSystem());
        engine.addSystem(new ResourceExtractionSystem(unitsById, resourcesByPlayer));
        engine.addSystem(new MovementSystem());
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
        resourcesByPlayer.put(playerId, new PlayerResources(playerId));
        spawnHome(playerId);

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
            }
            return owned;
        });

        buildingIdByPlayer.remove(playerId);
        resourcesByPlayer.remove(playerId);
        previousResourceValues.remove(playerId);
        playerSlotUsed[playerId] = false;
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
     * /handlePlaceIronMine.
     */
    private void spawnHome(int playerId) {
        float[] home = BuildingDefinitions.homeSpawnPoint(playerId);
        int homeUnitId = spawnBuilding(playerId, BuildingType.HOME, home[0], home[1]);
        buildingIdByPlayer.put(playerId, homeUnitId);
    }

    /**
     * Единая точка создания ЛЮБОГО здания — все свойства (размер, здоровье,
     * время постройки, что производит/добывает) решает BuildingDefinitions
     * по type, а не параметры этой функции. Если buildTimeFor(type) > 0
     * (сейчас — только шахта и электростанция) здание появляется ещё
     * строящимся, с ConstructionComponent — ConstructionSystem сама
     * доведёт его до рабочего состояния. Иначе (дом, казарма) — сразу
     * готовым, с ProductionComponent, если это здание производит юнитов.
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
        HealthComponent health = engine.createComponent(HealthComponent.class);
        health.maxHealth = maxHealth;
        health.currentHealth = maxHealth;

        BuildingComponent buildingMarker = engine.createComponent(BuildingComponent.class);
        buildingMarker.type = type;

        // Намеренно без DirectionComponent — здание неподвижно, и этого
        // достаточно, чтобы MovementSystem и (как атакующего) CombatSystem
        // автоматически его игнорировали за счёт своих Family-фильтров.
        building.add(position).add(unitComponent).add(owner).add(health).add(buildingMarker);

        float buildTime = BuildingDefinitions.buildTimeFor(type);
        if (buildTime > 0f) {
            ConstructionComponent construction = engine.createComponent(ConstructionComponent.class);
            construction.totalTime = buildTime;
            construction.remaining = buildTime;
            building.add(construction);
        } else {
            UnitType producesUnitType = BuildingDefinitions.producesUnitTypeFor(type);
            if (producesUnitType != null) {
                ProductionComponent production = engine.createComponent(ProductionComponent.class);
                production.queuedCount = 0;
                production.progress = 0f;
                production.producesUnitType = producesUnitType;
                building.add(production);
            }
        }

        engine.addEntity(building);
        unitsById.put(unitId, building);
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
            return; // не здание с производством (сейчас все здания такие, но на всякий случай)
        }

        if (unitsById.size() >= GameConstants.MAX_TOTAL_UNITS) {
            server.sendToTCP(connection.getID(),
                    new ErrorResponse("Unit limit reached (" + GameConstants.MAX_TOTAL_UNITS + " total)"));
            return;
        }

        production.queuedCount++;
    }

    /**
     * Точка сбора — куда идёт каждый только что произведённый юнит этого
     * здания (см. ProductionSystem). Клиент присылает её, когда игрок
     * кликает левой кнопкой по карте при выделенном СВОЁМ здании (см.
     * GameScreen.touchUp) — координаты не проверяются на валидность
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
        spawnBuilding(playerId, BuildingType.IRON_MINE, deposit[0], deposit[1]);
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

        spawnBuilding(playerId, type, request.x, request.y);
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

        // Ручной приказ на движение отменяет текущую атаку, как в большинстве RTS.
        if (unit.getComponent(AttackComponent.class) != null) {
            unit.remove(AttackComponent.class);
        }

        PositionComponent position = unit.getComponent(PositionComponent.class);
        float targetX = clamp(request.targetX, 0f, GameConstants.MAP_WIDTH);
        float targetY = clamp(request.targetY, 0f, GameConstants.MAP_HEIGHT);

        // Pathfinding сама решает: прямая линия свободна — идём напрямую,
        // как раньше; если по пути препятствие (вода/здание) — обойдёт его.
        Pathfinding.setDestination(unit, position, direction, targetX, targetY);
    }

    synchronized void handleAttackUnit(Connection connection, AttackUnitRequest request) {
        if (gameOver) {
            return;
        }

        Integer playerId = connectionToPlayer.get(connection.getID());
        if (playerId == null) {
            return;
        }

        Entity attacker = unitsById.get(request.unitId);
        Entity target = unitsById.get(request.targetUnitId);
        if (attacker == null || target == null) {
            return;
        }

        OwnerComponent attackerOwner = attacker.getComponent(OwnerComponent.class);
        if (attackerOwner == null || attackerOwner.playerId != playerId) {
            return; // не ваш юнит
        }

        if (attacker.getComponent(DirectionComponent.class) == null) {
            return; // здание атаковать не может
        }

        OwnerComponent targetOwner = target.getComponent(OwnerComponent.class);
        if (targetOwner == null || targetOwner.playerId == playerId) {
            return; // нельзя атаковать своих (свои здания — тоже)
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
        attack.targetUnitId = request.targetUnitId;
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
            unitSnapshot.health = health.currentHealth;
            unitSnapshot.maxHealth = health.maxHealth;
            BuildingComponent buildingMarker = unit.getComponent(BuildingComponent.class);
            unitSnapshot.building = buildingMarker != null;

            if (buildingMarker != null) {
                unitSnapshot.buildingType = buildingMarker.type.ordinal();
            } else {
                UnitTypeComponent unitTypeComponent = unit.getComponent(UnitTypeComponent.class);
                if (unitTypeComponent != null) {
                    unitSnapshot.unitType = unitTypeComponent.type.ordinal();
                }
            }

            ProductionComponent production = unit.getComponent(ProductionComponent.class);
            if (production != null) {
                unitSnapshot.queuedCount = production.queuedCount;
                unitSnapshot.buildProgress = production.progress;
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
        server.sendToAllTCP(snapshot);
    }
}
