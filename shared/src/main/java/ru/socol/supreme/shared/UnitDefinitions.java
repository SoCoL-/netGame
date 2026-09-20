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
 * Файл ищется в текущей рабочей директории процесса — единая assets/ в
 * корне проекта, а не отдельная копия под сервер (см. её же комментарий
 * в server/build.gradle, откуда там ./gradlew :server:run/IDE берут эту
 * рабочую директорию, и buildJars в корневом build.gradle, который при
 * java -jar кладёт units.json рядом с самим server.jar).
 * Если файла нет, он повреждён или не описывает какой-то из типов —
 * для ЭТОГО типа используются встроенные значения по умолчанию
 * (defaultDefinitions), а не падение сервера: баланс не настолько
 * критичен, чтобы сервер отказывался запускаться без него.
 *
 * Используется в основном сервером (CombatSystem — дальность атаки, урон,
 * скорострельность; AggroSystem — та же дальность атаки как радиус
 * агрессии, отдельного радиуса агрессии больше нет; GameServer.createUnit
 * — скорость и здоровье) — он ничего не пересчитывает заново из снапшота,
 * все решения принимает сам. Клиент тоже кое-что читает отсюда, но только
 * для отображения (например, GameScreen.drawBuildingInfoPanel —
 * buildTimeFor для доли прогресс-бара), никогда для авторитетных решений.
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

    /** Секунд на постройку одного юнита этого типа — раньше была общая GameConstants.UNIT_BUILD_TIME на всех, теперь своя у каждого типа. */
    public static float buildTimeFor(UnitType type) {
        return DEFINITIONS.get(type).buildTime;
    }

    /** Актуально только для авиации — может ли она зависать неподвижно вместо обязательного кружения (см. AircraftMovementSystem.loitering). false у наземных типов — не используется. */
    public static boolean canHoverFor(UnitType type) {
        return DEFINITIONS.get(type).canHover;
    }

    /** Половина угла конуса стрельбы вперёд, градусы — актуально только для авиации (см. CombatSystem). 0 у наземных типов — не используется, им ориентация не важна. */
    public static float firingArcDegreesFor(UnitType type) {
        return DEFINITIONS.get(type).firingArcDegrees;
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
        definitions.put(UnitType.WARRIOR, new UnitDefinition(UnitType.WARRIOR, 240f, 20, 4f, 2, 70f, 0, 0, 10f, 0f, 450f, 0f, false, 0f));
        definitions.put(UnitType.ARCHER, new UnitDefinition(UnitType.ARCHER, 240f, 20, 4f, 2, 210f, 300, 300, 10f, 0f, 750f, 0f, false, 0f));
        definitions.put(UnitType.BUILDER, new UnitDefinition(UnitType.BUILDER, 240f, 65, 4f, 1, 70f, 100, 150, 10f, 210f, 360f, 0f, false, 0f));
        definitions.put(UnitType.SCOUT, new UnitDefinition(UnitType.SCOUT, 240f, 65, 4f, 4, 100f, 150, 200, 10f, 0f, 300f, 100f, false, 45f));
        definitions.put(UnitType.ATTACK_AIRCRAFT, new UnitDefinition(UnitType.ATTACK_AIRCRAFT, 180f, 100, 4f, 4, 100f, 100, 800, 12f, 0f, 360f, 20f, true, 55f));
        // Только damage/fireRate/attackRadius тут реально используются
        // (CombatSystem/AggroSystem) — остальные поля для турели ничего не
        // значат, см. javadoc UnitType.TURRET, почему.
        definitions.put(UnitType.TURRET, new UnitDefinition(UnitType.TURRET, 0f, 0, 1f, 4, 200f, 0, 0, 0f, 0f, 0f, 0f, false, 0f));
        return definitions;
    }
}
