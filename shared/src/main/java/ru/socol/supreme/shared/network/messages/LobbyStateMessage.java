package ru.socol.supreme.shared.network.messages;

/**
 * Сервер -> обоим игрокам, которые сейчас сидят ВНУТРИ конкретной комнаты
 * лобби (не в общем списке) — полное состояние этой комнаты, пересылается
 * заново целиком при любом изменении (кто-то занял/освободил слот,
 * (не)готов, тикнул обратный отсчёт, матч только что закончился и лобби
 * вернулось в ожидание — см. javadoc LobbyManager.onSessionEnded).
 *
 * yourSlotIndex — какой из GameConstants.MAX_PLAYERS слотов принадлежит
 * ПОЛУЧАТЕЛЮ именно этой копии сообщения: LobbyManager рассылает его
 * персонально каждому из двух занявших слот игроков, и это единственное
 * поле, которое у них отличается — так клиент отличает "мой слот и моя
 * кнопка Готов" от слота соперника, не сравнивая имена/id вручную.
 */
public class LobbyStateMessage {

    public int lobbyId;
    public String name;

    /** По одному элементу на слот (GameConstants.MAX_PLAYERS). null — слот свободен. */
    public String[] slotPlayerName = new String[0];

    /** По одному элементу на слот, параллельно slotPlayerName. Бессмысленно для пустого слота. */
    public boolean[] slotReady = new boolean[0];

    /** Индекс слота получателя. -1 не должно происходить в норме (получатель этого сообщения всегда сидит в одном из слотов). */
    public int yourSlotIndex = -1;

    /** ordinal() LobbyPhase. */
    public int phase;

    /** Актуально только при phase == STARTING — сколько секунд осталось до старта, для тикающего таймера на клиенте (LobbyRoomScreen). */
    public float countdownRemaining;

    public LobbyStateMessage() {
    }
}
