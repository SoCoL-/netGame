package ru.socol.supreme;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.components.DebugPathComponent;
import ru.socol.supreme.components.SelectedComponent;
import ru.socol.supreme.network.GameClient;
import ru.socol.supreme.systems.InterpolationSystem;
import ru.socol.supreme.systems.RenderSystem;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.UnitComponent;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.WorldSnapshot;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Основной игровой экран: клиентский Ashley-движок, соединение с сервером и
 * обработка ввода.
 *
 * Управление:
 *  - WASD / стрелки            -> прокрутка камеры (карта 2000x2000, окно
 *                                 800x600 — целиком не помещается)
 *  - ЛКМ клик по своему юниту   -> выбрать только его
 *  - ЛКМ протяжка (рамка)       -> выбрать все свои юниты внутри рамки
 *    (здания рамкой не выделяются)
 *  - ЛКМ клик по своему зданию  -> открыть/закрыть панель постройки
 *    (повторный клик по нему же закрывает; клик куда-то ещё — тоже)
 *  - ЛКМ клик по пустому месту  -> снять выделение
 *  - ПКМ по вражескому юниту/зданию (при непустом выделении) -> атаковать
 *    им всем выделением сразу
 *  - ПКМ по пустому месту / своему юниту -> приказ на движение всем
 *    выделенным юнитам (несколько — расходятся сеткой вокруг точки клика)
 */
public class GameScreen extends InputAdapter implements Screen {

    private static final float DRAG_THRESHOLD = 6f; // world units — отличает клик от протяжки рамки
    private static final float MOVE_ORDER_SPACING = 24f; // world units между юнитами в сетке при групповом приказе
    private static final float UNIT_CLICK_RADIUS = 12f;
    private static final float BUILDING_CLICK_RADIUS = 40f; // здание крупнее юнита — и цель клика шире
    private static final float CAMERA_PAN_SPEED = 400f; // world units в секунду
    private static final Color WATER_COLOR = new Color(0.25f, 0.55f, 0.85f, 1f); // голубой
    private static final Color GRASS_COLOR = new Color(0.2f, 0.45f, 0.2f, 1f); // зелёный, трава
    private static final Color DEBUG_PATH_COLOR = Color.ORANGE;

    // Панель постройки — экранные (HUD) координаты, не мировые, см. hudCamera.
    private static final float PANEL_X = 20f;
    private static final float PANEL_Y = 20f;
    private static final float PANEL_WIDTH = 280f;
    private static final float PANEL_HEIGHT = 100f;
    private static final float QUEUE_BUTTON_X = PANEL_X + 15f;
    private static final float QUEUE_BUTTON_Y = PANEL_Y + 15f;
    private static final float QUEUE_BUTTON_SIZE = 40f;
    private static final float PROGRESS_BAR_X = QUEUE_BUTTON_X + QUEUE_BUTTON_SIZE + 15f;
    private static final float PROGRESS_BAR_WIDTH = 170f;
    private static final float PROGRESS_BAR_HEIGHT = 12f;

    // Чисто визуальный полёт стрелы — урон уже применён на сервере в момент
    // выстрела (см. ProjectileFiredEvent), скорость тут только для картинки.
    private static final float ARROW_SPEED = 600f; // world units в секунду
    private static final Color ARROW_COLOR = Color.WHITE;
    private static final float ARROW_VISUAL_LENGTH = 8f; // половина длины отрезка, изображающего стрелу

    private final Engine engine = new Engine();
    private final ShapeRenderer shapeRenderer = new ShapeRenderer();
    private final SpriteBatch spriteBatch = new SpriteBatch();
    private final BitmapFont font = new BitmapFont(); // крупный — для "VICTORY"/"Connecting..." по центру экрана
    private final BitmapFont uiFont = new BitmapFont(); // помельче — для панели постройки
    private final OrthographicCamera camera = new OrthographicCamera();
    // Отдельная неподвижная камера для HUD (панель постройки) — рисуется в
    // экранных координатах, не должна зависеть от прокрутки world-камеры.
    private final OrthographicCamera hudCamera = new OrthographicCamera();
    private final EntityFactory entityFactory = new EntityFactory(engine);
    private final GameClient client = new GameClient();

    private final Set<Integer> selectedUnitIds = new HashSet<>();
    private Integer selectedBuildingId = null;

    // Активные визуальные стрелы (см. ProjectileFiredEvent) — список сам себя
    // чистит по мере пролёта, отдельного лимита на размер не нужно: при
    // FIRE_RATE=4/сек и полёте <1 сек он в любой реалистичной игре остаётся
    // маленьким сам по себе.
    private final List<ArrowVisual> activeArrows = new ArrayList<>();

    private static final class ArrowVisual {
        final float fromX;
        final float fromY;
        final float toX;
        final float toY;
        final float duration;
        float elapsed;

        ArrowVisual(float fromX, float fromY, float toX, float toY) {
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            float distance = Vector2.dst(fromX, fromY, toX, toY);
            this.duration = Math.max(0.05f, distance / ARROW_SPEED);
        }
    }

    private boolean dragging = false;
    private final Vector3 dragStartWorld = new Vector3();
    private final Vector3 dragCurrentWorld = new Vector3();

    private boolean cameraInitialized = false;
    // Переключается клавишей ` (GRAVE) — см. keyDown. Заменяет заливку земли
    // сеткой клеток поиска пути и рисует маршруты движущихся юнитов.
    private boolean debugMode = false;
    private String gameOverText = null;
    // Пока не null — показываем этот текст вместо игры (окно уже открыто и
    // отрисовывается, само подключение идёт в фоне — см. GameClient.connect()).
    private String connectionStatusText = "Connecting...";

    public GameScreen(String serverHost) {
        camera.setToOrtho(false, 800, 600);
        hudCamera.setToOrtho(false, 800, 600);
        font.getData().setScale(3f);
        uiFont.getData().setScale(1.3f);

        engine.addSystem(new InterpolationSystem());
        engine.addSystem(new RenderSystem(shapeRenderer));

        client.connect(serverHost, new GameClient.GameClientListener() {
            @Override
            public void onJoinResponse(JoinResponse response) {
                // received() приходит из сетевого потока KryoNet — переносим
                // изменение состояния экрана в поток рендера.
                Gdx.app.postRunnable(() -> {
                    Gdx.app.log("Network", response.message);
                    connectionStatusText = response.accepted ? null : response.message;
                });
            }

            @Override
            public void onWorldSnapshot(WorldSnapshot snapshot) {
                Gdx.app.postRunnable(() -> {
                    entityFactory.applySnapshot(snapshot.units);
                    // Юниты, погибшие в этом снапшоте, уже удалены из
                    // entityFactory — вычищаем их id из выделения, чтобы
                    // не пытаться командовать мёртвыми.
                    selectedUnitIds.removeIf(unitId -> entityFactory.getEntity(unitId) == null);
                    if (selectedBuildingId != null && entityFactory.getEntity(selectedBuildingId) == null) {
                        selectedBuildingId = null; // здание пропало — закрываем панель
                    }
                    centerCameraOnOwnBuildingIfNeeded();
                });
            }

            @Override
            public void onError(ErrorResponse error) {
                Gdx.app.log("Network", "Error: " + error.message);
            }

            @Override
            public void onGameOver(GameOverMessage message) {
                Gdx.app.postRunnable(() -> {
                    if (message.draw) {
                        gameOverText = "DRAW";
                        font.setColor(Color.WHITE);
                    } else if (message.winnerPlayerId == client.getPlayerId()) {
                        gameOverText = "VICTORY";
                        font.setColor(Color.GREEN);
                    } else {
                        gameOverText = "DEFEAT";
                        font.setColor(Color.RED);
                    }
                    dragging = false;
                });
            }

            @Override
            public void onProjectileFired(ProjectileFiredEvent event) {
                // received() приходит из сетевого потока KryoNet — переносим
                // изменение activeArrows (читается в render()) в поток рендера.
                Gdx.app.postRunnable(() ->
                        activeArrows.add(new ArrowVisual(event.fromX, event.fromY, event.toX, event.toY)));
            }

            @Override
            public void onConnectFailed(String message) {
                // Уже вызывается на GL-потоке — GameClient сама делает
                // Gdx.app.postRunnable() перед вызовом этого метода.
                Gdx.app.log("Network", "Connect failed: " + message);
                connectionStatusText = "Failed to connect" + (message != null ? ": " + message : "");
            }
        });

        Gdx.input.setInputProcessor(this);
    }

    // ---- Камера ----

    private void centerCameraOnOwnBuildingIfNeeded() {
        if (cameraInitialized) {
            return;
        }
        for (Entity entity : engine.getEntities()) {
            if (entity.getComponent(BuildingComponent.class) == null) {
                continue;
            }
            OwnerComponent owner = entity.getComponent(OwnerComponent.class);
            if (owner != null && owner.playerId == client.getPlayerId()) {
                PositionComponent position = entity.getComponent(PositionComponent.class);
                camera.position.set(position.position.x, position.position.y, 0);
                cameraInitialized = true;
                return;
            }
        }
    }

    private void updateCamera(float delta) {
        float pan = CAMERA_PAN_SPEED * delta;
        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP)) {
            camera.position.y += pan;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            camera.position.y -= pan;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            camera.position.x -= pan;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            camera.position.x += pan;
        }

        float halfWidth = camera.viewportWidth / 2f;
        float halfHeight = camera.viewportHeight / 2f;
        camera.position.x = MathUtils.clamp(camera.position.x, halfWidth, GameConstants.MAP_WIDTH - halfWidth);
        camera.position.y = MathUtils.clamp(camera.position.y, halfHeight, GameConstants.MAP_HEIGHT - halfHeight);
    }

    // ---- Рендер ----

    @Override
    public void render(float delta) {
        updateCamera(delta);

        Gdx.gl.glClearColor(0.1f, 0.1f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        camera.update();
        hudCamera.update();
        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);

        drawGround(); // до engine.update() — юниты должны рисоваться поверх земли/воды, а не под ними

        engine.update(delta);

        updateArrows(delta);
        drawOverlayLines();
        drawArrows();
        if (debugMode) {
            drawDebugPaths();
        }

        if (gameOverText != null) {
            drawCenteredText(gameOverText);
        } else if (connectionStatusText != null) {
            font.setColor(Color.LIGHT_GRAY);
            drawCenteredText(connectionStatusText);
        } else if (selectedBuildingId != null) {
            drawProductionPanel();
        }
    }

    /** Базовый слой земли: заливка (обычный режим) или сетка клеток поиска пути (отладочный, см. keyDown). */
    private void drawGround() {
        if (debugMode) {
            drawDebugGrid();
        } else {
            drawFilledGround();
        }
    }

    private void drawFilledGround() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(GRASS_COLOR);
        shapeRenderer.rect(0, 0, GameConstants.MAP_WIDTH, GameConstants.MAP_HEIGHT);
        shapeRenderer.setColor(WATER_COLOR);
        shapeRenderer.rect(
                GameConstants.WATER_MIN_X,
                GameConstants.WATER_MIN_Y,
                GameConstants.WATER_MAX_X - GameConstants.WATER_MIN_X,
                GameConstants.WATER_MAX_Y - GameConstants.WATER_MIN_Y);
        shapeRenderer.end();
    }

    /**
     * Сетка клеток поиска пути — только грани, без заливки. Цвет клетки
     * решает Pathfinding.isWaterCell (та же классификация, что видит A*,
     * включая PATH_CLEARANCE-инфляцию — это отладочный вид внутреннего
     * состояния пасфайндера, а не просто перерисовка сырой геометрии воды).
     * Здания отдельным цветом не выделяются — они и так видны как обычные
     * отрисованные прямоугольники поверх сетки.
     */
    private void drawDebugGrid() {
        int gridWidth = (int) Math.ceil(GameConstants.MAP_WIDTH / GameConstants.PATH_GRID_CELL_SIZE);
        int gridHeight = (int) Math.ceil(GameConstants.MAP_HEIGHT / GameConstants.PATH_GRID_CELL_SIZE);
        float cellSize = GameConstants.PATH_GRID_CELL_SIZE;

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        for (int cx = 0; cx < gridWidth; cx++) {
            for (int cy = 0; cy < gridHeight; cy++) {
                shapeRenderer.setColor(Pathfinding.isWaterCell(cx, cy) ? WATER_COLOR : GRASS_COLOR);
                shapeRenderer.rect(cx * cellSize, cy * cellSize, cellSize, cellSize);
            }
        }
        shapeRenderer.end();
    }

    /** Маршрут каждого движущегося юнита — от его текущей (интерполированной) позиции через все оставшиеся точки. */
    private void drawDebugPaths() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(DEBUG_PATH_COLOR);

        for (Entity entity : engine.getEntities()) {
            DebugPathComponent debugPath = entity.getComponent(DebugPathComponent.class);
            if (debugPath == null || debugPath.points.isEmpty()) {
                continue; // не движется — линию не рисуем
            }

            PositionComponent position = entity.getComponent(PositionComponent.class);
            float fromX = position.position.x;
            float fromY = position.position.y;
            for (Vector2 point : debugPath.points) {
                shapeRenderer.line(fromX, fromY, point.x, point.y);
                fromX = point.x;
                fromY = point.y;
            }
        }

        shapeRenderer.end();
    }

    private void drawOverlayLines() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);

        shapeRenderer.setColor(Color.DARK_GRAY);
        shapeRenderer.rect(0, 0, GameConstants.MAP_WIDTH, GameConstants.MAP_HEIGHT);

        if (dragging) {
            float x = Math.min(dragStartWorld.x, dragCurrentWorld.x);
            float y = Math.min(dragStartWorld.y, dragCurrentWorld.y);
            float width = Math.abs(dragCurrentWorld.x - dragStartWorld.x);
            float height = Math.abs(dragCurrentWorld.y - dragStartWorld.y);
            shapeRenderer.setColor(Color.WHITE);
            shapeRenderer.rect(x, y, width, height);
        }

        shapeRenderer.end();
    }

    /** Продвигает и вычищает истёкшие визуальные стрелы — сама отрисовка в drawArrows(). */
    private void updateArrows(float delta) {
        Iterator<ArrowVisual> iterator = activeArrows.iterator();
        while (iterator.hasNext()) {
            ArrowVisual arrow = iterator.next();
            arrow.elapsed += delta;
            if (arrow.elapsed >= arrow.duration) {
                iterator.remove();
            }
        }
    }

    /** Рисует каждую активную стрелу коротким отрезком в точке, соответствующей текущей доле полёта. */
    private void drawArrows() {
        if (activeArrows.isEmpty()) {
            return;
        }

        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(ARROW_COLOR);

        for (ArrowVisual arrow : activeArrows) {
            float t = MathUtils.clamp(arrow.elapsed / arrow.duration, 0f, 1f);
            float x = MathUtils.lerp(arrow.fromX, arrow.toX, t);
            float y = MathUtils.lerp(arrow.fromY, arrow.toY, t);

            float dirX = arrow.toX - arrow.fromX;
            float dirY = arrow.toY - arrow.fromY;
            float length = (float) Math.sqrt(dirX * dirX + dirY * dirY);
            if (length <= 0.0001f) {
                continue; // выстрел в упор, направление не определить — пропускаем кадр
            }
            dirX /= length;
            dirY /= length;

            shapeRenderer.line(
                    x - dirX * ARROW_VISUAL_LENGTH, y - dirY * ARROW_VISUAL_LENGTH,
                    x + dirX * ARROW_VISUAL_LENGTH, y + dirY * ARROW_VISUAL_LENGTH);
        }

        shapeRenderer.end();
    }

    private void drawCenteredText(String text) {
        GlyphLayout layout = new GlyphLayout(font, text);
        spriteBatch.begin();
        font.draw(spriteBatch, layout, camera.position.x - layout.width / 2f, camera.position.y + layout.height / 2f);
        spriteBatch.end();
    }

    /** Панель постройки открытого здания: кнопка "+" (поставить в очередь) и прогресс текущего юнита. */
    private void drawProductionPanel() {
        Entity building = entityFactory.getEntity(selectedBuildingId);
        if (building == null) {
            selectedBuildingId = null; // здание пропало — не должно происходить для своего дома, но на всякий случай
            return;
        }
        ProductionComponent production = building.getComponent(ProductionComponent.class);

        shapeRenderer.setProjectionMatrix(hudCamera.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

        shapeRenderer.setColor(Color.valueOf("222222"));
        shapeRenderer.rect(PANEL_X, PANEL_Y, PANEL_WIDTH, PANEL_HEIGHT);

        shapeRenderer.setColor(Color.LIGHT_GRAY);
        shapeRenderer.rect(QUEUE_BUTTON_X, QUEUE_BUTTON_Y, QUEUE_BUTTON_SIZE, QUEUE_BUTTON_SIZE);

        if (production.queuedCount > 0) {
            float fraction = MathUtils.clamp(production.progress / GameConstants.UNIT_BUILD_TIME, 0f, 1f);
            float barY = QUEUE_BUTTON_Y + QUEUE_BUTTON_SIZE / 2f - PROGRESS_BAR_HEIGHT / 2f;

            shapeRenderer.setColor(Color.DARK_GRAY);
            shapeRenderer.rect(PROGRESS_BAR_X, barY, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
            shapeRenderer.setColor(Color.GREEN);
            shapeRenderer.rect(PROGRESS_BAR_X, barY, PROGRESS_BAR_WIDTH * fraction, PROGRESS_BAR_HEIGHT);
        }

        shapeRenderer.end();

        spriteBatch.setProjectionMatrix(hudCamera.combined);
        spriteBatch.begin();
        uiFont.setColor(Color.WHITE);
        uiFont.draw(spriteBatch, "+", QUEUE_BUTTON_X + QUEUE_BUTTON_SIZE / 2f - 6f, QUEUE_BUTTON_Y + QUEUE_BUTTON_SIZE / 2f + 8f);
        String unitTypeName = production.producesUnitType == UnitType.ARCHER ? "Archers" : "Warriors";
        uiFont.draw(spriteBatch, "Builds: " + unitTypeName, PANEL_X + 15f, PANEL_Y + PANEL_HEIGHT - 12f);
        uiFont.draw(spriteBatch, "Queue: " + production.queuedCount, PANEL_X + 15f, PANEL_Y + PANEL_HEIGHT - 34f);
        spriteBatch.end();

        // Возвращаем world-камеру шейп-рендереру и спрайт-батчу для следующего кадра.
        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);
    }

    private boolean isInsideQueueButton(float hudX, float hudY) {
        return hudX >= QUEUE_BUTTON_X && hudX <= QUEUE_BUTTON_X + QUEUE_BUTTON_SIZE
                && hudY >= QUEUE_BUTTON_Y && hudY <= QUEUE_BUTTON_Y + QUEUE_BUTTON_SIZE;
    }

    // ---- Ввод ----

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (gameOverText != null || connectionStatusText != null) {
            return true;
        }

        if (button == Input.Buttons.LEFT) {
            if (selectedBuildingId != null) {
                Vector3 hudPoint = hudCamera.unproject(new Vector3(screenX, screenY, 0));
                if (isInsideQueueButton(hudPoint.x, hudPoint.y)) {
                    client.requestQueueUnit(selectedBuildingId);
                    return true; // клик поглощён кнопкой — не начинаем рамку выделения
                }
            }
            dragStartWorld.set(camera.unproject(new Vector3(screenX, screenY, 0)));
            dragCurrentWorld.set(dragStartWorld);
            dragging = true;
        } else if (button == Input.Buttons.RIGHT && !selectedUnitIds.isEmpty()) {
            Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
            Entity target = findEntityNear(world.x, world.y);
            if (target != null && isEnemy(target)) {
                issueAttackOrder(target.getComponent(UnitComponent.class).unitId);
            } else {
                issueMoveOrder(world.x, world.y);
            }
        }
        return true;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (dragging) {
            dragCurrentWorld.set(camera.unproject(new Vector3(screenX, screenY, 0)));
        }
        return true;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (gameOverText != null || connectionStatusText != null) {
            dragging = false;
            return true;
        }

        if (button == Input.Buttons.LEFT && dragging) {
            dragging = false;
            Vector3 end = camera.unproject(new Vector3(screenX, screenY, 0));

            if (dragStartWorld.dst(end) < DRAG_THRESHOLD) {
                handleSingleClickSelect(end.x, end.y);
            } else {
                handleBoxSelect(dragStartWorld.x, dragStartWorld.y, end.x, end.y);
            }
        }
        return true;
    }

    @Override
    public boolean keyDown(int keycode) {
        // Клавиша над Tab, слева от "1" — GRAVE в терминах LibGDX
        // (`/~ на латинской раскладке). Работает всегда, даже во время
        // подключения/после конца игры — чисто визуальный переключатель,
        // не влияет на геймплей.
        if (keycode == Input.Keys.GRAVE) {
            debugMode = !debugMode;
            return true;
        }
        return false;
    }

    // ---- Выделение ----

    private void handleSingleClickSelect(float x, float y) {
        Entity clicked = findEntityNear(x, y);
        if (clicked == null) {
            setSelection(Collections.emptySet());
            selectedBuildingId = null;
            return;
        }

        OwnerComponent owner = clicked.getComponent(OwnerComponent.class);
        boolean isMine = owner != null && owner.playerId == client.getPlayerId();
        boolean isBuilding = clicked.getComponent(BuildingComponent.class) != null;

        if (isMine && isBuilding) {
            int buildingUnitId = clicked.getComponent(UnitComponent.class).unitId;
            setSelection(Collections.emptySet());
            // Повторный клик по уже открытому зданию закрывает панель, иначе — открывает.
            selectedBuildingId = (selectedBuildingId != null && selectedBuildingId == buildingUnitId)
                    ? null : buildingUnitId;
            return;
        }

        if (isMine) { // свой юнит
            selectedBuildingId = null;
            setSelection(Collections.singleton(clicked.getComponent(UnitComponent.class).unitId));
            return;
        }

        // Клик по чужому юниту или чужому зданию — выделение юнитов не
        // трогаем (двигать/командовать чужим всё равно нельзя), но панель
        // постройки своего здания закрываем — раз клик был не по ней.
        selectedBuildingId = null;
    }

    private void handleBoxSelect(float x1, float y1, float x2, float y2) {
        selectedBuildingId = null; // начали выделять юнитов рамкой — панель постройки не нужна

        float minX = Math.min(x1, x2);
        float maxX = Math.max(x1, x2);
        float minY = Math.min(y1, y2);
        float maxY = Math.max(y1, y2);

        Set<Integer> newSelection = new HashSet<>();
        for (Entity entity : engine.getEntities()) {
            if (entity.getComponent(BuildingComponent.class) != null) {
                continue; // рамка не выделяет здания
            }

            OwnerComponent owner = entity.getComponent(OwnerComponent.class);
            if (owner == null || owner.playerId != client.getPlayerId()) {
                continue; // рамкой выделяем только своих юнитов
            }

            PositionComponent position = entity.getComponent(PositionComponent.class);
            if (position.position.x >= minX && position.position.x <= maxX
                    && position.position.y >= minY && position.position.y <= maxY) {
                newSelection.add(entity.getComponent(UnitComponent.class).unitId);
            }
        }
        setSelection(newSelection);
    }

    private void setSelection(Set<Integer> newSelection) {
        for (int unitId : selectedUnitIds) {
            Entity entity = entityFactory.getEntity(unitId);
            if (entity != null) {
                entity.remove(SelectedComponent.class);
            }
        }

        selectedUnitIds.clear();
        selectedUnitIds.addAll(newSelection);

        for (int unitId : selectedUnitIds) {
            Entity entity = entityFactory.getEntity(unitId);
            if (entity != null) {
                entity.add(new SelectedComponent());
            }
        }
    }

    // ---- Приказы выделенным юнитам ----

    private void issueMoveOrder(float targetX, float targetY) {
        List<Integer> ids = new ArrayList<>(selectedUnitIds);
        int count = ids.size();

        if (count == 1) {
            client.requestMoveUnit(ids.get(0), targetX, targetY);
            return;
        }

        // Раскладываем выделенных юнитов сеткой вокруг точки клика, иначе
        // все они пойдут в одну и ту же точку и встанут друг на друге —
        // никакого пасфайндинга/избегания столкновений в MovementSystem нет.
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil((double) count / columns);

        for (int i = 0; i < count; i++) {
            int col = i % columns;
            int row = i / columns;
            float offsetX = (col - (columns - 1) / 2f) * MOVE_ORDER_SPACING;
            float offsetY = (row - (rows - 1) / 2f) * MOVE_ORDER_SPACING;
            client.requestMoveUnit(ids.get(i), targetX + offsetX, targetY + offsetY);
        }
    }

    private void issueAttackOrder(int targetUnitId) {
        // Тут спред не нужен: CombatSystem сама останавливает каждого
        // атакующего на ATTACK_RANGE от цели, и подходя с разных сторон,
        // юниты естественным образом расходятся вокруг цели кольцом.
        for (int unitId : selectedUnitIds) {
            client.requestAttackUnit(unitId, targetUnitId);
        }
    }

    // ---- Вспомогательное ----

    private boolean isEnemy(Entity entity) {
        OwnerComponent owner = entity.getComponent(OwnerComponent.class);
        return owner != null && owner.playerId != client.getPlayerId();
    }

    /** Возвращает ближайшую сущность под точкой — у зданий клик-радиус шире, они крупнее юнитов. */
    private Entity findEntityNear(float x, float y) {
        Entity closest = null;
        float closestDistance = Float.MAX_VALUE;

        for (Entity entity : engine.getEntities()) {
            PositionComponent position = entity.getComponent(PositionComponent.class);
            boolean isBuilding = entity.getComponent(BuildingComponent.class) != null;
            float clickRadius = isBuilding ? BUILDING_CLICK_RADIUS : UNIT_CLICK_RADIUS;

            float distance = position.position.dst(x, y);
            if (distance < clickRadius && distance < closestDistance) {
                closest = entity;
                closestDistance = distance;
            }
        }
        return closest;
    }

    @Override
    public void show() {
    }

    @Override
    public void resize(int width, int height) {
    }

    @Override
    public void pause() {
    }

    @Override
    public void resume() {
    }

    @Override
    public void hide() {
    }

    @Override
    public void dispose() {
        shapeRenderer.dispose();
        spriteBatch.dispose();
        font.dispose();
        uiFont.dispose();
        client.dispose();
    }
}
