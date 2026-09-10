package ru.socol.supreme.shared.network.messages;

/** Сервер -> все клиенты: игра окончена (у одного из игроков уничтожен дом). */
public class GameOverMessage {

    public boolean draw;
    public int winnerPlayerId;

    public GameOverMessage() {
    }
}
