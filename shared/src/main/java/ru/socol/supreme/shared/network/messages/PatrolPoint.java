package ru.socol.supreme.shared.network.messages;

/**
 * Одна точка маршрута патрулирования. Используется в обе стороны: клиент
 * -> сервер, в PatrolUnitRequest.waypoints — весь список целиком, точки
 * расставлены кликами по карте после нажатия кнопки Patrol (см.
 * GameScreen — placingPatrol/finishPatrolPlacement); и сервер -> клиент,
 * в UnitSnapshot.patrolPoints — тот же список с сервера
 * (PatrolComponent.waypoints), чтобы клиент нарисовал замкнутый маршрут
 * патрулирующего юнита (GameScreen.drawPatrolRoute). В отличие от
 * QueuedOrderPoint (очередь обычных приказов) тут нет поля типа — все
 * точки маршрута патруля однородны, отдельно отмечать нечего.
 */
public class PatrolPoint {

    public float x;
    public float y;

    public PatrolPoint() {
    }

    public PatrolPoint(float x, float y) {
        this.x = x;
        this.y = y;
    }
}
