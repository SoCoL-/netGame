package ru.socol.supreme.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.MathUtils;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.components.InterpolationComponent;
import ru.socol.supreme.shared.components.PositionComponent;

/**
 * Каждый кадр подвигает PositionComponent.position (то, что реально рисует
 * RenderSystem) чуть ближе к последней позиции из снапшота — вместо того,
 * чтобы телепортировать её раз в SNAPSHOT_RATE секунд, как было раньше.
 *
 * Работает только на клиенте: приоритет 0, идёт до RenderSystem
 * (приоритет 10), чтобы к моменту отрисовки позиция уже была посчитана
 * на этот кадр.
 *
 * Упрощение: длительность лерпа берётся равной ожидаемому интервалу между
 * снапшотами (GameConstants.SNAPSHOT_RATE), а не фактическому времени между
 * двумя последними полученными снапшотами. При реальном джиттере сети это
 * может слегка "спешить" или "притормаживать" — более точный вариант:
 * буферизовать пару последних снапшотов с их временными метками и рисовать
 * с небольшой задержкой (render delay), интерполируя строго между ними.
 */
public class InterpolationSystem extends IteratingSystem {

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<InterpolationComponent> INTERPOLATION =
            ComponentMapper.getFor(InterpolationComponent.class);

    public InterpolationSystem() {
        super(Family.all(PositionComponent.class, InterpolationComponent.class).get(), 0);
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        InterpolationComponent interpolation = INTERPOLATION.get(entity);
        interpolation.elapsed += deltaTime;

        float t = MathUtils.clamp(interpolation.elapsed / GameConstants.SNAPSHOT_RATE, 0f, 1f);

        POSITION.get(entity).position
                .set(interpolation.previousPosition)
                .lerp(interpolation.targetPosition, t);
    }
}
