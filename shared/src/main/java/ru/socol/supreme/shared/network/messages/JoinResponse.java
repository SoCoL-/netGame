package ru.socol.supreme.shared.network.messages;

/**
 * Сервер -> клиент: результат JoinRequest — просто подтверждение "ты
 * подключился, лови список лобби" (LobbyManager сразу следом сам
 * присылает LobbyListMessage, отдельно запрашивать не нужно). accepted
 * сейчас всегда true — в отличие от старой версии, тут больше нет
 * общего на весь сервер лимита в GameConstants.MAX_PLAYERS игроков
 * (лимит остался только на ОДНО лобби — ровно MAX_PLAYERS слотов в нём,
 * см. Lobby); поле оставлено на будущее (например, бан по имени) и
 * поэтому по-прежнему проверяется клиентом (GameScreen/LobbyBrowserScreen
 * показывают message, если вдруг false).
 *
 * playerId тут больше не задаётся (был глобальным на всё подключение) —
 * см. javadoc GameStartedMessage.yourPlayerId, который занял его место.
 */
public class JoinResponse {

    public boolean accepted;
    public String message;

    public JoinResponse() {
    }
}
