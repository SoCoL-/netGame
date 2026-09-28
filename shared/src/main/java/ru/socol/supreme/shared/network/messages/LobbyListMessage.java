package ru.socol.supreme.shared.network.messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Сервер -> клиенту(ам), которые сейчас в браузере лобби (не внутри
 * какого-то конкретного лобби и не в матче) — полный текущий список,
 * пересылается заново целиком при любом изменении (лобби создано или
 * опустело и удалено, у кого-то изменилось число занятых слотов или
 * фаза), а не дельтой — списков всегда мало (по одной записи на игру,
 * не на юнита), пересылать целиком дешевле, чем городить дифф-протокол.
 */
public class LobbyListMessage {

    public List<LobbySummary> lobbies = new ArrayList<>();

    public LobbyListMessage() {
    }
}
