package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "ухожу из своего текущего лобби обратно в общий
 * список" — само лобби серверу называть не нужно, у него и так есть
 * подключение -> текущее лобби (LobbyManager). Игнорируется, если матч в
 * этом лобби уже идёт (LobbyPhase.IN_GAME) — во время матча выйти из
 * комнаты лобби нельзя, только отключиться совсем (см. javadoc
 * LobbyManager.handleDisconnect).
 */
public class LeaveLobbyRequest {

    public LeaveLobbyRequest() {
    }
}
