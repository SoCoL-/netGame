package ru.socol.supreme.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.math.Vector2;

/**
 * Чисто клиентский, презентационный компонент — сервер о нём ничего не
 * знает и никогда его не создаёт. Хранит "откуда" и "куда" плавно ехать
 * отрисовываемой позиции юнита между двумя снапшотами.
 *
 * Подход упрощённый (лерп за время одного интервала снапшота), а не
 * полноценная буферизация снапшотов с рендером "в прошлое" — см.
 * InterpolationSystem и README для деталей и возможных улучшений.
 */
public class InterpolationComponent implements Component {

    public final Vector2 previousPosition = new Vector2();
    public final Vector2 targetPosition = new Vector2();

    /** Сколько секунд прошло с момента, когда targetPosition был обновлён последним снапшотом. */
    public float elapsed;
}
