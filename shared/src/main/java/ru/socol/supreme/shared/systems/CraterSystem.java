package ru.socol.supreme.shared.systems;

import com.badlogic.ashley.core.ComponentMapper;
import com.badlogic.ashley.core.Engine;
import com.badlogic.ashley.core.Entity;
import com.badlogic.ashley.core.EntitySystem;
import com.badlogic.ashley.core.Family;
import com.badlogic.ashley.utils.ImmutableArray;
import ru.socol.supreme.shared.GameConstants;
import ru.socol.supreme.shared.UnitDefinitions;
import ru.socol.supreme.shared.UnitType;
import ru.socol.supreme.shared.components.AircraftComponent;
import ru.socol.supreme.shared.components.DirectionComponent;
import ru.socol.supreme.shared.components.FillCraterOrderComponent;
import ru.socol.supreme.shared.components.PositionComponent;
import ru.socol.supreme.shared.components.UnitTypeComponent;
import ru.socol.supreme.shared.craters.Crater;
import ru.socol.supreme.shared.craters.CraterField;
import ru.socol.supreme.shared.pathfinding.Pathfinding;

import java.util.HashMap;
import java.util.Map;

/**
 * Жизнь воронок матча (CraterField) на каждом тике:
 *
 * 1. Зарастание — CraterField.update старит воронки и убирает заросшие.
 * 2. Замедление — наземным юнитам внутри воронки ставит
 *    DirectionComponent.speedMultiplier = CRATER_SPEED_MULTIPLIER (иначе 1).
 *    Авиацию не касается. Приоритет 5 — раньше MovementSystem (10), чтобы
 *    множитель действовал уже в этом тике.
 * 3. Засыпка — строители с FillCraterOrderComponent едут к воронке, а
 *    оказавшись в buildRadius от её центра, засыпают: каждый добавляет
 *    свои секунды к Crater.fillProgress; когда набралось Crater.fillTime,
 *    воронка исчезает. Приказ снимается и когда воронка засыпана, и когда
 *    она заросла сама.
 *
 * Живёт в shared, но запускается только на сервере.
 */
public class CraterSystem extends EntitySystem {

    private static final Family GROUND_MOVERS =
            Family.all(PositionComponent.class, DirectionComponent.class).exclude(AircraftComponent.class).get();
    private static final Family FILLERS =
            Family.all(FillCraterOrderComponent.class, PositionComponent.class, DirectionComponent.class).get();

    private static final ComponentMapper<PositionComponent> POSITION = ComponentMapper.getFor(PositionComponent.class);
    private static final ComponentMapper<DirectionComponent> DIRECTION = ComponentMapper.getFor(DirectionComponent.class);
    private static final ComponentMapper<FillCraterOrderComponent> FILL_ORDER =
            ComponentMapper.getFor(FillCraterOrderComponent.class);
    private static final ComponentMapper<UnitTypeComponent> UNIT_TYPE = ComponentMapper.getFor(UnitTypeComponent.class);

    private final CraterField craterField;
    private final Pathfinding pathfinding;
    private ImmutableArray<Entity> groundMovers;
    private ImmutableArray<Entity> fillers;

    /** craterId -> сколько строителей засыпают его в этом тике. */
    private final Map<Integer, Integer> fillersByCrater = new HashMap<>();

    public CraterSystem(CraterField craterField, Pathfinding pathfinding) {
        super(5);
        this.craterField = craterField;
        this.pathfinding = pathfinding;
    }

    @Override
    public void addedToEngine(Engine engine) {
        groundMovers = engine.getEntitiesFor(GROUND_MOVERS);
        fillers = engine.getEntitiesFor(FILLERS);
    }

    @Override
    public void update(float deltaTime) {
        craterField.update(deltaTime);
        applySlowdown();
        fillCraters(deltaTime);
    }

    private void applySlowdown() {
        boolean anyCraters = !craterField.all().isEmpty();
        for (Entity entity : groundMovers) {
            DirectionComponent direction = DIRECTION.get(entity);
            PositionComponent position = POSITION.get(entity);
            direction.speedMultiplier = anyCraters && craterField.craterAt(position.position.x, position.position.y) != null
                    ? GameConstants.CRATER_SPEED_MULTIPLIER : 1f;
        }
    }

    private void fillCraters(float deltaTime) {
        fillersByCrater.clear();
        // Копия — снятие компонента меняет семейство прямо во время обхода.
        Entity[] snapshot = fillers.toArray(Entity.class);
        for (Entity builder : snapshot) {
            FillCraterOrderComponent order = FILL_ORDER.get(builder);
            DirectionComponent direction = DIRECTION.get(builder);
            Crater crater = craterField.find(order.craterId);
            if (crater == null) {
                builder.remove(FillCraterOrderComponent.class); // засыпана или заросла
                direction.moving = false;
                continue;
            }
            UnitTypeComponent unitType = UNIT_TYPE.get(builder);
            float range = UnitDefinitions.buildRadiusFor(unitType != null ? unitType.type : UnitType.BUILDER);
            PositionComponent position = POSITION.get(builder);
            if (position.position.dst(crater.x, crater.y) > range) {
                order.inRange = false;
                if (!direction.moving) {
                    pathfinding.setDestination(builder, position, direction, crater.x, crater.y);
                }
                continue;
            }
            order.inRange = true;
            direction.moving = false;
            fillersByCrater.merge(crater.id, 1, Integer::sum);
        }

        for (Map.Entry<Integer, Integer> entry : fillersByCrater.entrySet()) {
            Crater crater = craterField.find(entry.getKey());
            crater.fillProgress += deltaTime * entry.getValue();
            if (crater.fillProgress >= crater.fillTime()) {
                craterField.remove(crater);
            }
        }
    }
}
