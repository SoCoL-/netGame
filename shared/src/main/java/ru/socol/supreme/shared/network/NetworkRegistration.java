package ru.socol.supreme.shared.network;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.EndPoint;
import ru.socol.supreme.shared.network.messages.ArtilleryFireRequest;
import ru.socol.supreme.shared.network.messages.AttackUnitRequest;
import ru.socol.supreme.shared.network.messages.BuildOrderRequest;
import ru.socol.supreme.shared.network.messages.BuildingExplosionEvent;
import ru.socol.supreme.shared.network.messages.CollectOrderRequest;
import ru.socol.supreme.shared.network.messages.CraterSnapshot;
import ru.socol.supreme.shared.network.messages.DemolishBuildingRequest;
import ru.socol.supreme.shared.network.messages.ErrorResponse;
import ru.socol.supreme.shared.network.messages.FillCraterRequest;
import ru.socol.supreme.shared.network.messages.GameOverMessage;
import ru.socol.supreme.shared.network.messages.GameStartedMessage;
import ru.socol.supreme.shared.network.messages.JoinRequest;
import ru.socol.supreme.shared.network.messages.JoinResponse;
import ru.socol.supreme.shared.network.messages.CreateLobbyRequest;
import ru.socol.supreme.shared.network.messages.JoinLobbyRequest;
import ru.socol.supreme.shared.network.messages.LeaveLobbyRequest;
import ru.socol.supreme.shared.network.messages.SetReadyRequest;
import ru.socol.supreme.shared.network.messages.LobbySummary;
import ru.socol.supreme.shared.network.messages.LobbyListMessage;
import ru.socol.supreme.shared.network.messages.LobbyStateMessage;
import ru.socol.supreme.shared.network.messages.MoveUnitRequest;
import ru.socol.supreme.shared.network.messages.PatrolPoint;
import ru.socol.supreme.shared.network.messages.PatrolUnitRequest;
import ru.socol.supreme.shared.network.messages.PathPoint;
import ru.socol.supreme.shared.network.messages.FogSnapshot;
import ru.socol.supreme.shared.network.messages.QueuedOrderPoint;
import ru.socol.supreme.shared.network.messages.PlaceBuildingRequest;
import ru.socol.supreme.shared.network.messages.PlaceIronMineRequest;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.network.messages.ProjectileFiredEvent;
import ru.socol.supreme.shared.network.messages.QueueUnitRequest;
import ru.socol.supreme.shared.network.messages.RepairOrderRequest;
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
        // PlaceIronMineRequest/PlaceBuildingRequest.builderUnitIds — Kryo
        // умеет массивы примитивов и без явной регистрации, но
        // регистрируем явно, чтобы не полагаться на это неявное поведение.
        kryo.register(int[].class);
        kryo.register(ErrorResponse.class);
        kryo.register(GameOverMessage.class);
        kryo.register(ProjectileFiredEvent.class);
        kryo.register(PathPoint.class);
        kryo.register(QueuedOrderPoint.class);
        kryo.register(FogSnapshot.class);
        kryo.register(boolean[].class);
        kryo.register(PlayerResources.class);
        kryo.register(PlaceIronMineRequest.class);
        kryo.register(PlaceBuildingRequest.class);
        kryo.register(SetRallyPointRequest.class);
        kryo.register(BuildOrderRequest.class);
        kryo.register(DemolishBuildingRequest.class);
        kryo.register(RepairOrderRequest.class);
        kryo.register(CollectOrderRequest.class);
        kryo.register(PatrolPoint.class);
        kryo.register(PatrolUnitRequest.class);
        // Добавлены с лобби (LobbyManager) — как и остальной список выше,
        // ДОБАВЛЯЕМ строго в конец, никогда не переупорядочиваем и не
        // удаляем существующие строки (см. javadoc класса).
        kryo.register(CreateLobbyRequest.class);
        kryo.register(JoinLobbyRequest.class);
        kryo.register(LeaveLobbyRequest.class);
        kryo.register(SetReadyRequest.class);
        kryo.register(LobbySummary.class);
        kryo.register(LobbyListMessage.class);
        kryo.register(LobbyStateMessage.class);
        kryo.register(GameStartedMessage.class);
        // LobbyStateMessage.slotPlayerName — массив String, как
        // int[]/boolean[] выше регистрируем явно, не полагаясь на
        // неявную поддержку Kryo для массивов.
        kryo.register(String[].class);
        // Артиллерия — тоже строго в конец.
        kryo.register(ArtilleryFireRequest.class);
        kryo.register(BuildingExplosionEvent.class);
        kryo.register(CraterSnapshot.class);
        kryo.register(FillCraterRequest.class);
    }
}
