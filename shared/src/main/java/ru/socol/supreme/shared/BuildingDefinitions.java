package ru.socol.supreme.shared;

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
    public static UnitType producesUnitTypeFor(BuildingType type) {
        return DEFINITIONS.get(type).producesUnitType;
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
        home.halfWidth = 50f;
        home.halfHeight = 50f;
        home.maxHealth = 500;
        home.buildTime = 0f;
        home.producesUnitType = UnitType.WARRIOR;
        home.spawnMargin = 200f;
        definitions.put(BuildingType.HOME, home);

        BuildingDefinition archerBarracks = new BuildingDefinition();
        archerBarracks.type = BuildingType.ARCHER_BARRACKS;
        archerBarracks.halfWidth = 25f;
        archerBarracks.halfHeight = 50f;
        archerBarracks.maxHealth = 70;
        archerBarracks.buildTime = 10f; // теперь строит сам игрок, не автоспавн — та же длительность, что у шахты/станции
        archerBarracks.producesUnitType = UnitType.ARCHER;
        archerBarracks.consumesResourceType = ResourceType.ELECTRICITY;
        archerBarracks.idleConsumptionRate = 0.5f;
        archerBarracks.activeConsumptionRate = 2f;
        definitions.put(BuildingType.ARCHER_BARRACKS, archerBarracks);

        BuildingDefinition ironMine = new BuildingDefinition();
        ironMine.type = BuildingType.IRON_MINE;
        ironMine.halfWidth = 25f;
        ironMine.halfHeight = 25f;
        ironMine.maxHealth = 70;
        ironMine.buildTime = 10f;
        ironMine.resourceType = ResourceType.IRON;
        ironMine.extractionRate = 1f;
        ironMine.snapRadius = 60f;
        definitions.put(BuildingType.IRON_MINE, ironMine);

        BuildingDefinition powerPlant = new BuildingDefinition();
        powerPlant.type = BuildingType.POWER_PLANT;
        powerPlant.halfWidth = 50f;
        powerPlant.halfHeight = 50f;
        powerPlant.maxHealth = 90;
        powerPlant.buildTime = 10f;
        powerPlant.resourceType = ResourceType.ELECTRICITY;
        powerPlant.extractionRate = 150f;
        definitions.put(BuildingType.POWER_PLANT, powerPlant);

        BuildingDefinition ironStorage = new BuildingDefinition();
        ironStorage.type = BuildingType.IRON_STORAGE;
        ironStorage.halfWidth = 25f;
        ironStorage.halfHeight = 25f;
        ironStorage.maxHealth = 80;
        ironStorage.buildTime = 10f;
        ironStorage.storesResourceType = ResourceType.IRON;
        ironStorage.storageCapacity = 500f;
        definitions.put(BuildingType.IRON_STORAGE, ironStorage);

        return definitions;
    }
}
