#!/usr/bin/env python3
"""
Готовит спрайты растительности (вид сверху) для игры: обрезает
прозрачные поля, обнуляет почти невидимые пиксели (alpha <= 8 — остатки
генерации по краям), вписывает растение в квадрат степени двойки (128 —
кусты, 256 — деревья; так работают mip-уровни на любом GPU), растение —
по центру, пропорции сохраняются.

Печатает для каждого спрайта мировой размер квадрата: исходная "видимая
ширина" (sprites.json набора, suggestedVisibleWidthWorldUnits) пересчитана
на бо́льшую сторону растения — эти числа вписываются в VegetationType.

  python3 tools/bake_vegetation.py vegetation-sprites assets/vegetation

Нужен Pillow: pip install pillow
"""
import json
import os
import sys

from PIL import Image

SIZES = {'bush': 128, 'tree': 256}


def main():
    source, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(source, 'sprites.json'), encoding='utf-8') as f:
        sprites = json.load(f)['sprites']
    for sprite in sprites:
        image = Image.open(os.path.join(source, sprite['file'])).convert('RGBA')
        alpha = image.getchannel('A').point(lambda v: 0 if v <= 8 else v)
        image.putalpha(alpha)
        box = alpha.getbbox()
        plant = image.crop(box)
        side = SIZES[sprite['file'].split('-')[0]]
        longest = max(plant.size)
        scale = side / longest
        plant = plant.resize((max(1, round(plant.width * scale)), max(1, round(plant.height * scale))), Image.LANCZOS)
        canvas = Image.new('RGBA', (side, side), (0, 0, 0, 0))
        canvas.alpha_composite(plant, ((side - plant.width) // 2, (side - plant.height) // 2))
        name = os.path.splitext(sprite['file'])[0]
        canvas.save(os.path.join(out, name + '.png'))
        world = sprite['suggestedVisibleWidthWorldUnits'] * longest / (box[2] - box[0])
        print(f'{name}: {side}px, мировой размер квадрата {world:.1f}')


if __name__ == '__main__':
    main()
