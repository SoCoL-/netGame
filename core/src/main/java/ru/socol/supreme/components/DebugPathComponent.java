package ru.socol.supreme.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.math.Vector2;

import java.util.ArrayList;
import java.util.List;

/**
 * Чисто клиентский, презентационный компонент — сервер о нём не знает,
 * только заполняет UnitSnapshot.pathPoints, откуда EntityFactory
 * копирует их сюда. Используется только для отладочной отрисовки
 * маршрута (см. GameScreen, режим по клавише `). Пуст, если юнит не
 * движется.
 */
public class DebugPathComponent implements Component {

    public final List<Vector2> points = new ArrayList<>();
}
