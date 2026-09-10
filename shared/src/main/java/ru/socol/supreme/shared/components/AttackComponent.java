package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Приказ на атаку: id цели и таймер перезарядки. Присутствие этого
 * компонента на юните означает "у юнита есть активный приказ атаковать" —
 * {@link ru.socol.supreme.shared.systems.CombatSystem} читает и обновляет его каждый
 * тик на сервере. Обычный приказ на движение (MoveUnitRequest) снимает этот
 * компонент — как в большинстве RTS, новый приказ отменяет атаку.
 */
public class AttackComponent implements Component, Pool.Poolable {

    public int targetUnitId;

    /** Секунд до следующего выстрела. Стрельба возможна, когда <= 0. */
    public float cooldown;

    public AttackComponent() {
    }

    public AttackComponent(int targetUnitId) {
        this.targetUnitId = targetUnitId;
        this.cooldown = 0f;
    }

    @Override
    public void reset() {
        targetUnitId = 0;
        cooldown = 0f;
    }
}
