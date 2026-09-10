package ru.socol.supreme.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import ru.socol.supreme.components.SelectedComponent;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;

/**
 * Рисует каждую сущность: юнит — кружком (у стрелка ещё белая точка
 * внутри, чтобы отличать от воина), здание — квадратом покрупнее; у
 * обоих полоска здоровья над ними и (для выделенных юнитов) кольцо
 * подсветки. Приоритет 10 — выполняется после InterpolationSystem
 * (приоритет 0), чтобы рисовать уже посчитанную на этот кадр позицию.
 */
public class RenderSystem extends IteratingSystem {

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<OwnerComponent> OWNER =
            ComponentMapper.getFor(OwnerComponent.class);
    private static final ComponentMapper<HealthComponent> HEALTH =
            ComponentMapper.getFor(HealthComponent.class);
    private static final ComponentMapper<SelectedComponent> SELECTED =
            ComponentMapper.getFor(SelectedComponent.class);
    private static final ComponentMapper<BuildingComponent> BUILDING =
            ComponentMapper.getFor(BuildingComponent.class);
    private static final ComponentMapper<UnitTypeComponent> UNIT_TYPE =
            ComponentMapper.getFor(UnitTypeComponent.class);

    private static final Color[] PLAYER_COLORS = {Color.SKY, Color.ORANGE};
    private static final Color SELECTION_RING_COLOR = Color.WHITE;
    private static final Color ARCHER_MARKER_COLOR = Color.WHITE;
    private static final float ARCHER_MARKER_RADIUS = GameConstants.UNIT_RADIUS * 0.4f;

    private static final float SELECTION_RING_RADIUS = GameConstants.UNIT_RADIUS + 3f;

    private static final float BUILDING_HALF_SIZE = GameConstants.BUILDING_HALF_SIZE;

    private static final float HEALTH_BAR_HEIGHT = 4f;
    private static final float UNIT_HEALTH_BAR_WIDTH = 24f;
    private static final float UNIT_HEALTH_BAR_Y_OFFSET = 18f;
    private static final float BUILDING_HEALTH_BAR_WIDTH = BUILDING_HALF_SIZE * 2f;
    private static final float BUILDING_HEALTH_BAR_Y_OFFSET = BUILDING_HALF_SIZE + 10f;

    private final ShapeRenderer shapeRenderer;

    public RenderSystem(ShapeRenderer shapeRenderer) {
        super(Family.all(PositionComponent.class, OwnerComponent.class, HealthComponent.class).get(), 10);
        this.shapeRenderer = shapeRenderer;
    }

    @Override
    public void update(float deltaTime) {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        super.update(deltaTime);
        shapeRenderer.end();
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        PositionComponent position = POSITION.get(entity);
        OwnerComponent owner = OWNER.get(entity);
        HealthComponent health = HEALTH.get(entity);

        if (BUILDING.has(entity)) {
            shapeRenderer.setColor(PLAYER_COLORS[owner.playerId % PLAYER_COLORS.length]);
            shapeRenderer.rect(
                    position.position.x - BUILDING_HALF_SIZE,
                    position.position.y - BUILDING_HALF_SIZE,
                    BUILDING_HALF_SIZE * 2f,
                    BUILDING_HALF_SIZE * 2f);
            drawHealthBar(position, health, BUILDING_HEALTH_BAR_Y_OFFSET, BUILDING_HEALTH_BAR_WIDTH);
            return;
        }

        // Подсветка выделения рисуется под юнитом более крупным кругом —
        // из-под основного кружка выглядывает как обводка, без отдельного
        // ShapeType.Line-прохода (ShapeRenderer не позволяет мешать типы
        // фигур внутри одного begin()/end()). Зданий это не касается — их
        // нельзя выделить (см. GameScreen), SelectedComponent на них не бывает.
        if (SELECTED.has(entity)) {
            shapeRenderer.setColor(SELECTION_RING_COLOR);
            shapeRenderer.circle(position.position.x, position.position.y, SELECTION_RING_RADIUS);
        }

        shapeRenderer.setColor(PLAYER_COLORS[owner.playerId % PLAYER_COLORS.length]);
        shapeRenderer.circle(position.position.x, position.position.y, GameConstants.UNIT_RADIUS);

        // Маленькая белая точка внутри — единственное, что отличает стрелка
        // от воина на глаз (оба одного размера и цвета иначе).
        UnitTypeComponent unitType = UNIT_TYPE.get(entity);
        if (unitType != null && unitType.type == UnitType.ARCHER) {
            shapeRenderer.setColor(ARCHER_MARKER_COLOR);
            shapeRenderer.circle(position.position.x, position.position.y, ARCHER_MARKER_RADIUS);
        }

        drawHealthBar(position, health, UNIT_HEALTH_BAR_Y_OFFSET, UNIT_HEALTH_BAR_WIDTH);
    }

    private void drawHealthBar(PositionComponent position, HealthComponent health, float yOffset, float barWidth) {
        float barX = position.position.x - barWidth / 2f;
        float barY = position.position.y + yOffset;
        // health.maxHealth, а не общая константа — у здания и юнита разный максимум.
        float healthFraction = MathUtils.clamp((float) health.currentHealth / health.maxHealth, 0f, 1f);

        shapeRenderer.setColor(Color.DARK_GRAY);
        shapeRenderer.rect(barX, barY, barWidth, HEALTH_BAR_HEIGHT);

        shapeRenderer.setColor(healthFraction > 0.3f ? Color.GREEN : Color.RED);
        shapeRenderer.rect(barX, barY, barWidth * healthFraction, HEALTH_BAR_HEIGHT);
    }
}
