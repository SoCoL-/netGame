package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Запас снарядов артиллерийской башни (BuildingType.ARTILLERY). Появляется
 * только у ДОСТРОЕННОЙ башни (ConstructionSystem добавляет его по
 * завершении стройки) — пока компонента нет, башня не стреляет и снаряды
 * не строит.
 *
 * На сервере — авторитетное состояние, его двигает ArtillerySystem (стройка
 * снарядов) и GameServer.handleArtilleryFire (расход при выстреле). На
 * клиенте — последнее значение из снапшота, только для панели здания.
 */
public class ArtilleryComponent implements Component, Pool.Poolable {

    /** Готовых снарядов прямо сейчас (0..BuildingDefinitions.shellCapacityFor). */
    public int shells;

    /** Секунд прошло с начала постройки следующего снаряда. 0, когда снарядов максимум. */
    public float shellProgress;

    public ArtilleryComponent() {
    }

    @Override
    public void reset() {
        shells = 0;
        shellProgress = 0f;
    }
}
