package ru.socol.supreme.shared.network.messages;

/**
 * Одна точка оставшегося маршрута юнита. Список таких точек в
 * UnitSnapshot.pathPoints нужен только для отладочной отрисовки маршрута
 * на клиенте (режим по клавише ` в GameScreen) — на исход игры никак не
 * влияет, это чисто диагностическая информация.
 */
public class PathPoint {

    public float x;
    public float y;

    public PathPoint() {
    }

    public PathPoint(float x, float y) {
        this.x = x;
        this.y = y;
    }
}
