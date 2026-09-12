package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "построй здание добычи железа на месторождении с этим
 * индексом" (индекс в GameConstants.IRON_DEPOSITS). Шлётся только когда
 * клиент сам считает точку подходящей (курсор был "прилипшим" к
 * месторождению в момент клика, см. GameScreen) — но финальное решение
 * всегда за сервером (GameServer.handlePlaceIronMine): индекс в границах
 * и месторождение ещё не занято другим зданием добычи.
 */
public class PlaceIronMineRequest {

    public int depositIndex;

    public PlaceIronMineRequest() {
    }
}
