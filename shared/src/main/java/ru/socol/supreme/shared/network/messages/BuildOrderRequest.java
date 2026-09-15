package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "пусть мой строитель builderUnitId строит здание
 * targetBuildingUnitId". Отправляется по правому клику на своё
 * недостроенное здание, когда в выделении есть хотя бы один строитель
 * (см. GameScreen.issueBuildOrder) — по одному запросу на каждого
 * строителя в выделении, тем же приёмом, что и AttackUnitRequest.
 * Финальная проверка — на сервере (GameServer.handleBuildOrder):
 * builderUnitId существует, принадлежит отправителю и правда строитель
 * (UnitType.BUILDER), targetBuildingUnitId существует, тоже принадлежит
 * отправителю и всё ещё недостроено (есть ConstructionComponent).
 */
public class BuildOrderRequest {

    public int builderUnitId;
    public int targetBuildingUnitId;

    public BuildOrderRequest() {
    }
}
