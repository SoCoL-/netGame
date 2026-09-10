package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.math.Vector2;

/**
 * Приказ на движение: направление (нормализованный вектор), точка назначения
 * и флаг, движется ли юнит прямо сейчас. Именно этот компонент читает
 * {@link ru.socol.supreme.shared.systems.MovementSystem} каждый тик.
 */
public class DirectionComponent implements Component {

    /** Нормализованное направление движения. */
    public final Vector2 direction = new Vector2();

    /** Точка, куда юнит сейчас направляется (последний приказ игрока). */
    public final Vector2 target = new Vector2();

    public boolean moving = false;

    public float speed = 0f;
}
