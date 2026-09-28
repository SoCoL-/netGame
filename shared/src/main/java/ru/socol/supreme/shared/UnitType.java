package ru.socol.supreme.shared;

/**
 * Тип юнита. Все игровые характеристики — скорость, здоровье,
 * скорострельность, урон, дальность атаки, дальность обзора, стоимость
 * постройки, а у строителя ещё и радиус, с которого он может строить
 * здание, у разведчика — радиус разворота — заданы по типу в
 * units.json / UnitDefinitions, не общими константами.
 *
 * TURRET — особый случай: это не производимый юнит (никто его не строит
 * через ProductionSystem/очередь юнита, в BuildingDefinitions.producesUnitTypes
 * он нигде не упомянут), а служебная "боевая роль" стационарной защитной
 * турели (BuildingType.TURRET, см. GameServer.spawnBuilding) — сущность
 * турели одновременно и здание (размер/здоровье/стоимость/постройка — из
 * BuildingDefinitions), и этот UnitType (урон/скорострельность/дальность
 * атаки для AggroSystem/CombatSystem — из UnitDefinitions). health/speed/
 * ironCost/electricityCost/buildTime/buildRadius/sightRadius в её записи
 * units.json ничего не значат и не читаются — турель не создаётся через
 * GameServer.createUnit, см. javadoc UnitDefinition для этих полей.
 */
public enum UnitType {
    WARRIOR,
    ARCHER,
    BUILDER,
    SCOUT,
    ATTACK_AIRCRAFT,
    TURRET,
    // Новый тип дописан в конец, а не вставлен по алфавиту/смыслу между
    // остальными — порядковый номер константы (ordinal) кодируется по
    // сети как обычный int (см. QueueUnitRequest.unitType,
    // UnitSnapshot.unitType — "Kryo enum-as-int" конвенция), вставка в
    // середину сдвинула бы номера всех типов после неё и рассинхронила бы
    // клиент со старым сервером (и наоборот) при частичном обновлении.
    ANTI_AIR
}
