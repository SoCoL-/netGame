package ru.socol.supreme.assets;

/**
 * Все цвета клиента — имена, под которыми они лежат в assets/colors.json
 * (см. Palette). Код обращается к цвету только через это перечисление, а
 * сами значения меняются в json без пересборки. Палитра при загрузке
 * проверяет, что в файле есть каждый цвет из этого списка.
 */
public enum GameColor {
    // Игроки
    PLAYER_0("player.0"),
    PLAYER_1("player.1"),

    // Интерфейс
    UI_BACKGROUND("ui.background"),
    UI_TEXT("ui.text"),
    UI_TEXT_DIM("ui.text-dim"),
    UI_TEXT_DISABLED("ui.text-disabled"),
    UI_TEXT_DARK("ui.text-dark"),
    UI_ACCENT("ui.accent"),
    UI_POSITIVE("ui.positive"),
    UI_NEGATIVE("ui.negative"),
    UI_PANEL("ui.panel"),
    UI_BUTTON("ui.button"),
    UI_BUTTON_LIGHT("ui.button-light"),
    UI_BUTTON_DISABLED("ui.button-disabled"),
    UI_BUTTON_CONFIRM("ui.button-confirm"),
    UI_BUTTON_CANCEL("ui.button-cancel"),
    UI_BUTTON_NEUTRAL("ui.button-neutral"),
    UI_BUTTON_DEMOLISH("ui.button-demolish"),
    UI_PATROL_BUTTON("ui.patrol-button"),
    UI_PATROL_BUTTON_ACTIVE("ui.patrol-button-active"),
    UI_FPS("ui.fps"),

    // Иконки стратегической карты: пиктограмма поверх подложки в цвете игрока
    ICON_GLYPH("icon.glyph"),

    // Полоски и индикаторы
    BAR_BACKGROUND("bar.background"),
    BAR_HEALTH_HIGH("bar.health-high"),
    BAR_HEALTH_LOW("bar.health-low"),
    BAR_CONSTRUCTION("bar.construction"),
    BAR_PRODUCTION("bar.production"),
    BAR_RELOAD("bar.reload"),
    BAR_RELOAD_READY("bar.reload-ready"),

    // Мир: карта, туман, отладка
    FOG("world.fog"),
    MAP_BORDER("world.map-border"),
    DRAG_BOX("world.drag-box"),
    IRON_DEPOSIT("world.iron-deposit"),
    CRATER("world.crater"),
    CRATER_CORE("world.crater-core"),
    DEBUG_GRASS("debug.grass"),
    DEBUG_WATER("debug.water"),
    DEBUG_ROCK("debug.rock"),
    DEBUG_PATH("debug.path"),

    // Приказы и маршруты
    ORDER_MOVE("order.move"),
    ORDER_ATTACK("order.attack"),
    ORDER_BUILD("order.build"),
    ORDER_REPAIR("order.repair"),
    ORDER_COLLECT("order.collect"),
    PATROL_ROUTE("order.patrol"),
    PATROL_ROUTE_PREVIEW("order.patrol-preview"),
    RALLY_POINT("order.rally-point"),
    GHOST_VALID("order.ghost-valid"),
    GHOST_INVALID("order.ghost-invalid"),

    // Эффекты
    BEAM_OUTER("effect.beam-outer"),
    BEAM_MID("effect.beam-mid"),
    BEAM_CORE("effect.beam-core"),
    ARROW("effect.arrow"),
    ARTILLERY_SHELL("effect.artillery-shell"),
    ARTILLERY_EXPLOSION("effect.artillery-explosion"),
    ARTILLERY_RANGE("effect.artillery-range"),

    // Юниты и здания
    SELECTION_RING("entity.selection-ring"),
    TURRET("entity.turret"),
    BUILDER_MARKER("entity.builder-marker"),
    SCOUT_MARKER("entity.scout-marker"),
    ATTACK_AIRCRAFT_MARKER("entity.attack-aircraft-marker"),
    HQ_STAR("entity.hq-star"),
    ARCHER_ROOF("entity.archer-roof"),
    IRON_MINE_MARKER("entity.iron-mine-marker"),
    POWER_PLANT_MARKER("entity.power-plant-marker"),
    AIRCRAFT_FACTORY_MARKER("entity.aircraft-factory-marker"),
    UNDER_CONSTRUCTION("entity.under-construction"),
    WRECK("entity.wreck"),
    WRECK_UNDERWATER("entity.wreck-underwater");

    /** Ключ в colors.json. */
    public final String key;

    GameColor(String key) {
        this.key = key;
    }
}
