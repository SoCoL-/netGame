package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Pool;

import java.util.ArrayList;
import java.util.List;

/**
 * Состояние артиллерийской башни (BuildingType.ARTILLERY). Появляется
 * только у ДОСТРОЕННОЙ башни (ConstructionSystem добавляет его по
 * завершении стройки) — пока компонента нет, башня не стреляет и снаряды
 * не строит.
 *
 * На сервере — авторитетное состояние, его двигает ArtillerySystem (стройка
 * снарядов, поворот ствола, выстрелы) и GameServer.handleArtilleryFire
 * (новые приказы на выстрел). На клиенте — последнее значение из снапшота,
 * для панели здания и отрисовки целей.
 */
public class ArtilleryComponent implements Component, Pool.Poolable {

    /** Готовых снарядов прямо сейчас (0..BuildingDefinitions.shellCapacityFor). */
    public int shells;

    /** Секунд прошло с начала постройки следующего снаряда. 0, когда снарядов максимум. */
    public float shellProgress;

    /** Куда смотрит ствол, радианы (0 — вправо, против часовой стрелки). */
    public float barrelAngle;

    /**
     * Приказы на выстрел, по порядку: башня доворачивается к первой точке
     * и стреляет, как только та попадает в конус стрельбы, затем берётся
     * за следующую. Приказов не бывает больше, чем готовых снарядов (см.
     * GameServer.handleArtilleryFire) — каждый приказ как бы резервирует
     * свой снаряд.
     */
    public final List<Vector2> pendingTargets = new ArrayList<>();

    public ArtilleryComponent() {
    }

    @Override
    public void reset() {
        shells = 0;
        shellProgress = 0f;
        barrelAngle = 0f;
        pendingTargets.clear();
    }
}
