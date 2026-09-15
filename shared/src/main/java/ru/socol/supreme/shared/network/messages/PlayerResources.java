package ru.socol.supreme.shared.network.messages;

/**
 * Ресурсы одного игрока. На сервере GameServer держит по одному такому
 * объекту на игрока (map playerId -> PlayerResources) — это же авторитетное
 * состояние; тот же класс переиспользуется и как payload внутри
 * WorldSnapshot.playerResources, без отдельного DTO для сети — как и
 * ProductionComponent, который тоже служит и серверным состоянием, и
 * тем, что видит клиент.
 *
 * float, а не int: потребление и добыча считаются непрерывно, в
 * единицах/сек (ResourceExtractionSystem, ProductionSystem — здание
 * добычи или казарма стрелков могут тратить/добывать и дробную величину
 * за тик, например 0.5 электричества/сек в простое у казармы). Клиент
 * показывает игроку округлённое до целого значение (GameScreen
 * .drawResourcePanel) — дробная точность нужна только для внутреннего
 * счёта, не для интерфейса.
 */
public class PlayerResources {

    public int playerId;
    public float iron;
    public float electricity;

    /**
     * Чистое изменение ресурса в секунду — сумма добычи и всех трат
     * (постоянное потребление казармы, стоимость производимого юнита)
     * за интервал между двумя последними рассылками снапшота. Не
     * отдельный подсчёт "по формуле из текущего состояния зданий", а
     * реально измеренная разница (GameServer.broadcastSnapshot) — так
     * это гарантированно совпадает с тем, что на самом деле произошло с
     * счётом игрока, включая моменты, когда производство юнита стоит
     * из-за нехватки другого ресурса (см. ProductionSystem) и в этот
     * момент реально ничего не тратится, хотя по "теоретической ставке"
     * должно было бы.
     */
    public float ironRate;
    public float electricityRate;

    public PlayerResources() {
    }

    public PlayerResources(int playerId) {
        this.playerId = playerId;
    }
}
