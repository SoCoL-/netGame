package ru.socol.supreme.assets;

import com.badlogic.gdx.graphics.Color;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Палитра из assets/colors.json: в файле есть все цвета GameColor, разбор форматов и ошибка на пропуск. */
class PaletteTest {

    @Test
    void colorsJsonHasEveryGameColor() throws IOException {
        // Тесты core запускаются из папки core/ — assets рядом.
        String json = new String(Files.readAllBytes(Paths.get("../assets/colors.json")), StandardCharsets.UTF_8);
        Palette palette = PaletteLoader.parse(json);
        for (GameColor color : GameColor.values()) {
            assertNotNull(palette.get(color), color.key);
        }
        assertEquals(palette.get(GameColor.PLAYER_0), palette.player(0));
        assertEquals(palette.get(GameColor.PLAYER_1), palette.player(1));
    }

    @Test
    void parsesRgbAndRgbaAndSkipsComments() {
        StringBuilder json = new StringBuilder("{\"_comment\": \"не цвет\"");
        for (GameColor color : GameColor.values()) {
            json.append(", \"").append(color.key).append("\": \"").append(color == GameColor.FOG ? "1F1F1FE0" : "FF8000").append('"');
        }
        Palette palette = PaletteLoader.parse(json.append('}').toString());
        assertEquals(Color.valueOf("FF8000"), palette.get(GameColor.UI_TEXT));
        assertEquals(0xE0 / 255f, palette.get(GameColor.FOG).a, 0.001f);
    }

    @Test
    void missingColorIsReportedByName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PaletteLoader.parse("{\"ui.text\": \"FFFFFF\"}"));
        assertTrue(error.getMessage().contains("player.0"), error.getMessage());
    }
}
