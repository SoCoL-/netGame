package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.utils.Array;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.AttackComponent;
import ru.socol.supreme.shared.components.BuildOrderComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.pathfinding.SpatialHashGrid;

import java.util.Map;

/**
 * Автоагрессия: юнит без активного приказа атаковать (нет AttackComponent),
 * у которого в радиусе своей же дальности атаки (UnitDefinitions
 * .attackRadiusFor — отдельного радиуса агрессии в игре больше нет, это
 * та же величина, которой CombatSystem пользуется для самой атаки)
 * оказался враг, сам получает приказ атаковать ближайшего из них —
 * дальше этим приказом, как и приказом, выданным вручную через
 * AttackUnitRequest, занимается CombatSystem (погоня, стрельба,
 * добивание). У каждого типа юнита свой радиус — стрелок замечает
 * (и сам вступает в бой) намного раньше воина, ровно потому что видит
 * дальше него.
 *
 * Поиск ближайшего врага идёт через SpatialHashGrid, а не перебором всех
 * unitsById (O(n) на юнита вместо O(n²) на всех): сетка перестраивается
 * заново в начале каждого тика (позиции изменились с прошлого тика), а
 * дальше на каждого юнита — только запрос по его же радиусу атаки вместо
 * полного перебора. Ячейка сетки подбирается по максимальной дальности
 * атаки среди ЗАГРУЖЕННЫХ типов (UnitDefinitions.maxAttackRadius) — так
 * добавление нового типа юнита с ещё большей дальностью не потребует
 * ручной перенастройки размера ячейки.
 *
 * Это "агрессивная стойка" по умолчанию для всех боевых юнитов: приказ
 * на движение прерывается, если по пути подвернулся враг — стоек "не
 * атаковать" / "удерживать позицию" в игре нет. Единственное исключение
 * — строитель (см. processEntity): он никогда не ввязывается в бой сам,
 * атакует только по прямому приказу игрока (тот всё ещё снимает
 * BuildOrderComponent — см. GameServer.startAttackOrder), иначе стройка
 * ни на что не отвлекалась бы каждый раз, когда рядом пробежал враг.
 *
 * Family намеренно ИСКЛЮЧАЕТ AttackComponent — уже атакующие юниты цель не
 * пересматривают каждый тик, этим занимается только CombatSystem. Здания
 * автоматически не участвуют: у них нет DirectionComponent, а он
 * обязателен для этой Family (как и для CombatSystem-атакующего) — что
 * само по себе логично, здание сражаться не умеет. Единственное
 * исключение — TURRET (см. GameServer.spawnBuilding): она сознательно
 * получает DirectionComponent/TurretComponent/UnitTypeComponent именно
 * затем, чтобы её подхватила эта же система, как обычного наземного
 * юнита. Family дополнительно исключает ConstructionComponent — иначе
 * недостроенная турель начинала бы стрелять раньше, чем достроится, чего
 * не делает вообще ни одно другое здание (шахта не добывает, казарма не
 * производит, пока строится) — для всех остальных типов сущностей этот
 * фильтр ничего не меняет, у них ConstructionComponent с DirectionComponent
 * никогда не сочетаются.
 *
 * Приоритет -10 — раньше CombatSystem (0) и MovementSystem (10), чтобы
 * свежедобавленный в этот тик AttackComponent сразу подхватила CombatSystem
 * в этом же цикле обновления, без задержки на тик.
 *
 * Живёт в shared (как и CombatSystem/MovementSystem), но реально
 * используется только сервером.
 */
public class AggroSystem extends IteratingSystem {

    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<OwnerComponent> OWNER =
            ComponentMapper.getFor(OwnerComponent.class);
    private static final ComponentMapper<UnitTypeComponent> UNIT_TYPE =
            ComponentMapper.getFor(UnitTypeComponent.class);

    /** Тот же реестр unitId -> Entity, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, Entity> unitsById;
    private final SpatialHashGrid grid;

    private Engine engine;

    public AggroSystem(Map<Integer, Entity> unitsById, SpatialHashGrid grid) {
        super(Family.all(PositionComponent.class, OwnerComponent.class, DirectionComponent.class)
                .exclude(AttackComponent.class, ConstructionComponent.class).get(), -10);
        this.unitsById = unitsById;
        this.grid = grid;
    }

    @Override
    public void addedToEngine(Engine engine) {
        super.addedToEngine(engine);
        this.engine = engine;
    }

    /**
     * Перестраиваем spatial hash в начале каждого тика — юниты подвинулись
     * за предыдущий тик, старые позиции в сетке уже неактуальны. Все
     * сущности вставляются как точки: для целей агрессии здание не имеет
     * значения (Family уже отфильтровала атакующих на DirectionComponent,
     * которого у зданий нет), а координаты враждебного юнита — это просто
     * точка в пространстве.
     */
    @Override
    public void update(float deltaTime) {
        grid.clear();
        for (Entity other : unitsById.values()) {
            PositionComponent position = POSITION.get(other);
            if (position != null) {
                grid.insert(other, position.position.x, position.position.y);
            }
        }
        super.update(deltaTime);
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        PositionComponent position = POSITION.get(entity);
        OwnerComponent owner = OWNER.get(entity);

        UnitTypeComponent typeComponent = UNIT_TYPE.get(entity);
        UnitType myType = typeComponent != null ? typeComponent.type : UnitType.WARRIOR;

        // Строитель — исключение из автоагрессии: атакует только по прямому
        // приказу игрока (AttackUnitRequest/handleAttackUnit или очередь
        // приказов — startAttackOrder всё так же разрешает это, тут ничего
        // не менялось), но сам никогда не бросает стройку и не ввязывается
        // в бой только потому, что враг подошёл в радиус его атаки. Это не
        // общая "стойка" на уровне игрока (её в игре нет вовсе, см. javadoc
        // класса), а фиксированное поведение именно этого типа юнита.
        if (myType == UnitType.BUILDER) {
            return;
        }

        float aggroRadius = UnitDefinitions.attackRadiusFor(myType);

        Array<Entity> nearby = grid.query(position.position.x, position.position.y, aggroRadius);

        Entity nearestEnemy = null;
        float nearestDistanceSq = aggroRadius * aggroRadius;

        for (Entity other : nearby) {
            if (other == entity) {
                continue;
            }

            OwnerComponent otherOwner = OWNER.get(other);
            if (otherOwner == null || otherOwner.playerId == owner.playerId) {
                continue; // свой юнит/здание — не цель
            }

            float distanceSq = position.position.dst2(POSITION.get(other).position);
            if (distanceSq <= nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
                nearestEnemy = other;
            }
        }

        if (nearestEnemy == null) {
            return;
        }

        // cooldown уже 0 — AttackComponent реализует Pool.Poolable,
        // PooledEngine вызывает reset() сама при возврате в пул.
        AttackComponent attack = engine.createComponent(AttackComponent.class);
        attack.targetUnitId = nearestEnemy.getComponent(UnitComponent.class).unitId;
        entity.add(attack);

        // Автоагрессия прерывает вообще любое текущее занятие, включая
        // стройку — строитель, на которого напали, должен защищаться
        // (или хотя бы попытаться), а не долбить молотком, пока его убивают.
        if (entity.getComponent(BuildOrderComponent.class) != null) {
            entity.remove(BuildOrderComponent.class);
        }
    }
}
