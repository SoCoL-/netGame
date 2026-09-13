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
     * ordinal() значения UnitType для юнита (его собственный тип —
     * воин/стрелок) или производимого юнита для дома/казармы (то же
     * самое поле переиспользуется, а не заводится отдельное). Для здания
     * добычи ресурса (resourceBuilding=true) это поле не используется —
     * у него вместо этого resourceType.
     */
    public int unitType;

    /**
     * Здание добычи ресурса (шахта железа или электростанция) — игрок
     * ставит их сам (клавиша B открывает меню выбора), а не сервер
     * автоматически. true и пока строится, и когда уже работает —
     * underConstruction отличает эти две стадии.
     */
    public boolean resourceBuilding;

    /** Актуально только при resourceBuilding=true. ordinal() значения ResourceType — какое именно это здание (шахта или станция). */
    public int resourceType;

    /** Актуально только при resourceBuilding=true. Пока true — здание ещё не добывает, просто строится. */
    public boolean underConstruction;

    /** Актуально только при resourceBuilding=true и underConstruction=true. Доля постройки, 0..1, для прогресс-бара на клиенте. */
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
