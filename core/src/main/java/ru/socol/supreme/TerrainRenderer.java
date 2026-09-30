package ru.socol.supreme;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.map.GameMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Земля и вода из текстур (assets/terrain/*.jpg): бесшовные трава, грунт,
 * мелководье и глубокая вода повторяются по всей карте и смешиваются
 * шейдером по маске — одним прямоугольником на всю карту через
 * SpriteBatch со своим шейдером.
 *
 * Маска — маленькая текстура, пиксель на клетку поиска пути
 * (PATH_GRID_CELL_SIZE), три канала:
 *  - R — грунт (0 — трава, 1 — грунт);
 *  - G — расстояние до берега со знаком: 0.5 — ровно берег, больше — вода
 *    (1 — на 100 единиц вглубь и дальше), меньше — суша. Расстояние, а
 *    не "вода да/нет": билинейная выборка из грубой маски даёт точную и
 *    ровную линию берега, по нему же считаются пена и мокрый грунт;
 *  - B — глубина: 0 у берега, 1 в 600 единицах от него — мелководье
 *    плавно темнеет к середине водоёма;
 *  - A — скалы (1 — клетка скал).
 * Вода и скалы берутся из карты (GameMap — та же сетка, по которой сервер
 * считает проходимость), пятна грунта — детерминированный шум (одинаковый
 * у всех игроков) плюс полоса грунта вдоль берега.
 *
 * Края не гладкие: порог трава/грунт и линия берега сдвигаются на
 * яркость текстуры травы (рваный край, берег "гуляет" на ~20 единиц).
 * Против заметного повтора тайла на отдалении суша подкрашивается той
 * же текстурой травы в крупном масштабе, а вода — сумма двух выборок,
 * медленно плывущих в разные стороны (заодно это и анимация ряби).
 */
final class TerrainRenderer implements Disposable {

    /** Сколько мировых единиц покрывает один повтор текстуры (1024 px). */
    private static final float TILE_WORLD_SIZE = 400f;

    private static final String VERTEX_SHADER = ""
            + "attribute vec4 a_position;\n"
            + "uniform mat4 u_projTrans;\n"
            + "uniform vec2 u_mapSize;\n"
            + "uniform float u_tileSize;\n"
            + "varying vec2 v_tileUv;\n"
            + "varying vec2 v_maskUv;\n"
            + "void main() {\n"
            + "    v_tileUv = a_position.xy / u_tileSize;\n"
            + "    v_maskUv = a_position.xy / u_mapSize;\n"
            + "    gl_Position = u_projTrans * a_position;\n"
            + "}\n";

    private static final String FRAGMENT_SHADER = ""
            + "#ifdef GL_ES\n"
            + "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "#endif\n"
            + "uniform sampler2D u_texture;\n" // трава, юнит 0 — его привязывает сам SpriteBatch
            + "uniform sampler2D u_dirt;\n"
            + "uniform sampler2D u_mask;\n"
            + "uniform sampler2D u_waterShallow;\n"
            + "uniform sampler2D u_waterDeep;\n"
            + "uniform float u_time;\n"
            + "varying vec2 v_tileUv;\n"
            + "varying vec2 v_maskUv;\n"
            + "const vec3 LUMA = vec3(0.333);\n"
            + "const float GRASS_MEAN_LUMA = 0.332;\n"
            + "void main() {\n"
            + "    vec4 mask = texture2D(u_mask, v_maskUv);\n"
            + "    vec3 grass = texture2D(u_texture, v_tileUv).rgb;\n"
            + "    vec3 dirt = texture2D(u_dirt, v_tileUv).rgb;\n"
            // Крупномасштабная вариация яркости против заметного повтора тайла.
            + "    float macro = dot(texture2D(u_texture, v_tileUv * 0.137 + vec2(0.31, 0.77)).rgb, LUMA);\n"
            + "    float shade = 0.82 + 0.55 * macro;\n"
            // Рваный край: порог смещается на яркость травы — пучки травы "заходят" на грунт.
            + "    float breakup = dot(grass, LUMA) - 0.3;\n"
            + "    float dirtBlend = smoothstep(0.35, 0.65, mask.r - breakup * 0.9);\n"
            + "    vec3 land = mix(grass, dirt, dirtBlend) * shade;\n"
            // Скалы: серый камень из текстуры грунта, неровный по яркости травы; край рваный, как у грунта.
            + "    float dirtLuma = dot(dirt, LUMA);\n"
            + "    vec3 rock = mix(vec3(dirtLuma), dirt, 0.25) * (0.55 + 0.9 * dot(grass, LUMA)) * shade;\n"
            + "    float rockBlend = smoothstep(0.4, 0.6, mask.a - breakup * 0.7);\n"
            + "    land = mix(land, rock, rockBlend);\n"
            // Берег: 0.5 — кромка; шум сдвигает её на ~20 единиц в обе стороны.
            // Только крупные масштабы травы: мелкий шум рвал кромку на крапинки грунта в воде.
            + "    float broad = dot(texture2D(u_texture, v_tileUv * 0.053 + vec2(0.61, 0.08)).rgb, LUMA);\n"
            + "    float shore = mask.g + (broad - GRASS_MEAN_LUMA) * 0.9 + (macro - GRASS_MEAN_LUMA) * 0.35;\n"
            // Мокрый грунт — полоса ~25 единиц суши у самой воды, темнее.
            + "    land *= mix(1.0, 0.68, smoothstep(0.37, 0.5, shore));\n"
            // Вода: две выборки плывут в разные стороны — рябь движется и повтор не так заметен.
            + "    vec2 flowA = v_tileUv + vec2(u_time * 0.004, u_time * 0.0015);\n"
            + "    vec2 flowB = v_tileUv * 0.77 + vec2(-u_time * 0.0025, u_time * 0.0035);\n"
            + "    vec3 shallowA = texture2D(u_waterShallow, flowA).rgb;\n"
            + "    vec3 shallow = mix(shallowA, texture2D(u_waterShallow, flowB).rgb, 0.35);\n"
            + "    vec3 deep = mix(texture2D(u_waterDeep, flowA).rgb, texture2D(u_waterDeep, flowB).rgb, 0.35);\n"
            + "    vec3 water = mix(shallow, deep, smoothstep(0.0, 1.0, mask.b));\n"
            // Пена — узкая полоса у кромки, "дышит" волнами и рвётся по бликам ряби.
            + "    float foamBand = 1.0 - smoothstep(0.5, 0.56, shore);\n"
            + "    float surf = 0.55 + 0.45 * sin(u_time * 1.6 - shore * 70.0);\n"
            + "    float foam = foamBand * surf * clamp(dot(shallowA, LUMA) * 1.8 - 0.35, 0.0, 1.0);\n"
            + "    water = mix(water, vec3(0.9, 0.95, 0.94), foam * 0.75);\n"
            + "    float isWater = smoothstep(0.485, 0.515, shore);\n"
            + "    gl_FragColor = vec4(mix(land, water, isWater), 1.0);\n"
            + "}\n";

    private final Texture grass = loadTiled("terrain/grass.jpg");
    private final Texture dirt = loadTiled("terrain/dirt.jpg");
    private final Texture waterShallow = loadTiled("terrain/water-shallow.jpg");
    private final Texture waterDeep = loadTiled("terrain/water-deep.jpg");
    private final Texture mask = buildMask();
    private final ShaderProgram shader;

    TerrainRenderer() {
        shader = new ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (!shader.isCompiled()) {
            throw new GdxRuntimeException("Шейдер земли не собрался: " + shader.getLog());
        }
    }

    private static Texture loadTiled(String path) {
        Texture texture = new Texture(Gdx.files.internal(path), true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat); // требует размер степени двойки — 1024
        return texture;
    }

    /**
     * Рисует землю и воду на всю карту. Вызывается вне batch.begin()/end();
     * проекцию batch уже должен знать. time — секунды с начала экрана, для
     * анимации воды.
     */
    void draw(SpriteBatch batch, float time) {
        dirt.bind(1);
        mask.bind(2);
        waterShallow.bind(3);
        waterDeep.bind(4);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);

        batch.setShader(shader);
        batch.disableBlending(); // земля — самый нижний слой, смешивать не с чем
        batch.begin();
        shader.setUniformi("u_dirt", 1);
        shader.setUniformi("u_mask", 2);
        shader.setUniformf("u_mapSize", GameConstants.MAP_WIDTH, GameConstants.MAP_HEIGHT);
        shader.setUniformf("u_tileSize", TILE_WORLD_SIZE);
        shader.setUniformi("u_waterShallow", 3);
        shader.setUniformi("u_waterDeep", 4);
        // Остаток от деления — чтобы float не терял точность за долгую партию (фазы волн сдвинутся раз в ~40 минут, незаметно).
        shader.setUniformf("u_time", time % 2400f);
        batch.draw(grass, 0f, 0f, GameConstants.MAP_WIDTH, GameConstants.MAP_HEIGHT);
        batch.end();
        batch.enableBlending();
        batch.setShader(null);
    }

    /**
     * Маска (см. javadoc класса): R — грунт, G — расстояние до берега,
     * B — глубина, A — скалы. Строится по карте (GameMap) — у всех
     * игроков одинаковая.
     */
    private static Texture buildMask() {
        GameMap map = GameMap.current();
        float cell = GameConstants.PATH_GRID_CELL_SIZE;
        int width = map.width();
        int height = map.height();
        float[][] shoreDistance = signedDistanceToWater(map);
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float worldX = (x + 0.5f) * cell;
                float worldY = (y + 0.5f) * cell;
                float noise = fbm(worldX / 900f, worldY / 900f);
                float distance = shoreDistance[x][y];
                // Пятна: верхние ~15% значений шума.
                float patches = MathUtils.clamp((noise - 0.58f) / 0.12f, 0f, 1f);
                // Береговой грунт в пределах ~90..210 единиц от воды, ширина полосы "гуляет" по шуму.
                float shoreWidth = 90f + 120f * noise;
                float shoreDirt = 1f - MathUtils.clamp(distance / shoreWidth, 0f, 1f);
                float dirt = Math.max(patches, shoreDirt);
                float shore = MathUtils.clamp(0.5f - distance / 200f, 0f, 1f);
                float depth = MathUtils.clamp(-distance / 600f, 0f, 1f);
                float rock = map.cell(x, y) == GameMap.Cell.ROCK ? 1f : 0f;
                // Строка 0 Pixmap уходит в текстуру как v = 0 — это и есть низ карты
                // (в шейдере v = y / высота карты), так что без переворота.
                pixmap.drawPixel(x, y, Math.round(dirt * 255f) << 24 | Math.round(shore * 255f) << 16
                        | Math.round(depth * 255f) << 8 | Math.round(rock * 255f));
            }
        }
        Texture texture = new Texture(pixmap);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        pixmap.dispose();
        return texture;
    }

    /**
     * Для каждой клетки — расстояние от её центра до кромки воды:
     * положительное на суше, отрицательное в воде. Кромка — посередине
     * между центрами соседних клеток суши и воды, поэтому расстояние —
     * до ближайшей клетки "другого берега" минус полклетки. Перебираются
     * только пограничные клетки — их порядка тысячи, так что даже
     * прямой перебор для 160x160 — доли секунды один раз при входе в игру.
     */
    private static float[][] signedDistanceToWater(GameMap map) {
        int width = map.width();
        int height = map.height();
        float cell = GameConstants.PATH_GRID_CELL_SIZE;
        List<int[]> landEdge = new ArrayList<>();
        List<int[]> waterEdge = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                boolean water = map.isWaterCell(x, y);
                boolean edge = (x > 0 && map.isWaterCell(x - 1, y) != water)
                        || (x < width - 1 && map.isWaterCell(x + 1, y) != water)
                        || (y > 0 && map.isWaterCell(x, y - 1) != water)
                        || (y < height - 1 && map.isWaterCell(x, y + 1) != water);
                if (edge) {
                    (water ? waterEdge : landEdge).add(new int[]{x, y});
                }
            }
        }
        float farAway = 10f * cell;
        float[][] result = new float[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                boolean water = map.isWaterCell(x, y);
                List<int[]> otherSide = water ? landEdge : waterEdge;
                float bestSq = Float.MAX_VALUE;
                for (int[] other : otherSide) {
                    float dx = other[0] - x;
                    float dy = other[1] - y;
                    bestSq = Math.min(bestSq, dx * dx + dy * dy);
                }
                float distance = otherSide.isEmpty() ? farAway / cell : (float) Math.sqrt(bestSq);
                distance = (distance - 0.5f) * cell;
                result[x][y] = water ? -distance : distance;
            }
        }
        return result;
    }

    /** Фрактальный value noise, 4 октавы, результат примерно в [0, 1]. */
    private static float fbm(float x, float y) {
        float sum = 0f;
        float amplitude = 0.5f;
        float total = 0f;
        for (int octave = 0; octave < 4; octave++) {
            sum += valueNoise(x, y, octave) * amplitude;
            total += amplitude;
            x *= 2.03f;
            y *= 2.03f;
            amplitude *= 0.5f;
        }
        return sum / total;
    }

    private static float valueNoise(float x, float y, int seed) {
        int x0 = MathUtils.floor(x);
        int y0 = MathUtils.floor(y);
        float fx = x - x0;
        float fy = y - y0;
        float sx = fx * fx * (3f - 2f * fx);
        float sy = fy * fy * (3f - 2f * fy);
        float top = MathUtils.lerp(hash(x0, y0, seed), hash(x0 + 1, y0, seed), sx);
        float bottom = MathUtils.lerp(hash(x0, y0 + 1, seed), hash(x0 + 1, y0 + 1, seed), sx);
        return MathUtils.lerp(top, bottom, sy);
    }

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    @Override
    public void dispose() {
        grass.dispose();
        dirt.dispose();
        waterShallow.dispose();
        waterDeep.dispose();
        mask.dispose();
        shader.dispose();
    }
}
