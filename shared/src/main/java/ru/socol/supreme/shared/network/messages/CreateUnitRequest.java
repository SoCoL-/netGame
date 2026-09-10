package ru.socol.supreme.shared.network.messages;

/** Клиент -> сервер: "создай мне юнит в этой точке" (если не превышен лимит в 5). */
public class CreateUnitRequest {

    public float x;
    public float y;

    public CreateUnitRequest() {
    }
}
