#!/usr/bin/env python3
"""
Готовит спрайт юнита из двух слоёв концепта (корпус + башня) для игры.

Что делает с каждым слоем:
  * обнуляет шум альфы (<= 15, остаток удаления фона), почти непрозрачное
    тело (>= 240) делает сплошным;
  * выносит бирюзовые вставки в отдельную маску командного цвета
    (<name>-<layer>-team.png, оттенки серого) — в игре она красится цветом
    игрока, а в основе (<name>-<layer>.png) на их месте остаётся тёмно-серый;
  * обрезает прозрачные поля и уменьшает ОБА слоя одним коэффициентом
    (корпус до --hull-height пикселей);
  * печатает точки вращения в координатах готовых текстур (Y вверх, как в
    libGDX) — их нужно вписать в код отрисовки (см. ArcherSprite).

Пример (стрелок "Клин"):
  python3 tools/bake_unit_sprite.py --name archer \
      --hull klin-hull.png --hull-pivot 615 665 \
      --turret klin-turret.png --turret-pivot 615 790 \
      --out assets/units

Нужен Pillow: pip install pillow
"""
import argparse
import colorsys
import os

from PIL import Image


def split_layer(path):
    image = Image.open(path).convert('RGBA')
    source = image.load()
    base = Image.new('RGBA', image.size)
    team = Image.new('RGBA', image.size)
    base_px = base.load()
    team_px = team.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = source[x, y]
            if a <= 15:
                continue
            if a >= 240:
                a = 255
            hue, saturation, value = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            hue *= 360
            weight = 0.0
            if 165 <= hue <= 215 and value > 0.25:
                weight = max(0.0, min(1.0, (saturation - 0.30) / 0.25))
            gray = 0.299 * r + 0.587 * g + 0.114 * b
            base_px[x, y] = tuple(int(c * (1 - weight) + gray * 0.45 * weight) for c in (r, g, b)) + (a,)
            if weight > 0:
                lum = int(min(255, gray * 1.15))
                team_px[x, y] = (lum, lum, lum, int(a * weight))
    return base, team


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--name', required=True)
    parser.add_argument('--hull', required=True)
    parser.add_argument('--hull-pivot', type=float, nargs=2, required=True, metavar=('X', 'Y'),
                        help='центр погона на корпусе, пиксели исходника, Y вниз')
    parser.add_argument('--turret', required=True)
    parser.add_argument('--turret-pivot', type=float, nargs=2, required=True, metavar=('X', 'Y'),
                        help='центр вращения башни, пиксели исходника, Y вниз')
    parser.add_argument('--hull-height', type=int, default=128)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()

    hull_base, hull_team = split_layer(args.hull)
    hull_box = hull_base.getbbox()
    scale = args.hull_height / (hull_box[3] - hull_box[1])

    os.makedirs(args.out, exist_ok=True)
    for layer, base, team, pivot in (
            ('hull', hull_base, hull_team, args.hull_pivot),
            ('turret', *split_layer(args.turret), args.turret_pivot)):
        box = base.getbbox()
        size = (round((box[2] - box[0]) * scale), round((box[3] - box[1]) * scale))
        base.crop(box).resize(size, Image.LANCZOS).save(os.path.join(args.out, f'{args.name}-{layer}.png'))
        team = team.crop(box).resize(size, Image.LANCZOS)
        # Осветляем маску уже после ресайза: так командный цвет остаётся насыщенным на мелком масштабе.
        r, g, b, a = team.split()
        r, g, b = (ch.point(lambda v: min(255, int(v * 1.55))) for ch in (r, g, b))
        Image.merge('RGBA', (r, g, b, a)).save(os.path.join(args.out, f'{args.name}-{layer}-team.png'))
        pivot_x = (pivot[0] - box[0]) * scale
        pivot_y = (box[3] - pivot[1]) * scale
        print(f'{layer}: {size[0]}x{size[1]} px, точка вращения (Y вверх): ({pivot_x:.2f}, {pivot_y:.2f})')


if __name__ == '__main__':
    main()
