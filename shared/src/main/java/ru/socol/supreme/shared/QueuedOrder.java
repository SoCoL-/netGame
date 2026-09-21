package ru.socol.supreme.shared;

/**
 * Один отложенный приказ в очереди юнита (OrderQueueComponent) — не сам
 * компонент, а элемент списка внутри него. Какие поля актуальны, решает
 * type: для MOVE — x/y, для ATTACK — targetUnitId, для BUILD, REPAIR и
 * COLLECT — targetBuildingUnitId (остальные поля просто не используются
 * для этого типа, как и везде в проекте — не отдельные подклассы на
 * каждый вид приказа ради всего двух-трёх полей).
 */
public class QueuedOrder {

    public enum Type {
        MOVE,
        ATTACK,
        BUILD,
        REPAIR,
        COLLECT
    }

    public Type type;

    /** Актуально только для MOVE. */
    public float x;
    public float y;

    /** Актуально только для ATTACK. */
    public int targetUnitId;

    /** Актуально только для BUILD, REPAIR и COLLECT — для последнего это unitId обломков (WreckComponent), не настоящего здания, но поле то же самое, отдельного заводить не стали. */
    public int targetBuildingUnitId;
}
