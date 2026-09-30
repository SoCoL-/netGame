#!/usr/bin/env python3
"""
Готовит стратегические значки для игры из набора art/strategic-icons
(SVG по слоям): переименовывает их под типы зданий и юнитов, собирает
недостающие и растеризует в assets/icons/.

На каждый значок — три PNG:
  <имя>-team.png   белая заливка фона — в игре красится цветом игрока;
  <имя>-dark.png   рамка и тёмный знак роли — поверх заливки, без окраски;
  <имя>-light.png  то же со светлым знаком — для тёмных цветов игроков.
Имя — BuildingType/UnitType в нижнем регистре через дефис.

Воина (WARRIOR) в наборе нет — он собирается из рамки наземного юнита
(tank) и знака пехоты с казармы (крест). Значок artillery (наземная
артиллерия) пока не нужен: такого юнита в игре нет.

  python3 tools/import_strategic_icons.py art/strategic-icons assets/icons

Нужны Pillow и cairosvg: pip install pillow cairosvg
"""
import argparse
import io
import os
import re

import cairosvg
from PIL import Image

# id в наборе -> имя в игре
MAPPING = {
    'hq': 'home',
    'barracks': 'archer-barracks',
    'air-factory': 'aircraft-factory',
    'gun-turret': 'turret',
    'artillery-turret': 'artillery',
    'iron-mine': 'iron-mine',
    'iron-storage': 'iron-storage',
    'generator': 'power-plant',
    'battery': 'electricity-storage',
    'aa': 'anti-air',
    'builder': 'builder',
    'tank': 'archer',
    'air-scout': 'scout',
    'attack-aircraft': 'attack-aircraft',
}

# имя в игре -> (id с рамкой, id со знаком роли)
COMPOSED = {
    'warrior': ('tank', 'barracks'),
}

ROLE_MARK = re.compile(r'<g id="role-mark".*?</g>', re.S)


def read(path):
    with open(path, encoding='utf-8') as f:
        return f.read()


def render(svg, size):
    png = cairosvg.svg2png(bytestring=svg.encode('utf-8'), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert('RGBA')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('source', help='папка набора (с layers/)')
    parser.add_argument('out')
    parser.add_argument('--size', type=int, default=64)
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    def layer(kind, icon_id):
        return read(os.path.join(args.source, 'layers', kind, icon_id + '.svg'))

    jobs = {name: (icon_id, None) for icon_id, name in MAPPING.items()}
    jobs.update({name: pair for name, pair in COMPOSED.items()})
    for name, (frame_id, mark_id) in sorted(jobs.items()):
        outputs = {'team': layer('team', frame_id)}
        for tone in ('dark', 'light'):
            svg = layer('overlay-' + tone, frame_id)
            if mark_id:
                mark = ROLE_MARK.search(layer('overlay-' + tone, mark_id)).group(0)
                svg = ROLE_MARK.sub(lambda _: mark, svg)
            outputs[tone] = svg
        for suffix, svg in outputs.items():
            render(svg, args.size).save(os.path.join(args.out, f'{name}-{suffix}.png'))
    print(f'{args.out}: {len(jobs)} значков по 3 слоя, {args.size}x{args.size}')


if __name__ == '__main__':
    main()
