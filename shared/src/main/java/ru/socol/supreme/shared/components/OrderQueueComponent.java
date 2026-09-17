package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;
import ru.socol.supreme.shared.QueuedOrder;

import java.util.ArrayList;
import java.util.List;

/**
 * Очередь отложенных приказов юнита — заполняет GameServer (shift-клик,
 * см. MoveUnitRequest/AttackUnitRequest/BuildOrderRequest.queue), двигает
 * OrderQueueSystem: как только юнит освобождается (нет активной атаки,
 * стройки и не в пути), она забирает первый элемент очереди и запускает
 * его тем же путём, каким обычный немедленный приказ уже запускается.
 * Обычный (не-queue) приказ очередь целиком очищает, а не добавляет в
 * неё — так и ожидается от "замены" приказа в большинстве RTS.
 *
 * Только сервер её когда-либо читает и меняет — клиент не визуализирует
 * содержимое очереди (пока), так что client-side этот компонент никогда
 * не создаётся и не заполняется.
 */
public class OrderQueueComponent implements Component, Pool.Poolable {

    public final List<QueuedOrder> queue = new ArrayList<>();

    @Override
    public void reset() {
        queue.clear();
    }
}
