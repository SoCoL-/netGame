package ru.socol.supreme.shared.network.messages;

import java.util.ArrayList;
import java.util.List;

/** Состояние одного юнита внутри WorldSnapshot. */
public class UnitSnapshot {

    public int unitId;
    public int ownerId;
    public float x;
    public float y;
    public float dirX;
    public float dirY;
    public boolean moving;
    public int health;
    public int maxHealth;
    public boolean building;

    /** Актуально только для зданий (building=true) — состояние очереди производства. */
    public int queuedCount;
    public float buildProgress;

    /**
     * ordinal() значения UnitType. Для юнита — его собственный тип
     * (воин/стрелок). Для здания — тип юнита, который оно производит
     * (то же самое поле переиспользуется, а не заводится отдельное).
     */
    public int unitType;

    /**
     * Точки оставшегося маршрута (текущая цель direction.target + все
     * оставшиеся waypoints из PathComponent, если юнит обходит препятствие)
     * — только для отладочной отрисовки на клиенте. Пусто, если юнит
     * не движется.
     */
    public List<PathPoint> pathPoints = new ArrayList<>();

    public UnitSnapshot() {
    }
}
