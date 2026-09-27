package ru.socol.supreme.shared.network.messages;

import java.util.List;

/**
 * Клиент -> сервер: "пусть мой юнит unitId патрулирует замкнутый маршрут
 * waypoints" — список точек, расставленных кликами по карте после нажатия
 * кнопки Patrol в панели выделения (см. GameScreen — placingPatrol,
 * finishPatrolPlacement). Всегда немедленный приказ — в отличие от
 * MoveUnitRequest/AttackUnitRequest/BuildOrderRequest/RepairOrderRequest/
 * CollectOrderRequest, тут нет queue-модификатора (shift-клика): патруль —
 * бесконечный цикл без естественного "потом", после которого имело бы
 * смысл поставить что-то ещё в очередь конкретно ЭТОГО приказа. Если
 * понадобится, игрок просто отдаст новый приказ — как и любой другой, он
 * немедленно заменит патруль (см. GameServer.handlePatrolUnit и javadoc
 * PatrolComponent, "любая другая команда отменяет патруль", без
 * исключений — действует и для queue=true приказов остальных типов).
 *
 * waypoints должен содержать хотя бы одну точку — пустой или null список
 * сервер просто игнорирует (см. обработку в handlePatrolUnit).
 */
public class PatrolUnitRequest {

    public int unitId;
    public List<PatrolPoint> waypoints;

    public PatrolUnitRequest() {
    }
}
