package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.BuildingType;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.ConstructionComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;
import ru.socol.supreme.shared.network.messages.PlayerResources;

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
 * Железо — единственный ресурс с ограниченной вместимостью хранилища
 * (см. GameConstants.IRON_BASE_CAPACITY, IRON_STORAGE в buildings.json):
 * добыча железа ВСЕГДА "происходит" каждый тик, но зачисление зажимается
 * сверху текущей вместимостью игрока — если хранилище полно, лишнее
 * железо просто пропадает, а не копится и не блокирует саму добычу.
 * Электричество пока без потолка — если понадобится такое же хранилище
 * для него, storesResourceType/storageCapacity в BuildingDefinition уже
 * общие, не специфичные для железа, менять нужно будет только эту
 * систему, не схему данных.
 *
 * Вместимость считается ОДИН раз за тик (update(), до обработки
 * отдельных зданий добычи), а не при обработке каждого — иначе с
 * несколькими шахтами железа считали бы одно и то же по нескольку раз.
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

    /** Тот же реестр unitId -> Entity, что и в GameServer — нужен, чтобы найти все здания IRON_STORAGE игрока. */
    private final Map<Integer, Entity> unitsById;

    /** Тот же реестр playerId -> PlayerResources, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, PlayerResources> resourcesByPlayer;

    /** playerId -> текущая вместимость хранилища железа — пересчитывается заново в начале каждого тика, см. update(). */
    private final Map<Integer, Float> ironCapacityByPlayer = new HashMap<>();

    public ResourceExtractionSystem(Map<Integer, Entity> unitsById, Map<Integer, PlayerResources> resourcesByPlayer) {
        super(Family.all(ResourceExtractorComponent.class, OwnerComponent.class, BuildingComponent.class).get(), 3);
        this.unitsById = unitsById;
        this.resourcesByPlayer = resourcesByPlayer;
    }

    @Override
    public void update(float deltaTime) {
        computeIronCapacities();
        super.update(deltaTime);
    }

    /** Базовая вместимость плюс storageCapacityFor(IRON_STORAGE) за каждое достроенное (не строящееся) хранилище игрока. */
    private void computeIronCapacities() {
        ironCapacityByPlayer.clear();
        for (Integer playerId : resourcesByPlayer.keySet()) {
            ironCapacityByPlayer.put(playerId, GameConstants.IRON_BASE_CAPACITY);
        }

        for (Entity entity : unitsById.values()) {
            BuildingComponent building = BUILDING.get(entity);
            if (building == null || building.type != BuildingType.IRON_STORAGE) {
                continue;
            }
            if (entity.getComponent(ConstructionComponent.class) != null) {
                continue; // ещё строится — вместимость пока не увеличивает
            }
            OwnerComponent owner = OWNER.get(entity);
            if (owner == null) {
                continue;
            }
            float current = ironCapacityByPlayer.getOrDefault(owner.playerId, GameConstants.IRON_BASE_CAPACITY);
            ironCapacityByPlayer.put(owner.playerId, current + BuildingDefinitions.storageCapacityFor(BuildingType.IRON_STORAGE));
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
        switch (extractor.resourceType) {
            case IRON:
                float capacity = ironCapacityByPlayer.getOrDefault(owner.playerId, GameConstants.IRON_BASE_CAPACITY);
                resources.iron = Math.min(resources.iron + amount, capacity);
                break;
            case ELECTRICITY:
                resources.electricity += amount;
                break;
        }
    }
}
