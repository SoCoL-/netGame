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

    /** Актуально только для здания с production (building=true, queuedCount/buildProgress осмысленны). Точка сбора для новых юнитов — см. ProductionComponent.hasRallyPoint. */
    public boolean hasRallyPoint;
    public float rallyX;
    public float rallyY;

    /**
     * Актуально только для юнита-строителя (building=false, unitType ==
     * BUILDER.ordinal()) — unitId здания, которое он СЕЙЧАС реально
     * строит (не просто идёт к нему — см. BuildOrderComponent.inRange).
     * 0, если строитель бездействует или ещё в пути. Нужен клиенту
     * только для голографического луча стройки (GameScreen
     * .drawBuildBeams) — координаты самого здания клиент уже знает из
     * его собственного UnitSnapshot, тут только id, чтобы их связать.
     */
    public int buildTargetUnitId;

    /** Актуально только для юнита (building=false) — его собственный тип (воин/стрелок), ordinal() значения UnitType. */
    public int unitType;

    /**
     * Актуально только для здания (building=true) — какое именно, ordinal()
     * значения BuildingType (дом/казарма/шахта/электростанция). Что оно
     * производит/добывает, клиент сам смотрит в BuildingDefinitions по
     * этому полю — отдельно это не шлётся.
     */
    public int buildingType;

    /** Актуально только для здания (building=true). Пока true — здание ещё не работает (не производит юнитов/не добывает ресурс), просто строится. */
    public boolean underConstruction;

    /** Актуально только для здания (building=true) при underConstruction=true. Доля постройки, 0..1, для прогресс-бара на клиенте. */
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
