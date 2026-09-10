package ru.socol.supreme.shared.network.messages;

/** Клиент -> сервер: "пусть мой юнит unitId атакует юнит targetUnitId". */
public class AttackUnitRequest {

    public int unitId;
    public int targetUnitId;

    public AttackUnitRequest() {
    }
}
