package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.MathUtils;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.TurretComponent;

import java.util.Map;

/**
 * Доворачивает башню наземного юнита (TurretComponent.angleRadians) на
 * цель текущей атаки (AttackComponent), а когда её нет — на направление
 * движения корпуса (DirectionComponent.direction). Не мгновенно, а с
 * ограниченной скоростью — тот же приём, что и в
 * AircraftMovementSystem.turnTowards: разница углов нормализуется через
 * atan2(sin(diff), cos(diff)) в диапазон [-MathUtils.PI, MathUtils.PI]
 * без ручных проверок перехода через границу, дальше просто
 * MathUtils.clamp на максимальный поворот за тик.
 *
 * Family отбирает только сущности с TurretComponent — его получают
 * только наземные юниты (GameServer.createUnit, turnRadius == 0), у
 * авиации отдельной башни нет.
 *
 * Приоритет 1 — сразу после CombatSystem (0), чтобы в этом же тике
 * увидеть уже добавленный или снятый им AttackComponent, и задолго до
 * MovementSystem/AircraftMovementSystem (10), с которыми эта система
 * вообще не пересекается по сущностям (Family там требует
 * DirectionComponent/AircraftComponent без TurretComponent) — порядок
 * между ними не важен.
 *
 * Чисто косметическая система: угол башни ни на что не влияет в самой
 * симуляции боя (см. javadoc TurretComponent) — нужен только клиенту для
 * отрисовки. Живёт в shared, но реально используется только сервером —
 * как и остальные gameplay-системы.
 */
public class TurretAimSystem extends IteratingSystem {

    private static final float TURRET_TURN_RATE_RADIANS =
            (float) Math.toRadians(GameConstants.TURRET_TURN_SPEED_DEGREES_PER_SECOND);

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<DirectionComponent> DIRECTION =
            ComponentMapper.getFor(DirectionComponent.class);
    private static final ComponentMapper<TurretComponent> TURRET =
            ComponentMapper.getFor(TurretComponent.class);
    private static final ComponentMapper<AttackComponent> ATTACK =
            ComponentMapper.getFor(AttackComponent.class);

    /** Тот же реестр unitId -> Entity, что и в GameServer/CombatSystem — передаётся по ссылке, не копируется. */
    private final Map<Integer, Entity> unitsById;

    public TurretAimSystem(Map<Integer, Entity> unitsById) {
        super(Family.all(PositionComponent.class, DirectionComponent.class, TurretComponent.class).get(), 1);
        this.unitsById = unitsById;
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        PositionComponent position = POSITION.get(entity);
        DirectionComponent direction = DIRECTION.get(entity);
        TurretComponent turret = TURRET.get(entity);

        float desiredDx;
        float desiredDy;

        AttackComponent attack = ATTACK.get(entity);
        Entity target = attack != null ? unitsById.get(attack.targetUnitId) : null;
        if (target != null) {
            PositionComponent targetPosition = POSITION.get(target);
            desiredDx = targetPosition.position.x - position.position.x;
            desiredDy = targetPosition.position.y - position.position.y;
        } else {
            // Без активной атаки — башня "отдыхает", смотря по ходу
            // движения корпуса, а не куда попало.
            desiredDx = direction.direction.x;
            desiredDy = direction.direction.y;
        }

        if (desiredDx == 0f && desiredDy == 0f) {
            // Ни цели, ни направления движения — например, юнит только
            // что создан и ещё не получал ни одного приказа. Держим угол
            // как есть (стартовый 0 из TurretComponent.reset()), крутить
            // некуда.
            return;
        }

        float desiredAngle = MathUtils.atan2(desiredDy, desiredDx);
        float angleDiff = MathUtils.atan2(
                MathUtils.sin(desiredAngle - turret.angleRadians),
                MathUtils.cos(desiredAngle - turret.angleRadians));
        float maxTurn = TURRET_TURN_RATE_RADIANS * deltaTime;
        turret.angleRadians += MathUtils.clamp(angleDiff, -maxTurn, maxTurn);
    }
}
