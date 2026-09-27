package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "пусть мой строитель builderUnitId ремонтирует
 * здание targetBuildingUnitId". Отправляется по правому клику на своё
 * ПОВРЕЖДЁННОЕ, но уже достроенное здание (currentHealth < maxHealth,
 * ConstructionComponent уже снят — иначе это была бы обычная стройка,
 * см. BuildOrderRequest), когда в выделении есть хотя бы один строитель
 * (см. GameScreen.issueRepairOrder) — по одному запросу на каждого
 * строителя в выделении, тем же приёмом, что и BuildOrderRequest.
 * Финальная проверка — на сервере (GameServer.handleRepairOrder /
 * assignBuilderToRepair): builderUnitId существует, принадлежит
 * отправителю и правда строитель, targetBuildingUnitId существует, тоже
 * принадлежит отправителю, уже достроено и действительно повреждено.
 *
 * queue — см. её же смысл в BuildOrderRequest.queue: shift-клик
 * добавляет приказ в очередь строителя, не прерывая то, что он делает
 * сейчас.
 */
public class RepairOrderRequest {

    public int builderUnitId;
    public int targetBuildingUnitId;
    public boolean queue;

    public RepairOrderRequest() {
    }
}
