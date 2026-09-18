package ru.socol.supreme.shared.network.messages;

import java.util.ArrayList;
import java.util.List;

/** Сервер -> все клиенты: полное состояние всех юнитов, рассылается с фиксированной частотой. */
public class WorldSnapshot {

    public List<UnitSnapshot> units = new ArrayList<>();

    /** Ресурсы обоих игроков — как и юниты, видны всем одинаково (тумана войны для ресурсов нет, только для карты — см. fog ниже). */
    public List<PlayerResources> playerResources = new ArrayList<>();

    /** Туман войны обоих игроков — см. javadoc FogSnapshot, почему шлются оба, а не только свой. */
    public List<FogSnapshot> fog = new ArrayList<>();

    public WorldSnapshot() {
    }
}
