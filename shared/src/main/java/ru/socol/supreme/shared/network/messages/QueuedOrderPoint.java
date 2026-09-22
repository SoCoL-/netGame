package ru.socol.supreme.shared.network.messages;

/**
 * Одна точка приказа юнита — список таких точек в UnitSnapshot.queuedOrders
 * нужен только для отрисовки цепочки очереди на клиенте (GameScreen
 * .drawOrderQueue), сама очередь на исход игры уже повлияла тем, что она
 * есть (OrderQueueSystem её и без клиента разбирает) — это чисто
 * визуальная информация для игрока. ПЕРВАЯ точка в списке — не первый
 * ОТЛОЖЕННЫЙ приказ, а ТЕКУЩИЙ выполняемый (см. GameServer
 * .currentOrderPointFor) — дальше уже идут настоящие отложенные, по
 * порядку из OrderQueueComponent.queue.
 *
 * (x, y) — куда идёт этот конкретный приказ: для MOVE — прямо точка
 * назначения; для ATTACK/BUILD/REPAIR/COLLECT — позиция цели НА МОМЕНТ
 * снапшота (сервер сам разрешает id цели в координаты в
 * GameServer.broadcastSnapshot/currentOrderPointFor, клиенту искать цель
 * по id не нужно — целей приказов из ЧУЖОЙ очереди ему всё равно не
 * видно). type — ordinal() значения QueuedOrder.Type, чтобы клиент мог
 * покрасить и точку, и ведущий к ней сегмент линии по-разному для разных
 * видов приказа (GameScreen.orderQueueColorFor).
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
