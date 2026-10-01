package ru.socol.supreme;

/**
 * Виды растительности — декор карты (assets/vegetation, готовит
 * tools/bake_vegetation.py). worldSize — сторона квадрата текстуры в
 * мировых единицах (растение вписано в него по бо́льшей стороне; для
 * сравнения: диаметр наземного юнита — 20, длина танка — около 30).
 * Стартовые размеры из описания набора (кусты 18–26, деревья 52–64)
 * в игре терялись на траве — увеличены примерно в 1,6–1,8 раза. Кусты лежат на земле под
 * юнитами, кроны деревьев — над ними (см. VegetationRenderer).
 */
public enum VegetationType {
    BUSH_COMPACT("bush-01-compact", 32f, false),
    BUSH_SPREADING("bush-02-spreading", 46f, false),
    BUSH_DRY("bush-03-dry", 40f, false),
    TREE_ROUND("tree-01-round", 104f, true),
    TREE_AIRY("tree-02-airy", 94f, true),
    TREE_CONIFER("tree-03-conifer", 84f, true);

    public final String texturePath;
    public final float worldSize;
    public final boolean tree;

    VegetationType(String name, float worldSize, boolean tree) {
        this.texturePath = "vegetation/" + name + ".png";
        this.worldSize = worldSize;
        this.tree = tree;
    }
}
