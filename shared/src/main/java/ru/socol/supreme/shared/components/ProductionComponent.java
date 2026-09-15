package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;
import ru.socol.supreme.shared.UnitType;

/**
 * Очередь производства юнитов у здания. На сервере это авторитетное
 * состояние, которое двигает ProductionSystem; на клиенте — просто
 * последнее значение из снапшота, нужное только для отрисовки панели
 * постройки (см. GameScreen.drawProductionPanel) и точки сбора
 * (GameScreen.drawRallyPoints).
 */
public class ProductionComponent implements Component, Pool.Poolable {

    /** Сколько юнитов ещё осталось произвести, включая тот, что сейчас строится. */
    public int queuedCount;

    /** Секунд прошло с начала постройки текущего юнита (0, если очередь пуста). */
    public float progress;

    /** Какой тип юнита строит это здание — задаётся один раз при создании здания и не меняется. */
    public UnitType producesUnitType = UnitType.WARRIOR;

    /**
     * Точка сбора — куда идёт каждый только что произведённый юнит (см.
     * ProductionSystem, посылает Pathfinding.setDestination сразу после
     * создания юнита). hasRallyPoint=false, пока игрок ни разу не кликнул
     * левой кнопкой по карте при выделенном здании — тогда юнит просто
     * остаётся стоять в точке появления, как и раньше.
     */
    public boolean hasRallyPoint;
    public float rallyX;
    public float rallyY;

    @Override
    public void reset() {
        queuedCount = 0;
        progress = 0f;
        producesUnitType = UnitType.WARRIOR;
        hasRallyPoint = false;
        rallyX = 0f;
        rallyY = 0f;
    }
}
