package ru.socol.supreme.shared.network.messages;

/**
 * Туман войны одного игрока — плоская сетка клеток (см. GameConstants
 * .FOG_GRID_WIDTH/HEIGHT/CELL_SIZE), revealed[y * FOG_GRID_WIDTH + x] =
 * true, если эта клетка сейчас видна или недавно (в пределах
 * FOG_REVEAL_GRACE_PERIOD) была видна хотя бы одному юниту или зданию
 * этого игрока — см. GameServer.updateFogOfWar.
 *
 * Тот же приём, что и у PlayerResources в WorldSnapshot.playerResources:
 * шлётся для ОБОИХ игроков разом в WorldSnapshot.fog, не отдельно на
 * каждое соединение — клиент сам выбирает свою запись по playerId
 * (GameScreen.onWorldSnapshot) и игнорирует чужую, а не получает только
 * то, что ему положено видеть. Плоский boolean[], а не boolean[][] —
 * Kryo сериализует одномерные примитивные массивы без отдельной
 * регистрации, двумерные потребовали бы её.
 */
public class FogSnapshot {

    public int playerId;
    public boolean[] revealed = new boolean[0];

    public FogSnapshot() {
    }

    public FogSnapshot(int playerId) {
        this.playerId = playerId;
    }
}
