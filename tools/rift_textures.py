"""Ruby Rift textures: glowing reskins of the guns (body + emissive glow layer), the Rift
sword and grenade, and the veined Rift blocks (dark stone with molten, glowing cracks).

    python tools/rift_textures.py

Deterministic (fixed seeds), so re-running gives the same pictures.
"""
import os
import random
import re

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'rubymod', 'textures')
GUNS = ['arr_rifle', 'combat_shotgun', 'service_pistol', 'longshot_sniper', 'rocket_launcher', 'lancer', 'ruby_blaster', 'skyhammer',
        'ruby_minigun', 'rail_lance', 'ruby_chakram', 'dragons_breath', 'swarm_launcher', 'tesla_coilgun', 'bouncer', 'six_shooter', 'ruby_warhammer', 'singularity_cannon']


def lum(r, g, b):
    return 0.3 * r + 0.59 * g + 0.11 * b


def clamp(v):
    return max(0, min(255, int(v)))


def vein_color(t, hot, mid, edge):
    """t 0..1 along the heat: 1 = white-hot core, 0 = the cooler edge."""
    if t > 0.5:
        k = (t - 0.5) * 2
        return tuple(clamp(mid[i] + (hot[i] - mid[i]) * k) for i in range(3))
    k = t * 2
    return tuple(clamp(edge[i] + (mid[i] - edge[i]) * k) for i in range(3))


# ----- Guns: mostly black and gunmetal grey, a few parts glowing ruby -----

GLOW = (255, 50, 105)
# The glowing parts are drawn white and tinted in game with the color of the level you're in.
WHITE = (255, 255, 255)


MODELS = os.path.join(os.path.dirname(__file__), '..', 'src', 'client', 'java', 'dev', 'rubymod', 'client', 'model', 'GunModels.java')
METHODS = {'arr_rifle': 'arrRifle', 'service_pistol': 'servicePistol', 'combat_shotgun': 'combatShotgun',
           'longshot_sniper': 'longshotSniper', 'ruby_blaster': 'rubyBlaster', 'rocket_launcher': 'rocketLauncher',
           'skyhammer': 'skyhammer', 'lancer': 'lancer',
           'ruby_minigun': 'rubyMinigun', 'rail_lance': 'railLance', 'ruby_chakram': 'rubyChakram', 'dragons_breath': 'dragonsBreath', 'swarm_launcher': 'swarmLauncher', 'tesla_coilgun': 'teslaCoilgun', 'bouncer': 'bouncer', 'six_shooter': 'sixShooter', 'ruby_warhammer': 'rubyWarhammer', 'singularity_cannon': 'singularityCannon'}
# Which parts glow: 'part' = all of it, 'part:0,2' = those boxes, 'part:small2' = its two smallest boxes.
GLOWING = {
    'arr_rifle': ['mag_std', 'mag_ext', 'muzzle_device', 'irons', 'bolt'],
    'service_pistol': ['mag_std:1', 'mag_ext:2', 'hammer', 'slide:small3'],
    'combat_shotgun': ['pump', 'mag_std:small2', 'body:small2'],
    'longshot_sniper': ['mag_std', 'mag_ext', 'bolt', 'muzzle_device', 'scope:small4'],
    'ruby_blaster': ['mag_std', 'mag_ext', 'body:small3'],
    'rocket_launcher': ['rocket', 'body:2,3,7'],
    'skyhammer': ['rocket', 'body:2,3,21,22,23,24,25'],
    'lancer': ['rocket', 'body:2,3,8', 'clu:2,3,5'],
    # The exotic weapons glow where they were built to (their own glow parts), plus a few accents.
    'ruby_minigun': ['spin:small6'], 'rail_lance': [], 'ruby_chakram': ['rocket:small4'], 'dragons_breath': [], 'swarm_launcher': ['body:small6'],
    'tesla_coilgun': [], 'bouncer': [], 'six_shooter': ['hammer'], 'ruby_warhammer': [], 'singularity_cannon': ['rocket'],
}
# Left exactly as they were: the muzzle flash and the brass.
KEEP = ('flash', 'casing')


def boxes(name):
    """{part: [(u, v, w, h, d), ...]} read from the gun's model code."""
    src = open(MODELS, encoding='utf-8').read()
    m = re.search(r'LayerDefinition ' + METHODS[name] + r'\(\)(.*?)LayerDefinition\.create', src, re.S)
    segs = re.split(r'addOrReplaceChild\(\s*"(\w+)"', m.group(1))
    out = {}
    num = r'(-?[\d.]+)F?'
    for i in range(1, len(segs), 2):
        found = re.findall(r'texOffs\((\d+), (\d+)\)\.addBox\(' + ', '.join([num] * 6), segs[i + 1])
        out[segs[i]] = [(int(f[0]), int(f[1]), int(float(f[5])), int(float(f[6])), int(float(f[7]))) for f in found]
    return out


def net(box):
    """The pixels a box's unfolded net covers."""
    u, v, w, h, d = box
    return [(x, y) for y in range(v, v + d + h) for x in range(u, u + 2 * d + 2 * w)]


def pick(parts, spec):
    part, _, which = spec.partition(':')
    bs = parts.get(part, [])
    if not which:
        return bs
    if which.startswith('small'):
        return sorted(bs, key=lambda b: b[2] * b[3] * b[4])[:int(which[5:])]
    return [bs[int(i)] for i in which.split(',') if int(i) < len(bs)]


def gun(name):
    base = Image.open(os.path.join(ROOT, 'entity', 'gun', name + '.png')).convert('RGBA')
    w, h = base.size
    glow_path = os.path.join(ROOT, 'entity', 'gun', name + '_glow.png')
    old_glow = Image.open(glow_path).convert('RGBA') if os.path.exists(glow_path) else Image.new('RGBA', (w, h))
    body = Image.new('RGBA', (w, h))
    glow = Image.new('RGBA', (w, h))
    bp = base.load()
    op = body.load()
    gp = glow.load()
    og = old_glow.load()
    # The body: the same shading, as black and gunmetal grey.
    for y in range(h):
        for x in range(w):
            r, g, b, a = bp[x, y]
            if a == 0:
                continue
            v = 14 + lum(r, g, b) * 0.4
            op[x, y] = (clamp(v), clamp(v + 1), clamp(v + 4), a)
            if og[x, y][3] > 0:
                gp[x, y] = WHITE + (og[x, y][3],)
    parts = boxes(name)
    # A few whole parts glow ruby (magazine, sights, muzzle...), keeping the original shading.
    for spec in GLOWING[name]:
        for box in pick(parts, spec):
            for (x, y) in net(box):
                if x >= w or y >= h or bp[x, y][3] == 0:
                    continue
                v = clamp(170 + lum(*bp[x, y][:3]) / 255 * 120)
                gp[x, y] = (v, v, v, 255)
                op[x, y] = (16, 16, 18, bp[x, y][3])
    for part in KEEP:
        for box in parts.get(part, []):
            for (x, y) in net(box):
                if x < w and y < h:
                    op[x, y] = bp[x, y]
                    gp[x, y] = og[x, y]
    body.save(os.path.join(ROOT, 'entity', 'gun', name + '_rift.png'))
    glow.save(os.path.join(ROOT, 'entity', 'gun', name + '_rift_glow.png'))


# ----- Items: the Rift sword and grenade -----

def sword():
    """The hilt (dark gunmetal) and the blade (white, tinted with the level's color in game)."""
    im = Image.open(os.path.join(ROOT, 'item', 'ruby_sword.png')).convert('RGBA')
    p = im.load()
    out = Image.new('RGBA', im.size)
    blade = Image.new('RGBA', im.size)
    o = out.load()
    bl = blade.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = p[x, y]
            if a == 0:
                continue
            L = lum(r, g, b)
            if r > g + 40 and r > b + 20:
                # Brightest along the edge.
                v = clamp(150 + min(1, L / 140) * 105)
                bl[x, y] = (v, v, v, a)
            else:
                o[x, y] = (clamp(16 + L * 0.35), clamp(17 + L * 0.35), clamp(20 + L * 0.38), a)
    out.save(os.path.join(ROOT, 'item', 'ruby_sword_rift.png'))
    blade.save(os.path.join(ROOT, 'item', 'ruby_sword_rift_blade.png'))


def grenade():
    im = Image.open(os.path.join(ROOT, 'item', 'frag_grenade.png')).convert('RGBA')
    p = im.load()
    out = Image.new('RGBA', im.size)
    band = Image.new('RGBA', im.size)
    o = out.load()
    bd = band.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = p[x, y]
            if a == 0:
                continue
            L = lum(r, g, b)
            if y in (8, 9):
                v = clamp(200 + L * 0.2)
                bd[x, y] = (v, v, v, a)
            else:
                o[x, y] = (clamp(16 + L * 0.35), clamp(17 + L * 0.35), clamp(20 + L * 0.38), a)
    out.save(os.path.join(ROOT, 'item', 'frag_grenade_rift.png'))
    band.save(os.path.join(ROOT, 'item', 'frag_grenade_rift_band.png'))


# ----- Blocks: dark stone split by glowing veins -----

BLOCKS = {
    # name: (stone dark, stone light, hot core, vein, vein edge)
    'rift_stone': ((14, 8, 16), (40, 26, 40), (255, 230, 240), (255, 50, 105), (150, 10, 55)),
    'rift_magma': ((12, 8, 6), (38, 26, 20), (255, 250, 200), (255, 190, 40), (220, 90, 10)),
    'rift_frost': ((6, 10, 20), (24, 34, 54), (235, 255, 255), (90, 220, 255), (20, 110, 200)),
    'rift_verdant': ((6, 14, 8), (24, 40, 26), (240, 255, 220), (110, 255, 120), (20, 150, 60)),
}


def block(name, colors, seed):
    dark, light, hot, mid, edge = colors
    rnd = random.Random(seed)
    im = Image.new('RGBA', (16, 16))
    p = im.load()
    for y in range(16):
        for x in range(16):
            v = rnd.random()
            p[x, y] = tuple(clamp(dark[i] + (light[i] - dark[i]) * v * v) for i in range(3)) + (255,)
    # Zig-zag veins like lightning frozen in the rock.
    for _ in range(6):
        x, y = rnd.randrange(16), rnd.randrange(16)
        dx = rnd.choice([-1, 1])
        dy = rnd.choice([-1, 1])
        length = rnd.randint(4, 8)
        for i in range(length):
            t = 1 - abs(i - length / 2) / (length / 2)
            p[x % 16, y % 16] = vein_color(t, hot, mid, edge) + (255,)
            # a softer glow beside the brightest part
            if t > 0.6:
                nx, ny = (x + 1) % 16, y % 16
                r0, g0, b0, _ = p[nx, ny]
                p[nx, ny] = tuple(clamp(c0 * 0.4 + c1 * 0.6) for c0, c1 in zip((r0, g0, b0), edge)) + (255,)
            if rnd.random() < 0.5:
                x += dx
            else:
                y += dy
            if rnd.random() < 0.25:
                dx = -dx
    im.save(os.path.join(ROOT, 'block', name + '.png'))


if __name__ == '__main__':
    for g in GUNS:
        gun(g)
    sword()
    grenade()
    for i, (name, colors) in enumerate(BLOCKS.items()):
        block(name, colors, 4242 + i)
    print('Rift textures written')
