package ru.socol.supreme.shared.network;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.EndPoint;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.BuildOrderRequest;
import ru.socol.supreme.shared.network.messages.DemolishBuildingRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.PathPoint;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlaceIronMineRequest;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;
import ru.socol.supreme.shared.network.messages.SetRallyPointRequest;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;
import ru.socol.supreme.shared.network.messages.WorldSnapshot;

import java.util.ArrayList;

/**
 * Регистрирует все классы сообщений в Kryo. КРИТИЧНО: на клиенте и на
 * сервере регистрация должна идти строго в одном и том же порядке — иначе
 * Kryo будет присваивать сообщениям разные числовые id по разные стороны
 * соединения и десериализация сломается.
 */
public final class NetworkRegistration {

    private NetworkRegistration() {
    }

    public static void register(EndPoint endPoint) {
        Kryo kryo = endPoint.getKryo();
        kryo.register(JoinRequest.class);
        kryo.register(JoinResponse.class);
        kryo.register(QueueUnitRequest.class);
        kryo.register(MoveUnitRequest.class);
        kryo.register(AttackUnitRequest.class);
        kryo.register(UnitSnapshot.class);
        kryo.register(WorldSnapshot.class);
        kryo.register(ArrayList.class);
        kryo.register(ErrorResponse.class);
        kryo.register(GameOverMessage.class);
        kryo.register(ProjectileFiredEvent.class);
        kryo.register(PathPoint.class);
        kryo.register(PlayerResources.class);
        kryo.register(PlaceIronMineRequest.class);
        kryo.register(PlaceBuildingRequest.class);
        kryo.register(SetRallyPointRequest.class);
        kryo.register(BuildOrderRequest.class);
        kryo.register(DemolishBuildingRequest.class);
    }
}
