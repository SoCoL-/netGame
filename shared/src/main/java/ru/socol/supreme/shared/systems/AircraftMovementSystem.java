package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.MathUtils;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.AircraftComponent;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PositionComponent;

import java.util.Map;

/**
 * Двигает авиацию (см. AircraftComponent) — в отличие от MovementSystem,
 * не довортачивает мгновенно на цель, а ограничивает скорость поворота
 * turnRate (рад/сек, = speed/turnRadius — стандартная физика кругового
 * движения). DirectionComponent.target/moving по-прежнему означают "куда
 * юнит хочет лететь" — их выставляют те же места, что и наземным юнитам
 * (handleMoveUnit, CombatSystem при погоне); эта система просто
 * ИНТЕРПРЕТИРУЕТ их иначе.
 *
 * По достижении direction.target (или если что-то ещё, не подумав об
 * авиации специально, выставило moving=false — например, CombatSystem,
 * остановивший юнита в радиусе атаки) — дальнейшее поведение зависит от
 * AircraftComponent.canHover (своё у каждого типа, см.
 * UnitDefinitions.canHoverFor): кто умеет зависать (штурмовик) —
 * останавливается там, где оказался, но ПРОДОЛЖАЕТ доворачивать курс на
 * месте (без движения вперёд), пока у него есть активная атака (см.
 * ниже, почему это обязательно — иначе он мог бы замереть с курсом,
 * ни разу не совпавшим с конусом стрельбы CombatSystem, и никогда не
 * выстрелить); кто не умеет (разведчик) — физически не может просто
 * стоять в воздухе, переходит в кружение (loitering) вокруг последней
 * точки радиуса turnRadius. Круг получается без явного расчёта
 * траектории: непрерывное стремление лететь по касательной к окружности
 * при максимальной скорости поворота само вычерчивает нужный радиус, та
 * же идея, что и с approach point в CombatSystem/BuildSystem, только
 * цель движения — сама окружность, а не точка.
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
    private static final ComponentMapper<AttackComponent> ATTACK =
            ComponentMapper.getFor(AttackComponent.class);

    private final Map<Integer, Entity> unitsById;

    public AircraftMovementSystem(Map<Integer, Entity> unitsById) {
        super(Family.all(PositionComponent.class, DirectionComponent.class, AircraftComponent.class).get(), 10);
        this.unitsById = unitsById;
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        PositionComponent position = POSITION.get(entity);
        DirectionComponent direction = DIRECTION.get(entity);
        AircraftComponent aircraft = AIRCRAFT.get(entity);

        if (direction.moving) {
            float distanceToTarget = position.position.dst(direction.target);
            if (distanceToTarget <= GameConstants.ARRIVE_THRESHOLD) {
                // Долетели. moving становится false тут же, в момент
                // прибытия: для остальных систем (например,
                // OrderQueueSystem) это "приказ выполнен, юнит свободен
                // для следующего", хотя физически кружащий (не умеющий
                // зависать) может ещё лететь по кругу без приказа.
                direction.moving = false;
                if (aircraft.canHover) {
                    aircraft.loitering = false; // зависает тут же, где остановился (курс продолжает доворачивать, если атакует — см. ниже)
                } else {
                    aircraft.loitering = true;
                    aircraft.loiterCenterX = direction.target.x;
                    aircraft.loiterCenterY = direction.target.y;
                }
            }
        } else if (!aircraft.loitering && !aircraft.canHover) {
            // moving уже false, кружения тоже ещё нет, и зависать не
            // умеет — либо только что созданный юнит без единого приказа
            // (тогда кружит вокруг места появления), либо что-то другое
            // (CombatSystem — в радиусе атаки) остановило юнита, не
            // выставив кружение само. Самолёт не может просто зависнуть —
            // начинаем кружить прямо тут, где остановились.
            aircraft.loitering = true;
            aircraft.loiterCenterX = position.position.x;
            aircraft.loiterCenterY = position.position.y;
        }

        if (!direction.moving && !aircraft.loitering) {
            // Зависает — позиция не меняется, но курс всё ещё может быть
            // не в конусе стрельбы CombatSystem, а зависший юнит без
            // кружения никогда не довернётся сам собой, как разведчик.
            // Если атакует — доворачиваем курс на цель на месте (turnRate
            // тот же самый, просто без шага position.add ниже), чтобы
            // рано или поздно попасть в конус и открыть огонь. Без цели —
            // курс действительно не трогаем, зависает как было.
            rotateTowardsAttackTargetIfAny(entity, position, direction, aircraft, deltaTime);
            return;
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

        turnTowards(aircraft, desiredHeadingX, desiredHeadingY, deltaTime);

        position.position.add(
                MathUtils.cos(aircraft.heading) * direction.speed * deltaTime,
                MathUtils.sin(aircraft.heading) * direction.speed * deltaTime);
        direction.direction.set(MathUtils.cos(aircraft.heading), MathUtils.sin(aircraft.heading));
    }

    /** Доворачивает курс зависшего юнита на цель его текущей атаки (без движения — вызывающий код ничего не двигает после этого), если она есть. */
    private void rotateTowardsAttackTargetIfAny(Entity entity, PositionComponent position, DirectionComponent direction,
                                                 AircraftComponent aircraft, float deltaTime) {
        AttackComponent attack = ATTACK.get(entity);
        if (attack == null) {
            return;
        }
        Entity target = unitsById.get(attack.targetUnitId);
        PositionComponent targetPosition = target != null ? POSITION.get(target) : null;
        if (targetPosition == null) {
            return; // цель уже пропала — CombatSystem снимет AttackComponent сам на следующем тике
        }
        turnTowards(aircraft, targetPosition.position.x - position.position.x,
                targetPosition.position.y - position.position.y, deltaTime);
        // Обязательно обновляем direction.direction тут же — именно его
        // читает CombatSystem для проверки конуса стрельбы, а не
        // aircraft.heading напрямую; без этой строки поворот выше был бы
        // невидим снаружи метода, и штурмовик так и не смог бы попасть в
        // конус, несмотря на реально меняющийся aircraft.heading.
        direction.direction.set(MathUtils.cos(aircraft.heading), MathUtils.sin(aircraft.heading));
    }

    /** Поворачивает aircraft.heading к направлению (dx, dy), не быстрее turnRate — общая часть для полёта вперёд и доворота на месте при зависании. */
    private void turnTowards(AircraftComponent aircraft, float dx, float dy, float deltaTime) {
        if (dx == 0f && dy == 0f) {
            return;
        }
        float desiredHeading = MathUtils.atan2(dy, dx);
        // Разница углов, нормализованная в [-π, π] через синус/косинус
        // разности — без ручных проверок на переход через ±π.
        float angleDiff = MathUtils.atan2(
                MathUtils.sin(desiredHeading - aircraft.heading),
                MathUtils.cos(desiredHeading - aircraft.heading));
        float maxTurn = aircraft.turnRate * deltaTime;
        aircraft.heading += MathUtils.clamp(angleDiff, -maxTurn, maxTurn);
    }
}

