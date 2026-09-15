package ru.socol.supreme.shared.pathfinding;

import com.badlogic.ashley.core.Entity;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PathComponent;
import ru.socol.supreme.shared.components.PositionComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Динамический поиск пути с поддержкой зданий, строящихся в рантайме.
 *
 * Отличие от Pathfinding:
 * - Сетка препятствий теперь ДИНАМИЧЕСКАЯ: содержит не только воду/дома,
 *   но и казармы/шахты/электростанции, появляющиеся в рантайме.
 * - При постройке здания → updateBuildingOcclusion(building, +)
 * - При разрушении здания → updateBuildingOcclusion(building, -)
 * - Путь кешируется на юните (PathComponent.cachedDestinationX/Y), чтобы
 *   не пересчитывать его каждый тик при одной и той же цели.
 * - Перепланировка запускается только когда юнит "упирается" в препятствие
 *   (MovementSystem обнаруживает застревание, вызывает recalculatePath).
 *
 * Производительность для 100+ юнитов:
 * - Постройка здания: O(1) обновление сетки
 * - A* запрос: O(n log n) где n = количество клеток в радиусе цели, обычно < 200
 * - Кеширование пути: избегаем повторных вычислений, пока цель одна
 * - Перепланировка по требованию: только если юнит реально застрял
 */
public final class DynamicPathfinding {

    private DynamicPathfinding() {
    }

    private static final int GRID_WIDTH = (int) Math.ceil(GameConstants.MAP_WIDTH / GameConstants.PATH_GRID_CELL_SIZE);
    private static final int GRID_HEIGHT = (int) Math.ceil(GameConstants.MAP_HEIGHT / GameConstants.PATH_GRID_CELL_SIZE);

    /**
     * Статические препятствия: вода и дома игроков (они не двигаются).
     * Строится один раз при загрузке класса.
     */
    private static final boolean[][] STATIC_BLOCKED = buildStaticBlockedGrid();

    /**
     * Динамические препятствия: казармы, шахты, электростанции.
     * Обновляется при постройке/разрушении зданий.
     * Хранит Entity здания для быстрого доступа.
     */
    private static final Entity[][] DYNAMIC_BUILDINGS = new Entity[GRID_WIDTH][GRID_HEIGHT];

    private static boolean[][] buildStaticBlockedGrid() {
        boolean[][] blocked = new boolean[GRID_WIDTH][GRID_HEIGHT];
        for (int cx = 0; cx < GRID_WIDTH; cx++) {
            for (int cy = 0; cy < GRID_HEIGHT; cy++) {
                blocked[cx][cy] = isStaticBlocked(cellCenterX(cx), cellCenterY(cy));
            }
        }
        return blocked;
    }

    /**
     * Проверка статических препятствий (вода и дома).
     * Вызывается один раз при инициализации STATIC_BLOCKED.
     */
    private static boolean isStaticBlocked(float x, float y) {
        return isInsideWater(x, y) || isInsideAnyHome(x, y);
    }

    /**
     * Проверка, заблокирована ли точка (x, y).
     * Объединяет статические и динамические препятствия.
     */
    public static boolean isBlocked(float x, float y) {
        if (isStaticBlocked(x, y)) {
            return true;
        }
        // Проверяем динамические здания: казарма, шахта, электростанция
        int cx = cellX(x);
        int cy = cellY(y);
        if (cx >= 0 && cx < GRID_WIDTH && cy >= 0 && cy < GRID_HEIGHT) {
            Entity building = DYNAMIC_BUILDINGS[cx][cy];
            if (building != null) {
                // Проверяем, что это действительно здание (может быть удалено)
                PositionComponent pos = building.getComponent(PositionComponent.class);
                if (pos != null) {
                    BuildingComponent buildingComp = building.getComponent(BuildingComponent.class);
                    if (buildingComp != null) {
                        // Перепроверяем footprint на случай граничных ошибок
                        if (isInsideBuildingFootprint(x, y, pos.position.x, pos.position.y, buildingComp.type)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Добавить здание в динамическую сетку препятствий.
     * Вызывается из GameServer.spawnBuilding после создания здания,
     * но ТОЛЬКО для казарм/шахт/электростанций.
     * Дом уже в статических препятствиях.
     */
    public static void registerDynamicBuilding(Entity building, PositionComponent position, BuildingComponent buildingType) {
        // Пропускаем дом — он уже в статических препятствиях
        if (buildingType.type == BuildingType.HOME) {
            return;
        }

        float x = position.position.x;
        float y = position.position.y;
        float halfWidth = BuildingDefinitions.halfWidthFor(buildingType.type) + GameConstants.PATH_CLEARANCE;
        float halfHeight = BuildingDefinitions.halfHeightFor(buildingType.type) + GameConstants.PATH_CLEARANCE;

        int minCX = cellX(x - halfWidth);
        int maxCX = cellX(x + halfWidth);
        int minCY = cellY(y - halfHeight);
        int maxCY = cellY(y + halfHeight);

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cy = minCY; cy <= maxCY; cy++) {
                if (cx >= 0 && cx < GRID_WIDTH && cy >= 0 && cy < GRID_HEIGHT) {
                    DYNAMIC_BUILDINGS[cx][cy] = building;
                }
            }
        }
    }

    /**
     * Удалить здание из динамической сетки препятствий.
     * Вызывается из CombatSystem при уничтожении здания.
     */
    public static void unregisterDynamicBuilding(Entity building, PositionComponent position, BuildingComponent buildingType) {
        // Пропускаем дом — он в статических препятствиях, не трогаем
        if (buildingType.type == BuildingType.HOME) {
            return;
        }

        float x = position.position.x;
        float y = position.position.y;
        float halfWidth = BuildingDefinitions.halfWidthFor(buildingType.type) + GameConstants.PATH_CLEARANCE;
        float halfHeight = BuildingDefinitions.halfHeightFor(buildingType.type) + GameConstants.PATH_CLEARANCE;

        int minCX = cellX(x - halfWidth);
        int maxCX = cellX(x + halfWidth);
        int minCY = cellY(y - halfHeight);
        int maxCY = cellY(y + halfHeight);

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cy = minCY; cy <= maxCY; cy++) {
                if (cx >= 0 && cx < GRID_WIDTH && cy >= 0 && cy < GRID_HEIGHT) {
                    // Удаляем только если это именно наше здание (на случай коллизии адресов)
                    if (DYNAMIC_BUILDINGS[cx][cy] == building) {
                        DYNAMIC_BUILDINGS[cx][cy] = null;
                    }
                }
            }
        }
    }

    /**
     * Основной метод: выставить юниту цель движения.
     * Использует логику из Pathfinding, но с динамической сеткой.
     *
     * Логика:
     * 1. Если цель заблокирована — игнорируем
     * 2. Если прямая видимость есть → идём напрямую
     * 3. Если нет → запускаем A* (но используем кеш из PathComponent)
     * 4. Если уже кешировано до той же цели — ничего не делаем
     */
    public static void setDestination(Entity entity, PositionComponent position, DirectionComponent direction,
                                       float destX, float destY) {
        if (isBlocked(destX, destY)) {
            return; // цель недостижима, игнорируем приказ
        }

        if (hasLineOfSight(position.position.x, position.position.y, destX, destY)) {
            entity.remove(PathComponent.class);
            direction.target.set(destX, destY);
            direction.direction.set(direction.target).sub(position.position).nor();
            direction.moving = true;
            return;
        }

        int destCellX = cellX(destX);
        int destCellY = cellY(destY);

        PathComponent path = entity.getComponent(PathComponent.class);
        // Кеш: если уже идём к той же цели и путь есть — не пересчитываем
        if (path != null && path.destinationCellX == destCellX && path.destinationCellY == destCellY
                && !path.waypoints.isEmpty()) {
            return;
        }

        // Запускаем A* по динамической сетке
        List<Vector2> waypoints = findPath(position.position.x, position.position.y, destX, destY);
        if (waypoints.isEmpty()) {
            direction.moving = false;
            return;
        }

        if (path == null) {
            path = new PathComponent();
            entity.add(path);
        }
        path.waypoints.clear();
        path.waypoints.addAll(waypoints);
        path.destinationCellX = destCellX;
        path.destinationCellY = destCellY;
        // НОВОЕ: кешируем точный пункт назначения для быстрой перепланировки
        path.cachedDestinationX = destX;
        path.cachedDestinationY = destY;

        Vector2 firstWaypoint = path.waypoints.remove(0);
        direction.target.set(firstWaypoint);
        direction.direction.set(direction.target).sub(position.position).nor();
        direction.moving = true;
    }

    /**
     * НОВОЕ: Перепланировка пути, когда юнит упирается в препятствие.
     * Вызывается из MovementSystem, когда юнит застревает (много тиков подряд не двигается).
     *
     * Это позволяет юниту адаптироваться, если:
     * - Новое здание появилось на его пути
     * - Другой юнит заблокировал проход (CollisionSystem потом его разойдёт)
     * - Путь был кеширован, но обстановка изменилась
     */
    public static void recalculatePath(Entity entity, PositionComponent position, DirectionComponent direction) {
        PathComponent path = entity.getComponent(PathComponent.class);
        if (path == null) {
            return; // нет кешированного пути
        }

        // Пересчитываем в ту же цель
        float destX = path.cachedDestinationX;
        float destY = path.cachedDestinationY;

        if (isBlocked(destX, destY)) {
            return; // цель стала недостижима
        }

        // Силовой пересчёт: игнорируем кеш
        List<Vector2> waypoints = findPath(position.position.x, position.position.y, destX, destY);
        if (waypoints.isEmpty()) {
            direction.moving = false;
            return;
        }

        path.waypoints.clear();
        path.waypoints.addAll(waypoints);

        Vector2 firstWaypoint = path.waypoints.remove(0);
        direction.target.set(firstWaypoint);
        direction.direction.set(direction.target).sub(position.position).nor();
        direction.moving = true;
    }

    /**
     * Есть ли прямая видимость: не пересекает ли отрезок препятствие.
     * Использует объединённую проверку (статика + динамика).
     */
    private static boolean hasLineOfSight(float fromX, float fromY, float toX, float toY) {
        float distance = Vector2.dst(fromX, fromY, toX, toY);
        int steps = Math.max(1, (int) (distance / (GameConstants.PATH_GRID_CELL_SIZE / 2f)));
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            if (isBlocked(fromX + (toX - fromX) * t, fromY + (toY - fromY) * t)) {
                return false;
            }
        }
        return true;
    }

    // ---- Вспомогательные методы (как в Pathfinding) ----

    private static boolean isInsideWater(float x, float y) {
        float margin = GameConstants.PATH_CLEARANCE;
        return x >= GameConstants.WATER_MIN_X - margin && x <= GameConstants.WATER_MAX_X + margin
                && y >= GameConstants.WATER_MIN_Y - margin && y <= GameConstants.WATER_MAX_Y + margin;
    }

    private static boolean isInsideAnyHome(float x, float y) {
        for (int playerId = 0; playerId < GameConstants.MAX_PLAYERS; playerId++) {
            float[] home = BuildingDefinitions.homeSpawnPoint(playerId);
            if (isInsideBuildingFootprint(x, y, home[0], home[1], BuildingType.HOME)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideBuildingFootprint(float x, float y, float centerX, float centerY, BuildingType type) {
        float halfWidth = BuildingDefinitions.halfWidthFor(type) + GameConstants.PATH_CLEARANCE;
        float halfHeight = BuildingDefinitions.halfHeightFor(type) + GameConstants.PATH_CLEARANCE;
        return x >= centerX - halfWidth && x <= centerX + halfWidth
                && y >= centerY - halfHeight && y <= centerY + halfHeight;
    }

    /**
     * Клиент (GameScreen) запрашивает для отладочной сетки: вода ли эта клетка?
     * Используется как в Pathfinding.
     */
    public static boolean isWaterCell(int cellX, int cellY) {
        return isInsideWater(cellCenterX(cellX), cellCenterY(cellY));
    }

    // ---- A* алгоритм (идентичен Pathfinding) ----

    private static List<Vector2> findPath(float fromX, float fromY, float toX, float toY) {
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
                continue;
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

                    boolean isStart = nx == startX && ny == startY;
                    // ИЗМЕНЕНИЕ: используем STATIC_BLOCKED + проверяем DYNAMIC_BUILDINGS
                    boolean staticBlocked = STATIC_BLOCKED[nx][ny];
                    boolean dynamicBlocked = DYNAMIC_BUILDINGS[nx][ny] != null;
                    if (!isStart && (staticBlocked || dynamicBlocked)) {
                        continue;
                    }

                    boolean diagonal = dx != 0 && dy != 0;
                    if (diagonal) {
                        boolean hBlocked = STATIC_BLOCKED[current.x + dx][current.y] 
                                || DYNAMIC_BUILDINGS[current.x + dx][current.y] != null;
                        boolean vBlocked = STATIC_BLOCKED[current.x][current.y + dy]
                                || DYNAMIC_BUILDINGS[current.x][current.y + dy] != null;
                        if (hBlocked || vBlocked) {
                            continue;
                        }
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
                        continue;
                    }

                    neighbor.parent = current;
                    neighbor.g = tentativeG;
                    neighbor.h = heuristic(nx, ny, goalX, goalY);
                    neighbor.f = neighbor.g + neighbor.h;
                    open.add(neighbor);
                }
            }
        }

        return new ArrayList<>();
    }

    private static List<Vector2> reconstructPath(Node goalNode, float exactDestX, float exactDestY) {
        List<Vector2> path = new ArrayList<>();
        Node node = goalNode;
        while (node != null) {
            path.add(0, new Vector2(cellCenterX(node.x), cellCenterY(node.y)));
            node = node.parent;
        }
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
