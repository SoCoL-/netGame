package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.MathUtils;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.AircraftComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PositionComponent;

/**
 * Двигает авиацию (см. AircraftComponent) — в отличие от MovementSystem,
 * не довортачивает мгновенно на цель, а ограничивает скорость поворота
 * turnRate (рад/сек, = speed/turnRadius — стандартная физика кругового
 * движения). DirectionComponent.target/moving по-прежнему означают "куда
 * юнит хочет лететь" — их выставляют те же места, что и наземным юнитам
 * (handleMoveUnit, CombatSystem при погоне); эта система просто
 * ИНТЕРПРЕТИРУЕТ их иначе.
 *
 * Самолёт никогда не останавливается по-настоящему — физически не может
 * зависнуть в воздухе. По достижении direction.target (или если что-то
 * ещё, не подумав об авиации специально, выставило moving=false —
 * например, CombatSystem, остановивший юнита в радиусе атаки) он
 * переходит в кружение (loitering) вокруг последней точки и летает по
 * кругу радиуса turnRadius, пока не получит новый приказ. Круг получается
 * без явного расчёта траектории: непрерывное стремление лететь по
 * касательной к окружности при максимальной скорости поворота само
 * вычерчивает нужный радиус, та же идея, что и с approach point в
 * CombatSystem/BuildSystem, только цель движения — сама окружность, а не
 * точка.
 *
 * Приоритет 10 — тот же, что и у MovementSystem: они обрабатывают
 * взаимоисключающие множества сущностей (см. exclude(AircraftComponent)
 * в Family MovementSystem/CollisionSystem), порядок между ними не важен.
 *
 * Живёт в shared, но реально используется только сервером — как и
 * остальные gameplay-системы.
 */
public class AircraftMovementSystem extends IteratingSystem {

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<DirectionComponent> DIRECTION =
            ComponentMapper.getFor(DirectionComponent.class);
    private static final ComponentMapper<AircraftComponent> AIRCRAFT =
            ComponentMapper.getFor(AircraftComponent.class);

    public AircraftMovementSystem() {
        super(Family.all(PositionComponent.class, DirectionComponent.class, AircraftComponent.class).get(), 10);
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        PositionComponent position = POSITION.get(entity);
        DirectionComponent direction = DIRECTION.get(entity);
        AircraftComponent aircraft = AIRCRAFT.get(entity);

        if (direction.moving) {
            float distanceToTarget = position.position.dst(direction.target);
            if (distanceToTarget <= GameConstants.ARRIVE_THRESHOLD) {
                // Долетели — переходим в кружение, а не останавливаемся.
                // moving становится false тут же, в момент прибытия: для
                // остальных систем (например, OrderQueueSystem) это
                // "приказ выполнен, юнит свободен для следующего", хотя
                // физически он продолжает лететь по кругу без приказа.
                aircraft.loitering = true;
                aircraft.loiterCenterX = direction.target.x;
                aircraft.loiterCenterY = direction.target.y;
                direction.moving = false;
            }
        } else if (!aircraft.loitering) {
            // moving уже false, но кружения тоже ещё нет — либо только
            // что созданный юнит без единого приказа (тогда кружит вокруг
            // места появления), либо что-то другое (CombatSystem — в
            // радиусе атаки) остановило юнита, не выставив кружение само.
            // Самолёт не может просто зависнуть — начинаем кружить прямо
            // тут, где остановились.
            aircraft.loitering = true;
            aircraft.loiterCenterX = position.position.x;
            aircraft.loiterCenterY = position.position.y;
        }

        float desiredHeadingX;
        float desiredHeadingY;
        if (direction.moving) {
            desiredHeadingX = direction.target.x - position.position.x;
            desiredHeadingY = direction.target.y - position.position.y;
        } else {
            float fromCenterX = position.position.x - aircraft.loiterCenterX;
            float fromCenterY = position.position.y - aircraft.loiterCenterY;
            if (fromCenterX == 0f && fromCenterY == 0f) {
                fromCenterX = 1f; // ровно в центре круга — направление от центра не определено, берём любое
            }
            // Касательная к окружности (поворот радиус-вектора на 90°) —
            // см. javadoc класса, почему этого достаточно для кружения.
            desiredHeadingX = -fromCenterY;
            desiredHeadingY = fromCenterX;
        }

        if (desiredHeadingX != 0f || desiredHeadingY != 0f) {
            float desiredHeading = MathUtils.atan2(desiredHeadingY, desiredHeadingX);
            // Разница углов, нормализованная в [-π, π] через синус/косинус
            // разности — без ручных проверок на переход через ±π.
            float angleDiff = MathUtils.atan2(
                    MathUtils.sin(desiredHeading - aircraft.heading),
                    MathUtils.cos(desiredHeading - aircraft.heading));
            float maxTurn = aircraft.turnRate * deltaTime;
            aircraft.heading += MathUtils.clamp(angleDiff, -maxTurn, maxTurn);
        }

        position.position.add(
                MathUtils.cos(aircraft.heading) * direction.speed * deltaTime,
                MathUtils.sin(aircraft.heading) * direction.speed * deltaTime);
        direction.direction.set(MathUtils.cos(aircraft.heading), MathUtils.sin(aircraft.heading));
    }
}
