package ru.socol.supreme.server;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.BuildOrderRequest;
import ru.socol.supreme.shared.network.messages.DemolishBuildingRequest;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlaceIronMineRequest;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;
import ru.socol.supreme.shared.network.messages.RepairOrderRequest;
import ru.socol.supreme.shared.network.messages.SetRallyPointRequest;

/** Разбирает входящие сетевые сообщения и передаёт их нужному обработчику GameServer. */
public class ServerNetworkListener extends Listener {

    private final GameServer gameServer;

    public ServerNetworkListener(GameServer gameServer) {
        this.gameServer = gameServer;
    }

    @Override
    public void received(Connection connection, Object object) {
        if (object instanceof JoinRequest) {
            connection.sendTCP(gameServer.handleJoin(connection, (JoinRequest) object));
        } else if (object instanceof QueueUnitRequest) {
            gameServer.handleQueueUnit(connection, (QueueUnitRequest) object);
        } else if (object instanceof MoveUnitRequest) {
            gameServer.handleMoveUnit(connection, (MoveUnitRequest) object);
        } else if (object instanceof AttackUnitRequest) {
            gameServer.handleAttackUnit(connection, (AttackUnitRequest) object);
        } else if (object instanceof PlaceIronMineRequest) {
            gameServer.handlePlaceIronMine(connection, (PlaceIronMineRequest) object);
        } else if (object instanceof PlaceBuildingRequest) {
            gameServer.handlePlaceBuilding(connection, (PlaceBuildingRequest) object);
        } else if (object instanceof SetRallyPointRequest) {
            gameServer.handleSetRallyPoint(connection, (SetRallyPointRequest) object);
        } else if (object instanceof BuildOrderRequest) {
            gameServer.handleBuildOrder(connection, (BuildOrderRequest) object);
        } else if (object instanceof DemolishBuildingRequest) {
            gameServer.handleDemolishBuilding(connection, (DemolishBuildingRequest) object);
        } else if (object instanceof RepairOrderRequest) {
            gameServer.handleRepairOrder(connection, (RepairOrderRequest) object);
        }
    }

    @Override
    public void disconnected(Connection connection) {
        gameServer.handleDisconnect(connection);
    }
}
