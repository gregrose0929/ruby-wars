"""Ruby Rift textures: glowing reskins of the guns (body + emissive glow layer), the Rift
sword and grenade, and the veined Rift blocks (dark stone with molten, glowing cracks).

    python tools/rift_textures.py

Deterministic (fixed seeds), so re-running gives the same pictures.
"""
import os
import random

from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'rubymod', 'textures')
GUNS = ['arr_rifle', 'combat_shotgun', 'service_pistol', 'longshot_sniper', 'rocket_launcher', 'lancer', 'ruby_blaster', 'skyhammer']


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


# ----- Guns: obsidian and ruby bodies, glowing veins on the emissive layer -----

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
    opaque = []
    for y in range(h):
        for x in range(w):
            r, g, b, a = bp[x, y]
            if a == 0:
                continue
            opaque.append((x, y))
            L = lum(r, g, b)
            sat = max(r, g, b) - min(r, g, b)
            if sat > 28:
                # Coloured furniture (olive, tan, red): deep ruby.
                op[x, y] = (clamp(70 + L * 0.85), clamp(L * 0.12), clamp(24 + L * 0.28), a)
            else:
                # Gunmetal: obsidian with a violet sheen.
                op[x, y] = (clamp(14 + L * 0.32), clamp(9 + L * 0.2), clamp(24 + L * 0.48), a)
            gr, gg, gb, ga = og[x, y]
            if ga > 0:
                # The old glowing bits (sights, lamps): ruby-hot.
                gp[x, y] = (255, clamp(60 + lum(gr, gg, gb) * 0.5), clamp(110 + lum(gr, gg, gb) * 0.3), ga)
    rnd = random.Random(hash(name) & 0xFFFF)
    solid = set(opaque)
    # The edges of each part's net glow faintly (so every edge of the model has a line of light).
    for (x, y) in opaque:
        edge = any((x + dx, y + dy) not in solid for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if edge and rnd.random() < 0.6:
            gp[x, y] = (230, 30, 90, 170)
    # Molten veins running over it.
    for _ in range(max(6, len(opaque) // 160)):
        x, y = rnd.choice(opaque)
        dx, dy = rnd.choice([(1, 0), (0, 1), (-1, 0), (0, -1)])
        length = rnd.randint(5, 14)
        for i in range(length):
            if (x, y) not in solid:
                break
            t = 1 - abs(i - length / 2) / (length / 2)
            c = vein_color(t, (255, 225, 240), (255, 60, 110), (170, 20, 70))
            gp[x, y] = c + (255,)
            if rnd.random() < 0.3:
                dx, dy = rnd.choice([(1, 0), (0, 1), (-1, 0), (0, -1)])
            x += dx
            y += dy
    body.save(os.path.join(ROOT, 'entity', 'gun', name + '_rift.png'))
    glow.save(os.path.join(ROOT, 'entity', 'gun', name + '_rift_glow.png'))


# ----- Items: the Rift sword and grenade -----

def sword():
    im = Image.open(os.path.join(ROOT, 'item', 'ruby_sword.png')).convert('RGBA')
    p = im.load()
    out = Image.new('RGBA', im.size)
    o = out.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = p[x, y]
            if a == 0:
                continue
            L = lum(r, g, b)
            if r > g + 40 and r > b + 20:
                # The blade: neon ruby, white-hot along the edge.
                t = min(1, L / 140)
                o[x, y] = vein_color(t, (255, 235, 245), (255, 40, 110), (150, 0, 60)) + (a,)
            else:
                # The hilt: obsidian with a violet glint.
                o[x, y] = (clamp(20 + L * 0.3), clamp(10 + L * 0.15), clamp(36 + L * 0.5), a)
    out.save(os.path.join(ROOT, 'item', 'ruby_sword_rift.png'))


def grenade():
    im = Image.open(os.path.join(ROOT, 'item', 'frag_grenade.png')).convert('RGBA')
    p = im.load()
    out = Image.new('RGBA', im.size)
    o = out.load()
    for y in range(16):
        for x in range(16):
            r, g, b, a = p[x, y]
            if a == 0:
                continue
            L = lum(r, g, b)
            if y in (8, 9):
                o[x, y] = (255, clamp(80 + L * 0.4), clamp(140 + L * 0.3), a)
            else:
                o[x, y] = (clamp(18 + L * 0.35), clamp(10 + L * 0.2), clamp(30 + L * 0.55), a)
    out.save(os.path.join(ROOT, 'item', 'frag_grenade_rift.png'))


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
