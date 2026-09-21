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
    POWER_PLANT,
    IRON_STORAGE,
    ELECTRICITY_STORAGE,
    AIRCRAFT_FACTORY,
    TURRET,

    /**
     * Обломки погибшего юнита (GameServer.spawnWreck) — не настоящее
     * здание, а неподвижное препятствие с запасом железа внутри (см.
     * javadoc WreckComponent, почему это железо хранится в
     * HealthComponent, а не отдельным полем). Переиспользует
     * инфраструктуру препятствий/коллизий зданий (Pathfinding
     * .addBuildingObstacle, CollisionSystem), но никогда не строится
     * игроком (нет в BUILDABLE_TYPES), не производит и не добывает
     * ничего и не имеет владельца (OwnerComponent.playerId ==
     * GameConstants.NEUTRAL_OWNER_ID).
     */
    WRECK
}
