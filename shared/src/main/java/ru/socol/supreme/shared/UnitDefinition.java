package ru.socol.supreme.shared;

/**
 * Данные одного типа юнита — как они лежат в units.json. Простой POJO с
 * публичными полями специально: com.badlogic.gdx.utils.Json (см.
 * UnitDefinitions) десериализует такие классы через рефлексию по имени
 * поля без какой-либо ручной настройки, включая enum type — строка
 * "WARRIOR"/"ARCHER" в файле превращается в UnitType сама.
 */
public class UnitDefinition {

    public UnitType type;

    /** Единиц мира в секунду. */
    public float speed;

    /** Максимальный (и стартовый) запас здоровья. */
    public int health;

    /** Выстрелов в секунду. */
    public float fireRate;

    /** Урон за один выстрел. */
    public int damage;

    /**
     * Дальность атаки — на этом расстоянии юнит останавливается и стреляет
     * вместо того, чтобы идти ближе. Тем же числом AggroSystem пользуется
     * как радиусом авто-агрессии (см. её javadoc) — отдельного радиуса
     * агрессии в игре больше нет, это одна и та же величина.
     */
    public float attackRadius;

    /**
     * Сколько железа/электричества стоит один такой юнит — списывается не
     * разом, а равномерно за время постройки (UNIT_BUILD_TIME), см.
     * ProductionSystem: если на очередной тик не хватает ресурсов, прогресс
     * просто не растёт, как и при нехватке места под юнита (MAX_TOTAL_UNITS).
     * 0 — юнит ничего не стоит (сейчас так у воина).
     */
    public int ironCost;
    public int electricityCost;

    /**
     * Актуально только для строителя — на каком расстоянии от здания он
     * может его строить (см. BuildSystem — та же идея, что и attackRadius
     * у боевых юнитов: точка подхода на этом расстоянии от цели, не
     * вплотную). 0 у остальных типов — не используется.
     */
    public float buildRadius;

    public UnitDefinition() {
        // требуется Json для десериализации
    }

    public UnitDefinition(UnitType type, float speed, int health, float fireRate, int damage, float attackRadius,
                           int ironCost, int electricityCost, float buildRadius) {
        this.type = type;
        this.speed = speed;
        this.health = health;
        this.fireRate = fireRate;
        this.damage = damage;
        this.attackRadius = attackRadius;
        this.ironCost = ironCost;
        this.electricityCost = electricityCost;
        this.buildRadius = buildRadius;
    }
}
