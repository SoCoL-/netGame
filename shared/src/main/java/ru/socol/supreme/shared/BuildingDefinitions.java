package ru.socol.supreme.shared;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

/**
 * Загружает все данные зданий (размер, здоровье, время постройки, что
 * производят/добывают/тратят) из buildings.json один раз при первом
 * обращении — как и units.json/UnitDefinitions, баланс за партию не
 * меняется, "посчитать один раз при старте" достаточно.
 *
 * Файл ищется в текущей рабочей директории процесса — как и units.json,
 * см. её javadoc про buildJars и про то, что при запуске не через
 * собранные jar-и (а, например, ./gradlew :lwjgl3:run) клиент и сервер
 * могут не увидеть один и тот же файл и разойтись в конфигурации.
 * Единственная РЕАЛЬНАЯ разница с UnitDefinitions: её раньше вызывал
 * только сервер (клиенту скорость/урон не нужны, он их не считает сам),
 * а эту вызывает и клиент тоже (RenderSystem/GameScreen — размер и
 * значок здания), так что теперь действительно важно, чтобы buildings
 * .json был одинаково виден обеим сторонам, а не только серверу.
 *
 * Если файла нет, он повреждён или не описывает какой-то из типов — для
 * ЭТОГО типа используются встроенные значения по умолчанию
 * (defaultDefinitions), сервер не падает и не отказывается запускаться.
 */
public final class BuildingDefinitions {

    private static final String BUILDINGS_JSON_PATH = "buildings.json";

    private static final Map<BuildingType, BuildingDefinition> DEFINITIONS = load();

    private BuildingDefinitions() {
    }

    public static float halfWidthFor(BuildingType type) {
        return DEFINITIONS.get(type).halfWidth;
    }

    public static float halfHeightFor(BuildingType type) {
        return DEFINITIONS.get(type).halfHeight;
    }

    public static int maxHealthFor(BuildingType type) {
        return DEFINITIONS.get(type).maxHealth;
    }

    /** 0 — здание появляется сразу готовым (сейчас только дом), не через ConstructionComponent. */
    public static float buildTimeFor(BuildingType type) {
        return DEFINITIONS.get(type).buildTime;
    }

    /** null, если это здание не производит юнитов (здания добычи ресурсов). */
    private static final UnitType[] NO_PRODUCED_TYPES = new UnitType[0];

    /** Какие юниты это здание умеет производить — пустой массив (не null), если ничего не производит (здания добычи/хранилища). */
    public static UnitType[] producesUnitTypesFor(BuildingType type) {
        UnitType[] types = DEFINITIONS.get(type).producesUnitTypes;
        return types != null ? types : NO_PRODUCED_TYPES;
    }

    /** null, если это здание не добывает ресурс (дом, казарма). */
    public static ResourceType resourceTypeFor(BuildingType type) {
        return DEFINITIONS.get(type).resourceType;
    }

    public static float extractionRateFor(BuildingType type) {
        return DEFINITIONS.get(type).extractionRate;
    }

    /** null, если это здание не тратит ресурс непрерывно (сейчас — все, кроме казармы стрелков). */
    public static ResourceType consumesResourceTypeFor(BuildingType type) {
        return DEFINITIONS.get(type).consumesResourceType;
    }

    /** Единиц в секунду, когда очередь производства пуста. Актуально только если consumesResourceTypeFor != null. */
    public static float idleConsumptionRateFor(BuildingType type) {
        return DEFINITIONS.get(type).idleConsumptionRate;
    }

    /** Единиц в секунду, когда здание что-то производит. Актуально только если consumesResourceTypeFor != null. */
    public static float activeConsumptionRateFor(BuildingType type) {
        return DEFINITIONS.get(type).activeConsumptionRate;
    }

    public static float snapRadiusFor(BuildingType type) {
        return DEFINITIONS.get(type).snapRadius;
    }

    /** null, если это здание не увеличивает вместимость хранилища ресурса. Сейчас — только IRON_STORAGE (IRON). */
    public static ResourceType storesResourceTypeFor(BuildingType type) {
        return DEFINITIONS.get(type).storesResourceType;
    }

    /** Актуально только если storesResourceTypeFor != null. */
    public static float storageCapacityFor(BuildingType type) {
        return DEFINITIONS.get(type).storageCapacity;
    }

    /** Место автоспавна дома игрока — в противоположных углах карты. Возвращает {x, y}. Единственное здание, которое сервер ставит сам при входе игрока — остальные строит сам игрок из меню (клавиша B). */
    public static float[] homeSpawnPoint(int playerId) {
        float margin = DEFINITIONS.get(BuildingType.HOME).spawnMargin;
        float x = playerId == 0 ? margin : GameConstants.MAP_WIDTH - margin;
        float y = playerId == 0 ? margin : GameConstants.MAP_HEIGHT - margin;
        return new float[]{x, y};
    }

    /**
     * Безопасный (не занижающий) радиус взаимодействия юнит-здание —
     * диагональ до угла САМОГО крупного из всех типов зданий, плюс запас
     * на радиус юнита. Единая формула для двух мест, которые обязаны
     * совпадать по порядку величины: размер ячейки SpatialHashGrid при её
     * создании (GameServer) и радиус запроса к ней же в CollisionSystem.
     */
    /** Дальность обзора этого здания — задел под туман войны, настраиваемый за-типно, см. javadoc BuildingDefinition.sightRadius. */
    public static float sightRadiusFor(BuildingType type) {
        return DEFINITIONS.get(type).sightRadius;
    }

    public static float maxInteractionRadius() {
        float maxHalfDimension = 0f;
        for (BuildingType type : BuildingType.values()) {
            maxHalfDimension = Math.max(maxHalfDimension, halfWidthFor(type));
            maxHalfDimension = Math.max(maxHalfDimension, halfHeightFor(type));
        }
        return (float) Math.sqrt(2) * maxHalfDimension + GameConstants.UNIT_RADIUS;
    }

    /**
     * На каком расстоянии от центра здания появляется готовый юнит —
     * зависит от размера конкретного здания (дом крупнее казармы). Берём
     * диагональ ДО УГЛА раздутой (на PATH_CLEARANCE) зоны этого здания —
     * самую длинную возможную "дистанцию выхода" из его прямоугольника в
     * любом направлении — и умножаем на 1.5 для запаса, а не впритык к
     * границе.
     */
    public static float productionSpawnDistanceFor(BuildingType type) {
        float inflatedHalfWidth = halfWidthFor(type) + GameConstants.PATH_CLEARANCE;
        float inflatedHalfHeight = halfHeightFor(type) + GameConstants.PATH_CLEARANCE;
        float cornerDistance = (float) Math.sqrt(inflatedHalfWidth * inflatedHalfWidth + inflatedHalfHeight * inflatedHalfHeight);
        return cornerDistance * 1.5f;
    }

    /**
     * Точка чуть в стороне от здания данного типа, по направлению к
     * центру карты, на productionSpawnDistanceFor от него — общая формула
     * для места появления произведённого юнита (ProductionSystem) и
     * начального строителя, который появляется вместе с домом при входе
     * игрока (GameServer.spawnHomeAndBuilder), чтобы не дублировать один и тот же
     * расчёт в двух местах.
     */
    public static Vector2 spawnPointNear(BuildingType type, Vector2 buildingPosition) {
        Vector2 towardCenter = new Vector2(GameConstants.MAP_WIDTH / 2f, GameConstants.MAP_HEIGHT / 2f)
                .sub(buildingPosition)
                .nor();
        return new Vector2(buildingPosition).mulAdd(towardCenter, productionSpawnDistanceFor(type));
    }

    /** Сколько железа стоит построить это здание — списывается равномерно за время постройки (BuildSystem), тем же приёмом, что и стоимость юнита в ProductionSystem. 0, если бесплатно (сейчас — казарма, оба хранилища). */
    public static int ironCostFor(BuildingType type) {
        return DEFINITIONS.get(type).ironCost;
    }

    /** Сколько электричества стоит построить это здание — см. ironCostFor. */
    public static int electricityCostFor(BuildingType type) {
        return DEFINITIONS.get(type).electricityCost;
    }

    private static Map<BuildingType, BuildingDefinition> load() {
        Map<BuildingType, BuildingDefinition> definitions = defaultDefinitions();

        try {
            byte[] bytes = Files.readAllBytes(Paths.get(BUILDINGS_JSON_PATH));
            String content = new String(bytes, StandardCharsets.UTF_8);
            BuildingDefinition[] loaded = new Json().fromJson(BuildingDefinition[].class, content);

            for (BuildingDefinition definition : loaded) {
                definitions.put(definition.type, definition); // переопределяет дефолт для этого типа; остальные типы дефолт сохраняют
            }
            System.out.println("buildings.json: загружено " + loaded.length + " описаний зданий из " + BUILDINGS_JSON_PATH);
        } catch (Exception e) {
            System.out.println("buildings.json не найден или повреждён (" + e.getMessage()
                    + ") — используются встроенные значения по умолчанию для всех типов зданий.");
        }

        return definitions;
    }

    /** Встроенные значения — то, чем баланс зданий был до вынесения в JSON. Подстраховка на случай отсутствия/поломки файла. */
    private static Map<BuildingType, BuildingDefinition> defaultDefinitions() {
        Map<BuildingType, BuildingDefinition> definitions = new EnumMap<>(BuildingType.class);

        BuildingDefinition home = new BuildingDefinition();
        home.type = BuildingType.HOME;
        home.sightRadius = 420f; // настраиваемый параметр — см. javadoc BuildingDefinition.sightRadius, почему у всех сейчас одно и то же число
        home.halfWidth = 50f;
        home.halfHeight = 50f;
        home.maxHealth = 500;
        home.buildTime = 0f;
        home.producesUnitTypes = new UnitType[]{UnitType.WARRIOR, UnitType.BUILDER};
        home.spawnMargin = 800f; // увеличен в 4 раза вместе с картой (было 200 при 2000x2000) — та же относительная позиция дома в углу карты
        definitions.put(BuildingType.HOME, home);

        BuildingDefinition archerBarracks = new BuildingDefinition();
        archerBarracks.type = BuildingType.ARCHER_BARRACKS;
        archerBarracks.sightRadius = 420f; // настраиваемый параметр — см. javadoc BuildingDefinition.sightRadius, почему у всех сейчас одно и то же число
        archerBarracks.halfWidth = 25f;
        archerBarracks.halfHeight = 50f;
        archerBarracks.maxHealth = 70;
        archerBarracks.buildTime = 10f; // теперь строит сам игрок, не автоспавн — та же длительность, что у шахты/станции
        archerBarracks.producesUnitTypes = new UnitType[]{UnitType.ARCHER};
        archerBarracks.consumesResourceType = ResourceType.ELECTRICITY;
        archerBarracks.idleConsumptionRate = 0.5f;
        archerBarracks.activeConsumptionRate = 2f;
        definitions.put(BuildingType.ARCHER_BARRACKS, archerBarracks);

        BuildingDefinition ironMine = new BuildingDefinition();
        ironMine.type = BuildingType.IRON_MINE;
        ironMine.sightRadius = 420f; // настраиваемый параметр — см. javadoc BuildingDefinition.sightRadius, почему у всех сейчас одно и то же число
        ironMine.halfWidth = 25f;
        ironMine.halfHeight = 25f;
        ironMine.maxHealth = 70;
        ironMine.buildTime = 10f;
        ironMine.resourceType = ResourceType.IRON;
        ironMine.extractionRate = 20f;
        ironMine.snapRadius = 60f;
        ironMine.ironCost = 50;
        definitions.put(BuildingType.IRON_MINE, ironMine);

        BuildingDefinition powerPlant = new BuildingDefinition();
        powerPlant.type = BuildingType.POWER_PLANT;
        powerPlant.sightRadius = 420f; // настраиваемый параметр — см. javadoc BuildingDefinition.sightRadius, почему у всех сейчас одно и то же число
        powerPlant.halfWidth = 50f;
        powerPlant.halfHeight = 50f;
        powerPlant.maxHealth = 90;
        powerPlant.buildTime = 10f;
        powerPlant.resourceType = ResourceType.ELECTRICITY;
        powerPlant.extractionRate = 150f;
        powerPlant.electricityCost = 50;
        definitions.put(BuildingType.POWER_PLANT, powerPlant);

        BuildingDefinition ironStorage = new BuildingDefinition();
        ironStorage.type = BuildingType.IRON_STORAGE;
        ironStorage.sightRadius = 420f; // настраиваемый параметр — см. javadoc BuildingDefinition.sightRadius, почему у всех сейчас одно и то же число
        ironStorage.halfWidth = 25f;
        ironStorage.halfHeight = 25f;
        ironStorage.maxHealth = 80;
        ironStorage.buildTime = 10f;
        ironStorage.storesResourceType = ResourceType.IRON;
        ironStorage.storageCapacity = 500f;
        definitions.put(BuildingType.IRON_STORAGE, ironStorage);

        BuildingDefinition electricityStorage = new BuildingDefinition();
        electricityStorage.type = BuildingType.ELECTRICITY_STORAGE;
        electricityStorage.sightRadius = 420f; // настраиваемый параметр — см. javadoc BuildingDefinition.sightRadius, почему у всех сейчас одно и то же число
        electricityStorage.halfWidth = 25f;
        electricityStorage.halfHeight = 25f;
        electricityStorage.maxHealth = 80;
        electricityStorage.buildTime = 10f;
        electricityStorage.storesResourceType = ResourceType.ELECTRICITY;
        electricityStorage.storageCapacity = 3000f; // крупнее, чем у IRON_STORAGE — электричество течёт куда быстрее (150/сек против 20/сек у железа)
        definitions.put(BuildingType.ELECTRICITY_STORAGE, electricityStorage);

        BuildingDefinition aircraftFactory = new BuildingDefinition();
        aircraftFactory.type = BuildingType.AIRCRAFT_FACTORY;
        aircraftFactory.sightRadius = 420f; // настраиваемый параметр — см. javadoc BuildingDefinition.sightRadius, почему у всех сейчас одно и то же число
        aircraftFactory.halfWidth = 50f; // 2 клетки по 50
        aircraftFactory.halfHeight = 75f; // 3 клетки по 50
        aircraftFactory.maxHealth = 50;
        aircraftFactory.buildTime = 10f;
        aircraftFactory.producesUnitTypes = new UnitType[]{UnitType.SCOUT, UnitType.ATTACK_AIRCRAFT};
        aircraftFactory.consumesResourceType = ResourceType.ELECTRICITY;
        aircraftFactory.idleConsumptionRate = 30f;
        aircraftFactory.activeConsumptionRate = 70f;
        aircraftFactory.ironCost = 200;
        aircraftFactory.electricityCost = 1000;
        definitions.put(BuildingType.AIRCRAFT_FACTORY, aircraftFactory);

        BuildingDefinition turret = new BuildingDefinition();
        turret.type = BuildingType.TURRET;
        turret.sightRadius = 200f; // задано отдельно от остальных зданий (у них 420) — обзор именно этого типа
        turret.halfWidth = 25f;
        turret.halfHeight = 25f;
        turret.maxHealth = 50;
        turret.buildTime = 10f;
        turret.ironCost = 100;
        definitions.put(BuildingType.TURRET, turret);

        // Обломки — не настоящее здание (см. javadoc BuildingType.WRECK),
        // поэтому большинство полей тут не используются вовсе: buildTime/
        // ironCost/electricityCost == 0 (игрок их не строит), sightRadius
        // == 0 (не видят). Единственное, что реально читается —
        // halfWidth/halfHeight (BuildingSizes/Pathfinding.addBuildingObstacle,
        // размер препятствия) и maxHealth — тоже НЕ читается: настоящий
        // запас железа задаётся напрямую на HealthComponent в момент
        // создания (GameServer.spawnWreck), это поле оставлено 0 просто
        // чтобы не вводить в заблуждение. Размер чуть больше UNIT_RADIUS
        // (10) — заметно меньше самого маленького настоящего здания (25),
        // обломки юнита не должны выглядеть/перекрывать как постройка.
        BuildingDefinition wreck = new BuildingDefinition();
        wreck.type = BuildingType.WRECK;
        wreck.halfWidth = 15f;
        wreck.halfHeight = 15f;
        definitions.put(BuildingType.WRECK, wreck);

        return definitions;
    }
}
