package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.EntitySystem;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.utils.ImmutableArray;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.CollectOrderComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.components.WreckComponent;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.HashMap;
import java.util.Map;

/**
 * Обрабатывает приказ "собирать железо с обломков" (CollectOrderComponent)
 * — устроена зеркально BuildSystem/RepairSystem (см. подробный javadoc
 * BuildSystem про точку подхода с отступом внутрь buildRadius, общий
 * множитель скорости у нескольких строителей и почему всё это в два
 * прохода, а не в processEntity на каждого независимо), только вместо
 * того чтобы ТРАТИТЬ ресурсы игрока, эта система их ему НАЧИСЛЯЕТ,
 * забирая железо из HealthComponent.currentHealth обломков (см. javadoc
 * WreckComponent, почему запас железа хранится именно там, а не отдельным
 * полем). Как только currentHealth доходит до 0 — обломки убираются с
 * карты тем же приёмом, что и снесённое здание (engine.removeEntity +
 * unitsById.remove + Pathfinding.removeBuildingObstacle): любой
 * строитель, чей CollectOrderComponent ссылался на них, на следующем
 * тике сам увидит target == null (updateBuilder) и снимет приказ —
 * отдельно чистить не нужно, тот же идиома, что и у BuildSystem/
 * RepairSystem при разрушении цели.
 *
 * Начисление НЕ ограничено вместимостью хранилища игрока (в отличие от
 * ResourceExtractionSystem, где переполнение хранилища просто "теряет"
 * лишнюю добычу) — сознательное упрощение: обломки, в отличие от шахты,
 * разовый и небольшой источник железа, портить игроку сбор из-за
 * забитого хранилища не хотелось.
 *
 * Владелец, которому зачисляется железо, — владелец СТРОИТЕЛЯ, а не
 * обломков: у обломков нет владельца вовсе (WreckComponent, playerId ==
 * GameConstants.NEUTRAL_OWNER_ID) — собрать их может строитель любого
 * игрока, кто первым доберётся. Если у одних обломков одновременно
 * оказались в радиусе строители РАЗНЫХ игроков (в игре с двумя игроками
 * это означало бы, что они мирно стоят рядом — на практике недостижимо:
 * рано или поздно один атакует другого), железо получает игрок ПЕРВОГО
 * найденного в радиусе строителя — простое и достаточное поведение для
 * сценария, который тут вообще имеется в виду.
 *
 * Приоритет 1 — тот же, что и у BuildSystem/RepairSystem (после
 * CombatSystem (0), до ProductionSystem (2)/ConstructionSystem (3)) —
 * не то чтобы порядок между этими тремя системами важен сам по себе (они
 * не пересекаются по данным), но направление погони, выставленное здесь,
 * должно успеть попасть в тот же тик MovementSystem (10).
 *
 * Живёт в shared (как и BuildSystem/RepairSystem), но реально
 * используется только сервером.
 */
public class ScavengeSystem extends EntitySystem {

    /** count — сколько строителей сейчас в радиусе этих обломков; creditedPlayerId — кому зачислять железо (playerId первого найденного в радиусе, см. javadoc класса). */
    private static final class Tally {
        int count;
        int creditedPlayerId = -1;
    }

    private static final Family FAMILY =
            Family.all(CollectOrderComponent.class, PositionComponent.class, DirectionComponent.class).get();

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<DirectionComponent> DIRECTION =
            ComponentMapper.getFor(DirectionComponent.class);
    private static final ComponentMapper<CollectOrderComponent> COLLECT_ORDER =
            ComponentMapper.getFor(CollectOrderComponent.class);
    private static final ComponentMapper<UnitTypeComponent> UNIT_TYPE =
            ComponentMapper.getFor(UnitTypeComponent.class);
    private static final ComponentMapper<OwnerComponent> OWNER =
            ComponentMapper.getFor(OwnerComponent.class);

    /** Тот же реестр unitId -> Entity, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, Entity> unitsById;

    /** Тот же реестр playerId -> PlayerResources, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, PlayerResources> resourcesByPlayer;

    /** Переиспользуемый вектор для точки подхода — не аллоцируем новый каждый тик на каждого строителя. */
    private final Vector2 approachPoint = new Vector2();

    /** targetWreckUnitId -> Tally — пересчитывается заново в начале каждого update(), не накапливается между тиками. */
    private final Map<Integer, Tally> buildersInRangeByTarget = new HashMap<>();

    private Engine engine;
    private ImmutableArray<Entity> collectors;

    /** Поиск пути этого матча — см. javadoc Pathfinding, почему не статический. */
    private final Pathfinding pathfinding;

    public ScavengeSystem(Map<Integer, Entity> unitsById, Map<Integer, PlayerResources> resourcesByPlayer,
                          Pathfinding pathfinding) {
        super(1);
        this.pathfinding = pathfinding;
        this.unitsById = unitsById;
        this.resourcesByPlayer = resourcesByPlayer;
    }

    @Override
    public void addedToEngine(Engine engine) {
        this.engine = engine;
        collectors = engine.getEntitiesFor(FAMILY);
    }

    @Override
    public void update(float deltaTime) {
        buildersInRangeByTarget.clear();

        // Первый проход — у каждого строителя решаем "в пути или на месте".
        for (Entity builder : collectors) {
            updateBuilder(builder);
        }

        // Второй проход — зная итоговое число строителей на каждую цель,
        // применяем к ней ОДИН общий множитель скорости разом.
        for (Map.Entry<Integer, Tally> entry : buildersInRangeByTarget.entrySet()) {
            advanceCollection(entry.getKey(), entry.getValue(), deltaTime);
        }
    }

    /** Продвигает сбор с ОДНИХ обломков на этот тик — общий множитель скорости и зачисление железа владельцу строителя. */
    private void advanceCollection(int targetWreckUnitId, Tally tally, float deltaTime) {
        Entity wreck = unitsById.get(targetWreckUnitId);
        HealthComponent wreckIron = wreck != null ? wreck.getComponent(HealthComponent.class) : null;
        if (wreck == null || wreckIron == null) {
            return; // не должно происходить — цель уже проверена в updateBuilder — но на всякий случай не падаем
        }

        float speedMultiplier = 1f + (tally.count - 1) * GameConstants.EXTRA_BUILDER_SPEED_BONUS;
        float amount = Math.min(GameConstants.WRECK_IRON_COLLECT_PER_SECOND * speedMultiplier * deltaTime,
                wreckIron.currentHealth);
        if (amount <= 0f) {
            return;
        }

        if (tally.creditedPlayerId >= 0) {
            PlayerResources resources = resourcesByPlayer.get(tally.creditedPlayerId);
            if (resources != null) {
                resources.iron += amount;
            }
        }

        wreckIron.currentHealth -= Math.round(amount);
        if (wreckIron.currentHealth <= 0) {
            // Железо кончилось — обломки убираются с карты, тем же
            // приёмом, что и снесённое/разрушенное здание. Строители,
            // чей CollectOrderComponent ссылался на них, сами заметят
            // target == null на следующем тике (updateBuilder) и снимут
            // приказ — отдельно чистить не нужно.
            engine.removeEntity(wreck);
            unitsById.remove(targetWreckUnitId);
            pathfinding.removeBuildingObstacle(targetWreckUnitId);
        }
    }

    /** Решает для одного строителя: цель ещё актуальна? Идти к ней или уже на месте? Если на месте — засчитывает его в buildersInRangeByTarget, сам сбор железа тут не трогает. */
    private void updateBuilder(Entity builder) {
        CollectOrderComponent order = COLLECT_ORDER.get(builder);
        Entity target = unitsById.get(order.targetWreckUnitId);
        WreckComponent wreck = target != null ? target.getComponent(WreckComponent.class) : null;

        if (target == null || wreck == null) {
            // Обломки уже собраны без остатка (см. advanceCollection) или
            // пропали как-то иначе — приказ больше не актуален.
            builder.remove(CollectOrderComponent.class);
            DIRECTION.get(builder).moving = false;
            return;
        }

        UnitTypeComponent builderTypeComponent = UNIT_TYPE.get(builder);
        UnitType builderType = builderTypeComponent != null ? builderTypeComponent.type : UnitType.BUILDER;
        float buildRange = UnitDefinitions.buildRadiusFor(builderType);

        PositionComponent myPosition = POSITION.get(builder);
        PositionComponent targetPosition = POSITION.get(target);
        DirectionComponent direction = DIRECTION.get(builder);

        float distance = myPosition.position.dst(targetPosition.position);

        if (distance > buildRange) {
            order.inRange = false;
            // Та же точка подхода, с тем же отступом внутрь buildRange и
            // тем же условием пересчёта, что и в BuildSystem.updateBuilder
            // — см. её подробный javadoc про то, откуда взялся отступ на
            // ARRIVE_THRESHOLD и почему пересчёт не на каждом тике, а
            // именно так — лениво, при (пере)выдаче приказа или когда
            // строитель уже "дошёл", но всё ещё не в радиусе.
            if (!order.hasApproachPoint || !direction.moving) {
                float approachDistance = Math.max(0f, buildRange - GameConstants.ARRIVE_THRESHOLD);
                approachPoint.set(myPosition.position).sub(targetPosition.position).nor()
                        .scl(approachDistance).add(targetPosition.position);
                order.hasApproachPoint = true;
                order.approachX = approachPoint.x;
                order.approachY = approachPoint.y;
            }
            pathfinding.setDestination(builder, myPosition, direction, order.approachX, order.approachY);
            return;
        }

        // В радиусе — останавливаемся и засчитываемся в подсчёт для
        // второго прохода.
        order.hasApproachPoint = false;
        direction.moving = false;
        order.inRange = true;

        Tally tally = buildersInRangeByTarget.computeIfAbsent(order.targetWreckUnitId, key -> new Tally());
        tally.count++;
        if (tally.creditedPlayerId < 0) {
            OwnerComponent owner = OWNER.get(builder);
            if (owner != null) {
                tally.creditedPlayerId = owner.playerId;
            }
        }
    }
}
