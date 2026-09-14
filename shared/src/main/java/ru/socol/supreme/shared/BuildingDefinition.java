package ru.socol.supreme.shared;

/**
 * Данные одного типа здания — как они лежат в buildings.json. Простой
 * POJO с публичными полями, как и UnitDefinition — com.badlogic.gdx.utils
 * .Json (см. BuildingDefinitions) десериализует такие классы через
 * рефлексию по имени поля, включая enum-поля, без ручной настройки.
 *
 * Не у каждого здания используются все поля — producesUnitType актуален
 * только для HOME/ARCHER_BARRACKS, resourceType/extractionRate только для
 * IRON_MINE/POWER_PLANT, spawnMargin только для HOME, offsetFromHome
 * только для ARCHER_BARRACKS, snapRadius только для IRON_MINE. Здание
 * само не использует свои "чужие" поля — какие именно читать, решает
 * BuildingType.
 */
public class BuildingDefinition {

    public BuildingType type;

    public float halfWidth;
    public float halfHeight;
    public int maxHealth;

    /** Секунд на постройку. 0 — здание появляется сразу готовым (дом, казарма), не через ConstructionComponent. */
    public float buildTime;

    /** Актуально только для HOME/ARCHER_BARRACKS — какой юнит производится. Иначе null. */
    public UnitType producesUnitType;

    /** Актуально только для IRON_MINE/POWER_PLANT — какой ресурс добывается. Иначе null. */
    public ResourceType resourceType;

    /** Актуально только если resourceType != null — единиц ресурса в секунду. */
    public float extractionRate;

    /** Актуально только для HOME — отступ от края карты при автоспавне в углу. */
    public float spawnMargin;

    /** Актуально только для ARCHER_BARRACKS — расстояние от дома того же игрока по оси X при автоспавне. */
    public float offsetFromHome;

    /** Актуально только для IRON_MINE — радиус "прилипания" превью к месторождению при постройке. */
    public float snapRadius;

    public BuildingDefinition() {
        // требуется Json для десериализации
    }
}
