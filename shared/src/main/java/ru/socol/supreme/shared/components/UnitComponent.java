package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;

/**
 * Стабильный сетевой идентификатор юнита. Сервер назначает его при создании
 * юнита и использует, чтобы отличать команды/снапшоты, относящиеся к
 * конкретному юниту; клиент — чтобы сопоставлять сущности снапшота
 * с локальными Ashley-сущностями (см. EntityFactory).
 */
public class UnitComponent implements Component {

    public int unitId;

    public UnitComponent() {
    }

    public UnitComponent(int unitId) {
        this.unitId = unitId;
    }
}
