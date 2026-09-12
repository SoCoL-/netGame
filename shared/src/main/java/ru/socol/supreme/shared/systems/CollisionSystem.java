package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.pathfinding.SpatialHashGrid;

import java.util.Map;

/**
 * Не даёт юнитам скапливаться в одной точке, залезать внутрь зданий (или
 * воды — на случай, если взаимное расталкивание юнитов кого-то туда
 * протолкнёт) и выходить за границы карты. Простая локальная коррекция
 * позиции постфактум — юниты "мягко" отталкиваются друг от друга и
 * "жёстко" выталкиваются из зданий/воды/за карту, — а не полноценная
 * физика с массой и импульсом. Этого достаточно, чтобы юниты не стояли
 * друг в друге, не проходили сквозь стены и не уезжали за пределы карты
 * толпой у края.
 *
 * Поиск соседей и зданий идёт через SpatialHashGrid, а не перебором всех
 * unitsById (O(n) на юнита вместо O(n²) на всех): сетка перестраивается
 * заново в начале каждого тика (юниты подвинулись за прошлый тик), юниты
 * вставляются как точки, здания — как прямоугольники (могут попасть в
 * несколько соседних ячеек сразу, поэтому одно и то же здание иногда
 * встречается в результате запроса больше одного раза — это безопасно,
 * pushOutOfRect идемпотентен: повторный вызов ничего не делает, если юнит
 * уже снаружи). Радиус запроса — GameConstants.maxBuildingInteractionRadius()
 * — с запасом покрывает и столкновение юнит-юнит (2×UNIT_RADIUS), и
 * выталкивание из САМОГО крупного из существующих типов здания.
 *
 * Приоритет 20 — после MovementSystem (10): корректирует уже случившееся
 * за этот тик перемещение, а не решает, куда юнит хочет идти (этим
 * занимаются AggroSystem/CombatSystem/Pathfinding, отдельно от коллизий).
 *
 * Пасфайндинг (см. Pathfinding) и эта система дополняют друг друга:
 * Pathfinding решает МАКРО-задачу (обойти воду/здание по дуге на большом
 * расстоянии), эта система — МИКРО-задачу (не дать юниту в моменте влезть
 * в стену или в соседа, в том числе из-за огрубления сетки поиска пути или
 * скученности от других юнитов). Одного Pathfinding недостаточно: без
 * этой системы юнит, которого толкнули соседи, мог бы на тик оказаться
 * внутри здания и там застрять.
 *
 * Работает только на сервере, как и остальные gameplay-системы.
 */
public class CollisionSystem extends IteratingSystem {

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<BuildingComponent> BUILDING =
            ComponentMapper.getFor(BuildingComponent.class);

    /** Тот же реестр unitId -> Entity, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, Entity> unitsById;

    /** Переиспользуемый вектор для направления отталкивания — чтобы не аллоцировать новый на каждую пару каждый тик. */
    private final Vector2 pushDirection = new Vector2();

    private final SpatialHashGrid grid;

    /** Тот же радиус, что и размер ячейки grid (см. GameServer) — запрос обычно укладывается в 3×3 ячейки. */
    private final float queryRadius = GameConstants.maxBuildingInteractionRadius();

    public CollisionSystem(Map<Integer, Entity> unitsById, SpatialHashGrid grid) {
        super(Family.all(PositionComponent.class, DirectionComponent.class).get(), 20);
        this.unitsById = unitsById;
        this.grid = grid;
    }

    /**
     * Перестраиваем spatial hash в начале каждого тика — MovementSystem
     * (приоритет 10) уже сдвинул юнитов, старые позиции неактуальны.
     * Здания вставляются как прямоугольники (их реальная форма зависит от
     * типа — см. GameConstants.buildingHalfWidthFor/HeightFor), юниты —
     * как точки.
     */
    @Override
    public void update(float deltaTime) {
        grid.clear();
        for (Entity other : unitsById.values()) {
            PositionComponent position = POSITION.get(other);
            if (position == null) {
                continue;
            }

            if (BUILDING.has(other)) {
                ProductionComponent production = other.getComponent(ProductionComponent.class);
                UnitType producesType = production != null ? production.producesUnitType : UnitType.WARRIOR;
                float halfWidth = GameConstants.buildingHalfWidthFor(producesType);
                float halfHeight = GameConstants.buildingHalfHeightFor(producesType);
                grid.insertRect(other,
                        position.position.x - halfWidth, position.position.y - halfHeight,
                        position.position.x + halfWidth, position.position.y + halfHeight);
            } else {
                grid.insert(other, position.position.x, position.position.y);
            }
        }
        super.update(deltaTime);
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        PositionComponent position = POSITION.get(entity);

        Array<Entity> nearby = grid.query(position.position.x, position.position.y, queryRadius);

        for (Entity other : nearby) {
            if (other == entity) {
                continue;
            }

            if (BUILDING.has(other)) {
                PositionComponent buildingPosition = POSITION.get(other);
                ProductionComponent buildingProduction = other.getComponent(ProductionComponent.class);
                UnitType producesType = buildingProduction != null ? buildingProduction.producesUnitType : UnitType.WARRIOR;
                float halfWidth = GameConstants.buildingHalfWidthFor(producesType);
                float halfHeight = GameConstants.buildingHalfHeightFor(producesType);
                pushOutOfRect(position,
                        buildingPosition.position.x - halfWidth, buildingPosition.position.y - halfHeight,
                        buildingPosition.position.x + halfWidth, buildingPosition.position.y + halfHeight);
            } else if (other.getComponent(DirectionComponent.class) != null) {
                pushApartFromUnit(position, POSITION.get(other));
            }
        }

        // Подстраховка: если соседи всё же протолкнули юнита в воду — выталкиваем и оттуда.
        pushOutOfRect(position,
                GameConstants.WATER_MIN_X, GameConstants.WATER_MIN_Y,
                GameConstants.WATER_MAX_X, GameConstants.WATER_MAX_Y);

        // Финальная подстраховка: ни отталкивание от соседей, ни выталкивание
        // из воды/здания само по себе не знает о границах карты — без этого
        // юнита, зажатого у самого края толпой соседей, можно вытолкнуть
        // прямо за пределы карты. Клэмпим последним шагом, после всех
        // остальных коррекций этого тика.
        clampToMapBounds(position);
    }

    private void clampToMapBounds(PositionComponent position) {
        float radius = GameConstants.UNIT_RADIUS;
        position.position.x = MathUtils.clamp(position.position.x, radius, GameConstants.MAP_WIDTH - radius);
        position.position.y = MathUtils.clamp(position.position.y, radius, GameConstants.MAP_HEIGHT - radius);
    }

    /** Мягкое взаимное отталкивание — оба юнита понемногу расходятся, пока их круги перекрываются. */
    private void pushApartFromUnit(PositionComponent a, PositionComponent b) {
        float minDistance = GameConstants.UNIT_RADIUS * 2f;
        float distance = a.position.dst(b.position);

        if (distance >= minDistance) {
            return; // не перекрываются
        }

        if (distance <= 0.0001f) {
            // Ровно в одной точке — направление не определить, толкаем в
            // произвольную фиксированную сторону, лишь бы разъехались.
            a.position.add(minDistance * 0.5f, 0f);
            return;
        }

        float overlap = minDistance - distance;
        pushDirection.set(a.position).sub(b.position).nor();
        // Половина перекрытия — второй юнит оттолкнётся от первого настолько
        // же в свою очередь, когда до него дойдёт очередь в этом же проходе.
        a.position.mulAdd(pushDirection, overlap * 0.5f);
    }

    /** Жёсткое выталкивание из прямоугольной области (здание или вода), если юнит в неё влез. */
    private void pushOutOfRect(PositionComponent unit, float minX, float minY, float maxX, float maxY) {
        float closestX = MathUtils.clamp(unit.position.x, minX, maxX);
        float closestY = MathUtils.clamp(unit.position.y, minY, maxY);

        float dx = unit.position.x - closestX;
        float dy = unit.position.y - closestY;
        float distanceSq = dx * dx + dy * dy;
        float radius = GameConstants.UNIT_RADIUS;

        if (distanceSq >= radius * radius) {
            return; // не перекрывается
        }

        if (distanceSq <= 0.0001f) {
            // Центр юнита ровно внутри прямоугольника (редкий вырожденный
            // случай) — выталкиваем в фиксированную сторону, лишь бы не
            // остаться внутри.
            unit.position.set(maxX + radius, unit.position.y);
            return;
        }

        float distance = (float) Math.sqrt(distanceSq);
        float overlap = radius - distance;
        pushDirection.set(dx, dy).nor();
        unit.position.mulAdd(pushDirection, overlap);
    }
}
