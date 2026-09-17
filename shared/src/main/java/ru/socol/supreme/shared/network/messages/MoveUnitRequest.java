package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "перемести мой юнит unitId к точке (targetX,
 * targetY)". queue=false (обычный клик) — заменяет текущий приказ, как и
 * раньше; queue=true (shift-клик, см. GameScreen) — добавляет в конец
 * очереди отложенных приказов юнита (OrderQueueComponent), не трогая то,
 * что юнит делает прямо сейчас.
 */
public class MoveUnitRequest {

    public int unitId;
    public float targetX;
    public float targetY;
    public boolean queue;

    public MoveUnitRequest() {
    }
}
