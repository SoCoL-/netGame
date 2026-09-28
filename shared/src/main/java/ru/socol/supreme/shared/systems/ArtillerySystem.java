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
import ru.socol.supreme.shared.components.BuildingRubbleComponent;
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
import java.util.Random;

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
 * Наведение и выстрел. Игрок отдаёт приказ (GameServer.handleArtilleryFire
 * кладёт точку в ArtilleryComponent.pendingTargets), а стреляет уже эта
 * система: ствол поворачивается к первой цели со скоростью barrelTurnSpeed,
 * и как только цель оказывается в конусе стрельбы (±firingConeDegrees/2
 * от ствола), башня стреляет — если есть снаряд и shotElectricityCost
 * электричества; нет электричества — ждёт, как и стройка снаряда. Снаряд
 * падает не точно в указанную точку, а в случайную точку круга вокруг неё
 * радиусом shellSpreadRadius (равномерно по площади круга, на любой
 * дальности одинаково).
 *
 * Полёт. Урон наносится не в момент выстрела, а при падении, через
 * расстояние / shellSpeed секунд. Взрыв задевает всё в радиусе
 * shellSplashRadius, и своих тоже, включая руины зданий, кроме авиации
 * (снаряд наземный), юнитов под водой и обломков юнитов. Гибель от
 * взрыва — через тот же EntityDestruction, что и в обычном бою (обломки, руины, препятствия).
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
    private static final ComponentMapper<PositionComponent> POSITION =
            ComponentMapper.getFor(PositionComponent.class);

    /** Сообщает GameServer о выстреле — чтобы разослать клиентам ProjectileFiredEvent (чисто визуальный). */
    public interface ShellLaunchedListener {
        void onShellLaunched(float fromX, float fromY, float toX, float toY, float flightTime);
    }

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
    private final ShellLaunchedListener shellLaunchedListener;
    private final List<Shell> shellsInFlight = new ArrayList<>();
    private Random random = new Random();
    private Engine engine;

    public ArtillerySystem(Map<Integer, Entity> unitsById, Map<Integer, PlayerResources> resourcesByPlayer,
                           Pathfinding pathfinding,
                           CombatSystem.UnitDestroyedListener unitDestroyedListener,
                           CombatSystem.BuildingDestroyedListener buildingDestroyedListener,
                           ShellLaunchedListener shellLaunchedListener) {
        super(Family.all(ArtilleryComponent.class, BuildingComponent.class, OwnerComponent.class,
                PositionComponent.class).get(), 2);
        this.unitsById = unitsById;
        this.resourcesByPlayer = resourcesByPlayer;
        this.pathfinding = pathfinding;
        this.unitDestroyedListener = unitDestroyedListener;
        this.buildingDestroyedListener = buildingDestroyedListener;
        this.shellLaunchedListener = shellLaunchedListener;
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

        aimAndFire(entity, artillery, type, resources, deltaTime);
        buildShells(artillery, type, resources, deltaTime);
    }

    /**
     * Поворачивает ствол к первой цели из очереди (не больше чем на
     * barrelTurnSpeed * deltaTime за тик, кратчайшим путём) и стреляет,
     * как только цель в конусе стрельбы.
     */
    private void aimAndFire(Entity entity, ArtilleryComponent artillery, BuildingType type,
                            PlayerResources resources, float deltaTime) {
        if (artillery.pendingTargets.isEmpty()) {
            return;
        }
        Vector2 position = POSITION.get(entity).position;
        Vector2 target = artillery.pendingTargets.get(0);

        float desiredAngle = MathUtils.atan2(target.y - position.y, target.x - position.x);
        float difference = angleDifference(desiredAngle, artillery.barrelAngle);
        float maxTurn = BuildingDefinitions.barrelTurnSpeedFor(type) * MathUtils.degreesToRadians * deltaTime;
        artillery.barrelAngle = normalizeAngle(artillery.barrelAngle + MathUtils.clamp(difference, -maxTurn, maxTurn));

        float halfCone = BuildingDefinitions.firingConeDegreesFor(type) * 0.5f * MathUtils.degreesToRadians;
        if (Math.abs(angleDifference(desiredAngle, artillery.barrelAngle)) > halfCone + 0.0001f) {
            return; // ещё доворачиваемся
        }

        int shotCost = BuildingDefinitions.shotElectricityCostFor(type);
        if (artillery.shells <= 0 || resources.electricity < shotCost - GameConstants.RESOURCE_EPSILON) {
            return; // наведены, но стрелять нечем — ждём снаряд или электричество
        }

        artillery.pendingTargets.remove(0);
        artillery.shells--;
        resources.electricity = Math.max(0f, resources.electricity - shotCost);

        // Разброс: случайная точка круга радиусом spread вокруг цели. sqrt —
        // чтобы точки ложились равномерно по площади, а не кучковались в центре.
        float spread = BuildingDefinitions.shellSpreadRadiusFor(type);
        float offsetRadius = spread * (float) Math.sqrt(random.nextFloat());
        float offsetAngle = random.nextFloat() * MathUtils.PI2;
        float landX = MathUtils.clamp(target.x + MathUtils.cos(offsetAngle) * offsetRadius, 0f, GameConstants.MAP_WIDTH);
        float landY = MathUtils.clamp(target.y + MathUtils.sin(offsetAngle) * offsetRadius, 0f, GameConstants.MAP_HEIGHT);

        float flightTime = launch(type, position.x, position.y, landX, landY);
        if (shellLaunchedListener != null) {
            shellLaunchedListener.onShellLaunched(position.x, position.y, landX, landY, flightTime);
        }
    }

    /** Источник случайности для разброса — тесты подменяют его, чтобы стрелять точно. */
    public void setRandom(Random random) {
        this.random = random;
    }

    /** Разница углов to - from, приведённая к (-PI, PI] — кратчайший поворот. */
    private static float angleDifference(float to, float from) {
        return normalizeAngle(to - from);
    }

    private static float normalizeAngle(float angle) {
        while (angle > MathUtils.PI) {
            angle -= MathUtils.PI2;
        }
        while (angle <= -MathUtils.PI) {
            angle += MathUtils.PI2;
        }
        return angle;
    }

    /** Стройка следующего снаряда или, при полном запасе, содержание — см. javadoc класса. */
    private void buildShells(ArtilleryComponent artillery, BuildingType type, PlayerResources resources, float deltaTime) {
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
     * Выпускает снаряд башни типа type из (fromX, fromY) в (toX, toY) —
     * снаряд и электричество уже списаны вызывающим (aimAndFire), здесь
     * только полёт и взрыв. Возвращает время полёта в секундах.
     */
    private float launch(BuildingType type, float fromX, float fromY, float toX, float toY) {
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
            // Не ниже нуля — у руин это остаток железа.
            health.currentHealth = Math.max(0, health.currentHealth - shell.damage);
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
        if (entity.getComponent(WreckComponent.class) != null
                && entity.getComponent(BuildingRubbleComponent.class) == null) {
            return false; // обломки юнитов — не цель, а источник железа
        }
        // Руины зданий (BuildingRubbleComponent) задеваем: взрыв отнимает их
        // железо, при нуле они исчезают — так важное здание можно выбить
        // насовсем, не дав отстроить его на старом месте со скидкой. Руины,
        // появившиеся от попадания этого же снаряда, в список целей не
        // попадают (они создаются уже после подсчёта урона, см. explode).
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
