package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;

/**
 * Запас здоровья юнита. На сервере это авторитетное значение, которое
 * уменьшает {@link ru.socol.supreme.shared.systems.CombatSystem} при попадании;
 * на клиенте — просто последнее значение из снапшота, нужное только для
 * отрисовки полоски здоровья.
 */
public class HealthComponent implements Component {

    public int currentHealth;
    public int maxHealth;

    public HealthComponent() {
    }

    public HealthComponent(int maxHealth) {
        this.maxHealth = maxHealth;
        this.currentHealth = maxHealth;
    }
}
