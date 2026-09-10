package ru.socol.supreme.shared.network.messages;

/** Клиент -> сервер: "хочу присоединиться к игре". Отправляется сразу после коннекта. */
public class JoinRequest {

    public String playerName = "Player";

    public JoinRequest() {
        // требуется Kryo для десериализации
    }
}
