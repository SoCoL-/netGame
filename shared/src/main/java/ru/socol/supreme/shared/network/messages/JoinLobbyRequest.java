package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "хочу занять свободный слот в этом лобби" —
 * отправляется из браузера лобби по клику на строку списка
 * (LobbyListMessage.lobbies). Отклоняется (ErrorResponse), если лобби уже
 * не существует или оба слота заняты — списки на клиенте и на сервере
 * могут разойтись на долю секунды из-за сетевой задержки.
 */
public class JoinLobbyRequest {

    public int lobbyId;

    public JoinLobbyRequest() {
    }
}
