package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;
import ru.socol.supreme.shared.BuildingType;

/**
 * Маркер "это здание, не юнит" — и единственный источник истины, КАКОЕ
 * именно это здание. Задаётся один раз при создании и не меняется.
 * Раньше различие между домом/казармой/зданиями добычи решалось косвенно
 * (по наличию ProductionComponent/ConstructionComponent/
 * ResourceExtractorComponent) — теперь все они читают этот же type и
 * смотрят его свойства (размер, здоровье, что производит/добывает) в
 * BuildingDefinitions (buildings.json), а не гадают по компонентам.
 *
 * У зданий никогда нет DirectionComponent (не двигаются) — MovementSystem
 * автоматически их пропускает за счёт Family-фильтра, даже не заглядывая
 * в этот компонент.
 */
public class BuildingComponent implements Component, Pool.Poolable {

    public BuildingType type = BuildingType.HOME;

    public BuildingComponent() {
    }

    public BuildingComponent(BuildingType type) {
        this.type = type;
    }

    @Override
    public void reset() {
        type = BuildingType.HOME;
    }
}
