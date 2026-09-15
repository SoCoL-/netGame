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
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.BuildOrderRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlaceIronMineRequest;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;
import ru.socol.supreme.shared.network.messages.SetRallyPointRequest;
import ru.socol.supreme.shared.network.messages.WorldSnapshot;

import java.io.IOException;

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
 */
public class GameClient implements Disposable {

    public interface GameClientListener {
        void onJoinResponse(JoinResponse response);

        void onWorldSnapshot(WorldSnapshot snapshot);

        void onError(ErrorResponse error);

        void onGameOver(GameOverMessage message);

        /** Чисто косметическое: лучник выстрелил — нарисовать летящую стрелу. Урон уже применён на сервере. */
        void onProjectileFired(ProjectileFiredEvent event);

        /** Не удалось подключиться (таймаут/сеть/сервер недоступен) — сообщение для отображения игроку. */
        void onConnectFailed(String message);
    }

    private final Client client = new Client(GameConstants.NETWORK_WRITE_BUFFER_SIZE, GameConstants.NETWORK_OBJECT_BUFFER_SIZE);
    private GameClientListener listener;
    private int playerId = -1;

    public void connect(String host, GameClientListener listener) {
        this.listener = listener;

        NetworkRegistration.register(client);
        client.addListener(new Listener() {
            @Override
            public void received(Connection connection, Object object) {
                if (object instanceof JoinResponse) {
                    JoinResponse response = (JoinResponse) object;
                    if (response.accepted) {
                        playerId = response.playerId;
                    }
                    if (GameClient.this.listener != null) {
                        GameClient.this.listener.onJoinResponse(response);
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
                client.sendTCP(new JoinRequest());
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

    public void requestQueueUnit(int buildingUnitId, UnitType unitType) {
        QueueUnitRequest request = new QueueUnitRequest();
        request.buildingUnitId = buildingUnitId;
        request.unitType = unitType.ordinal();
        client.sendTCP(request);
    }

    public void requestBuildOrder(int builderUnitId, int targetBuildingUnitId) {
        BuildOrderRequest request = new BuildOrderRequest();
        request.builderUnitId = builderUnitId;
        request.targetBuildingUnitId = targetBuildingUnitId;
        client.sendTCP(request);
    }

    public void requestSetRallyPoint(int buildingUnitId, float x, float y) {
        SetRallyPointRequest request = new SetRallyPointRequest();
        request.buildingUnitId = buildingUnitId;
        request.x = x;
        request.y = y;
        client.sendTCP(request);
    }

    public void requestPlaceIronMine(int depositIndex) {
        PlaceIronMineRequest request = new PlaceIronMineRequest();
        request.depositIndex = depositIndex;
        client.sendTCP(request);
    }

    /** Для любого свободно размещаемого здания — сейчас казарма стрелков и электростанция (не дом, не шахта — у неё отдельный requestPlaceIronMine). */
    public void requestPlaceBuilding(BuildingType type, float x, float y) {
        PlaceBuildingRequest request = new PlaceBuildingRequest();
        request.buildingType = type.ordinal();
        request.x = x;
        request.y = y;
        client.sendTCP(request);
    }

    public void requestMoveUnit(int unitId, float targetX, float targetY) {
        MoveUnitRequest request = new MoveUnitRequest();
        request.unitId = unitId;
        request.targetX = targetX;
        request.targetY = targetY;
        client.sendTCP(request);
    }

    public void requestAttackUnit(int unitId, int targetUnitId) {
        AttackUnitRequest request = new AttackUnitRequest();
        request.unitId = unitId;
        request.targetUnitId = targetUnitId;
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
