package ru.socol.supreme.assets;

import com.badlogic.gdx.graphics.Color;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Цвета из assets/colors.json — ассет AssetManager (грузит PaletteLoader),
 * такой же, как шрифты и текстуры. Возвращаемые Color общие: их нельзя
 * менять (set/mul и т.п.) — только читать.
 */
public final class Palette {

    private final Map<GameColor, Color> colors;
    private final Color[] players;

    /** values — ключ из colors.json -> цвет; в нём должен быть каждый GameColor. */
    Palette(Map<String, Color> values) {
        colors = new EnumMap<>(GameColor.class);
        List<String> missing = new ArrayList<>();
        for (GameColor color : GameColor.values()) {
            Color value = values.get(color.key);
            if (value == null) {
                missing.add(color.key);
            } else {
                colors.put(color, value);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("в colors.json нет цветов: " + missing);
        }
        players = new Color[]{colors.get(GameColor.PLAYER_0), colors.get(GameColor.PLAYER_1)};
    }

    public Color get(GameColor color) {
        return colors.get(color);
    }

    /** Цвет игрока по его playerId (0 или 1). */
    public Color player(int playerId) {
        return players[playerId % players.length];
    }
}
