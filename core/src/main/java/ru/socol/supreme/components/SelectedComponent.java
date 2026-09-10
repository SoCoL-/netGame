package ru.socol.supreme.components;

import com.badlogic.ashley.core.Component;

/**
 * Чисто клиентский маркер без полей: присутствие этого компонента на
 * сущности означает "юнит сейчас выделен игроком". Сервер о нём ничего не
 * знает — это чисто презентационное состояние, которым управляет
 * GameScreen (добавляет/убирает при изменении выделения), а читает
 * RenderSystem, чтобы нарисовать подсветку.
 */
public class SelectedComponent implements Component {
}
