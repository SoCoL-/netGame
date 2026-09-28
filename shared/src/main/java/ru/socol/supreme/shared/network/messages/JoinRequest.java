package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: первое сообщение сразу после коннекта — "вот моё имя,
 * покажи мне список лобби". playerName — то, что увидят другие игроки в
 * списке слотов комнаты лобби (LobbyStateMessage.slotPlayerName), а не
 * какой-то логин/аккаунт — сервер не проверяет его на уникальность и не
 * запоминает между подключениями (GameClient.connect() берёт значение по
 * умолчанию из имени пользователя ОС, см. её же javadoc). Раньше это
 * сообщение сразу же давало подключению глобальный playerId на всю игру
 * (JoinResponse.playerId) — теперь playerId назначается заново перед
 * КАЖДЫМ матчем, отдельным GameStartedMessage, когда лобби реально
 * стартует (см. её же javadoc, почему).
 */
public class JoinRequest {

    public String playerName = "Player";

    public JoinRequest() {
        // требуется Kryo для десериализации
    }
}
