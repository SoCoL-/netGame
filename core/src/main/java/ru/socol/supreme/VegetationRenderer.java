package ru.socol.supreme;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.assets.GameAssets;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.map.GameMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Растительность — чистый декор карты: на проходимость, стрельбу и обзор
 * не влияет, сервер о ней не знает. Расставляется на клиенте один раз,
 * детерминированно (фиксированный сид и шум), так что у обоих игроков
 * одинакова:
 *  - только на траве — не в воде и не на скалах, всем силуэтом;
 *  - деревья растут рощами (крупный шум), вид дерева меняется пятнами
 *    (рощи одной породы), кусты — россыпью и чаще у рощ;
 *  - не у точек старта (место под базу) и не на месторождениях.
 *
 * Кусты рисуются на земле под юнитами, кроны деревьев — над юнитами, как
 * в жизни. Чтобы техника под кроной не терялась, крона над юнитом
 * становится полупрозрачной, а крона на месте здания не рисуется вовсе.
 * Под туманом войны растения видны (затемнены туманом, как и земля) —
 * это рельеф, а не игровые объекты.
 */
final class VegetationRenderer {

    /** Плотность расстановки: один кандидат на квадрат CELL x CELL. */
    private static final float CELL = 95f;
    /** Ячейка поиска соседних деревьев — больше самой крупной кроны. */
    private static final float NEIGHBOUR_CELL = 120f;
    private static final long SEED = 20261001L;
    /** Свободный круг вокруг точек старта — под постройки базы. */
    private static final float SPAWN_CLEARANCE_TREES = 850f;
    private static final float SPAWN_CLEARANCE_BUSHES = 550f;
    /** Свободный круг вокруг месторождения — под шахту. */
    private static final float DEPOSIT_CLEARANCE = 110f;
    private static final float MAX_ROTATION_DEGREES = 35f;
    /** Прозрачность кроны, под которой стоит юнит. */
    private static final float CANOPY_OVER_UNIT_ALPHA = 0.4f;

    private static final VegetationType[] BUSHES = {VegetationType.BUSH_COMPACT, VegetationType.BUSH_SPREADING, VegetationType.BUSH_DRY};
    private static final VegetationType[] TREES = {VegetationType.TREE_ROUND, VegetationType.TREE_AIRY, VegetationType.TREE_CONIFER};

    /** Одно растение: тип, центр и множитель размера. */
    static final class Plant {
        final VegetationType type;
        final float x;
        final float y;
        final float size;
        /** Небольшой поворот — чтобы одинаковые спрайты не выстраивались в ряды; больше ±35° уже заметно спорит с нарисованным светом. */
        final float rotation;

        Plant(VegetationType type, float x, float y, float scale, float rotation) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.size = type.worldSize * scale;
            this.rotation = rotation;
        }

        /** Радиус кроны/куста — чуть меньше половины квадрата текстуры (по краям — прозрачные просветы). */
        float radius() {
            return size * 0.42f;
        }
    }

    private final Map<VegetationType, Texture> textures = new EnumMap<>(VegetationType.class);
    private final List<Plant> bushes = new ArrayList<>();
    private final List<Plant> trees = new ArrayList<>();

    VegetationRenderer(GameAssets assets) {
        for (VegetationType type : VegetationType.values()) {
            textures.put(type, assets.texture(type.texturePath));
        }
        place(GameMap.current(), bushes, trees);
        // По типу — меньше переключений текстуры в SpriteBatch.
        bushes.sort(Comparator.comparing(plant -> plant.type));
        trees.sort(Comparator.comparing(plant -> plant.type));
    }

    /**
     * Детерминированная расстановка по карте (вынесена отдельно —
     * проверяется тестом без OpenGL). Кандидаты — в случайных точках, а не
     * по сетке (сетка давала ровные ряды, как в саду); деревья держат
     * дистанцию друг от друга, но кроны могут немного перекрываться.
     */
    static void place(GameMap map, List<Plant> bushes, List<Plant> trees) {
        Random random = new Random(SEED);
        Map<Long, List<Plant>> treesByCell = new HashMap<>();
        int candidates = (int) (GameConstants.MAP_WIDTH * GameConstants.MAP_HEIGHT / (CELL * CELL));
        for (int i = 0; i < candidates; i++) {
            float x = random.nextFloat() * GameConstants.MAP_WIDTH;
            float y = random.nextFloat() * GameConstants.MAP_HEIGHT;
            float grove = noise(x / 1300f, y / 1300f, 1);
            float roll = random.nextFloat();
            float scale = 0.85f + random.nextFloat() * 0.3f;
            float rotation = (random.nextFloat() - 0.5f) * 2f * MAX_ROTATION_DEGREES;

            float treeChance = MathUtils.clamp((grove - 0.47f) / 0.15f, 0f, 1f) * 0.75f;
            float bushChance = 0.08f + 0.2f * grove;
            if (roll < treeChance) {
                // Порода: в каждом квадрате 1500x1500 преобладает своя (2 из 3 деревьев), остальные — вперемешку.
                int dominant = (int) (hash(MathUtils.floor(x / 1500f), MathUtils.floor(y / 1500f), 7) * TREES.length) % TREES.length;
                VegetationType type = random.nextFloat() < 0.65f ? TREES[dominant] : TREES[random.nextInt(TREES.length)];
                Plant tree = new Plant(type, x, y, scale, rotation);
                if (fits(map, tree, SPAWN_CLEARANCE_TREES) && hasRoom(tree, treesByCell)) {
                    trees.add(tree);
                    treesByCell.computeIfAbsent(cellKey(x, y), key -> new ArrayList<>()).add(tree);
                }
            } else if (roll < treeChance + bushChance) {
                Plant bush = new Plant(BUSHES[random.nextInt(BUSHES.length)], x, y, scale, rotation);
                if (fits(map, bush, SPAWN_CLEARANCE_BUSHES)) {
                    bushes.add(bush);
                }
            }
        }
    }

    /** Соседние деревья не ближе ~0.7 суммы радиусов крон — кроны касаются и слегка перекрываются, но не сливаются в одно. */
    private static boolean hasRoom(Plant tree, Map<Long, List<Plant>> treesByCell) {
        int cx = (int) (tree.x / NEIGHBOUR_CELL);
        int cy = (int) (tree.y / NEIGHBOUR_CELL);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                List<Plant> neighbours = treesByCell.get(cellKey((cx + dx) * NEIGHBOUR_CELL, (cy + dy) * NEIGHBOUR_CELL));
                if (neighbours == null) {
                    continue;
                }
                for (Plant other : neighbours) {
                    float minDistance = (tree.radius() + other.radius()) * 0.7f;
                    if (Vector2.dst2(tree.x, tree.y, other.x, other.y) < minDistance * minDistance) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static long cellKey(float x, float y) {
        return ((long) (int) (x / NEIGHBOUR_CELL) << 32) ^ ((int) (y / NEIGHBOUR_CELL) & 0xffffffffL);
    }

    /** Растение целиком на траве, не у точек старта и не на месторождении. */
    private static boolean fits(GameMap map, Plant plant, float spawnClearance) {
        float r = plant.radius();
        float[][] probes = {{0f, 0f}, {r, 0f}, {-r, 0f}, {0f, r}, {0f, -r}};
        for (float[] probe : probes) {
            float px = plant.x + probe[0];
            float py = plant.y + probe[1];
            if (px < 0f || py < 0f || px >= GameConstants.MAP_WIDTH || py >= GameConstants.MAP_HEIGHT
                    || map.cell(map.cellX(px), map.cellY(py)) != GameMap.Cell.GRASS) {
                return false;
            }
        }
        for (int player = 0; player < GameConstants.MAX_PLAYERS; player++) {
            float[] spawn = map.spawnPoint(player);
            if (Vector2.dst(plant.x, plant.y, spawn[0], spawn[1]) < spawnClearance) {
                return false;
            }
        }
        for (float[] deposit : map.ironDeposits()) {
            if (Vector2.dst(plant.x, plant.y, deposit[0], deposit[1]) < DEPOSIT_CLEARANCE + r) {
                return false;
            }
        }
        return true;
    }

    /** Кусты — на земле, до юнитов. alpha — общая прозрачность (кроссфейд тактического/стратегического вида). */
    void drawBushes(SpriteBatch batch, OrthographicCamera camera, float alpha) {
        if (alpha <= 0f) {
            return;
        }
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        batch.setColor(1f, 1f, 1f, alpha);
        for (Plant bush : bushes) {
            if (isOnScreen(camera, bush)) {
                drawPlant(batch, bush);
            }
        }
        batch.end();
        batch.setColor(Color.WHITE);
    }

    /**
     * Кроны деревьев — над юнитами. units — центры видимых юнитов (крона
     * над ними полупрозрачная), buildings — прямоугольники видимых зданий
     * {minX, minY, maxX, maxY} (крона, чей центр в здании, не рисуется).
     */
    void drawTrees(SpriteBatch batch, OrthographicCamera camera, float alpha, List<Vector2> units, List<float[]> buildings) {
        if (alpha <= 0f) {
            return;
        }
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        for (Plant tree : trees) {
            if (!isOnScreen(camera, tree) || isUnderBuilding(tree, buildings)) {
                continue;
            }
            batch.setColor(1f, 1f, 1f, alpha * (coversUnit(tree, units) ? CANOPY_OVER_UNIT_ALPHA : 1f));
            drawPlant(batch, tree);
        }
        batch.end();
        batch.setColor(Color.WHITE);
    }

    private void drawPlant(SpriteBatch batch, Plant plant) {
        Texture texture = textures.get(plant.type);
        float half = plant.size / 2f;
        batch.draw(texture, plant.x - half, plant.y - half, half, half, plant.size, plant.size, 1f, 1f, plant.rotation,
                0, 0, texture.getWidth(), texture.getHeight(), false, false);
    }

    private static boolean isOnScreen(OrthographicCamera camera, Plant plant) {
        float halfWidth = camera.viewportWidth * camera.zoom / 2f + plant.size;
        float halfHeight = camera.viewportHeight * camera.zoom / 2f + plant.size;
        return Math.abs(plant.x - camera.position.x) < halfWidth && Math.abs(plant.y - camera.position.y) < halfHeight;
    }

    private static boolean coversUnit(Plant tree, List<Vector2> units) {
        float reach = tree.radius() + GameConstants.UNIT_RADIUS;
        for (Vector2 unit : units) {
            if (unit.dst2(tree.x, tree.y) < reach * reach) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnderBuilding(Plant tree, List<float[]> buildings) {
        for (float[] rect : buildings) {
            if (tree.x >= rect[0] && tree.x <= rect[2] && tree.y >= rect[1] && tree.y <= rect[3]) {
                return true;
            }
        }
        return false;
    }

    int bushCount() {
        return bushes.size();
    }

    int treeCount() {
        return trees.size();
    }

    // ---- Шум для рощ: value noise, 3 октавы, результат примерно в [0, 1] ----

    private static float noise(float x, float y, int seed) {
        float sum = 0f;
        float amplitude = 0.5f;
        float total = 0f;
        for (int octave = 0; octave < 3; octave++) {
            sum += valueNoise(x, y, seed * 31 + octave) * amplitude;
            total += amplitude;
            x *= 2.1f;
            y *= 2.1f;
            amplitude *= 0.5f;
        }
        return sum / total;
    }

    private static float valueNoise(float x, float y, int seed) {
        int x0 = MathUtils.floor(x);
        int y0 = MathUtils.floor(y);
        float sx = smooth(x - x0);
        float sy = smooth(y - y0);
        float top = MathUtils.lerp(hash(x0, y0, seed), hash(x0 + 1, y0, seed), sx);
        float bottom = MathUtils.lerp(hash(x0, y0 + 1, seed), hash(x0 + 1, y0 + 1, seed), sx);
        return MathUtils.lerp(top, bottom, sy);
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0xFFFFFF;
    }
}
