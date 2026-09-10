package ru.socol.supreme.shared.network.messages;

/** Клиент -> сервер: "перемести мой юнит unitId к точке (targetX, targetY)". */
public class MoveUnitRequest {

    public int unitId;
    public float targetX;
    public float targetY;

    public MoveUnitRequest() {
    }
}
