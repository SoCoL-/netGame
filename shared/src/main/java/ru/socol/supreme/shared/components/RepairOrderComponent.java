package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Приказ строителю ремонтировать конкретное (уже достроенное, но
 * повреждённое) здание — устроен зеркально BuildOrderComponent, только
 * цель другая: там ConstructionComponent, тут RepairComponent (см. её
 * javadoc). {@link ru.socol.supreme.shared.systems.RepairSystem} читает
 * и обрабатывает этот компонент каждый тик на сервере, тем же путём
 * (погоня до дистанции подхода, потом работа по цели), каким BuildSystem
 * обрабатывает BuildOrderComponent. Обычный приказ на движение
 * (MoveUnitRequest), на атаку (AttackUnitRequest) или на стройку другого
 * здания (BuildOrderRequest) снимает этот компонент — новый приказ
 * отменяет ремонт, как и любой другой активный приказ в этой игре.
 *
 * Строитель не может одновременно и строить, и ремонтировать — у него
 * либо BuildOrderComponent, либо RepairOrderComponent, никогда оба сразу
 * (везде, где выдаётся один из двух приказов, второй снимается явно).
 */
public class RepairOrderComponent implements Component, Pool.Poolable {

    public int targetBuildingUnitId;

    /**
     * Строитель СЕЙЧАС в радиусе цели и реально ремонтирует — не просто
     * идёт к ней. Тот же смысл и та же причина, что и у
     * BuildOrderComponent.inRange: клиенту нужно рисовать голографический
     * луч (GameScreen.drawBuildBeams) только пока строитель РАБОТАЕТ.
     * GameServer.broadcastSnapshot пишет id цели в то же самое
     * unitSnapshot.buildTargetUnitId, что и для стройки — с точки зрения
     * этого луча ремонт и стройка неотличимы, оба означают "строитель
     * что-то делает вон с тем зданием".
     */
    public boolean inRange;

    /** Точка подхода к зданию — тот же смысл и та же причина ленивого (не каждый тик) пересчёта, что и у BuildOrderComponent.hasApproachPoint/approachX/approachY, см. её подробный javadoc и javadoc BuildSystem.updateBuilder. */
    public boolean hasApproachPoint;
    public float approachX;
    public float approachY;

    public RepairOrderComponent() {
    }

    public RepairOrderComponent(int targetBuildingUnitId) {
        this.targetBuildingUnitId = targetBuildingUnitId;
    }

    @Override
    public void reset() {
        targetBuildingUnitId = 0;
        inRange = false;
        hasApproachPoint = false;
        approachX = 0f;
        approachY = 0f;
    }
}
