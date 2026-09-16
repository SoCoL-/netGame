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
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingPlacement;
import ru.socol.supreme.shared.BuildingSizes;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.components.BuildBeamComponent;
import ru.socol.supreme.components.DebugPathComponent;
import ru.socol.supreme.components.SelectedComponent;
import ru.socol.supreme.network.GameClient;
import ru.socol.supreme.systems.InterpolationSystem;
import ru.socol.supreme.systems.RenderSystem;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.UnitComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.PlayerResources;
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
    private static final float CAMERA_PAN_SPEED = 400f; // world units в секунду
    private static final Color WATER_COLOR = new Color(0.25f, 0.55f, 0.85f, 1f); // голубой
    private static final Color GRASS_COLOR = new Color(0.2f, 0.45f, 0.2f, 1f); // зелёный, трава
    private static final Color DEBUG_PATH_COLOR = Color.ORANGE;
    private static final Color IRON_DEPOSIT_COLOR = new Color(0.55f, 0.35f, 0.2f, 1f); // ржаво-коричневый
    private static final float IRON_DEPOSIT_RADIUS = 18f;
    private static final Color IRON_MINE_GHOST_VALID_COLOR = Color.GREEN;
    private static final Color IRON_MINE_GHOST_INVALID_COLOR = Color.RED;
    private static final Color RALLY_POINT_COLOR = Color.TEAL;
    private static final float RALLY_POINT_RADIUS = 10f;
    private static final float RALLY_DASH_LENGTH = 15f;
    private static final float RALLY_GAP_LENGTH = 10f;

    // Голографический луч стройки — три слоя одного отрезка (см.
    // drawSingleBuildBeam) плюс "бегущие" сегменты вдоль него. Цвета —
    // свои Color-объекты, не общие константы вроде Color.CYAN: alpha у
    // них перезаписывается каждый кадр под пульсацию, а мутировать
    // библиотечный синглтон было бы небезопасно (его используют и в
    // других местах LibGDX/проекта).
    private static final Color BEAM_OUTER_COLOR = new Color(0.2f, 0.9f, 1f, 1f);
    private static final Color BEAM_MID_COLOR = new Color(0.4f, 0.95f, 1f, 1f);
    private static final Color BEAM_CORE_COLOR = new Color(0.85f, 1f, 1f, 1f);
    private static final float BEAM_PULSE_SPEED = 3f; // рад/сек — период пульсации альфы
    private static final float BEAM_SEGMENT_LENGTH = 14f; // длина одного "бегущего" сегмента
    private static final float BEAM_FLOW_SPEED = 220f; // юнитов/сек — скорость движения сегментов вдоль луча

    // Панель ресурсов — тоже экранные координаты, сверху слева, всегда видна
    // (в отличие от панели постройки — не только когда что-то выбрано).
    private static final float RESOURCE_PANEL_X = 20f;
    private static final float RESOURCE_PANEL_Y = 550f;
    private static final float RESOURCE_PANEL_WIDTH = 280f; // расширено под ставку изменения справа от количества
    private static final float RESOURCE_PANEL_HEIGHT = 40f;
    // Где начинается текст ставки — фиксированный отступ от правого края
    // панели, не "после текста количества": разная ширина цифр количества
    // (1 против 4 разрядов) иначе сдвигала бы ставку то туда, то сюда.
    private static final float RESOURCE_PANEL_RATE_X = RESOURCE_PANEL_X + RESOURCE_PANEL_WIDTH - 60f;

    // Панель построек — горизонтальный ряд кнопок у самого низа экрана,
    // видна всегда во время игры (не по клавише — см. поле
    // placingBuildingType выше). Один и тот же Y для всех пяти кнопок,
    // раздельные X, вычисляются в drawBuildBar/buildBarButtonAt по
    // индексу в BUILD_BAR_TYPES, а не пятью отдельными константами, как
    // было у вертикального меню — с одинаковым по форме рядом кнопок это
    // не нужно.
    private static final BuildingType[] BUILD_BAR_TYPES = {
            BuildingType.IRON_MINE,
            BuildingType.ARCHER_BARRACKS,
            BuildingType.POWER_PLANT,
            BuildingType.IRON_STORAGE,
            BuildingType.ELECTRICITY_STORAGE,
    };
    private static final float BUILD_BAR_X = 20f;
    private static final float BUILD_BAR_Y = 5f;
    private static final float BUILD_BAR_BUTTON_WIDTH = 150f;
    private static final float BUILD_BAR_BUTTON_HEIGHT = 50f;
    private static final float BUILD_BAR_GAP = 4f;

    // Панель постройки — экранные (HUD) координаты, не мировые, см. hudCamera.
    private static final float PANEL_X = 20f;
    // BUILD_BAR_Y + BUILD_BAR_BUTTON_HEIGHT + запас, а не число само по
    // себе — чтобы панель постройки гарантированно не перекрылась с
    // баром построек под ней, даже если его высота ещё поменяется.
    private static final float PANEL_Y = BUILD_BAR_Y + BUILD_BAR_BUTTON_HEIGHT + 10f;
    private static final float PANEL_WIDTH = 280f;
    // Выросла со 100 до 138 — снизу появился отдельный ряд под кнопку
    // "Demolish" (см. её ниже): у здания без производства (сейчас —
    // шахта, обе электростанции, оба хранилища) кнопок очереди и
    // прогресс-бара вообще нет, но панель для него теперь тоже
    // открывается — раз в ней есть эта кнопка.
    private static final float PANEL_HEIGHT = 138f;
    // Кнопки очереди — теперь их может быть несколько (у дома их две:
    // воин и строитель, у казармы по-прежнему одна), поэтому ряд сверху
    // панели по индексу (queueButtonX), а не одна фиксированная позиция,
    // как было раньше. Прогресс-бар и текст "Queue: N" — под ними, а не
    // сбоку, иначе при двух кнопках не осталось бы места.
    private static final float QUEUE_BUTTON_WIDTH = 90f;
    private static final float QUEUE_BUTTON_HEIGHT = 32f;
    private static final float QUEUE_BUTTON_GAP = 8f;
    private static final float QUEUE_BUTTON_X = PANEL_X + 15f;
    private static final float QUEUE_BUTTON_Y = PANEL_Y + PANEL_HEIGHT - QUEUE_BUTTON_HEIGHT - 15f;
    private static final float PROGRESS_BAR_X = PANEL_X + 15f;
    private static final float PROGRESS_BAR_WIDTH = 250f;
    private static final float PROGRESS_BAR_HEIGHT = 12f;
    private static final float PROGRESS_BAR_Y = PANEL_Y + 63f;
    // Кнопка сноса — отдельный, самый нижний ряд панели, есть у ЛЮБОГО
    // своего здания (не только производящего), фиксированная позиция
    // независимо от того, сколько кнопок очереди выше неё (0, 1 или 2).
    private static final float DEMOLISH_BUTTON_WIDTH = 110f;
    private static final float DEMOLISH_BUTTON_HEIGHT = 28f;
    private static final float DEMOLISH_BUTTON_X = PANEL_X + 15f;
    private static final float DEMOLISH_BUTTON_Y = PANEL_Y + 15f;

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
    // Копится с начала экрана каждый кадр в render(), никогда не сбрасывается
    // и не используется ни для какой игровой логики — только чтобы
    // анимировать голографический луч стройки (drawBuildBeams).
    private float elapsedTime = 0f;
    // Переключается клавишей ` (GRAVE) — см. keyDown. Заменяет заливку земли
    // сеткой клеток поиска пути и рисует маршруты движущихся юнитов.
    private boolean debugMode = false;

    // Панель построек — горизонтальный ряд кнопок внизу экрана, виден
    // всегда во время игры (drawBuildBar), не по клавише. Клик по кнопке
    // сразу переводит в режим постройки конкретного здания —
    // placingBuildingType (null = не строим ничего). Пока идёт постройка,
    // ЛКМ по карте не выделяет юнитов/здания, а подтверждает — см.
    // touchDown. Превью считается каждый кадр в render() (обычный опрос
    // текущей позиции курсора, без отдельного mouseMoved). Клавиша B
    // осталась только как отмена текущей постройки — см. keyDown.
    private BuildingType placingBuildingType = null;
    private float buildGhostX;
    private float buildGhostY;
    // Актуально только пока placingBuildingType == IRON_MINE — индекс
    // месторождения, к которому "прилип" курсор, или -1, если ни к одному.
    // У казармы и электростанции своей привязки к точке нет — там
    // валидность решает buildGhostValid.
    private int ironMineSnapDepositIndex = -1;
    private boolean buildGhostValid = false;

    // Свои ресурсы — обновляются из каждого снапшота (см. onWorldSnapshot).
    // float, не int — потребление/добыча считаются дробно (например,
    // 0.5 электричества/сек простоя казармы), см. PlayerResources. На
    // панели показываем округлённым до целого (drawResourcePanel).
    // Ресурсов противника здесь нет: сервер их присылает (fog of war для
    // ресурсов отдельно от остального не планируется), но панель — только
    // про свои же.
    private float myIron = 0f;
    private float myElectricity = 0f;
    // Чистое изменение в секунду — уже посчитано сервером как реально
    // измеренная разница между снапшотами (см. javadoc
    // PlayerResources.ironRate), клиент тут ничего сам не вычисляет.
    private float myIronRate = 0f;
    private float myElectricityRate = 0f;
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
                    for (PlayerResources resources : snapshot.playerResources) {
                        if (resources.playerId == client.getPlayerId()) {
                            myIron = resources.iron;
                            myElectricity = resources.electricity;
                            myIronRate = resources.ironRate;
                            myElectricityRate = resources.electricityRate;
                            break;
                        }
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
        elapsedTime += delta; // копится с начала экрана, не сбрасывается — нужен только для анимации (пульс луча стройки), не для геймплейной логики

        Gdx.gl.glClearColor(0.1f, 0.1f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        camera.update();
        hudCamera.update();
        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);

        drawGround(); // до engine.update() — юниты должны рисоваться поверх земли/воды, а не под ними
        drawIronDeposits(); // тоже до engine.update() — поверх земли, но под юнитами/зданиями

        engine.update(delta);

        updateArrows(delta);
        drawOverlayLines();
        drawArrows();
        drawRallyPoints();
        drawBuildBeams();
        if (debugMode) {
            drawDebugPaths();
        }

        if (gameOverText != null) {
            drawCenteredText(gameOverText);
        } else if (connectionStatusText != null) {
            font.setColor(Color.LIGHT_GRAY);
            drawCenteredText(connectionStatusText);
        } else {
            if (placingBuildingType != null) {
                updateBuildGhost();
                drawBuildGhost();
            }
            drawResourcePanel(); // всегда видна во время игры, не только когда выбрано здание
            drawBuildBar(); // тоже всегда — горизонтальный ряд кнопок построек внизу экрана
            if (selectedBuildingId != null) {
                drawBuildingPanel();
            }
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
     * Месторождения железа — просто точки на карте (GameConstants
     * .IRON_DEPOSITS), не препятствие и не игровой объект: юниты через них
     * свободно ходят, кликом не выделяются. Рисуются в обоих режимах
     * (обычном и отладочном) одинаково — в отличие от земли/воды, это не
     * часть "слоя земли", а отдельный, всегда видимый маркер.
     */
    private void drawIronDeposits() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(IRON_DEPOSIT_COLOR);
        for (float[] deposit : GameConstants.IRON_DEPOSITS) {
            shapeRenderer.circle(deposit[0], deposit[1], IRON_DEPOSIT_RADIUS);
        }
        shapeRenderer.end();
    }

    /**
     * Считается каждый кадр опросом текущего положения курсора
     * (Gdx.input.getX/getY), а не отдельным обработчиком mouseMoved —
     * превью должно двигаться, даже если мышь просто лежит неподвижно
     * (например, сразу после выбора здания в меню). Логика зависит от
     * того, что строим: шахта "прилипает" к ближайшему месторождению в
     * радиусе её snapRadius, остальные (казарма, электростанция) свободно
     * следуют за курсором, но валидны только там, где реально можно
     * строить (см. BuildingPlacement — та же проверка, что и на сервере).
     */
    private void updateBuildGhost() {
        Vector3 cursorWorld = camera.unproject(new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0));

        if (placingBuildingType == BuildingType.IRON_MINE) {
            ironMineSnapDepositIndex = -1;
            float snapRadius = BuildingDefinitions.snapRadiusFor(BuildingType.IRON_MINE);
            float closestDistanceSq = snapRadius * snapRadius;

            for (int i = 0; i < GameConstants.IRON_DEPOSITS.length; i++) {
                float[] deposit = GameConstants.IRON_DEPOSITS[i];
                float dx = cursorWorld.x - deposit[0];
                float dy = cursorWorld.y - deposit[1];
                float distanceSq = dx * dx + dy * dy;
                if (distanceSq <= closestDistanceSq) {
                    closestDistanceSq = distanceSq;
                    ironMineSnapDepositIndex = i;
                }
            }

            if (ironMineSnapDepositIndex >= 0) {
                buildGhostX = GameConstants.IRON_DEPOSITS[ironMineSnapDepositIndex][0];
                buildGhostY = GameConstants.IRON_DEPOSITS[ironMineSnapDepositIndex][1];
            } else {
                buildGhostX = cursorWorld.x;
                buildGhostY = cursorWorld.y;
            }
            buildGhostValid = ironMineSnapDepositIndex >= 0;
        } else {
            // Казарма стрелков / электростанция — свободное размещение, не привязано к точке.
            buildGhostX = cursorWorld.x;
            buildGhostY = cursorWorld.y;
            buildGhostValid = BuildingPlacement.canPlaceBuilding(placingBuildingType, engine.getEntities(), buildGhostX, buildGhostY);
        }
    }

    /** Зелёный — можно подтвердить кликом; красный — сейчас нельзя (см. updateBuildGhost, разная логика по типу здания). */
    private void drawBuildGhost() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(buildGhostValid ? IRON_MINE_GHOST_VALID_COLOR : IRON_MINE_GHOST_INVALID_COLOR);
        float halfWidth = BuildingDefinitions.halfWidthFor(placingBuildingType);
        float halfHeight = BuildingDefinitions.halfHeightFor(placingBuildingType);
        shapeRenderer.rect(buildGhostX - halfWidth, buildGhostY - halfHeight, halfWidth * 2f, halfHeight * 2f);
        shapeRenderer.end();
    }

    /** Меню "что строить" — открыто клавишей B, пока не выбран конкретный тип здания (см. keyDown/touchDown). */
    /** Название кнопки для конкретного типа здания — единственное место, которое переводит BuildingType в подпись на баре. */
    private String buildBarLabel(BuildingType type) {
        switch (type) {
            case IRON_MINE:
                return "Iron mine";
            case ARCHER_BARRACKS:
                return "Archer barracks";
            case POWER_PLANT:
                return "Power plant";
            case IRON_STORAGE:
                return "Iron storage";
            case ELECTRICITY_STORAGE:
                return "Electricity storage";
            default:
                return "";
        }
    }

    /** Левый X кнопки с этим индексом в баре — единая формула, чтобы отрисовка и проверка клика не могли разойтись. */
    private float buildBarButtonX(int index) {
        return BUILD_BAR_X + index * (BUILD_BAR_BUTTON_WIDTH + BUILD_BAR_GAP);
    }

    private void drawBuildBar() {
        shapeRenderer.setProjectionMatrix(hudCamera.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(Color.valueOf("222222"));
        for (int i = 0; i < BUILD_BAR_TYPES.length; i++) {
            shapeRenderer.rect(buildBarButtonX(i), BUILD_BAR_Y, BUILD_BAR_BUTTON_WIDTH, BUILD_BAR_BUTTON_HEIGHT);
        }
        shapeRenderer.end();

        spriteBatch.setProjectionMatrix(hudCamera.combined);
        spriteBatch.begin();
        uiFont.setColor(Color.WHITE);
        for (int i = 0; i < BUILD_BAR_TYPES.length; i++) {
            uiFont.draw(spriteBatch, buildBarLabel(BUILD_BAR_TYPES[i]), buildBarButtonX(i) + 10f, BUILD_BAR_Y + BUILD_BAR_BUTTON_HEIGHT - 18f);
        }
        spriteBatch.end();

        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);
    }

    /** Какое здание нажато по HUD-координатам клика — null, если мимо всех кнопок бара. */
    private BuildingType buildBarButtonAt(float hudX, float hudY) {
        if (hudY < BUILD_BAR_Y || hudY > BUILD_BAR_Y + BUILD_BAR_BUTTON_HEIGHT) {
            return null;
        }
        for (int i = 0; i < BUILD_BAR_TYPES.length; i++) {
            float buttonX = buildBarButtonX(i);
            if (hudX >= buttonX && hudX <= buttonX + BUILD_BAR_BUTTON_WIDTH) {
                return BUILD_BAR_TYPES[i];
            }
        }
        return null;
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

    /**
     * Точка сбора каждого производящего здания, у которого она задана —
     * пунктирная линия от здания до точки и сама точка (бирюзовый круг).
     * Видна всегда, не только пока здание выделено (это настройка,
     * влияющая на всех будущих юнитов, а не разовое действие — держать
     * её скрытой было бы неудобно), и для обеих сторон одинаково, как и
     * остальное на карте (отдельного тумана войны в игре нет).
     */
    private void drawRallyPoints() {
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (Entity entity : engine.getEntities()) {
            ProductionComponent production = entity.getComponent(ProductionComponent.class);
            if (production == null || !production.hasRallyPoint) {
                continue;
            }
            PositionComponent position = entity.getComponent(PositionComponent.class);
            drawDashedLine(position.position.x, position.position.y, production.rallyX, production.rallyY);
            shapeRenderer.setColor(RALLY_POINT_COLOR);
            shapeRenderer.circle(production.rallyX, production.rallyY, RALLY_POINT_RADIUS);
        }
        shapeRenderer.end();
    }

    /** ShapeRenderer не рисует пунктир сам — чередуем короткие толстые отрезки (rectLine) с промежутками вдоль направления линии. */
    private void drawDashedLine(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1f) {
            return;
        }

        float dirX = dx / length;
        float dirY = dy / length;
        float step = RALLY_DASH_LENGTH + RALLY_GAP_LENGTH;

        shapeRenderer.setColor(RALLY_POINT_COLOR);
        for (float traveled = 0f; traveled < length; traveled += step) {
            float dashEnd = Math.min(traveled + RALLY_DASH_LENGTH, length);
            shapeRenderer.rectLine(
                    x1 + dirX * traveled, y1 + dirY * traveled,
                    x1 + dirX * dashEnd, y1 + dirY * dashEnd,
                    2f);
        }
    }

    /**
     * "Голографический луч" от строителя к зданию, которое он СЕЙЧАС
     * реально строит (не просто идёт туда — см. BuildBeamComponent,
     * заполняет EntityFactory по UnitSnapshot.buildTargetUnitId, который
     * сервер шлёт только пока BuildOrderComponent.inRange). Без него
     * работающая стройка была бы почти незаметна на глаз — разве что по
     * чуть двигающемуся прогресс-бару под зданием.
     *
     * ShapeRenderer не поддерживает шейдеры/свечение сам по себе — имитация
     * простая: три слоя ОДНОГО и того же отрезка разной толщины и яркости
     * (широкий тусклый "ореол", средний, яркая тонкая "сердцевина"),
     * плюс пульсация альфы по elapsedTime и несколько ярких сегментов,
     * "бегущих" вдоль луча от строителя к зданию — впечатление потока
     * энергии без реальной анимации текстуры.
     */
    private void drawBuildBeams() {
        boolean anyBeam = false;
        for (Entity entity : engine.getEntities()) {
            if (entity.getComponent(BuildBeamComponent.class) != null) {
                anyBeam = true;
                break;
            }
        }
        if (!anyBeam) {
            return; // не трогаем GL_BLEND зря, если прямо сейчас никто не строит
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

        float pulse = 0.5f + 0.5f * MathUtils.sin(elapsedTime * BEAM_PULSE_SPEED);

        for (Entity entity : engine.getEntities()) {
            BuildBeamComponent beam = entity.getComponent(BuildBeamComponent.class);
            if (beam == null) {
                continue;
            }
            Entity target = entityFactory.getEntity(beam.targetBuildingUnitId);
            if (target == null) {
                continue;
            }
            PositionComponent builderPosition = entity.getComponent(PositionComponent.class);
            PositionComponent targetPosition = target.getComponent(PositionComponent.class);
            drawSingleBuildBeam(builderPosition.position.x, builderPosition.position.y,
                    targetPosition.position.x, targetPosition.position.y, pulse);
        }

        shapeRenderer.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawSingleBuildBeam(float x1, float y1, float x2, float y2, float pulse) {
        BEAM_OUTER_COLOR.a = 0.12f + 0.1f * pulse;
        shapeRenderer.setColor(BEAM_OUTER_COLOR);
        shapeRenderer.rectLine(x1, y1, x2, y2, 10f);

        BEAM_MID_COLOR.a = 0.3f + 0.2f * pulse;
        shapeRenderer.setColor(BEAM_MID_COLOR);
        shapeRenderer.rectLine(x1, y1, x2, y2, 5f);

        BEAM_CORE_COLOR.a = 0.55f + 0.35f * pulse;
        shapeRenderer.setColor(BEAM_CORE_COLOR);
        shapeRenderer.rectLine(x1, y1, x2, y2, 2f);

        // "Бегущие" яркие сегменты вдоль луча, от строителя (x1,y1) к
        // зданию (x2,y2) — offset растёт со временем, сегменты едут в ту
        // же сторону, что и сам поток "энергии" в стройку.
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1f) {
            return;
        }
        float dirX = dx / length;
        float dirY = dy / length;
        float period = BEAM_SEGMENT_LENGTH * 3f;
        float offset = (elapsedTime * BEAM_FLOW_SPEED) % period;

        BEAM_CORE_COLOR.a = 0.9f;
        shapeRenderer.setColor(BEAM_CORE_COLOR);
        for (float traveled = offset; traveled < length; traveled += period) {
            float segmentEnd = Math.min(traveled + BEAM_SEGMENT_LENGTH, length);
            shapeRenderer.rectLine(
                    x1 + dirX * traveled, y1 + dirY * traveled,
                    x1 + dirX * segmentEnd, y1 + dirY * segmentEnd,
                    4f);
        }
    }

    private void drawCenteredText(String text) {
        GlyphLayout layout = new GlyphLayout(font, text);
        spriteBatch.begin();
        font.draw(spriteBatch, layout, camera.position.x - layout.width / 2f, camera.position.y + layout.height / 2f);
        spriteBatch.end();
    }

    /** Панель постройки открытого здания: кнопка "+" (поставить в очередь) и прогресс текущего юнита. */
    /** Ресурсы своего игрока — всегда на экране во время игры, не только при выбранном здании (в отличие от панели постройки). */
    private void drawResourcePanel() {
        shapeRenderer.setProjectionMatrix(hudCamera.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(Color.valueOf("222222"));
        shapeRenderer.rect(RESOURCE_PANEL_X, RESOURCE_PANEL_Y, RESOURCE_PANEL_WIDTH, RESOURCE_PANEL_HEIGHT);
        shapeRenderer.end();

        spriteBatch.setProjectionMatrix(hudCamera.combined);
        spriteBatch.begin();
        uiFont.setColor(Color.WHITE);
        // (int) — округление вниз для отображения; внутренний счёт остаётся дробным (float), см. myIron/myElectricity.
        uiFont.draw(spriteBatch, "Iron: " + (int) myIron, RESOURCE_PANEL_X + 12f, RESOURCE_PANEL_Y + RESOURCE_PANEL_HEIGHT - 8f);
        uiFont.draw(spriteBatch, "Electricity: " + (int) myElectricity, RESOURCE_PANEL_X + 12f, RESOURCE_PANEL_Y + RESOURCE_PANEL_HEIGHT - 26f);
        drawResourceRate(myIronRate, RESOURCE_PANEL_Y + RESOURCE_PANEL_HEIGHT - 8f);
        drawResourceRate(myElectricityRate, RESOURCE_PANEL_Y + RESOURCE_PANEL_HEIGHT - 26f);
        spriteBatch.end();

        // Возвращаем world-камеру шейп-рендереру и спрайт-батчу для следующего кадра.
        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);
    }

    /**
     * Чистое изменение ресурса в секунду, напротив его количества —
     * зелёным при приросте, красным при расходе, белым, если после
     * округления ровно 0. Цвет и знак берутся от УЖЕ округлённого числа
     * (не от сырого float), чтобы не показывать, например, зелёный "0" —
     * если на экране 0, он должен быть белым, а не намекать на скрытый
     * дробный прирост, который всё равно не виден.
     */
    private void drawResourceRate(float rate, float y) {
        int rounded = Math.round(rate);
        uiFont.setColor(rounded > 0 ? Color.GREEN : rounded < 0 ? Color.RED : Color.WHITE);
        String text = rounded > 0 ? "+" + rounded : String.valueOf(rounded);
        uiFont.draw(spriteBatch, text, RESOURCE_PANEL_RATE_X, y);
    }

    /** Панель выделенного своего здания — очередь производства (если есть, см. producible.length) плюс кнопка "Demolish" (есть всегда). */
    private void drawBuildingPanel() {
        Entity building = entityFactory.getEntity(selectedBuildingId);
        if (building == null) {
            selectedBuildingId = null; // здание пропало — не должно происходить для своего дома, но на всякий случай
            return;
        }
        ProductionComponent production = building.getComponent(ProductionComponent.class);
        BuildingType buildingType = building.getComponent(BuildingComponent.class).type;
        UnitType[] producible = BuildingDefinitions.producesUnitTypesFor(buildingType);

        shapeRenderer.setProjectionMatrix(hudCamera.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

        shapeRenderer.setColor(Color.valueOf("222222"));
        shapeRenderer.rect(PANEL_X, PANEL_Y, PANEL_WIDTH, PANEL_HEIGHT);

        shapeRenderer.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i < producible.length; i++) {
            shapeRenderer.rect(queueButtonX(i), QUEUE_BUTTON_Y, QUEUE_BUTTON_WIDTH, QUEUE_BUTTON_HEIGHT);
        }

        if (production != null && production.queuedCount > 0) {
            float fraction = MathUtils.clamp(production.progress / GameConstants.UNIT_BUILD_TIME, 0f, 1f);

            shapeRenderer.setColor(Color.DARK_GRAY);
            shapeRenderer.rect(PROGRESS_BAR_X, PROGRESS_BAR_Y, PROGRESS_BAR_WIDTH, PROGRESS_BAR_HEIGHT);
            shapeRenderer.setColor(Color.GREEN);
            shapeRenderer.rect(PROGRESS_BAR_X, PROGRESS_BAR_Y, PROGRESS_BAR_WIDTH * fraction, PROGRESS_BAR_HEIGHT);
        }

        shapeRenderer.setColor(Color.FIREBRICK);
        shapeRenderer.rect(DEMOLISH_BUTTON_X, DEMOLISH_BUTTON_Y, DEMOLISH_BUTTON_WIDTH, DEMOLISH_BUTTON_HEIGHT);

        shapeRenderer.end();

        spriteBatch.setProjectionMatrix(hudCamera.combined);
        spriteBatch.begin();
        uiFont.setColor(Color.WHITE);
        for (int i = 0; i < producible.length; i++) {
            uiFont.draw(spriteBatch, "+" + unitTypeLabel(producible[i]), queueButtonX(i) + 8f, QUEUE_BUTTON_Y + QUEUE_BUTTON_HEIGHT - 10f);
        }
        if (production != null) {
            uiFont.draw(spriteBatch, "Queue: " + production.queuedCount, PANEL_X + 15f, PROGRESS_BAR_Y - 6f);
        }
        uiFont.draw(spriteBatch, "Demolish", DEMOLISH_BUTTON_X + 10f, DEMOLISH_BUTTON_Y + DEMOLISH_BUTTON_HEIGHT - 8f);
        spriteBatch.end();

        // Возвращаем world-камеру шейп-рендереру и спрайт-батчу для следующего кадра.
        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);
    }

    /** Левый X кнопки очереди с этим индексом — единая формула, чтобы отрисовка и проверка клика не могли разойтись. */
    private float queueButtonX(int index) {
        return QUEUE_BUTTON_X + index * (QUEUE_BUTTON_WIDTH + QUEUE_BUTTON_GAP);
    }

    private String unitTypeLabel(UnitType type) {
        switch (type) {
            case WARRIOR:
                return "Warrior";
            case ARCHER:
                return "Archer";
            case BUILDER:
                return "Builder";
            default:
                return "";
        }
    }

    /** Какой тип юнита нажат по HUD-координатам клика — null, если мимо всех кнопок очереди этого здания. */
    private UnitType queueButtonAt(float hudX, float hudY, UnitType[] producible) {
        if (hudY < QUEUE_BUTTON_Y || hudY > QUEUE_BUTTON_Y + QUEUE_BUTTON_HEIGHT) {
            return null;
        }
        for (int i = 0; i < producible.length; i++) {
            float x = queueButtonX(i);
            if (hudX >= x && hudX <= x + QUEUE_BUTTON_WIDTH) {
                return producible[i];
            }
        }
        return null;
    }

    /** Клик по кнопке "Demolish" панели — та же кнопка у любого своего здания, независимо от того, сколько кнопок очереди выше неё. */
    private boolean isInsideDemolishButton(float hudX, float hudY) {
        return hudX >= DEMOLISH_BUTTON_X && hudX <= DEMOLISH_BUTTON_X + DEMOLISH_BUTTON_WIDTH
                && hudY >= DEMOLISH_BUTTON_Y && hudY <= DEMOLISH_BUTTON_Y + DEMOLISH_BUTTON_HEIGHT;
    }

    // ---- Ввод ----

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (gameOverText != null || connectionStatusText != null) {
            return true;
        }

        if (button == Input.Buttons.LEFT) {
            Vector3 hudPoint = hudCamera.unproject(new Vector3(screenX, screenY, 0));
            BuildingType barButton = buildBarButtonAt(hudPoint.x, hudPoint.y);
            if (barButton != null) {
                // Клик по бару — всегда переключает, что строим, даже если
                // уже что-то строилось (см. javadoc массива BUILD_BAR_TYPES);
                // выделение снимаем, панель постройки в этом режиме не нужна.
                placingBuildingType = barButton;
                setSelection(Collections.emptySet());
                selectedBuildingId = null;
                return true;
            }
        }

        if (placingBuildingType != null) {
            if (button == Input.Buttons.LEFT && buildGhostValid) {
                if (placingBuildingType == BuildingType.IRON_MINE) {
                    client.requestPlaceIronMine(ironMineSnapDepositIndex);
                } else {
                    client.requestPlaceBuilding(placingBuildingType, buildGhostX, buildGhostY);
                }
                placingBuildingType = null;
            }
            // Клик поглощён размещением здания целиком — ни выделение, ни
            // рамка, ни приказ на движение в этом режиме не должны сработать.
            return true;
        }

        if (button == Input.Buttons.LEFT) {
            if (selectedBuildingId != null) {
                Vector3 hudPoint = hudCamera.unproject(new Vector3(screenX, screenY, 0));
                if (isInsideDemolishButton(hudPoint.x, hudPoint.y)) {
                    client.requestDemolishBuilding(selectedBuildingId);
                    setSelection(Collections.emptySet());
                    selectedBuildingId = null; // не ждём подтверждения от сервера — здание всё равно скоро пропадёт из снапшота
                    return true;
                }
                Entity selectedBuilding = entityFactory.getEntity(selectedBuildingId);
                if (selectedBuilding != null) {
                    BuildingType buildingType = selectedBuilding.getComponent(BuildingComponent.class).type;
                    UnitType clickedUnitType = queueButtonAt(hudPoint.x, hudPoint.y, BuildingDefinitions.producesUnitTypesFor(buildingType));
                    if (clickedUnitType != null) {
                        client.requestQueueUnit(selectedBuildingId, clickedUnitType);
                        return true; // клик поглощён кнопкой — не начинаем рамку выделения
                    }
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
            } else if (target != null && !isEnemy(target) && target.getComponent(ConstructionComponent.class) != null) {
                // Своё (не чужое — isEnemy(target) уже false тут исключает и
                // "ничьё" быть не может, раз ConstructionComponent вообще
                // есть) недостроенное здание — строители из выделения идут
                // его достраивать, остальные юниты выделения просто
                // игнорируют клик (см. javadoc issueBuildOrder).
                issueBuildOrder(target.getComponent(UnitComponent.class).unitId);
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
                Entity clicked = findEntityNear(end.x, end.y);
                OwnerComponent clickedOwner = clicked != null ? clicked.getComponent(OwnerComponent.class) : null;
                boolean clickedOwnEntity = clickedOwner != null && clickedOwner.playerId == client.getPlayerId();

                Entity selectedBuilding = selectedBuildingId != null ? entityFactory.getEntity(selectedBuildingId) : null;
                boolean selectedIsProducer = selectedBuilding != null && selectedBuilding.getComponent(ProductionComponent.class) != null;

                if (selectedIsProducer && !clickedOwnEntity) {
                    // Клик мимо своего юнита/здания, пока выделено (своё)
                    // производящее здание — не выделение, а точка сбора.
                    // Клик по СВОЕМУ юниту/зданию по-прежнему переключает
                    // выделение как обычно (иначе нельзя было бы выйти из
                    // этого режима, не отменив выделение как-то ещё). У
                    // непроизводящего здания (снова выделяемого — теперь и
                    // у него есть панель, но с одной кнопкой "Demolish", не
                    // очередью) точки сбора не бывает вовсе — клик мимо него
                    // просто выделяет/двигает как обычно.
                    client.requestSetRallyPoint(selectedBuildingId, end.x, end.y);
                } else {
                    handleSingleClickSelect(end.x, end.y);
                }
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
        if (keycode == Input.Keys.B) {
            // Панель построек теперь всегда на экране (см. drawBuildBar) —
            // клавиша B осталась только как способ отменить уже начатое
            // размещение здания, не открывает больше никакого меню.
            if (placingBuildingType != null) {
                placingBuildingType = null;
            }
            return true;
        }

        if (keycode == Input.Keys.ESCAPE) {
            // То же самое, что и B выше — убирает чертёж здания из-под
            // курсора, если сейчас что-то размещаем. Отдельная клавиша, а
            // не альтернативная ветка того же if, чтобы обе продолжали
            // работать независимо, если одну из них потом всё же уберут.
            if (placingBuildingType != null) {
                placingBuildingType = null;
            }
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
            // Повторный клик по уже открытому зданию закрывает панель, иначе
            // — открывает. Панель теперь показывается для ЛЮБОГО своего
            // здания (не только производящего) — раз в ней появилась кнопка
            // "Demolish", а не только очередь производства; сама очередь
            // просто не рисуется, если у здания нет ProductionComponent (см.
            // drawBuildingPanel).
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

    /**
     * Приказ строить это здание — только строителям из выделения (если
     * там оказались ещё воины/стрелки вместе со строителем, они просто
     * ничего не делают в ответ на этот клик, не двигаются к зданию и не
     * получают никакого другого приказа). Спред тут не нужен по той же
     * причине, что и в issueAttackOrder — BuildSystem сама останавливает
     * каждого строителя на buildRadius от цели.
     */
    private void issueBuildOrder(int targetBuildingUnitId) {
        for (int unitId : selectedUnitIds) {
            Entity unit = entityFactory.getEntity(unitId);
            if (unit == null) {
                continue;
            }
            UnitTypeComponent unitType = unit.getComponent(UnitTypeComponent.class);
            if (unitType != null && unitType.type == UnitType.BUILDER) {
                client.requestBuildOrder(unitId, targetBuildingUnitId);
            }
        }
    }

    // ---- Вспомогательное ----

    private boolean isEnemy(Entity entity) {
        OwnerComponent owner = entity.getComponent(OwnerComponent.class);
        return owner != null && owner.playerId != client.getPlayerId();
    }

    /** Возвращает ближайшую сущность под точкой — у зданий клик-радиус зависит от их реальной формы (дом и казарма разного размера). */
    private Entity findEntityNear(float x, float y) {
        Entity closest = null;
        float closestDistance = Float.MAX_VALUE;

        for (Entity entity : engine.getEntities()) {
            PositionComponent position = entity.getComponent(PositionComponent.class);
            float clickRadius = clickRadiusFor(entity);

            float distance = position.position.dst(x, y);
            if (distance < clickRadius && distance < closestDistance) {
                closest = entity;
                closestDistance = distance;
            }
        }
        return closest;
    }

    /** Юнит — фиксированный радиус; здание — до угла его фактического прямоугольника (дом и казарма разной формы, см. GameConstants). */
    /** Юнит — фиксированный радиус; здание (дом/казарма/здание добычи, в любом состоянии) — до угла его фактического прямоугольника. */
    private float clickRadiusFor(Entity entity) {
        if (entity.getComponent(BuildingComponent.class) == null) {
            return UNIT_CLICK_RADIUS;
        }
        float halfWidth = BuildingSizes.halfWidth(entity);
        float halfHeight = BuildingSizes.halfHeight(entity);
        return (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
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
