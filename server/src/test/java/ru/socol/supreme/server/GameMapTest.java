package ru.socol.supreme.server;

import org.junit.jupiter.api.Test;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.map.GameMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Карта из assets/maps/default.json (серверные тесты запускаются из
 * assets/, см. server/build.gradle) — проверяет, что в ней нет явных
 * ошибок разметки: старты и месторождения на суше, до месторождений можно
 * дойти.
 */
class GameMapTest {

    private final GameMap map = GameMap.current();

    @Test
    void spawnsAndDepositsAreOnLandInsideTheMap() {
        for (int player = 0; player < GameConstants.MAX_PLAYERS; player++) {
            float[] spawn = map.spawnPoint(player);
            assertInsideMap(spawn);
            assertFalse(map.isWaterAt(spawn[0], spawn[1]), "старт игрока " + player + " на суше");
        }
        assertTrue(map.ironDeposits().length > 0, "есть месторождения");
        for (float[] deposit : map.ironDeposits()) {
            assertInsideMap(deposit);
            assertFalse(map.isWaterAt(deposit[0], deposit[1]), "месторождение (" + deposit[0] + ", " + deposit[1] + ") на суше");
        }
    }

    @Test
    void mapHasWaterAndBothSpawnsAreConnectedByLand() {
        boolean[][] reachable = landReachableFrom(map.spawnPoint(0));
        float[] other = map.spawnPoint(1);
        assertTrue(reachable[map.cellX(other[0])][map.cellY(other[1])], "от базы до базы можно дойти по суше");
        boolean anyWater = false;
        for (int cx = 0; cx < map.width() && !anyWater; cx++) {
            for (int cy = 0; cy < map.height() && !anyWater; cy++) {
                anyWater = map.isWaterCell(cx, cy);
            }
        }
        assertTrue(anyWater, "на карте есть вода");
    }

    @Test
    void nearestLandPointPullsOutOfWaterOnly() {
        float cell = GameConstants.PATH_GRID_CELL_SIZE;
        for (int cx = 0; cx < map.width(); cx++) {
            for (int cy = 0; cy < map.height(); cy++) {
                if (map.isWaterCell(cx, cy) && !map.isWaterCell(cx + 1, cy) && cx + 1 < map.width()) {
                    float[] land = map.nearestLandPoint((cx + 0.5f) * cell, (cy + 0.5f) * cell, GameConstants.UNIT_RADIUS);
                    assertNotNull(land);
                    assertFalse(map.isWaterAt(land[0], land[1]), "вытолкнуло на сушу");
                    float[] spawn = map.spawnPoint(0);
                    assertNull(map.nearestLandPoint(spawn[0], spawn[1], GameConstants.UNIT_RADIUS), "на суше не двигает");
                    return;
                }
            }
        }
        throw new AssertionError("не нашлось кромки воды");
    }

    @Test
    void mapWithWrongGridSizeIsRejected() {
        String json = "{\"cells\": [\"..\", \"~~\"], \"ironDeposits\": [], \"spawns\": [[1, 1], [2, 2]]}";
        assertThrows(IllegalArgumentException.class, () -> GameMap.parse(json));
    }

    private void assertInsideMap(float[] point) {
        assertTrue(point[0] > 0f && point[0] < GameConstants.MAP_WIDTH && point[1] > 0f && point[1] < GameConstants.MAP_HEIGHT,
                "точка (" + point[0] + ", " + point[1] + ") внутри карты");
    }

    /** Клетки суши, достижимые от точки по соседям (4 направления). */
    private boolean[][] landReachableFrom(float[] start) {
        boolean[][] reached = new boolean[map.width()][map.height()];
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        int sx = map.cellX(start[0]);
        int sy = map.cellY(start[1]);
        reached[sx][sy] = true;
        queue.add(new int[]{sx, sy});
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cellXY = queue.poll();
            for (int[] step : steps) {
                int nx = cellXY[0] + step[0];
                int ny = cellXY[1] + step[1];
                if (nx >= 0 && ny >= 0 && nx < map.width() && ny < map.height() && !reached[nx][ny] && !map.isWaterCell(nx, ny)) {
                    reached[nx][ny] = true;
                    queue.add(new int[]{nx, ny});
                }
            }
        }
        return reached;
    }
}
