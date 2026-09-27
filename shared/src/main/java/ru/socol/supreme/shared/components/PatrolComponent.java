package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Pool;

import java.util.ArrayList;
import java.util.List;

/**
 * Приказ на патрулирование: замкнутый список точек, которые игрок
 * расставил кликами по карте после нажатия кнопки Patrol в панели
 * выделения (см. GameScreen — placingPatrol/finishPatrolPlacement,
 * GameServer.handlePatrolUnit). Присутствие этого компонента означает
 * "юнит патрулирует этот маршрут" — PatrolSystem читает и обновляет его
 * каждый тик на сервере, тем же путём, каким OrderQueueSystem запускает
 * обычные приказы (Pathfinding.setDestination к очередной точке), но без
 * дренажа списка: currentIndex просто зацикливается по модулю
 * waypoints.size() при выдаче очередного отрезка пути, ни одна точка не
 * удаляется — маршрут проходится бесконечно, пока не придёт другой приказ.
 *
 * Любой другой приказ (движение, атака, стройка, ремонт, сбор обломков —
 * немедленный ИЛИ поставленный в очередь, см. javadoc GameServer
 * .handlePatrolUnit) снимает этот компонент целиком — "любая другая
 * команда отменяет патруль", без исключений. Обратное тоже верно: сам
 * приказ на патрулирование снимает все прочие активные приказы юнита и
 * очищает его OrderQueueComponent — как и любой другой немедленный
 * приказ в этой игре, только патруль ещё и не поддерживает queue-модификатор
 * вовсе (см. javadoc PatrolUnitRequest, почему).
 *
 * Исключение — автоагрессия (AggroSystem): она сознательно НЕ снимает
 * PatrolComponent, когда патрулирующий юнит сам вступает в бой со
 * встреченным врагом — это не команда игрока, а естественное поведение
 * патруля (отбился и пошёл дальше своим маршрутом, см. её же javadoc).
 * Пока юнит атакует (есть AttackComponent), Family PatrolSystem его
 * исключает — маршрутом распоряжается CombatSystem; как только бой
 * заканчивается (AttackComponent снят), PatrolSystem подхватывает юнита
 * заново и ведёт его к той же точке цикла, на которую он шёл до боя —
 * currentIndex за это время не менялся.
 */
public class PatrolComponent implements Component, Pool.Poolable {

    public final List<Vector2> waypoints = new ArrayList<>();

    /** Индекс точки в waypoints, к которой юнит идёт СЕЙЧАС (или пойдёт, как только освободится) — см. javadoc PatrolSystem.processEntity. */
    public int currentIndex;

    @Override
    public void reset() {
        waypoints.clear();
        currentIndex = 0;
    }
}
