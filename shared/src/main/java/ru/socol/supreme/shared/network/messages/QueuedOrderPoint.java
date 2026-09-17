package ru.socol.supreme.shared.network.messages;

/**
 * Одна точка отложенного приказа из очереди юнита — список таких точек в
 * UnitSnapshot.queuedOrders нужен только для отрисовки цепочки очереди на
 * клиенте (GameScreen.drawOrderQueue), сама очередь на исход игры уже
 * повлияла тем, что она есть (OrderQueueSystem её и без клиента
 * разбирает) — это чисто визуальная информация для игрока.
 *
 * (x, y) — куда идёт этот конкретный приказ: для MOVE — прямо точка
 * назначения; для ATTACK/BUILD — позиция цели НА МОМЕНТ снапшота (сервер
 * сам разрешает id цели в координаты в GameServer.broadcastSnapshot,
 * клиенту искать цель по id не нужно — целей приказов из ЧУЖОЙ очереди
 * ему всё равно не видно). type — ordinal() значения QueuedOrder.Type,
 * только чтобы клиент мог покрасить точку по-разному для разных видов
 * приказа.
 */
public class QueuedOrderPoint {

    public float x;
    public float y;
    public int type;

    public QueuedOrderPoint() {
    }

    public QueuedOrderPoint(float x, float y, int type) {
        this.x = x;
        this.y = y;
        this.type = type;
    }
}
