package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Маркер "это обломки погибшего юнита, а не настоящее здание"
 * (GameServer.spawnWreck) — сущность при этом всё равно несёт
 * BuildingComponent с type == BuildingType.WRECK, чтобы бесплатно
 * переиспользовать всю инфраструктуру препятствий/коллизий, уже
 * написанную для зданий (Pathfinding.addBuildingObstacle/
 * removeBuildingObstacle, CollisionSystem — обломки нельзя пройти
 * насквозь, как и любое здание, см. BuildingSizes). WreckComponent —
 * дополнительный маркер поверх этого, чтобы отличить настоящее здание
 * от обломков там, где это важно (см. ниже).
 *
 * Оставшееся железо, которое ещё можно собрать, хранится НЕ отдельным
 * полем тут, а прямо в HealthComponent той же сущности — currentHealth
 * значит "сколько железа осталось", maxHealth — "сколько было
 * изначально". Это сознательное переиспользование, а не путаница:
 * так бесплатно достаются сетевой снапшот (health/maxHealth и так
 * пересылаются для любой сущности, см. GameServer.broadcastSnapshot) и
 * прогресс-бар на клиенте (RenderSystem.drawHealthBar уже умеет рисовать
 * долю current/max, ему всё равно, что именно она значит) — не
 * понадобилось ни новых полей в UnitSnapshot, ни отдельной отрисовки
 * бара специально для обломков. Из этого следует, что здоровье обломков
 * может уменьшать только ScavengeSystem (сбор железа строителем),
 * никогда не бой: у обломков нет владельца (OwnerComponent.playerId ==
 * GameConstants.NEUTRAL_OWNER_ID), и без специальной защиты они
 * выглядели бы как "ничьи, а значит вражеские" сразу для обоих игроков.
 * Эта защита — явная проверка на WreckComponent в трёх местах:
 * AggroSystem (автобой не выбирает обломки целью), GameServer
 * .startAttackOrder и GameScreen.isEnemy (ручной приказ "атаковать" по
 * обломкам тоже невозможен) — см. их javadoc.
 */
public class WreckComponent implements Component, Pool.Poolable {

    /**
     * Обломки лежат под водой (юнит, обычно авиация, был уничтожен над
     * водой и упал в неё — см. GameServer.spawnWreck) — сейчас влияет
     * только на отрисовку (RenderSystem рисует их темнее/синее обычных).
     * Строители пока не умеют заезжать в воду вообще (Pathfinding/
     * CollisionSystem считают её препятствием, как и любое здание), так
     * что подводные обломки физически недостижимы, пока не появится
     * отдельная поддержка "строитель умеет заходить в воду" — это поле
     * уже готово к тому моменту, менять формат снапшота не придётся.
     */
    public boolean underwater;

    public WreckComponent() {
    }

    @Override
    public void reset() {
        underwater = false;
    }
}
