package ru.socol.supreme.shared.craters;

import ru.socol.supreme.shared.GameConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Все воронки одного матча. Взрыв рядом с уже существующей воронкой не
 * плодит новую, а углубляет старую: если точка взрыва внутри воронки, её
 * радиус растёт на CRATER_GROWTH_PER_HIT (не больше CRATER_MAX_RADIUS),
 * зарастание начинается заново, а засыпка — с нуля. Так постоянно
 * обстреливаемое место становится глубоким шрамом, а число воронок не
 * растёт бесконечно.
 *
 * Синхронизации нет: как и остальное состояние матча, трогается только под
 * монитором GameServer.
 */
public final class CraterField {

    private final List<Crater> craters = new ArrayList<>();
    private int nextId = 1;

    /** Взрыв радиусом radius в (x, y): новая воронка или углубление существующей. Возвращает её. */
    public Crater addExplosion(float x, float y, float radius) {
        for (Crater crater : craters) {
            if (crater.contains(x, y)) {
                crater.radius = Math.min(GameConstants.CRATER_MAX_RADIUS,
                        Math.max(crater.radius, radius) + GameConstants.CRATER_GROWTH_PER_HIT);
                crater.age = 0f;
                crater.fillProgress = 0f;
                return crater;
            }
        }
        Crater crater = new Crater(nextId++, x, y, Math.min(radius, GameConstants.CRATER_MAX_RADIUS));
        craters.add(crater);
        return crater;
    }

    /** Старит воронки и убирает заросшие. */
    public void update(float deltaTime) {
        for (Iterator<Crater> iterator = craters.iterator(); iterator.hasNext(); ) {
            Crater crater = iterator.next();
            crater.age += deltaTime;
            if (crater.age >= GameConstants.CRATER_LIFETIME_SECONDS) {
                iterator.remove();
            }
        }
    }

    public Crater find(int id) {
        for (Crater crater : craters) {
            if (crater.id == id) {
                return crater;
            }
        }
        return null;
    }

    public void remove(Crater crater) {
        craters.remove(crater);
    }

    /** Воронка, в которую попадает точка, или null. */
    public Crater craterAt(float x, float y) {
        for (Crater crater : craters) {
            if (crater.contains(x, y)) {
                return crater;
            }
        }
        return null;
    }

    /** Есть ли воронка, задевающая прямоугольник — для запрета стройки. */
    public boolean overlapsRect(float minX, float minY, float maxX, float maxY) {
        for (Crater crater : craters) {
            if (crater.overlapsRect(minX, minY, maxX, maxY)) {
                return true;
            }
        }
        return false;
    }

    /** Только для клиента: копия воронок из снапшота пересобирается заново при каждом WorldSnapshot. */
    public void clear() {
        craters.clear();
    }

    /** Только для клиента — см. clear(). */
    public void add(Crater crater) {
        craters.add(crater);
    }

    public List<Crater> all() {
        return Collections.unmodifiableList(craters);
    }
}
