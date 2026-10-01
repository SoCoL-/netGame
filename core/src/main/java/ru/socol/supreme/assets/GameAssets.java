package ru.socol.supreme.assets;

import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.TextureLoader;
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGeneratorLoader;
import com.badlogic.gdx.graphics.g2d.freetype.FreetypeFontLoader;
import com.badlogic.gdx.utils.Disposable;
import ru.socol.supreme.VegetationType;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.UnitType;

import java.util.ArrayList;
import java.util.List;

/**
 * Все ассеты клиента — шрифты, текстуры и палитра цветов — в одном
 * AssetManager. Загружаются один раз при старте (Main.create), живут до
 * выхода из игры и освобождаются только здесь, в dispose(): экраны и
 * системы ассеты лишь берут и сами их не освобождают.
 *
 * Шрифты генерируются FreeType из assets/fonts/Exo2-Medium.ttf (SIL OFL,
 * лицензия рядом, в OFL-Exo2.txt), с кириллицей — по одному BitmapFont на
 * каждый GameFont. Один шрифт общий для всех, кто его берёт, поэтому цвет
 * задаётся перед каждой отрисовкой (и до GlyphLayout.setText — раскладка
 * запоминает цвет шрифта на момент setText).
 */
public final class GameAssets implements Disposable {

    /** Размеры шрифтов интерфейса (высота em в логических пикселях HUD 1024x768). */
    public enum GameFont {
        /** Отсчёт перед стартом матча. */
        COUNTDOWN(60),
        /** "ПОБЕДА"/"Подключение..." по центру экрана. */
        HEADLINE(45),
        /** Заголовки экранов лобби. */
        TITLE(33),
        /** Текст комнаты лобби. */
        ROOM(21),
        /** Текст браузера лобби. */
        BODY(20),
        /** Панели игры и счётчик FPS. */
        UI(19),
        /** Подписи кнопок — русские названия длиннее английских. */
        BUTTON(16);

        final int size;

        GameFont(int size) {
            this.size = size;
        }

        /** Имя ассета: у FreetypeFontLoader это не файл, а ключ; сам шрифт — FONT_FILE. */
        String assetName() {
            return "font-" + name().toLowerCase() + ".ttf";
        }
    }

    private static final String FONT_FILE = "fonts/Exo2-Medium.ttf";
    private static final String PALETTE_FILE = "colors.json";

    /** Латиница, цифры и знаки (набор FreeType по умолчанию) плюс русский алфавит и типографские знаки. */
    private static final String CHARACTERS = FreeTypeFontGenerator.DEFAULT_CHARS
            + "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ"
            + "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
            + "№«»—–…°·";

    /** Спрайты юнитов: для каждого имени — корпус, башня и их маски командного цвета (см. LayeredSprite). */
    private static final String[] UNIT_SPRITES = {"archer", "anti-air", "artillery"};
    private static final String[] UNIT_LAYERS = {"hull", "hull-team", "turret", "turret-team"};

    /** Бесшовные текстуры земли и воды (см. TerrainRenderer) — повторяются по всей карте. */
    public static final String TERRAIN_GRASS = "terrain/grass.jpg";
    public static final String TERRAIN_DIRT = "terrain/dirt.jpg";
    public static final String TERRAIN_WATER_SHALLOW = "terrain/water-shallow.jpg";
    public static final String TERRAIN_WATER_DEEP = "terrain/water-deep.jpg";
    private static final String[] TERRAIN = {TERRAIN_GRASS, TERRAIN_DIRT, TERRAIN_WATER_SHALLOW, TERRAIN_WATER_DEEP};

    /**
     * Стратегический значок: заливка фона (team — красится цветом игрока)
     * и рамка со знаком роли в двух вариантах — тёмном и светлом, под
     * светлый и тёмный цвет игрока (см. overlayFor). Значки —
     * art/strategic-icons, в игру их готовит tools/import_strategic_icons.py.
     */
    public static final class StrategicIcon {
        public final Texture team;
        public final Texture dark;
        public final Texture light;

        StrategicIcon(Texture team, Texture dark, Texture light) {
            this.team = team;
            this.dark = dark;
            this.light = light;
        }

        /** Знак с большим контрастом к цвету игрока: на светлом фоне — тёмный, на тёмном — светлый. */
        public Texture overlayFor(Color teamColor) {
            float luminance = 0.2126f * teamColor.r + 0.7152f * teamColor.g + 0.0722f * teamColor.b;
            return luminance > 0.5f ? dark : light;
        }
    }

    private static final String[] ICON_LAYERS = {"team", "dark", "light"};

    /** Месторождение железа — готовая цветная картинка (tools/make_icons.py), не красится. */
    public static final String ICON_IRON_DEPOSIT = "icons/iron-deposit.png";

    private final AssetManager manager;

    public GameAssets() {
        FileHandleResolver resolver = new InternalFileHandleResolver();
        manager = new AssetManager(resolver);
        manager.setLoader(FreeTypeFontGenerator.class, new FreeTypeFontGeneratorLoader(resolver));
        manager.setLoader(BitmapFont.class, ".ttf", new FreetypeFontLoader(resolver));
        manager.setLoader(Palette.class, new PaletteLoader(resolver));
    }

    /** Ставит в очередь всё, что нужно игре, и дожидается загрузки. */
    public void loadAll() {
        manager.load(PALETTE_FILE, Palette.class);

        for (GameFont font : GameFont.values()) {
            FreetypeFontLoader.FreeTypeFontLoaderParameter parameter = new FreetypeFontLoader.FreeTypeFontLoaderParameter();
            parameter.fontFileName = FONT_FILE;
            parameter.fontParameters.size = font.size;
            parameter.fontParameters.characters = CHARACTERS;
            // Linear — HUD-камера растягивает интерфейс под размер окна, без
            // сглаживания буквы при масштабировании выглядели бы рваными.
            parameter.fontParameters.minFilter = Texture.TextureFilter.Linear;
            parameter.fontParameters.magFilter = Texture.TextureFilter.Linear;
            manager.load(font.assetName(), BitmapFont.class, parameter);
        }

        for (String name : UNIT_SPRITES) {
            for (String layer : UNIT_LAYERS) {
                // Без повтора: спрайт рисуется целиком, края не должны "заворачиваться".
                manager.load(unitTexturePath(name, layer), Texture.class, mipmapped(Texture.TextureWrap.ClampToEdge));
            }
        }
        for (VegetationType type : VegetationType.values()) {
            manager.load(type.texturePath, Texture.class, mipmapped(Texture.TextureWrap.ClampToEdge));
        }
        for (String path : iconPaths()) {
            manager.load(path, Texture.class, mipmapped(Texture.TextureWrap.ClampToEdge));
        }
        for (String path : TERRAIN) {
            // Повтор по всей карте — требует размер степени двойки (1024).
            manager.load(path, Texture.class, mipmapped(Texture.TextureWrap.Repeat));
        }

        manager.finishLoading();
    }

    /**
     * С mip-уровнями: при обычном масштабе камеры 128-пиксельный спрайт
     * ужимается до ~30 px, а в стратегическом виде ещё сильнее — без
     * mipmap при таком уменьшении мелкие детали рябят.
     */
    private static TextureLoader.TextureParameter mipmapped(Texture.TextureWrap wrap) {
        TextureLoader.TextureParameter parameter = new TextureLoader.TextureParameter();
        parameter.genMipMaps = true;
        parameter.minFilter = Texture.TextureFilter.MipMapLinearLinear;
        parameter.magFilter = Texture.TextureFilter.Linear;
        parameter.wrapU = wrap;
        parameter.wrapV = wrap;
        return parameter;
    }

    /** Все иконки: месторождение и по три слоя значка на каждое здание (кроме обломков) и юнит (кроме турели — она здание). */
    private static List<String> iconPaths() {
        List<String> paths = new ArrayList<>();
        paths.add(ICON_IRON_DEPOSIT);
        List<String> names = new ArrayList<>();
        for (BuildingType type : BuildingType.values()) {
            if (type != BuildingType.WRECK) {
                names.add(type.name());
            }
        }
        for (UnitType type : UnitType.values()) {
            if (type != UnitType.TURRET) {
                names.add(type.name());
            }
        }
        for (String name : names) {
            for (String layer : ICON_LAYERS) {
                paths.add(iconPath(name, layer));
            }
        }
        return paths;
    }

    /** HOME, "team" -> icons/home-team.png; ARCHER_BARRACKS, "dark" -> icons/archer-barracks-dark.png. */
    private static String iconPath(String enumName, String layer) {
        return "icons/" + enumName.toLowerCase().replace('_', '-') + "-" + layer + ".png";
    }

    private StrategicIcon strategicIcon(String enumName) {
        return new StrategicIcon(texture(iconPath(enumName, "team")), texture(iconPath(enumName, "dark")),
                texture(iconPath(enumName, "light")));
    }

    /** Значок здания для стратегической карты; null — у обломков его нет. */
    public StrategicIcon icon(BuildingType type) {
        return type == BuildingType.WRECK ? null : strategicIcon(type.name());
    }

    /** Значок юнита для стратегической карты (TURRET — это здание, см. icon(BuildingType)). */
    public StrategicIcon icon(UnitType type) {
        return strategicIcon((type == UnitType.TURRET ? BuildingType.TURRET : type).name());
    }

    public static String unitTexturePath(String name, String layer) {
        return "units/" + name + "-" + layer + ".png";
    }

    public BitmapFont font(GameFont font) {
        return manager.get(font.assetName(), BitmapFont.class);
    }

    public Texture texture(String path) {
        return manager.get(path, Texture.class);
    }

    public Palette palette() {
        return manager.get(PALETTE_FILE, Palette.class);
    }

    @Override
    public void dispose() {
        manager.dispose();
    }
}
