package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.AircraftComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PathComponent;
import ru.socol.supreme.shared.components.PositionComponent;

/**
 * Двигает каждый наземный юнит к цели на скорость * deltaTime, каждый тик
 * заново прицеливаясь на direction.target от текущей позиции (а не идя по
 * зафиксированному один раз направлению) — это и не даёт юниту, которого
 * CollisionSystem слегка оттолкнул от соседей, промахнуться мимо цели и
 * улететь по старому курсу навсегда. Это единственное место, где реально
 * меняется позиция наземного юнита — система выполняется на сервере
 * (авторитетная симуляция), клиент лишь показывает то, что прислал
 * сервер. Family исключает AircraftComponent — у авиации мгновенный
 * поворот на цель физически не подходит, ей занимается отдельная
 * AircraftMovementSystem (ограниченная скорость разворота, кружение
 * вместо остановки).
 *
 * Если у юнита есть PathComponent (обходной путь вокруг препятствия — см.
 * Pathfinding) — по достижении текущей точки цели берётся следующая точка
 * из списка вместо остановки; юнит останавливается только когда список
 * закончился (или его вообще не было — обычное прямолинейное движение).
 */
public class MovementSystem extends IteratingSystem {

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<DirectionComponent> DIRECTION =
            ComponentMapper.getFor(DirectionComponent.class);
    private static final ComponentMapper<PathComponent> PATH =
            ComponentMapper.getFor(PathComponent.class);

    /**
     * Приоритет 10, а не по умолчанию (0) — важно, чтобы движение
     * применялось ПОСЛЕ того, как AggroSystem/CombatSystem за этот же тик
     * решат, куда юнит идёт (см. их приоритеты -10 и 0). Меньшее значение
     * приоритета в Ashley выполняется раньше.
     */
    public MovementSystem() {
        super(Family.all(PositionComponent.class, DirectionComponent.class)
                .exclude(AircraftComponent.class).get(), 10);
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        DirectionComponent direction = DIRECTION.get(entity);
        if (!direction.moving) {
            return;
        }

        PositionComponent position = POSITION.get(entity);
        float distanceToTarget = position.position.dst(direction.target);

        // К промежуточной точке обходного пути (см. javadoc
        // PATH_WAYPOINT_ARRIVE_THRESHOLD) — более щедрый порог прибытия,
        // чем к конечной цели: иначе юнит, идущий следом за соседом,
        // остановившимся ровно в следующей путевой точке, никогда не
        // "дойдёт" до неё точно (CollisionSystem не подпускает ближе
        // 2×UNIT_RADIUS) и зависнет навсегда вместо того, чтобы пойти на
        // следующий отрезок маршрута.
        PathComponent path = PATH.get(entity);
        boolean intermediateWaypoint = path != null && !path.waypoints.isEmpty();
        float arriveThreshold = intermediateWaypoint
                ? GameConstants.PATH_WAYPOINT_ARRIVE_THRESHOLD
                : GameConstants.ARRIVE_THRESHOLD;

        if (distanceToTarget <= arriveThreshold) {
            if (!intermediateWaypoint) {
                // Только для точного финального прибытия юнит "прилипает"
                // к точной координате цели — на промежуточной точке этого
                // делать нельзя: при более широком пороге прибытия это
                // выглядело бы как рывок юнита в сторону от текущего курса.
                position.position.set(direction.target);
            }
            advanceToNextWaypointOrStop(entity, position, direction);
            return;
        }

        // Пересчитываем направление к цели каждый тик от ТЕКУЩЕЙ позиции, а
        // не используем один раз посчитанный вектор. Это важно: CollisionSystem
        // выполняется позже в этом же тике (приоритет 20 против 10 у этой
        // системы) и может слегка сдвинуть юнита в сторону, разводя его с
        // соседями. Без пересчёта юнит продолжал бы лететь по старому,
        // уже неверному направлению и мог промахнуться мимо цели навсегда —
        // дистанция до цели никогда не попала бы в ARRIVE_THRESHOLD, если
        // курс не сходится к ней. С пересчётом юнит каждый тик "доворачивает"
        // на цель и всегда в итоге доходит, даже если его постоянно толкают.
        direction.direction.set(direction.target).sub(position.position).nor();
        position.position.mulAdd(direction.direction, direction.speed * deltaTime);
    }

    private void advanceToNextWaypointOrStop(Entity entity, PositionComponent position, DirectionComponent direction) {
        PathComponent path = PATH.get(entity);

        if (path != null && !path.waypoints.isEmpty()) {
            Vector2 next = path.waypoints.remove(0);
            direction.target.set(next);
            direction.direction.set(direction.target).sub(position.position).nor();
            return; // direction.moving остаётся true — есть куда идти дальше
        }

        if (path != null) {
            entity.remove(PathComponent.class); // обходной путь пройден полностью
        }
        direction.moving = false;
    }
}
