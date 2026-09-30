#!/usr/bin/env python3
"""
Делает текстуру бесшовной для обычного повтора (GL_REPEAT) и готовит её
для игры: смешивает картинку с её же копией, сдвинутой на половину
размера, по маске, которая равна 1 в центре и 0 у краёв. У краёв
остаётся сдвинутая копия — её края это середина исходника, поэтому
противоположные края совпадают; шов сдвинутой копии (крест посередине)
закрыт исходником. Затем уменьшает до --size (степень двойки — нужна для
повтора с mip-уровнями на GLES 2) и сохраняет.

Пример:
  python3 tools/make_seamless.py water-deep.png assets/terrain/water-deep.jpg

Нужен Pillow: pip install pillow
"""
import argparse

from PIL import Image


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('source')
    parser.add_argument('out')
    parser.add_argument('--size', type=int, default=1024)
    parser.add_argument('--band', type=float, default=0.3,
                        help='ширина перехода у каждого края, доля размера (0..0.5)')
    parser.add_argument('--quality', type=int, default=90)
    args = parser.parse_args()

    image = Image.open(args.source).convert('RGB')
    width, height = image.size
    shifted = Image.new('RGB', image.size)
    for dx in (0, 1):
        for dy in (0, 1):
            shifted.paste(image, (dx * width - width // 2, dy * height - height // 2))

    def weight(position, length):
        edge = min(position, length - 1 - position) / (length * args.band)
        edge = max(0.0, min(1.0, edge))
        return edge * edge * (3 - 2 * edge)

    wx = [weight(x, width) for x in range(width)]
    wy = [weight(y, height) for y in range(height)]
    mask = Image.new('L', image.size)
    mask.putdata([int(255 * wx[x] * wy[y]) for y in range(height) for x in range(width)])
    result = Image.composite(image, shifted, mask)
    # Ресайз с "завёрнутыми" полями: без них фильтр у краёв видит пустоту,
    # а не противоположный край, и шов появляется заново.
    pad = width // 16
    padded = Image.new('RGB', (width + 2 * pad, height + 2 * pad))
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            padded.paste(result, (pad + dx * width, pad + dy * height))
    scale = args.size / width
    big = padded.resize((round(padded.width * scale), round(padded.height * scale)), Image.LANCZOS)
    offset = round(pad * scale)
    big.crop((offset, offset, offset + args.size, offset + args.size)).save(args.out, quality=args.quality)


if __name__ == '__main__':
    main()
