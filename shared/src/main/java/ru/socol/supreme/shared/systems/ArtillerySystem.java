package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingSizes;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.components.ArtilleryComponent;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.components.WreckComponent;
import ru.socol.supreme.shared.network.messages.PlayerResources;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Артиллерия: стройка снарядов внутри башни и полёт уже выпущенных.
 *
 * Стройка снарядов. Пока готовых снарядов меньше shellCapacity, башня
 * строит следующий за shellBuildTime секунд и всё это время тратит
 * activeConsumptionRate электричества в секунду — тем же приёмом, что и
 * ProductionSystem со стоимостью юнита: не хватает электричества на этот
 * тик — прогресс просто стоит, ничего не теряется и в минус не уходит.
 * Когда снарядов максимум, стройка прекращается и башня тратит
 * idleConsumptionRate в секунду на содержание (списывается безусловно,
 * зажимаясь на нуле, как простой казармы).
 *
 * Полёт. Сам выстрел (проверки, списание электричества и снаряда)
 * делает GameServer.handleArtilleryFire и передаёт сюда снаряд через
 * launch — урон наносится не в момент выстрела, а при падении, через
 * расстояние / shellSpeed секунд. Взрыв задевает всё в радиусе
 * shellSplashRadius, и своих тоже, кроме авиации (снаряд наземный), юнитов
 * под водой и обломков. Гибель от взрыва — через тот же
 * EntityDestruction, что и в обычном бою (обломки, руины, препятствия).
 *
 * Живёт в shared, но запускается только на сервере.
 */
public class ArtillerySystem extends IteratingSystem {

    private static final ComponentMapper<ArtilleryComponent> ARTILLERY =
            ComponentMapper.getFor(ArtilleryComponent.class);
    private static final ComponentMapper<BuildingComponent> BUILDING =
            ComponentMapper.getFor(BuildingComponent.class);
    private static final ComponentMapper<OwnerComponent> OWNER =
            ComponentMapper.getFor(OwnerComponent.class);

    /** Выпущенный, ещё летящий снаряд. */
    private static final class Shell {
        final float targetX;
        final float targetY;
        final int damage;
        final float splashRadius;
        float remainingFlightTime;

        Shell(float targetX, float targetY, int damage, float splashRadius, float flightTime) {
            this.targetX = targetX;
            this.targetY = targetY;
            this.damage = damage;
            this.splashRadius = splashRadius;
            this.remainingFlightTime = flightTime;
        }
    }

    private final Map<Integer, Entity> unitsById;
    private final Map<Integer, PlayerResources> resourcesByPlayer;
    private final Pathfinding pathfinding;
    private final CombatSystem.UnitDestroyedListener unitDestroyedListener;
    private final CombatSystem.BuildingDestroyedListener buildingDestroyedListener;
    private final List<Shell> shellsInFlight = new ArrayList<>();
    private Engine engine;

    public ArtillerySystem(Map<Integer, Entity> unitsById, Map<Integer, PlayerResources> resourcesByPlayer,
                           Pathfinding pathfinding,
                           CombatSystem.UnitDestroyedListener unitDestroyedListener,
                           CombatSystem.BuildingDestroyedListener buildingDestroyedListener) {
        super(Family.all(ArtilleryComponent.class, BuildingComponent.class, OwnerComponent.class).get(), 2);
        this.unitsById = unitsById;
        this.resourcesByPlayer = resourcesByPlayer;
        this.pathfinding = pathfinding;
        this.unitDestroyedListener = unitDestroyedListener;
        this.buildingDestroyedListener = buildingDestroyedListener;
    }

    @Override
    public void addedToEngine(Engine engine) {
        super.addedToEngine(engine);
        this.engine = engine;
    }

    @Override
    public void update(float deltaTime) {
        super.update(deltaTime);
        updateShellsInFlight(deltaTime);
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        ArtilleryComponent artillery = ARTILLERY.get(entity);
        BuildingType type = BUILDING.get(entity).type;
        PlayerResources resources = resourcesByPlayer.get(OWNER.get(entity).playerId);
        if (resources == null) {
            return;
        }

        if (artillery.shells >= BuildingDefinitions.shellCapacityFor(type)) {
            artillery.shellProgress = 0f;
            float upkeep = BuildingDefinitions.idleConsumptionRateFor(type) * deltaTime;
            resources.electricity = Math.max(0f, resources.electricity - upkeep);
            return;
        }

        float shellBuildTime = BuildingDefinitions.shellBuildTimeFor(type);
        float progressThisTick = Math.min(deltaTime, shellBuildTime - artillery.shellProgress);
        float cost = BuildingDefinitions.activeConsumptionRateFor(type) * progressThisTick;
        if (resources.electricity < cost - GameConstants.RESOURCE_EPSILON) {
            return; // не хватает электричества на этот тик — прогресс стоит
        }
        resources.electricity = Math.max(0f, resources.electricity - cost);
        artillery.shellProgress += progressThisTick;
        if (artillery.shellProgress >= shellBuildTime) {
            artillery.shells++;
            artillery.shellProgress = 0f;
        }
    }

    /**
     * Выпускает снаряд башни типа type из (fromX, fromY) в (toX, toY).
     * Вызывающий (GameServer) уже проверил дальность, запас снарядов и
     * электричества и списал их — здесь только полёт и взрыв. Возвращает
     * время полёта в секундах (для клиентской анимации).
     */
    public float launch(BuildingType type, float fromX, float fromY, float toX, float toY) {
        float distance = Vector2.dst(fromX, fromY, toX, toY);
        float speed = BuildingDefinitions.shellSpeedFor(type);
        float flightTime = speed > 0f ? distance / speed : 0f;
        shellsInFlight.add(new Shell(toX, toY, BuildingDefinitions.shellDamageFor(type),
                BuildingDefinitions.shellSplashRadiusFor(type), flightTime));
        return flightTime;
    }

    /** Сколько снарядов сейчас в полёте — для тестов. */
    public int shellsInFlightCount() {
        return shellsInFlight.size();
    }

    private void updateShellsInFlight(float deltaTime) {
        List<Shell> landed = null;
        for (Iterator<Shell> iterator = shellsInFlight.iterator(); iterator.hasNext(); ) {
            Shell shell = iterator.next();
            shell.remainingFlightTime -= deltaTime;
            if (shell.remainingFlightTime <= 0f) {
                iterator.remove();
                if (landed == null) {
                    landed = new ArrayList<>();
                }
                landed.add(shell);
            }
        }
        if (landed != null) {
            for (Shell shell : landed) {
                explode(shell);
            }
        }
    }

    private void explode(Shell shell) {
        // Сначала только урон и список погибших, уничтожение — отдельным
        // проходом: EntityDestruction удаляет из unitsById, по которому мы
        // сейчас итерируемся.
        List<Entity> killed = new ArrayList<>();
        for (Entity entity : unitsById.values()) {
            if (!isHitBy(shell, entity)) {
                continue;
            }
            HealthComponent health = entity.getComponent(HealthComponent.class);
            health.currentHealth -= shell.damage;
            if (health.currentHealth <= 0) {
                killed.add(entity);
            }
        }
        for (Entity entity : killed) {
            int unitId = entity.getComponent(UnitComponent.class).unitId;
            EntityDestruction.destroy(engine, unitsById, pathfinding, entity, unitId,
                    unitDestroyedListener, buildingDestroyedListener);
        }
    }

    private boolean isHitBy(Shell shell, Entity entity) {
        if (entity.getComponent(WreckComponent.class) != null) {
            return false; // обломки — не боевая цель, а источник железа
        }
        HealthComponent health = entity.getComponent(HealthComponent.class);
        PositionComponent position = entity.getComponent(PositionComponent.class);
        if (health == null || position == null) {
            return false;
        }
        UnitTypeComponent unitType = entity.getComponent(UnitTypeComponent.class);
        boolean isBuilding = entity.getComponent(BuildingComponent.class) != null;
        if (!isBuilding && unitType != null && UnitDefinitions.isAirUnit(unitType.type)) {
            return false; // снаряд наземный — авиацию не задевает
        }
        float x = position.position.x;
        float y = position.position.y;
        if (!isBuilding && Pathfinding.isInsideWater(x, y)) {
            return false; // строитель под водой — от взрыва на поверхности защищён, как и от обычного боя
        }
        // У здания расстояние считаем до ближайшей точки его прямоугольника,
        // а не до центра: взрыв у стены большого здания должен его задеть.
        float closestX = x;
        float closestY = y;
        if (isBuilding) {
            float halfWidth = BuildingSizes.halfWidth(entity);
            float halfHeight = BuildingSizes.halfHeight(entity);
            closestX = MathUtils.clamp(shell.targetX, x - halfWidth, x + halfWidth);
            closestY = MathUtils.clamp(shell.targetY, y - halfHeight, y + halfHeight);
        }
        return Vector2.dst2(shell.targetX, shell.targetY, closestX, closestY) <= shell.splashRadius * shell.splashRadius;
    }
}
