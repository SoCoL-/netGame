package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Здание сейчас ремонтируется — присутствует на сущности только пока
 * ремонт не завершён, зеркально ConstructionComponent для стройки.
 * Заводится один раз, в момент, когда игрок ВПЕРВЫЕ отправляет строителя
 * чинить это здание (GameServer.assignBuilderToRepair), а не при
 * получении повреждений сам по себе — здание может простоять
 * повреждённым сколько угодно, ремонт не идёт, пока к нему явно не
 * приставили строителя.
 *
 * startHealth/totalIronCost/totalTime фиксируются РАЗ, в момент
 * создания компонента, по проценту повреждений НА ТОТ МОМЕНТ — если
 * здание тем временем получит ещё повреждений (ремонт идёт, а по
 * зданию как раз в это время бьёт враг), уже идущий ремонт заново не
 * пересчитывается: пересчёт заново на каждый тик был бы такой же
 * ошибкой, как и для прогресса стройки (см. javadoc BuildSystem).
 *
 * remaining уменьшает {@link ru.socol.supreme.shared.systems.RepairSystem}
 * (сервер), и только пока рядом активно работает строитель с
 * RepairOrderComponent на эту сущность — по достижении 0 система сама
 * снимает этот компонент и выставляет health.currentHealth ровно в
 * maxHealth (в отличие от ConstructionSystem, тут не нужно добавлять
 * никакой другой компонент взамен — здание и так уже полностью
 * функционально всё это время, ремонт не прерывает его работу).
 */
public class RepairComponent implements Component, Pool.Poolable {

    /** Секунд до завершения ремонта. */
    public float remaining;

    /** Полное время ремонта — нужно на клиенте не для отрисовки (отдельного прогресс-бара ремонта нет, HP-бар и так растёт), а лишь как знаменатель для remaining при пересчёте прогресса тут же, на сервере. */
    public float totalTime;

    /** Сколько железа всего уйдёт на этот ремонт — процент повреждений здания на момент начала ремонта, см. javadoc GameServer.assignBuilderToRepair. Электричество не хранится отдельно — фиксированная сумма GameConstants.REPAIR_ELECTRICITY_COST одна на любой ремонт, вне зависимости от степени повреждений. */
    public int totalIronCost;

    /** Здоровье здания в момент начала ремонта — от него, а не от нуля, растёт currentHealth по мере прогресса (см. RepairSystem.advanceRepair). */
    public int startHealth;

    public RepairComponent() {
    }

    @Override
    public void reset() {
        remaining = 0f;
        totalTime = 0f;
        totalIronCost = 0;
        startHealth = 0;
    }
}
