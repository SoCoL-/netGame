package ru.socol.supreme.systems;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.components.InterpolationComponent;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.PositionComponent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InterpolationSystem — единственное место, которое реально двигает
 * PositionComponent (то, что рисует RenderSystem) между двумя снапшотами
 * (см. её же javadoc). Тесты гоняют её на голом Ashley Engine — без окна,
 * без OpenGL и без сети — создавая сущность с ручными previousPosition/
 * targetPosition и проверяя, что отрисовываемая позиция предсказуемо
 * едет от одной точки к другой ровно за GameConstants.SNAPSHOT_RATE
 * секунд, не телепортируется и не перелетает цель при просевшем кадре.
 */
class InterpolationSystemTest {

    private Engine engine;
    private Entity entity;
    private PositionComponent position;
    private InterpolationComponent interpolation;

    @BeforeEach
    void setUp() {
        engine = new Engine();
        engine.addSystem(new InterpolationSystem());

        entity = new Entity();
        position = new PositionComponent();
        position.position.set(0f, 0f);
        interpolation = new InterpolationComponent();
        interpolation.previousPosition.set(0f, 0f);
        interpolation.targetPosition.set(100f, 200f);

        entity.add(position).add(interpolation);
        engine.addEntity(entity);
    }

    @Test
    void halfwayThroughSnapshotIntervalPositionIsHalfway() {
        engine.update(GameConstants.SNAPSHOT_RATE / 2f);

        assertEquals(50f, position.position.x, 0.001f);
        assertEquals(100f, position.position.y, 0.001f);
    }

    @Test
    void reachesExactTargetAtEndOfSnapshotInterval() {
        engine.update(GameConstants.SNAPSHOT_RATE);

        assertEquals(100f, position.position.x, 0.001f);
        assertEquals(200f, position.position.y, 0.001f);
    }

    @Test
    void doesNotOvershootTargetWhenFrameIsSlowerThanSnapshotRate() {
        // Джиттер/лаг кадра длиннее интервала снапшота — t зажимается в
        // [0, 1] (MathUtils.clamp в InterpolationSystem), а не
        // экстраполирует ЗА targetPosition.
        engine.update(GameConstants.SNAPSHOT_RATE * 5f);

        assertEquals(100f, position.position.x, 0.001f);
        assertEquals(200f, position.position.y, 0.001f);
    }

    @Test
    void elapsedAccumulatesAcrossMultipleFrames() {
        float frame = GameConstants.SNAPSHOT_RATE / 4f;
        engine.update(frame);
        engine.update(frame);

        // Два кадра по четверти интервала = половина пройденного пути.
        assertEquals(50f, position.position.x, 0.001f);
        assertTrue(interpolation.elapsed > 0f);
    }

    @Test
    void newSnapshotResetsElapsedAndStartsFromCurrentRenderedPosition() {
        engine.update(GameConstants.SNAPSHOT_RATE / 2f); // юнит на полпути, (50, 100)

        // Ровно то, что делает EntityFactory.applySnapshot при новом
        // снапшоте (см. её же javadoc): точка отправления — где юнит
        // нарисован ПРЯМО СЕЙЧАС, а не старая targetPosition.
        interpolation.previousPosition.set(position.position);
        interpolation.targetPosition.set(150f, 100f);
        interpolation.elapsed = 0f;

        engine.update(GameConstants.SNAPSHOT_RATE / 2f);

        assertEquals(100f, position.position.x, 0.001f,
                "продолжает плавно ехать от текущей точки, не дёргается назад к старому previousPosition");
    }
}
