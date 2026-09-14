package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Здание ещё строится — присутствует на сущности только пока постройка не
 * завершена. ConstructionSystem (сервер) уменьшает remaining каждый тик;
 * по достижении 0 система сама снимает этот компонент и добавляет тот,
 * который делает здание функциональным (ResourceExtractorComponent или
 * ProductionComponent — какое именно, решает BuildingComponent.type через
 * BuildingDefinitions, не этот компонент: ему для этого не нужно знать
 * тип здания отдельно, он уже есть на BuildingComponent той же сущности).
 * На клиенте — то же самое, только для отрисовки: пока компонент есть,
 * RenderSystem рисует "стройку" (тусклый цвет + прогресс-бар) вместо
 * готового здания, нужного размера (размер тоже решает BuildingComponent
 * .type через BuildingDefinitions/BuildingSizes).
 */
public class ConstructionComponent implements Component, Pool.Poolable {

    /** Секунд до завершения постройки. */
    public float remaining;

    /** Полное время постройки — нужно на клиенте, чтобы посчитать долю прогресса для прогресс-бара. */
    public float totalTime;

    public ConstructionComponent() {
    }

    public ConstructionComponent(float totalTime) {
        this.totalTime = totalTime;
        this.remaining = totalTime;
    }

    @Override
    public void reset() {
        remaining = 0f;
        totalTime = 0f;
    }
}
