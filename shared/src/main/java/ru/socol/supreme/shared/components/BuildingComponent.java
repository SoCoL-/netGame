package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Маркер без полей: сущность — здание, а не юнит. У зданий никогда нет
 * DirectionComponent (не двигаются) и AttackComponent (не атакуют) —
 * MovementSystem и CombatSystem автоматически пропускают их за счёт
 * Family-фильтров, даже не заглядывая в этот компонент. Он нужен там, где
 * важно явно отличить "это здание": спавн на сервере, снапшот, отрисовка и
 * фильтрация выделения на клиенте.
 */
public class BuildingComponent implements Component, Pool.Poolable {
    @Override
    public void reset() {
        //Nothing
    }
}
