package ru.socol.supreme.shared.network.messages;

/** Воронка в WorldSnapshot — для отрисовки и ПКМ-засыпки на клиенте (см. Crater). */
public class CraterSnapshot {

    public int id;
    public float x;
    public float y;
    public float radius;

    /** Сколько жизни осталось, 1 — только что появилась, 0 — вот-вот зарастёт. Клиент по нему бледнит воронку. */
    public float life;

    /** Доля засыпки строителями, 0..1. */
    public float fill;

    public CraterSnapshot() {
    }
}
