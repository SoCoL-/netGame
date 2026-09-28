package ru.socol.supreme;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;

/**
 * Все шрифты интерфейса — из assets/fonts/Exo2-Medium.ttf (SIL OFL, лицензия
 * рядом, в OFL-Exo2.txt) через FreeType, с кириллицей. Раньше везде стоял
 * new BitmapFont() — встроенный в libGDX растровый шрифт, в котором есть
 * только латиница, из-за чего русский текст рисовался пустыми
 * прямоугольниками.
 *
 * Каждый вызов create() генерирует новый BitmapFont нужного размера — его
 * владелец (экран) сам вызывает dispose(), как и раньше со встроенным
 * шрифтом. Шрифтов в игре немного, и создаются они один раз при создании
 * экрана, так что отдельный кэш не нужен.
 */
public final class Fonts {

    private static final String FONT_PATH = "fonts/Exo2-Medium.ttf";

    /** Латиница, цифры и знаки (набор FreeType по умолчанию) плюс русский алфавит и типографские знаки. */
    private static final String CHARACTERS = FreeTypeFontGenerator.DEFAULT_CHARS
            + "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
            + "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
            + "№«»—–…°·";

    private Fonts() {
    }

    /** Шрифт с высотой em в size пикселей (при HUD 1024x768 — логических пикселях HUD). */
    public static BitmapFont create(int size) {
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(Gdx.files.internal(FONT_PATH));
        try {
            FreeTypeFontGenerator.FreeTypeFontParameter parameter = new FreeTypeFontGenerator.FreeTypeFontParameter();
            parameter.size = size;
            parameter.characters = CHARACTERS;
            // Linear — HUD-камера растягивает интерфейс под размер окна, без
            // сглаживания буквы при масштабировании выглядели бы рваными.
            parameter.minFilter = Texture.TextureFilter.Linear;
            parameter.magFilter = Texture.TextureFilter.Linear;
            return generator.generateFont(parameter);
        } finally {
            generator.dispose();
        }
    }
}
