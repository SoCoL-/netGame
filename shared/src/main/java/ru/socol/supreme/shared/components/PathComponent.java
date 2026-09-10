package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Pool;

import java.util.ArrayList;
import java.util.List;

/**
 * Оставшиеся точки обходного пути вокруг препятствий — см.
 * ru.socol.supreme.shared.pathfinding.Pathfinding. Присутствует на юните
 * только пока он идёт обходным путём: как только список опустеет,
 * MovementSystem сам снимает этот компонент. Юнитам, которым обход не
 * требовался (прямая видимость до цели была свободна), он вообще не
 * выдаётся — прямая линия остаётся быстрым путём по умолчанию.
 */
public class PathComponent implements Component, Pool.Poolable {

    public final List<Vector2> waypoints = new ArrayList<>();

    /**
     * Клетка сетки конечной цели, для которой был посчитан этот путь.
     * Пока новый пункт назначения попадает в ту же клетку — пересчитывать
     * путь не нужно, см. Pathfinding.setDestination.
     */
    public int destinationCellX = -1;
    public int destinationCellY = -1;

    @Override
    public void reset() {
        destinationCellX = -1;
        destinationCellY = -1;
        waypoints.clear();
    }
}
