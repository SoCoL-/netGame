package ru.socol.supreme.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import ru.socol.supreme.components.SelectedComponent;
import ru.socol.supreme.components.TurretDisplayComponent;
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
import ru.socol.supreme.shared.components.WreckComponent;

/**
 * Рисует каждую сущность. Наземная техника (WARRIOR/ARCHER/BUILDER) —
 * прямоугольный корпус, повёрнутый по направлению движения, и отдельная
 * башня-треугольник поверх него, доворачивающаяся на цель атаки (см.
 * drawGroundVehicle/TurretDisplayComponent). Авиация (SCOUT,
 * ATTACK_AIRCRAFT — несмотря на "разведчик" в названии, см. javadoc
 * processEntity) — по-прежнему кружком с треугольником-курсом поверх.
 * У стрелка и строителя ещё маленькая метка на корпусе (белая точка и
 * серый квадратик соответственно), у воина — нет. Здание — прямоугольником
 * нужной формы (дом 2x2 клетки с золотой звездой, казарма стрелков 1x2 с
 * белой "крышечкой", шахта железа 1x1 с ржаво-коричневым ромбом,
 * электростанция 2x2 с жёлтым кружком — единственный способ различить
 * их на глаз, раз цвет у всех один и тот же — цвет игрока). Какой значок
 * и какого размера рисовать, решает BuildingComponent.type —
 * единственный источник истины "какое это здание" (BuildingSizes для
 * размера, switch по type здесь для значка, не отдельные проверки
 * компонентов). Здание добычи, пока строится, рисуется тускло-серым с
 * прогресс-баром вместо обычного вида — см. drawUnderConstruction. У
 * всех — полоска здоровья над ними и (для выделенных юнитов) кольцо
 * подсветки. Приоритет 10 — выполняется после InterpolationSystem
 * (приоритет 0), чтобы рисовать уже посчитанную на этот кадр позицию.
 *
 * TURRET (турель) — единственное исключение из "здание = прямоугольник":
 * готовая турель рисуется drawTurretBuilding — ромб (drawDiamond) вместо
 * прямоугольника плюс доворачивающаяся башня-треугольник поверх, той же
 * механикой (TurretDisplayComponent/TURRET_COLOR/drawHeadingTriangleMarker),
 * что и у наземной техники. Пока строится — обычный вид "стройки", как у
 * любого здания, см. её же диспетчеризацию в processEntity.
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
    private static final ComponentMapper<TurretDisplayComponent> TURRET_DISPLAY =
            ComponentMapper.getFor(TurretDisplayComponent.class);
    private static final ComponentMapper<WreckComponent> WRECK =
            ComponentMapper.getFor(WreckComponent.class);

    // Публичный — GameScreen переиспользует те же цвета для стратегических
    // значков (drawStrategicIcons), чтобы кроссфейд тактический/
    // стратегический вид не менял ещё и цвет заодно с формой/размером.
    public static final Color[] PLAYER_COLORS = {Color.SKY, Color.ORANGE};
    private static final Color SELECTION_RING_COLOR = Color.WHITE;
    private static final Color ARCHER_MARKER_COLOR = Color.WHITE;
    private static final float ARCHER_MARKER_RADIUS = GameConstants.UNIT_RADIUS * 0.4f;
    private static final Color BUILDER_MARKER_COLOR = Color.LIGHT_GRAY; // тот же цвет, что у "стройки" (UNDER_CONSTRUCTION_COLOR) — тематическая связь

    /**
     * Цвет башни наземной техники (drawGroundVehicle) — намеренно НЕ
     * playerColor, а один фиксированный тёмно-серый на всех игроков:
     * иначе башня, будучи того же цвета, что и корпус, визуально
     * сливалась бы с ним в один силуэт и доворот башни на цель было бы
     * трудно заметить на глаз. Корпус (drawRotatedRect) по-прежнему
     * красится в playerColor — по нему различают, чей юнит.
     */
    private static final Color TURRET_COLOR = Color.DARK_GRAY;
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

    // Наземная техника (WARRIOR/ARCHER/BUILDER — не SCOUT, см. javadoc
    // processEntity, почему разведчик тут авиация) — прямоугольный
    // корпус вместо круга, ориентированный по DirectionComponent
    // .direction (drawGroundVehicle/drawRotatedRect), и отдельно
    // поворачивающаяся башня-треугольник поверх него (TurretDisplayComponent).
    private static final float HULL_HALF_LENGTH = GameConstants.UNIT_RADIUS * 1.3f;
    private static final float HULL_HALF_WIDTH = GameConstants.UNIT_RADIUS * 0.8f;
    // Побольше, чем у круглых юнитов (UNIT_RADIUS + 3) — иначе кольцо
    // выделения обрезало бы углы прямоугольного корпуса на диагональных
    // поворотах (диагональ корпуса — sqrt(HULL_HALF_LENGTH^2 +
    // HULL_HALF_WIDTH^2), примерно 15.3 при текущих множителях).
    private static final float GROUND_SELECTION_RING_RADIUS = 17f;
    // "Нос" треугольника башни — дуло, откуда визуально вылетает снаряд
    // (см. GameScreen.onProjectileFired) — длиннее половины корпуса,
    // чтобы торчать за его край стволом, как у настоящей техники;
    // "хвост" короче и уже — просто чтобы силуэт читался как треугольник.
    private static final float TURRET_BARREL_LENGTH = GameConstants.UNIT_RADIUS * 1.6f;
    private static final float TURRET_REAR_LENGTH = GameConstants.UNIT_RADIUS * 0.5f;
    private static final float TURRET_HALF_WIDTH = GameConstants.UNIT_RADIUS * 0.35f;

    private static final Color HQ_STAR_COLOR = Color.GOLD;
    private static final Color ARCHER_ROOF_COLOR = Color.WHITE;
    private static final Color IRON_MINE_MARKER_COLOR = new Color(0.55f, 0.35f, 0.2f, 1f); // тот же ржавый цвет, что у месторождений
    private static final Color POWER_PLANT_MARKER_COLOR = Color.YELLOW;
    // Не жёлтый и не голубой (Color.SKY) — оба уже заняты (станция и
    // цвет игрока 1 соответственно), маркер на его фоне был бы почти
    // невидим.
    private static final Color AIRCRAFT_FACTORY_MARKER_COLOR = Color.CYAN;
    private static final Color UNDER_CONSTRUCTION_COLOR = Color.GRAY;
    /** Обломки на суше — тускло-ржавый, чтобы не путались ни с одним цветом игрока (SKY/ORANGE) и не выглядели как здание. */
    private static final Color WRECK_COLOR = Color.valueOf("6B5B4B");
    /** Обломки под водой (WreckComponent.underwater) — темнее и холоднее, чтобы читалось "на дне", а не "на суше в тени". */
    private static final Color WRECK_UNDERWATER_COLOR = Color.valueOf("35465A");

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
            BuildingComponent buildingComponent = BUILDING.get(entity);
            // Турель — единственное здание с собственным силуэтом (ромб +
            // доворачивающаяся башня, как у наземной техники), пока она
            // готова. Под стройкой она выглядит как любое другое здание —
            // тускло-серый квадрат с прогресс-баром (drawBuilding сама
            // рисует его до switch по типу, см. её javadoc), отдельный вид
            // "строящейся турели" не нужен, ромб/башня появляются сразу,
            // как только CONSTRUCTION.get(entity) == null.
            if (buildingComponent.type == BuildingType.TURRET && CONSTRUCTION.get(entity) == null) {
                drawTurretBuilding(entity, position, owner, health);
            } else if (buildingComponent.type == BuildingType.WRECK) {
                // Обломки — не настоящее здание и не принадлежат никому
                // (OwnerComponent.playerId == GameConstants.NEUTRAL_OWNER_ID,
                // см. javadoc WreckComponent), поэтому не могут пойти через
                // drawBuilding — та красит корпус в PLAYER_COLORS[owner
                // .playerId % ...], что упало бы с отрицательным индексом.
                drawWreck(entity, position, health);
            } else {
                drawBuilding(entity, position, owner, health);
            }
            return;
        }

        UnitTypeComponent unitTypeComponent = UNIT_TYPE.get(entity);
        UnitType type = unitTypeComponent != null ? unitTypeComponent.type : null;
        // "Наземная техника" — только WARRIOR/ARCHER/BUILDER (turnRadius
        // == 0, см. GameServer.createUnit). SCOUT, несмотря на название,
        // и ATTACK_AIRCRAFT — авиация (обоих производит AIRCRAFT_FACTORY,
        // см. BuildingDefinitions, у обоих ненулевой turnRadius) — их
        // отрисовка (круг + треугольник-курс) этой веткой не тронута.
        boolean groundVehicle = type == UnitType.WARRIOR || type == UnitType.ARCHER || type == UnitType.BUILDER;

        // Подсветка выделения рисуется под юнитом более крупным кругом —
        // из-под основного кружка/корпуса выглядывает как обводка, без
        // отдельного ShapeType.Line-прохода (ShapeRenderer не позволяет
        // мешать типы фигур внутри одного begin()/end()). Зданий это не
        // касается — их нельзя выделить (см. GameScreen), SelectedComponent
        // на них не бывает. У наземной техники кольцо пошире — иначе на
        // некоторых углах поворота прямоугольный корпус вылезал бы за его
        // пределы (см. javadoc GROUND_SELECTION_RING_RADIUS).
        if (SELECTED.has(entity)) {
            setColor(SELECTION_RING_COLOR);
            float ringRadius = groundVehicle ? GROUND_SELECTION_RING_RADIUS : SELECTION_RING_RADIUS;
            shapeRenderer.circle(position.position.x, position.position.y, ringRadius);
        }

        Color playerColor = PLAYER_COLORS[owner.playerId % PLAYER_COLORS.length];

        if (groundVehicle) {
            drawGroundVehicle(entity, position, playerColor, type);
        } else {
            setColor(playerColor);
            shapeRenderer.circle(position.position.x, position.position.y, GameConstants.UNIT_RADIUS);

            // Треугольник по курсу — только у авиации (см. выше, почему
            // разведчик тут тоже авиация), у обычной наземной техники
            // курса в этом смысле нет вовсе, ей занимается drawGroundVehicle.
            if (type == UnitType.SCOUT) {
                drawHeadingTriangleMarker(entity, position, SCOUT_MARKER_COLOR);
            } else if (type == UnitType.ATTACK_AIRCRAFT) {
                drawHeadingTriangleMarker(entity, position, ATTACK_AIRCRAFT_MARKER_COLOR);
            }
        }

        drawHealthBar(position, health, UNIT_HEALTH_BAR_Y_OFFSET, UNIT_HEALTH_BAR_WIDTH);
    }

    /**
     * Наземная техника (воин/стрелок/строитель) — прямоугольный корпус
     * вместо круга, повёрнутый по направлению движения
     * (DirectionComponent.direction — куда юнит реально сейчас едет, не
     * куда "смотрит" произвольно), и отдельная башня-треугольник поверх
     * него, повёрнутая по TurretDisplayComponent — доворачивается на
     * цель атаки независимо от корпуса, TurretAimSystem считает это на
     * сервере (см. её javadoc и TurretComponent), клиент только
     * отображает уже готовый угол. Башня рисуется фиксированным
     * тёмно-серым (TURRET_COLOR), а не playerColor, как корпус — чтобы
     * их было видно раздельно, а не одним слитным силуэтом. Вершина
     * треугольника — дуло, откуда визуально вылетает снаряд (см.
     * GameScreen.onProjectileFired).
     * Маленькая метка типа (белая точка у стрелка, серый квадратик у
     * строителя — как и раньше) рисуется на корпусе ПОД башней, у воина
     * по-прежнему нет отдельной метки.
     */
    private void drawGroundVehicle(Entity entity, PositionComponent position, Color playerColor, UnitType type) {
        DirectionComponent bodyDirection = DIRECTION.get(entity);
        float hullDx = bodyDirection != null ? bodyDirection.direction.x : 0f;
        float hullDy = bodyDirection != null ? bodyDirection.direction.y : 0f;
        if (hullDx == 0f && hullDy == 0f) {
            hullDx = 1f; // ещё ни разу не двигался — направление не определено, берём любое
        }

        setColor(playerColor);
        drawRotatedRect(position.position.x, position.position.y, hullDx, hullDy, HULL_HALF_LENGTH, HULL_HALF_WIDTH);

        if (type == UnitType.ARCHER) {
            setColor(ARCHER_MARKER_COLOR);
            shapeRenderer.circle(position.position.x, position.position.y, ARCHER_MARKER_RADIUS);
        } else if (type == UnitType.BUILDER) {
            setColor(BUILDER_MARKER_COLOR);
            float half = BUILDER_MARKER_HALF_SIZE;
            shapeRenderer.rect(position.position.x - half, position.position.y - half, half * 2f, half * 2f);
        }

        TurretDisplayComponent turretDisplay = TURRET_DISPLAY.get(entity);
        float turretDx = turretDisplay != null ? turretDisplay.dirX : 0f;
        float turretDy = turretDisplay != null ? turretDisplay.dirY : 0f;
        if (turretDx == 0f && turretDy == 0f) {
            // Сервер ещё не прислал ни одного снапшота с осмысленным
            // углом (самый первый кадр после создания юнита) — рисуем
            // башню по корпусу, а не в никуда.
            turretDx = hullDx;
            turretDy = hullDy;
        }
        drawHeadingTriangleMarker(position, turretDx, turretDy, TURRET_COLOR,
                TURRET_BARREL_LENGTH, TURRET_REAR_LENGTH, TURRET_HALF_WIDTH);
    }

    /**
     * Заполненный прямоугольник с центром (cx, cy), повёрнутый так, что
     * (dx, dy) — направление его длинной оси — корпус наземной техники
     * (drawGroundVehicle). ShapeRenderer не умеет рисовать повёрнутый
     * прямоугольник одним вызовом — раскладываем на два треугольника по
     * вершинам, тот же приём, что и у звезды/маркеров ниже. (dx, dy)
     * должен быть единичным вектором — иначе halfLength/halfWidth
     * означали бы не то, что заявлено в имени.
     */
    private void drawRotatedRect(float cx, float cy, float dx, float dy, float halfLength, float halfWidth) {
        float perpX = -dy;
        float perpY = dx;
        float frontLeftX = cx + dx * halfLength + perpX * halfWidth;
        float frontLeftY = cy + dy * halfLength + perpY * halfWidth;
        float frontRightX = cx + dx * halfLength - perpX * halfWidth;
        float frontRightY = cy + dy * halfLength - perpY * halfWidth;
        float rearRightX = cx - dx * halfLength - perpX * halfWidth;
        float rearRightY = cy - dy * halfLength - perpY * halfWidth;
        float rearLeftX = cx - dx * halfLength + perpX * halfWidth;
        float rearLeftY = cy - dy * halfLength + perpY * halfWidth;

        shapeRenderer.triangle(frontLeftX, frontLeftY, frontRightX, frontRightY, rearRightX, rearRightY);
        shapeRenderer.triangle(frontLeftX, frontLeftY, rearRightX, rearRightY, rearLeftX, rearLeftY);
    }

    /**
     * Готовая (не строящаяся, см. вызывающий код в processEntity) турель —
     * корпус-ромб (drawDiamond) в playerColor вместо прямоугольника
     * обычного здания, и поверх него та же доворачивающаяся
     * башня-треугольник, что и у наземной техники (drawGroundVehicle) —
     * тот же TurretDisplayComponent/TURRET_COLOR/drawHeadingTriangleMarker,
     * сервер шлёт угол одинаково для обоих случаев (см. javadoc
     * TurretComponent — компонент общий, не завязан на юнит/здание).
     * Полоска здоровья — как у обычного здания (по halfWidth/halfHeight),
     * не как у юнита: у турели, в отличие от наземного юнита, размер не
     * фиксированный UNIT_RADIUS, а свой, из BuildingDefinitions.
     */
    private void drawTurretBuilding(Entity entity, PositionComponent position, OwnerComponent owner, HealthComponent health) {
        float halfWidth = BuildingSizes.halfWidth(entity);
        float halfHeight = BuildingSizes.halfHeight(entity);

        setColor(PLAYER_COLORS[owner.playerId % PLAYER_COLORS.length]);
        drawDiamond(position.position.x, position.position.y, halfWidth, halfHeight);

        TurretDisplayComponent turretDisplay = TURRET_DISPLAY.get(entity);
        float turretDx = turretDisplay != null ? turretDisplay.dirX : 0f;
        float turretDy = turretDisplay != null ? turretDisplay.dirY : 0f;
        drawHeadingTriangleMarker(position, turretDx, turretDy, TURRET_COLOR,
                TURRET_BARREL_LENGTH, TURRET_REAR_LENGTH, TURRET_HALF_WIDTH);

        drawHealthBar(position, health, halfHeight + BUILDING_HEALTH_BAR_Y_MARGIN, halfWidth * 2f);
    }

    /**
     * Ромб с центром (cx, cy) — вершины на halfWidth/halfHeight от центра
     * по осям (не повёрнутый квадрат в честном смысле, а "спрайт-ромб":
     * ширина и высота задаются раздельно, как у прямоугольника обычного
     * здания, только углы по осям, а не по сторонам). Турель никогда не
     * двигается (см. GameServer.spawnBuilding), поворачивать сам ромб не
     * нужно — в отличие от drawRotatedRect у наземной техники.
     */
    private void drawDiamond(float cx, float cy, float halfWidth, float halfHeight) {
        shapeRenderer.triangle(cx, cy + halfHeight, cx + halfWidth, cy, cx, cy - halfHeight);
        shapeRenderer.triangle(cx, cy + halfHeight, cx - halfWidth, cy, cx, cy - halfHeight);
    }

    /**
     * Обломки погибшего юнита (BuildingType.WRECK, см. её javadoc) —
     * крестообразный силуэт фиксированного цвета (WRECK_COLOR/
     * WRECK_UNDERWATER_COLOR — НЕ playerColor, у обломков нет владельца),
     * а не ровный прямоугольник обычного здания, чтобы на глаз не
     * путались с настоящей постройкой. ironStock — на самом деле
     * HealthComponent той же сущности: currentHealth/maxHealth значат
     * "сколько железа осталось / было изначально", не HP (см. javadoc
     * WreckComponent) — drawHealthBar рисует ту же долю current/max, что
     * и всегда, ему всё равно, что именно она значит, так что полоска
     * честно показывает, сколько ещё осталось собрать.
     */
    private void drawWreck(Entity entity, PositionComponent position, HealthComponent ironStock) {
        float halfWidth = BuildingSizes.halfWidth(entity);
        float halfHeight = BuildingSizes.halfHeight(entity);

        WreckComponent wreck = WRECK.get(entity);
        setColor(wreck != null && wreck.underwater ? WRECK_UNDERWATER_COLOR : WRECK_COLOR);
        shapeRenderer.rect(
                position.position.x - halfWidth, position.position.y - halfHeight * 0.35f,
                halfWidth * 2f, halfHeight * 0.7f);
        shapeRenderer.rect(
                position.position.x - halfWidth * 0.35f, position.position.y - halfHeight,
                halfWidth * 0.7f, halfHeight * 2f);

        drawHealthBar(position, ironStock, halfHeight + BUILDING_HEALTH_BAR_Y_MARGIN, halfWidth * 2f);
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
     * Треугольник по направлению полёта авиации (DirectionComponent
     * .direction, его поддерживает AircraftMovementSystem) — тонкая
     * обёртка над общей drawHeadingTriangleMarker(position, dx, dy, ...),
     * которая читает направление из сущности сама и подставляет
     * фиксированные AIRCRAFT_MARKER_LENGTH/WIDTH — только чтобы не
     * дублировать эти два вызова с одинаковыми размерами в двух местах.
     */
    private void drawHeadingTriangleMarker(Entity entity, PositionComponent position, Color color) {
        DirectionComponent unitDirection = DIRECTION.get(entity);
        float dx = unitDirection != null ? unitDirection.direction.x : 1f;
        float dy = unitDirection != null ? unitDirection.direction.y : 0f;
        drawHeadingTriangleMarker(position, dx, dy, color,
                AIRCRAFT_MARKER_LENGTH, AIRCRAFT_MARKER_LENGTH * 0.5f, AIRCRAFT_MARKER_WIDTH);
    }

    /**
     * Треугольник по направлению (dx, dy) — общий для курса авиации
     * (drawHeadingTriangleMarker(Entity, ...) выше) и башни наземной
     * техники (drawGroundVehicle): "нос" на расстоянии noseLength от
     * центра — нос самолёта или дуло орудия, смотря по вызывающему коду,
     * "хвост" на tailLength в противоположную сторону, раздвинутый на
     * halfWidth перпендикулярно — тот же приём, что и раньше, только без
     * жёстко зашитых размеров.
     */
    private void drawHeadingTriangleMarker(PositionComponent position, float dx, float dy, Color color,
                                            float noseLength, float tailLength, float halfWidth) {
        if (dx == 0f && dy == 0f) {
            dx = 1f; // направление не определено — берём любое
        }
        float perpX = -dy;
        float perpY = dx;
        float noseX = position.position.x + dx * noseLength;
        float noseY = position.position.y + dy * noseLength;
        float tailX = position.position.x - dx * tailLength;
        float tailY = position.position.y - dy * tailLength;
        setColor(color);
        shapeRenderer.triangle(
                noseX, noseY,
                tailX + perpX * halfWidth, tailY + perpY * halfWidth,
                tailX - perpX * halfWidth, tailY - perpY * halfWidth);
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
