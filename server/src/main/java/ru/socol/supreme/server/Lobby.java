package ru.socol.supreme.server;

import com.esotericsoftware.kryonet.Connection;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.LobbyPhase;

/**
 * Состояние ОДНОЙ комнаты лобби — чистый объект данных, которым владеет и
 * который мутирует исключительно LobbyManager (все методы этого класса
 * вызываются только изнутри уже synchronized(LobbyManager.this) блоков, так
 * что сам Lobby не синхронизируется — см. javadoc LobbyManager).
 *
 * Ровно GameConstants.MAX_PLAYERS слотов (сейчас 2) — слот свободен, когда
 * slotConnection[i] == null. Комната живёт всё время, пока в LobbyManager
 * есть хоть один хендл на неё: после конца матча (activeSession завершился,
 * см. LobbyManager.onSessionEnded) она НЕ удаляется, а возвращается в
 * WAITING с сохранёнными слотами (оба игрока по-прежнему сидят в ней) —
 * именно так реализован реванш ("после проигрыша игроки попадают обратно в
 * лобби" из исходного запроса). Комната удаляется из LobbyManager только
 * когда становится пустой (оба слота освободились).
 */
class Lobby {

    final int id;
    String name;

    /** По одному элементу на слот. null — слот свободен. */
    final Connection[] slotConnection = new Connection[GameConstants.MAX_PLAYERS];

    /** Параллельно slotConnection — имя игрока (JoinRequest.playerName), сохранённое на случай его переподключения/для рассылки. */
    final String[] slotPlayerName = new String[GameConstants.MAX_PLAYERS];

    /** Параллельно slotConnection — бессмысленно для пустого слота. */
    final boolean[] slotReady = new boolean[GameConstants.MAX_PLAYERS];

    LobbyPhase phase = LobbyPhase.WAITING;

    /** Актуально только при phase == STARTING — см. javadoc LobbyManager.tickCountdowns. */
    float countdownRemaining;

    /** Матч этой комнаты, пока phase == IN_GAME — null во всех остальных фазах. */
    GameServer activeSession;

    Lobby(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /** Индекс первого свободного слота, или -1, если оба заняты. */
    int firstFreeSlot() {
        for (int i = 0; i < slotConnection.length; i++) {
            if (slotConnection[i] == null) {
                return i;
            }
        }
        return -1;
    }

    int occupiedSlotCount() {
        int count = 0;
        for (Connection connection : slotConnection) {
            if (connection != null) {
                count++;
            }
        }
        return count;
    }

    boolean isEmpty() {
        return occupiedSlotCount() == 0;
    }

    boolean isFull() {
        return occupiedSlotCount() == slotConnection.length;
    }

    /** Индекс слота, который занимает это соединение, или -1, если оно не сидит в этой комнате. */
    int slotIndexOf(Connection connection) {
        for (int i = 0; i < slotConnection.length; i++) {
            if (slotConnection[i] == connection) {
                return i;
            }
        }
        return -1;
    }

    /** true, только если ВСЕ занятые слоты готовы (и хотя бы один слот занят — пустая комната не "готова к старту"). */
    boolean allOccupiedSlotsReady() {
        boolean anyOccupied = false;
        for (int i = 0; i < slotConnection.length; i++) {
            if (slotConnection[i] != null) {
                anyOccupied = true;
                if (!slotReady[i]) {
                    return false;
                }
            }
        }
        return anyOccupied;
    }

    /**
     * Сбрасывает готовность всех слотов — вызывается и при отмене отсчёта
     * (кто-то снял готовность/вышел до истечения LOBBY_COUNTDOWN_SECONDS),
     * и при возврате из IN_GAME обратно в WAITING после конца матча
     * (реванш начинается с чистой готовности, а не с той, что была перед
     * прошлым стартом — иначе матч запустился бы повторно мгновенно, без
     * возможности кому-то выйти или уступить слот).
     */
    void resetReady() {
        for (int i = 0; i < slotReady.length; i++) {
            slotReady[i] = false;
        }
    }

    /**
     * Освобождает слот этого соединения (если оно вообще сидело в этой
     * комнате) и сбрасывает готовность ОБОИХ слотов — уход одного игрока
     * всегда отменяет уже начатый отсчёт (или просто снимает свою же
     * готовность, если отсчёт ещё не шёл), оставшемуся нужно заново
     * подтвердить готовность, когда слот займёт кто-то новый. Не трогает
     * phase — это решает вызывающий (LobbyManager), т.к. только он знает,
     * можно ли вообще покидать комнату прямо сейчас (IN_GAME — нельзя, см.
     * javadoc LeaveLobbyRequest).
     */
    void removePlayer(Connection connection) {
        int slot = slotIndexOf(connection);
        if (slot == -1) {
            return;
        }
        slotConnection[slot] = null;
        slotPlayerName[slot] = null;
        resetReady();
    }
}
