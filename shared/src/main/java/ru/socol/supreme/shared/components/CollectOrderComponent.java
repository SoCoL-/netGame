package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Приказ строителю собирать железо с обломков погибшего юнита
 * (WreckComponent) — устроен зеркально BuildOrderComponent/
 * RepairOrderComponent: тот же приём точки подхода (см. подробный
 * javadoc BuildSystem.updateBuilder про то, почему она считается лениво,
 * не каждый тик, и с отступом внутрь границы buildRadius) и тот же общий
 * цикл "первый проход решает, кто сейчас в радиусе, второй разом
 * начисляет прогресс" (см. ScavengeSystem). Обычный приказ на движение,
 * атаку, стройку или ремонт снимает этот компонент — как и любой другой
 * активный приказ в этой игре; и наоборот, приказ на сбор снимает их.
 *
 * Строитель не может одновременно и строить/чинить, и собирать — у него
 * либо BuildOrderComponent/RepairOrderComponent, либо
 * CollectOrderComponent, никогда несколько сразу (везде, где выдаётся
 * один из приказов, остальные явно снимаются).
 */
public class CollectOrderComponent implements Component, Pool.Poolable {

    public int targetWreckUnitId;

    /**
     * Строитель СЕЙЧАС в радиусе обломков и реально собирает железо — не
     * просто идёт к ним. Тот же смысл, что и BuildOrderComponent.inRange/
     * RepairOrderComponent.inRange: GameServer.broadcastSnapshot пишет id
     * цели в то же самое unitSnapshot.buildTargetUnitId, что и для
     * стройки/ремонта — с точки зрения голографического луча на клиенте
     * (GameScreen.drawBuildBeams) все три занятия неотличимы, это просто
     * "строитель что-то делает вон с той сущностью".
     */
    public boolean inRange;

    /** Точка подхода к обломкам — та же логика и та же причина ленивого пересчёта, что и у BuildOrderComponent.hasApproachPoint/approachX/approachY. */
    public boolean hasApproachPoint;
    public float approachX;
    public float approachY;

    public CollectOrderComponent() {
    }

    public CollectOrderComponent(int targetWreckUnitId) {
        this.targetWreckUnitId = targetWreckUnitId;
    }

    @Override
    public void reset() {
        targetWreckUnitId = 0;
        inRange = false;
        hasApproachPoint = false;
        approachX = 0f;
        approachY = 0f;
    }
}
