package ru.socol.supreme.shared;

/**
 * Тип боевого юнита. Влияет только на дальность атаки (см.
 * GameConstants.attackRangeFor) — здоровье, скорострельность, урон,
 * скорость движения и радиус авто-агрессии одинаковы для всех типов.
 */
public enum UnitType {
    WARRIOR,
    ARCHER
}
