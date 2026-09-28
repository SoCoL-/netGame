package ru.socol.supreme.server;

import com.esotericsoftware.kryonet.Server;

import java.util.HashMap;
import java.util.Map;

/**
 * KryoNet Server, который никогда не биндится на порт: GameServer
 * использует его только для server.sendToTCP(connectionId, ...) (разовые
 * ErrorResponse конкретному игроку) — тут этот вызов просто
 * перенаправляется в FakeConnection с тем же id, чтобы тест видел
 * ошибку там же, где и все остальные сообщения этому игроку.
 */
class RecordingServer extends Server {

    private final Map<Integer, FakeConnection> connectionsById = new HashMap<>();

    RecordingServer(FakeConnection... connections) {
        for (FakeConnection connection : connections) {
            connectionsById.put(connection.getID(), connection);
        }
    }

    @Override
    public void sendToTCP(int connectionId, Object object) {
        FakeConnection connection = connectionsById.get(connectionId);
        if (connection != null) {
            connection.sendTCP(object);
        }
    }
}
