package ru.socol.supreme.components;

import com.badlogic.ashley.core.Component;

/**
 * Чисто клиентский компонент: присутствие на сущности строителя означает
 * "сейчас реально строит вот это здание" — рисуем между ними
 * голографический луч (GameScreen.drawBuildBeams). Сервер о нём ничего не
 * знает; заполняет и снимает его EntityFactory по полю
 * UnitSnapshot.buildTargetUnitId (0 — снять компонент, если был).
 */
public class BuildBeamComponent implements Component {

    public int targetBuildingUnitId;

    public BuildBeamComponent(int targetBuildingUnitId) {
        this.targetBuildingUnitId = targetBuildingUnitId;
    }
}
