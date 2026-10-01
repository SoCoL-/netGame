#!/usr/bin/env python3
"""
Превращает рисованный макет карты (PNG, цвета по легенде) в файл карты
для игры (assets/maps/<имя>.json), который читают и сервер (проходимость,
месторождения, точки старта), и клиент (отрисовка земли и воды).

Легенда (каждый пиксель приводится к БЛИЖАЙШЕМУ цвету легенды, так что
сглаженные края рисунка не мешают):
  синий     #0026FF — вода
  зелёный   #04FF00 — трава (суша)
  красно-оранжевый #FF490C — скалы
  оранжевый #FF6C11 — месторождение железа (берётся центр пятна)
  белый     #FFFFFF — старт игрока 0 (центр пятна)
  чёрный    #000000 — старт игрока 1 (центр пятна)
Под месторождениями и стартами клетка считается травой.

Макет должен быть квадратным (карта 8000x8000). Каждая клетка карты
(GRID x GRID, по умолчанию 160 — клетка 50 единиц, как у поиска пути)
получает тот тип, которого в её пикселях больше всего.

Если старт игрока 1 не нарисован, он ставится центрально-симметрично
старту игрока 0. --mirror <половина> делает всю карту центрально-
симметричной: рисуется только указанная половина (top, bottom, left,
right — по сторонам; top-right, bottom-left — по диагонали из левого
верхнего угла в правый нижний; top-left, bottom-right — по другой
диагонали), вторая получается поворотом на 180 градусов, со всеми
месторождениями; старт игрока 1 — отражение старта игрока 0.

Пример:
  python3 tools/bake_map.py map.png assets/maps/default.json
  python3 tools/bake_map.py half.png assets/maps/duel.json --mirror bottom-left

Нужен Pillow: pip install pillow
"""
import argparse
import json

from PIL import Image

MAP_SIZE = 8000.0

WATER, GRASS, ROCK, IRON, SPAWN0, SPAWN1 = range(6)
PALETTE = {
    WATER: (0, 38, 255),
    GRASS: (4, 255, 0),
    ROCK: (255, 73, 12),
    IRON: (255, 108, 17),
    SPAWN0: (255, 255, 255),
    SPAWN1: (0, 0, 0),
}
CELL_CHAR = {WATER: '~', GRASS: '.', ROCK: '#'}

HALVES = {
    'top': lambda x, y, s: y < s / 2,
    'bottom': lambda x, y, s: y >= s / 2,
    'left': lambda x, y, s: x < s / 2,
    'right': lambda x, y, s: x >= s / 2,
    'top-right': lambda x, y, s: x > y,
    'bottom-left': lambda x, y, s: x <= y,
    'top-left': lambda x, y, s: x + y < s - 1,
    'bottom-right': lambda x, y, s: x + y >= s - 1,
}


def classify(image):
    size = image.width
    pixels = image.load()
    cache = {}
    labels = [[0] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            color = pixels[x, y]
            label = cache.get(color)
            if label is None:
                label = min(PALETTE, key=lambda k: sum((a - b) ** 2 for a, b in zip(color, PALETTE[k])))
                cache[color] = label
            labels[y][x] = label
    return labels


def blobs(labels, label, min_side=4):
    """
    Центры связных пятен цвета label, в пикселях (x вправо, y вниз).
    Берутся только компактные пятна (кружки): сглаженная кромка скал
    по цвету близка к месторождению и после приведения к легенде даёт
    тонкие полоски "железа" в 1-2 пикселя вдоль границ — их отсекаем.
    """
    size = len(labels)
    seen = [[False] * size for _ in range(size)]
    centers = []
    for y in range(size):
        for x in range(size):
            if seen[y][x] or labels[y][x] != label:
                continue
            stack = [(x, y)]
            seen[y][x] = True
            points = []
            while stack:
                px, py = stack.pop()
                points.append((px, py))
                for nx, ny in ((px + 1, py), (px - 1, py), (px, py + 1), (px, py - 1)):
                    if 0 <= nx < size and 0 <= ny < size and not seen[ny][nx] and labels[ny][nx] == label:
                        seen[ny][nx] = True
                        stack.append((nx, ny))
            xs = [p[0] for p in points]
            ys = [p[1] for p in points]
            width = max(xs) - min(xs) + 1
            height = max(ys) - min(ys) + 1
            compact = (min(width, height) >= min_side and max(width, height) <= 1.6 * min(width, height)
                       and len(points) >= 0.6 * width * height)
            if compact:
                centers.append((sum(p[0] for p in points) / len(points) + 0.5,
                                sum(p[1] for p in points) / len(points) + 0.5))
    return centers


def to_world(point, size):
    # Картинка — Y вниз, мир — Y вверх.
    scale = MAP_SIZE / size
    return [round(point[0] * scale, 1), round(MAP_SIZE - point[1] * scale, 1)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('source')
    parser.add_argument('out')
    parser.add_argument('--grid', type=int, default=160, help='клеток по стороне (8000 / 50 = 160)')
    parser.add_argument('--mirror', choices=sorted(HALVES), help='нарисована только эта половина — вторую отразить')
    args = parser.parse_args()

    image = Image.open(args.source).convert('RGB')
    if image.width != image.height:
        raise SystemExit(f'макет должен быть квадратным, а он {image.width}x{image.height}')
    size = image.width
    labels = classify(image)

    if args.mirror:
        keep = HALVES[args.mirror]
        for y in range(size):
            for x in range(size):
                if not keep(x, y, size):
                    labels[y][x] = labels[size - 1 - y][size - 1 - x]

    deposits = blobs(labels, IRON)
    spawn0 = blobs(labels, SPAWN0)
    spawn1 = blobs(labels, SPAWN1)
    if args.mirror:
        # Отражённая половина уже содержит копии месторождений; старт — только из нарисованной.
        spawn0 = [p for p in spawn0 if HALVES[args.mirror](int(p[0]), int(p[1]), size)]
        spawn1 = []
    if len(spawn0) != 1:
        raise SystemExit(f'нужен ровно один старт игрока 0 (белая точка), найдено {len(spawn0)}')
    if not spawn1:
        spawn1 = [(size - spawn0[0][0], size - spawn0[0][1])]
    elif len(spawn1) != 1:
        raise SystemExit(f'старт игрока 1 (чёрная точка) должен быть один, найдено {len(spawn1)}')

    cell = size / args.grid
    rows = []
    for gy in range(args.grid):
        row = []
        for gx in range(args.grid):
            counts = {WATER: 0, GRASS: 0, ROCK: 0}
            for y in range(int(gy * cell), int((gy + 1) * cell)):
                for x in range(int(gx * cell), int((gx + 1) * cell)):
                    label = labels[y][x]
                    counts[label if label in counts else GRASS] += 1
            row.append(CELL_CHAR[max(counts, key=counts.get)])
        rows.append(''.join(row))

    result = {
        'legend': {'.': 'трава', '~': 'вода', '#': 'скалы'},
        'cellSize': MAP_SIZE / args.grid,
        'cells': rows,  # первая строка — ВЕРХ карты, как на картинке
        'ironDeposits': [to_world(p, size) for p in sorted(deposits, key=lambda p: (p[1], p[0]))],
        'spawns': [to_world(spawn0[0], size), to_world(spawn1[0], size)],
    }
    with open(args.out, 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=1)
    water = sum(r.count('~') for r in rows)
    rock = sum(r.count('#') for r in rows)
    total = args.grid * args.grid
    print(f'{args.out}: {args.grid}x{args.grid} клеток, вода {100 * water / total:.0f}%, '
          f'скалы {100 * rock / total:.0f}%, месторождений {len(deposits)}, старты {result["spawns"]}')


if __name__ == '__main__':
    main()
