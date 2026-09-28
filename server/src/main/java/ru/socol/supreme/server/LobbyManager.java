package ru.socol.supreme.server;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Server;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.LobbyPhase;
import ru.socol.supreme.shared.network.NetworkRegistration;
import ru.socol.supreme.shared.network.messages.CreateLobbyRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.GameStartedMessage;
import ru.socol.supreme.shared.network.messages.JoinLobbyRequest;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.LeaveLobbyRequest;
import ru.socol.supreme.shared.network.messages.LobbyListMessage;
import ru.socol.supreme.shared.network.messages.LobbyStateMessage;
import ru.socol.supreme.shared.network.messages.LobbySummary;
import ru.socol.supreme.shared.network.messages.SetReadyRequest;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Единственный на весь процесс оркестратор — поднимает и владеет ОБЩИМ на
 * все матчи KryoNet Server (раньше это делал сам GameServer, см. её
 * javadoc), ведёт список всех комнат лобби, маршрутизирует сообщения
 * управления лобби (Create/Join/Leave/SetReady) и создаёт по одному
 * экземпляру GameServer на каждый стартовавший матч — на СВОЁМ отдельном
 * потоке, чтобы долгий матч не блокировал тиканье отсчётов и обработку
 * сообщений остальных лобби (см. javadoc GameServer.start()).
 *
 * Все методы-обработчики (handleXxx/onSessionEnded/tickCountdowns/sessionFor)
 * synchronized на this — то же самое соглашение, что и у GameServer, по той
 * же причине: общее изменяемое состояние (lobbiesById/connectionIdToLobby)
 * трогают одновременно и сетевой поток KryoNet (много вызовов handleXxx),
 * и отдельный поток тиканья обратных отсчётов (tickCountdowns). У каждого
 * запущенного матча — СВОЙ, отдельный монитор (сам объект GameServer), так
 * что долгая симуляция внутри GameServer.runLoop() никогда не держит лок
 * LobbyManager. Единственная точка, где поток матча обращается обратно к
 * LobbyManager — callback gameEndListener (см. onSessionEnded), который
 * срабатывает СТРОГО ПОСЛЕ того, как GameServer.runLoop() уже вышел из
 * своего while и покинул все свои synchronized-блоки — обратного захвата
 * "чужого" лока изнутри "своего" тут нет, взаимной блокировки быть не может.
 */
public class LobbyManager {

    /** Сколько раз в секунду пересчитывается обратный отсчёт STARTING-комнат и рассылается тикающее значение клиентам (LobbyRoomScreen). */
    private static final float COUNTDOWN_TICK_INTERVAL_SECONDS = 0.5f;

    private final Server server = new Server(GameConstants.NETWORK_WRITE_BUFFER_SIZE, GameConstants.NETWORK_OBJECT_BUFFER_SIZE);

    /** connectionId -> имя игрока (JoinRequest.playerName), живёт со времени коннекта до disconnected(). */
    private final Map<Integer, String> playerNameByConnectionId = new LinkedHashMap<>();

    /** lobbyId -> комната. LinkedHashMap — чтобы список лобби на клиенте не "прыгал" произвольным образом при каждом обновлении. */
    private final Map<Integer, Lobby> lobbiesById = new LinkedHashMap<>();

    /** connectionId -> комната, в которой это соединение сейчас занимает слот (browsing-соединения тут отсутствуют). */
    private final Map<Integer, Lobby> connectionIdToLobby = new LinkedHashMap<>();

    private final AtomicInteger lobbyIdSequence = new AtomicInteger(1);

    /**
     * Поднимает общий Server (регистрация Kryo, bind, start — раньше это
     * делал GameServer.start(), теперь ровно один раз на весь процесс) и
     * запускает фоновый поток тиканья обратных отсчётов лобби.
     */
    public void start() throws IOException {
        NetworkRegistration.register(server);
        server.addListener(new ServerNetworkListener(this));
        // TCP-only — см. тот же комментарий, что раньше был в GameServer.start().
        server.bind(GameConstants.TCP_PORT);
        server.start();
        System.out.println("Server started on TCP " + GameConstants.TCP_PORT);

        Thread countdownThread = new Thread(this::countdownLoop, "lobby-countdown");
        countdownThread.setDaemon(true);
        countdownThread.start();
    }

    private void countdownLoop() {
        while (true) {
            sleep(COUNTDOWN_TICK_INTERVAL_SECONDS);
            tickCountdowns();
        }
    }

    private void sleep(float seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Раз в COUNTDOWN_TICK_INTERVAL_SECONDS уменьшает countdownRemaining
     * каждой STARTING-комнаты и либо рассылает новое тикающее значение (для
     * LobbyRoomScreen), либо, если отсчёт дошёл до нуля, стартует матч.
     * Не трогает набор ключей lobbiesById (startMatch меняет только сами
     * объекты Lobby) — безопасно перебирать values() без ConcurrentModificationException.
     */
    private synchronized void tickCountdowns() {
        for (Lobby lobby : lobbiesById.values()) {
            if (lobby.phase != LobbyPhase.STARTING) {
                continue;
            }
            lobby.countdownRemaining -= COUNTDOWN_TICK_INTERVAL_SECONDS;
            if (lobby.countdownRemaining <= 0f) {
                startMatch(lobby);
            } else {
                sendLobbyStateToOccupants(lobby);
            }
        }
    }

    // ---- Подключение и список лобби ----

    /**
     * Первое сообщение от только что подключившегося клиента — запоминаем
     * его имя (используется как отображаемое имя в слотах комнат, см.
     * javadoc JoinRequest) и сразу шлём в ответ и подтверждение, и текущий
     * список комнат — второе сообщение отдельно запрашивать не нужно (см.
     * javadoc JoinResponse).
     */
    synchronized void handleJoin(Connection connection, JoinRequest request) {
        String name = (request.playerName == null || request.playerName.trim().isEmpty())
                ? "Player"
                : request.playerName.trim();
        playerNameByConnectionId.put(connection.getID(), name);

        JoinResponse response = new JoinResponse();
        response.accepted = true;
        response.message = "Welcome, " + name;
        connection.sendTCP(response);

        sendLobbyListTo(connection);
    }

    /**
     * Создатель автоматически занимает слот 0 своей новой комнаты (см.
     * javadoc CreateLobbyRequest) — отдельного JoinLobbyRequest следом не
     * требуется. Молча игнорируется, если соединение уже сидит в какой-то
     * другой комнате — сперва нужно выйти (LeaveLobbyRequest).
     */
    synchronized void handleCreateLobby(Connection connection, CreateLobbyRequest request) {
        if (connectionIdToLobby.containsKey(connection.getID())) {
            return;
        }

        int id = lobbyIdSequence.getAndIncrement();
        String name = (request.lobbyName == null || request.lobbyName.trim().isEmpty())
                ? ("Лобби #" + id)
                : request.lobbyName.trim();
        Lobby lobby = new Lobby(id, name);

        String playerName = playerNameByConnectionId.getOrDefault(connection.getID(), "Player");
        lobby.slotConnection[0] = connection;
        lobby.slotPlayerName[0] = playerName;

        lobbiesById.put(id, lobby);
        connectionIdToLobby.put(connection.getID(), lobby);

        sendLobbyStateToOccupants(lobby);
        broadcastLobbyListToBrowsers();
    }

    /**
     * Занимает первый свободный слот указанной комнаты. Отказывает (шлёт
     * ErrorResponse), если комната не найдена, уже полна или матч в ней уже
     * идёт — WAITING единственная фаза, в которую можно войти.
     */
    synchronized void handleJoinLobby(Connection connection, JoinLobbyRequest request) {
        if (connectionIdToLobby.containsKey(connection.getID())) {
            return;
        }

        Lobby lobby = lobbiesById.get(request.lobbyId);
        if (lobby == null || lobby.phase != LobbyPhase.WAITING) {
            connection.sendTCP(new ErrorResponse("Эта комната сейчас недоступна"));
            return;
        }

        int slot = lobby.firstFreeSlot();
        if (slot == -1) {
            connection.sendTCP(new ErrorResponse("Комната уже заполнена"));
            return;
        }

        String playerName = playerNameByConnectionId.getOrDefault(connection.getID(), "Player");
        lobby.slotConnection[slot] = connection;
        lobby.slotPlayerName[slot] = playerName;
        connectionIdToLobby.put(connection.getID(), lobby);

        sendLobbyStateToOccupants(lobby);
        broadcastLobbyListToBrowsers();
    }

    /**
     * Выход из комнаты обратно в общий список — игнорируется, если матч в
     * этой комнате уже идёт (см. javadoc LeaveLobbyRequest, почему). Всегда
     * отменяет уже идущий отсчёт (Lobby.removePlayer сбрасывает готовность
     * обоих слотов), а если комната опустела — удаляет её совсем.
     */
    synchronized void handleLeaveLobby(Connection connection, LeaveLobbyRequest request) {
        Lobby lobby = connectionIdToLobby.get(connection.getID());
        if (lobby == null || lobby.phase == LobbyPhase.IN_GAME) {
            return;
        }

        lobby.removePlayer(connection);
        lobby.phase = LobbyPhase.WAITING;
        connectionIdToLobby.remove(connection.getID());

        if (lobby.isEmpty()) {
            lobbiesById.remove(lobby.id);
        } else {
            sendLobbyStateToOccupants(lobby);
        }

        // Вышедший игрок снова "просто браузит" список — обновляем ему
        // список немедленно, не дожидаясь следующего broadcastLobbyListToBrowsers.
        sendLobbyListTo(connection);
        broadcastLobbyListToBrowsers();
    }

    /**
     * Подтверждение/снятие готовности своего слота. Снятие готовности во
     * время уже идущего отсчёта (STARTING) отменяет его — комната
     * возвращается в WAITING (см. постановку задачи: "если кто-то снимает
     * готовность... отсчёт отменяется"). Отсчёт запускается, только когда
     * ОБА слота заняты и ОБА готовы — Lobby.isFull()+allOccupiedSlotsReady().
     */
    synchronized void handleSetReady(Connection connection, SetReadyRequest request) {
        Lobby lobby = connectionIdToLobby.get(connection.getID());
        if (lobby == null || lobby.phase == LobbyPhase.IN_GAME) {
            return;
        }

        int slot = lobby.slotIndexOf(connection);
        if (slot == -1) {
            return;
        }
        lobby.slotReady[slot] = request.ready;

        if (!request.ready && lobby.phase == LobbyPhase.STARTING) {
            lobby.phase = LobbyPhase.WAITING;
            lobby.countdownRemaining = 0f;
        } else if (request.ready && lobby.phase == LobbyPhase.WAITING
                && lobby.isFull() && lobby.allOccupiedSlotsReady()) {
            lobby.phase = LobbyPhase.STARTING;
            lobby.countdownRemaining = GameConstants.LOBBY_COUNTDOWN_SECONDS;
        }

        sendLobbyStateToOccupants(lobby);
    }

    /**
     * Отсчёт STARTING-комнаты дошёл до нуля (вызывается только из
     * tickCountdowns, уже под synchronized(this)) — рассылает обоим
     * игрокам их playerId на этот матч (GameStartedMessage) и запускает
     * GameServer этой сессии на отдельном потоке, не блокируя ни этот
     * (сетевой/тикающий), ни чужие матчи.
     */
    private void startMatch(Lobby lobby) {
        lobby.phase = LobbyPhase.IN_GAME;
        lobby.countdownRemaining = 0f;

        Connection[] connections = lobby.slotConnection.clone();
        for (int playerId = 0; playerId < connections.length; playerId++) {
            GameStartedMessage message = new GameStartedMessage();
            message.yourPlayerId = playerId;
            connections[playerId].sendTCP(message);
        }

        GameServer session = new GameServer(server, connections, () -> onSessionEnded(lobby));
        lobby.activeSession = session;

        Thread matchThread = new Thread(session::start, "match-" + lobby.id);
        matchThread.setDaemon(true);
        matchThread.start();
    }

    /**
     * Вызывается ОДИН раз потоком завершившегося матча, уже после того как
     * GameServer.start() вернулся (см. её же javadoc про порядок и про
     * отсутствие риска взаимной блокировки). Возвращает лобби в WAITING с
     * сохранёнными слотами — оба ещё подключённых игрока остаются в той же
     * комнате и могут сыграть реванш (постановка задачи: "после проигрыша
     * игроки попадают обратно в лобби"). Игроков, отвалившихся ПОСРЕДИ
     * матча, до сих пор держали в своих слотах (см. handleDisconnect ниже,
     * почему) — только теперь, когда матч точно закончился, их слоты
     * реально освобождаются.
     */
    private synchronized void onSessionEnded(Lobby lobby) {
        lobby.activeSession = null;
        lobby.phase = LobbyPhase.WAITING;
        lobby.resetReady();

        for (Connection connection : lobby.slotConnection.clone()) {
            if (connection != null && !connection.isConnected()) {
                lobby.removePlayer(connection);
            }
        }

        if (lobby.isEmpty()) {
            lobbiesById.remove(lobby.id);
        } else {
            sendLobbyStateToOccupants(lobby);
        }
        broadcastLobbyListToBrowsers();
    }

    /**
     * Матч этой комнаты (если соединение сейчас сидит в комнате с
     * phase == IN_GAME) — используется ServerNetworkListener, чтобы
     * маршрутизировать все игровые сообщения (QueueUnitRequest,
     * MoveUnitRequest и т.д.) именно в ЕГО GameServer, а не в чужой.
     * null, если соединение сейчас не в матче (браузит список или сидит в
     * комнате, которая ещё не стартовала).
     */
    synchronized GameServer sessionFor(Connection connection) {
        Lobby lobby = connectionIdToLobby.get(connection.getID());
        return lobby != null ? lobby.activeSession : null;
    }

    /**
     * Отключение игрока — общее для ЛЮБОЙ фазы, вызывается из
     * ServerNetworkListener.disconnected() для каждого разорвавшегося
     * соединения. Во время матча (IN_GAME) НЕ трогает слоты лобби напрямую
     * — только пересылает событие в GameServer этой сессии, который сам
     * уберёт юниты игрока, и его следующий же тик checkGameOver увидит
     * пропавший дом и объявит победителя (та же логика, что и при обычной
     * потере дома в бою — см. javadoc GameServer.handleDisconnect). Слот в
     * Lobby освобождается только после конца матча, в onSessionEnded —
     * пока матч идёт, Lobby.slotConnection остаётся единственным местом,
     * которое помнит, что это было ЗА игрок на этом матче.
     */
    synchronized void handleDisconnect(Connection connection) {
        playerNameByConnectionId.remove(connection.getID());
        Lobby lobby = connectionIdToLobby.remove(connection.getID());
        if (lobby == null) {
            return;
        }

        if (lobby.phase == LobbyPhase.IN_GAME) {
            if (lobby.activeSession != null) {
                lobby.activeSession.handleDisconnect(connection);
            }
            return;
        }

        lobby.removePlayer(connection);
        lobby.phase = LobbyPhase.WAITING;
        if (lobby.isEmpty()) {
            lobbiesById.remove(lobby.id);
        } else {
            sendLobbyStateToOccupants(lobby);
        }
        broadcastLobbyListToBrowsers();
    }

    // ---- Рассылка ----

    private void sendLobbyStateToOccupants(Lobby lobby) {
        for (int slot = 0; slot < lobby.slotConnection.length; slot++) {
            Connection occupant = lobby.slotConnection[slot];
            if (occupant == null) {
                continue;
            }
            occupant.sendTCP(buildLobbyStateMessage(lobby, slot));
        }
    }

    private LobbyStateMessage buildLobbyStateMessage(Lobby lobby, int yourSlotIndex) {
        LobbyStateMessage message = new LobbyStateMessage();
        message.lobbyId = lobby.id;
        message.name = lobby.name;
        message.slotPlayerName = lobby.slotPlayerName.clone();
        message.slotReady = lobby.slotReady.clone();
        message.yourSlotIndex = yourSlotIndex;
        message.phase = lobby.phase.ordinal();
        message.countdownRemaining = lobby.countdownRemaining;
        return message;
    }

    private void sendLobbyListTo(Connection connection) {
        connection.sendTCP(buildLobbyListMessage());
    }

    /**
     * Рассылает актуальный список комнат всем подключённым, кто СЕЙЧАС НЕ
     * сидит ни в одной комнате (т.е. смотрит на экран браузера лобби) —
     * тем, кто уже внутри комнаты, список не нужен, у них свой
     * LobbyStateMessage (см. sendLobbyStateToOccupants).
     */
    private void broadcastLobbyListToBrowsers() {
        LobbyListMessage message = buildLobbyListMessage();
        for (Connection connection : server.getConnections()) {
            if (!connectionIdToLobby.containsKey(connection.getID())) {
                connection.sendTCP(message);
            }
        }
    }

    private LobbyListMessage buildLobbyListMessage() {
        LobbyListMessage message = new LobbyListMessage();
        for (Lobby lobby : lobbiesById.values()) {
            LobbySummary summary = new LobbySummary();
            summary.lobbyId = lobby.id;
            summary.name = lobby.name;
            summary.occupiedSlots = lobby.occupiedSlotCount();
            summary.maxSlots = GameConstants.MAX_PLAYERS;
            summary.phase = lobby.phase.ordinal();
            message.lobbies.add(summary);
        }
        return message;
    }
}
