package ru.socol.supreme.shared.network.messages;

import java.util.ArrayList;
import java.util.List;

/** Сервер -> все клиенты: полное состояние всех юнитов, рассылается с фиксированной частотой. */
public class WorldSnapshot {

    public List<UnitSnapshot> units = new ArrayList<>();

    public WorldSnapshot() {
    }
}
