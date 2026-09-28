package ru.socol.supreme.shared.network.messages;

/**
 * Клиент -> сервер: выстрелить из своей артиллерийской башни
 * buildingUnitId в точку (x, y) — ПКМ по карте при выделенной башне (см.
 * GameScreen). Видимость точки не проверяется: стрелять можно и в туман
 * войны. Сервер проверяет дальность, наличие готового снаряда и
 * электричества на выстрел (см. GameServer.handleArtilleryFire) и при
 * отказе отвечает ErrorResponse.
 */
public class ArtilleryFireRequest {

    public int buildingUnitId;
    public float x;
    public float y;

    public ArtilleryFireRequest() {
    }
}
