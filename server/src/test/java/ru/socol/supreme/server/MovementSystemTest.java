package ru.socol.supreme.server;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.systems.MovementSystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Прибытие в точку: шаг за тик (8 единиц при скорости 240 и 30 тиках в
 * секунду) больше порога прибытия, и раньше юнит, перелетев точку,
 * качался вокруг неё туда-обратно вместо того, чтобы остановиться.
 */
class MovementSystemTest {

    private static final float SPEED = 240f;

    @Test
    void unitStopsExactlyOnTargetInsteadOfOscillating() {
        // Любые остатки от деления на шаг — в том числе те, что раньше давали
        // вечное качание (5 -> 3 -> 5 ...).
        for (float distance = 100f; distance < 108f; distance += 0.5f) {
            Engine engine = new Engine();
            engine.addSystem(new MovementSystem());
            Entity unit = unit(engine, distance);

            int ticks = 0;
            DirectionComponent direction = unit.getComponent(DirectionComponent.class);
            while (direction.moving && ticks < 100) {
                engine.update(GameConstants.SERVER_TICK_RATE);
                ticks++;
            }

            assertFalse(direction.moving, "остановился (расстояние " + distance + ")");
            int expectedTicks = (int) Math.ceil(distance / (SPEED * GameConstants.SERVER_TICK_RATE));
            assertEquals(expectedTicks, ticks, "без лишних тиков (расстояние " + distance + ")");
            assertEquals(distance, unit.getComponent(PositionComponent.class).position.x, 0.001f);
        }
    }

    private static Entity unit(Engine engine, float distance) {
        Entity entity = new Entity();
        entity.add(new PositionComponent());
        DirectionComponent direction = new DirectionComponent();
        direction.speed = SPEED;
        direction.target.set(distance, 0f);
        direction.direction.set(1f, 0f);
        direction.moving = true;
        entity.add(direction);
        engine.addEntity(entity);
        return entity;
    }
}
