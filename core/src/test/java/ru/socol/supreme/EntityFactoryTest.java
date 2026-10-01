package ru.socol.supreme;

import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.socol.supreme.components.BuildBeamComponent;
import ru.socol.supreme.components.InterpolationComponent;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.ArtilleryComponent;
import ru.socol.supreme.shared.components.BuildingRubbleComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.HealthComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.ProductionComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.components.WreckComponent;
import ru.socol.supreme.shared.network.messages.UnitSnapshot;
import ru.socol.supreme.components.TurretDisplayComponent;
import ru.socol.supreme.shared.network.messages.PathPoint;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EntityFactory — единственное место на клиенте, где серверный
 * WorldSnapshot превращается в Ashley-сущности (см. её же javadoc). Эти
 * тесты гоняют её на голом Ashley Engine, без окна, без OpenGL и без
 * настоящей сети — только руками собранные UnitSnapshot — чтобы регрессии
 * в этой синхронизации (забытое поле при обновлении существующей
 * сущности, не тот компонент навешен не тому типу здания и т.п.) ловились
 * сразу, а не только визуально на запущенной игре.
 *
 * BuildingDefinitions читается тестами через её же публичные методы
 * (buildTimeFor/maxHealthFor/resourceTypeFor), а не хардкодом чисел из
 * buildings.json — эти тесты проверяют логику EntityFactory (какой
 * компонент когда навешивается), а не сам баланс зданий, так что должны
 * оставаться верными независимо от того, найден ли buildings.json в
 * рабочей директории на момент запуска (в core/ его нет — используются
 * встроенные значения по умолчанию, см. javadoc BuildingDefinitions).
 */
class EntityFactoryTest {

    private Engine engine;
    private EntityFactory factory;

    @BeforeEach
    void setUp() {
        engine = new Engine();
        factory = new EntityFactory(engine);
    }

    private static UnitSnapshot unitSnapshot(int unitId, int ownerId, UnitType type, float x, float y) {
        UnitSnapshot snapshot = new UnitSnapshot();
        snapshot.unitId = unitId;
        snapshot.ownerId = ownerId;
        snapshot.building = false;
        snapshot.unitType = type.ordinal();
        snapshot.x = x;
        snapshot.y = y;
        snapshot.health = 80;
        snapshot.maxHealth = 80;
        return snapshot;
    }

    private static UnitSnapshot buildingSnapshot(int unitId, BuildingType type, float x, float y) {
        UnitSnapshot snapshot = new UnitSnapshot();
        snapshot.unitId = unitId;
        snapshot.ownerId = 0;
        snapshot.building = true;
        snapshot.buildingType = type.ordinal();
        snapshot.x = x;
        snapshot.y = y;
        snapshot.maxHealth = BuildingDefinitions.maxHealthFor(type);
        snapshot.health = snapshot.maxHealth;
        return snapshot;
    }

    @Test
    void createsUnitEntityWithPositionOwnerAndDirection() {
        UnitSnapshot snapshot = unitSnapshot(1, 1, UnitType.WARRIOR, 120f, 340f);
        snapshot.dirX = 1f;
        snapshot.dirY = 0f;
        snapshot.moving = true;

        factory.applySnapshot(List.of(snapshot));
        Entity entity = factory.getEntity(1);

        assertNotNull(entity, "сущность должна быть создана по новому unitId из снапшота");
        assertEquals(120f, entity.getComponent(PositionComponent.class).position.x);
        assertEquals(340f, entity.getComponent(PositionComponent.class).position.y);
        assertEquals(1, entity.getComponent(OwnerComponent.class).playerId);
        assertEquals(UnitType.WARRIOR, entity.getComponent(UnitTypeComponent.class).type);
        assertTrue(entity.getComponent(DirectionComponent.class).moving);
        assertEquals(1f, entity.getComponent(DirectionComponent.class).direction.x);
    }

    @Test
    void newUnitStartsInterpolationAlreadyAtTargetPosition() {
        // Без этого юнит бы "наезжал" на своё место из (0,0) при появлении
        // на экране — см. javadoc EntityFactory.createEntity про elapsed.
        UnitSnapshot snapshot = unitSnapshot(2, 0, UnitType.SCOUT, 500f, 600f);

        factory.applySnapshot(List.of(snapshot));
        InterpolationComponent interpolation = factory.getEntity(2).getComponent(InterpolationComponent.class);

        assertEquals(500f, interpolation.previousPosition.x);
        assertEquals(500f, interpolation.targetPosition.x);
        assertEquals(GameConstants.SNAPSHOT_RATE, interpolation.elapsed);
    }

    @Test
    void existingEntityUpdatesHealthAndInterpolationTargetButKeepsMaxHealth() {
        UnitSnapshot first = unitSnapshot(3, 0, UnitType.WARRIOR, 0f, 0f);
        first.health = 80;
        first.maxHealth = 80;
        factory.applySnapshot(List.of(first));

        UnitSnapshot second = unitSnapshot(3, 0, UnitType.WARRIOR, 50f, 0f);
        second.health = 40;
        second.maxHealth = 999; // не должно попасть в maxHealth уже существующей сущности
        factory.applySnapshot(List.of(second));

        Entity entity = factory.getEntity(3);
        HealthComponent health = entity.getComponent(HealthComponent.class);
        assertEquals(40, health.currentHealth);
        assertEquals(80, health.maxHealth,
                "maxHealth задаётся только при создании сущности, снапшот на уже существующей его не меняет");

        InterpolationComponent interpolation = entity.getComponent(InterpolationComponent.class);
        assertEquals(0f, interpolation.previousPosition.x, "точка отправления лерпа — там, где юнит нарисован ПРЯМО СЕЙЧАС");
        assertEquals(50f, interpolation.targetPosition.x);
    }

    @Test
    void entityMissingFromNextSnapshotIsRemoved() {
        UnitSnapshot a = unitSnapshot(10, 0, UnitType.WARRIOR, 0f, 0f);
        UnitSnapshot b = unitSnapshot(11, 0, UnitType.WARRIOR, 0f, 0f);
        factory.applySnapshot(List.of(a, b));
        assertEquals(2, engine.getEntities().size());

        factory.applySnapshot(List.of(a)); // b пропал из мира

        assertNull(factory.getEntity(11));
        assertEquals(1, engine.getEntities().size());
    }

    @Test
    void underConstructionBuildingGetsConstructionComponentWithProgress() {
        UnitSnapshot snapshot = buildingSnapshot(20, BuildingType.ARCHER_BARRACKS, 100f, 100f);
        snapshot.underConstruction = true;
        snapshot.constructionProgress = 0.25f;

        factory.applySnapshot(List.of(snapshot));
        Entity entity = factory.getEntity(20);

        ConstructionComponent construction = entity.getComponent(ConstructionComponent.class);
        assertNotNull(construction);
        float totalTime = BuildingDefinitions.buildTimeFor(BuildingType.ARCHER_BARRACKS);
        assertEquals(totalTime, construction.totalTime);
        assertEquals((1f - 0.25f) * totalTime, construction.remaining, 0.0001f);
        assertNull(entity.getComponent(ProductionComponent.class),
                "пока стройка не завершена, рабочий компонент не навешивается");
    }

    @Test
    void constructionFinishingBetweenSnapshotsSwapsToProductionComponent() {
        UnitSnapshot underConstruction = buildingSnapshot(21, BuildingType.ARCHER_BARRACKS, 0f, 0f);
        underConstruction.underConstruction = true;
        underConstruction.constructionProgress = 0.9f;
        factory.applySnapshot(List.of(underConstruction));
        assertNotNull(factory.getEntity(21).getComponent(ConstructionComponent.class));

        UnitSnapshot finished = buildingSnapshot(21, BuildingType.ARCHER_BARRACKS, 0f, 0f);
        finished.underConstruction = false;
        finished.queuedCount = 0;
        factory.applySnapshot(List.of(finished));

        Entity entity = factory.getEntity(21);
        assertNull(entity.getComponent(ConstructionComponent.class),
                "ConstructionComponent должен сняться, когда стройка завершилась между снапшотами");
        assertNotNull(entity.getComponent(ProductionComponent.class),
                "казарма производит юнитов — должен появиться ProductionComponent");
    }

    @Test
    void finishedIronMineGetsResourceExtractorNotProduction() {
        UnitSnapshot snapshot = buildingSnapshot(22, BuildingType.IRON_MINE, 0f, 0f);
        snapshot.underConstruction = false;

        factory.applySnapshot(List.of(snapshot));
        Entity entity = factory.getEntity(22);

        assertNull(entity.getComponent(ProductionComponent.class));
        ResourceExtractorComponent extractor = entity.getComponent(ResourceExtractorComponent.class);
        assertNotNull(extractor);
        assertEquals(BuildingDefinitions.resourceTypeFor(BuildingType.IRON_MINE), extractor.resourceType);
    }

    @Test
    void plainWreckHasNoRubbleComponent() {
        UnitSnapshot snapshot = buildingSnapshot(30, BuildingType.WRECK, 0f, 0f);
        snapshot.wreckUnderwater = true;
        snapshot.rubbleOriginalBuildingType = -1; // обычные обломки юнита, не именные

        factory.applySnapshot(List.of(snapshot));
        Entity entity = factory.getEntity(30);

        WreckComponent wreck = entity.getComponent(WreckComponent.class);
        assertNotNull(wreck);
        assertTrue(wreck.underwater);
        assertNull(entity.getComponent(BuildingRubbleComponent.class));
    }

    @Test
    void namedBuildingRubbleGetsOriginalTypeComponent() {
        UnitSnapshot snapshot = buildingSnapshot(31, BuildingType.WRECK, 0f, 0f);
        snapshot.rubbleOriginalBuildingType = BuildingType.ARCHER_BARRACKS.ordinal();

        factory.applySnapshot(List.of(snapshot));
        Entity entity = factory.getEntity(31);

        BuildingRubbleComponent rubble = entity.getComponent(BuildingRubbleComponent.class);
        assertNotNull(rubble, "именные обломки должны нести BuildingRubbleComponent");
        assertEquals(BuildingType.ARCHER_BARRACKS, rubble.originalType);
    }

    @Test
    void buildBeamAppearsAndDisappearsWithBuildTargetUnitId() {
        UnitSnapshot idle = unitSnapshot(40, 0, UnitType.BUILDER, 0f, 0f);
        idle.buildTargetUnitId = 0;
        factory.applySnapshot(List.of(idle));
        assertNull(factory.getEntity(40).getComponent(BuildBeamComponent.class));

        UnitSnapshot building = unitSnapshot(40, 0, UnitType.BUILDER, 0f, 0f);
        building.buildTargetUnitId = 99;
        factory.applySnapshot(List.of(building));
        BuildBeamComponent beam = factory.getEntity(40).getComponent(BuildBeamComponent.class);
        assertNotNull(beam);
        assertEquals(99, beam.targetBuildingUnitId);

        UnitSnapshot idleAgain = unitSnapshot(40, 0, UnitType.BUILDER, 0f, 0f);
        idleAgain.buildTargetUnitId = 0;
        factory.applySnapshot(List.of(idleAgain));
        assertNull(factory.getEntity(40).getComponent(BuildBeamComponent.class),
                "луч стройки должен сняться, когда строитель перестал строить");
    }

    @Test
    void artilleryShellsAppearWhenTowerIsReadyAndFollowSnapshots() {
        UnitSnapshot underConstruction = buildingSnapshot(50, BuildingType.ARTILLERY, 1500f, 1500f);
        underConstruction.underConstruction = true;
        factory.applySnapshot(List.of(underConstruction));
        assertNull(factory.getEntity(50).getComponent(ArtilleryComponent.class),
                "у строящейся башни (artilleryShells == -1) запаса снарядов нет");

        UnitSnapshot ready = buildingSnapshot(50, BuildingType.ARTILLERY, 1500f, 1500f);
        ready.artilleryShells = 3;
        ready.artilleryShellProgress = 2.5f;
        ready.artilleryCooldownRemaining = 0.7f;
        ready.artilleryTargets.add(new PathPoint(3000f, 3100f));
        ready.turretDirX = 0f;
        ready.turretDirY = 1f;
        factory.applySnapshot(List.of(ready));

        ArtilleryComponent artillery = factory.getEntity(50).getComponent(ArtilleryComponent.class);
        assertNotNull(artillery);
        assertEquals(3, artillery.shells);
        assertEquals(2.5f, artillery.shellProgress);
        assertEquals(0.7f, artillery.cooldownRemaining, "перезарядка — для индикатора в панели");
        assertEquals(1, artillery.pendingTargets.size());
        assertEquals(3100f, artillery.pendingTargets.get(0).y);
        assertEquals(1f, factory.getEntity(50).getComponent(TurretDisplayComponent.class).dirY,
                "угол ствола артиллерии — через тот же TurretDisplayComponent, что и у турели");
    }
}
