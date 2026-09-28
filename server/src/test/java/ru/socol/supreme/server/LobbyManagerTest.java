package ru.socol.supreme.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.LobbyPhase;
import ru.socol.supreme.shared.network.messages.CreateLobbyRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.GameStartedMessage;
import ru.socol.supreme.shared.network.messages.JoinLobbyRequest;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.LeaveLobbyRequest;
import ru.socol.supreme.shared.network.messages.LobbyListMessage;
import ru.socol.supreme.shared.network.messages.LobbyStateMessage;
import ru.socol.supreme.shared.network.messages.SetReadyRequest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LobbyManager — список комнат, слоты, готовность, обратный отсчёт и
 * запуск матча. Сеть не поднимается (start() не вызывается): обработчики
 * handleXxx вызываются напрямую с FakeConnection, а ответы сервера
 * читаются из того, что "отправлено" этим соединениям. Обратный отсчёт
 * прокручивается вызовами tickCountdowns() без фонового потока.
 */
class LobbyManagerTest {

    private LobbyManager manager;
    private FakeConnection alice;
    private FakeConnection bob;
    private FakeConnection carol;

    @BeforeEach
    void setUp() {
        manager = new LobbyManager();
        alice = connect(1, "Alice");
        bob = connect(2, "Bob");
        carol = connect(3, "Carol");
    }

    private FakeConnection connect(int id, String name) {
        FakeConnection connection = new FakeConnection(id);
        JoinRequest request = new JoinRequest();
        request.playerName = name;
        manager.handleJoin(connection, request);
        return connection;
    }

    private int createLobby(FakeConnection owner, String name) {
        CreateLobbyRequest request = new CreateLobbyRequest();
        request.lobbyName = name;
        manager.handleCreateLobby(owner, request);
        return owner.lastSent(LobbyStateMessage.class).lobbyId;
    }

    private void joinLobby(FakeConnection connection, int lobbyId) {
        JoinLobbyRequest request = new JoinLobbyRequest();
        request.lobbyId = lobbyId;
        manager.handleJoinLobby(connection, request);
    }

    private void setReady(FakeConnection connection, boolean ready) {
        SetReadyRequest request = new SetReadyRequest();
        request.ready = ready;
        manager.handleSetReady(connection, request);
    }

    private static LobbyPhase phaseOf(LobbyStateMessage state) {
        return LobbyPhase.values()[state.phase];
    }

    // ---- Подключение ----

    @Test
    void joinIsAcceptedAndImmediatelyFollowedByLobbyList() {
        JoinResponse response = alice.lastSent(JoinResponse.class);
        assertNotNull(response);
        assertTrue(response.accepted);
        assertEquals("Welcome, Alice", response.message);
        assertNotNull(alice.lastSent(LobbyListMessage.class));
    }

    @Test
    void blankPlayerNameFallsBackToDefault() {
        FakeConnection anonymous = connect(4, "   ");
        assertEquals("Welcome, Player", anonymous.lastSent(JoinResponse.class).message);
    }

    // ---- Создание и вход в комнату ----

    @Test
    void creatorTakesSlotZero() {
        createLobby(alice, "  Room  ");
        LobbyStateMessage state = alice.lastSent(LobbyStateMessage.class);
        assertEquals("Room", state.name, "имя комнаты обрезается");
        assertEquals(0, state.yourSlotIndex);
        assertArrayEquals(new String[]{"Alice", null}, state.slotPlayerName);
        assertEquals(LobbyPhase.WAITING, phaseOf(state));
    }

    @Test
    void blankLobbyNameGetsNumberedDefault() {
        int lobbyId = createLobby(alice, "");
        assertEquals("Лобби #" + lobbyId, alice.lastSent(LobbyStateMessage.class).name);
    }

    @Test
    void cannotCreateSecondLobbyWhileSittingInOne() {
        createLobby(alice, "First");
        int statesBefore = alice.sentOf(LobbyStateMessage.class).size();
        CreateLobbyRequest second = new CreateLobbyRequest();
        second.lobbyName = "Second";
        manager.handleCreateLobby(alice, second);
        assertEquals(statesBefore, alice.sentOf(LobbyStateMessage.class).size());

        FakeConnection browser = connect(5, "Dave");
        assertEquals(1, browser.lastSent(LobbyListMessage.class).lobbies.size());
    }

    @Test
    void secondPlayerTakesSlotOneAndBothSeeEachOther() {
        int lobbyId = createLobby(alice, "Room");
        joinLobby(bob, lobbyId);

        LobbyStateMessage bobState = bob.lastSent(LobbyStateMessage.class);
        assertEquals(1, bobState.yourSlotIndex);
        assertArrayEquals(new String[]{"Alice", "Bob"}, bobState.slotPlayerName);

        LobbyStateMessage aliceState = alice.lastSent(LobbyStateMessage.class);
        assertEquals(0, aliceState.yourSlotIndex);
        assertArrayEquals(new String[]{"Alice", "Bob"}, aliceState.slotPlayerName);
    }

    @Test
    void cannotJoinFullOrUnknownLobby() {
        int lobbyId = createLobby(alice, "Room");
        joinLobby(bob, lobbyId);

        joinLobby(carol, lobbyId);
        assertNotNull(carol.lastSent(ErrorResponse.class), "комната заполнена");
        assertNull(carol.lastSent(LobbyStateMessage.class));

        carol.sent.clear();
        joinLobby(carol, 9999);
        assertNotNull(carol.lastSent(ErrorResponse.class), "комнаты нет");
    }

    @Test
    void lobbyListReflectsOccupancy() {
        int lobbyId = createLobby(alice, "Room");
        joinLobby(bob, lobbyId);

        FakeConnection browser = connect(5, "Dave");
        LobbyListMessage list = browser.lastSent(LobbyListMessage.class);
        assertEquals(1, list.lobbies.size());
        assertEquals(lobbyId, list.lobbies.get(0).lobbyId);
        assertEquals(2, list.lobbies.get(0).occupiedSlots);
        assertEquals(GameConstants.MAX_PLAYERS, list.lobbies.get(0).maxSlots);
    }

    // ---- Готовность и обратный отсчёт ----

    @Test
    void countdownStartsOnlyWhenLobbyIsFullAndEveryoneIsReady() {
        int lobbyId = createLobby(alice, "Room");
        setReady(alice, true);
        assertEquals(LobbyPhase.WAITING, phaseOf(alice.lastSent(LobbyStateMessage.class)), "второго игрока ещё нет");

        joinLobby(bob, lobbyId);
        assertEquals(LobbyPhase.WAITING, phaseOf(alice.lastSent(LobbyStateMessage.class)), "Bob ещё не готов");

        setReady(bob, true);
        LobbyStateMessage state = alice.lastSent(LobbyStateMessage.class);
        assertEquals(LobbyPhase.STARTING, phaseOf(state));
        assertEquals(GameConstants.LOBBY_COUNTDOWN_SECONDS, state.countdownRemaining, 0.001f);
    }

    @Test
    void unreadyDuringCountdownCancelsIt() {
        int lobbyId = createLobby(alice, "Room");
        joinLobby(bob, lobbyId);
        setReady(alice, true);
        setReady(bob, true);

        setReady(bob, false);

        LobbyStateMessage state = alice.lastSent(LobbyStateMessage.class);
        assertEquals(LobbyPhase.WAITING, phaseOf(state));
        assertArrayEquals(new boolean[]{true, false}, state.slotReady);
    }

    @Test
    void leavingDuringCountdownCancelsItAndResetsReadiness() {
        int lobbyId = createLobby(alice, "Room");
        joinLobby(bob, lobbyId);
        setReady(alice, true);
        setReady(bob, true);

        manager.handleLeaveLobby(bob, new LeaveLobbyRequest());

        LobbyStateMessage state = alice.lastSent(LobbyStateMessage.class);
        assertEquals(LobbyPhase.WAITING, phaseOf(state));
        assertArrayEquals(new String[]{"Alice", null}, state.slotPlayerName);
        assertArrayEquals(new boolean[]{false, false}, state.slotReady);
        assertNotNull(bob.lastSent(LobbyListMessage.class), "вышедший снова видит список комнат");
    }

    @Test
    void countdownTicksDownBeforeMatchStarts() {
        int lobbyId = createLobby(alice, "Room");
        joinLobby(bob, lobbyId);
        setReady(alice, true);
        setReady(bob, true);

        manager.tickCountdowns();

        LobbyStateMessage state = alice.lastSent(LobbyStateMessage.class);
        assertEquals(LobbyPhase.STARTING, phaseOf(state));
        assertTrue(state.countdownRemaining < GameConstants.LOBBY_COUNTDOWN_SECONDS);
        assertNull(alice.lastSent(GameStartedMessage.class));
    }

    // ---- Выход и отключение ----

    @Test
    void lastPlayerLeavingRemovesLobby() {
        createLobby(alice, "Room");
        manager.handleLeaveLobby(alice, new LeaveLobbyRequest());

        FakeConnection browser = connect(5, "Dave");
        assertTrue(browser.lastSent(LobbyListMessage.class).lobbies.isEmpty());
    }

    @Test
    void disconnectBeforeMatchFreesSlot() {
        int lobbyId = createLobby(alice, "Room");
        joinLobby(bob, lobbyId);

        manager.handleDisconnect(bob);

        assertArrayEquals(new String[]{"Alice", null}, alice.lastSent(LobbyStateMessage.class).slotPlayerName);
        joinLobby(carol, lobbyId);
        assertEquals(1, carol.lastSent(LobbyStateMessage.class).yourSlotIndex, "освободившийся слот можно занять");
    }

    // ---- Сквозной сценарий: отсчёт -> матч -> конец -> реванш ----

    @Test
    void fullMatchLifecycleReturnsSurvivorToLobby() throws InterruptedException {
        int lobbyId = createLobby(alice, "Room");
        joinLobby(bob, lobbyId);
        setReady(alice, true);
        setReady(bob, true);

        for (int i = 0; i < 100 && alice.lastSent(GameStartedMessage.class) == null; i++) {
            manager.tickCountdowns();
        }

        assertEquals(0, alice.lastSent(GameStartedMessage.class).yourPlayerId);
        assertEquals(1, bob.lastSent(GameStartedMessage.class).yourPlayerId);
        assertNotNull(manager.sessionFor(alice), "игровые сообщения теперь маршрутизируются в матч");
        assertNotNull(manager.sessionFor(bob));
        assertNull(manager.sessionFor(carol), "у просто подключённого матча нет");

        // Во время матча из комнаты выйти нельзя.
        manager.handleLeaveLobby(alice, new LeaveLobbyRequest());
        assertNotNull(manager.sessionFor(alice));

        // Bob отваливается — его дом исчезает, Alice побеждает, после
        // паузы POST_GAME_OVER_DELAY_SECONDS комната возвращается в WAITING.
        bob.connected = false;
        manager.handleDisconnect(bob);

        long deadline = System.currentTimeMillis()
                + (long) (GameConstants.POST_GAME_OVER_DELAY_SECONDS * 1000) + 10_000;
        while (manager.sessionFor(alice) != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertNull(manager.sessionFor(alice), "матч завершился");

        LobbyStateMessage state = alice.lastSent(LobbyStateMessage.class);
        assertEquals(lobbyId, state.lobbyId);
        assertEquals(LobbyPhase.WAITING, phaseOf(state));
        assertArrayEquals(new String[]{"Alice", null}, state.slotPlayerName, "слот отключившегося освобождён");
        assertArrayEquals(new boolean[]{false, false}, state.slotReady, "реванш начинается с чистой готовности");
    }
}
