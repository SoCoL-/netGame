package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;

/** Владелец юнита: 0 или 1, т.к. в игре ровно 2 игрока (см. GameConstants.MAX_PLAYERS). */
public class OwnerComponent implements Component {

    public int playerId;

    public OwnerComponent() {
    }

    public OwnerComponent(int playerId) {
        this.playerId = playerId;
    }
}
