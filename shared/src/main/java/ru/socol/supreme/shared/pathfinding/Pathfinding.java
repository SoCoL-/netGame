package ru.socol.supreme.shared.pathfinding;

import com.badlogic.ashley.core.Entity;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.components.AircraftComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PathComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Поиск пути по сетке в обход препятствий: прямоугольник воды посередине
 * карты (GameConstants.WATER_*, никогда не меняется) и footprint каждого
 * СЕЙЧАС существующего здания — динамический список, а не что-то,
 * зафиксированное раз и навсегда. GameServer вызывает
 * addBuildingObstacle при появлении любого здания (в том числе ещё
 * строящегося — CollisionSystem и так не пускает юнитов сквозь него
 * реактивно в любом состоянии, тут для консистентности так же) и
 * removeBuildingObstacle, когда здание пропадает из игры (гибель в бою,
 * снос, отключение игрока) — так A* реально огибает ЛЮБОЕ здание на
 * карте, не только дом, как было раньше.
 *
 * Работает только на сервере — как и MovementSystem/CombatSystem, живёт в
 * shared, но реально используется только GameServer.handleMoveUnit и
 * CombatSystem при погоне. Клиент ничего об этом не знает: он просто видит
 * результат — позицию юнита в очередном снапшоте, которая естественным
 * образом обходит препятствия, потому что сервер туда юнита никогда не ведёт.
 * add/removeBuildingObstacle клиент тоже никогда не вызывает — у него свой
 * собственный, всегда пустой список зданий-препятствий (статические поля
 * этого класса не расшарены между клиентом и сервером, это разные
 * процессы), так что отладочная сетка (isWaterCell) остаётся какой и
 * была — только про воду, никак не про здания.
 *
 * Дешёвый случай (прямая видимость до цели свободна) вообще не запускает
 * A* — вызывающий код как и раньше просто идёт по прямой. Сетка 160x160
 * (при клетке 50 и карте 8000x8000) и поиск запускается только когда
 * прямая линия реально пересекает препятствие, так что даже наивный A* без
 * оптимизаций тут более чем достаточно быстрый.
 *
 * Про потокобезопасность: BLOCKED, activeBuildings и buildingsByCell —
 * мутабельное статическое состояние без собственной синхронизации внутри
 * этого класса, потому что она тут не нужна — единственный вызывающий,
 * GameServer, уже всё делает под одним и тем же synchronized-монитором
 * (сетевые обработчики и основной игровой цикл), так же, как и с
 * unitsById/resourcesByPlayer.
 */
public final class Pathfinding {

    private Pathfinding() {
    }

    private static final int GRID_WIDTH = (int) Math.ceil(GameConstants.MAP_WIDTH / GameConstants.PATH_GRID_CELL_SIZE);
    private static final int GRID_HEIGHT = (int) Math.ceil(GameConstants.MAP_HEIGHT / GameConstants.PATH_GRID_CELL_SIZE);

    /** unitId здания -> его footprint (уже раздутый на PATH_CLEARANCE) — нужен, чтобы знать, какие клетки пересчитать при removeBuildingObstacle. */
    private static final Map<Integer, Footprint> activeBuildings = new HashMap<>();

    /**
     * Пространственный индекс тех же footprint'ов по клеткам сетки —
     * используется ТОЛЬКО isInsideAnyBuilding, чтобы точечная проверка не
     * перебирала вообще все здания на карте (activeBuildings.values()),
     * а только те, что накрывают ровно ту клетку, где лежит запрошенная
     * точка. Без этого индекса hasLineOfSight (шаг луча — половина клетки,
     * см. её javadoc) на каждом шаге заново сканировала бы ВСЕ здания —
     * а её саму CombatSystem дёргает КАЖДЫЙ тик на каждого атакующего в
     * погоне, так что стоимость одной точечной проверки умножается срезу
     * на два счётчика, растущих по ходу партии: число шагов луча и число
     * построенных зданий.
     *
     * Тот же приём, что и SpatialHashGrid.insertRect (см. её javadoc):
     * один footprint регистрируется во ВСЕХ клетках, которые он
     * перекрывает, а не в одной — но, в отличие от SpatialHashGrid, здесь
     * не круговой запрос по радиусу, а всегда ровно одна точная клетка
     * (cellX/cellY самой точки), так что обычная Map по ключу клетки, без
     * отдельного класса, вполне достаточна.
     */
    private static final Map<Long, List<Footprint>> buildingsByCell = new HashMap<>();

    /**
     * Кэш препятствий по клеткам для A* (см. findPath) — ТОЛЬКО footprint
     * каждого здания из activeBuildings (вода вынесена в отдельную,
     * неизменную WATER_BLOCKED, см. её javadoc, почему), но и это не
     * обновляется целиком при каждом изменении: addBuildingObstacle/
     * removeBuildingObstacle пересчитывают только клетки, которые
     * затрагивает конкретное здание, остальная сетка не трогается.
     * Для точечных запросов (hasLineOfSight, начальная проверка цели)
     * используется не эта сетка, а isBlocked — она считает по точным
     * координатам, не огрубляя до клетки.
     */
    private static final boolean[][] BLOCKED = new boolean[GRID_WIDTH][GRID_HEIGHT];

    /**
     * Клетки воды — считается РОВНО ОДИН РАЗ при старте и никогда не
     * пересчитывается (в отличие от BLOCKED выше): в отличие от зданий,
     * прямоугольник воды (GameConstants.WATER_*) фиксирован на всю партию.
     * Отдельная от BLOCKED сетка нужна ровно затем, чтобы её можно было
     * проигнорировать целиком для юнита, умеющего плавать под водой
     * (UnitDefinitions.canEnterWater — сейчас только строитель), не трогая
     * при этом обычную непроходимость зданий, которая касается вообще всех
     * без исключения.
     */
    private static final boolean[][] WATER_BLOCKED = buildWaterOnlyGrid();

    private static boolean[][] buildWaterOnlyGrid() {
        boolean[][] blocked = new boolean[GRID_WIDTH][GRID_HEIGHT];
        for (int cx = 0; cx < GRID_WIDTH; cx++) {
            for (int cy = 0; cy < GRID_HEIGHT; cy++) {
                blocked[cx][cy] = isInsideWater(cellCenterX(cx), cellCenterY(cy));
            }
        }
        return blocked;
    }

    /** BLOCKED[cx][cy] (здания) с учётом воды только для тех, кому она вообще препятствие — общий приём и для A* (findPath), и для проверки "срезания" угла. */
    private static boolean isCellBlocked(int cx, int cy, boolean canEnterWater) {
        return BLOCKED[cx][cy] || (!canEnterWater && WATER_BLOCKED[cx][cy]);
    }

    /**
     * Регистрирует здание как препятствие — GameServer вызывает сразу при
     * создании сущности здания, любого типа и в любом состоянии (в том
     * числе ещё строящегося). unitId — чтобы потом можно было снять
     * именно это здание через removeBuildingObstacle, не трогая остальные
     * (несколько зданий рядом не должны мешать друг другу при сносе
     * одного из них — см. её javadoc).
     */
    public static void addBuildingObstacle(int unitId, float centerX, float centerY, BuildingType type) {
        Footprint footprint = new Footprint(centerX, centerY, type);
        activeBuildings.put(unitId, footprint);
        recomputeCellsFor(footprint);
        indexFootprint(footprint);
    }

    /**
     * Снимает здание с препятствий — GameServer вызывает, когда здание
     * пропадает из игры (гибель в бою, добровольный снос, отключение
     * игрока — везде, где вызывается engine.removeEntity для сущности с
     * BuildingComponent). Молча ничего не делает, если unitId не был
     * зарегистрирован (защита на случай двойного вызова — не должно
     * происходить, но и не страшно, если произойдёт).
     */
    public static void removeBuildingObstacle(int unitId) {
        Footprint footprint = activeBuildings.remove(unitId);
        if (footprint != null) {
            recomputeCellsFor(footprint);
            deindexFootprint(footprint);
        }
    }

    /**
     * Пересчитывает клетки, накрытые этим footprint — не просто
     * "поставить true"/"поставить false" напрямую: другое (СОСЕДНЕЕ, ещё
     * живое) здание могло частично накрывать те же самые клетки, снос
     * одного не должен по ошибке открыть проход через границу с другим.
     * Вода сюда не входит — она в отдельной, неизменной WATER_BLOCKED (см.
     * её javadoc), пересчитывать нечего. Общая логика для добавления и
     * снятия — после put/remove в activeBuildings единственная разница уже
     * выражена тем, что сейчас лежит в карте, отдельно её дублировать не
     * нужно.
     */
    private static void recomputeCellsFor(Footprint footprint) {
        int minX = cellX(footprint.centerX - footprint.halfWidth);
        int maxX = cellX(footprint.centerX + footprint.halfWidth);
        int minY = cellY(footprint.centerY - footprint.halfHeight);
        int maxY = cellY(footprint.centerY + footprint.halfHeight);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                BLOCKED[cx][cy] = isInsideAnyBuilding(cellCenterX(cx), cellCenterY(cy));
            }
        }
    }

    /** Прописывает footprint во все клетки buildingsByCell, которые он перекрывает — см. её javadoc. Тот же диапазон клеток, что и recomputeCellsFor, но для другой сетки. */
    private static void indexFootprint(Footprint footprint) {
        int minX = cellX(footprint.centerX - footprint.halfWidth);
        int maxX = cellX(footprint.centerX + footprint.halfWidth);
        int minY = cellY(footprint.centerY - footprint.halfHeight);
        int maxY = cellY(footprint.centerY + footprint.halfHeight);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                buildingsByCell.computeIfAbsent(key(cx, cy), unused -> new ArrayList<>()).add(footprint);
            }
        }
    }

    /** Обратная операция indexFootprint — убирает footprint из тех же клеток при сносе здания, чистя опустевшие bucket'ы, чтобы buildingsByCell не пух бесконечно за долгую партию. */
    private static void deindexFootprint(Footprint footprint) {
        int minX = cellX(footprint.centerX - footprint.halfWidth);
        int maxX = cellX(footprint.centerX + footprint.halfWidth);
        int minY = cellY(footprint.centerY - footprint.halfHeight);
        int maxY = cellY(footprint.centerY + footprint.halfHeight);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                long cellKey = key(cx, cy);
                List<Footprint> bucket = buildingsByCell.get(cellKey);
                if (bucket != null) {
                    bucket.remove(footprint);
                    if (bucket.isEmpty()) {
                        buildingsByCell.remove(cellKey);
                    }
                }
            }
        }
    }

    /**
     * Footprint любого сейчас живого здания, а для тех, кто не умеет
     * плавать под водой (canEnterWater == false — то есть почти все, см.
     * UnitDefinitions.canEnterWater), ещё и прямоугольник воды — то, что
     * такому юниту нельзя ни пройти, ни в чём заспавниться. Считает по
     * ТОЧНЫМ координатам (x, y), не по клетке — в отличие от сеток
     * BLOCKED/WATER_BLOCKED, которые используются только внутри A*. Раздут
     * на PATH_CLEARANCE сверх реального размера — см. её javadoc, почему
     * без этого юниты зависали, топчась у самого края препятствия.
     */
    public static boolean isBlocked(float x, float y, boolean canEnterWater) {
        return (!canEnterWater && isInsideWater(x, y)) || isInsideAnyBuilding(x, y);
    }

    /**
     * Вода ли клетка (cellX, cellY) — публичный запрос для клиента, чтобы
     * отладочная сетка (GameScreen) красила клетки в точности так же, как
     * их видит сам A* (та же PATH_CLEARANCE-инфляция, тот же расчёт центра
     * клетки), а не приблизительно повторяла логику независимо. Намеренно
     * только про воду, не про здания — у клиента список зданий-препятствий
     * всегда пуст (см. javadoc класса), так что для них это было бы
     * бессмысленно. Не зависит от canEnterWater — рисуем воду на карте как
     * воду для всех, кто на неё смотрит, а не только для тех, кому она
     * препятствие.
     */
    public static boolean isWaterCell(int cellX, int cellY) {
        return isInsideWater(cellCenterX(cellX), cellCenterY(cellY));
    }

    /**
     * Прямоугольник воды (с той же PATH_CLEARANCE-инфляцией, что и
     * остальные проверки препятствий в этом классе) — публичный, помимо
     * внутреннего использования в A*/isBlocked, ещё и для боевых правил:
     * CombatSystem/AggroSystem/GameServer.startAttackOrder проверяют им,
     * что цель атаки СЕЙЧАС не спряталась под водой (см. javadoc
     * UnitDefinitions.canTarget, почему это отдельная, третья проверка
     * помимо разделения на "воздух/земля").
     */
    public static boolean isInsideWater(float x, float y) {
        float margin = GameConstants.PATH_CLEARANCE;
        return x >= GameConstants.WATER_MIN_X - margin && x <= GameConstants.WATER_MAX_X + margin
                && y >= GameConstants.WATER_MIN_Y - margin && y <= GameConstants.WATER_MAX_Y + margin;
    }

    /**
     * Проверяет только здания, зарегистрированные в buildingsByCell для
     * клетки, накрывающей саму точку (x, y) — не все здания на карте (см.
     * javadoc buildingsByCell, почему это важно: эту функцию гоняет
     * hasLineOfSight на каждом шаге луча, а её — CombatSystem каждый тик
     * на каждого атакующего в погоне). Само сравнение с точными границами
     * footprint внутри не изменилось — координаты по-прежнему точные, не
     * огрубляются до клетки, только КАНДИДАТЫ на проверку теперь берутся
     * из индекса, а не перебором всех activeBuildings.
     */
    private static boolean isInsideAnyBuilding(float x, float y) {
        List<Footprint> bucket = buildingsByCell.get(key(cellX(x), cellY(y)));
        if (bucket == null) {
            return false;
        }
        for (Footprint footprint : bucket) {
            if (x >= footprint.centerX - footprint.halfWidth && x <= footprint.centerX + footprint.halfWidth
                    && y >= footprint.centerY - footprint.halfHeight && y <= footprint.centerY + footprint.halfHeight) {
                return true;
            }
        }
        return false;
    }

    /** Раздутый (на PATH_CLEARANCE) прямоугольник одного здания — считается один раз при регистрации, не на каждый запрос. */
    private static final class Footprint {
        final float centerX;
        final float centerY;
        final float halfWidth;
        final float halfHeight;

        Footprint(float centerX, float centerY, BuildingType type) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.halfWidth = BuildingDefinitions.halfWidthFor(type) + GameConstants.PATH_CLEARANCE;
            this.halfHeight = BuildingDefinitions.halfHeightFor(type) + GameConstants.PATH_CLEARANCE;
        }
    }

    /**
     * Выставляет юниту цель движения к (destX, destY): если препятствий на
     * прямой линии нет — обычная прямая, без A*, как и раньше; если есть —
     * считает путь по сетке в обход и ведёт юнита по нему точка за точкой
     * (MovementSystem сама подхватывает следующую точку при достижении
     * текущей). Если юнит уже идёт обходным путём к той же клетке цели —
     * путь не пересчитывается.
     *
     * Авиация (AircraftComponent) — особый случай: весь наземный обход
     * препятствий (вода, здания) её не касается вовсе, она летает поверх
     * всего этого. Для неё просто выставляется direction.target/moving,
     * а фактическим полётом — с ограниченной скоростью разворота, не
     * мгновенным довортом — занимается AircraftMovementSystem, а не эта
     * функция.
     */
    public static void setDestination(Entity entity, PositionComponent position, DirectionComponent direction,
                                       float destX, float destY) {
        if (entity.getComponent(AircraftComponent.class) != null) {
            AircraftComponent aircraft = entity.getComponent(AircraftComponent.class);
            aircraft.loitering = false; // новая цель — прекращаем кружение, летим к ней
            direction.target.set(destX, destY);
            direction.moving = true;
            return;
        }

        // Умеет ли ИМЕННО ЭТА сущность плавать под водой (сейчас — только
        // строитель, см. UnitDefinitions.canEnterWater) — вычисляется здесь,
        // один раз на вызов, а не в каждом из вызывающих setDestination
        // мест (их около десятка — GameServer, CombatSystem, BuildSystem и
        // т.д.): так сигнатура setDestination не меняется вовсе, и ни один
        // из этих вызовов не нужно трогать.
        UnitTypeComponent unitTypeComponent = entity.getComponent(UnitTypeComponent.class);
        boolean canEnterWater = unitTypeComponent != null && UnitDefinitions.canEnterWater(unitTypeComponent.type);

        if (isBlocked(destX, destY, canEnterWater)) {
            return; // нельзя дойти ДО воды/здания — цель недостижима, приказ просто игнорируем
        }

        if (hasLineOfSight(position.position.x, position.position.y, destX, destY, canEnterWater)) {
            entity.remove(PathComponent.class); // если раньше шли обходным путём — он больше не нужен
            direction.target.set(destX, destY);
            direction.direction.set(direction.target).sub(position.position).nor();
            direction.moving = true;
            return;
        }

        int destCellX = cellX(destX);
        int destCellY = cellY(destY);

        PathComponent path = entity.getComponent(PathComponent.class);
        if (path != null && path.destinationCellX == destCellX && path.destinationCellY == destCellY
                && !path.waypoints.isEmpty()) {
            // Путь пересчитывать не нужно, но direction.moving обязательно
            // выставляем явно, а не полагаемся, что он и так true. Юнит
            // мог быть остановлен ДО того, как этот путь был пройден до
            // конца — например, BuildSystem ставит moving=false, как
            // только строитель входит в радиус стройки, а это может
            // случиться раньше, чем MovementSystem дойдёт до последней
            // точки обходного пути (buildRange обычно больше
            // ARRIVE_THRESHOLD). Если после этого выдать тот же приказ
            // на стройку заново (hasApproachPoint сбрасывается в false —
            // см. GameServer.assignBuilderToBuild), новая точка подхода
            // нередко попадает в ту же самую клетку сетки, что и уже
            // пройденная часть старого пути: тогда без этой строчки юнит
            // навсегда зависал бы с валидным, но не используемым путём —
            // видимая на экране линия есть, а движения нет, — пока
            // случайный приказ с ДРУГОЙ целевой клеткой не проходил бы
            // мимо этого раннего return и не включал moving заново.
            direction.moving = true;
            return;
        }

        List<Vector2> waypoints = findPath(position.position.x, position.position.y, destX, destY, canEnterWater);
        if (waypoints.isEmpty()) {
            direction.moving = false; // со всех сторон окружено препятствиями — идти некуда
            return;
        }

        if (path == null) {
            // new, а не engine.createComponent — у Pathfinding нет ссылки на
            // Engine (статический метод, вызывается и из CombatSystem, и из
            // GameServer). Это по-прежнему безопасно теперь, когда
            // PathComponent реализует Pool.Poolable: PooledEngine возвращает
            // в пул и сбрасывает (resет()) ЛЮБОЙ компонент поддерживаемого
            // типа при его удалении с сущности, независимо от того, был он
            // создан через createComponent или обычным new — просто не
            // получает возможной экономии на переиспользовании из пула при
            // СОЗДАНИИ, что для редко создаваемого PathComponent несущественно.
            path = new PathComponent();
            entity.add(path);
        }
        path.waypoints.clear();
        path.waypoints.addAll(waypoints);
        path.destinationCellX = destCellX;
        path.destinationCellY = destCellY;

        Vector2 firstWaypoint = path.waypoints.remove(0);
        direction.target.set(firstWaypoint);
        direction.direction.set(direction.target).sub(position.position).nor();
        direction.moving = true;
    }

    /** Есть ли прямая видимость от (fromX,fromY) до (toX,toY) — то есть отрезок не пересекает препятствие (canEnterWater — см. isBlocked). */
    private static boolean hasLineOfSight(float fromX, float fromY, float toX, float toY, boolean canEnterWater) {
        float distance = Vector2.dst(fromX, fromY, toX, toY);
        int steps = Math.max(1, (int) (distance / (GameConstants.PATH_GRID_CELL_SIZE / 2f)));
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            if (isBlocked(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t, canEnterWater)) {
                return false;
            }
        }
        return true;
    }

    private static int cellX(float worldX) {
        return clampCell((int) (worldX / GameConstants.PATH_GRID_CELL_SIZE), GRID_WIDTH);
    }

    private static int cellY(float worldY) {
        return clampCell((int) (worldY / GameConstants.PATH_GRID_CELL_SIZE), GRID_HEIGHT);
    }

    private static int clampCell(int cell, int size) {
        return Math.max(0, Math.min(size - 1, cell));
    }

    private static float cellCenterX(int cx) {
        return (cx + 0.5f) * GameConstants.PATH_GRID_CELL_SIZE;
    }

    private static float cellCenterY(int cy) {
        return (cy + 0.5f) * GameConstants.PATH_GRID_CELL_SIZE;
    }

    // ---- A* по сетке, 8 направлений, без "срезания" углов между двумя занятыми клетками ----

    private static List<Vector2> findPath(float fromX, float fromY, float toX, float toY, boolean canEnterWater) {
        int startX = cellX(fromX);
        int startY = cellY(fromY);
        int goalX = cellX(toX);
        int goalY = cellY(toY);

        if (startX == goalX && startY == goalY) {
            List<Vector2> single = new ArrayList<>();
            single.add(new Vector2(toX, toY));
            return single;
        }

        Node start = new Node(startX, startY);
        start.g = 0;
        start.h = heuristic(startX, startY, goalX, goalY);
        start.f = start.h;

        Map<Long, Node> allNodes = new HashMap<>();
        allNodes.put(key(startX, startY), start);

        PriorityQueue<Node> open = new PriorityQueue<>();
        open.add(start);

        Set<Long> closed = new HashSet<>();

        while (!open.isEmpty()) {
            Node current = open.poll();
            long currentKey = key(current.x, current.y);
            if (closed.contains(currentKey)) {
                continue; // устаревшая копия из очереди (см. "ленивое удаление" ниже) — пропускаем
            }
            closed.add(currentKey);

            if (current.x == goalX && current.y == goalY) {
                return reconstructPath(current, toX, toY);
            }

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }

                    int nx = current.x + dx;
                    int ny = current.y + dy;
                    if (nx < 0 || ny < 0 || nx >= GRID_WIDTH || ny >= GRID_HEIGHT) {
                        continue;
                    }
                    // Старт всегда проходим, даже если формально "в воде" (защита на случай,
                    // если юнит уже как-то оказался у самой границы препятствия).
                    boolean isStart = nx == startX && ny == startY;
                    if (!isStart && isCellBlocked(nx, ny, canEnterWater)) {
                        continue;
                    }

                    boolean diagonal = dx != 0 && dy != 0;
                    if (diagonal && (isCellBlocked(current.x + dx, current.y, canEnterWater)
                            || isCellBlocked(current.x, current.y + dy, canEnterWater))) {
                        continue; // не даём "срезать" угол между двумя занятыми клетками
                    }

                    long neighborKey = key(nx, ny);
                    if (closed.contains(neighborKey)) {
                        continue;
                    }

                    float moveCost = diagonal ? 1.41421356f : 1f;
                    float tentativeG = current.g + moveCost;

                    Node neighbor = allNodes.get(neighborKey);
                    if (neighbor == null) {
                        neighbor = new Node(nx, ny);
                        allNodes.put(neighborKey, neighbor);
                    } else if (tentativeG >= neighbor.g) {
                        continue; // старый путь до этой клетки был не хуже
                    }

                    neighbor.parent = current;
                    neighbor.g = tentativeG;
                    neighbor.h = heuristic(nx, ny, goalX, goalY);
                    neighbor.f = neighbor.g + neighbor.h;
                    // Дубликат в куче при повторном добавлении не страшен — устаревшую
                    // версию отсеет проверка closed.contains(...) при извлечении.
                    open.add(neighbor);
                }
            }
        }

        return new ArrayList<>(); // пути нет — цель окружена препятствиями со всех сторон
    }

    private static List<Vector2> reconstructPath(Node goalNode, float exactDestX, float exactDestY) {
        List<Vector2> path = new ArrayList<>();
        Node node = goalNode;
        while (node != null) {
            path.add(0, new Vector2(cellCenterX(node.x), cellCenterY(node.y)));
            node = node.parent;
        }
        // Последнюю точку сетки заменяем на точный пункт назначения — иначе
        // юнит останавливался бы в центре клетки, а не там, куда кликнули.
        if (!path.isEmpty()) {
            path.set(path.size() - 1, new Vector2(exactDestX, exactDestY));
        }
        return path;
    }

    private static float heuristic(int x, int y, int goalX, int goalY) {
        float dx = x - goalX;
        float dy = y - goalY;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    private static final class Node implements Comparable<Node> {
        final int x;
        final int y;
        float g;
        float h;
        float f;
        Node parent;

        Node(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public int compareTo(Node other) {
            return Float.compare(this.f, other.f);
        }
    }
}
