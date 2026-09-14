package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import ru.socol.supreme.shared.BuildingDefinitions;
import ru.socol.supreme.shared.components.BuildingComponent;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;
import ru.socol.supreme.shared.network.messages.PlayerResources;

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

    /** Тот же реестр playerId -> PlayerResources, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, PlayerResources> resourcesByPlayer;

    public ResourceExtractionSystem(Map<Integer, PlayerResources> resourcesByPlayer) {
        super(Family.all(ResourceExtractorComponent.class, OwnerComponent.class, BuildingComponent.class).get(), 3);
        this.resourcesByPlayer = resourcesByPlayer;
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
                resources.iron += amount;
                break;
            case ELECTRICITY:
                resources.electricity += amount;
                break;
        }
    }
}
