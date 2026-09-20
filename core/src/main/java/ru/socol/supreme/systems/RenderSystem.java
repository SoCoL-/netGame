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
import ru.socol.supreme.shared.components.DirectionComponent;
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
    private static final ComponentMapper<DirectionComponent> DIRECTION =
            ComponentMapper.getFor(DirectionComponent.class);
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

    // Публичный — GameScreen переиспользует те же цвета для стратегических
    // значков (drawStrategicIcons), чтобы кроссфейд тактический/
    // стратегический вид не менял ещё и цвет заодно с формой/размером.
    public static final Color[] PLAYER_COLORS = {Color.SKY, Color.ORANGE};
    private static final Color SELECTION_RING_COLOR = Color.WHITE;
    private static final Color ARCHER_MARKER_COLOR = Color.WHITE;
    private static final float ARCHER_MARKER_RADIUS = GameConstants.UNIT_RADIUS * 0.4f;
    private static final Color BUILDER_MARKER_COLOR = Color.LIGHT_GRAY; // тот же цвет, что у "стройки" (UNDER_CONSTRUCTION_COLOR) — тематическая связь
    private static final float BUILDER_MARKER_HALF_SIZE = GameConstants.UNIT_RADIUS * 0.35f;
    // Вся авиация — единственные юниты с настоящим курсом (см.
    // AircraftMovementSystem), поэтому метка не просто цветная точка, а
    // треугольник по направлению полёта (drawHeadingTriangleMarker),
    // общая форма на любой тип, цвет свой у каждого.
    private static final float AIRCRAFT_MARKER_LENGTH = GameConstants.UNIT_RADIUS * 0.9f;
    private static final float AIRCRAFT_MARKER_WIDTH = GameConstants.UNIT_RADIUS * 0.6f;
    private static final Color SCOUT_MARKER_COLOR = Color.YELLOW;
    private static final Color ATTACK_AIRCRAFT_MARKER_COLOR = Color.RED;

    private static final float SELECTION_RING_RADIUS = GameConstants.UNIT_RADIUS + 3f;

    private static final Color HQ_STAR_COLOR = Color.GOLD;
    private static final Color ARCHER_ROOF_COLOR = Color.WHITE;
    private static final Color IRON_MINE_MARKER_COLOR = new Color(0.55f, 0.35f, 0.2f, 1f); // тот же ржавый цвет, что у месторождений
    private static final Color POWER_PLANT_MARKER_COLOR = Color.YELLOW;
    // Не жёлтый и не голубой (Color.SKY) — оба уже заняты (станция и
    // цвет игрока 1 соответственно), маркер на его фоне был бы почти
    // невидим.
    private static final Color AIRCRAFT_FACTORY_MARKER_COLOR = Color.CYAN;
    private static final Color UNDER_CONSTRUCTION_COLOR = Color.GRAY;

    private static final float HEALTH_BAR_HEIGHT = 4f;
    private static final float UNIT_HEALTH_BAR_WIDTH = 24f;
    private static final float UNIT_HEALTH_BAR_Y_OFFSET = 18f;
    private static final float BUILDING_HEALTH_BAR_Y_MARGIN = 10f;

    private final ShapeRenderer shapeRenderer;

    // Множитель альфы всего тактического слоя — 1 в обычном режиме, тает до
    // 0 при переходе в стратегический вид на сильном отдалении камеры (см.
    // GameScreen.strategicFactor/render()). Выставляется GameScreen перед
    // каждым engine.update(), а не читается отсюда напрямую — у ashley-
    // систем нет доступа к камере клиента, да и незачем: одно поле проще,
    // чем протаскивать сюда всю камеру ради единственного числа.
    private float renderAlpha = 1f;

    // Туман войны для чужих юнитов/зданий — своя сущность (owner.playerId
    // == localPlayerId) сквозь туман видна всегда, чужая рисуется только
    // если её клетка сейчас просвечена. null, пока GameScreen ещё не
    // получил ни одного FogSnapshot (в начале подключения) — тогда фильтр
    // выключен, рисуем всех, как и раньше. Оба поля выставляет GameScreen
    // перед каждым engine.update() (см. setFogVisibility) — то же самое
    // "протащить одно значение, а не всю камеру/клиента" решение, что и у
    // renderAlpha чуть выше.
    private boolean[] fogRevealed;
    private int localPlayerId = -1;

    public RenderSystem(ShapeRenderer shapeRenderer) {
        super(Family.all(PositionComponent.class, OwnerComponent.class, HealthComponent.class).get(), 10);
        this.shapeRenderer = shapeRenderer;
    }

    public void setRenderAlpha(float renderAlpha) {
        this.renderAlpha = renderAlpha;
    }

    public void setFogVisibility(boolean[] fogRevealed, int localPlayerId) {
        this.fogRevealed = fogRevealed;
        this.localPlayerId = localPlayerId;
    }

    /**
     * Чужая сущность, чья клетка сейчас не просвечена туманом войны, вообще
     * не рисуется — раньше она была видна сквозь полупрозрачную серую
     * плашку тумана (FOG_COLOR.a меньше 1, это же и часть сглаживания),
     * что превращало туман в чисто косметический эффект. Свои сущности
     * туман не трогает никогда — сравнение идёт напрямую по
     * owner.playerId == localPlayerId.
     */
    private boolean isHiddenByFog(OwnerComponent owner, PositionComponent position) {
        if (fogRevealed == null || owner.playerId == localPlayerId) {
            return false;
        }
        int cellX = (int) (position.position.x / GameConstants.FOG_GRID_CELL_SIZE);
        int cellY = (int) (position.position.y / GameConstants.FOG_GRID_CELL_SIZE);
        if (cellX < 0 || cellX >= GameConstants.FOG_GRID_WIDTH || cellY < 0 || cellY >= GameConstants.FOG_GRID_HEIGHT) {
            return true; // координата вне сетки тумана — не должно происходить, но безопаснее скрыть, чем показать
        }
        int index = cellY * GameConstants.FOG_GRID_WIDTH + cellX;
        return index >= fogRevealed.length || !fogRevealed[index];
    }

    @Override
    public void update(float deltaTime) {
        if (renderAlpha <= 0f) {
            return; // полностью прозрачно (чистый стратегический вид) — тактический слой можно не рисовать вовсе
        }
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        super.update(deltaTime);
        shapeRenderer.end();
    }

    /**
     * Обёртка над shapeRenderer.setColor, домножающая альфу цвета на
     * renderAlpha — единая точка, через которую проходит вообще любой цвет
     * в этой системе (см. замены ниже), чтобы кроссфейд тактический/
     * стратегический вид плавно затухал целиком, а не только у части фигур.
     * При renderAlpha=1 (обычный вид) ведёт себя как обычный setColor.
     */
    private void setColor(Color color) {
        shapeRenderer.setColor(color.r, color.g, color.b, color.a * renderAlpha);
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        PositionComponent position = POSITION.get(entity);
        OwnerComponent owner = OWNER.get(entity);
        HealthComponent health = HEALTH.get(entity);

        if (isHiddenByFog(owner, position)) {
            return;
        }

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
            setColor(SELECTION_RING_COLOR);
            shapeRenderer.circle(position.position.x, position.position.y, SELECTION_RING_RADIUS);
        }

        setColor(PLAYER_COLORS[owner.playerId % PLAYER_COLORS.length]);
        shapeRenderer.circle(position.position.x, position.position.y, GameConstants.UNIT_RADIUS);

        // Маленькая метка внутри (или, у разведчика, треугольник по
        // направлению полёта) — единственное, что отличает остальные
        // типы от воина (и друг от друга) на глаз: у всех одинаковый
        // размер и цвет круга иначе. Стрелок — белая точка, строитель —
        // серый квадратик (тот же цвет, что у "стройки" на зданиях —
        // тематическая связь), разведчик — жёлтый треугольник по курсу,
        // у воина метки нет вовсе.
        UnitTypeComponent unitType = UNIT_TYPE.get(entity);
        if (unitType != null && unitType.type == UnitType.ARCHER) {
            setColor(ARCHER_MARKER_COLOR);
            shapeRenderer.circle(position.position.x, position.position.y, ARCHER_MARKER_RADIUS);
        } else if (unitType != null && unitType.type == UnitType.BUILDER) {
            setColor(BUILDER_MARKER_COLOR);
            float half = BUILDER_MARKER_HALF_SIZE;
            shapeRenderer.rect(position.position.x - half, position.position.y - half, half * 2f, half * 2f);
        } else if (unitType != null && unitType.type == UnitType.SCOUT) {
            drawHeadingTriangleMarker(entity, position, SCOUT_MARKER_COLOR);
        } else if (unitType != null && unitType.type == UnitType.ATTACK_AIRCRAFT) {
            drawHeadingTriangleMarker(entity, position, ATTACK_AIRCRAFT_MARKER_COLOR);
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

        setColor(PLAYER_COLORS[owner.playerId % PLAYER_COLORS.length]);
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
                drawStorageMarker(position.position.x, position.position.y, Math.min(halfWidth, halfHeight), IRON_MINE_MARKER_COLOR);
                break;
            case ELECTRICITY_STORAGE:
                drawStorageMarker(position.position.x, position.position.y, Math.min(halfWidth, halfHeight), POWER_PLANT_MARKER_COLOR);
                break;
            case AIRCRAFT_FACTORY:
                drawWaveMarker(position.position.x, position.position.y, Math.min(halfWidth, halfHeight));
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
        setColor(UNDER_CONSTRUCTION_COLOR);
        shapeRenderer.rect(
                position.position.x - halfWidth,
                position.position.y - halfHeight,
                halfWidth * 2f,
                halfHeight * 2f);

        float fraction = MathUtils.clamp(1f - construction.remaining / construction.totalTime, 0f, 1f);
        float barWidth = halfWidth * 2f;
        float barY = position.position.y - halfHeight - 10f; // под зданием, а не над ним — там уже полоска здоровья

        setColor(Color.DARK_GRAY);
        shapeRenderer.rect(position.position.x - halfWidth, barY, barWidth, HEALTH_BAR_HEIGHT);
        setColor(Color.GOLD);
        shapeRenderer.rect(position.position.x - halfWidth, barY, barWidth * fraction, HEALTH_BAR_HEIGHT);
    }

    /** Маленький ромб в цвете месторождений — единственное, что отличает шахту железа от дома/казармы на глаз. */
    private void drawIronMineMarker(float cx, float cy, float halfSize) {
        float markerHalf = halfSize * 0.5f;
        setColor(IRON_MINE_MARKER_COLOR);
        shapeRenderer.triangle(cx - markerHalf, cy, cx, cy + markerHalf, cx + markerHalf, cy);
        shapeRenderer.triangle(cx - markerHalf, cy, cx, cy - markerHalf, cx + markerHalf, cy);
    }

    /** Жёлтый кружок — единственное, что отличает электростанцию от дома (та же форма 2x2, но с этим значком) на глаз. */
    private void drawPowerPlantMarker(float cx, float cy, float halfSize) {
        setColor(POWER_PLANT_MARKER_COLOR);
        shapeRenderer.circle(cx, cy, halfSize * 0.5f);
    }

    /** Маленький квадрат в том же ржавом цвете, что у шахты/месторождений (оба про железо) — но квадрат, не ромб, чтобы не путать с шахтой. */
    /** Маленький квадрат в цвете добываемого ресурса — единственное, что отличает хранилище (железа или электричества) от здания добычи того же ресурса (там ромб/кружок) на глаз. */
    private void drawStorageMarker(float cx, float cy, float halfSize, Color color) {
        float markerHalf = halfSize * 0.4f;
        setColor(color);
        shapeRenderer.rect(cx - markerHalf, cy - markerHalf, markerHalf * 2f, markerHalf * 2f);
    }

    /**
     * Треугольник по направлению полёта — общий для любого типа авиации с
     * настоящим курсом (DirectionComponent.direction, его поддерживает
     * AircraftMovementSystem), цвет передаёт вызывающий код, форма и
     * размер — общие (AIRCRAFT_MARKER_LENGTH/WIDTH).
     */
    private void drawHeadingTriangleMarker(Entity entity, PositionComponent position, Color color) {
        DirectionComponent unitDirection = DIRECTION.get(entity);
        float dx = unitDirection != null ? unitDirection.direction.x : 1f;
        float dy = unitDirection != null ? unitDirection.direction.y : 0f;
        if (dx == 0f && dy == 0f) {
            dx = 1f; // ещё ни разу не летал — направление не определено, берём любое
        }
        float perpX = -dy;
        float perpY = dx;
        float noseX = position.position.x + dx * AIRCRAFT_MARKER_LENGTH;
        float noseY = position.position.y + dy * AIRCRAFT_MARKER_LENGTH;
        float tailX = position.position.x - dx * AIRCRAFT_MARKER_LENGTH * 0.5f;
        float tailY = position.position.y - dy * AIRCRAFT_MARKER_LENGTH * 0.5f;
        setColor(color);
        shapeRenderer.triangle(
                noseX, noseY,
                tailX + perpX * AIRCRAFT_MARKER_WIDTH, tailY + perpY * AIRCRAFT_MARKER_WIDTH,
                tailX - perpX * AIRCRAFT_MARKER_WIDTH, tailY - perpY * AIRCRAFT_MARKER_WIDTH);
    }

    /**
     * Волна "~" — несколько коротких сегментов (rectLine), аппроксимирующих
     * синусоиду, тем же приёмом, что и пунктирная линия/голографический
     * луч стройки в GameScreen: ShapeRenderer не умеет кривые сам по себе,
     * но ломаная из достаточного числа коротких отрезков на глаз читается
     * как гладкая волна. Один полный период (сначала вверх, потом вниз) —
     * силуэтом похоже на "~".
     */
    private void drawWaveMarker(float cx, float cy, float halfSize) {
        float amplitude = halfSize * 0.35f;
        float width = halfSize * 1.6f;
        int segments = 12;

        setColor(AIRCRAFT_FACTORY_MARKER_COLOR);
        float startX = cx - width / 2f;
        float prevX = startX;
        float prevY = cy;
        for (int i = 1; i <= segments; i++) {
            float t = (float) i / segments;
            float x = startX + width * t;
            float y = cy + amplitude * MathUtils.sin(t * MathUtils.PI2);
            shapeRenderer.rectLine(prevX, prevY, x, y, 2.5f);
            prevX = x;
            prevY = y;
        }
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

        setColor(HQ_STAR_COLOR);
        shapeRenderer.triangle(cx, cy + radius, cx - tall, cy - half, cx + tall, cy - half);
        shapeRenderer.triangle(cx, cy - radius, cx + tall, cy + half, cx - tall, cy + half);
    }

    /** "Крышечка" — залитый треугольник поверх верхней грани здания, силуэтом похожий на двускатную крышу. */
    private void drawRoofCap(float cx, float baseY, float halfWidth) {
        float peakHeight = halfWidth;
        setColor(ARCHER_ROOF_COLOR);
        shapeRenderer.triangle(cx - halfWidth, baseY, cx + halfWidth, baseY, cx, baseY + peakHeight);
    }

    private void drawHealthBar(PositionComponent position, HealthComponent health, float yOffset, float barWidth) {
        float barX = position.position.x - barWidth / 2f;
        float barY = position.position.y + yOffset;
        // health.maxHealth, а не общая константа — у здания и юнита разный максимум.
        float healthFraction = MathUtils.clamp((float) health.currentHealth / health.maxHealth, 0f, 1f);

        setColor(Color.DARK_GRAY);
        shapeRenderer.rect(barX, barY, barWidth, HEALTH_BAR_HEIGHT);

        setColor(healthFraction > 0.3f ? Color.GREEN : Color.RED);
        shapeRenderer.rect(barX, barY, barWidth * healthFraction, HEALTH_BAR_HEIGHT);
    }
}
