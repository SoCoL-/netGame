package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import ru.socol.supreme.shared.UnitType;

/**
 * Тип юнита (воин/стрелок). На сервере CombatSystem читает его, чтобы
 * выбрать дальность атаки (GameConstants.attackRangeFor); на клиенте
 * RenderSystem читает его, чтобы нарисовать стрелка иначе, чем воина.
 * Только у мобильных юнитов — у зданий его нет, у них вместо этого
 * ProductionComponent.producesUnitType (какой тип они производят, а не
 * какой тип они сами — здание не сражается).
 */
public class UnitTypeComponent implements Component {

    public UnitType type = UnitType.WARRIOR;

    public UnitTypeComponent() {
    }

    public UnitTypeComponent(UnitType type) {
        this.type = type;
    }
}
