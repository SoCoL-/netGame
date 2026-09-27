package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "пусть мой строитель builderUnitId собирает железо с
 * обломков targetWreckUnitId". Отправляется по правому клику на обломки
 * погибшего юнита (WreckComponent), когда в выделении есть хотя бы один
 * строитель (см. GameScreen.issueCollectOrder) — по одному запросу на
 * каждого строителя в выделении, тем же приёмом, что и BuildOrderRequest/
 * RepairOrderRequest. Финальная проверка — на сервере
 * (GameServer.handleCollectOrder/assignBuilderToCollect): builderUnitId
 * существует, принадлежит отправителю и правда строитель,
 * targetWreckUnitId существует и это действительно обломки, а не
 * настоящее здание или юнит (у обломков нет владельца — "чужое/своё" тут
 * не проверяется, собрать может строитель любого игрока).
 *
 * queue — см. её же смысл в BuildOrderRequest.queue: shift-клик добавляет
 * приказ в очередь строителя, не прерывая то, что он делает сейчас.
 */
public class CollectOrderRequest {

    public int builderUnitId;
    public int targetWreckUnitId;
    public boolean queue;

    public CollectOrderRequest() {
    }
}
