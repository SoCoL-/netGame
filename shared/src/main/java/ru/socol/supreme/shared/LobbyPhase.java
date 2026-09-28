package ru.socol.supreme.shared;

/**
 * Фаза одной комнаты лобби (сервер: Lobby; клиент: LobbyRoomScreen) — по
 * сети всегда пересылается как ordinal() (LobbySummary.phase,
 * LobbyStateMessage.phase), тем же приёмом, что и BuildingType/UnitType в
 * UnitSnapshot: сетевые DTO хранят голый int, а не enum, см. их же javadoc.
 */
public enum LobbyPhase {
    /** Ждём: пока не заняты оба слота, или заняты, но не оба готовы. Можно свободно занять пустой слот. */
    WAITING,
    /** Оба слота заняты и оба готовы — идёт обратный отсчёт (countdownRemaining) перед стартом матча. */
    STARTING,
    /** Матч идёт (GameServer-сессия уже создана) — оба слота заняты игроками матча, войти нельзя. */
    IN_GAME
}
