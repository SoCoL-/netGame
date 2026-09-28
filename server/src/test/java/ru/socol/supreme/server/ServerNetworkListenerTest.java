package ru.socol.supreme.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.shared.network.messages.CreateLobbyRequest;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.LobbyListMessage;
import ru.socol.supreme.shared.network.messages.LobbyStateMessage;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ServerNetworkListener — только маршрутизация: сообщения лобби идут в
 * LobbyManager, игровые — в GameServer матча этого соединения, а если
 * матча нет, молча отбрасываются (клиент мог прислать приказ, например,
 * сразу после конца игры).
 */
class ServerNetworkListenerTest {

    private LobbyManager manager;
    private ServerNetworkListener listener;
    private FakeConnection connection;

    @BeforeEach
    void setUp() {
        manager = new LobbyManager();
        listener = new ServerNetworkListener(manager);
        connection = new FakeConnection(1);
    }

    @Test
    void lobbyMessagesAreRoutedToLobbyManager() {
        JoinRequest join = new JoinRequest();
        join.playerName = "Alice";
        listener.received(connection, join);
        assertNotNull(connection.lastSent(JoinResponse.class));
        assertNotNull(connection.lastSent(LobbyListMessage.class));

        CreateLobbyRequest create = new CreateLobbyRequest();
        create.lobbyName = "Room";
        listener.received(connection, create);
        assertEquals("Room", connection.lastSent(LobbyStateMessage.class).name);
    }

    @Test
    void gameMessagesWithoutActiveMatchAreDropped() {
        listener.received(connection, new MoveUnitRequest());
        listener.received(connection, new QueueUnitRequest());
        listener.received(connection, "неизвестное сообщение");
        assertTrue(connection.sent.isEmpty());
    }

    @Test
    void disconnectIsForwardedToLobbyManager() {
        listener.received(connection, new JoinRequest());
        listener.received(connection, new CreateLobbyRequest());

        listener.disconnected(connection);

        FakeConnection browser = new FakeConnection(2);
        listener.received(browser, new JoinRequest());
        assertTrue(browser.lastSent(LobbyListMessage.class).lobbies.isEmpty(), "опустевшая комната удалена");
    }
}
