package ru.socol.supreme.shared;

/**
 * Все "магические числа" игры, общие для всех юнитов и не зависящие от их
 * типа, собраны здесь (лимиты, тикрейт, размеры карты и зданий) — и на
 * сервере, и на клиенте. Скорость, урон и дальность атаки — то, чем
 * реально отличаются типы юнитов друг от друга — вынесены в units.json
 * (см. UnitDefinitions), не сюда.
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

    /**
     * Размер буферов KryoNet под сериализацию сообщений. Server()/Client()
     * без аргументов используют крошечные буферы по умолчанию (16384 байт
     * на запись и всего 2048 — на один объект). Этого хватало, пока
     * WorldSnapshot был маленьким, но с ростом числа юнитов и особенно
     * после того как в UnitSnapshot добавился pathPoints (для отладочной
     * отрисовки маршрута — см. GameScreen) один снапшот на несколько
     * десятков юнитов уже не помещается в 2048 байт: сериализация падает с
     * переполнением буфера (баг: воспроизводился на ~40 юнитах). На
     * MAX_TOTAL_UNITS с юнитами, огибающими препятствия по всей сетке,
     * оценка худшего случая — десятки килобайт; 1 МБ даёт большой запас.
     */
    public static final int NETWORK_WRITE_BUFFER_SIZE = 1024 * 1024;
    public static final int NETWORK_OBJECT_BUFFER_SIZE = 1024 * 1024;

    /** На каком расстоянии до цели юнит считается прибывшим и останавливается. */
    public static final float ARRIVE_THRESHOLD = 2f;

    /**
     * Условный радиус юнита — используется для отрисовки на клиенте и как
     * реальный радиус столкновений в CollisionSystem (два юнита ближе
     * 2×UNIT_RADIUS друг к другу считаются перекрывающимися и
     * расталкиваются). Одинаков у всех типов — в отличие от здоровья,
     * скорости, скорострельности, урона и дальности атаки/агрессии,
     * которые теперь заданы по типу в units.json (см. UnitDefinitions):
     * этот, в отличие от них, не про боевые характеристики, а про то,
     * какого юнит физически размера, и её вынос в данные пока не давал
     * бы ничего, кроме лишнего слоя.
     */
    public static final float UNIT_RADIUS = 10f;

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
     * углах). Кратен PATH_GRID_CELL_SIZE (200 = 4×50) — дом теперь 2x2
     * клетки (чётное число), и для чистого совпадения с сеткой без
     * дробных пересечений центр здания с ЧЁТНЫМ числом клеток должен
     * стоять на ГРАНИЦЕ клетки, а не в её центре (это наоборот тому, что
     * нужно нечётным по ширине зданиям — см. buildingHalfWidthFor и
     * archerBuildingSpawnPoint, у стрелковой казармы своя логика именно
     * из-за этой чётности/нечётности).
     */
    public static final float BUILDING_SPAWN_MARGIN = 200f;

    /**
     * Половина ширины и половина высоты здания — раздельно по осям и по
     * типу (задаётся тем, что здание ПРОИЗВОДИТ — см. ProductionComponent
     * .producesUnitType, других "типов здания" в игре нет). Используется
     * для отрисовки на клиенте, блокировки клеток под зданием в
     * Pathfinding и выталкивания юнитов из здания в CollisionSystem —
     * везде расчёт идёт через эти две функции, а не через один общий
     * размер, потому что дом (2x2 клетки) и казарма стрелков (1x2)
     * — разной формы, не только разного размера.
     */
    public static float buildingHalfWidthFor(UnitType producesType) {
        return producesType == UnitType.ARCHER ? PATH_GRID_CELL_SIZE / 2f : PATH_GRID_CELL_SIZE;
    }

    /** Высота сейчас одна и та же (2 клетки) у обоих типов здания — но метод, а не константа, на случай если это изменится. */
    public static float buildingHalfHeightFor(UnitType producesType) {
        return PATH_GRID_CELL_SIZE;
    }

    /**
     * Безопасный (не занижающий) радиус взаимодействия юнит-здание —
     * диагональ до угла САМОГО крупного здания среди всех типов, плюс
     * запас на радиус юнита. Единая формула для двух разных мест, которые
     * обязаны совпадать по порядку величины: размер ячейки
     * SpatialHashGrid при её создании (GameServer) и радиус запроса к ней
     * же в CollisionSystem. Считать раздельно в двух местах было бы
     * рискованно разъехаться при следующем изменении размера здания.
     */
    public static float maxBuildingInteractionRadius() {
        float maxHalfDimension = 0f;
        for (UnitType type : UnitType.values()) {
            maxHalfDimension = Math.max(maxHalfDimension, buildingHalfWidthFor(type));
            maxHalfDimension = Math.max(maxHalfDimension, buildingHalfHeightFor(type));
        }
        return (float) Math.sqrt(2) * maxHalfDimension + UNIT_RADIUS;
    }

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
     * На каком расстоянии от центра здания появляется готовый юнит —
     * зависит от размера конкретного здания (дом крупнее казармы). Берём
     * диагональ ДО УГЛА раздутой (на PATH_CLEARANCE — см. её javadoc) зоны
     * этого здания — самую длинную возможную "дистанцию выхода" из его
     * прямоугольника в любом направлении — и умножаем на 1.5 для запаса,
     * а не впритык к границе (был баг: юнит рождался практически на
     * самой границе собственной заблокированной зоны, и пасфайндер вёл
     * себя нестабильно с рождения юнита).
     */
    public static float productionSpawnDistanceFor(UnitType producesType) {
        float inflatedHalfWidth = buildingHalfWidthFor(producesType) + PATH_CLEARANCE;
        float inflatedHalfHeight = buildingHalfHeightFor(producesType) + PATH_CLEARANCE;
        float cornerDistance = (float) Math.sqrt(inflatedHalfWidth * inflatedHalfWidth + inflatedHalfHeight * inflatedHalfHeight);
        return cornerDistance * 1.5f;
    }

    /** Запас здоровья дома (HQ) — на порядок больше, чем у обычного юнита. */
    public static final int BUILDING_MAX_HEALTH = 500;

    /** Запас здоровья казармы стрелков — гораздо более хрупкое здание, чем дом. */
    public static final int ARCHER_BUILDING_MAX_HEALTH = 70;

    /**
     * На каком расстоянии от дома стоит казарма стрелков — чисто по оси X
     * (в сторону центра карты по горизонтали, Y совпадает с домом), а не
     * по диагонали, как было раньше: дом теперь 2x2 клетки (центр на
     * ГРАНИЦЕ клетки), а казарма 1x2 (центр должен быть в ЦЕНТРЕ клетки
     * по ширине, но на ГРАНИЦЕ по высоте, раз высота тоже чётная — 2
     * клетки) — смешивать два разных условия выравнивания по одной
     * диагонали было бы куда сложнее, чем развести здания по одной оси,
     * где Y совпадает с домом (уже верно выровнен) и только X даёт запас
     * в 175 = кратно клетке (150) плюс половина клетки (25) для
     * казарменного центра. Даёт зазор между раздутыми (на PATH_CLEARANCE)
     * зонами домов и казармы ~76 юнитов — больше клетки сетки (50),
     * пасфайндеру есть где пройти без зигзага.
     */
    public static final float ARCHER_BUILDING_OFFSET_DISTANCE = 175f;

    /**
     * Место казармы стрелков конкретного игрока — смещена от его дома по
     * оси X в сторону центра карты на ARCHER_BUILDING_OFFSET_DISTANCE, Y
     * совпадает с домом (см. javadoc ARCHER_BUILDING_OFFSET_DISTANCE, почему
     * не по диагонали, как было раньше). Возвращает {x, y}.
     */
    public static float[] archerBuildingSpawnPoint(int playerId) {
        float hqX = buildingSpawnX(playerId);
        float hqY = buildingSpawnY(playerId);
        float direction = playerId == 0 ? 1f : -1f; // в сторону центра карты по X
        return new float[]{hqX + direction * ARCHER_BUILDING_OFFSET_DISTANCE, hqY};
    }

    /** Частота обновления симуляции на сервере. */
    public static final float SERVER_TICK_RATE = 1f / 30f;

    /** Частота рассылки снапшотов мира клиентам. Также используется клиентом как длительность интерполяции. */
    public static final float SNAPSHOT_RATE = 1f / 15f;
}
