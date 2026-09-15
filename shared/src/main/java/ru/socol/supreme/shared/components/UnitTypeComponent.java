package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;
import ru.socol.supreme.shared.UnitType;

/**
 * Тип юнита (воин/стрелок/строитель). На сервере CombatSystem/BuildSystem
 * /GameServer читают его, чтобы взять скорость/урон/дальность
 * атаки/дальность стройки из UnitDefinitions (units.json); на клиенте
 * RenderSystem читает его, чтобы нарисовать каждый тип со своей меткой.
 * Только у мобильных юнитов — у зданий его нет, у них вместо этого
 * BuildingComponent.type (какое это здание, а какие юниты оно
 * производит — решает не оно само, а BuildingDefinitions.
 * producesUnitTypesFor по этому типу здания).
 */
public class UnitTypeComponent implements Component, Pool.Poolable {

    public UnitType type = UnitType.WARRIOR;

    public UnitTypeComponent() {
    }

    public UnitTypeComponent(UnitType type) {
        this.type = type;
    }

    @Override
    public void reset() {
        type = UnitType.WARRIOR;
    }
}
