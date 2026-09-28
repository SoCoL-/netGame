package ru.socol.supreme.shared.network.messages;

/**
 * Сервер -> обоим игрокам лобби: обратный отсчёт дошёл до нуля,
 * GameServer-сессия матча реально создана и уже тикает — клиент по
 * получении переключается с комнаты лобби на игровой экран.
 * yourPlayerId — playerId получателя НА ЭТОТ МАТЧ (0 или 1, совпадает с
 * индексом его слота в лобби — см. javadoc LobbyManager), раньше эту роль
 * играл JoinResponse.playerId, назначаемый один раз на всё подключение;
 * теперь у одного и того же подключения за сессию может быть сыграно
 * много матчей подряд (реванш в том же лобби), и playerId сообщается
 * заново перед КАЖДЫМ из них.
 */
public class GameStartedMessage {

    public int yourPlayerId;

    public GameStartedMessage() {
    }
}
