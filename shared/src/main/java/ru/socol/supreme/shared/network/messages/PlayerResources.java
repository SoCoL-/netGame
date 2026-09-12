package ru.socol.supreme.shared.network.messages;

/**
 * Ресурсы одного игрока. На сервере GameServer держит по одному такому
 * объекту на игрока (map playerId -> PlayerResources) — это же авторитетное
 * состояние; тот же класс переиспользуется и как payload внутри
 * WorldSnapshot.playerResources, без отдельного DTO для сети — как и
 * ProductionComponent, который тоже служит и серверным состоянием, и
 * тем, что видит клиент.
 *
 * Пока ничего не добывает и не тратит эти числа — зданий добычи ещё нет
 * (см. ResourceType) — оба поля всегда 0. Само хранение и рассылка уже
 * готовы, чтобы будущим зданиям добычи было куда писать.
 */
public class PlayerResources {

    public int playerId;
    public int iron;
    public int electricity;

    public PlayerResources() {
    }

    public PlayerResources(int playerId) {
        this.playerId = playerId;
    }
}
