package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Pool;

import java.util.ArrayList;
import java.util.List;

/**
 * Оставшиеся точки обходного пути вокруг препятствий — см.
 * ru.socol.supreme.shared.pathfinding.DynamicPathfinding. Присутствует на юните
 * только пока он идёт обходным путём: как только список опустеет,
 * MovementSystem сам снимает этот компонент. Юнитам, которым обход не
 * требовался (прямая видимость до цели была свободна), он вообще не
 * выдаётся — прямая линия остаётся быстрым путём по умолчанию.
 *
 * НОВОЕ (для динамического pathfinding):
 * - cachedDestinationX/Y: точные координаты цели (используется в recalculatePath)
 * - stuckCounter: счётчик, сколько тиков юнит не двигался (для обнаружения застревания)
 */
public class PathComponent implements Component, Pool.Poolable {

    public final List<Vector2> waypoints = new ArrayList<>();

    /**
     * Клетка сетки конечной цели, для которой был посчитан этот путь.
     * Пока новый пункт назначения попадает в ту же клетку — пересчитывать
     * путь не нужно, см. DynamicPathfinding.setDestination.
     */
    public int destinationCellX = -1;
    public int destinationCellY = -1;

    /**
     * НОВОЕ: точные координаты последней цели (не клетка, а мировые координаты).
     * Используется в DynamicPathfinding.recalculatePath для пересчёта пути,
     * если юнит упирается в препятствие.
     */
    public float cachedDestinationX = 0f;
    public float cachedDestinationY = 0f;

    /**
     * НОВОЕ: счётчик для обнаружения застревания.
     * Если юнит не движется N тиков подряд → вызываем recalculatePath.
     * Сбрасывается в 0 каждый раз, когда юнит реально движется.
     */
    public int stuckCounter = 0;

    @Override
    public void reset() {
        destinationCellX = -1;
        destinationCellY = -1;
        cachedDestinationX = 0f;
        cachedDestinationY = 0f;
        stuckCounter = 0;
        waypoints.clear();
    }
}
