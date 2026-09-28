package ru.socol.supreme.server;

import com.esotericsoftware.kryonet.Connection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Подставное KryoNet-соединение для серверных тестов — без сокета и без
 * сериализации: всё, что сервер "отправил" через sendTCP, просто
 * складывается в список sent, откуда тест его потом достаёт и проверяет.
 * getID()/isConnected() переопределены, потому что у настоящего
 * Connection их выставляет только сам KryoNet при реальном подключении.
 *
 * sent — CopyOnWriteArrayList, а не обычный ArrayList: в сквозном тесте
 * лобби (LobbyManagerTest) матч крутится на своём отдельном потоке и
 * пишет сюда снапшоты одновременно с тем, как тест читает список.
 */
class FakeConnection extends Connection {

    private final int id;
    volatile boolean connected = true;
    final List<Object> sent = new CopyOnWriteArrayList<>();

    FakeConnection(int id) {
        this.id = id;
    }

    @Override
    public int getID() {
        return id;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public int sendTCP(Object object) {
        sent.add(object);
        return 0;
    }

    /** Все отправленные этому соединению сообщения указанного типа, в порядке отправки. */
    <T> List<T> sentOf(Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Object message : sent) {
            if (type.isInstance(message)) {
                result.add(type.cast(message));
            }
        }
        return result;
    }

    /** Последнее отправленное сообщение указанного типа, или null, если такого не было. */
    <T> T lastSent(Class<T> type) {
        List<T> all = sentOf(type);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }
}
