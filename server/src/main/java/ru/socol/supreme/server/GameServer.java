package ru.socol.supreme.server;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.PooledEngine;
import com.badlogic.gdx.math.Vector2;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Server;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.BuildingComponent;
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
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;
import ru.socol.supreme.shared.network.messages.WorldSnapshot;
import ru.socol.supreme.shared.pathfinding.Pathfinding;
import ru.socol.supreme.shared.pathfinding.SpatialHashGrid;
import ru.socol.supreme.shared.systems.AggroSystem;
import ru.socol.supreme.shared.systems.CollisionSystem;
import ru.socol.supreme.shared.systems.CombatSystem;
import ru.socol.supreme.shared.systems.MovementSystem;
import ru.socol.supreme.shared.systems.ProductionSystem;

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
 * (не казарма стрелков — см. spawnArcherBuilding) — объявляет победителя и
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
     * playerId -> его ресурсы. Авторитетное состояние — пока ничего его не
     * меняет (нет зданий добычи, см. ResourceType), но хранение и рассылка
     * клиенту (WorldSnapshot.playerResources) уже готовы для них.
     */
    private final Map<Integer, PlayerResources> resourcesByPlayer = new HashMap<>();

    private final boolean[] playerSlotUsed = new boolean[GameConstants.MAX_PLAYERS];

    private final AtomicInteger unitIdSequence = new AtomicInteger(1);

    private float snapshotAccumulator = 0f;
    private volatile boolean gameOver = false;

    public GameServer() {
        // Приоритет: AggroSystem (-10) до CombatSystem (0) до ProductionSystem (1)
        // до MovementSystem (10) до CollisionSystem (20) — см. их конструкторы.
        //
        // Обе системы ищут соседей через SpatialHashGrid вместо перебора
        // unitsById целиком (O(n) на юнита вместо O(n²) на всех) — у каждой
        // своя сетка со своим размером ячейки, потому что типичный радиус
        // запроса у них совсем разный: агрессия ищет в радиусе дальности
        // атаки (у стрелка 210), коллизии — в радиусе здания/юнита (порядка
        // 60-80). Один общий размер ячейки одинаково плохо подошёл бы обеим.
        SpatialHashGrid aggroGrid = new SpatialHashGrid(UnitDefinitions.maxAttackRadius());
        SpatialHashGrid collisionGrid = new SpatialHashGrid(GameConstants.maxBuildingInteractionRadius());

        engine.addSystem(new AggroSystem(unitsById, aggroGrid));
        engine.addSystem(new CombatSystem(unitsById, this::handleShotFired));
        engine.addSystem(new ProductionSystem(unitsById, this::createUnit));
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
        spawnBuilding(playerId);
        spawnArcherBuilding(playerId);

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
        playerSlotUsed[playerId] = false;
    }

    // ---- Спавн зданий ----

    /**
     * Дом (HQ) ставится в противоположных углах карты — это единственная
     * причина, почему у GameServer вообще есть представление о том, где
     * "начало" у каждого игрока. Единственное здание, которое регистрируется
     * в buildingIdByPlayer — его разрушение заканчивает игру, см. checkGameOver.
     */
    private void spawnBuilding(int playerId) {
        float x = GameConstants.buildingSpawnX(playerId);
        float y = GameConstants.buildingSpawnY(playerId);
        int unitId = spawnBuildingEntity(playerId, x, y, GameConstants.BUILDING_MAX_HEALTH, UnitType.WARRIOR);
        buildingIdByPlayer.put(playerId, unitId);
    }

    /**
     * Казарма стрелков — стоит чуть в стороне от дома того же игрока (см.
     * GameConstants.archerBuildingSpawnPoint), гораздо более хрупкая (70 HP
     * против 500 у дома) и НЕ регистрируется в buildingIdByPlayer: её
     * разрушение не заканчивает игру, просто лишает игрока возможности
     * производить стрелков.
     */
    private void spawnArcherBuilding(int playerId) {
        float[] point = GameConstants.archerBuildingSpawnPoint(playerId);
        spawnBuildingEntity(playerId, point[0], point[1], GameConstants.ARCHER_BUILDING_MAX_HEALTH, UnitType.ARCHER);
    }

    /** Общая часть создания любого здания — единственное, чем отличаются дом и казарма, это позиция/HP/что производят. */
    private int spawnBuildingEntity(int playerId, float x, float y, int maxHealth, UnitType producesType) {
        Entity building = engine.createEntity();

        PositionComponent position = engine.createComponent(PositionComponent.class);
        position.position.set(x, y);

        int unitId = unitIdSequence.getAndIncrement();
        UnitComponent unitComponent = engine.createComponent(UnitComponent.class);
        unitComponent.unitId = unitId;

        OwnerComponent owner = engine.createComponent(OwnerComponent.class);
        owner.playerId = playerId;

        HealthComponent health = engine.createComponent(HealthComponent.class);
        health.maxHealth = maxHealth;
        health.currentHealth = maxHealth;

        BuildingComponent buildingMarker = engine.createComponent(BuildingComponent.class);

        ProductionComponent production = engine.createComponent(ProductionComponent.class);
        production.queuedCount = 0;
        production.progress = 0f;
        production.producesUnitType = producesType;

        // Намеренно без DirectionComponent — здание неподвижно, и этого
        // достаточно, чтобы MovementSystem и (как атакующего) CombatSystem
        // автоматически его игнорировали за счёт своих Family-фильтров.
        building.add(position).add(unitComponent).add(owner).add(health).add(buildingMarker).add(production);
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
            unitSnapshot.building = unit.getComponent(BuildingComponent.class) != null;

            // unitType: для юнита — собственный тип, для здания — что оно производит
            // (см. javadoc UnitSnapshot.unitType).
            UnitTypeComponent unitTypeComponent = unit.getComponent(UnitTypeComponent.class);
            ProductionComponent production = unit.getComponent(ProductionComponent.class);
            if (unitTypeComponent != null) {
                unitSnapshot.unitType = unitTypeComponent.type.ordinal();
            } else if (production != null) {
                unitSnapshot.unitType = production.producesUnitType.ordinal();
            }

            if (production != null) {
                unitSnapshot.queuedCount = production.queuedCount;
                unitSnapshot.buildProgress = production.progress;
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
        snapshot.playerResources.addAll(resourcesByPlayer.values());
        server.sendToAllTCP(snapshot);
    }
}
