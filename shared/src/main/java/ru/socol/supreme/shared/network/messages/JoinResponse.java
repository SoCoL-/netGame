package ru.socol.supreme.shared.network.messages;

/**
 * Сервер -> клиент: результат JoinRequest. accepted=false, если уже
 * подключено GameConstants.MAX_PLAYERS игроков.
 */
public class JoinResponse {

    public boolean accepted;
    public int playerId;
    public String message;

    public JoinResponse() {
    }
}
