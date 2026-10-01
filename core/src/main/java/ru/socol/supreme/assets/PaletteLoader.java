package ru.socol.supreme.assets;

import com.badlogic.gdx.assets.AssetDescriptor;
import com.badlogic.gdx.assets.AssetLoaderParameters;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.SynchronousAssetLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Загрузчик Palette для AssetManager: json-объект "ключ": "RRGGBB" или
 * "RRGGBBAA" (как Color.valueOf). Ключи с "_" в начале — комментарии, их
 * пропускаем.
 */
public class PaletteLoader extends SynchronousAssetLoader<Palette, PaletteLoader.Parameters> {

    public PaletteLoader(FileHandleResolver resolver) {
        super(resolver);
    }

    @Override
    public Palette load(AssetManager manager, String fileName, FileHandle file, Parameters parameter) {
        return parse(resolve(fileName).readString("UTF-8"));
    }

    /** Разбор содержимого colors.json — отдельно от файлов, чтобы проверять в тестах без Gdx.files. */
    static Palette parse(String json) {
        JsonValue root = new JsonReader().parse(json);
        Map<String, Color> values = new HashMap<>();
        for (JsonValue entry = root.child; entry != null; entry = entry.next) {
            if (!entry.name.startsWith("_")) {
                values.put(entry.name, Color.valueOf(entry.asString()));
            }
        }
        return new Palette(values);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Array<AssetDescriptor> getDependencies(String fileName, FileHandle file, Parameters parameter) {
        return null;
    }

    public static class Parameters extends AssetLoaderParameters<Palette> {
    }
}
