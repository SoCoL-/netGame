package ru.socol.supreme.shared;

/**
 * Тип здания. Единственный источник истины "какое это здание" — хранится
 * на BuildingComponent.type у каждого здания с момента создания и не
 * меняется. Все остальные его свойства (размер, здоровье, время постройки,
 * что производит/добывает) — не Java-константы, а данные в buildings.json,
 * см. BuildingDefinitions.
 */
public enum BuildingType {
    HOME,
    ARCHER_BARRACKS,
    IRON_MINE,
    POWER_PLANT
}
