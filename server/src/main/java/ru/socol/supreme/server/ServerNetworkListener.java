package ru.socol.supreme.server;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import ru.socol.supreme.shared.network.messages.ArtilleryFireRequest;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.BuildOrderRequest;
import ru.socol.supreme.shared.network.messages.CollectOrderRequest;
import ru.socol.supreme.shared.network.messages.CreateLobbyRequest;
import ru.socol.supreme.shared.network.messages.DemolishBuildingRequest;
import ru.socol.supreme.shared.network.messages.FillCraterRequest;
import ru.socol.supreme.shared.network.messages.JoinLobbyRequest;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.LeaveLobbyRequest;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.PatrolUnitRequest;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlaceIronMineRequest;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;
import ru.socol.supreme.shared.network.messages.RepairOrderRequest;
import ru.socol.supreme.shared.network.messages.SetRallyPointRequest;
import ru.socol.supreme.shared.network.messages.SetReadyRequest;

/**
 * Единая точка входа для всех входящих сообщений процесса — раньше
 * оборачивала один-единственный GameServer на весь процесс, теперь
 * оборачивает LobbyManager (владеет общим Server, см. её же javadoc) и
 * делит входящие сообщения на два потока:
 *
 *  - Управление лобби (JoinRequest/CreateLobbyRequest/JoinLobbyRequest/
 *    LeaveLobbyRequest/SetReadyRequest) — всегда идёт напрямую в
 *    LobbyManager, независимо от того, в матче сейчас соединение или нет.
 *  - Игровые команды (QueueUnitRequest, MoveUnitRequest и т.д. — всё, чем
 *    раньше занимался один общий GameServer) — сначала ищем через
 *    lobbyManager.sessionFor(connection) конкретный GameServer ТЕКУЩЕГО
 *    матча этого соединения и только потом вызываем на нём тот же самый
 *    handleXxx, что и раньше. Если соединение сейчас не в матче (сидит в
 *    браузере лобби или в комнате, которая ещё не стартовала) — sessionFor
 *    возвращает null и сообщение молча отбрасывается, как и любая другая
 *    невалидная команда от случайно рассинхронизированного или
 *    модифицированного клиента.
 */
public class ServerNetworkListener extends Listener {

    private final LobbyManager lobbyManager;

    public ServerNetworkListener(LobbyManager lobbyManager) {
        this.lobbyManager = lobbyManager;
    }

    @Override
    public void received(Connection connection, Object object) {
        // ---- Управление лобби — всегда в LobbyManager напрямую ----
        if (object instanceof JoinRequest) {
            lobbyManager.handleJoin(connection, (JoinRequest) object);
        } else if (object instanceof CreateLobbyRequest) {
            lobbyManager.handleCreateLobby(connection, (CreateLobbyRequest) object);
        } else if (object instanceof JoinLobbyRequest) {
            lobbyManager.handleJoinLobby(connection, (JoinLobbyRequest) object);
        } else if (object instanceof LeaveLobbyRequest) {
            lobbyManager.handleLeaveLobby(connection, (LeaveLobbyRequest) object);
        } else if (object instanceof SetReadyRequest) {
            lobbyManager.handleSetReady(connection, (SetReadyRequest) object);
        }
        // ---- Игровые команды — только в GameServer ТЕКУЩЕГО матча этого соединения ----
        else if (object instanceof QueueUnitRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handleQueueUnit(connection, (QueueUnitRequest) object);
            }
        } else if (object instanceof MoveUnitRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handleMoveUnit(connection, (MoveUnitRequest) object);
            }
        } else if (object instanceof AttackUnitRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handleAttackUnit(connection, (AttackUnitRequest) object);
            }
        } else if (object instanceof PlaceIronMineRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handlePlaceIronMine(connection, (PlaceIronMineRequest) object);
            }
        } else if (object instanceof PlaceBuildingRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handlePlaceBuilding(connection, (PlaceBuildingRequest) object);
            }
        } else if (object instanceof SetRallyPointRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handleSetRallyPoint(connection, (SetRallyPointRequest) object);
            }
        } else if (object instanceof BuildOrderRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handleBuildOrder(connection, (BuildOrderRequest) object);
            }
        } else if (object instanceof DemolishBuildingRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handleDemolishBuilding(connection, (DemolishBuildingRequest) object);
            }
        } else if (object instanceof RepairOrderRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handleRepairOrder(connection, (RepairOrderRequest) object);
            }
        } else if (object instanceof CollectOrderRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handleCollectOrder(connection, (CollectOrderRequest) object);
            }
        } else if (object instanceof PatrolUnitRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handlePatrolUnit(connection, (PatrolUnitRequest) object);
            }
        } else if (object instanceof FillCraterRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handleFillCrater(connection, (FillCraterRequest) object);
            }
        } else if (object instanceof ArtilleryFireRequest) {
            GameServer session = lobbyManager.sessionFor(connection);
            if (session != null) {
                session.handleArtilleryFire(connection, (ArtilleryFireRequest) object);
            }
        }
    }

    @Override
    public void disconnected(Connection connection) {
        lobbyManager.handleDisconnect(connection);
    }
}
