package ru.socol.supreme.shared.network.messages;

/** Сервер -> клиент: сообщение об отказе, например "лимит юнитов исчерпан". */
public class ErrorResponse {

    public String message;

    public ErrorResponse() {
    }

    public ErrorResponse(String message) {
        this.message = message;
    }
}
