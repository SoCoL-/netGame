package ru.socol.supreme.network;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.network.NetworkRegistration;
import ru.socol.supreme.shared.network.messages.ArtilleryFireRequest;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.BuildingExplosionEvent;
import ru.socol.supreme.shared.network.messages.BuildOrderRequest;
import ru.socol.supreme.shared.network.messages.CollectOrderRequest;
import ru.socol.supreme.shared.network.messages.CreateLobbyRequest;
import ru.socol.supreme.shared.network.messages.DemolishBuildingRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.FillCraterRequest;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.GameStartedMessage;
import ru.socol.supreme.shared.network.messages.JoinLobbyRequest;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.LeaveLobbyRequest;
import ru.socol.supreme.shared.network.messages.LobbyListMessage;
import ru.socol.supreme.shared.network.messages.LobbyStateMessage;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.PatrolPoint;
import ru.socol.supreme.shared.network.messages.PatrolUnitRequest;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlaceIronMineRequest;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;
import ru.socol.supreme.shared.network.messages.RepairOrderRequest;
import ru.socol.supreme.shared.network.messages.SetRallyPointRequest;
import ru.socol.supreme.shared.network.messages.SetReadyRequest;
import ru.socol.supreme.shared.network.messages.WorldSnapshot;

import java.io.IOException;
import java.util.List;

/**
 * Тонкая обёртка над KryoNet Client: держит соединение с сервером и
 * предоставляет простые методы для отправки команд игрока. Входящие
 * сообщения пробрасываются в GameClientListener, который передаёт экран игры.
 *
 * connect() сам ничего не блокирует: попытка подключения идёт в отдельном
 * потоке, чтобы окно LibGDX успевало появиться сразу, а не зависало "без
 * окна" на весь таймаут connect(), если сервер недоступен или сеть ведёт
 * себя странно.
 *
 * Подключаемся только по TCP (без udpPort) — сейчас все сообщения и так
 * идут через sendTCP, UDP-канал пока нигде не используется, а его
 * дополнительная handshake-стадия при connect() — лишний источник зависаний
 * без выигрыша. Если позже добавите частые снапшоты по UDP (см. README),
 * возвращайте udpPort и на сервере, и здесь.
 *
 * Одно соединение теперь живёт куда дольше одного матча: после connect()
 * клиент сначала браузит список лобби (onLobbyList), затем сидит в
 * комнате (onLobbyState) и только после onGameStarted переходит в саму
 * игру — а после onGameOver (и паузы на сервере, см. GameConstants
 * .POST_GAME_OVER_DELAY_SECONDS) снова получит onLobbyState той же
 * комнаты, вернувшейся в ожидание (реванш), без переподключения.
 */
public class GameClient implements Disposable {

    public interface GameClientListener {
        void onJoinResponse(JoinResponse response);

        /** Актуальный список комнат — приходит и сразу после onJoinResponse, и после любого изменения, пока это соединение НЕ сидит ни в одной комнате. */
        void onLobbyList(LobbyListMessage message);

        /** Полное состояние комнаты, в которой сейчас сидит это соединение — приходит заново при любом изменении (занят/освобождён слот, (не)готов, тик обратного отсчёта, конец матча). */
        void onLobbyState(LobbyStateMessage message);

        /** Обратный отсчёт комнаты дошёл до нуля — пора переключаться на игровой экран с playerId = message.yourPlayerId. */
        void onGameStarted(GameStartedMessage message);

        void onWorldSnapshot(WorldSnapshot snapshot);

        void onError(ErrorResponse error);

        void onGameOver(GameOverMessage message);

        /** Чисто косметическое: дальнобойный юнит выстрелил (см. javadoc ProjectileFiredEvent, кто именно) — нарисовать летящий снаряд. Урон уже применён на сервере. */
        void onProjectileFired(ProjectileFiredEvent event);

        /** Чисто косметическое: разрушенное здание (электростанция) взорвалось — нарисовать взрыв. Урон уже применён на сервере. */
        void onBuildingExplosion(BuildingExplosionEvent event);

        /** Не удалось подключиться (таймаут/сеть/сервер недоступен) — сообщение для отображения игроку. */
        void onConnectFailed(String message);
    }

    private final Client client = new Client(GameConstants.NETWORK_WRITE_BUFFER_SIZE, GameConstants.NETWORK_OBJECT_BUFFER_SIZE);
    private GameClientListener listener;

    /**
     * playerId НА ТЕКУЩИЙ матч — раньше назначался один раз на всё
     * соединение (JoinResponse.playerId), теперь приходит заново перед
     * каждым матчем в GameStartedMessage.yourPlayerId (см. её же javadoc,
     * почему: одно и то же соединение может сыграть много матчей подряд в
     * одном и том же лобби, и playerId — это индекс СЛОТА в лобби, а не
     * что-то постоянное для соединения). -1, пока ни один матч ещё не
     * начинался.
     */
    private int playerId = -1;

    public void connect(String host, GameClientListener listener) {
        this.listener = listener;

        NetworkRegistration.register(client);
        client.addListener(new Listener() {
            @Override
            public void received(Connection connection, Object object) {
                if (object instanceof JoinResponse) {
                    if (GameClient.this.listener != null) {
                        GameClient.this.listener.onJoinResponse((JoinResponse) object);
                    }
                } else if (object instanceof LobbyListMessage) {
                    if (GameClient.this.listener != null) {
                        GameClient.this.listener.onLobbyList((LobbyListMessage) object);
                    }
                } else if (object instanceof LobbyStateMessage) {
                    if (GameClient.this.listener != null) {
                        GameClient.this.listener.onLobbyState((LobbyStateMessage) object);
                    }
                } else if (object instanceof GameStartedMessage) {
                    playerId = ((GameStartedMessage) object).yourPlayerId;
                    if (GameClient.this.listener != null) {
                        GameClient.this.listener.onGameStarted((GameStartedMessage) object);
                    }
                } else if (object instanceof WorldSnapshot) {
                    if (GameClient.this.listener != null) {
                        GameClient.this.listener.onWorldSnapshot((WorldSnapshot) object);
                    }
                } else if (object instanceof ErrorResponse) {
                    if (GameClient.this.listener != null) {
                        GameClient.this.listener.onError((ErrorResponse) object);
                    }
                } else if (object instanceof GameOverMessage) {
                    if (GameClient.this.listener != null) {
                        GameClient.this.listener.onGameOver((GameOverMessage) object);
                    }
                } else if (object instanceof ProjectileFiredEvent) {
                    if (GameClient.this.listener != null) {
                        GameClient.this.listener.onProjectileFired((ProjectileFiredEvent) object);
                    }
                } else if (object instanceof BuildingExplosionEvent) {
                    if (GameClient.this.listener != null) {
                        GameClient.this.listener.onBuildingExplosion((BuildingExplosionEvent) object);
                    }
                }
            }
        });

        client.start();

        // Сама попытка connect() блокирующая (до 5 сек, а в некоторых сетевых
        // условиях с UDP-хендшейком — и дольше) — уводим её с потока, который
        // вызвал GameClient.connect() (у нас это GL-поток из Main.create()),
        // чтобы окно успело открыться независимо от результата подключения.
        Thread connectThread = new Thread(() -> {
            try {
                client.connect(5000, host, GameConstants.TCP_PORT);
                JoinRequest request = new JoinRequest();
                // Имя пользователя ОС как разумный дефолт — своего экрана
                // ввода ника сознательно не делаем (не просили, см. javadoc
                // JoinRequest), но и оставлять всем одинаковое "Player" в
                // списке слотов комнаты было бы бесполезно.
                request.playerName = System.getProperty("user.name", "Player");
                client.sendTCP(request);
            } catch (IOException e) {
                if (GameClient.this.listener != null) {
                    String message = e.getMessage();
                    Gdx.app.postRunnable(() -> GameClient.this.listener.onConnectFailed(message));
                }
            }
        }, "kryonet-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /** Создаёт новую комнату лобби — создатель автоматически занимает слот 0 (см. javadoc CreateLobbyRequest). */
    public void requestCreateLobby(String lobbyName) {
        CreateLobbyRequest request = new CreateLobbyRequest();
        request.lobbyName = lobbyName;
        client.sendTCP(request);
    }

    /** Занимает первый свободный слот указанной комнаты из списка (onLobbyList). */
    public void requestJoinLobby(int lobbyId) {
        JoinLobbyRequest request = new JoinLobbyRequest();
        request.lobbyId = lobbyId;
        client.sendTCP(request);
    }

    /** Выход обратно в общий список лобби — сервер молча игнорирует, если матч в этой комнате уже идёт (см. javadoc LeaveLobbyRequest). */
    public void requestLeaveLobby() {
        client.sendTCP(new LeaveLobbyRequest());
    }

    /** Подтверждает или снимает готовность своего слота — снятие во время уже идущего отсчёта отменяет его (см. javadoc LobbyManager.handleSetReady). */
    public void requestSetReady(boolean ready) {
        SetReadyRequest request = new SetReadyRequest();
        request.ready = ready;
        client.sendTCP(request);
    }

    public void requestQueueUnit(int buildingUnitId, UnitType unitType) {
        QueueUnitRequest request = new QueueUnitRequest();
        request.buildingUnitId = buildingUnitId;
        request.unitType = unitType.ordinal();
        client.sendTCP(request);
    }

    /** queue — см. javadoc BuildOrderRequest.queue: shift-клик добавляет в очередь, не прерывая текущий приказ строителя. */
    public void requestBuildOrder(int builderUnitId, int targetBuildingUnitId, boolean queue) {
        BuildOrderRequest request = new BuildOrderRequest();
        request.builderUnitId = builderUnitId;
        request.targetBuildingUnitId = targetBuildingUnitId;
        request.queue = queue;
        client.sendTCP(request);
    }

    /** queue — см. javadoc RepairOrderRequest.queue: shift-клик добавляет в очередь, не прерывая текущий приказ строителя. */
    public void requestRepairOrder(int builderUnitId, int targetBuildingUnitId, boolean queue) {
        RepairOrderRequest request = new RepairOrderRequest();
        request.builderUnitId = builderUnitId;
        request.targetBuildingUnitId = targetBuildingUnitId;
        request.queue = queue;
        client.sendTCP(request);
    }

    /** queue — см. javadoc CollectOrderRequest.queue: shift-клик добавляет в очередь, не прерывая текущий приказ строителя. */
    public void requestCollectOrder(int builderUnitId, int targetWreckUnitId, boolean queue) {
        CollectOrderRequest request = new CollectOrderRequest();
        request.builderUnitId = builderUnitId;
        request.targetWreckUnitId = targetWreckUnitId;
        request.queue = queue;
        client.sendTCP(request);
    }

    /** Строителю засыпать воронку — см. javadoc FillCraterRequest. */
    public void requestFillCrater(int builderUnitId, int craterId) {
        FillCraterRequest request = new FillCraterRequest();
        request.builderUnitId = builderUnitId;
        request.craterId = craterId;
        client.sendTCP(request);
    }

    public void requestDemolishBuilding(int buildingUnitId) {
        DemolishBuildingRequest request = new DemolishBuildingRequest();
        request.buildingUnitId = buildingUnitId;
        client.sendTCP(request);
    }

    /** Выстрел своей артиллерии в точку (x, y) — см. javadoc ArtilleryFireRequest. */
    public void requestArtilleryFire(int buildingUnitId, float x, float y) {
        ArtilleryFireRequest request = new ArtilleryFireRequest();
        request.buildingUnitId = buildingUnitId;
        request.x = x;
        request.y = y;
        client.sendTCP(request);
    }

    public void requestSetRallyPoint(int buildingUnitId, float x, float y) {
        SetRallyPointRequest request = new SetRallyPointRequest();
        request.buildingUnitId = buildingUnitId;
        request.x = x;
        request.y = y;
        client.sendTCP(request);
    }

    /**
     * builderUnitIds — строители, выделенные в момент подтверждения,
     * автоматически пойдут строить это здание (см. javadoc
     * PlaceIronMineRequest.builderUnitIds). queue — shift-клик при
     * подтверждении размещения (см. javadoc PlaceIronMineRequest.queue).
     */
    public void requestPlaceIronMine(int depositIndex, int[] builderUnitIds, boolean queue) {
        PlaceIronMineRequest request = new PlaceIronMineRequest();
        request.depositIndex = depositIndex;
        request.builderUnitIds = builderUnitIds;
        request.queue = queue;
        client.sendTCP(request);
    }

    /** Для любого свободно размещаемого здания — сейчас казарма стрелков и электростанция (не дом, не шахта — у неё отдельный requestPlaceIronMine). builderUnitIds/queue — см. их же javadoc в PlaceBuildingRequest. */
    public void requestPlaceBuilding(BuildingType type, float x, float y, int[] builderUnitIds, boolean queue) {
        PlaceBuildingRequest request = new PlaceBuildingRequest();
        request.buildingType = type.ordinal();
        request.x = x;
        request.y = y;
        request.builderUnitIds = builderUnitIds;
        request.queue = queue;
        client.sendTCP(request);
    }

    /** queue — см. javadoc MoveUnitRequest.queue: shift-клик добавляет в очередь, не прерывая текущий приказ юнита. */
    public void requestMoveUnit(int unitId, float targetX, float targetY, boolean queue) {
        MoveUnitRequest request = new MoveUnitRequest();
        request.unitId = unitId;
        request.targetX = targetX;
        request.targetY = targetY;
        request.queue = queue;
        client.sendTCP(request);
    }

    /** queue — см. javadoc AttackUnitRequest.queue: shift-клик добавляет в очередь, не прерывая текущий приказ юнита. */
    public void requestAttackUnit(int unitId, int targetUnitId, boolean queue) {
        AttackUnitRequest request = new AttackUnitRequest();
        request.unitId = unitId;
        request.targetUnitId = targetUnitId;
        request.queue = queue;
        client.sendTCP(request);
    }

    /**
     * Нет queue-варианта — см. javadoc PatrolUnitRequest, почему у
     * патруля нет смысла в shift-клике "добавить в очередь". waypoints —
     * весь маршрут целиком, накопленный за время расстановки (см.
     * GameScreen.finishPatrolPlacement).
     */
    public void requestPatrolUnit(int unitId, List<PatrolPoint> waypoints) {
        PatrolUnitRequest request = new PatrolUnitRequest();
        request.unitId = unitId;
        request.waypoints = waypoints;
        client.sendTCP(request);
    }

    public int getPlayerId() {
        return playerId;
    }

    @Override
    public void dispose() {
        client.stop();
        client.close();
    }
}
