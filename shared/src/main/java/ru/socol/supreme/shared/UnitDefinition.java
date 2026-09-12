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

    /** Пока нигде не читается (см. GameServer.handleQueueUnit) — задел на будущее, когда появятся ресурсы игрока. */
    public int cost;

    public UnitDefinition() {
        // требуется Json для десериализации
    }

    public UnitDefinition(UnitType type, float speed, int health, float fireRate, int damage, float attackRadius, int cost) {
        this.type = type;
        this.speed = speed;
        this.health = health;
        this.fireRate = fireRate;
        this.damage = damage;
        this.attackRadius = attackRadius;
        this.cost = cost;
    }
}
