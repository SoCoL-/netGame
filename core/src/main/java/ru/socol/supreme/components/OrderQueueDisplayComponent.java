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
 * (GameScreen.drawOrderQueue) у выделенного юнита. ПЕРВАЯ точка — это
 * ТЕКУЩИЙ выполняемый приказ юнита (GameServer.currentOrderPointFor), а
 * не первый отложенный — так пунктирная линия всегда идёт от юнита к
 * его настоящей текущей цели, даже без единого отложенного приказа
 * позади. Пуст, только если у юнита вообще нет ни активного, ни
 * отложенных приказов.
 */
public class OrderQueueDisplayComponent implements Component {

    public final List<QueuedOrderPoint> points = new ArrayList<>();
}
