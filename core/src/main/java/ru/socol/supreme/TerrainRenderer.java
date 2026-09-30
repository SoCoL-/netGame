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

/**
 * Земля из текстур: бесшовные трава и грунт (assets/terrain/*.jpg)
 * повторяются по всей карте и смешиваются по маске — одним прямоугольником
 * на всю карту через SpriteBatch со своим шейдером.
 *
 * Маска — маленькая текстура, пиксель на клетку поиска пути
 * (PATH_GRID_CELL_SIZE): 0 — трава, 1 — грунт. Пока карты как данных нет,
 * маска строится здесь же детерминированным шумом (одинаково у всех
 * игроков): пятна вытоптанной земли по полю плюс полоса грунта вдоль
 * берега озера. Когда появятся файлы карт, достаточно грузить маску из
 * них — шейдер не изменится.
 *
 * Граница трава/грунт — не гладкий градиент маски, а рваный край:
 * шейдер сдвигает порог смешивания на яркость самой текстуры травы.
 * Чтобы не бросался в глаза повтор тайла на отдалении, трава и грунт
 * слегка подкрашиваются этой же текстурой, взятой в крупном масштабе.
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
            + "uniform float u_alpha;\n"
            + "varying vec2 v_tileUv;\n"
            + "varying vec2 v_maskUv;\n"
            + "void main() {\n"
            + "    vec3 grass = texture2D(u_texture, v_tileUv).rgb;\n"
            + "    vec3 dirt = texture2D(u_dirt, v_tileUv).rgb;\n"
            // Крупномасштабная вариация яркости против заметного повтора тайла.
            + "    float macro = dot(texture2D(u_texture, v_tileUv * 0.137 + vec2(0.31, 0.77)).rgb, vec3(0.333));\n"
            + "    float shade = 0.82 + 0.55 * macro;\n"
            + "    float mask = texture2D(u_mask, v_maskUv).r;\n"
            // Рваный край: порог смещается на яркость травы — пучки травы "заходят" на грунт.
            + "    float breakup = dot(grass, vec3(0.333)) - 0.3;\n"
            + "    float blend = smoothstep(0.35, 0.65, mask - breakup * 0.9);\n"
            + "    gl_FragColor = vec4(mix(grass, dirt, blend) * shade, u_alpha);\n"
            + "}\n";

    private final Texture grass = loadTiled("terrain/grass.jpg");
    private final Texture dirt = loadTiled("terrain/dirt.jpg");
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

    /** Рисует землю на всю карту. Вызывается вне batch.begin()/end(); проекцию batch уже должен знать. */
    void draw(SpriteBatch batch) {
        dirt.bind(1);
        mask.bind(2);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);

        batch.setShader(shader);
        batch.disableBlending(); // земля — самый нижний слой, смешивать не с чем
        batch.begin();
        shader.setUniformi("u_dirt", 1);
        shader.setUniformi("u_mask", 2);
        shader.setUniformf("u_mapSize", GameConstants.MAP_WIDTH, GameConstants.MAP_HEIGHT);
        shader.setUniformf("u_tileSize", TILE_WORLD_SIZE);
        shader.setUniformf("u_alpha", 1f);
        batch.draw(grass, 0f, 0f, GameConstants.MAP_WIDTH, GameConstants.MAP_HEIGHT);
        batch.end();
        batch.enableBlending();
        batch.setShader(null);
    }

    /** Маска грунта: пятна по шуму и полоса вдоль берега озера. Детерминирована — у всех игроков одинаковая. */
    private static Texture buildMask() {
        float cell = GameConstants.PATH_GRID_CELL_SIZE;
        int width = MathUtils.ceil(GameConstants.MAP_WIDTH / cell);
        int height = MathUtils.ceil(GameConstants.MAP_HEIGHT / cell);
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float worldX = (x + 0.5f) * cell;
                float worldY = (y + 0.5f) * cell;
                float noise = fbm(worldX / 900f, worldY / 900f);
                // Пятна: верхние ~15% значений шума.
                float patches = MathUtils.clamp((noise - 0.58f) / 0.12f, 0f, 1f);
                // Берег: грунт в пределах ~150 единиц от воды, ширина полосы "гуляет" по шуму.
                float shoreWidth = 90f + 120f * noise;
                float shore = 1f - MathUtils.clamp(distanceToWater(worldX, worldY) / shoreWidth, 0f, 1f);
                float value = Math.max(patches, shore);
                // Строка 0 Pixmap уходит в текстуру как v = 0 — это и есть низ карты
                // (в шейдере v = y / высота карты), так что без переворота.
                pixmap.drawPixel(x, y, Math.round(value * 255f) << 24 | 0xFF);
            }
        }
        Texture texture = new Texture(pixmap);
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        pixmap.dispose();
        return texture;
    }

    private static float distanceToWater(float x, float y) {
        float dx = Math.max(Math.max(GameConstants.WATER_MIN_X - x, 0f), x - GameConstants.WATER_MAX_X);
        float dy = Math.max(Math.max(GameConstants.WATER_MIN_Y - y, 0f), y - GameConstants.WATER_MAX_Y);
        return (float) Math.sqrt(dx * dx + dy * dy);
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
        mask.dispose();
        shader.dispose();
    }
}
