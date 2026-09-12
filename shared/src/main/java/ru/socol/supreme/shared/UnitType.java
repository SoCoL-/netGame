package ru.socol.supreme.shared;

/**
 * Тип боевого юнита. Влияет на скорость, урон и дальность атаки — см.
 * units.json / UnitDefinitions, где эти цифры и заданы по типу.
 * Здоровье, скорострельность и радиус авто-агрессии одинаковы для всех
 * типов (остались обычными константами в GameConstants).
 */
public enum UnitType {
    WARRIOR,
    ARCHER
}
