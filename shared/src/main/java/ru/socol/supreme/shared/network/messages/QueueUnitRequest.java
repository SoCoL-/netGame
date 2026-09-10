package ru.socol.supreme.shared.network.messages;

/** Клиент -> сервер: "поставь в очередь производства ещё одного юнита у этого здания". */
public class QueueUnitRequest {

    public int buildingUnitId;

    public QueueUnitRequest() {
    }
}
