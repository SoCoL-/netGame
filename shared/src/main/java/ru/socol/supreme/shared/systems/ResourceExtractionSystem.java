package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.systems.IteratingSystem;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.components.OwnerComponent;
import ru.socol.supreme.shared.components.ResourceExtractorComponent;
import ru.socol.supreme.shared.network.messages.PlayerResources;

import java.util.Map;

/**
 * Для каждого достроенного здания добычи (ResourceExtractorComponent —
 * добавляет ConstructionSystem по завершении постройки) копит дробный
 * прогресс на GameConstants.IRON_EXTRACTION_RATE в секунду и, набрав
 * целую единицу, зачисляет её владельцу здания в PlayerResources. Пока в
 * игре только железо (ResourceType.IRON) — при добавлении второго
 * добываемого ресурса (электричество, электростанция) переключение по
 * resourceType здесь нужно будет обобщить, сейчас обобщать нечего.
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

    /** Тот же реестр playerId -> PlayerResources, что и в GameServer — передаётся по ссылке, не копируется. */
    private final Map<Integer, PlayerResources> resourcesByPlayer;

    public ResourceExtractionSystem(Map<Integer, PlayerResources> resourcesByPlayer) {
        super(Family.all(ResourceExtractorComponent.class, OwnerComponent.class).get(), 3);
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

        extractor.progress += deltaTime * GameConstants.IRON_EXTRACTION_RATE;
        while (extractor.progress >= 1f) {
            extractor.progress -= 1f;
            switch (extractor.resourceType) {
                case IRON:
                    resources.iron++;
                    break;
                case ELECTRICITY:
                    resources.electricity++;
                    break;
            }
        }
    }
}
