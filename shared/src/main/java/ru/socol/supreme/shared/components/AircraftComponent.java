package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Присутствие этого компонента отличает авиацию от наземных юнитов —
 * AircraftMovementSystem обрабатывает только сущности с ним (а обычная
 * MovementSystem/CollisionSystem, наоборот, исключают его из своих
 * Family: наземная логика мгновенного поворота и выталкивания из
 * зданий/воды самолёту не подходит, он летает поверх всего этого).
 *
 * DirectionComponent (target/moving/speed) по-прежнему используется как
 * "куда юнит ХОЧЕТ лететь" — тот же компонент, что и у наземных юнитов,
 * ставят его те же места (handleMoveUnit, CombatSystem при погоне и
 * т.д.). Разница только в том, КАК AircraftMovementSystem его
 * интерпретирует — через курс, ограниченный радиусом разворота, а не
 * мгновенным довортом на цель.
 */
public class AircraftComponent implements Component, Pool.Poolable {

    /** Текущий курс, радианы — куда самолёт РЕАЛЬНО летит сейчас; может отличаться от направления на цель, потому что поворот не мгновенный. */
    public float heading;

    /** Максимальная угловая скорость поворота, рад/сек — speed/turnRadius (см. UnitDefinitions.turnRadiusFor), считается один раз при создании юнита (GameServer.createUnit), не каждый тик. */
    public float turnRate;

    /**
     * Может ли этот конкретный самолёт зависать неподвижно в воздухе
     * вместо обязательного кружения — не все типы авиации это умеют (см.
     * UnitDefinitions.canHoverFor, считается один раз при создании юнита,
     * как и turnRate). Разведчик не умеет и всегда кружит; штурмовик
     * умеет и просто останавливается там, где оказался. Если умеет —
     * AircraftMovementSystem не выставляет loitering вовсе, юнит просто
     * стоит на месте, куда бы его ни остановили (движение или бой).
     */
    public boolean canHover;

    /**
     * Кружит вокруг loiterCenter, а не остановился — актуально только для
     * авиации, которая НЕ умеет зависать (canHover=false): она физически
     * не может просто стоять в воздухе. Выставляется AircraftMovementSystem
     * сама (и по достижении цели движения, и если что-то ещё, например
     * CombatSystem, остановило юнита, не подумав об этом специально).
     */
    public boolean loitering;
    public float loiterCenterX;
    public float loiterCenterY;

    @Override
    public void reset() {
        heading = 0f;
        turnRate = 0f;
        canHover = false;
        loitering = false;
        loiterCenterX = 0f;
        loiterCenterY = 0f;
    }
}
