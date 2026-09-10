package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Pool;

/**
 * Приказ на движение: направление (нормализованный вектор), точка назначения
 * и флаг, движется ли юнит прямо сейчас. Именно этот компонент читает
 * {@link ru.socol.supreme.shared.systems.MovementSystem} каждый тик.
 */
public class DirectionComponent implements Component, Pool.Poolable {

    /** Нормализованное направление движения. */
    public final Vector2 direction = new Vector2();

    /** Точка, куда юнит сейчас направляется (последний приказ игрока). */
    public final Vector2 target = new Vector2();

    public boolean moving = false;

    public float speed = 0f;

    @Override
    public void reset() {
        moving = false;
        speed = 0f;
        direction.set(0f, 0f);
        target.set(0f, 0f);
    }
}
