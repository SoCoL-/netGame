package ru.socol.supreme.shared.components;

import com.badlogic.ashley.core.Component;
import com.badlogic.gdx.utils.Pool;

/**
 * Приказ строителю засыпать воронку craterId (см. Crater) — ПКМ по воронке
 * при выделенных строителях. Исполняет CraterSystem: строитель подъезжает
 * на buildRadius к центру воронки и засыпает её; несколько строителей
 * засыпают одну воронку быстрее. Как и остальные приказы строителя,
 * снимается любым другим приказом.
 */
public class FillCraterOrderComponent implements Component, Pool.Poolable {

    public int craterId;

    /** Строитель уже на месте и засыпает (а не едет к воронке). */
    public boolean inRange;

    @Override
    public void reset() {
        craterId = 0;
        inRange = false;
    }
}
