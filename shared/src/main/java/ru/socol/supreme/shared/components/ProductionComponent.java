package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

import ru.socol.supreme.shared.UnitType;

/**
 * Очередь производства юнитов у здания. На сервере это авторитетное
 * состояние, которое двигает ProductionSystem; на клиенте — просто
 * последнее значение из снапшота, нужное только для отрисовки панели
 * постройки (см. GameScreen.drawProductionPanel).
 */
public class ProductionComponent implements Component, Pool.Poolable {

    /** Сколько юнитов ещё осталось произвести, включая тот, что сейчас строится. */
    public int queuedCount;

    /** Секунд прошло с начала постройки текущего юнита (0, если очередь пуста). */
    public float progress;

    /** Какой тип юнита строит это здание — задаётся один раз при создании здания и не меняется. */
    public UnitType producesUnitType = UnitType.WARRIOR;

    @Override
    public void reset() {
        queuedCount = 0;
        progress = 0f;
        producesUnitType = UnitType.WARRIOR;
    }
}
