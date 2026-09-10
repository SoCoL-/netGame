package ru.socol.supreme.shared;

/**
 * Все "магические числа" игры собраны здесь, чтобы менять правила
 * (лимиты, скорость, тикрейт) в одном месте — и на сервере, и на клиенте.
 */
public final class GameConstants {

    private GameConstants() {
    }

    /** Всего 2 игрока в игре. */
    public static final int MAX_PLAYERS = 2;

    /** Суммарный лимит юнитов на всю игру (не на игрока, а на обоих вместе). */
    public static final int MAX_TOTAL_UNITS = 300;

    public static final int TCP_PORT = 54555;

    /** Пока не используется: весь трафик сейчас идёт по TCP (см. GameClient.connect() / GameServer.start()). */
    public static final int UDP_PORT = 54777;

    /** Скорость юнита, единиц мира в секунду. */
    public static final float UNIT_SPEED = 80f;

    /** На каком расстоянии до цели юнит считается прибывшим и останавливается. */
    public static final float ARRIVE_THRESHOLD = 2f;

    /** Максимальный (и стартовый) запас здоровья у любого юнита. */
    public static final int MAX_HEALTH = 20;

    /** Скорострельность — выстрелов в секунду, одинаковая для всех юнитов. */
    public static final float FIRE_RATE = 4f;

    /** Интервал между выстрелами, секунд. Производная от FIRE_RATE. */
    public static final float FIRE_INTERVAL = 1f / FIRE_RATE;

    /** Урон за один выстрел. При 20 HP и 4 выстрела/сек юнит гибнет примерно за 2.5 секунды под непрерывным огнём. */
    public static final int DAMAGE_PER_SHOT = 2;

    /** Дальность атаки воина (первого юнита). У стрелка — см. ARCHER_RANGE_MULTIPLIER/attackRangeFor. */
    public static final float ATTACK_RANGE = 70f;

    /** Во сколько раз дальность атаки стрелка больше, чем у воина. */
    public static final float ARCHER_RANGE_MULTIPLIER = 3f;

    /**
     * Дальность атаки конкретного типа юнита. Единственное, чем стрелок
     * отличается от воина в бою — здоровье, скорострельность, урон,
     * скорость движения и радиус авто-агрессии одинаковы у обоих типов.
     */
    public static float attackRangeFor(UnitType type) {
        return type == UnitType.ARCHER ? ATTACK_RANGE * ARCHER_RANGE_MULTIPLIER : ATTACK_RANGE;
    }

    /**
     * Условный радиус юнита — используется для отрисовки на клиенте, для
     * расчёта радиуса авто-агрессии, а также как реальный радиус
     * столкновений в CollisionSystem (два юнита ближе 2×UNIT_RADIUS друг
     * к другу считаются перекрывающимися и расталкиваются).
     */
    public static final float UNIT_RADIUS = 10f;

    /** Радиус авто-агрессии — во сколько раз больше UNIT_RADIUS (см. AggroSystem). */
    public static final float AGGRO_RADIUS_MULTIPLIER = 3f;

    /** Итоговый радиус авто-агрессии в мировых единицах. */
    public static final float AGGRO_RADIUS = UNIT_RADIUS * AGGRO_RADIUS_MULTIPLIER;

    /**
     * Запас, на который Pathfinding "раздувает" препятствия (воду, здания)
     * при планировании пути — сверх их реального размера. Без этого A*
     * мог выбрать клетку, чей центр формально снаружи препятствия, но
     * ближе UNIT_RADIUS к его краю — CollisionSystem тут же вытолкнула бы
     * юнита обратно, а он (после доворота курса в MovementSystem) тут же
     * пошёл бы туда снова, зависнув в вечном топтании у стены. Запас чуть
     * больше UNIT_RADIUS — на случай, если юнит "прибыл" не точно в центр
     * клетки, а где-то в пределах ARRIVE_THRESHOLD от него.
     */
    public static final float PATH_CLEARANCE = UNIT_RADIUS + ARRIVE_THRESHOLD;

    /** Размер карты по каждой оси. Юниты, здания и камера не выходят за эти границы. */
    public static final float MAP_WIDTH = 2000f;
    public static final float MAP_HEIGHT = 2000f;

    /**
     * Препятствие посередине карты — вода. Блокирует ХОЖДЕНИЕ (юниты
     * обходят её по Pathfinding), но не дальность атаки — стрелять через
     * воду можно, это сознательное упрощение (нет отдельной проверки
     * "линии обзора" для боя, только для движения).
     */
    public static final float WATER_MIN_X = 700f;
    public static final float WATER_MAX_X = 1300f;
    public static final float WATER_MIN_Y = 700f;
    public static final float WATER_MAX_Y = 1300f;

    /** Размер клетки сетки для поиска пути (Pathfinding) — 2000/50 = 40x40 клеток. */
    public static final float PATH_GRID_CELL_SIZE = 50f;

    /**
     * Отступ от края карты, где спавнится дом игрока (в противоположных
     * углах). Намеренно НЕ кратен PATH_GRID_CELL_SIZE, а смещён на
     * половину клетки (200 -> 225) — 200 попадал бы точно на ГРАНИЦУ
     * клетки сетки, а 225 = 4×50 + 25 попадает точно в её ЦЕНТР. Вместе с
     * BUILDING_HALF_SIZE, теперь равным ровно половине клетки, это даёт
     * дом, который чисто накрывает ОДНУ клетку сетки целиком, без
     * дробного пересечения с соседними — то, что и было видно на
     * отладочной сетке как "плавающий" квадрат не по линиям.
     */
    public static final float BUILDING_SPAWN_MARGIN = 225f;

    /**
     * Половина стороны здания (здание — квадрат) — используется и для
     * отрисовки на клиенте, и для блокировки клеток под зданием в
     * Pathfinding, и для выталкивания юнитов из здания в CollisionSystem.
     * Ровно половина PATH_GRID_CELL_SIZE — вместе с BUILDING_SPAWN_MARGIN
     * (см. её javadoc) и ARCHER_BUILDING_OFFSET_DISTANCE (см. её javadoc)
     * это даёт здание, целиком и без остатка занимающее клетки сетки.
     */
    public static final float BUILDING_HALF_SIZE = PATH_GRID_CELL_SIZE / 2f;

    /**
     * Единая формула места спавна дома игрока — чтобы GameServer (создание
     * здания) и Pathfinding (блокировка клеток под ним ещё до того, как
     * оно реально создано) не могли разойтись в этом расчёте.
     */
    public static float buildingSpawnX(int playerId) {
        return playerId == 0 ? BUILDING_SPAWN_MARGIN : MAP_WIDTH - BUILDING_SPAWN_MARGIN;
    }

    public static float buildingSpawnY(int playerId) {
        return playerId == 0 ? BUILDING_SPAWN_MARGIN : MAP_HEIGHT - BUILDING_SPAWN_MARGIN;
    }

    /** Сколько секунд строится один юнит в очереди производства здания. */
    public static final float UNIT_BUILD_TIME = 10f;

    /**
     * На каком расстоянии от центра здания появляется готовый юнит.
     * ProductionSystem ставит эту точку по диагонали в сторону центра
     * карты (см. её computeSpawnPoint) — а значит расстояние должно
     * перекрывать не просто половину здания, а диагональ ДО УГЛА его
     * раздутой (на PATH_CLEARANCE — см. её javadoc) зоны:
     * (BUILDING_HALF_SIZE + PATH_CLEARANCE) * sqrt(2). Множитель 2 — не
     * впритык к этой границе, а с реальным запасом (был баг: юнит рождался
     * практически на самой границе собственной заблокированной зоны, и
     * пасфайндер вёл себя нестабильно с рождения юнита).
     */
    public static final float PRODUCTION_SPAWN_DISTANCE = (BUILDING_HALF_SIZE + PATH_CLEARANCE) * 2f;

    /** Запас здоровья дома (HQ) — на порядок больше, чем у обычного юнита. */
    public static final int BUILDING_MAX_HEALTH = 500;

    /** Запас здоровья казармы стрелков — гораздо более хрупкое здание, чем дом. */
    public static final int ARCHER_BUILDING_MAX_HEALTH = 70;

    /**
     * На каком расстоянии от дома стоит казарма стрелков (в сторону
     * центра карты от дома). Дом и казарма лежат на одной диагонали к
     * центру карты — расстояние специально взято равным 3 диагональным
     * шагам сетки (диагональный шаг между центрами соседних клеток —
     * PATH_GRID_CELL_SIZE * sqrt(2)), чтобы казарма, как и дом, попадала
     * ровно в центр своей клетки и целиком её занимала, а не пересекала
     * несколько клеток дробно. Раньше (250, взятое произвольно) уже
     * решило исходный баг с зазором ~22 (меньше половины клетки — путь
     * у угла шёл зигзагом), но само не было кратно шагу сетки; сейчас и
     * численно похоже (3 шага ≈ 212), и ещё и чисто по сетке.
     */
    public static final float ARCHER_BUILDING_OFFSET_DISTANCE = 3f * PATH_GRID_CELL_SIZE * (float) Math.sqrt(2);

    /**
     * Место казармы стрелков конкретного игрока — смещена от его дома в
     * сторону центра карты на ARCHER_BUILDING_OFFSET_DISTANCE, тем же
     * приёмом, что GameServer/ProductionSystem уже используют для точки
     * появления произведённого юнита. Возвращает {x, y}.
     */
    public static float[] archerBuildingSpawnPoint(int playerId) {
        float hqX = buildingSpawnX(playerId);
        float hqY = buildingSpawnY(playerId);
        float dx = MAP_WIDTH / 2f - hqX;
        float dy = MAP_HEIGHT / 2f - hqY;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        return new float[]{
                hqX + dx / length * ARCHER_BUILDING_OFFSET_DISTANCE,
                hqY + dy / length * ARCHER_BUILDING_OFFSET_DISTANCE
        };
    }

    /** Частота обновления симуляции на сервере. */
    public static final float SERVER_TICK_RATE = 1f / 30f;

    /** Частота рассылки снапшотов мира клиентам. Также используется клиентом как длительность интерполяции. */
    public static final float SNAPSHOT_RATE = 1f / 15f;
}
