package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Приказ строителю строить конкретное здание: id цели. Присутствие этого
 * компонента на юните означает "у юнита есть активный приказ строить" —
 * {@link ru.socol.supreme.shared.systems.BuildSystem} читает и
 * обрабатывает его каждый тик на сервере, тем же путём (погоня до
 * дистанции подхода, потом работа по цели), каким CombatSystem обрабатывает
 * AttackComponent. Обычный приказ на движение (MoveUnitRequest) или на
 * атаку (AttackUnitRequest) снимает этот компонент — новый приказ отменяет
 * стройку, как и любой другой активный приказ в этой игре.
 */
public class BuildOrderComponent implements Component, Pool.Poolable {

    public int targetBuildingUnitId;

    /**
     * Строитель СЕЙЧАС в радиусе цели и реально вносит вклад в стройку —
     * не просто идёт к ней. Считает и выставляет BuildSystem каждый тик
     * (первый проход, до расчёта скорости). Нужен отдельно от самого
     * присутствия BuildOrderComponent — клиенту важно рисовать
     * голографический луч (GameScreen) только пока строитель РАБОТАЕТ, а
     * не всё время, что у него есть приказ, включая время в пути.
     */
    public boolean inRange;

    public BuildOrderComponent() {
    }

    public BuildOrderComponent(int targetBuildingUnitId) {
        this.targetBuildingUnitId = targetBuildingUnitId;
    }

    @Override
    public void reset() {
        targetBuildingUnitId = 0;
        inRange = false;
    }
}
