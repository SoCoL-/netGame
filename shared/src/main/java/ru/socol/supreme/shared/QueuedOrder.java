package ru.socol.supreme.shared;

/**
 * Один отложенный приказ в очереди юнита (OrderQueueComponent) — не сам
 * компонент, а элемент списка внутри него. Какие поля актуальны, решает
 * type: для MOVE — x/y, для ATTACK — targetUnitId, для BUILD —
 * targetBuildingUnitId (остальные поля просто не используются для этого
 * типа, как и везде в проекте — не отдельные подклассы на каждый вид
 * приказа ради всего двух-трёх полей).
 */
public class QueuedOrder {

    public enum Type {
        MOVE,
        ATTACK,
        BUILD
    }

    public Type type;

    /** Актуально только для MOVE. */
    public float x;
    public float y;

    /** Актуально только для ATTACK. */
    public int targetUnitId;

    /** Актуально только для BUILD. */
    public int targetBuildingUnitId;
}
