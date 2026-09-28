package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PatrolComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

/**
 * Ведёт патрулирующего юнита (PatrolComponent) по замкнутому маршруту:
 * как только юнит освобождается (не движется прямо сейчас — та же идея,
 * что и "idle" в OrderQueueSystem, только тут нечего проверять, кроме
 * DirectionComponent.moving — патруль ни с чем, кроме AttackComponent, не
 * сочетается, см. ниже) — выдаёт ему Pathfinding.setDestination к
 * следующей точке маршрута и сдвигает currentIndex по кругу (по модулю
 * размера списка, см. javadoc PatrolComponent), не удаляя пройденную
 * точку — в отличие от OrderQueueSystem, тут нечего "дренировать", цикл
 * бесконечен.
 *
 * Family ИСКЛЮЧАЕТ AttackComponent — пока патрулирующий юнит дерётся со
 * встреченным по пути врагом (AggroSystem сама эту атаку назначает, не
 * снимая PatrolComponent — см. её же javadoc), маршрутом распоряжается
 * CombatSystem, не эта система; как только бой заканчивается
 * (AttackComponent снят), юнит снова попадает в эту Family и
 * возобновляет тот же маршрут с той же точки, на которую шёл до боя.
 *
 * Точку, к которой не удалось проложить путь (вода, чужое здание —
 * Pathfinding.setDestination в этом случае просто не выставляет moving
 * в true), processEntity не пропускает молча навсегда: за один тик
 * пробует до waypoints.size() точек подряд, пока какая-то не приведёт к
 * реальному движению, — иначе юнит с хотя бы одной непроходимой точкой в
 * маршруте застрял бы на ней бесконечно, вместо того чтобы просто
 * пропустить её и патрулировать оставшимися доступными точками.
 *
 * Приоритет 16 — сразу после OrderQueueSystem (15, по той же причине:
 * "юнит только что дошёл" от MovementSystem, приоритет 10, должно
 * успеть примениться в этом же тике), до CollisionSystem (20).
 *
 * Живёт в shared, но реально используется только сервером — как и
 * остальные gameplay-системы.
 */
public class PatrolSystem extends IteratingSystem {

    private static final ComponentMapper<PatrolComponent> PATROL =
            ComponentMapper.getFor(PatrolComponent.class);
    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<DirectionComponent> DIRECTION =
            ComponentMapper.getFor(DirectionComponent.class);

    /** Поиск пути этого матча — см. javadoc Pathfinding, почему не статический. */
    private final Pathfinding pathfinding;

    public PatrolSystem(Pathfinding pathfinding) {
        super(Family.all(PatrolComponent.class, PositionComponent.class, DirectionComponent.class)
                .exclude(AttackComponent.class).get(), 16);
        this.pathfinding = pathfinding;
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        DirectionComponent direction = DIRECTION.get(entity);
        if (direction.moving) {
            return; // уже идёт к точке маршрута, выданной на прошлом тике (или ещё раньше) — ничего не делаем
        }

        PatrolComponent patrol = PATROL.get(entity);
        PositionComponent position = POSITION.get(entity);

        int attemptsLeft = patrol.waypoints.size();
        while (attemptsLeft > 0 && !direction.moving) {
            Vector2 waypoint = patrol.waypoints.get(patrol.currentIndex);
            patrol.currentIndex = (patrol.currentIndex + 1) % patrol.waypoints.size();
            pathfinding.setDestination(entity, position, direction, waypoint.x, waypoint.y);
            attemptsLeft--;
        }
    }
}
