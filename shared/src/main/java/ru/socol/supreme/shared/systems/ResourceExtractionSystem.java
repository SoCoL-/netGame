package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.ResourceType;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;
import ru.socol.supreme.shared.network.messages.PlayerResources;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Для каждого достроенного здания добычи (ResourceExtractorComponent —
 * добавляет ConstructionSystem по завершении постройки) прибавляет
 * владельцу здания в PlayerResources rate * deltaTime за тик, где rate —
 * BuildingDefinitions.extractionRateFor(BuildingComponent.type) той же
 * сущности (не абстрактному типу ресурса, а конкретному типу здания,
 * buildings.json). PlayerResources — float, поэтому зачисление идёт
 * сразу дробно, без промежуточного накопителя.
 *
 * Оба ресурса — с ограниченной вместимостью хранилища
 * (GameConstants.baseCapacityFor, IRON_STORAGE/ELECTRICITY_STORAGE в
 * buildings.json — оба хранилища находятся тут по BuildingDefinitions
 * .storesResourceTypeFor, а не двумя раздельными веткам кода на каждый
 * тип). Добыча ВСЕГДА "происходит" каждый тик, но зачисление зажимается
 * сверху текущей вместимостью игрока для этого ресурса — если хранилище
 * полно, лишнее просто пропадает, а не копится и не блокирует саму
 * добычу.
 *
 * Вместимость считается ОДИН раз за тик (update(), до обработки
 * отдельных зданий добычи), а не при обработке каждого — иначе с
 * несколькими зданиями добычи одного игрока считали бы одно и то же по
 * нескольку раз.
 *
 * Приоритет 3 — после ConstructionSystem (2), до MovementSystem (10).
 *
 * Живёт в shared (как и остальные gameplay-системы), но реально
 * используется только сервером.
 */
public class ResourceExtractionSystem extends IteratingSystem {

    private static final ComponentMapper<OwnerComponent> OWNER =
            ComponentMapper.getFor(OwnerComponent.class);
    private static final ComponentMapper<ResourceExtractorComponent> EXTRACTOR =
            ComponentMapper.getFor(ResourceExtractorComponent.class);
    private static final ComponentMapper<BuildingComponent> BUILDING =
            ComponentMapper.getFor(BuildingComponent.class);

    /** Тот же реестр unitId -> Entity, что и в GameServer — нужен, чтобы найти все здания-хранилища игрока. */
    private final Map<Integer, Entity> unitsById;

    /** Тот же реестр playerId -> PlayerResources, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, PlayerResources> resourcesByPlayer;

    /** ResourceType -> (playerId -> текущая вместимость хранилища этого ресурса) — пересчитывается заново в начале каждого тика, см. update(). */
    private final Map<ResourceType, Map<Integer, Float>> capacityByResourceAndPlayer = new EnumMap<>(ResourceType.class);

    public ResourceExtractionSystem(Map<Integer, Entity> unitsById, Map<Integer, PlayerResources> resourcesByPlayer) {
        super(Family.all(ResourceExtractorComponent.class, OwnerComponent.class, BuildingComponent.class).get(), 3);
        this.unitsById = unitsById;
        this.resourcesByPlayer = resourcesByPlayer;
        for (ResourceType type : ResourceType.values()) {
            capacityByResourceAndPlayer.put(type, new HashMap<>());
        }
    }

    @Override
    public void update(float deltaTime) {
        computeCapacities();
        super.update(deltaTime);
    }

    /** Базовая вместимость (GameConstants.baseCapacityFor) плюс storageCapacityFor каждого достроенного (не строящегося) хранилища этого ресурса у игрока. */
    private void computeCapacities() {
        for (ResourceType type : ResourceType.values()) {
            Map<Integer, Float> capacityByPlayer = capacityByResourceAndPlayer.get(type);
            capacityByPlayer.clear();
            for (Integer playerId : resourcesByPlayer.keySet()) {
                capacityByPlayer.put(playerId, GameConstants.baseCapacityFor(type));
            }
        }

        for (Entity entity : unitsById.values()) {
            BuildingComponent building = BUILDING.get(entity);
            if (building == null) {
                continue;
            }
            ResourceType storesType = BuildingDefinitions.storesResourceTypeFor(building.type);
            if (storesType == null) {
                continue;
            }
            if (entity.getComponent(ConstructionComponent.class) != null) {
                continue; // ещё строится — вместимость пока не увеличивает
            }
            OwnerComponent owner = OWNER.get(entity);
            if (owner == null) {
                continue;
            }
            Map<Integer, Float> capacityByPlayer = capacityByResourceAndPlayer.get(storesType);
            float current = capacityByPlayer.getOrDefault(owner.playerId, GameConstants.baseCapacityFor(storesType));
            capacityByPlayer.put(owner.playerId, current + BuildingDefinitions.storageCapacityFor(building.type));
        }
    }

    @Override
    protected void processEntity(Entity entity, float deltaTime) {
        ResourceExtractorComponent extractor = EXTRACTOR.get(entity);
        OwnerComponent owner = OWNER.get(entity);

        PlayerResources resources = resourcesByPlayer.get(owner.playerId);
        if (resources == null) {
            return; // игрок уже отключился — просто ничего не зачисляем, здание скоро уберут вместе с его сущностями
        }

        float amount = BuildingDefinitions.extractionRateFor(BUILDING.get(entity).type) * deltaTime;
        float capacity = capacityByResourceAndPlayer.get(extractor.resourceType)
                .getOrDefault(owner.playerId, GameConstants.baseCapacityFor(extractor.resourceType));

        switch (extractor.resourceType) {
            case IRON:
                resources.iron = Math.min(resources.iron + amount, capacity);
                break;
            case ELECTRICITY:
                resources.electricity = Math.min(resources.electricity + amount, capacity);
                break;
        }
    }
}
