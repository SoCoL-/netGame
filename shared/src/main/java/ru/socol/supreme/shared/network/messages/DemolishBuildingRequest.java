package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "снеси моё здание". Отправляется по кнопке
 * "Demolish" в панели выделенного здания (см. GameScreen
 * .drawBuildingPanel) — для любого своего здания, не только
 * производящего. Финальная проверка — на сервере
 * (GameServer.handleDemolishBuilding): здание существует и принадлежит
 * отправителю. Снос идёт тем же путём, каким сервер убирает юнита/здание,
 * погибшее в бою (engine.removeEntity + unitsById.remove) — если снесли
 * свой же HQ, обычная проверка условия победы (checkGameOver) сама
 * заметит его пропажу и засчитает поражение, отдельной обработки для
 * этого случая не требуется.
 */
public class DemolishBuildingRequest {

    public int buildingUnitId;

    public DemolishBuildingRequest() {
    }
}
