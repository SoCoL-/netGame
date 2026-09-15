package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "поставь в очередь производства ещё одного юнита у
 * этого здания". unitType — ordinal() значения UnitType: раньше у
 * каждого здания был ровно один производимый тип, теперь дом умеет
 * производить и воина, и строителя, так что клиент обязан явно сказать,
 * какой из них. Сервер (GameServer.handleQueueUnit) проверяет, что
 * здание вообще умеет производить именно этот тип
 * (BuildingDefinitions.producesUnitTypesFor), не доверяя клиенту слепо.
 */
public class QueueUnitRequest {

    public int buildingUnitId;
    public int unitType;

    public QueueUnitRequest() {
    }
}
