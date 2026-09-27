package ru.socol.supreme.components;

import com.badlogic.ashley.core.Component;

/**
 * Чисто клиентский, презентационный компонент — направление наведения
 * башни наземного юнита, как единичный вектор (то же соглашение, что и у
 * DirectionComponent.direction для корпуса). Сервер считает его из
 * своего TurretComponent.angleRadians и шлёт в UnitSnapshot.turretDirX/
 * turretDirY, EntityFactory копирует их сюда каждый снапшот. Используется
 * только для отрисовки треугольника-башни отдельно от прямоугольного
 * корпуса (RenderSystem) и для смещения точки вылета визуального снаряда
 * (GameScreen.onProjectileFired). (0, 0) у типов без башни (авиация) —
 * там этот компонент вообще не добавляется, см. EntityFactory.
 */
public class TurretDisplayComponent implements Component {
    public float dirX;
    public float dirY;
}
