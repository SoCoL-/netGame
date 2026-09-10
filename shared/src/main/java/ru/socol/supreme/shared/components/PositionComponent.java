package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.math.Vector2;

/** Текущая позиция юнита в мировых координатах. */
public class PositionComponent implements Component {

    public final Vector2 position = new Vector2();
}
