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
     * У здания добычи железа (ironMine=true) не актуально — оно юнитов
     * не производит вообще.
     */
    public int unitType;

    /**
     * Здание добычи железа — единственное здание, которое ставит игрок
     * сам (клавиша B), а не сервер автоматически. true и пока строится,
     * и когда уже работает — underConstruction отличает эти две стадии.
     */
    public boolean ironMine;

    /** Актуально только при ironMine=true. Пока true — здание ещё не добывает, просто строится. */
    public boolean underConstruction;

    /** Актуально только при ironMine=true и underConstruction=true. Доля постройки, 0..1, для прогресс-бара на клиенте. */
    public float constructionProgress;

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
