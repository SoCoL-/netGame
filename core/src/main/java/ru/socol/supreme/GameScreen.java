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
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
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
import ru.socol.supreme.shared.QueuedOrder;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.components.BuildBeamComponent;
import ru.socol.supreme.components.DebugPathComponent;
import ru.socol.supreme.components.OrderQueueDisplayComponent;
import ru.socol.supreme.components.SelectedComponent;
import ru.socol.supreme.network.GameClient;
import ru.socol.supreme.systems.InterpolationSystem;
import ru.socol.supreme.systems.RenderSystem;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.UnitComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.FogSnapshot;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.QueuedOrderPoint;
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
 *  - WASD / стрелки            -> прокрутка камеры (карта 8000x8000, окно
 *                                 1024x768 — целиком не помещается)
 *  - Колесо мыши                -> зум камеры к точке под курсором (как в
 *                                 Supreme Commander); на сильном отдалении
 *                                 юниты и здания плавно сменяются
 *                                 укрупнёнными стратегическими значками
 *                                 (см. strategicFactor/drawStrategicIcons)
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

    // Виртуальный размер вьюпорта (HUD- и мировой камеры, см. конструктор) —
    // всё HUD-расположение ниже считается относительно этих чисел, а не
    // зашито само по себе, чтобы при следующей смене разрешения нужно было
    // поменять только тут. Реальный размер окна (Lwjgl3Launcher) — тоже
    // 1024x768, то же соотношение сторон 4:3, что было и раньше у 800x600,
    // так что сам движок камеры (FitViewport-подобный setToOrtho) не
    // искажает картинку независимо от того, совпадают ли числа тут с
    // размером окна физически.
    private static final float HUD_WIDTH = 1024f;
    private static final float HUD_HEIGHT = 768f;

    // ---- Зум камеры и переход тактический/стратегический вид ----
    //
    // MIN_ZOOM — чуть ближе, чем базовый zoom=1, для детального осмотра.
    // MAX_ZOOM считается от размера карты и вьюпорта (не зашит числом),
    // так что на максимальном отдалении вся карта помещается в кадр с
    // запасом ~15% по краям — и не придётся руками пересчитывать эту
    // константу при следующем изменении GameConstants.MAP_WIDTH/HEIGHT.
    private static final float MIN_ZOOM = 0.4f;
    private static final float MAX_ZOOM = Math.max(
            GameConstants.MAP_WIDTH / HUD_WIDTH,
            GameConstants.MAP_HEIGHT / HUD_HEIGHT) * 1.15f;

    // Один "щелчок" колеса меняет ЦЕЛЕВОЙ зум на этот множитель
    // (мультипликативно, не аддитивно — иначе шаг на большом зуме казался
    // бы то огромным, то незаметным). Сам camera.zoom плавно "доезжает" до
    // цели каждый кадр (ZOOM_LERP_SPEED — доля пути в секунду), а не
    // переключается мгновенно, см. updateCamera.
    private static final float ZOOM_SCROLL_FACTOR = 1.15f;
    private static final float ZOOM_LERP_SPEED = 10f;

    // Полоса перехода тактический -> стратегический вид, в долях MAX_ZOOM.
    // Ниже STRATEGIC_ZOOM_START виден только тактический слой (обычные
    // юниты/здания, как сейчас, RenderSystem), выше STRATEGIC_ZOOM_FULL —
    // только укрупнённые стратегические значки (drawStrategicIcons), между
    // ними оба слоя рисуются одновременно с дополняющей друг друга альфой
    // (strategicFactor) — плавный кроссфейд, а не мгновенный переключатель.
    private static final float STRATEGIC_ZOOM_START = MAX_ZOOM * 0.35f;
    private static final float STRATEGIC_ZOOM_FULL = MAX_ZOOM * 0.65f;

    // Размер стратегического значка — в экранных пикселях (при zoom=1 это
    // мировые юниты, но рисуется он с размером *camera.zoom, см.
    // drawStrategicIcons, поэтому на любом зуме внутри стратегической
    // полосы значок остаётся одного и того же видимого на экране размера,
    // а не съёживается вместе с остальным миром).
    private static final float STRATEGIC_UNIT_ICON_RADIUS_PX = 8f;
    private static final float STRATEGIC_BUILDING_ICON_HALF_SIZE_PX = 11f;
    private static final float STRATEGIC_SELECTION_RING_EXTRA_PX = 3f;

    private static final float DRAG_THRESHOLD = 6f; // world units — отличает клик от протяжки рамки
    private static final float MOVE_ORDER_SPACING = 24f; // world units между юнитами в сетке при групповом приказе
    private static final float UNIT_CLICK_RADIUS = 12f;
    private static final float CAMERA_PAN_SPEED = 400f; // world units в секунду
    private static final Color WATER_COLOR = new Color(0.25f, 0.55f, 0.85f, 1f); // голубой
    // Серое затенение непросвеченных клеток тумана войны — полупрозрачное
    // (не сплошной чёрный), чтобы "затенение", как просили, а не полное
    // перекрытие.
    private static final Color FOG_COLOR = new Color(0.12f, 0.12f, 0.12f, 0.88f);
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

    // Цепочка очереди приказов выделенного юнита — линия одного цвета
    // (отличного от точки сбора, чтобы не путать два разных пунктира на
    // экране), маркеры на каждой точке красятся по типу приказа.
    private static final Color ORDER_QUEUE_LINE_COLOR = Color.valueOf("FFD966");
    private static final Color ORDER_QUEUE_MOVE_COLOR = Color.WHITE;
    private static final Color ORDER_QUEUE_ATTACK_COLOR = Color.valueOf("FF5555");
    private static final Color ORDER_QUEUE_BUILD_COLOR = Color.valueOf("55DDFF");
    private static final float ORDER_QUEUE_MARKER_RADIUS = 6f;

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
    // Y считается от HUD_HEIGHT (верх экрана), а не зашит числом само по
    // себе — иначе при следующей смене высоты вьюпорта панель осталась бы
    // на старом месте, а не у верха, где ей и положено быть.
    private static final float RESOURCE_PANEL_X = 20f;
    private static final float RESOURCE_PANEL_WIDTH = 280f; // расширено под ставку изменения справа от количества
    private static final float RESOURCE_PANEL_HEIGHT = 40f;
    private static final float RESOURCE_PANEL_Y = HUD_HEIGHT - RESOURCE_PANEL_HEIGHT - 10f;
    // Где начинается текст ставки — фиксированный отступ от правого края
    // панели, не "после текста количества": разная ширина цифр количества
    // (1 против 4 разрядов) иначе сдвигала бы ставку то туда, то сюда.
    private static final float RESOURCE_PANEL_RATE_X = RESOURCE_PANEL_X + RESOURCE_PANEL_WIDTH - 60f;

    // Единая контекстная плашка внизу экрана — раньше тут было два разных
    // блока (постоянный бар построек + отдельная панель здания), теперь
    // одна плашка, чьё содержимое зависит от текущего выделения (см.
    // drawInfoPanel): здание — имя, HP, очередь (если производит), кнопка
    // сноса; юнит — имя, HP; строитель — имя, HP, кнопки построек вместо
    // очереди; несколько юнитов — просто счётчик. Кнопки построек больше
    // не в постоянном баре, а в панели ВЫДЕЛЕННОГО строителя — строить
    // может только он, вот кнопки и живут при нём, а не сами по себе.
    private static final BuildingType[] BUILDABLE_TYPES = {
            BuildingType.IRON_MINE,
            BuildingType.ARCHER_BARRACKS,
            BuildingType.POWER_PLANT,
            BuildingType.IRON_STORAGE,
            BuildingType.ELECTRICITY_STORAGE,
            BuildingType.AIRCRAFT_FACTORY,
    };
    // Пустой массив вместо null — для ещё строящегося (или непроизводящего)
    // здания, см. drawBuildingInfoPanel/touchDown.
    private static final UnitType[] NO_PRODUCIBLE_TYPES = new UnitType[0];

    private static final float PANEL_X = 0f;
    private static final float PANEL_Y = 0f;
    // Во всю ширину экрана (HUD_WIDTH), без отступов по бокам и до самого
    // низа — раньше была уже панели постройки (770 из 800 при старом
    // вьюпорте) с полями со всех сторон; теперь полей нет вовсе, только
    // внутренний отступ текста/кнопок от края плашки (те самые "+ 15f"
    // ниже — это отступ КОНТЕНТА от края плашки, не отступ самой плашки
    // от края экрана, их не стоит путать).
    private static final float PANEL_WIDTH = HUD_WIDTH;
    private static final float PANEL_HEIGHT = 160f;

    // Имя и HP — верхняя строка плашки, слева.
    private static final float NAME_TEXT_Y = PANEL_Y + PANEL_HEIGHT - 20f;
    private static final float HP_TEXT_Y = PANEL_Y + PANEL_HEIGHT - 42f;

    // Ряд кнопок действия — очередь производства ИЛИ кнопки построек
    // (никогда не оба сразу, зависит от того, что выделено), поэтому одна
    // общая геометрия на оба случая, не две раздельные.
    private static final float ACTION_BUTTON_WIDTH = 140f;
    private static final float ACTION_BUTTON_HEIGHT = 42f;
    private static final float ACTION_BUTTON_GAP = 8f;
    private static final float ACTION_BUTTON_X = PANEL_X + 15f;
    private static final float ACTION_BUTTON_Y = PANEL_Y + 58f;

    // Прогресс-бар очереди — нижняя часть плашки, только когда выделено
    // производящее здание и очередь не пуста.
    private static final float PROGRESS_BAR_X = PANEL_X + 15f;
    private static final float PROGRESS_BAR_WIDTH = 400f;
    private static final float PROGRESS_BAR_HEIGHT = 14f;
    private static final float PROGRESS_BAR_Y = PANEL_Y + 18f;

    // Кнопка сноса — верхний правый угол плашки, есть у ЛЮБОГО своего
    // здания (не только производящего), фиксированная позиция независимо
    // от ряда кнопок действия под ней.
    private static final float DEMOLISH_BUTTON_WIDTH = 110f;
    private static final float DEMOLISH_BUTTON_HEIGHT = 34f;
    private static final float DEMOLISH_BUTTON_X = PANEL_X + PANEL_WIDTH - DEMOLISH_BUTTON_WIDTH - 15f;
    private static final float DEMOLISH_BUTTON_Y = PANEL_Y + PANEL_HEIGHT - DEMOLISH_BUTTON_HEIGHT - 15f;

    // Чисто визуальный полёт стрелы — урон уже применён на сервере в момент
    // выстрела (см. ProjectileFiredEvent), скорость тут только для картинки.
    private static final float ARROW_SPEED = 600f; // world units в секунду
    private static final Color ARROW_COLOR = Color.WHITE;
    private static final float ARROW_VISUAL_LENGTH = 8f; // половина длины отрезка, изображающего стрелу
    // Смещение точки вылета вдоль линии огня (см. onProjectileFired) — у
    // наземной техники снаряд должен визуально вылетать из дула башни
    // (см. RenderSystem.TURRET_BARREL_LENGTH — то же число, но
    // недоступно отсюда напрямую, поэтому продублировано как отдельная
    // константа, не общий метод/поле ради одного числа), а не из самого
    // центра юнита. ProjectileFiredEvent не знает угол башни в момент
    // выстрела (это чисто клиентский TurretDisplayComponent, сервер его
    // отдельно не шлёт с событием) — используем направление на цель,
    // ровно ту же линию, куда башня должна быть наведена в момент
    // выстрела (наземным ориентация для самой атаки не важна, см.
    // javadoc CombatSystem, но башня к этому моменту в подавляющем
    // большинстве случаев уже довёрнута, см. TurretAimSystem). Для
    // авиации то же смещение просто отодвигает точку вылета чуть вперёд
    // от центра — тоже уместно, у неё оружие тоже не в самом центре
    // корпуса.
    private static final float ARROW_MUZZLE_OFFSET = GameConstants.UNIT_RADIUS * 1.6f;

    private final Engine engine = new Engine();
    private final ShapeRenderer shapeRenderer = new ShapeRenderer();
    private final SpriteBatch spriteBatch = new SpriteBatch();
    private final BitmapFont font = new BitmapFont(); // крупный — для "VICTORY"/"Connecting..." по центру экрана
    private final BitmapFont uiFont = new BitmapFont(); // помельче — для панели постройки
    private final OrthographicCamera camera = new OrthographicCamera();
    // Отдельная неподвижная камера для HUD (панель постройки) — рисуется в
    // экранных координатах, не должна зависеть от прокрутки world-камеры.
    private final OrthographicCamera hudCamera = new OrthographicCamera();
    // Именованное поле (не анонимный addSystem(new RenderSystem(...))), так
    // как GameScreen каждый кадр должен сообщать ей текущую альфу
    // тактического слоя (см. render()/strategicFactor).
    private final RenderSystem renderSystem = new RenderSystem(shapeRenderer);
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
    // Цель, к которой camera.zoom плавно "доезжает" каждый кадр (см.
    // updateCamera) — меняется колесом мыши (scrolled), а не самим zoom
    // напрямую, иначе один щелчок колеса мгновенно перескакивал бы зум,
    // без всякой плавности.
    private float targetZoom = 1f;
    // Копится с начала экрана каждый кадр в render(), никогда не сбрасывается
    // и не используется ни для какой игровой логики — только чтобы
    // анимировать голографический луч стройки (drawBuildBeams).
    private float elapsedTime = 0f;
    // Переключается клавишей ` (GRAVE) — см. keyDown. Заменяет заливку земли
    // сеткой клеток поиска пути и рисует маршруты движущихся юнитов.
    private boolean debugMode = false;

    // Кнопки построек — теперь в панели выделенного строителя
    // (drawUnitInfoPanel), не в отдельном постоянном баре. Клик по кнопке
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
    // Ресурсов противника здесь нет: сервер их присылает без тумана войны
    // (он есть только для самой карты, не для счёта ресурсов), но панель —
    // только про свои же.
    private float myIron = 0f;
    private float myElectricity = 0f;
    // Чистое изменение в секунду — уже посчитано сервером как реально
    // измеренная разница между снапшотами (см. javadoc
    // PlayerResources.ironRate), клиент тут ничего сам не вычисляет.
    private float myIronRate = 0f;
    private float myElectricityRate = 0f;
    // Туман войны — своя сетка (см. GameConstants.FOG_GRID_WIDTH/HEIGHT),
    // revealed[i]=true — клетка сейчас видна (не затенена). Заполняется из
    // FogSnapshot с моим playerId (см. onWorldSnapshot); null, пока не
    // пришёл первый снапшот — drawFogOfWar тогда просто ничего не рисует.
    private boolean[] fogRevealed;
    // Тот же fogRevealed, но перегнанный в маленькую (FOG_GRID_WIDTH x
    // FOG_GRID_HEIGHT, т.е. по пикселю на клетку) alpha-текстуру с
    // билинейной фильтрацией — см. updateFogTexture/drawFogOfWar. GPU сам
    // интерполирует между соседними клетками при растяжении текстуры на
    // всю карту, получая мягкий сглаженный край вместо чётких
    // прямоугольников клеток, безо всякого увеличения сетевого трафика
    // (та же грубая сетка, что и раньше, меняется только СПОСОБ её
    // отрисовки на клиенте) и почти бесплатно по производительности —
    // перезаливка 80x80 пикселей при каждом снапшоте (15/сек) на порядки
    // дешевле самого рендера сцены.
    private final Pixmap fogPixmap = new Pixmap(
            GameConstants.FOG_GRID_WIDTH, GameConstants.FOG_GRID_HEIGHT, Pixmap.Format.Alpha);
    private final Texture fogTexture = new Texture(fogPixmap);
    private String gameOverText = null;
    // Пока не null — показываем этот текст вместо игры (окно уже открыто и
    // отрисовывается, само подключение идёт в фоне — см. GameClient.connect()).
    private String connectionStatusText = "Connecting...";

    public GameScreen(String serverHost) {
        camera.setToOrtho(false, HUD_WIDTH, HUD_HEIGHT);
        hudCamera.setToOrtho(false, HUD_WIDTH, HUD_HEIGHT);
        font.getData().setScale(3f);
        uiFont.getData().setScale(1.3f);

        // Linear — это и есть всё сглаживание тумана войны: GPU сама
        // интерполирует альфу между соседними клетками при растяжении
        // текстуры на всю карту (см. drawFogOfWar). ClampToEdge — чтобы на
        // самом краю карты текстура не подхватывала клетку с
        // противоположного края (поведение по умолчанию, Repeat, дало бы
        // это именно на границе).
        fogTexture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        fogTexture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        // Критично: по умолчанию у Pixmap стоит Blending.SourceOver, и
        // drawPixel(...) не перезаписывает пиксель, а альфа-смешивает его
        // со старым значением. Для прозрачного цвета (alpha=0, которым
        // updateFogTexture помечает просвеченные клетки) SourceOver — это
        // no-op: 0 + old*(1-0) = old, то есть старое значение остаётся
        // как было. В итоге клетка, которая хоть раз была затянута
        // туманом (alpha=1), никогда не смогла бы снова стать прозрачной
        // — новая территория при движении юнита просто не открывалась
        // бы (а обратно закрывать туманом работало бы, потому что
        // alpha=1 при SourceOver всегда просто перезаписывает). None —
        // обычная прямая перезапись пикселя, без смешивания.
        fogPixmap.setBlending(Pixmap.Blending.None);

        engine.addSystem(new InterpolationSystem());
        engine.addSystem(renderSystem);

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
                    for (FogSnapshot fog : snapshot.fog) {
                        if (fog.playerId == client.getPlayerId()) {
                            fogRevealed = fog.revealed;
                            updateFogTexture();
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
                Gdx.app.postRunnable(() -> {
                    float dx = event.toX - event.fromX;
                    float dy = event.toY - event.fromY;
                    float length = (float) Math.sqrt(dx * dx + dy * dy);
                    float fromX = event.fromX;
                    float fromY = event.fromY;
                    if (length > 0.0001f) {
                        // Не даём смещению "перепрыгнуть" саму цель, если она
                        // почему-то оказалась ближе ARROW_MUZZLE_OFFSET —
                        // не должно происходить (attackRadius всегда больше),
                        // но на всякий случай.
                        float offset = Math.min(ARROW_MUZZLE_OFFSET, length * 0.5f);
                        fromX += dx / length * offset;
                        fromY += dy / length * offset;
                    }
                    activeArrows.add(new ArrowVisual(fromX, fromY, event.toX, event.toY));
                });
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
        // На сильном отдалении (большой camera.zoom) фиксированная скорость
        // прокрутки в мировых юнитах ощущалась бы неподвижной — экран же
        // после zoom-аута показывает намного больше мира сразу. Скорость
        // прокрутки растёт вместе с zoom, как и в большинстве RTS-камер.
        float pan = CAMERA_PAN_SPEED * camera.zoom * delta;
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

        if (!MathUtils.isEqual(camera.zoom, targetZoom, 0.0005f)) {
            // Зум "доезжает" до targetZoom плавно, а не мгновенно по щелчку
            // колеса (scrolled только меняет цель), и всё время держит под
            // курсором ТУ ЖЕ мировую точку, что была под ним в начале этого
            // кадра — как в Supreme Commander, карта "тянется" из-под
            // курсора, а не масштабируется от центра экрана. camera.update()
            // вызывается вручную дважды (до и после смены zoom) — иначе
            // unproject ниже пользовался бы устаревшей (позапрошлого кадра)
            // матрицей проекции.
            camera.update();
            Vector3 cursorBefore = camera.unproject(new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0));

            camera.zoom = MathUtils.lerp(camera.zoom, targetZoom, Math.min(1f, ZOOM_LERP_SPEED * delta));

            camera.update();
            Vector3 cursorAfter = camera.unproject(new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0));

            camera.position.x += cursorBefore.x - cursorAfter.x;
            camera.position.y += cursorBefore.y - cursorAfter.y;
        }

        float halfWidth = camera.viewportWidth * camera.zoom / 2f;
        float halfHeight = camera.viewportHeight * camera.zoom / 2f;
        // Если на таком зуме сама камера шире/выше карты целиком (близко к
        // MAX_ZOOM) — не зажимаем позицию между halfWidth и MAP_WIDTH-half
        // Width (при halfWidth > MAP_WIDTH/2 это дало бы min > max и
        // MathUtils.clamp вернул бы бессмысленное значение), а центрируем
        // камеру по этой оси.
        camera.position.x = halfWidth * 2f >= GameConstants.MAP_WIDTH
                ? GameConstants.MAP_WIDTH / 2f
                : MathUtils.clamp(camera.position.x, halfWidth, GameConstants.MAP_WIDTH - halfWidth);
        camera.position.y = halfHeight * 2f >= GameConstants.MAP_HEIGHT
                ? GameConstants.MAP_HEIGHT / 2f
                : MathUtils.clamp(camera.position.y, halfHeight, GameConstants.MAP_HEIGHT - halfHeight);
    }

    /**
     * Колесо мыши — зум. Отрицательный amountY (прокрутка "от себя"/вверх,
     * стандартное поведение LibGDX) приближает — множитель меньше 1,
     * targetZoom уменьшается; положительный (прокрутка "к себе"/вниз)
     * отдаляет. Сам camera.zoom сюда не трогаем — только цель, к которой он
     * плавно едет в updateCamera, отсюда и "плавно переключается" из
     * заказа, а не мгновенный прыжок по щелчку колеса.
     */
    @Override
    public boolean scrolled(float amountX, float amountY) {
        float factor = (float) Math.pow(ZOOM_SCROLL_FACTOR, amountY);
        targetZoom = MathUtils.clamp(targetZoom * factor, MIN_ZOOM, MAX_ZOOM);
        return true;
    }

    /**
     * 0 — чистый тактический вид (RenderSystem рисует юниты/здания как
     * сейчас), 1 — чистый стратегический (см. drawStrategicIcons). Между
     * STRATEGIC_ZOOM_START и STRATEGIC_ZOOM_FULL — плавный кроссфейд обоих
     * слоёв разом (renderSystem.setRenderAlpha(1-factor) и
     * drawStrategicIcons рисует свой слой с alpha=factor), а не мгновенное
     * переключение. Smoothstep, а не линейная интерполяция — мягче на
     * границах полосы (нулевая производная на обоих концах), меньше
     * ощущается как "включили-выключили".
     */
    private float strategicFactor() {
        float t = (camera.zoom - STRATEGIC_ZOOM_START) / (STRATEGIC_ZOOM_FULL - STRATEGIC_ZOOM_START);
        t = MathUtils.clamp(t, 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * Стратегический вид — вместо настоящих фигур юнитов/зданий (кружки,
     * значки-детали, полоски здоровья — см. RenderSystem) укрупнённые
     * значки постоянного ВИДИМОГО НА ЭКРАНЕ размера: их мировой размер
     * растёт вместе с camera.zoom (*camera.zoom ниже), поэтому на любом
     * зуме внутри стратегической полосы они не съёживаются вместе с
     * остальным миром, как обычные юниты — ровно то, ради чего Supreme
     * Commander вообще переключается в этот режим при отдалении: иначе на
     * сильном зуме юниты стали бы неразличимыми точками в 1-2 пикселя.
     * Юнит — кружок, здание — квадрат (та же логика различения, что и в
     * тактическом виде, просто без индивидуальных значков по типу — на
     * таком отдалении они всё равно неразличимы на глаз), оба в цвете
     * игрока (тот же RenderSystem.PLAYER_COLORS, чтобы кроссфейд не менял
     * ещё и цвет одновременно с формой). Рисуется ДО тумана войны (как и
     * тактический слой), чтобы неразведанные области оставались
     * притенёнными одинаково в обоих режимах.
     */
    private void drawStrategicIcons(float alpha) {
        if (alpha <= 0f) {
            return;
        }

        float unitRadius = STRATEGIC_UNIT_ICON_RADIUS_PX * camera.zoom;
        float buildingHalfSize = STRATEGIC_BUILDING_ICON_HALF_SIZE_PX * camera.zoom;
        float selectionRingRadius = unitRadius + STRATEGIC_SELECTION_RING_EXTRA_PX * camera.zoom;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

        for (Entity entity : engine.getEntities()) {
            PositionComponent position = entity.getComponent(PositionComponent.class);
            OwnerComponent owner = entity.getComponent(OwnerComponent.class);
            if (position == null || owner == null) {
                continue;
            }
            if (isHiddenByFog(owner, position)) {
                continue; // тот же принцип, что и в RenderSystem.isHiddenByFog — чужой значок в тумане вообще не рисуем
            }

            Color playerColor = RenderSystem.PLAYER_COLORS[owner.playerId % RenderSystem.PLAYER_COLORS.length];
            boolean isBuilding = entity.getComponent(BuildingComponent.class) != null;

            if (!isBuilding && entity.getComponent(SelectedComponent.class) != null) {
                // Тот же приём, что и в RenderSystem: сперва больший белый
                // круг, потом обычный поверх него — снизу выглядывает
                // кольцом, без отдельного прохода ShapeType.Line.
                shapeRenderer.setColor(1f, 1f, 1f, alpha);
                shapeRenderer.circle(position.position.x, position.position.y, selectionRingRadius, 14);
            }

            shapeRenderer.setColor(playerColor.r, playerColor.g, playerColor.b, playerColor.a * alpha);
            if (isBuilding) {
                shapeRenderer.rect(
                        position.position.x - buildingHalfSize,
                        position.position.y - buildingHalfSize,
                        buildingHalfSize * 2f,
                        buildingHalfSize * 2f);
            } else {
                shapeRenderer.circle(position.position.x, position.position.y, unitRadius, 14);
            }
        }

        shapeRenderer.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
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

        // Кроссфейд тактический/стратегический вид (см. strategicFactor) —
        // тактический слой (RenderSystem, внутри engine.update) рисуется с
        // дополняющей альфой 1-factor, стратегические значки поверх него —
        // с alpha=factor. GL_BLEND нужен именно тут: при alpha=1 (обычный,
        // не зумированный вид) он не меняет картинку (dst-вклад умножается
        // на 1-1=0), но без него частичная альфа при кроссфейде просто не
        // применялась бы (RenderSystem рисовал бы непрозрачно всегда).
        float strategicFactor = strategicFactor();
        renderSystem.setRenderAlpha(1f - strategicFactor);
        renderSystem.setFogVisibility(fogRevealed, client.getPlayerId());
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        engine.update(delta);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        drawStrategicIcons(strategicFactor); // тоже до тумана войны — см. javadoc метода

        updateArrows(delta);
        drawOverlayLines();
        drawArrows();
        drawBuildBeams();
        drawFogOfWar(); // поверх всего мирового — юнитов, зданий, лучей стройки — но до HUD
        // После тумана, не до него: и точка сбора, и очередь приказов —
        // это всегда СВОИ же здание/юнит, включая точки, ведущие в ещё не
        // открытую туманом территорию (можно назначить точку сбора или
        // приказ движения куда угодно на карте, не только в видимую
        // область) — раньше обе рисовались ДО тумана и потому частично
        // прятались под его серой плашкой.
        drawRallyPoints();
        drawOrderQueue();
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
            drawResourcePanel(); // всегда видна во время игры, не только когда что-то выбрано
            // Единая контекстная плашка — сама решает, что показать (или
            // не показывает вовсе, если не выбрано ничего). Рисуется и во
            // время placingBuildingType != null тоже: если выделен
            // строитель, его кнопки построек остаются видны и кликабельны,
            // это и позволяет переключить тип здания на лету — см. touchDown.
            drawInfoPanel();
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

    /** Название здания этого типа — используется и как имя в плашке выделения (drawBuildingInfoPanel), и как подпись кнопки постройки (drawBuilderActionButtons). Единственное место, переводящее BuildingType в текст для игрока. */
    private String buildingTypeLabel(BuildingType type) {
        switch (type) {
            case HOME:
                return "Home";
            case ARCHER_BARRACKS:
                return "Archer Barracks";
            case IRON_MINE:
                return "Iron Mine";
            case POWER_PLANT:
                return "Power Plant";
            case IRON_STORAGE:
                return "Iron Storage";
            case ELECTRICITY_STORAGE:
                return "Electricity Storage";
            case AIRCRAFT_FACTORY:
                return "Aircraft Factory";
            default:
                return "";
        }
    }

    /** Левый X кнопки действия с этим индексом — общая формула что для кнопок очереди, что для кнопок построек, чтобы отрисовка и проверка клика не могли разойтись. */
    private float actionButtonX(int index) {
        return ACTION_BUTTON_X + index * (ACTION_BUTTON_WIDTH + ACTION_BUTTON_GAP);
    }

    /** Какое здание нажато по HUD-координатам клика среди кнопок построек — null, если мимо всех них. */
    private BuildingType buildButtonAt(float hudX, float hudY) {
        if (hudY < ACTION_BUTTON_Y || hudY > ACTION_BUTTON_Y + ACTION_BUTTON_HEIGHT) {
            return null;
        }
        for (int i = 0; i < BUILDABLE_TYPES.length; i++) {
            float buttonX = actionButtonX(i);
            if (hudX >= buttonX && hudX <= buttonX + ACTION_BUTTON_WIDTH) {
                return BUILDABLE_TYPES[i];
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
     * Точка сбора выделенного здания — пунктирная линия от здания до
     * точки и сама точка (бирюзовый круг), только пока это здание
     * выделено (иначе не показываем вовсе — точки сбора чужих или просто
     * невыделенных зданий не должны загромождать экран). Рисуется после
     * тумана войны (см. render()) — точка сбора может быть назначена и
     * за пределами видимой области, туман её скрывать не должен.
     */
    private void drawRallyPoints() {
        if (selectedBuildingId == null) {
            return;
        }
        Entity building = entityFactory.getEntity(selectedBuildingId);
        if (building == null) {
            return;
        }
        ProductionComponent production = building.getComponent(ProductionComponent.class);
        if (production == null || !production.hasRallyPoint) {
            return;
        }

        PositionComponent position = building.getComponent(PositionComponent.class);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        drawDashedLine(position.position.x, position.position.y, production.rallyX, production.rallyY, RALLY_POINT_COLOR);
        shapeRenderer.setColor(RALLY_POINT_COLOR);
        shapeRenderer.circle(production.rallyX, production.rallyY, RALLY_POINT_RADIUS);
        shapeRenderer.end();
    }

    /**
     * Цепочка отложенных приказов выделенного юнита — пунктирная линия
     * через все точки по порядку (юнит -> первая -> вторая -> ...) и
     * маркер на каждой, цвет маркера зависит от типа приказа
     * (ORDER_QUEUE_MOVE_COLOR/ATTACK_COLOR/BUILD_COLOR). Только для ОДНОГО
     * выделенного юнита — как и у здания с точкой сбора: у нескольких
     * выделенных юнитов очереди почти наверняка разные, единую цепочку
     * рисовать было бы бессмысленно. Ничего не рисует, если очередь пуста
     * или юнита сейчас не видно среди своих же сущностей.
     */
    private void drawOrderQueue() {
        if (selectedUnitIds.size() != 1) {
            return;
        }
        Entity unit = entityFactory.getEntity(selectedUnitIds.iterator().next());
        OrderQueueDisplayComponent display = unit != null ? unit.getComponent(OrderQueueDisplayComponent.class) : null;
        if (display == null || display.points.isEmpty()) {
            return;
        }

        PositionComponent position = unit.getComponent(PositionComponent.class);
        float fromX = position.position.x;
        float fromY = position.position.y;

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (QueuedOrderPoint point : display.points) {
            drawDashedLine(fromX, fromY, point.x, point.y, ORDER_QUEUE_LINE_COLOR);
            fromX = point.x;
            fromY = point.y;
        }
        for (QueuedOrderPoint point : display.points) {
            shapeRenderer.setColor(orderQueueMarkerColor(point.type));
            shapeRenderer.circle(point.x, point.y, ORDER_QUEUE_MARKER_RADIUS);
        }
        shapeRenderer.end();
    }

    private Color orderQueueMarkerColor(int typeOrdinal) {
        QueuedOrder.Type[] types = QueuedOrder.Type.values();
        if (typeOrdinal < 0 || typeOrdinal >= types.length) {
            return ORDER_QUEUE_MOVE_COLOR;
        }
        switch (types[typeOrdinal]) {
            case ATTACK:
                return ORDER_QUEUE_ATTACK_COLOR;
            case BUILD:
                return ORDER_QUEUE_BUILD_COLOR;
            default:
                return ORDER_QUEUE_MOVE_COLOR;
        }
    }

    /** Пунктирная линия — общая для точки сбора (RALLY_POINT_COLOR) и цепочки очереди приказов (см. drawOrderQueue), цвет передаёт вызывающий код, не жёстко зашит внутри. */
    private void drawDashedLine(float x1, float y1, float x2, float y2, Color color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < 1f) {
            return;
        }

        float dirX = dx / length;
        float dirY = dy / length;
        float step = RALLY_DASH_LENGTH + RALLY_GAP_LENGTH;

        shapeRenderer.setColor(color);
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
    /**
     * Перегоняет fogRevealed (FOG_GRID_WIDTH x FOG_GRID_HEIGHT булевых
     * значений с сервера, см. onWorldSnapshot) в fogPixmap — по одному
     * пикселю alpha-канала на клетку: непросвеченная клетка получает
     * полную альфу (её и покрасит FOG_COLOR при отрисовке, см.
     * drawFogOfWar), просвеченная — нулевую (совсем прозрачно, ничего не
     * рисуется). Затем сразу перезаливает fogTexture этим пикселем —
     * дальше её растягивает на всю карту сам GPU с билинейной
     * интерполяцией между соседними клетками, отсюда и сглаженный край.
     *
     * Pixmap.drawPixel считает (0,0) верхним левым углом (Y вниз), а мир
     * — Y вверх (cellY=0 у fogRevealed — нижний ряд карты, как и всюду в
     * этом файле, см. старую версию drawFogOfWar в истории git) — без
     * переворота по Y текстура легла бы на карту вверх ногами.
     */
    private void updateFogTexture() {
        if (fogRevealed == null) {
            return;
        }

        for (int cellY = 0; cellY < GameConstants.FOG_GRID_HEIGHT; cellY++) {
            int pixmapY = GameConstants.FOG_GRID_HEIGHT - 1 - cellY;
            for (int cellX = 0; cellX < GameConstants.FOG_GRID_WIDTH; cellX++) {
                int index = cellY * GameConstants.FOG_GRID_WIDTH + cellX;
                boolean revealed = index < fogRevealed.length && fogRevealed[index];
                fogPixmap.drawPixel(cellX, pixmapY, revealed ? 0x00000000 : 0x000000FF);
            }
        }
        fogTexture.draw(fogPixmap, 0, 0);
    }

    /**
     * Одна текстурированная плашка на всю карту вместо сетки отдельных
     * прямоугольников (как было раньше) — сама сетка (fogRevealed) всё
     * та же грубая FOG_GRID_WIDTH x FOG_GRID_HEIGHT, что и раньше (её
     * огрубили нарочно ради сетевого трафика, см. GameConstants
     * .FOG_GRID_CELL_SIZE), но билинейная фильтрация fogTexture (см.
     * конструктор) сама сглаживает переход между соседними клетками при
     * растяжении на MAP_WIDTH x MAP_HEIGHT — край тумана выглядит мягким,
     * а не рублеными прямоугольниками, притом без единого лишнего байта
     * по сети: меняется только СПОСОБ отрисовки уже полученных данных.
     * Ничего не рисует, пока не пришёл первый снапшот (fogRevealed ==
     * null) — до этого момента карта просто не видна вместо ложного "всё
     * в тумане".
     */
    private void drawFogOfWar() {
        if (fogRevealed == null) {
            return;
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        spriteBatch.begin();
        spriteBatch.setColor(FOG_COLOR.r, FOG_COLOR.g, FOG_COLOR.b, FOG_COLOR.a);
        spriteBatch.draw(fogTexture, 0f, 0f, GameConstants.MAP_WIDTH, GameConstants.MAP_HEIGHT);
        // Сбрасываем тинт сразу же — иначе он "утёк" бы в следующий кадр
        // spriteBatch, например в drawResourcePanel/drawCenteredText,
        // которые сами не переустанавливают spriteBatch.setColor и
        // рассчитывают на белый (непрозрачный, без тонировки) цвет по
        // умолчанию.
        spriteBatch.setColor(Color.WHITE);
        spriteBatch.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

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
    /**
     * Единая контекстная плашка внизу экрана — что в ней, решает текущее
     * выделение: здание (drawBuildingInfoPanel), один юнит
     * (drawUnitInfoPanel — для строителя ещё и кнопки построек), несколько
     * юнитов (drawMultiSelectionPanel) — или ничего, если не выделено
     * вообще ничего, тогда плашка просто не рисуется.
     */
    private void drawInfoPanel() {
        if (selectedBuildingId != null) {
            drawBuildingInfoPanel();
        } else if (allSelectedAreBuilders()) {
            drawBuilderInfoPanel();
        } else if (selectedUnitIds.size() == 1) {
            drawUnitInfoPanel(selectedUnitIds.iterator().next());
        } else if (selectedUnitIds.size() > 1) {
            drawMultiSelectionPanel();
        }
    }

    /** Множество id в массив — нужен только для отправки в PlaceIronMineRequest/PlaceBuildingRequest.builderUnitIds, Kryo сеты не шлёт так же просто, как примитивные массивы. */
    private int[] toIntArray(Set<Integer> ids) {
        int[] array = new int[ids.size()];
        int i = 0;
        for (int id : ids) {
            array[i++] = id;
        }
        return array;
    }

    /** Выделен ли хотя бы один юнит, и все выделенные — строители (не смешанное выделение). Пустое выделение — тоже false, "все" из пустого множества было бы формально true, но бессмысленно тут. */
    private boolean allSelectedAreBuilders() {
        if (selectedUnitIds.isEmpty()) {
            return false;
        }
        for (int unitId : selectedUnitIds) {
            Entity unit = entityFactory.getEntity(unitId);
            UnitTypeComponent unitType = unit != null ? unit.getComponent(UnitTypeComponent.class) : null;
            if (unitType == null || unitType.type != UnitType.BUILDER) {
                return false;
            }
        }
        return true;
    }

    /** Тёмный прямоугольник плашки — общий для всех режимов, вызывается уже внутри открытого ShapeType.Filled. */
    private void drawPanelBackground() {
        shapeRenderer.setColor(Color.valueOf("222222"));
        shapeRenderer.rect(PANEL_X, PANEL_Y, PANEL_WIDTH, PANEL_HEIGHT);
    }

    /** Здание: имя, HP, очередь производства (если производит — иначе этого ряда просто нет), кнопка "Demolish" (есть всегда). */
    private void drawBuildingInfoPanel() {
        Entity building = entityFactory.getEntity(selectedBuildingId);
        if (building == null) {
            selectedBuildingId = null; // здание пропало — не должно происходить для своего дома, но на всякий случай
            return;
        }
        HealthComponent health = building.getComponent(HealthComponent.class);
        ProductionComponent production = building.getComponent(ProductionComponent.class);
        ConstructionComponent construction = building.getComponent(ConstructionComponent.class);
        BuildingType buildingType = building.getComponent(BuildingComponent.class).type;
        // Кнопки очереди — только если у СУЩНОСТИ прямо сейчас есть
        // ProductionComponent, не по одному лишь типу здания:
        // BuildingDefinitions.producesUnitTypesFor(buildingType) говорит,
        // что этот ТИП умеет производить в принципе, но ConstructionSystem
        // добавляет сам компонент только по завершении стройки — раньше
        // кнопка рисовалась и была кликабельна даже на ещё строящемся
        // здании (сервер её молча отклонял, но выглядело как рабочая
        // кнопка). Тот же самый признак (production != null) и есть общий
        // "здание готово и функционально" на будущее — им же стоит
        // проверять доступность любого другого функционала здания
        // (радары, щиты и что угодно ещё), не только очередь.
        UnitType[] producible = production != null ? BuildingDefinitions.producesUnitTypesFor(buildingType) : NO_PRODUCIBLE_TYPES;

        shapeRenderer.setProjectionMatrix(hudCamera.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        drawPanelBackground();

        shapeRenderer.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i < producible.length; i++) {
            shapeRenderer.rect(actionButtonX(i), ACTION_BUTTON_Y, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT);
        }

        if (production != null && production.queuedCount > 0) {
            float buildTime = production.producingUnitType != null
                    ? UnitDefinitions.buildTimeFor(production.producingUnitType) : GameConstants.UNIT_BUILD_TIME;
            float fraction = MathUtils.clamp(production.progress / buildTime, 0f, 1f);

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
        uiFont.draw(spriteBatch, buildingTypeLabel(buildingType), PANEL_X + 15f, NAME_TEXT_Y);
        uiFont.draw(spriteBatch, "HP: " + health.currentHealth + "/" + health.maxHealth, PANEL_X + 15f, HP_TEXT_Y);
        for (int i = 0; i < producible.length; i++) {
            uiFont.draw(spriteBatch, "+" + unitTypeLabel(producible[i]), actionButtonX(i) + 10f, ACTION_BUTTON_Y + ACTION_BUTTON_HEIGHT - 14f);
        }
        if (construction != null) {
            // Занимает то же место, где были бы кнопки очереди — их тут
            // нет, раз функционал недоступен до завершения стройки.
            int percent = Math.round(MathUtils.clamp(1f - construction.remaining / construction.totalTime, 0f, 1f) * 100f);
            uiFont.draw(spriteBatch, "Under construction: " + percent + "%", ACTION_BUTTON_X, ACTION_BUTTON_Y + ACTION_BUTTON_HEIGHT - 14f);
        }
        if (production != null) {
            uiFont.draw(spriteBatch, "Queue: " + production.queuedCount, PROGRESS_BAR_X + PROGRESS_BAR_WIDTH + 20f, PROGRESS_BAR_Y + 11f);
        }
        uiFont.draw(spriteBatch, "Demolish", DEMOLISH_BUTTON_X + 10f, DEMOLISH_BUTTON_Y + DEMOLISH_BUTTON_HEIGHT - 10f);
        spriteBatch.end();

        // Возвращаем world-камеру шейп-рендереру и спрайт-батчу для следующего кадра.
        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);
    }

    /** Один юнит: имя, HP — и для строителя ещё ряд кнопок построек вместо очереди (строить может только он). */
    /** Один невыделенный-как-группа-строителей юнит: просто имя и HP — для строителя (одного, не группы) см. drawBuilderInfoPanel, у него ещё и кнопки построек. */
    private void drawUnitInfoPanel(int unitId) {
        Entity unit = entityFactory.getEntity(unitId);
        if (unit == null) {
            return; // юнит уже пропал из-под курсора выделения между снапшотами — просто ничего не рисуем в этом кадре
        }
        HealthComponent health = unit.getComponent(HealthComponent.class);
        UnitTypeComponent unitTypeComponent = unit.getComponent(UnitTypeComponent.class);
        UnitType unitType = unitTypeComponent != null ? unitTypeComponent.type : UnitType.WARRIOR;

        shapeRenderer.setProjectionMatrix(hudCamera.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        drawPanelBackground();
        shapeRenderer.end();

        spriteBatch.setProjectionMatrix(hudCamera.combined);
        spriteBatch.begin();
        uiFont.setColor(Color.WHITE);
        uiFont.draw(spriteBatch, unitTypeLabel(unitType), PANEL_X + 15f, NAME_TEXT_Y);
        uiFont.draw(spriteBatch, "HP: " + health.currentHealth + "/" + health.maxHealth, PANEL_X + 15f, HP_TEXT_Y);
        spriteBatch.end();

        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);
    }

    /**
     * Один строитель или целая группа строителей (allSelectedAreBuilders
     * — единственное, из-за чего этот метод вообще вызывается, см.
     * drawInfoPanel) — кнопки построек вместо очереди производства, ведь
     * строить может только строитель. При ровно одном строителе ещё и его
     * HP (у группы одного числа на всех нет — просто счётчик вместо имени).
     */
    private void drawBuilderInfoPanel() {
        shapeRenderer.setProjectionMatrix(hudCamera.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        drawPanelBackground();
        shapeRenderer.setColor(Color.LIGHT_GRAY);
        for (int i = 0; i < BUILDABLE_TYPES.length; i++) {
            shapeRenderer.rect(actionButtonX(i), ACTION_BUTTON_Y, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT);
        }
        shapeRenderer.end();

        spriteBatch.setProjectionMatrix(hudCamera.combined);
        spriteBatch.begin();
        uiFont.setColor(Color.WHITE);
        if (selectedUnitIds.size() == 1) {
            uiFont.draw(spriteBatch, "Builder", PANEL_X + 15f, NAME_TEXT_Y);
            Entity builder = entityFactory.getEntity(selectedUnitIds.iterator().next());
            HealthComponent health = builder != null ? builder.getComponent(HealthComponent.class) : null;
            if (health != null) {
                uiFont.draw(spriteBatch, "HP: " + health.currentHealth + "/" + health.maxHealth, PANEL_X + 15f, HP_TEXT_Y);
            }
        } else {
            uiFont.draw(spriteBatch, selectedUnitIds.size() + " Builders", PANEL_X + 15f, NAME_TEXT_Y);
        }
        for (int i = 0; i < BUILDABLE_TYPES.length; i++) {
            uiFont.draw(spriteBatch, buildingTypeLabel(BUILDABLE_TYPES[i]), actionButtonX(i) + 10f, ACTION_BUTTON_Y + ACTION_BUTTON_HEIGHT - 14f);
        }
        spriteBatch.end();

        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);
    }

    /** Несколько юнитов сразу — просто счётчик, без имени/HP отдельного юнита (они все разные) и без кнопок. */
    private void drawMultiSelectionPanel() {
        shapeRenderer.setProjectionMatrix(hudCamera.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        drawPanelBackground();
        shapeRenderer.end();

        spriteBatch.setProjectionMatrix(hudCamera.combined);
        spriteBatch.begin();
        uiFont.setColor(Color.WHITE);
        uiFont.draw(spriteBatch, selectedUnitIds.size() + " units selected", PANEL_X + 15f, NAME_TEXT_Y);
        spriteBatch.end();

        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);
    }

    private String unitTypeLabel(UnitType type) {
        switch (type) {
            case WARRIOR:
                return "Warrior";
            case ARCHER:
                return "Archer";
            case BUILDER:
                return "Builder";
            case SCOUT:
                return "Scout";
            case ATTACK_AIRCRAFT:
                return "Attack Aircraft";
            default:
                return "";
        }
    }

    /** Какой тип юнита нажат по HUD-координатам клика среди кнопок очереди этого здания — null, если мимо всех них. */
    private UnitType queueButtonAt(float hudX, float hudY, UnitType[] producible) {
        if (hudY < ACTION_BUTTON_Y || hudY > ACTION_BUTTON_Y + ACTION_BUTTON_HEIGHT) {
            return null;
        }
        for (int i = 0; i < producible.length; i++) {
            float x = actionButtonX(i);
            if (hudX >= x && hudX <= x + ACTION_BUTTON_WIDTH) {
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
            // Клик по кнопке постройки в панели выделенного строителя (или
            // группы строителей) — проверяем ПЕРВЫМ, ещё до проверки "уже
            // что-то размещаем": так можно переключить тип здания на лету
            // во время размещения, потому что панель строителя остаётся
            // видна и кликабельна всё это время (мы не снимаем с него
            // выделение ниже, когда начинаем размещение).
            if (selectedBuildingId == null && allSelectedAreBuilders()) {
                Vector3 hudPoint = hudCamera.unproject(new Vector3(screenX, screenY, 0));
                BuildingType clickedBuildingType = buildButtonAt(hudPoint.x, hudPoint.y);
                if (clickedBuildingType != null) {
                    placingBuildingType = clickedBuildingType;
                    return true;
                }
            }
        }

        if (placingBuildingType != null) {
            if (button == Input.Buttons.LEFT && buildGhostValid) {
                // Строители, выделенные сейчас (те же самые, чьей панелью
                // выбирали тип здания — выделение не менялось всё это
                // время, см. комментарий выше), автоматически пойдут
                // строить то, что вот-вот появится — см. javadoc
                // PlaceIronMineRequest.builderUnitIds, почему список едет
                // прямо с этой заявкой, а не отдельным запросом следом.
                // queue — тот же shift-модификатор, что и у обычного
                // приказа на стройку (см. touchDown ниже и javadoc
                // PlaceIronMineRequest.queue): без него строители,
                // занятые чем-то другим, немедленно бросают это и едут
                // строить новое здание, с ним — приказ просто встаёт в
                // конец их очереди.
                int[] builderUnitIds = toIntArray(selectedUnitIds);
                boolean queue = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
                if (placingBuildingType == BuildingType.IRON_MINE) {
                    client.requestPlaceIronMine(ironMineSnapDepositIndex, builderUnitIds, queue);
                } else {
                    client.requestPlaceBuilding(placingBuildingType, buildGhostX, buildGhostY, builderUnitIds, queue);
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
                // Кнопка очереди кликабельна, только если у здания СЕЙЧАС
                // есть ProductionComponent (готово и правда производит) —
                // та же причина, что и в drawBuildingInfoPanel: тип здания
                // сам по себе не говорит, достроено ли оно.
                ProductionComponent selectedProduction = selectedBuilding != null
                        ? selectedBuilding.getComponent(ProductionComponent.class) : null;
                if (selectedProduction != null) {
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
        } else if (button == Input.Buttons.RIGHT && selectedBuildingId != null) {
            // Здание выделено — правый клик по карте ставит точку сбора
            // (не выделение и не приказ юниту, тех тут просто нет). Только
            // у производящего здания есть куда собирать — у остальных
            // (шахта, обе электростанции, оба хранилища) клик просто
            // ничего не делает.
            Entity selectedBuilding = entityFactory.getEntity(selectedBuildingId);
            if (selectedBuilding != null && selectedBuilding.getComponent(ProductionComponent.class) != null) {
                Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                client.requestSetRallyPoint(selectedBuildingId, world.x, world.y);
            }
        } else if (button == Input.Buttons.RIGHT && !selectedUnitIds.isEmpty()) {
            // Shift — добавить приказ в очередь, а не заменить текущий (см.
            // javadoc MoveUnitRequest.queue) — тот же модификатор для всех
            // трёх видов приказа, выдаваемых отсюда.
            boolean queue = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
            Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
            Entity target = findEntityNear(world.x, world.y);
            if (target != null && isEnemy(target)) {
                issueAttackOrder(target.getComponent(UnitComponent.class).unitId, queue);
            } else if (target != null && !isEnemy(target) && target.getComponent(ConstructionComponent.class) != null) {
                // Своё (не чужое — isEnemy(target) уже false тут исключает и
                // "ничьё" быть не может, раз ConstructionComponent вообще
                // есть) недостроенное здание — строители из выделения идут
                // его достраивать, остальные юниты выделения просто
                // игнорируют клик (см. javadoc issueBuildOrder).
                issueBuildOrder(target.getComponent(UnitComponent.class).unitId, queue);
            } else {
                issueMoveOrder(world.x, world.y, queue);
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
        if (keycode == Input.Keys.B) {
            // Кнопки построек теперь в панели выделенного строителя, не в
            // отдельном меню — клавиша B осталась только как способ
            // отменить уже начатое размещение здания.
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
            // drawBuildingInfoPanel).
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

    private void issueMoveOrder(float targetX, float targetY, boolean queue) {
        List<Integer> ids = new ArrayList<>(selectedUnitIds);
        int count = ids.size();

        if (count == 1) {
            client.requestMoveUnit(ids.get(0), targetX, targetY, queue);
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
            client.requestMoveUnit(ids.get(i), targetX + offsetX, targetY + offsetY, queue);
        }
    }

    private void issueAttackOrder(int targetUnitId, boolean queue) {
        // Тут спред не нужен: CombatSystem сама останавливает каждого
        // атакующего на ATTACK_RANGE от цели, и подходя с разных сторон,
        // юниты естественным образом расходятся вокруг цели кольцом.
        for (int unitId : selectedUnitIds) {
            client.requestAttackUnit(unitId, targetUnitId, queue);
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
    private void issueBuildOrder(int targetBuildingUnitId, boolean queue) {
        for (int unitId : selectedUnitIds) {
            Entity unit = entityFactory.getEntity(unitId);
            if (unit == null) {
                continue;
            }
            UnitTypeComponent unitType = unit.getComponent(UnitTypeComponent.class);
            if (unitType != null && unitType.type == UnitType.BUILDER) {
                client.requestBuildOrder(unitId, targetBuildingUnitId, queue);
            }
        }
    }

    // ---- Вспомогательное ----

    private boolean isEnemy(Entity entity) {
        OwnerComponent owner = entity.getComponent(OwnerComponent.class);
        return owner != null && owner.playerId != client.getPlayerId();
    }

    /**
     * Та же проверка, что и в RenderSystem.isHiddenByFog (дублируется
     * здесь, а не выносится в общий метод, — единственное, что вызывает
     * это тут, drawStrategicIcons, и там своя, не Ashley-система, версия
     * цикла, так что общий метод потребовал бы либо статического
     * хелпера в GameConstants ради одной строки формулы, либо
     * протаскивания RenderSystem в этот метод — ни то ни другое не
     * дешевле). Своя сущность видна всегда, чужая — только если её
     * клетка сейчас просвечена туманом.
     */
    private boolean isHiddenByFog(OwnerComponent owner, PositionComponent position) {
        if (fogRevealed == null || owner.playerId == client.getPlayerId()) {
            return false;
        }
        int cellX = (int) (position.position.x / GameConstants.FOG_GRID_CELL_SIZE);
        int cellY = (int) (position.position.y / GameConstants.FOG_GRID_CELL_SIZE);
        if (cellX < 0 || cellX >= GameConstants.FOG_GRID_WIDTH || cellY < 0 || cellY >= GameConstants.FOG_GRID_HEIGHT) {
            return true;
        }
        int index = cellY * GameConstants.FOG_GRID_WIDTH + cellX;
        return index >= fogRevealed.length || !fogRevealed[index];
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

    /**
     * Юнит — фиксированный радиус (или радиус укрупнённого стратегического
     * значка, если он сейчас крупнее — см. drawStrategicIcons: на сильном
     * отдалении юнит рисуется заметно больше своего настоящего мирового
     * размера, клик обязан попадать по нему так же надёжно, а не только с
     * пиксельной точностью по крошечному настоящему кружку под значком).
     * Здание (дом/казарма/здание добычи, в любом состоянии) — до угла его
     * фактического прямоугольника, с тем же поправками на стратегический
     * значок.
     */
    private float clickRadiusFor(Entity entity) {
        if (entity.getComponent(BuildingComponent.class) == null) {
            return Math.max(UNIT_CLICK_RADIUS, STRATEGIC_UNIT_ICON_RADIUS_PX * camera.zoom);
        }
        float halfWidth = BuildingSizes.halfWidth(entity);
        float halfHeight = BuildingSizes.halfHeight(entity);
        float baseRadius = (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
        float strategicRadius = STRATEGIC_BUILDING_ICON_HALF_SIZE_PX * camera.zoom * (float) Math.sqrt(2);
        return Math.max(baseRadius, strategicRadius);
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
        fogTexture.dispose();
        fogPixmap.dispose();
    }
}
