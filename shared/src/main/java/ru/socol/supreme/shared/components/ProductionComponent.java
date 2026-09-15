package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;
import ru.socol.supreme.shared.UnitType;

import java.util.ArrayList;
import java.util.List;

/**
 * Очередь производства юнитов у здания. На сервере это авторитетное
 * состояние, которое двигает ProductionSystem; на клиенте — просто
 * последнее значение из снапшота, нужное только для отрисовки панели
 * постройки (см. GameScreen.drawProductionPanel) и точки сбора
 * (GameScreen.drawRallyPoints).
 *
 * queue — не просто счётчик: здание может уметь производить НЕСКОЛЬКО
 * разных типов юнита сразу (сейчас — только дом: воин и строитель, см.
 * BuildingDefinitions.producesUnitTypesFor(HOME)), а какой тип строится в
 * данный момент — самый первый элемент очереди, FIFO. Раньше здесь были
 * queuedCount (int) + producesUnitType (один фиксированный тип на всё
 * здание) — этого хватало, пока у каждого здания был ровно один
 * производимый тип, но с появлением строителя перестало.
 */
public class ProductionComponent implements Component, Pool.Poolable {

    /** Очередь производства — порядок = порядок постройки. queue.get(0), если очередь не пуста, — тип юнита, который строится прямо сейчас. Авторитетно только на сервере. */
    public final List<UnitType> queue = new ArrayList<>();

    /**
     * Размер очереди для отображения на клиенте (GameScreen.drawProductionPanel)
     * — клиенту не нужно содержимое очереди (какие именно типы там стоят),
     * только количество, поэтому не пытаемся держать в синхроне сам queue
     * фиктивными записями; сервер эту переменную не читает вообще, только
     * пишет в неё broadcastSnapshot через UnitSnapshot.queuedCount, а
     * настоящий источник истины на сервере — queue.size().
     */
    public int queuedCount;

    /** Секунд прошло с начала постройки текущего (первого в очереди) юнита. 0, если очередь пуста. */
    public float progress;

    /**
     * Точка сбора — куда идёт каждый только что произведённый юнит (см.
     * ProductionSystem, посылает Pathfinding.setDestination сразу после
     * создания юнита). hasRallyPoint=false, пока игрок ни разу не кликнул
     * левой кнопкой по карте при выделенном здании — тогда юнит просто
     * остаётся стоять в точке появления, как и раньше.
     */
    public boolean hasRallyPoint;
    public float rallyX;
    public float rallyY;

    @Override
    public void reset() {
        queue.clear();
        queuedCount = 0;
        progress = 0f;
        hasRallyPoint = false;
        rallyX = 0f;
        rallyY = 0f;
    }
}
