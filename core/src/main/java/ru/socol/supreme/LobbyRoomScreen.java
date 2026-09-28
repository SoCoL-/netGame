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
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.LobbyPhase;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.LobbyStateMessage;

/**
 * Комната одного лобби — показывает оба слота (имя + готовность), свою
 * кнопку "Готов"/"Не готов" и кнопку выхода обратно в список. Пока
 * phase == STARTING — вместо кнопки "Готов" крупно тикает обратный отсчёт
 * (countdownRemaining), ровно как просили в постановке задачи. После конца
 * матча в ту же комнату (тот же lobbyId) приходит новый LobbyStateMessage
 * (реванш) — Main обнаруживает совпадающий lobbyId и не пересоздаёт этот
 * экран, а вызывает updateState() на уже существующем, см. javadoc Main
 * .onLobbyState.
 */
public class LobbyRoomScreen extends InputAdapter implements Screen {

    private static final float HUD_WIDTH = 1024f;
    private static final float HUD_HEIGHT = 768f;

    private static final float SLOT_X = 40f;
    private static final float SLOT_WIDTH = HUD_WIDTH - 80f;
    private static final float SLOT_HEIGHT = 60f;
    private static final float FIRST_SLOT_Y = HUD_HEIGHT - 160f;

    private static final float READY_BUTTON_X = 40f;
    private static final float READY_BUTTON_Y = 80f;
    private static final float READY_BUTTON_WIDTH = 220f;
    private static final float READY_BUTTON_HEIGHT = 44f;

    private static final float LEAVE_BUTTON_X = HUD_WIDTH - 40f - 180f;
    private static final float LEAVE_BUTTON_Y = 80f;
    private static final float LEAVE_BUTTON_WIDTH = 180f;
    private static final float LEAVE_BUTTON_HEIGHT = 44f;

    private final GameClient client;
    private final OrthographicCamera camera = new OrthographicCamera();
    private final ShapeRenderer shapeRenderer = new ShapeRenderer();
    private final SpriteBatch spriteBatch = new SpriteBatch();
    private final BitmapFont titleFont = Fonts.create(33);
    private final BitmapFont font = Fonts.create(21);
    private final BitmapFont countdownFont = Fonts.create(60);
    private final GlyphLayout layout = new GlyphLayout();

    private int lobbyId;
    private String name;
    private String[] slotPlayerName;
    private boolean[] slotReady;
    private int yourSlotIndex;
    private int phase;
    private float countdownRemaining;

    private String statusText;

    public LobbyRoomScreen(GameClient client, LobbyStateMessage initialState) {
        this.client = client;
        camera.setToOrtho(false, HUD_WIDTH, HUD_HEIGHT);
        applyState(initialState);
    }

    public int getLobbyId() {
        return lobbyId;
    }

    /** Вызывается из Main вместо пересоздания экрана, пока это та же самая комната (см. её же javadoc). */
    public void updateState(LobbyStateMessage message) {
        applyState(message);
    }

    private void applyState(LobbyStateMessage message) {
        this.lobbyId = message.lobbyId;
        this.name = message.name;
        this.slotPlayerName = message.slotPlayerName;
        this.slotReady = message.slotReady;
        this.yourSlotIndex = message.yourSlotIndex;
        this.phase = message.phase;
        this.countdownRemaining = message.countdownRemaining;
    }

    public void onError(ErrorResponse error) {
        statusText = error.message;
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

        boolean starting = phase == LobbyPhase.STARTING.ordinal();
        boolean ownReady = yourSlotIndex >= 0 && yourSlotIndex < slotReady.length && slotReady[yourSlotIndex];

        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        for (int slot = 0; slot < GameConstants.MAX_PLAYERS; slot++) {
            float y = FIRST_SLOT_Y - slot * (SLOT_HEIGHT + 12f);
            boolean occupied = slot < slotPlayerName.length && slotPlayerName[slot] != null;
            boolean ready = slot < slotReady.length && slotReady[slot];
            shapeRenderer.setColor(!occupied ? Color.valueOf("263238") : ready ? Color.valueOf("2E7D32") : Color.valueOf("37474F"));
            shapeRenderer.rect(SLOT_X, y, SLOT_WIDTH, SLOT_HEIGHT);
        }

        if (!starting) {
            shapeRenderer.setColor(ownReady ? Color.valueOf("B71C1C") : Color.valueOf("2E7D32"));
            shapeRenderer.rect(READY_BUTTON_X, READY_BUTTON_Y, READY_BUTTON_WIDTH, READY_BUTTON_HEIGHT);
        }
        shapeRenderer.setColor(Color.valueOf("616161"));
        shapeRenderer.rect(LEAVE_BUTTON_X, LEAVE_BUTTON_Y, LEAVE_BUTTON_WIDTH, LEAVE_BUTTON_HEIGHT);
        shapeRenderer.end();

        spriteBatch.begin();
        titleFont.setColor(Color.WHITE);
        titleFont.draw(spriteBatch, name != null ? name : "Лобби", 40f, HUD_HEIGHT - 12f);

        for (int slot = 0; slot < GameConstants.MAX_PLAYERS; slot++) {
            float y = FIRST_SLOT_Y - slot * (SLOT_HEIGHT + 12f);
            boolean occupied = slot < slotPlayerName.length && slotPlayerName[slot] != null;
            boolean ready = slot < slotReady.length && slotReady[slot];
            String label = occupied ? slotPlayerName[slot] : "Свободный слот";
            font.setColor(Color.WHITE);
            font.draw(spriteBatch, label, SLOT_X + 16f, y + SLOT_HEIGHT - 20f);
            if (occupied) {
                String readyLabel = ready ? "Готов" : "Не готов";
                layout.setText(font, readyLabel);
                font.draw(spriteBatch, readyLabel, SLOT_X + SLOT_WIDTH - layout.width - 16f, y + SLOT_HEIGHT - 20f);
            }
        }

        if (starting) {
            String countdownText = "Старт через " + Math.max(0, (int) Math.ceil(countdownRemaining));
            countdownFont.setColor(Color.valueOf("FFCA28"));
            layout.setText(countdownFont, countdownText);
            countdownFont.draw(spriteBatch, countdownText, (HUD_WIDTH - layout.width) / 2f, READY_BUTTON_Y + READY_BUTTON_HEIGHT + 20f);
        } else {
            font.setColor(Color.WHITE);
            String readyButtonLabel = ownReady ? "Не готов" : "Готов";
            layout.setText(font, readyButtonLabel);
            font.draw(spriteBatch, readyButtonLabel,
                    READY_BUTTON_X + (READY_BUTTON_WIDTH - layout.width) / 2f,
                    READY_BUTTON_Y + (READY_BUTTON_HEIGHT + layout.height) / 2f);
        }

        layout.setText(font, "Выйти");
        font.draw(spriteBatch, "Выйти",
                LEAVE_BUTTON_X + (LEAVE_BUTTON_WIDTH - layout.width) / 2f,
                LEAVE_BUTTON_Y + (LEAVE_BUTTON_HEIGHT + layout.height) / 2f);

        if (statusText != null) {
            font.setColor(Color.valueOf("FFCA28"));
            layout.setText(font, statusText);
            font.draw(spriteBatch, statusText, (HUD_WIDTH - layout.width) / 2f, 200f);
        }
        spriteBatch.end();
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (button != Input.Buttons.LEFT) {
            return true;
        }
        Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));

        boolean starting = phase == LobbyPhase.STARTING.ordinal();
        boolean ownReady = yourSlotIndex >= 0 && yourSlotIndex < slotReady.length && slotReady[yourSlotIndex];

        if (!starting && isInside(world.x, world.y, READY_BUTTON_X, READY_BUTTON_Y, READY_BUTTON_WIDTH, READY_BUTTON_HEIGHT)) {
            client.requestSetReady(!ownReady);
            return true;
        }
        if (isInside(world.x, world.y, LEAVE_BUTTON_X, LEAVE_BUTTON_Y, LEAVE_BUTTON_WIDTH, LEAVE_BUTTON_HEIGHT)) {
            client.requestLeaveLobby();
            return true;
        }
        return true;
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
        countdownFont.dispose();
    }
}
