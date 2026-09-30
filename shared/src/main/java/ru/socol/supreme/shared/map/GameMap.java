package ru.socol.supreme.shared.map;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import ru.socol.supreme.shared.GameConstants;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Карта матча: какая клетка — трава, вода или скалы, где месторождения
 * железа и откуда стартуют игроки. Одна и та же для сервера (проходимость,
 * месторождения, точки старта) и клиента (отрисовка земли и воды, призрак
 * шахты), поэтому живёт в shared.
 *
 * Читается один раз из maps/default.json в рабочей директории процесса —
 * так же, как units.json/buildings.json (сервер и клиент запускаются из
 * assets/, см. build.gradle). Файл готовит tools/bake_map.py из
 * рисованного макета. Если файла нет или он битый — встроенная карта
 * "как было раньше": прямоугольное озеро в центре, по два месторождения
 * у каждой базы, старты в противоположных углах.
 *
 * Сетка — клетки PATH_GRID_CELL_SIZE (50 единиц), та же, что у поиска
 * пути. Скалы пока только рисуются — на проходимость и стрельбу не
 * влияют (задел под следующий шаг).
 *
 * Неизменяема после загрузки — безопасно читать из любых потоков и
 * матчей одновременно.
 */
public final class GameMap {

    public enum Cell { GRASS, WATER, ROCK }

    private static final String MAP_PATH = "maps/default.json";

    private static final GameMap CURRENT = load();

    private final int width;
    private final int height;
    /** cells[cx][cy], cy = 0 — низ карты (мировой Y вверх). */
    private final Cell[][] cells;
    private final float[][] ironDeposits;
    private final float[][] spawns;

    private GameMap(Cell[][] cells, float[][] ironDeposits, float[][] spawns) {
        this.cells = cells;
        this.width = cells.length;
        this.height = cells[0].length;
        this.ironDeposits = ironDeposits;
        this.spawns = spawns;
    }

    public static GameMap current() {
        return CURRENT;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Клетка сетки; за пределами карты — трава (края карты и так ограничены отдельно). */
    public Cell cell(int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= width || cy >= height) {
            return Cell.GRASS;
        }
        return cells[cx][cy];
    }

    public boolean isWaterCell(int cx, int cy) {
        return cell(cx, cy) == Cell.WATER;
    }

    /** Вода ли ровно в точке (x, y) — без запаса на радиус юнита. */
    public boolean isWaterAt(float x, float y) {
        return isWaterCell(cellX(x), cellY(y));
    }

    public int cellX(float x) {
        return (int) Math.floor(x / GameConstants.PATH_GRID_CELL_SIZE);
    }

    public int cellY(float y) {
        return (int) Math.floor(y / GameConstants.PATH_GRID_CELL_SIZE);
    }

    /** Есть ли вода хоть в одной клетке, которую задевает прямоугольник [minX..maxX] x [minY..maxY]. */
    public boolean rectTouchesWater(float minX, float minY, float maxX, float maxY) {
        for (int cx = cellX(minX); cx <= cellX(maxX); cx++) {
            for (int cy = cellY(minY); cy <= cellY(maxY); cy++) {
                if (isWaterCell(cx, cy)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Ближайшая к (x, y) точка суши, отступив от кромки воды на margin,
     * или null, если точка и так на суше. Ищет в клетках вокруг — на
     * случай, если юнита (не умеющего в воду) протолкнули за кромку.
     */
    public float[] nearestLandPoint(float x, float y, float margin) {
        int cx = cellX(x);
        int cy = cellY(y);
        if (!isWaterCell(cx, cy)) {
            return null;
        }
        float cell = GameConstants.PATH_GRID_CELL_SIZE;
        float[] best = null;
        float bestDistanceSq = Float.MAX_VALUE;
        for (int radius = 1; radius <= 8 && best == null; radius++) {
            for (int nx = cx - radius; nx <= cx + radius; nx++) {
                for (int ny = cy - radius; ny <= cy + radius; ny++) {
                    if (nx < 0 || ny < 0 || nx >= width || ny >= height || isWaterCell(nx, ny)) {
                        continue;
                    }
                    // Ближайшая точка клетки суши, с отступом внутрь неё (но не дальше её центра).
                    float inset = Math.min(margin, cell / 2f);
                    float px = clamp(x, nx * cell + inset, (nx + 1) * cell - inset);
                    float py = clamp(y, ny * cell + inset, (ny + 1) * cell - inset);
                    float distanceSq = (px - x) * (px - x) + (py - y) * (py - y);
                    if (distanceSq < bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        best = new float[]{px, py};
                    }
                }
            }
        }
        return best;
    }

    /** Месторождения железа: [индекс][0 — x, 1 — y]. Индекс — тот, что уходит по сети (PlaceIronMineRequest.depositIndex). */
    public float[][] ironDeposits() {
        return ironDeposits;
    }

    /** Точка старта (центр дома) игрока 0 или 1. */
    public float[] spawnPoint(int playerId) {
        return spawns[playerId];
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static GameMap load() {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(MAP_PATH));
            GameMap map = parse(new String(bytes, StandardCharsets.UTF_8));
            System.out.println("Карта: загружена " + MAP_PATH + " (" + map.width + "x" + map.height + " клеток, "
                    + map.ironDeposits.length + " месторождений)");
            return map;
        } catch (Exception e) {
            System.out.println("Карта " + MAP_PATH + " не найдена или повреждена (" + e.getMessage()
                    + ") — используется встроенная карта по умолчанию.");
            return builtIn();
        }
    }

    /** Разбор JSON из tools/bake_map.py. Публичный — для тестов. */
    public static GameMap parse(String json) {
        JsonValue root = new JsonReader().parse(json);
        JsonValue rows = root.get("cells");
        int height = rows.size;
        int width = rows.get(0).asString().length();
        int expectedWidth = (int) Math.ceil(GameConstants.MAP_WIDTH / GameConstants.PATH_GRID_CELL_SIZE);
        int expectedHeight = (int) Math.ceil(GameConstants.MAP_HEIGHT / GameConstants.PATH_GRID_CELL_SIZE);
        if (width != expectedWidth || height != expectedHeight) {
            throw new IllegalArgumentException("сетка " + width + "x" + height + ", а нужна "
                    + expectedWidth + "x" + expectedHeight);
        }
        Cell[][] cells = new Cell[width][height];
        for (int row = 0; row < height; row++) {
            String line = rows.get(row).asString();
            if (line.length() != width) {
                throw new IllegalArgumentException("строка " + row + " длиной " + line.length() + ", а не " + width);
            }
            int cy = height - 1 - row; // первая строка файла — верх карты
            for (int cx = 0; cx < width; cx++) {
                char symbol = line.charAt(cx);
                cells[cx][cy] = symbol == '~' ? Cell.WATER : symbol == '#' ? Cell.ROCK : Cell.GRASS;
            }
        }
        float[][] deposits = points(root.get("ironDeposits"));
        float[][] spawns = points(root.get("spawns"));
        if (spawns.length != GameConstants.MAX_PLAYERS) {
            throw new IllegalArgumentException("точек старта " + spawns.length + ", а игроков " + GameConstants.MAX_PLAYERS);
        }
        return new GameMap(cells, deposits, spawns);
    }

    private static float[][] points(JsonValue array) {
        float[][] points = new float[array.size][];
        for (int i = 0; i < array.size; i++) {
            points[i] = array.get(i).asFloatArray();
        }
        return points;
    }

    /** Карта до появления файлов карт: озеро 2800..5200 по обеим осям, старты в углах. */
    static GameMap builtIn() {
        int width = (int) Math.ceil(GameConstants.MAP_WIDTH / GameConstants.PATH_GRID_CELL_SIZE);
        int height = (int) Math.ceil(GameConstants.MAP_HEIGHT / GameConstants.PATH_GRID_CELL_SIZE);
        float cell = GameConstants.PATH_GRID_CELL_SIZE;
        Cell[][] cells = new Cell[width][height];
        for (int cx = 0; cx < width; cx++) {
            for (int cy = 0; cy < height; cy++) {
                float x = (cx + 0.5f) * cell;
                float y = (cy + 0.5f) * cell;
                boolean water = x >= 2800f && x <= 5200f && y >= 2800f && y <= 5200f;
                cells[cx][cy] = water ? Cell.WATER : Cell.GRASS;
            }
        }
        float[][] deposits = {{2000f, 800f}, {800f, 2000f}, {6000f, 7200f}, {7200f, 6000f}};
        float margin = 800f; // HOME.spawnMargin — как было в BuildingDefinitions.homeSpawnPoint
        float[][] spawns = {{margin, margin}, {GameConstants.MAP_WIDTH - margin, GameConstants.MAP_HEIGHT - margin}};
        return new GameMap(cells, deposits, spawns);
    }
}
