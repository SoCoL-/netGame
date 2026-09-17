package ru.socol.supreme.components;

import com.badlogic.ashley.core.Component;
import ru.socol.supreme.shared.network.messages.QueuedOrderPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Чисто клиентский, презентационный компонент — сервер о нём не знает,
 * только заполняет UnitSnapshot.queuedOrders, откуда EntityFactory
 * копирует их сюда (переиспользует сам DTO из сетевого сообщения, не
 * заводит отдельный клиентский тип ради тех же двух чисел и типа
 * приказа). Используется только для отрисовки цепочки очереди
 * (GameScreen.drawOrderQueue) у выделенного юнита. Пуст, если у юнита
 * нет отложенных приказов.
 */
public class OrderQueueDisplayComponent implements Component {

    public final List<QueuedOrderPoint> points = new ArrayList<>();
}
