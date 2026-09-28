package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: строителю builderUnitId засыпать воронку craterId
 * (ПКМ по воронке при выделенных строителях, по запросу на каждого
 * строителя). См. GameServer.handleFillCrater и CraterSystem.
 */
public class FillCraterRequest {

    public int builderUnitId;
    public int craterId;

    public FillCraterRequest() {
    }
}
