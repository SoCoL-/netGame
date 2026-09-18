package ru.socol.supreme.shared;

/**
 * Данные одного типа здания — как они лежат в buildings.json. Простой
 * POJO с публичными полями, как и UnitDefinition — com.badlogic.gdx.utils
 * .Json (см. BuildingDefinitions) десериализует такие классы через
 * рефлексию по имени поля, включая enum-поля, без ручной настройки.
 *
 * Не у каждого здания используются все поля — producesUnitTypes актуален
 * только для HOME/ARCHER_BARRACKS, resourceType/extractionRate только для
 * IRON_MINE/POWER_PLANT, consumesResourceType/idleConsumptionRate/
 * activeConsumptionRate только для ARCHER_BARRACKS (пока — но поле
 * общее, не специфичное для неё одной, если в будущем появится ещё одно
 * потребляющее здание), spawnMargin только для HOME, snapRadius только
 * для IRON_MINE, storesResourceType/storageCapacity только для
 * IRON_STORAGE (тоже общее поле, не специфичное для железа — если
 * появится хранилище электричества, использует то же). Здание само не
 * использует свои "чужие" поля — какие именно читать, решает BuildingType.
 * ironCost/electricityCost — 0 у казармы и обоих хранилищ (бесплатны),
 * ненулевое у шахты и станции (см. их javadoc чуть ниже).
 */
public class BuildingDefinition {

    public BuildingType type;

    public float halfWidth;
    public float halfHeight;
    public int maxHealth;

    /** Секунд на постройку. 0 — здание появляется сразу готовым (сейчас только дом), не через ConstructionComponent. */
    public float buildTime;

    /** Актуален только для HOME/ARCHER_BARRACKS — какие юниты производятся. Иначе null/пусто. */
    public UnitType[] producesUnitTypes;

    /** Актуально только для IRON_MINE/POWER_PLANT — какой ресурс добывается. Иначе null. */
    public ResourceType resourceType;

    /** Актуально только если resourceType != null — единиц ресурса в секунду. */
    public float extractionRate;

    /**
     * Какой ресурс это здание тратит непрерывно, пока существует — null,
     * если не тратит вообще (дом, шахта, электростанция сейчас). Ставка
     * зависит от того, простаивает здание или работает — см.
     * idleConsumptionRate/activeConsumptionRate.
     */
    public ResourceType consumesResourceType;

    /** Актуально только если consumesResourceType != null. Единиц в секунду, когда очередь производства пуста. */
    public float idleConsumptionRate;

    /** Актуально только если consumesResourceType != null. Единиц в секунду, когда здание что-то производит. */
    public float activeConsumptionRate;

    /** Актуально только для HOME — отступ от края карты при автоспавне в углу. */
    public float spawnMargin;

    /** Актуально только для IRON_MINE — радиус "прилипания" превью к месторождению при постройке. */
    public float snapRadius;

    /** Какой ресурс это здание хранит (увеличивает вместимость) — null, если не хранилище. Сейчас только IRON_STORAGE. */
    public ResourceType storesResourceType;

    /** Актуально только если storesResourceType != null — на сколько единиц это здание увеличивает вместимость хранилища сверх базовой (GameConstants.IRON_BASE_CAPACITY). */
    public float storageCapacity;

    /**
     * Дальность обзора — насколько далеко вокруг этого здания рассеивается
     * туман войны (см. FogSnapshot/GameServer.updateFogOfWar), пока оно
     * существует. Настраиваемый параметр, а не общая константа — сейчас
     * у всех типов зданий одно и то же значение (70) просто потому, что
     * так задано в buildings.json для каждого из них по отдельности, а
     * не потому что где-то в коде это число одно на всех.
     */
    public float sightRadius;

    /**
     * Сколько железа/электричества стоит ПОСТРОИТЬ это здание —
     * списывается равномерно за время постройки, тем же приёмом, каким
     * уже тратится стоимость юнита в ProductionSystem (см. javadoc
     * BuildSystem — там же и учёт нескольких строителей). 0 у обоих —
     * здание бесплатно (сейчас так у казармы и обоих хранилищ; шахта и
     * станция стоят что-то одно из двух каждая).
     */
    public int ironCost;
    public int electricityCost;

    public BuildingDefinition() {
        // требуется Json для десериализации
    }
}
