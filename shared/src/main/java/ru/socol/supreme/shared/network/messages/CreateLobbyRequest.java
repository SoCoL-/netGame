package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: "хочу создать новое лобби с таким названием" —
 * отправляется из браузера лобби. Создатель автоматически занимает слот 0
 * в новом лобби (LobbyManager.handleCreateLobby), отдельного
 * JoinLobbyRequest следом слать не нужно.
 */
public class CreateLobbyRequest {

    public String lobbyName = "";

    public CreateLobbyRequest() {
    }
}
