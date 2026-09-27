package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Присутствие этого компонента отличает наземный юнит от авиации —
 * ставится только там, где turnRadius == 0 (GameServer.createUnit, тот
 * же признак "наземный", что решает, нужен ли юниту AircraftComponent, у
 * авиации отдельной башни нет, орудие жёстко смотрит по курсу, см.
 * CombatSystem firingArcDegrees).
 *
 * Угол наведения башни в радианах (0 = вдоль +X, как Math.atan2/
 * MathUtils.atan2 — то же соглашение, что и у AircraftComponent.heading).
 * Доворачивает TurretAimSystem каждый тик — к текущей цели атаки
 * (AttackComponent), если она есть, иначе к направлению движения корпуса
 * (DirectionComponent.direction), не мгновенно, а с ограниченной
 * скоростью (GameConstants.TURRET_TURN_SPEED_DEGREES_PER_SECOND).
 *
 * Чисто косметическая штука для клиента: на исход боя не влияет вообще
 * (наземным ориентация корпуса для самой атаки не важна, см. javadoc
 * CombatSystem) — нужна только чтобы RenderSystem нарисовал
 * треугольник-башню, поворачивающийся отдельно от прямоугольного
 * корпуса, и чтобы GameScreen сместил по этому же углу точку вылета
 * визуального снаряда.
 */
public class TurretComponent implements Component, Pool.Poolable {

    public float angleRadians = 0f;

    @Override
    public void reset() {
        angleRadians = 0f;
    }
}
