package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "построй электростанцию в этой точке". В отличие от
 * PlaceIronMineRequest (индекс месторождения — шахта может встать только
 * в одну из фиксированных точек), электростанция не привязана ни к чему
 * на карте, поэтому шлём координаты напрямую. Финальная проверка — на
 * сервере (GameServer.handlePlacePowerPlant через
 * BuildingPlacement.canPlacePowerPlant), клиент лишь заранее не даёт
 * подтвердить явно невалидную точку (см. GameScreen).
 */
public class PlacePowerPlantRequest {

    public float x;
    public float y;

    public PlacePowerPlantRequest() {
    }
}
