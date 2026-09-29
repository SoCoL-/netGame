package ru.socol.supreme.systems;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;

/**
 * Спрайт стрелка ("Клин"): корпус и отдельно вращающаяся башня, каждый из
 * двух слоёв — основа и маска командного цвета (assets/units/archer-*.png).
 *
 * Текстуры подготовлены из присланного концепта (1254x1254): очищен шум
 * альфы, обрезаны прозрачные поля, всё уменьшено одним коэффициентом
 * (корпус — до 128 px), бирюзовые вставки вынесены в *-team.png
 * (оттенки серого) — при отрисовке маска красится цветом игрока. Нос и
 * ствол на картинках смотрят вверх (+Y).
 *
 * Совмещение слоёв — по ориентирам из описания к картинкам: центр погона
 * на корпусе (HULL_TURRET_PIVOT) совпадает с центром вращения башни
 * (TURRET_PIVOT). Позиция юнита — геометрический центр корпуса, так что
 * погон (а с ним и ось башни) чуть впереди неё.
 */
final class ArcherSprite implements Disposable {

    /** Длина корпуса в мировых единицах (для сравнения: UNIT_RADIUS = 10). */
    private static final float HULL_WORLD_LENGTH = 30f;

    // Координаты в пикселях уменьшенных текстур, Y вверх (как в libGDX).
    private static final float HULL_TURRET_PIVOT_X = 47.4f;
    private static final float HULL_TURRET_PIVOT_Y = 71.04f;
    private static final float TURRET_PIVOT_X = 25.19f;
    private static final float TURRET_PIVOT_Y = 43.27f;

    private final Texture hull = load("units/archer-hull.png");
    private final Texture hullTeam = load("units/archer-hull-team.png");
    private final Texture turret = load("units/archer-turret.png");
    private final Texture turretTeam = load("units/archer-turret-team.png");
    private final TextureRegion hullRegion = new TextureRegion(hull);
    private final TextureRegion hullTeamRegion = new TextureRegion(hullTeam);
    private final TextureRegion turretRegion = new TextureRegion(turret);
    private final TextureRegion turretTeamRegion = new TextureRegion(turretTeam);

    /** Мировых единиц на пиксель текстуры — один масштаб для обоих слоёв. */
    private final float scale = HULL_WORLD_LENGTH / hull.getHeight();

    private static Texture load(String path) {
        // С mip-уровнями: при обычном масштабе камеры 128-пиксельная текстура
        // ужимается до ~30 px, а в стратегическом виде ещё сильнее — без
        // mipmap при таком уменьшении мелкие детали рябят.
        Texture texture = new Texture(Gdx.files.internal(path), true);
        texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        return texture;
    }

    /**
     * Рисует стрелка с центром корпуса в (x, y). (hullDx, hullDy) — куда
     * едет корпус, (turretDx, turretDy) — куда смотрит башня (не обязаны
     * быть единичными, но не нулевые). teamColor — цвет игрока, alpha —
     * общая прозрачность (кроссфейд тактического/стратегического вида).
     * Вызывается внутри batch.begin()/end().
     */
    void draw(SpriteBatch batch, float x, float y, float hullDx, float hullDy,
              float turretDx, float turretDy, Color teamColor, float alpha) {
        // Картинка смотрит вверх (+Y), а угол 0 у atan2 — вправо (+X): отсюда -90.
        float hullDegrees = MathUtils.atan2(hullDy, hullDx) * MathUtils.radiansToDegrees - 90f;
        float turretDegrees = MathUtils.atan2(turretDy, turretDx) * MathUtils.radiansToDegrees - 90f;

        float hullWidth = hull.getWidth() * scale;
        float hullHeight = hull.getHeight() * scale;
        drawLayer(batch, hullRegion, hullTeamRegion, x, y, hullWidth / 2f, hullHeight / 2f,
                hullWidth, hullHeight, hullDegrees, teamColor, alpha);

        // Ось башни — погон на корпусе, повёрнутый вместе с корпусом.
        float offsetX = (HULL_TURRET_PIVOT_X - hull.getWidth() / 2f) * scale;
        float offsetY = (HULL_TURRET_PIVOT_Y - hull.getHeight() / 2f) * scale;
        float cos = MathUtils.cosDeg(hullDegrees);
        float sin = MathUtils.sinDeg(hullDegrees);
        float pivotX = x + offsetX * cos - offsetY * sin;
        float pivotY = y + offsetX * sin + offsetY * cos;

        drawLayer(batch, turretRegion, turretTeamRegion, pivotX, pivotY, TURRET_PIVOT_X * scale, TURRET_PIVOT_Y * scale,
                turret.getWidth() * scale, turret.getHeight() * scale, turretDegrees, teamColor, alpha);
    }

    /** Основа слоя и поверх неё — маска, окрашенная в цвет игрока; (pivotX, pivotY) — мировая точка оси вращения. */
    private void drawLayer(SpriteBatch batch, TextureRegion base, TextureRegion team, float pivotX, float pivotY,
                           float originX, float originY, float width, float height, float degrees,
                           Color teamColor, float alpha) {
        float left = pivotX - originX;
        float bottom = pivotY - originY;
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(base, left, bottom, originX, originY, width, height, 1f, 1f, degrees);
        batch.setColor(teamColor.r, teamColor.g, teamColor.b, alpha);
        batch.draw(team, left, bottom, originX, originY, width, height, 1f, 1f, degrees);
        batch.setColor(Color.WHITE);
    }

    @Override
    public void dispose() {
        hull.dispose();
        hullTeam.dispose();
        turret.dispose();
        turretTeam.dispose();
    }
}
