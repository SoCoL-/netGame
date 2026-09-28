package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "готов начать" (ready=true) / "передумал, ещё не
 * готов" (ready=false) — отправляется из комнаты лобби по кнопке "Готов",
 * актуально только пока сам сидишь в чьём-то слоте. Как только оба слота
 * лобби заняты и оба игрока прислали ready=true — LobbyManager запускает
 * обратный отсчёт (GameConstants.LOBBY_COUNTDOWN_SECONDS); если кто-то из
 * них ready=false посреди отсчёта — отсчёт отменяется (см. её же javadoc
 * в LobbyManager.handleSetReady).
 */
public class SetReadyRequest {

    public boolean ready;

    public SetReadyRequest() {
    }
}
