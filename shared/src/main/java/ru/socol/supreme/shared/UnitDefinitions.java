package ru.socol.supreme.shared;

import com.badlogic.gdx.utils.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

/**
 * Загружает баланс юнитов (здоровье, скорость, скорострельность, урон,
 * дальность атаки/агрессии, стоимость) из units.json один раз при первом
 * обращении — как и статическая сетка препятствий в Pathfinding, баланс
 * за время партии не меняется, так что "посчитать один раз при старте"
 * достаточно.
 *
 * Файл ищется в текущей рабочей директории процесса (units.json рядом с
 * server.jar при запуске через java -jar, или рядом с корнем модуля
 * server при ./gradlew :server:run — см. buildJars в корневом
 * build.gradle, который кладёт units.json туда же, куда собранные jar-и).
 * Если файла нет, он повреждён или не описывает какой-то из типов —
 * для ЭТОГО типа используются встроенные значения по умолчанию
 * (defaultDefinitions), а не падение сервера: баланс не настолько
 * критичен, чтобы сервер отказывался запускаться без него.
 *
 * Используется только сервером (CombatSystem — дальность атаки, урон,
 * скорострельность; AggroSystem — та же дальность атаки как радиус
 * агрессии, отдельного радиуса агрессии больше нет; GameServer.createUnit
 * — скорость и здоровье). Клиенту эти цифры не нужны — он ничего не
 * считает сам, только показывает то, что уже посчитал сервер.
 *
 * Ровно ради этого всё и выносилось из Java-констант в данные: чтобы
 * добавить новый тип юнита, достаточно дописать запись в units.json, не
 * трогая код систем.
 */
public final class UnitDefinitions {

    private static final String UNITS_JSON_PATH = "units.json";

    private static final Map<UnitType, UnitDefinition> DEFINITIONS = load();

    private UnitDefinitions() {
    }

    public static float speedFor(UnitType type) {
        return DEFINITIONS.get(type).speed;
    }

    public static int healthFor(UnitType type) {
        return DEFINITIONS.get(type).health;
    }

    public static float fireRateFor(UnitType type) {
        return DEFINITIONS.get(type).fireRate;
    }

    /** Секунд между выстрелами — производная от fireRateFor, как раньше GameConstants.FIRE_INTERVAL от FIRE_RATE. */
    public static float fireIntervalFor(UnitType type) {
        return 1f / fireRateFor(type);
    }

    public static int damageFor(UnitType type) {
        return DEFINITIONS.get(type).damage;
    }

    /** Дальность атаки — она же радиус авто-агрессии, см. javadoc UnitDefinition.attackRadius. */
    public static float attackRadiusFor(UnitType type) {
        return DEFINITIONS.get(type).attackRadius;
    }

    /**
     * Наибольшая дальность атаки среди всех загруженных типов юнитов —
     * нужна AggroSystem/GameServer, чтобы подобрать размер ячейки
     * SpatialHashGrid под текущий баланс, а не под число, зашитое в код:
     * если в units.json добавят юнита с ещё большей дальностью, сетка
     * подстроится сама, без правки Java.
     */
    public static float maxAttackRadius() {
        float max = 0f;
        for (UnitType type : UnitType.values()) {
            max = Math.max(max, attackRadiusFor(type));
        }
        return max;
    }

    /** Сколько железа стоит один такой юнит — списывается равномерно за время постройки, см. ProductionSystem. */
    public static int ironCostFor(UnitType type) {
        return DEFINITIONS.get(type).ironCost;
    }

    /** Сколько электричества стоит один такой юнит — см. ironCostFor. */
    public static int electricityCostFor(UnitType type) {
        return DEFINITIONS.get(type).electricityCost;
    }

    /** Актуально только для строителя — на каком расстоянии он может строить здание, см. UnitDefinition.buildRadius. */
    public static float buildRadiusFor(UnitType type) {
        return DEFINITIONS.get(type).buildRadius;
    }

    /** Задел под будущий туман войны — сама механика ещё не сделана, это только данные. Есть у любого типа. */
    public static float sightRadiusFor(UnitType type) {
        return DEFINITIONS.get(type).sightRadius;
    }

    /** Актуально только для авиации — 0 у наземных типов, не используется (мгновенный поворот, как и раньше). */
    public static float turnRadiusFor(UnitType type) {
        return DEFINITIONS.get(type).turnRadius;
    }

    private static Map<UnitType, UnitDefinition> load() {
        Map<UnitType, UnitDefinition> definitions = defaultDefinitions();

        try {
            byte[] bytes = Files.readAllBytes(Paths.get(UNITS_JSON_PATH));
            String content = new String(bytes, StandardCharsets.UTF_8);
            UnitDefinition[] loaded = new Json().fromJson(UnitDefinition[].class, content);

            for (UnitDefinition definition : loaded) {
                definitions.put(definition.type, definition); // переопределяет дефолт для этого типа; остальные типы дефолт сохраняют
            }
            System.out.println("units.json: загружено " + loaded.length + " описаний юнитов из " + UNITS_JSON_PATH);
        } catch (Exception e) {
            System.out.println("units.json не найден или повреждён (" + e.getMessage()
                    + ") — используются встроенные значения по умолчанию для всех типов юнитов.");
        }

        return definitions;
    }

    /** Встроенные значения — то, чем баланс был до вынесения в JSON. Подстраховка на случай отсутствия/поломки файла. */
    private static Map<UnitType, UnitDefinition> defaultDefinitions() {
        Map<UnitType, UnitDefinition> definitions = new EnumMap<>(UnitType.class);
        definitions.put(UnitType.WARRIOR, new UnitDefinition(UnitType.WARRIOR, 80f, 20, 4f, 2, 70f, 0, 0, 0f, 150f, 0f));
        definitions.put(UnitType.ARCHER, new UnitDefinition(UnitType.ARCHER, 80f, 20, 4f, 2, 210f, 300, 300, 0f, 250f, 0f));
        definitions.put(UnitType.BUILDER, new UnitDefinition(UnitType.BUILDER, 80f, 65, 4f, 1, 70f, 100, 150, 210f, 120f, 0f));
        definitions.put(UnitType.SCOUT, new UnitDefinition(UnitType.SCOUT, 80f, 65, 4f, 4, 100f, 150, 200, 0f, 100f, 100f));
        return definitions;
    }
}
