package ru.socol.supreme.shared.network.messages;

/**
 * Одна строка списка лобби (LobbyListMessage.lobbies) — ровно то, что нужно
 * нарисовать в браузере лобби (название, сколько занято слотов, можно ли
 * войти), не полное состояние комнаты: то, что происходит ВНУТРИ лобби
 * (кто именно в каком слоте, кто готов, обратный отсчёт), видят только
 * те, кто реально в нём сидит — см. LobbyStateMessage.
 */
public class LobbySummary {

    public int lobbyId;
    public String name;
    public int occupiedSlots;
    public int maxSlots;

    /** ordinal() LobbyPhase — см. её же javadoc. IN_GAME здесь всегда означает occupiedSlots == maxSlots, войти нельзя. */
    public int phase;

    public LobbySummary() {
    }
}
