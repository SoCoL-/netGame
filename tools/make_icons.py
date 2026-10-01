#!/usr/bin/env python3
"""
Рисует иконку месторождения железа (assets/icons/iron-deposit.png) —
программная графика: три рыжих камня с гранями и тёмной обводкой, уже в
цвете (в игре не красится). Стратегические значки зданий и юнитов — из
набора art/strategic-icons, см. tools/import_strategic_icons.py.

Рисуется в 4 раза крупнее и уменьшается — так края сглажены.
Координаты фигур — в квадрате 100x100.

  python3 tools/make_icons.py assets/icons

Нужен Pillow: pip install pillow
"""
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


def finish(img):
    return img.resize((SIZE, SIZE), Image.LANCZOS)


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
    iron_deposit().save(os.path.join(out, 'iron-deposit.png'))
    print(f'{out}/iron-deposit.png')


if __name__ == '__main__':
    main()
