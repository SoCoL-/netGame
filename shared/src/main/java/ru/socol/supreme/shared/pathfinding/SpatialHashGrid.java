package ru.socol.supreme.shared.pathfinding;

import com.badlogic.ashley.core.Entity;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.LongMap;

/**
 * Сетка пространственного хеширования для быстрого поиска соседей.
 *
 * Заменяет перебор «каждый с каждым» (O(n²)) на «каждый со своими
 * соседями по ячейке» (O(n × k), где k — среднее число юнитов в 3×3 ячейках).
 *
 * Принцип:
 * 1. Карта делится на квадратные ячейки размера cellSize.
 * 2. Каждая сущность добавляется в ячейку по её координатам.
 * 3. При поиске соседей проверяются только ячейки, перекрывающиеся
 *    с кругом запроса (centre + radius) — обычно 3×3 = 9 ячеек.
 *
 * Важно: сетка перестраивается (clear + insert) в начале каждого
 * тика системы, потому что юниты двигаются. Это дёшево — O(n).
 *
 * Потокобезопасность: не потокобезопасна. Используется только
 * в серверном потоке симуляции, внутри synchronized блока runLoop.
 */
public class SpatialHashGrid {
    private final float cellSize;
    private final LongMap<Array<Entity>> grid = new LongMap<>();

    /**
     * Переиспользуемый массив результатов запроса.
     * Безопасен, потому что processEntity полностью потребляет
     * результат до того, как следующий entity вызовет query снова.
     */
    private final Array<Entity> queryResult = new Array<>(Entity.class);

    public SpatialHashGrid(float cellSize) {
        this.cellSize = cellSize;
    }

    /** Очистить сетку. Вызывается в начале каждого тика перед заполнением. */
    public void clear() {
        grid.clear();
    }

    /**
     * Добавить сущность как точку (для юнитов).
     * Юнит попадает ровно в одну ячейку.
     */
    public void insert(Entity entity, float x, float y) {
        long key = packKey(toCell(x), toCell(y));
        Array<Entity> bucket = grid.get(key);
        if (bucket == null) {
            bucket = new Array<>(true, 8, Entity.class);
            grid.put(key, bucket);
        }
        bucket.add(entity);
    }

    /**
     * Добавить сущность как прямоугольник (для зданий).
     * Здание попадает во ВСЕ ячейки, которые оно перекрывает —
     * это нужно, чтобы юнит у края здания нашёл его в соседней ячейке.
     *
     * Побочный эффект: одно и то же здание может оказаться в результате
     * запроса несколько раз (из разных ячеек). Для CollisionSystem это
     * безопасно — pushOutOfRect идемпотентен (повторный вызов ничего
     * не делает, если юнит уже снаружи).
     */
    public void insertRect(Entity entity, float minX, float minY, float maxX, float maxY) {
        int minCX = toCell(minX);
        int maxCX = toCell(maxX);
        int minCY = toCell(minY);
        int maxCY = toCell(maxY);
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cy = minCY; cy <= maxCY; cy++) {
                long key = packKey(cx, cy);
                Array<Entity> bucket = grid.get(key);
                if (bucket == null) {
                    bucket = new Array<>(true, 4, Entity.class);
                    grid.put(key, bucket);
                }
                bucket.add(entity);
            }
        }
    }

    /**
     * Найти все сущности в ячейках, перекрывающихся с кругом (x, y, radius).
     *
     * Возвращает переиспользуемый массив — не сохраняйте ссылку,
     * он будет перезаписан при следующем вызове query.
     *
     * Внимание: результат может содержать дубликаты (если сущность
     * добавлена через insertRect и попала в несколько ячеек).
     * Вызывающий код должен это учитывать.
     */
    public Array<Entity> query(float x, float y, float radius) {
        queryResult.clear();

        int minCX = toCell(x - radius);
        int maxCX = toCell(x + radius);
        int minCY = toCell(y - radius);
        int maxCY = toCell(y + radius);

        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cy = minCY; cy <= maxCY; cy++) {
                Array<Entity> bucket = grid.get(packKey(cx, cy));
                if (bucket != null) {
                    queryResult.addAll(bucket);
                }
            }
        }
        return queryResult;
    }

    /** Перевести мировую координату в индекс ячейки. */
    private int toCell(float v) {
        return (int) Math.floor(v / cellSize);
    }

    /**
     * Упаковать два int-индекса ячейки в один long-ключ для LongMap.
     * cx — в старшие 32 бита, cy — в младшие. Работает для отрицательных
     * индексов (когда юнит у края карты и радиус запроса уходит в минус).
     */
    private static long packKey(int cx, int cy) {
        return ((long) cx << 32) | ((long) cy & 0xFFFFFFFFL);
    }
}
