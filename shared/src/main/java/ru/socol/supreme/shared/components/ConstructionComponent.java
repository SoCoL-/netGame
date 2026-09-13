package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;
import ru.socol.supreme.shared.ResourceType;

/**
 * Здание ещё строится — присутствует на сущности только пока постройка не
 * завершена. ConstructionSystem (сервер) уменьшает remaining каждый тик; по
 * достижении 0 система сама снимает этот компонент и добавляет
 * ResourceExtractorComponent с тем же resourceType — вот зачем он здесь
 * нужен уже во время стройки, а не только когда здание заработает: без
 * него ConstructionSystem не знала бы, чем именно станет конкретное
 * строящееся здание (шахтой железа или электростанцией), а BuildingSizes
 * не знала бы, какого размера рисовать стройку (шахта 1x1, станция 2x2).
 * На клиенте — то же самое, только для отрисовки: пока компонент есть,
 * RenderSystem рисует "стройку" (тусклый цвет + прогресс-бар) вместо
 * готового здания, нужного размера.
 */
public class ConstructionComponent implements Component, Pool.Poolable {

    /** Секунд до завершения постройки. */
    public float remaining;

    /** Полное время постройки — нужно на клиенте, чтобы посчитать долю прогресса для прогресс-бара. */
    public float totalTime;

    /** Каким станет здание по завершении — IRON (шахта) или ELECTRICITY (электростанция). */
    public ResourceType resourceType = ResourceType.IRON;

    public ConstructionComponent() {
    }

    public ConstructionComponent(float totalTime, ResourceType resourceType) {
        this.totalTime = totalTime;
        this.remaining = totalTime;
        this.resourceType = resourceType;
    }

    @Override
    public void reset() {
        remaining = 0f;
        totalTime = 0f;
        resourceType = ResourceType.IRON;
    }
}
