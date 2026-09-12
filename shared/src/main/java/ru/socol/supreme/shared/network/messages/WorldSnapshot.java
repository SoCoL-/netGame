package ru.socol.supreme.shared.network.messages;

import java.util.ArrayList;
import java.util.List;

/** Сервер -> все клиенты: полное состояние всех юнитов, рассылается с фиксированной частотой. */
public class WorldSnapshot {

    public List<UnitSnapshot> units = new ArrayList<>();

    /**
     * Ресурсы обоих игроков — как и юниты, видны всем одинаково (в игре
     * нет и не планируется "тумана войны" для ресурсов отдельно от
     * остального — см. README про упрощения без fog of war).
     */
    public List<PlayerResources> playerResources = new ArrayList<>();

    public WorldSnapshot() {
    }
}
