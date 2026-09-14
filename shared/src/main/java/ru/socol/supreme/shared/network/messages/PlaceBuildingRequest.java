package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "построй здание этого типа в этой точке". Для любого
 * свободно размещаемого здания (сейчас — казарма стрелков, электростанция;
 * НЕ для шахты железа — она привязана к месторождению и идёт отдельным
 * PlaceIronMineRequest с индексом, а не координатами; и НЕ для дома — его
 * строит только сервер автоматически при входе игрока). Финальная
 * проверка — на сервере (GameServer.handlePlaceBuilding через
 * BuildingPlacement.canPlaceBuilding), клиент лишь заранее не даёт
 * подтвердить явно невалидную точку.
 */
public class PlaceBuildingRequest {

    /** ordinal() значения BuildingType. */
    public int buildingType;
    public float x;
    public float y;

    public PlaceBuildingRequest() {
    }
}
