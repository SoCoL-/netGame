package ru.socol.supreme.shared;

import com.badlogic.ashley.core.Entity;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PositionComponent;

/**
 * Проверка "можно ли поставить здание в эту точку" для зданий, которые НЕ
 * привязаны к фиксированной точке на карте (в отличие от шахты железа,
 * которая может встать только на месторождение — см.
 * GameServer.isDepositOccupied, у неё своя, гораздо более простая
 * проверка, зазор вокруг производящих зданий, описанный ниже, её не
 * касается — на нынешней карте все месторождения и так стоят далеко от
 * дома). Сейчас это казарма стрелков, электростанция и оба хранилища —
 * все четыре строит сам игрок в произвольном месте из меню постройки.
 *
 * Общий метод, а не два разных (клиент/сервер): оба принимают один и тот
 * же Iterable&lt;Entity&gt; — на клиенте это engine.getEntities(), на
 * сервере unitsById.values() — и одинаково перебирают его, так что
 * результат гарантированно совпадает по обе стороны сети. Клиент вызывает
 * это каждый кадр для цвета превью (GameScreen), сервер — один раз при
 * обработке PlaceBuildingRequest, авторитетно.
 */
public final class BuildingPlacement {

    private BuildingPlacement() {
    }

    /**
     * Помещается ли здание данного типа центром в (x, y) — в границах
     * карты, не на воде, не пересекая юнитов/другие здания, и с зазором
     * вокруг ЛЮБОГО здания, производящего юнитов (своего или чужого —
     * проверка чисто геометрическая, без учёта владельца, как и остальные
     * условия этой функции). Зазор — clearanceRadiusFor того же
     * производящего здания, той же величины, на которой у него
     * появляется готовый юнит (BuildingDefinitions
     * .productionSpawnDistanceFor): без него соседняя постройка могла бы
     * перекрыть юниту точку появления. Действует в обе стороны — и когда
     * рядом уже стоит производящее здание, и когда само строящееся
     * здание производит юнитов (тогда зазор нужен вокруг НЕГО, чтобы
     * ЕГО собственное место появления юнита осталось свободным).
     */
    public static boolean canPlaceBuilding(BuildingType type, Iterable<Entity> entities, float x, float y) {
        float halfWidth = BuildingDefinitions.halfWidthFor(type);
        float halfHeight = BuildingDefinitions.halfHeightFor(type);
        float minX = x - halfWidth;
        float minY = y - halfHeight;
        float maxX = x + halfWidth;
        float maxY = y + halfHeight;

        if (minX < 0f || minY < 0f || maxX > GameConstants.MAP_WIDTH || maxY > GameConstants.MAP_HEIGHT) {
            return false; // вылезает за границы карты
        }

        if (rectsOverlap(minX, minY, maxX, maxY,
                GameConstants.WATER_MIN_X, GameConstants.WATER_MIN_Y, GameConstants.WATER_MAX_X, GameConstants.WATER_MAX_Y)) {
            return false;
        }

        float ownClearance = clearanceRadiusFor(type);

        for (Entity entity : entities) {
            PositionComponent position = entity.getComponent(PositionComponent.class);
            if (position == null) {
                continue;
            }

            BuildingComponent buildingMarker = entity.getComponent(BuildingComponent.class);
            if (buildingMarker != null) {
                float otherHalfWidth = BuildingSizes.halfWidth(entity);
                float otherHalfHeight = BuildingSizes.halfHeight(entity);
                if (rectsOverlap(minX, minY, maxX, maxY,
                        position.position.x - otherHalfWidth, position.position.y - otherHalfHeight,
                        position.position.x + otherHalfWidth, position.position.y + otherHalfHeight)) {
                    return false;
                }

                // Зазор вокруг производящего здания — своего (ownClearance,
                // если само строящееся здание производит юнитов) или
                // соседнего (clearanceRadiusFor(buildingMarker.type)), какой
                // бы из них ни требовал большего расстояния. У обоих
                // непроизводящих зданий это 0 — условие не сработает вовсе, как
                // и раньше, когда этой проверки не было.
                float requiredClearance = Math.max(ownClearance, clearanceRadiusFor(buildingMarker.type));
                if (requiredClearance > 0f
                        && Vector2.dst(x, y, position.position.x, position.position.y) < requiredClearance) {
                    return false;
                }
            } else if (entity.getComponent(DirectionComponent.class) != null) {
                // Юнит — не прямоугольник, а круг радиуса UNIT_RADIUS; ближайшая
                // точка нашего прямоугольника до его центра — тот же приём, что
                // и в CollisionSystem.pushOutOfRect, только без выталкивания.
                float closestX = MathUtils.clamp(position.position.x, minX, maxX);
                float closestY = MathUtils.clamp(position.position.y, minY, maxY);
                float dx = position.position.x - closestX;
                float dy = position.position.y - closestY;
                if (dx * dx + dy * dy < GameConstants.UNIT_RADIUS * GameConstants.UNIT_RADIUS) {
                    return false;
                }
            }
        }

        return true;
    }

    /** 0, если это здание не производит юнитов — иначе рекомендуемый зазор вокруг него, равный расстоянию до точки появления юнита (BuildingDefinitions.productionSpawnDistanceFor). */
    private static float clearanceRadiusFor(BuildingType type) {
        return BuildingDefinitions.producesUnitTypesFor(type).length > 0
                ? BuildingDefinitions.productionSpawnDistanceFor(type) : 0f;
    }

    private static boolean rectsOverlap(float minX1, float minY1, float maxX1, float maxY1,
                                         float minX2, float minY2, float maxX2, float maxY2) {
        return minX1 < maxX2 && maxX1 > minX2 && minY1 < maxY2 && maxY1 > minY2;
    }
}
