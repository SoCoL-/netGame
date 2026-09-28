package ru.socol.supreme;

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
import com.badlogic.gdx.math.Vector3;
import ru.socol.supreme.network.GameClient;
import ru.socol.supreme.shared.LobbyPhase;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.LobbyListMessage;
import ru.socol.supreme.shared.network.messages.LobbySummary;

import java.util.ArrayList;
import java.util.List;

/**
 * Браузер лобби — первый экран после подключения (см. javadoc Main): список
 * всех текущих комнат (LobbyListMessage, обновляется сервером сам, безо
 * всякого ручного "обновить") и кнопка создать свою. Клик по строке уже
 * существующей WAITING-комнаты с свободным слотом сразу отправляет
 * JoinLobbyRequest — отдельного подтверждения не требуется, тем же приёмом,
 * что и выбор здания на постройку в GameScreen (клик = команда, не просто
 * выделение).
 *
 * Тот же стиль разметки, что и у GameScreen (ShapeRenderer для прямоугольных
 * кнопок/панелей, SpriteBatch+BitmapFont для текста, вручную посчитанные
 * прямоугольники для hit-теста в touchDown) — тут нет своего Ashley-движка,
 * рисовать нечего, кроме списка и пары кнопок, так что полноценный UI-тулкит
 * (scene2d) был бы куда больше кода ради того же результата.
 */
public class LobbyBrowserScreen extends InputAdapter implements Screen {

    private static final float HUD_WIDTH = 1024f;
    private static final float HUD_HEIGHT = 768f;

    private static final float CREATE_BUTTON_X = 40f;
    private static final float CREATE_BUTTON_Y = HUD_HEIGHT - 90f;
    private static final float CREATE_BUTTON_WIDTH = 220f;
    private static final float CREATE_BUTTON_HEIGHT = 40f;

    private static final float ROW_X = 40f;
    private static final float ROW_WIDTH = HUD_WIDTH - 80f;
    private static final float ROW_HEIGHT = 44f;
    private static final float FIRST_ROW_Y = HUD_HEIGHT - 160f;

    private final GameClient client;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final ShapeRenderer shapeRenderer = new ShapeRenderer();
    private final SpriteBatch spriteBatch = new SpriteBatch();
    private final BitmapFont titleFont = Fonts.create(33);
    private final BitmapFont font = Fonts.create(20);
    private final GlyphLayout layout = new GlyphLayout();

    private List<LobbySummary> lobbies = new ArrayList<>();

    /** Пока не null — рисуется вместо/поверх списка (ошибка сервера, статус подключения). null — список показывается как обычно. */
    private String statusText = "Подключение...";

    public LobbyBrowserScreen(GameClient client) {
        this.client = client;
        camera.setToOrtho(false, HUD_WIDTH, HUD_HEIGHT);
    }

    /** Вызывается из Main при каждом LobbyListMessage — см. её же javadoc. */
    public void updateLobbyList(LobbyListMessage message) {
        lobbies = message.lobbies;
        statusText = null;
    }

    /** Вызывается из Main при ErrorResponse, пришедшем, пока этот экран показан (например "Комната уже заполнена"). */
    public void onError(ErrorResponse error) {
        statusText = error.message;
    }

    /** Вызывается из Main, если самое первое connect() не удался. */
    public void onConnectFailed(String message) {
        statusText = "Не удалось подключиться" + (message != null ? ": " + message : "");
    }

    @Override
    public void show() {
        Gdx.input.setInputProcessor(this);
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        camera.update();
        shapeRenderer.setProjectionMatrix(camera.combined);
        spriteBatch.setProjectionMatrix(camera.combined);

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(Color.valueOf("2E7D32"));
        shapeRenderer.rect(CREATE_BUTTON_X, CREATE_BUTTON_Y, CREATE_BUTTON_WIDTH, CREATE_BUTTON_HEIGHT);

        for (int i = 0; i < lobbies.size(); i++) {
            LobbySummary lobby = lobbies.get(i);
            float y = FIRST_ROW_Y - i * (ROW_HEIGHT + 8f);
            boolean joinable = lobby.phase == LobbyPhase.WAITING.ordinal() && lobby.occupiedSlots < lobby.maxSlots;
            shapeRenderer.setColor(joinable ? Color.valueOf("37474F") : Color.valueOf("263238"));
            shapeRenderer.rect(ROW_X, y, ROW_WIDTH, ROW_HEIGHT);
        }
        shapeRenderer.end();

        spriteBatch.begin();
        titleFont.setColor(Color.WHITE);
        titleFont.draw(spriteBatch, "Лобби", 40f, HUD_HEIGHT - 12f);

        font.setColor(Color.WHITE);
        layout.setText(font, "Создать");
        font.draw(spriteBatch, "Создать",
                CREATE_BUTTON_X + (CREATE_BUTTON_WIDTH - layout.width) / 2f,
                CREATE_BUTTON_Y + (CREATE_BUTTON_HEIGHT + layout.height) / 2f);

        if (lobbies.isEmpty() && statusText == null) {
            font.setColor(Color.LIGHT_GRAY);
            font.draw(spriteBatch, "Пока нет открытых комнат — создайте свою", ROW_X, FIRST_ROW_Y + ROW_HEIGHT + 20f);
        }

        for (int i = 0; i < lobbies.size(); i++) {
            LobbySummary lobby = lobbies.get(i);
            float y = FIRST_ROW_Y - i * (ROW_HEIGHT + 8f);
            boolean joinable = lobby.phase == LobbyPhase.WAITING.ordinal() && lobby.occupiedSlots < lobby.maxSlots;
            font.setColor(joinable ? Color.WHITE : Color.GRAY);
            font.draw(spriteBatch, lobby.name, ROW_X + 16f, y + ROW_HEIGHT - 14f);
            String status = lobby.phase == LobbyPhase.IN_GAME.ordinal() ? "матч идёт"
                    : lobby.phase == LobbyPhase.STARTING.ordinal() ? "стартует..."
                    : (lobby.occupiedSlots + "/" + lobby.maxSlots);
            layout.setText(font, status);
            font.draw(spriteBatch, status, ROW_X + ROW_WIDTH - layout.width - 16f, y + ROW_HEIGHT - 14f);
        }

        if (statusText != null) {
            font.setColor(Color.valueOf("FFCA28"));
            layout.setText(font, statusText);
            font.draw(spriteBatch, statusText, (HUD_WIDTH - layout.width) / 2f, 60f);
        }
        spriteBatch.end();
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (button != Input.Buttons.LEFT) {
            return true;
        }
        Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));

        if (isInside(world.x, world.y, CREATE_BUTTON_X, CREATE_BUTTON_Y, CREATE_BUTTON_WIDTH, CREATE_BUTTON_HEIGHT)) {
            promptCreateLobby();
            return true;
        }

        for (int i = 0; i < lobbies.size(); i++) {
            LobbySummary lobby = lobbies.get(i);
            float y = FIRST_ROW_Y - i * (ROW_HEIGHT + 8f);
            if (isInside(world.x, world.y, ROW_X, y, ROW_WIDTH, ROW_HEIGHT)) {
                boolean joinable = lobby.phase == LobbyPhase.WAITING.ordinal() && lobby.occupiedSlots < lobby.maxSlots;
                if (joinable) {
                    client.requestJoinLobby(lobby.lobbyId);
                }
                return true;
            }
        }
        return true;
    }

    /**
     * Нативный системный диалог ввода текста (Gdx.input.getTextInput) —
     * простейший способ получить название комнаты от игрока без написания
     * собственного текстового поля с курсором/фокусом, чего в этом проекте
     * больше нигде нет. Пустой ввод/отмена — не ошибка, сервер сам
     * подставит дефолтное имя "Лобби #N" (см. javadoc LobbyManager
     * .handleCreateLobby), поэтому canceled() тоже просто отправляет запрос
     * с пустым именем, а не отменяет создание совсем — так проще для
     * игрока, случайно закрывшего диалог, чем считать это отказом.
     */
    private void promptCreateLobby() {
        Gdx.input.getTextInput(new Input.TextInputListener() {
            @Override
            public void input(String text) {
                client.requestCreateLobby(text);
            }

            @Override
            public void canceled() {
                client.requestCreateLobby("");
            }
        }, "Название комнаты", "", "Моя комната");
    }

    private static boolean isInside(float x, float y, float rectX, float rectY, float width, float height) {
        return x >= rectX && x <= rectX + width && y >= rectY && y <= rectY + height;
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
        titleFont.dispose();
        font.dispose();
    }
}
