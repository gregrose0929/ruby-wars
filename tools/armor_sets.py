"""Draws the cosmetic armor sets: the Ruby armor's shape, repainted (and patterned) for each set.

python tools/armor_sets.py  ->  assets/rubymod/textures/entity/equipment/{humanoid,humanoid_leggings}/set_<id>.png
                                 assets/rubymod/equipment/set_<id>.json

Each set is five shades from dark to light, the colour of the trim (the Ruby armor's pale edges), and
sometimes a pattern on top (camouflage blotches, hazard stripes, glowing seams).
"""
import json
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'rubymod')
LAYERS = ['humanoid', 'humanoid_leggings']


def hexc(h):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def noise(x, y, seed):
    n = (x * 374761393 + y * 668265263 + seed * 982451653) & 0xFFFFFFFF
    n = (n ^ (n >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def camo(a, b):
    def f(x, y, shade, color):
        if shade >= 5:
            return color
        v = noise(x // 2, y // 2, 7)
        return a if v < 0.28 else b if v > 0.8 else color
    return f


def stripes(a, b):
    """Diagonal hazard stripes across the middle shades."""
    def f(x, y, shade, color):
        if shade in (1, 2) and y % 8 >= 5:
            return a if (x + y) % 4 < 2 else b
        return color
    return f


def seams(glow):
    """Glowing lines where the armor's shades meet its highlights."""
    def f(x, y, shade, color):
        return glow if shade == 3 and (x + y) % 3 == 0 else color
    return f


def speckle(a, chance):
    def f(x, y, shade, color):
        return a if shade < 5 and noise(x, y, 3) < chance else color
    return f


# id: (five shades dark to light, trim, pattern)
SETS = {
    'recruit': (['4a5238', '5c6644', '6e7a52', '84926a', '9aa880'], 'c8c2a0', None),
    'ranger': (['2e4024', '3c5430', '4c6a3c', '5e8048', '74985a'], '8a6a40', camo(hexc('6a4e2c'), hexc('24341c'))),
    'frost': (['6a94b8', '86b0d0', 'a4cae4', 'c4e0f2', 'e2f2fb'], 'ffffff', speckle(hexc('ffffff'), 0.08)),
    'shadow': (['101114', '1a1c21', '25282f', '32363f', '424752'], 'e0294a', None),
    'champion': (['5a0c1e', '7a1228', 'a01a36', 'c82848', 'e84a68'], 'f5c542', None),
    'hauler': (['8a4a10', 'b06414', 'd6801c', 'f29a2e', 'ffb85a'], 'd8dce4', stripes(hexc('1a1a1c'), hexc('f5c542'))),
    'legion': (['1c1e24', '2a2d36', '3a3e4a', '50556a', '6a7088'], 'c01830', None),
    'riftwalker': (['2a1040', '3e1860', '582488', '7a38b4', '9c58d8'], '7ff0ff', seams(hexc('c85bff'))),
    'breaker': (['7a7460', '989078', 'b4ac92', 'd0c8ae', 'e8e2cc'], '4cc66a', None),
    'star': (['a8780c', 'c89414', 'e4b024', 'f5c542', 'ffe07a'], 'ffffff', speckle(hexc('fff6c8'), 0.1)),
    'victor': (['b08a10', 'd0a418', 'e8be28', 'f8d848', 'fff0a0'], 'e0294a', None),
    'desert': (['8a744a', 'a48c5c', 'bca470', 'd2bc88', 'e6d4a4'], '6a5434', camo(hexc('7a6238'), hexc('efe2bc'))),
    'ocean': (['0c4a5c', '126a80', '1a8ca4', '2cb0c8', '5cd4e6'], 'f2f6f8', speckle(hexc('c8f4ff'), 0.06)),
    'ember': (['1c1412', '2a1c18', '3c2620', '54342a', '70463a'], 'ff7a1a', seams(hexc('ffb030'))),
    'royal': (['341060', '4a1a86', '6426ac', '8238d0', 'a45cec'], 'f5c542', None),
    'medic': (['b4bac2', 'ccd2d8', 'e0e4e8', 'f0f2f4', 'ffffff'], 'd81e2c', None),
    'racer': (['1c56c0', '2a70dc', '4290f0', '66acff', '96c8ff'], 'ffe040', stripes(hexc('ffffff'), hexc('1c56c0'))),
    'duelist': (['40444c', '565c66', '707884', '8c96a4', 'acb6c4'], '30c8a0', None),
}

# The Ruby armor's own colours, darkest first; anything paler is trim.
BASE = [(186, 28, 56), (184, 27, 55), (197, 30, 60), (212, 24, 58), (225, 54, 85), (230, 88, 114)]


def shade_of(rgb):
    if rgb in BASE:
        return {0: 0, 1: 0, 2: 1, 3: 2, 4: 3, 5: 4}[BASE.index(rgb)]
    return 5


for name, (shades, trim, pattern) in SETS.items():
    shades = [hexc(s) for s in shades]
    trim = hexc(trim)
    for layer in LAYERS:
        src = Image.open(os.path.join(ROOT, 'textures', 'entity', 'equipment', layer, 'ruby.png')).convert('RGBA')
        out = Image.new('RGBA', src.size, (0, 0, 0, 0))
        for y in range(src.height):
            for x in range(src.width):
                r, g, b, a = src.getpixel((x, y))
                if a == 0:
                    continue
                s = shade_of((r, g, b))
                color = trim if s == 5 else shades[s]
                if pattern:
                    color = pattern(x, y, s, color)
                out.putpixel((x, y), color)
        out.save(os.path.join(ROOT, 'textures', 'entity', 'equipment', layer, 'set_' + name + '.png'))
    json.dump({'layers': {layer: [{'texture': 'rubymod:set_' + name}] for layer in LAYERS}},
              open(os.path.join(ROOT, 'equipment', 'set_' + name + '.json'), 'w'), indent='\t')
print('drew', len(SETS), 'armor sets')
