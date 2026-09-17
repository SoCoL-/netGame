package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "пусть мой юнит unitId атакует юнит targetUnitId".
 * queue — см. её же смысл в MoveUnitRequest.queue.
 */
public class AttackUnitRequest {

    public int unitId;
    public int targetUnitId;
    public boolean queue;

    public AttackUnitRequest() {
    }
}
