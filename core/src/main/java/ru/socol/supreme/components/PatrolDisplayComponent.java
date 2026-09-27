package ru.socol.supreme.components;

import com.badlogic.ashley.core.Component;
import ru.socol.supreme.shared.network.messages.PatrolPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Чисто клиентский, презентационный компонент — сервер о нём не знает,
 * только заполняет UnitSnapshot.patrolPoints, откуда EntityFactory
 * копирует их сюда (тот же приём, что и у OrderQueueDisplayComponent —
 * переиспользуется сам DTO из сетевого сообщения, не заводится отдельный
 * клиентский тип ради тех же двух чисел). Используется только для
 * отрисовки замкнутого маршрута патрулирования (GameScreen
 * .drawPatrolRoute) у выделенного юнита. В отличие от
 * OrderQueueDisplayComponent тут нет "текущей" и "будущих" точек — весь
 * список равноправен и никогда не тратится, юнит идёт по кругу
 * бесконечно. Пуст, если юнит сейчас не патрулирует.
 */
public class PatrolDisplayComponent implements Component {

    public final List<PatrolPoint> points = new ArrayList<>();
}
