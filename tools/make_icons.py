#!/usr/bin/env python3
"""
Рисует иконки стратегической карты (assets/icons/*.png) — временная
программная графика, пока нет рисованных иконок; любую можно заменить
своей картинкой с тем же именем и размером.

Что получается:
  * badge-building.png / badge-unit.png — подложки (квадрат со скруглением
    для зданий, круг для юнитов): белая заливка + тёмная обводка. В игре
    заливка красится цветом игрока (обводка остаётся тёмной — умножение
    на цвет чёрное не меняет).
  * <тип>.png — пиктограмма здания или юнита: белая на прозрачном, в игре
    красится одним цветом (GameColor.ICON_GLYPH) и рисуется поверх подложки.
    Имя — BuildingType/UnitType в нижнем регистре через дефис.
  * iron-deposit.png — месторождение железа, уже в цвете (не красится).

Всё рисуется в 4 раза крупнее и уменьшается — так края сглажены.
Координаты фигур — в квадрате 100x100.

  python3 tools/make_icons.py assets/icons

Нужен Pillow: pip install pillow
"""
import math
import os
import sys

from PIL import Image, ImageDraw

SIZE = 64
SS = 4
CANVAS = SIZE * SS
K = CANVAS / 100.0


def p(*pts):
    return [(x * K, y * K) for x, y in pts]


def box(x0, y0, x1, y1):
    return [x0 * K, y0 * K, x1 * K, y1 * K]


def w(v):
    return max(1, int(v * K))


def rotated_rect(cx, cy, length, width, degrees):
    a = math.radians(degrees)
    dx, dy = math.cos(a), math.sin(a)
    px, py = -dy, dx
    hl, hw = length / 2, width / 2
    return p((cx + dx * hl + px * hw, cy + dy * hl + py * hw), (cx + dx * hl - px * hw, cy + dy * hl - py * hw),
             (cx - dx * hl - px * hw, cy - dy * hl - py * hw), (cx - dx * hl + px * hw, cy - dy * hl + py * hw))


# ---- Пиктограммы: рисуют маску (255 — пиктограмма, 0 — стереть) ----

def home(d):
    d.polygon(p((50, 16), (86, 48), (14, 48)), fill=255)
    d.rectangle(box(24, 46, 76, 84), fill=255)
    d.rectangle(box(43, 60, 57, 84), fill=0)  # дверь


def archer_barracks(d):
    for y in (22, 46):  # шевроны — казарма
        d.polygon(p((16, y), (50, y + 20), (84, y), (84, y + 16), (50, y + 36), (16, y + 16)), fill=255)


def aircraft_factory(d):
    plane(d, 50, 44, 0.9)
    d.rectangle(box(16, 80, 84, 88), fill=255)  # "завод" — основание


def plane(d, cx, cy, s):
    def q(x, y):
        return (cx + x * s, cy + y * s)
    d.polygon(p(q(-6, -36), q(6, -36), q(6, 30), q(-6, 30)), fill=255)  # фюзеляж
    d.polygon(p(q(-40, 8), q(-6, -10), q(6, -10), q(40, 8), q(40, 16), q(-40, 16)), fill=255)  # крылья
    d.polygon(p(q(-18, 36), q(-6, 24), q(6, 24), q(18, 36), q(18, 42), q(-18, 42)), fill=255)  # хвост
    d.ellipse(box(*q(-6, -44), *q(6, -30)), fill=255)


def iron_mine(d):
    d.polygon(rotated_rect(40, 54, 70, 11, -64), fill=255)  # рукоять кирки — от левого нижнего угла к середине головки
    # головка кирки — дуга поперёк рукояти
    d.arc(box(12, 14, 88, 90), start=205, end=335, fill=255, width=w(12))


def power_plant(d):
    d.polygon(p((58, 10), (24, 56), (46, 56), (38, 90), (76, 40), (54, 40), (64, 10)), fill=255)


def iron_storage(d):
    def ingot(x, y):
        d.polygon(p((x + 5, y), (x + 29, y), (x + 34, y + 16), (x, y + 16)), fill=255)
    ingot(16, 66)  # пирамида слитков
    ingot(50, 66)
    ingot(33, 46)
    ingot(33, 26)


def electricity_storage(d):
    d.rectangle(box(22, 26, 78, 84), fill=255)
    d.rectangle(box(38, 16, 62, 26), fill=255)
    d.polygon(p((54, 34), (38, 58), (50, 58), (44, 76), (62, 50), (50, 50), (58, 34)), fill=0)  # молния стёрта


def turret(d):
    d.ellipse(box(22, 38, 70, 86), fill=255)
    d.polygon(rotated_rect(62, 38, 44, 12, -45), fill=255)


def artillery(d):
    d.pieslice(box(14, 50, 70, 106), start=180, end=360, fill=255)  # основание
    d.polygon(rotated_rect(58, 44, 70, 13, -40), fill=255)  # длинный ствол
    d.rectangle(box(10, 76, 74, 86), fill=255)


def warrior(d):
    d.polygon(p((50, 12), (84, 24), (80, 58), (50, 90), (20, 58), (16, 24)), fill=255)  # щит
    d.polygon(p((50, 24), (72, 32), (69, 56), (50, 76), (31, 56), (28, 32)), fill=0)
    d.polygon(p((50, 30), (64, 36), (62, 54), (50, 68), (38, 54), (36, 36)), fill=255)


def archer(d):
    d.ellipse(box(18, 18, 82, 82), fill=255)
    d.ellipse(box(28, 28, 72, 72), fill=0)
    d.ellipse(box(42, 42, 58, 58), fill=255)
    for r in (box(46, 8, 54, 32), box(46, 68, 54, 92), box(8, 46, 32, 54), box(68, 46, 92, 54)):
        d.rectangle(r, fill=255)


def builder(d):
    d.polygon(rotated_rect(44, 56, 60, 13, -45), fill=255)  # рукоять ключа
    d.ellipse(box(52, 12, 88, 48), fill=255)
    d.polygon(rotated_rect(78, 22, 26, 13, -45), fill=0)  # зев ключа


def scout(d):
    d.chord(box(10, 22, 90, 104), start=200, end=340, fill=255)
    d.chord(box(10, -4, 90, 78), start=20, end=160, fill=255)
    d.ellipse(box(36, 36, 64, 64), fill=0)
    d.ellipse(box(43, 43, 57, 57), fill=255)


def attack_aircraft(d):
    d.polygon(p((50, 10), (58, 40), (88, 70), (88, 78), (56, 66), (54, 80), (64, 90), (36, 90), (46, 80), (44, 66),
                (12, 78), (12, 70), (42, 40)), fill=255)


def anti_air(d):
    for x in (34, 66):  # две ракеты вверх
        d.polygon(p((x, 10), (x + 8, 24), (x + 8, 72), (x + 14, 84), (x - 14, 84), (x - 8, 72), (x - 8, 24)), fill=255)


GLYPHS = {
    'home': home, 'archer-barracks': archer_barracks, 'aircraft-factory': aircraft_factory,
    'iron-mine': iron_mine, 'power-plant': power_plant, 'iron-storage': iron_storage,
    'electricity-storage': electricity_storage, 'turret': turret, 'artillery': artillery,
    'warrior': warrior, 'archer': archer, 'builder': builder, 'scout': scout,
    'attack-aircraft': attack_aircraft, 'anti-air': anti_air,
}


def finish(img):
    return img.resize((SIZE, SIZE), Image.LANCZOS)


def glyph(draw_fn):
    mask = Image.new('L', (CANVAS, CANVAS), 0)
    draw_fn(ImageDraw.Draw(mask))
    img = Image.new('RGBA', (CANVAS, CANVAS), (255, 255, 255, 0))
    img.putalpha(mask)
    return finish(img)


def badge(shape):
    img = Image.new('RGBA', (CANVAS, CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    outline = (25, 25, 25, 255)
    if shape == 'building':
        d.rounded_rectangle(box(3, 3, 97, 97), radius=14 * K, fill=outline)
        d.rounded_rectangle(box(10, 10, 90, 90), radius=9 * K, fill=(255, 255, 255, 255))
    else:
        d.ellipse(box(3, 3, 97, 97), fill=outline)
        d.ellipse(box(10, 10, 90, 90), fill=(255, 255, 255, 255))
    return finish(img)


def iron_deposit():
    img = Image.new('RGBA', (CANVAS, CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    outline = (30, 22, 16, 255)
    rocks = [  # грани: (многоугольник, цвет тёмной части, цвет светлой грани)
        ([(14, 70), (26, 44), (48, 38), (58, 60), (46, 86), (22, 86)], (120, 72, 40), (178, 112, 62)),
        ([(44, 58), (58, 26), (80, 22), (90, 50), (78, 80), (56, 80)], (140, 84, 46), (205, 132, 72)),
        ([(34, 40), (42, 14), (60, 12), (64, 32), (52, 44)], (104, 64, 36), (160, 100, 56)),
    ]
    for poly, dark, light in rocks:
        d.polygon(p(*poly), fill=outline)
        cx = sum(x for x, _ in poly) / len(poly)
        cy = sum(y for _, y in poly) / len(poly)
        inner = [(cx + (x - cx) * 0.8, cy + (y - cy) * 0.8) for x, y in poly]
        d.polygon(p(*inner), fill=dark + (255,))
        facet = inner[:3] + [(cx, cy)]
        d.polygon(p(*facet), fill=light + (255,))
    return finish(img)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'assets/icons'
    os.makedirs(out, exist_ok=True)
    badge('building').save(os.path.join(out, 'badge-building.png'))
    badge('unit').save(os.path.join(out, 'badge-unit.png'))
    for name, fn in GLYPHS.items():
        glyph(fn).save(os.path.join(out, name + '.png'))
    iron_deposit().save(os.path.join(out, 'iron-deposit.png'))
    print(f'{out}: {len(GLYPHS) + 3} иконок')


if __name__ == '__main__':
    main()
