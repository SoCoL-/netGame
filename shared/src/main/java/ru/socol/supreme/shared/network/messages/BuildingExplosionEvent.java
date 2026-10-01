package ru.socol.supreme.shared.network.messages;

/**
 * Сервер -> клиенты матча: разрушенное здание взорвалось (сейчас —
 * электростанция, см. BuildingDefinition.destructionBlastDamage). Урон уже
 * применён на сервере; это сообщение чисто косметическое — нарисовать
 * взрыв радиусом radius в точке (x, y).
 */
public class BuildingExplosionEvent {

    public float x;
    public float y;
    public float radius;

    public BuildingExplosionEvent() {
    }
}
