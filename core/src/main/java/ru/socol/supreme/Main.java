package ru.socol.supreme;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import ru.socol.supreme.network.GameClient;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.GameStartedMessage;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.LobbyListMessage;
import ru.socol.supreme.shared.network.messages.LobbyStateMessage;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.WorldSnapshot;

/**
 * {@link com.badlogic.gdx.ApplicationListener} implementation shared by all
 * platforms. serverHost is supplied by each platform launcher (CLI arg on
 * desktop, a constant on Android — see AndroidLauncher for why).
 *
 * Владеет ОДНИМ GameClient на весь процесс жизни приложения — раньше
 * GameScreen создавал и подключал свой собственный клиент, потому что одно
 * соединение = один матч; теперь одно соединение живёт куда дольше (браузер
 * лобби -> комната лобби -> сама игра -> после game over снова та же
 * комната, реванш — см. javadoc GameClient), так что подключаться заново
 * при каждом матче незачем и вредно (сервер и так помнит, в каком лобби
 * сидит это соединение). GameClient поддерживает только ОДНОГО слушателя
 * (см. её же javadoc), поэтому им становится сама Main, один раз на всё
 * время жизни приложения, и просто пересылает каждое сообщение в публичный
 * метод ТЕКУЩЕГО экрана (getScreen()) — тем самым методам, которыми раньше
 * владел анонимный GameClientListener внутри самого GameScreen.
 */
public class Main extends Game implements GameClient.GameClientListener {

    private final String serverHost;
    private final GameClient client = new GameClient();

    /**
     * Единственный долгоживущий экземпляр браузера лобби — переиспользуется
     * между показами (а не пересоздаётся на каждый LobbyListMessage), чтобы
     * не терять статус/состояние экрана, пока игрок его просто разглядывает.
     */
    private LobbyBrowserScreen browserScreen;

    /**
     * Экран текущей комнаты лобби — пересоздаётся, только когда реально
     * меняется САМА комната (другой lobbyId), а не на каждое её обновление
     * (тик отсчёта, смена готовности, реванш той же комнаты после game over)
     * — те применяются к уже существующему экземпляру через updateState(),
     * см. onLobbyState.
     */
    private LobbyRoomScreen roomScreen;

    public Main(String serverHost) {
        // ВАЖНО: этот конструктор выполняется как аргумент "new Main(...)"
        // ДО того, как отработает конструктор Lwjgl3Application — то есть
        // ДО того, как LibGDX присвоит Gdx.app. Здесь Gdx.app == null, и
        // Gdx.app.log(...) в этом месте бросит NullPointerException. Любое
        // логирование, завязанное на Gdx.*, должно жить в create() —
        // единственном месте, где эти статические поля уже гарантированно
        // проинициализированы (см. ниже).
        this.serverHost = serverHost;
    }

    @Override
    public void create() {
        System.out.println("Main.create() started, serverHost = " + serverHost);
        Gdx.app.log("StartApp", "serverHost: " + serverHost);
        browserScreen = new LobbyBrowserScreen(client);
        setScreen(browserScreen);
        client.connect(serverHost, this);
    }

    // ---- GameClient.GameClientListener — вызывается из сетевого потока KryoNet, см. постановку каждого метода ниже про Gdx.app.postRunnable ----

    @Override
    public void onJoinResponse(JoinResponse response) {
        Gdx.app.postRunnable(() -> {
            Gdx.app.log("Network", response.message);
            if (!response.accepted) {
                browserScreen.onError(new ErrorResponse(response.message));
            }
        });
    }

    @Override
    public void onLobbyList(LobbyListMessage message) {
        Gdx.app.postRunnable(() -> {
            browserScreen.updateLobbyList(message);
            if (getScreen() != browserScreen) {
                setScreen(browserScreen);
            }
        });
    }

    @Override
    public void onLobbyState(LobbyStateMessage message) {
        Gdx.app.postRunnable(() -> {
            Screen previous = getScreen();
            LobbyRoomScreen target;
            if (roomScreen != null && roomScreen.getLobbyId() == message.lobbyId) {
                roomScreen.updateState(message);
                target = roomScreen;
            } else {
                // Другая комната (или комнаты ещё не было вовсе) — если
                // старый экземпляр был, это НЕ "та же комната вернулась
                // после game over" (тот случай ловится веткой выше), а
                // реальная смена комнаты — старый больше никогда не
                // понадобится и явно освобождается, а не просто теряется
                // как ссылка при переприсваивании поля ниже.
                if (roomScreen != null) {
                    roomScreen.dispose();
                }
                target = new LobbyRoomScreen(client, message);
                roomScreen = target;
            }
            if (previous != target) {
                setScreen(target);
                // Экран матча, из которого мы сюда попали (обычный путь —
                // "конец игры, реванш той же комнаты"), больше никогда не
                // покажется повторно: следующий матч этой же комнаты
                // создаст СВОЙ новый GameScreen (см. onGameStarted) — старый
                // явно освобождаем, а не ждём выхода из всего приложения.
                if (previous instanceof GameScreen) {
                    previous.dispose();
                }
            }
        });
    }

    @Override
    public void onGameStarted(GameStartedMessage message) {
        Gdx.app.postRunnable(() -> setScreen(new GameScreen(client)));
    }

    @Override
    public void onWorldSnapshot(WorldSnapshot snapshot) {
        Gdx.app.postRunnable(() -> {
            if (getScreen() instanceof GameScreen) {
                ((GameScreen) getScreen()).onWorldSnapshot(snapshot);
            }
        });
    }

    @Override
    public void onError(ErrorResponse error) {
        Gdx.app.postRunnable(() -> {
            Screen current = getScreen();
            if (current instanceof GameScreen) {
                ((GameScreen) current).onError(error);
            } else if (current instanceof LobbyRoomScreen) {
                ((LobbyRoomScreen) current).onError(error);
            } else if (current instanceof LobbyBrowserScreen) {
                ((LobbyBrowserScreen) current).onError(error);
            }
        });
    }

    @Override
    public void onGameOver(GameOverMessage message) {
        Gdx.app.postRunnable(() -> {
            if (getScreen() instanceof GameScreen) {
                ((GameScreen) getScreen()).onGameOver(message);
            }
        });
    }

    @Override
    public void onProjectileFired(ProjectileFiredEvent event) {
        Gdx.app.postRunnable(() -> {
            if (getScreen() instanceof GameScreen) {
                ((GameScreen) getScreen()).onProjectileFired(event);
            }
        });
    }

    @Override
    public void onConnectFailed(String message) {
        // Уже вызывается на GL-потоке — GameClient сама делает
        // Gdx.app.postRunnable() перед вызовом этого метода.
        Gdx.app.log("Network", "Connect failed: " + message);
        browserScreen.onConnectFailed(message);
    }
}
