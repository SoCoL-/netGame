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

    /**
     * Актуально только для наземного юнита (building=false, есть
     * TurretComponent на сервере) — направление, куда сейчас наведена
     * его башня (единичный вектор, то же соглашение, что и у dirX/dirY
     * для корпуса). (0, 0) у зданий и авиации — там башни нет вовсе (см.
     * javadoc TurretComponent), клиент туда и не смотрит для них.
     * Клиент рисует по этому вектору треугольник башни отдельно от
     * прямоугольного корпуса (RenderSystem) и по нему же смещает точку
     * вылета визуального снаряда (GameScreen.onProjectileFired).
     */
    public float turretDirX;
    public float turretDirY;
    public int health;
    public int maxHealth;
    public boolean building;

    /** Актуально только для зданий (building=true) — состояние очереди производства. */
    public int queuedCount;
    public float buildProgress;

    /**
     * Актуально только когда queuedCount > 0 — ordinal() типа юнита,
     * который сейчас строится первым в очереди (production.queue.get(0)
     * на сервере). Нужен клиенту, чтобы верно посчитать долю
     * прогресс-бара (GameScreen.drawBuildingInfoPanel) — у каждого типа
     * юнита теперь своё UnitDefinitions.buildTimeFor, не общая константа
     * на всех, так что без этого поля клиент не знал бы, на что делить.
     */
    public int producingUnitType;

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
     * Актуально только для обломков (building=true, buildingType ==
     * WRECK.ordinal()) — лежат ли они под водой (см. javadoc
     * WreckComponent.underwater), только для отрисовки другим оттенком.
     * Сколько железа осталось собрать — НЕ отдельное поле тут, а те же
     * health/maxHealth, что и у любой сущности (см. её же javadoc, зачем
     * это сознательное переиспользование).
     */
    public boolean wreckUnderwater;

    /**
     * Точки оставшегося маршрута (текущая цель direction.target + все
     * оставшиеся waypoints из PathComponent, если юнит обходит препятствие)
     * — только для отладочной отрисовки на клиенте. Пусто, если юнит
     * не движется.
     */
    public List<PathPoint> pathPoints = new ArrayList<>();

    /** Отложенные приказы юнита (если есть) — только для отрисовки цепочки очереди (GameScreen.drawOrderQueue), см. javadoc QueuedOrderPoint. Пусто, если очередь пуста или её нет вовсе. */
    public List<QueuedOrderPoint> queuedOrders = new ArrayList<>();

    /**
     * Весь замкнутый маршрут патрулирования (PatrolComponent.waypoints),
     * если юнит патрулирует — только для отрисовки на клиенте
     * (GameScreen.drawPatrolRoute), см. javadoc PatrolPoint. В отличие от
     * queuedOrders тут нет разделения на "текущую" и "будущие" точки —
     * весь список равноправен и никогда не тратится, юнит идёт по кругу
     * бесконечно. Пусто, если юнит сейчас не патрулирует.
     */
    public List<PatrolPoint> patrolPoints = new ArrayList<>();

    public UnitSnapshot() {
    }
}
