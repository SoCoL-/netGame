package ru.socol.supreme.shared.craters;

import ru.socol.supreme.shared.GameConstants;

/**
 * Воронка от взрыва (снаряд артиллерии, взрыв электростанции) — шрам на
 * карте. Не сущность Ashley и не препятствие для поиска пути: наземные
 * юниты сквозь неё проходят, но медленнее (CRATER_SPEED_MULTIPLIER), а
 * строить на ней нельзя, пока строитель её не засыплет. Сама зарастает за
 * CRATER_LIFETIME_SECONDS. Живёт в CraterField конкретного матча.
 */
public final class Crater {

    public final int id;
    public final float x;
    public final float y;
    public float radius;

    /** Секунд с момента появления (или последнего углубления). При CRATER_LIFETIME_SECONDS воронка зарастает. */
    public float age;

    /** Секунд работы строителей, уже вложенных в засыпку (см. fillTime). */
    public float fillProgress;

    public Crater(int id, float x, float y, float radius) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.radius = radius;
    }

    public boolean contains(float px, float py) {
        float dx = px - x;
        float dy = py - y;
        return dx * dx + dy * dy <= radius * radius;
    }

    /** Пересекает ли воронка прямоугольник (ближайшая точка прямоугольника к центру — внутри круга). */
    public boolean overlapsRect(float minX, float minY, float maxX, float maxY) {
        float closestX = Math.max(minX, Math.min(x, maxX));
        float closestY = Math.max(minY, Math.min(y, maxY));
        return contains(closestX, closestY);
    }

    /**
     * Секунд работы одного строителя на засыпку — пропорционально площади:
     * воронка радиусом CRATER_FILL_REFERENCE_RADIUS засыпается за
     * CRATER_FILL_SECONDS, вдвое шире — вчетверо дольше.
     */
    public float fillTime() {
        float scale = radius / GameConstants.CRATER_FILL_REFERENCE_RADIUS;
        return GameConstants.CRATER_FILL_SECONDS * scale * scale;
    }
}
