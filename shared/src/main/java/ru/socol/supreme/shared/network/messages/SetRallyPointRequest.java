package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "установи точку сбора у этого здания". Отправляется,
 * когда игрок кликает правой кнопкой по карте, пока у него выделено
 * СВОЁ здание, производящее юнитов — см. GameScreen.touchDown.
 * Финальная проверка — на сервере (GameServer.handleSetRallyPoint):
 * здание существует, принадлежит отправителю и производит юнитов (есть
 * ProductionComponent), иначе запрос молча игнорируется.
 */
public class SetRallyPointRequest {

    public int buildingUnitId;
    public float x;
    public float y;

    public SetRallyPointRequest() {
    }
}
