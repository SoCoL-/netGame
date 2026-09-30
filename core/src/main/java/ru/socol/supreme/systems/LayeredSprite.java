package ru.socol.supreme.systems;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import ru.socol.supreme.assets.GameAssets;

/**
 * Двухслойный спрайт машины: корпус и отдельно вращающаяся башня, каждый
 * слой — основа и маска командного цвета (assets/units/&lt;name&gt;-*.png).
 *
 * Текстуры запекаются из концепт-арта скриптом tools/bake_unit_sprite.py:
 * обрезаны прозрачные поля, всё уменьшено до игрового размера, бирюзовые
 * вставки вынесены в *-team.png (оттенки серого) — при отрисовке маска
 * красится цветом игрока. Нос и ствол на картинках смотрят вверх (+Y).
 * Пиксели обеих текстур одного размера (разницу масштаба слоёв на
 * исходниках скрипт уже учёл), так что оба слоя рисуются одним масштабом.
 *
 * Совмещение слоёв — точка погона на корпусе (hullPivot) совпадает с осью
 * вращения башни (turretPivot); обе точки скрипт печатает в пикселях
 * готовых текстур, Y вверх. Позиция сущности — центр корпуса.
 *
 * Текстуры (с mip-уровнями) грузит и освобождает GameAssets — спрайт их
 * только берёт.
 */
final class LayeredSprite {

    /** Стрелок "Клин". */
    static LayeredSprite archer(GameAssets assets) {
        return new LayeredSprite(assets, "archer", 30f, 47.4f, 71.04f, 25.19f, 43.27f);
    }

    /** ПВО — "Заслон" (Бастион): гусеничное шасси и спаренные автопушки с радаром. */
    static LayeredSprite antiAir(GameAssets assets) {
        return new LayeredSprite(assets, "anti-air", 34f, 72.38f, 76.17f, 41.71f, 26.66f);
    }

    /**
     * Артиллерийская башня — "Прилив" (Поток): корпус неподвижен, вращается
     * излучатель. Шире, чем в длину, — вписан в квадрат здания 100x100.
     */
    static LayeredSprite artillery(GameAssets assets) {
        return new LayeredSprite(assets, "artillery", 96f, 125.66f, 124.12f, 64.89f, 28.96f);
    }

    private final Texture hull;
    private final Texture hullTeam;
    private final Texture turret;
    private final Texture turretTeam;
    private final TextureRegion hullRegion;
    private final TextureRegion hullTeamRegion;
    private final TextureRegion turretRegion;
    private final TextureRegion turretTeamRegion;
    private final float hullPivotX;
    private final float hullPivotY;
    private final float turretPivotX;
    private final float turretPivotY;

    /** Мировых единиц на пиксель текстуры — один масштаб для обоих слоёв. */
    private final float scale;

    /**
     * @param hullWorldSize бо́льшая сторона корпуса в мировых единицах
     *                      (для сравнения: UNIT_RADIUS = 10)
     */
    private LayeredSprite(GameAssets assets, String name, float hullWorldSize, float hullPivotX, float hullPivotY,
                          float turretPivotX, float turretPivotY) {
        hull = assets.texture(GameAssets.unitTexturePath(name, "hull"));
        hullTeam = assets.texture(GameAssets.unitTexturePath(name, "hull-team"));
        turret = assets.texture(GameAssets.unitTexturePath(name, "turret"));
        turretTeam = assets.texture(GameAssets.unitTexturePath(name, "turret-team"));
        hullRegion = new TextureRegion(hull);
        hullTeamRegion = new TextureRegion(hullTeam);
        turretRegion = new TextureRegion(turret);
        turretTeamRegion = new TextureRegion(turretTeam);
        this.hullPivotX = hullPivotX;
        this.hullPivotY = hullPivotY;
        this.turretPivotX = turretPivotX;
        this.turretPivotY = turretPivotY;
        scale = hullWorldSize / Math.max(hull.getWidth(), hull.getHeight());
    }

    /**
     * Рисует машину с центром корпуса в (x, y). (hullDx, hullDy) — куда
     * смотрит корпус, (turretDx, turretDy) — куда смотрит башня (не обязаны
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
        float offsetX = (hullPivotX - hull.getWidth() / 2f) * scale;
        float offsetY = (hullPivotY - hull.getHeight() / 2f) * scale;
        float cos = MathUtils.cosDeg(hullDegrees);
        float sin = MathUtils.sinDeg(hullDegrees);
        float pivotX = x + offsetX * cos - offsetY * sin;
        float pivotY = y + offsetX * sin + offsetY * cos;

        drawLayer(batch, turretRegion, turretTeamRegion, pivotX, pivotY, turretPivotX * scale, turretPivotY * scale,
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
}
