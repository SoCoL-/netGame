package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;
import ru.socol.supreme.shared.BuildingType;

/**
 * Маркер "это именные обломки уничтоженного ЗДАНИЯ" (GameServer
 * .spawnBuildingRubble) — появляются как при уничтожении в бою, так и при
 * добровольном сносе (GameServer.handleDemolishBuilding). Сущность при этом,
 * как и обычные обломки юнита (см. javadoc WreckComponent), несёт
 * BuildingComponent с type == BuildingType.WRECK — переиспользует всю
 * инфраструктуру препятствий/коллизий/сети, написанную для WRECK. Этот
 * компонент — дополнительный маркер поверх WRECK, хранящий, каким именно
 * зданием были эти обломки: отсюда "именные".
 *
 * Как и у обычных обломков, оставшееся собираемое железо хранится в
 * HealthComponent той же сущности (currentHealth/maxHealth — см. её же
 * javadoc в WreckComponent, то же самое рассуждение верно и тут). Доля от
 * исходной стоимости здания — GameConstants.BUILDING_RUBBLE_IRON_PERCENT,
 * фиксированная (не случайный разброс, как у обломков юнита) — см. её
 * javadoc, почему.
 *
 * Именные обломки дают ДВЕ независимые ценности, но игрок может забрать
 * только одну — какая наступит раньше:
 *   1. Собрать их железо строителем (ScavengeSystem, как у обычных
 *      обломков) — сущность исчезает, когда железо кончится, обычным путём.
 *   2. Начать строить здание ТОГО ЖЕ типа (originalType) на этом месте —
 *      GameServer.spawnBuilding находит совпадающие обломки
 *      (findMatchingRubble) и поглощает их целиком (removeRubble) сразу при
 *      начале стройки, отдав взамен скидку GameConstants
 *      .BUILDING_RUBBLE_REBUILD_DISCOUNT на время и суммарные ресурсы
 *      постройки (см. ConstructionComponent.costMultiplier). Обломки при
 *      этом поглощаются целиком — их железо (пункт 1) пропадает
 *      невозвратно, а не суммируется со скидкой (пункт 2).
 * Обломки ДРУГОГО типа здания или юнита по-прежнему блокируют место, как
 * обычное препятствие (см. BuildingPlacement.canPlaceBuilding) — точечный
 * обход не срабатывает для чужого originalType.
 *
 * В отличие от WreckComponent, у обломков здания нет своего аналога
 * underwater — здание не может быть "над водой" на месте, где стояло.
 */
public class BuildingRubbleComponent implements Component, Pool.Poolable {

    /**
     * Тип здания, которым были эти обломки, пока оно не было уничтожено —
     * фиксируется один раз при создании (GameServer.spawnBuildingRubble) и
     * больше не меняется, ровно как WreckComponent.underwater (см. её же
     * javadoc): на клиенте (EntityFactory) при создании сущности читается
     * из UnitSnapshot.rubbleOriginalBuildingType и никогда не обновляется
     * на уже существующей сущности в applySnapshot.
     */
    public BuildingType originalType;

    public BuildingRubbleComponent() {
    }

    @Override
    public void reset() {
        originalType = null;
    }
}
