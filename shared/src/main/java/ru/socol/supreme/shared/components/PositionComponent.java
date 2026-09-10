package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Pool;

/** Текущая позиция юнита в мировых координатах. */
public class PositionComponent implements Component, Pool.Poolable {

    public final Vector2 position = new Vector2();

    @Override
    public void reset() {
        position.set(0f, 0f);
    }
}
