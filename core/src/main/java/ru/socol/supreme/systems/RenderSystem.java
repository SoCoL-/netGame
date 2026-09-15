package ru.socol.supreme.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import ru.socol.supreme.components.SelectedComponent;
import ru.socol.supreme.shared.BuildingSizes;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;

/**
 * Рисует каждую сущность: юнит — кружком (у стрелка ещё белая точка
 * внутри, чтобы отличать от воина), здание — прямоугольником нужной
 * формы (дом 2x2 клетки с золотой звездой, казарма стрелков 1x2 с белой
 * "крышечкой", шахта железа 1x1 с ржаво-коричневым ромбом, электростанция
 * 2x2 с жёлтым кружком — единственный способ различить их на глаз, раз
 * цвет у всех один и тот же — цвет игрока). Какой значок и какого
 * размера рисовать, решает BuildingComponent.type — единственный
 * источник истины "какое это здание" (BuildingSizes для размера,
 * switch по type здесь для значка, не отдельные проверки компонентов).
 * Здание добычи, пока строится, рисуется тускло-серым с прогресс-баром
 * вместо обычного вида — см. drawUnderConstruction. У всех — полоска
 * здоровья над ними и (для выделенных юнитов) кольцо подсветки.
 * Приоритет 10 — выполняется после InterpolationSystem (приоритет 0),
 * чтобы рисовать уже посчитанную на этот кадр позицию.
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
    private static final ComponentMapper<ConstructionComponent> CONSTRUCTION =
            ComponentMapper.getFor(ConstructionComponent.class);

    private static final Color[] PLAYER_COLORS = {Color.SKY, Color.ORANGE};
    private static final Color SELECTION_RING_COLOR = Color.WHITE;
    private static final Color ARCHER_MARKER_COLOR = Color.WHITE;
    private static final float ARCHER_MARKER_RADIUS = GameConstants.UNIT_RADIUS * 0.4f;

    private static final float SELECTION_RING_RADIUS = GameConstants.UNIT_RADIUS + 3f;

    private static final Color HQ_STAR_COLOR = Color.GOLD;
    private static final Color ARCHER_ROOF_COLOR = Color.WHITE;
    private static final Color IRON_MINE_MARKER_COLOR = new Color(0.55f, 0.35f, 0.2f, 1f); // тот же ржавый цвет, что у месторождений
    private static final Color POWER_PLANT_MARKER_COLOR = Color.YELLOW;
    private static final Color UNDER_CONSTRUCTION_COLOR = Color.GRAY;

    private static final float HEALTH_BAR_HEIGHT = 4f;
    private static final float UNIT_HEALTH_BAR_WIDTH = 24f;
    private static final float UNIT_HEALTH_BAR_Y_OFFSET = 18f;
    private static final float BUILDING_HEALTH_BAR_Y_MARGIN = 10f;

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
            drawBuilding(entity, position, owner, health);
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

    private void drawBuilding(Entity entity, PositionComponent position, OwnerComponent owner, HealthComponent health) {
        float halfWidth = BuildingSizes.halfWidth(entity);
        float halfHeight = BuildingSizes.halfHeight(entity);

        ConstructionComponent construction = CONSTRUCTION.get(entity);
        if (construction != null) {
            drawUnderConstruction(position, halfWidth, halfHeight, construction);
            drawHealthBar(position, health, halfHeight + BUILDING_HEALTH_BAR_Y_MARGIN, halfWidth * 2f);
            return;
        }

        shapeRenderer.setColor(PLAYER_COLORS[owner.playerId % PLAYER_COLORS.length]);
        shapeRenderer.rect(
                position.position.x - halfWidth,
                position.position.y - halfHeight,
                halfWidth * 2f,
                halfHeight * 2f);

        BuildingType type = BUILDING.get(entity).type;
        switch (type) {
            case ARCHER_BARRACKS:
                drawRoofCap(position.position.x, position.position.y + halfHeight, halfWidth);
                break;
            case IRON_MINE:
                drawIronMineMarker(position.position.x, position.position.y, Math.min(halfWidth, halfHeight));
                break;
            case POWER_PLANT:
                drawPowerPlantMarker(position.position.x, position.position.y, Math.min(halfWidth, halfHeight));
                break;
            case IRON_STORAGE:
                drawIronStorageMarker(position.position.x, position.position.y, Math.min(halfWidth, halfHeight));
                break;
            case HOME:
            default:
                drawStar(position.position.x, position.position.y, Math.min(halfWidth, halfHeight) * 0.6f);
                break;
        }

        drawHealthBar(position, health, halfHeight + BUILDING_HEALTH_BAR_Y_MARGIN, halfWidth * 2f);
    }

    /** Стройка (шахта или электростанция) — тускло-серый квадрат вместо цвета игрока (здание ещё не работает) плюс прогресс-бар. */
    private void drawUnderConstruction(PositionComponent position, float halfWidth, float halfHeight,
                                        ConstructionComponent construction) {
        shapeRenderer.setColor(UNDER_CONSTRUCTION_COLOR);
        shapeRenderer.rect(
                position.position.x - halfWidth,
                position.position.y - halfHeight,
                halfWidth * 2f,
                halfHeight * 2f);

        float fraction = MathUtils.clamp(1f - construction.remaining / construction.totalTime, 0f, 1f);
        float barWidth = halfWidth * 2f;
        float barY = position.position.y - halfHeight - 10f; // под зданием, а не над ним — там уже полоска здоровья

        shapeRenderer.setColor(Color.DARK_GRAY);
        shapeRenderer.rect(position.position.x - halfWidth, barY, barWidth, HEALTH_BAR_HEIGHT);
        shapeRenderer.setColor(Color.GOLD);
        shapeRenderer.rect(position.position.x - halfWidth, barY, barWidth * fraction, HEALTH_BAR_HEIGHT);
    }

    /** Маленький ромб в цвете месторождений — единственное, что отличает шахту железа от дома/казармы на глаз. */
    private void drawIronMineMarker(float cx, float cy, float halfSize) {
        float markerHalf = halfSize * 0.5f;
        shapeRenderer.setColor(IRON_MINE_MARKER_COLOR);
        shapeRenderer.triangle(cx - markerHalf, cy, cx, cy + markerHalf, cx + markerHalf, cy);
        shapeRenderer.triangle(cx - markerHalf, cy, cx, cy - markerHalf, cx + markerHalf, cy);
    }

    /** Жёлтый кружок — единственное, что отличает электростанцию от дома (та же форма 2x2, но с этим значком) на глаз. */
    private void drawPowerPlantMarker(float cx, float cy, float halfSize) {
        shapeRenderer.setColor(POWER_PLANT_MARKER_COLOR);
        shapeRenderer.circle(cx, cy, halfSize * 0.5f);
    }

    /** Маленький квадрат в том же ржавом цвете, что у шахты/месторождений (оба про железо) — но квадрат, не ромб, чтобы не путать с шахтой. */
    private void drawIronStorageMarker(float cx, float cy, float halfSize) {
        float markerHalf = halfSize * 0.4f;
        shapeRenderer.setColor(IRON_MINE_MARKER_COLOR);
        shapeRenderer.rect(cx - markerHalf, cy - markerHalf, markerHalf * 2f, markerHalf * 2f);
    }

    /**
     * Шестиконечная звезда (два наложенных треугольника) — простая
     * геометрия, которую можно нарисовать двумя filled-треугольниками, без
     * отдельного прохода ShapeType.Line или сложной "полигон-веером"
     * логики для настоящей пятиконечной звезды.
     */
    private void drawStar(float cx, float cy, float radius) {
        float tall = radius * 0.8660254f; // radius * sqrt(3)/2
        float half = radius * 0.5f;

        shapeRenderer.setColor(HQ_STAR_COLOR);
        shapeRenderer.triangle(cx, cy + radius, cx - tall, cy - half, cx + tall, cy - half);
        shapeRenderer.triangle(cx, cy - radius, cx + tall, cy + half, cx - tall, cy + half);
    }

    /** "Крышечка" — залитый треугольник поверх верхней грани здания, силуэтом похожий на двускатную крышу. */
    private void drawRoofCap(float cx, float baseY, float halfWidth) {
        float peakHeight = halfWidth;
        shapeRenderer.setColor(ARCHER_ROOF_COLOR);
        shapeRenderer.triangle(cx - halfWidth, baseY, cx + halfWidth, baseY, cx, baseY + peakHeight);
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
