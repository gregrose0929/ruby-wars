"""Draws the mod's UI art: 9-slice panels and buttons, 9x9 icons, mode emblems and the logo.

python tools/ui_art.py  ->  src/main/resources/assets/rubymod/textures/gui/sprites/ui/*.png

Everything is pixel art drawn from the little pictures below, in one palette:
dark slate stone, ruby red, gold. Grey pieces (buttons, tags) are tinted in game.
"""
import json
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'rubymod', 'textures', 'gui', 'sprites', 'ui')
os.makedirs(OUT, exist_ok=True)


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def save(name, im, nine=None, size=None):
    im.save(os.path.join(OUT, name + '.png'))
    meta = os.path.join(OUT, name + '.png.mcmeta')
    if nine:
        w, h = size or im.size
        json.dump({'gui': {'scaling': {'type': 'nine_slice', 'width': w, 'height': h, 'border': nine}}}, open(meta, 'w'))
    elif os.path.exists(meta):
        os.remove(meta)


def draw(rows, palette):
    h = len(rows)
    w = max(len(r) for r in rows)
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch in palette:
                im.putpixel((x, y), palette[ch])
    return im


# ----- 9-slice pieces -----

def frame(size, outline, fill, light, dark, rivet=None, light2=None):
    """A bevelled box: outline, a light top/left edge, a dark bottom/right edge, rivets in the corners."""
    im = Image.new('RGBA', (size, size), fill)
    for i in range(size):
        for (x, y) in ((i, 0), (i, size - 1), (0, i), (size - 1, i)):
            im.putpixel((x, y), outline)
    for i in range(1, size - 1):
        im.putpixel((i, 1), light)
        im.putpixel((1, i), light2 or light)
        im.putpixel((i, size - 2), dark)
        im.putpixel((size - 2, i), dark)
    im.putpixel((1, size - 2), dark)
    im.putpixel((size - 2, 1), dark)
    if rivet:
        for (x, y) in ((3, 3), (size - 4, 3), (3, size - 4), (size - 4, size - 4)):
            im.putpixel((x, y), rivet)
    for (x, y) in ((0, 0), (size - 1, 0), (0, size - 1), (size - 1, size - 1)):
        im.putpixel((x, y), (0, 0, 0, 0))
    return im


OUTLINE = hexc('0a0b0f')
save('panel', frame(16, OUTLINE, hexc('20242c'), hexc('454b58'), hexc('13151a'), rivet=hexc('8a2438'), light2=hexc('383d48')), nine=5)
save('card', frame(16, OUTLINE, hexc('262b34'), hexc('4e5563'), hexc('171a20'), light2=hexc('3e4450')), nine=4)
save('inset', frame(16, hexc('0e1014'), hexc('15181e'), hexc('0b0c10'), hexc('2b3039')), nine=3)
save('tooltip', frame(16, hexc('050608'), hexc('15171c', 245), hexc('3a2030'), hexc('0e0f13'), light2=hexc('3a2030')), nine=3)

# Buttons: grey, tinted in game. A raised face with a lip underneath; pressed has no lip.
def button(pressed=False):
    s = 16
    im = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    face = hexc('d0d0d0')
    for y in range(s):
        for x in range(s):
            edge = x in (0, s - 1) or y in (0, s - 1)
            if edge:
                c = hexc('101010')
            elif not pressed and y >= s - 3:
                c = hexc('6a6a6a')
            elif y == 1:
                c = hexc('ffffff')
            elif x == 1:
                c = hexc('ececec')
            elif x == s - 2:
                c = hexc('a8a8a8')
            else:
                c = face
            im.putpixel((x, y), c)
    for (x, y) in ((0, 0), (s - 1, 0), (0, s - 1), (s - 1, s - 1)):
        im.putpixel((x, y), (0, 0, 0, 0))
    return im


save('button', button(), nine=4)
save('button_pressed', button(True), nine=4)
# A small pill (tags): grey, tinted.
pill = Image.new('RGBA', (8, 8), hexc('c8c8c8'))
for x, y in ((0, 0), (7, 0), (0, 7), (7, 7)):
    pill.putpixel((x, y), (0, 0, 0, 0))
for i in range(1, 7):
    pill.putpixel((i, 0), hexc('ffffff'))
    pill.putpixel((i, 7), hexc('7a7a7a'))
save('pill', pill, nine=2)
# Tabs: a raised tab, and the one you're on (joined to the panel below it).
tab = frame(16, OUTLINE, hexc('1a1d23'), hexc('323743'), hexc('101216'))
save('tab', tab, nine=4)
tab_on = frame(16, OUTLINE, hexc('20242c'), hexc('5a2a38'), hexc('20242c'), light2=hexc('454b58'))
for x in range(1, 15):
    tab_on.putpixel((x, 15), hexc('20242c'))
    tab_on.putpixel((x, 1), hexc('e0294a'))
    tab_on.putpixel((x, 2), hexc('9a1d36'))
save('tab_on', tab_on, nine=4)
# Bars: a sunken track.
bar = frame(8, hexc('07080a'), hexc('101216'), hexc('07080a'), hexc('262a31'))
save('bar', bar, nine=2)

# ----- 9x9 icons -----
K = hexc('0a0b0f')
ICON = {
    'heart': (['.kk...kk.', 'kRrk.krrk', 'kRrrkrrrk', 'krrrrrrrk', 'krrrrrrdk', '.krrrrdk.', '..krrdk..', '...kdk...', '....k....'],
              {'k': K, 'R': hexc('ff8a9c'), 'r': hexc('e0294a'), 'd': hexc('9a1d36')}),
    'shield': (['.kkkkkkk.', 'kBbbbbbdk', 'kBbbbbbdk', 'kBbbbbbdk', 'kBbbbbbdk', '.kbbbbdk.', '.kbbbbdk.', '..kbbdk..', '...kkk...'],
               {'k': K, 'B': hexc('a8dcff'), 'b': hexc('4fa8ff'), 'd': hexc('2a6cc0')}),
    'star': (['....k....', '...kyk...', '...kyk...', 'kkkkyykkk', 'kYyyyyyok', '.kYyyyok.', '.kyyoyyk.', 'kyok.koyk', 'kkk...kkk'],
             {'k': K, 'Y': hexc('fff0a0'), 'y': hexc('f5c542'), 'o': hexc('c08a1a')}),
    'gem': (['..kkkkk..', '.kRRrrdk.', 'kRRrrrddk', 'kkkkkkkkk', '.krrrrdk.', '..krrdk..', '...krk...', '....k....', '.........'],
            {'k': K, 'R': hexc('ff8a9c'), 'r': hexc('e0294a'), 'd': hexc('9a1d36')}),
    'skull': (['..kkkkk..', '.kWwwwwk.', 'kWwwwwwwk', 'kwkkwkkwk', 'kwkkwkkwk', 'kwwwkwwwk', '.kwwwwwk.', '.kwkwkwk.', '..kkkkk..'],
              {'k': K, 'W': hexc('ffffff'), 'w': hexc('d8d4cc')}),
    'clock': (['..kkkkk..', '.kwwwwwk.', 'kwwwkwwwk', 'kwwwkwwwk', 'kwwwkkkwk', 'kwwwwwwwk', 'kwwwwwwwk', '.kwwwwwk.', '..kkkkk..'],
              {'k': K, 'w': hexc('f2eee8')}),
    'flag': (['kk.......', 'kwrrrrr..', 'kwrrrrrr.', 'kwrrrrr..', 'kwrrrr...', 'kw.......', 'kw.......', 'kw.......', 'kk.......'],
             {'k': K, 'w': hexc('c0c0c0'), 'r': hexc('e0294a')}),
    'wrench': (['......kk.', '.....kwk.', '....kwwkk', '...kwwwwk', '..kwwwkk.', '.kwwwk...', 'kwwwk....', 'kwwk.....', '.kk......'],
               {'k': K, 'w': hexc('c8ccd4')}),
    'warn': (['....k....', '...kyk...', '...kyk...', '..kykyk..', '..kykyk..', '.kyykyyk.', '.kyyyyyk.', 'kyyykyyyk', 'kkkkkkkkk'],
             {'k': K, 'y': hexc('f2a93b')}),
    'check': (['.........', '.......kk', '......kgk', '.....kgk.', 'kk..kgk..', 'kgkkgk...', '.kggk....', '..kk.....', '.........'],
              {'k': K, 'g': hexc('5bd27a')}),
    'cross': (['.........', 'kk.....kk', 'krk...krk', '.krk.krk.', '..krkrk..', '.krk.krk.', 'krk...krk', 'kk.....kk', '.........'],
              {'k': K, 'r': hexc('e5484d')}),
    'lock': (['..kkkkk..', '.kk...kk.', '.kk...kk.', 'kkkkkkkkk', 'kyyyyyyyk', 'kyyykyyyk', 'kyyykyyyk', 'kyyyyyyyk', 'kkkkkkkkk'],
             {'k': K, 'y': hexc('c9a14a')}),
    'truck': (['.........', 'kkkkkk...', 'kgggggkkk', 'kgggggkbk', 'kgggggkrrk'[:9], 'kkkkkkkkk', '.kok..kok', '..k....k.', '.........'],
              {'k': K, 'g': hexc('8a8f99'), 'b': hexc('7fd0ff'), 'r': hexc('e0294a'), 'o': hexc('3a3a3a')}),
    'right': (['...k.....', '...kk....', '...kwk...', '...kwwk..', '...kwwwk.', '...kwwk..', '...kwk...', '...kk....', '...k.....'],
              {'k': K, 'w': hexc('f2eee8')}),
    'left': (['.....k...', '....kk...', '...kwk...', '..kwwk...', '.kwwwk...', '..kwwk...', '...kwk...', '....kk...', '.....k...'],
             {'k': K, 'w': hexc('f2eee8')}),
    'down': (['.........', '.........', 'kkkkkkkkk', '.kwwwwwk.', '..kwwwk..', '...kwk...', '....k....', '.........', '.........'],
             {'k': K, 'w': hexc('f2eee8')}),
    'bolt': (['....kkk..', '...kyyk..', '..kyyk...', '.kyyykkk.', '.kkkyyyk.', '...kyyk..', '..kyyk...', '..kyk....', '..kk.....'],
             {'k': K, 'y': hexc('ffd84a')}),
    'crown': (['.........', 'k...k...k', 'kk.kyk.kk', 'kykyyykyk', 'kyyyyyyyk', 'kyrryrryk', 'kyyyyyyyk', 'kkkkkkkkk', '.........'],
              {'k': K, 'y': hexc('f5c542'), 'r': hexc('e0294a')}),
    'dot': (['.........', '.........', '...kkk...', '..kwwwk..', '..kwwwk..', '..kwwwk..', '...kkk...', '.........', '.........'],
            {'k': K, 'w': hexc('f2eee8')}),
}
for name, (rows, pal) in ICON.items():
    rows = [r.ljust(9, '.')[:9] for r in rows]
    save('icon_' + name, draw(rows, pal))

# ----- Mode emblems (16x16 art, saved at 32x32) -----
EMB = {
    'rubywars': ([
        '................',
        '....kkkkkkkk....',
        '...kWRRrrrrdk...',
        '..kWRRrrrrrddk..',
        '.kWRRrrrrrrrddk.',
        'kkkkkkkkkkkkkkkk',
        '.kRRrrrrrrrrddk.',
        '..kRrrrrrrrrdk..',
        '...kRrrrrrrdk...',
        '....kRrrrrdk....',
        '.....kRrrdk.....',
        '......kRdk......',
        '.......kk.......',
        '................',
        '................',
        '................'], {'k': K, 'W': hexc('ffd0d8'), 'R': hexc('ff6a84'), 'r': hexc('e0294a'), 'd': hexc('8a1830')}),
    'rift': ([
        '................',
        '.....kkkkkk.....',
        '....kppppppk....',
        '...kpPPPPPPpk...',
        '..kpPkkkkkkPpk..',
        '..kpPkvvvvkPpk..',
        '..kpPkvWWvkPpk..',
        '..kpPkvWWvkPpk..',
        '..kpPkvvvvkPpk..',
        '..kpPkkkkkkPpk..',
        '..kpPPPPPPPPpk..',
        '..kppppppppppk..',
        '..kkkkkkkkkkkk..',
        '................',
        '................',
        '................'], {'k': K, 'p': hexc('7a2aa8'), 'P': hexc('c85bff'), 'v': hexc('e8a0ff'), 'W': hexc('ffffff')}),
    'convoy': ([
        '................',
        '................',
        '.kkkkkkkkk......',
        '.kgggggggk......',
        '.kgrrrrrgkkkkk..',
        '.kgrrrrrgkbbbkk.',
        '.kgggggggkbbbbk.',
        '.kgggggggkrrrrk.',
        '.kkkkkkkkkkkkkk.',
        '.kddddddddddddk.',
        '..kook....kook..',
        '..koOk....koOk..',
        '...kk......kk...',
        '................',
        '................',
        '................'], {'k': K, 'g': hexc('9aa0aa'), 'r': hexc('e0294a'), 'b': hexc('7fd0ff'), 'd': hexc('3a3f48'), 'o': hexc('2a2a2a'), 'O': hexc('8a8a8a')}),
    'horde': ([
        '................',
        '..kkkkkkkkkkkk..',
        '..kGggggggggdk..',
        '..kGggggggggdk..',
        '..kGgkkggkkgdk..',
        '..kGgkkggkkgdk..',
        '..kGggggggggdk..',
        '..kGggkkkkggdk..',
        '...kGgggggggk...',
        '...kGgggggdk....',
        '....kGgggdk.....',
        '.....kGgdk......',
        '......kkk.......',
        '................',
        '................',
        '................'], {'k': K, 'G': hexc('9af0a8'), 'g': hexc('4cc66a'), 'd': hexc('2a7a40')}),
    'party': ([
        '................',
        '.......kk.......',
        '......kYYk......',
        '......kYyk......',
        '.kkkkkkyykkkkkk.',
        '.kYyyyyyyyyyyok.',
        '..kYyyyyyyyyok..',
        '...kyyyyyyyok...',
        '...kyyyyyyyok...',
        '..kyyyokkyyyok..',
        '..kyyok..kyyok..',
        '.kyok......kyok.',
        '.kkk........kkk.',
        '................',
        '................',
        '................'], {'k': K, 'Y': hexc('e8d8ff'), 'y': hexc('9b7bff'), 'o': hexc('5a3fc0')}),
    'hub': ([
        '................',
        '.......kk.......',
        '......kRRk......',
        '.....kRrrdk.....',
        '....kRrrrrdk....',
        '...kkkkkkkkkk...',
        '....kssssssk....',
        '....kskkkksk....',
        '....kskddksk....',
        '....kskddksk....',
        '..kkkkkkkkkkkk..',
        '..kGGGGGGGGGGk..',
        '...kdddddddddk..',
        '....kddddddk....',
        '......kkkk......',
        '................'], {'k': K, 'R': hexc('ff6a84'), 'r': hexc('e0294a'), 'd': hexc('5a3a2a'), 's': hexc('c8c0b0'), 'G': hexc('5bd27a')}),
}
for name, (rows, pal) in EMB.items():
    im = draw(rows, pal)
    save('emblem_' + name, im.resize((32, 32), Image.NEAREST))

# ----- The logo: RUBY WARS in chunky letters, ruby fading to dark, with an outline and a drop -----
FONT = {
    'R': ['1111.', '11.11', '11.11', '1111.', '11.11', '11.11', '11.11'],
    'U': ['11.11', '11.11', '11.11', '11.11', '11.11', '11.11', '.111.'],
    'B': ['1111.', '11.11', '11.11', '1111.', '11.11', '11.11', '1111.'],
    'Y': ['11.11', '11.11', '11.11', '.111.', '.11..', '.11..', '.11..'],
    'W': ['1...1', '1...1', '1.1.1', '1.1.1', '11111', '11.11', '1...1'],
    'A': ['.111.', '11.11', '11.11', '11111', '11.11', '11.11', '11.11'],
    'S': ['.1111', '11...', '11...', '.111.', '...11', '...11', '1111.'],
    ' ': ['...', '...', '...', '...', '...', '...', '...'],
}


def logo(text, scale=2):
    glyphs = [FONT[c] for c in text]
    w = sum(len(g[0]) + 1 for g in glyphs) - 1
    h = 7
    pad = 2
    W = (w + pad * 2) * scale
    H = (h + pad * 2 + 1) * scale
    mask = Image.new('L', (W, H), 0)
    x0 = pad
    for g in glyphs:
        for y, row in enumerate(g):
            for x, ch in enumerate(row):
                if ch == '1':
                    for sy in range(scale):
                        for sx in range(scale):
                            mask.putpixel(((x0 + x) * scale + sx, (pad + y) * scale + sy), 255)
        x0 += len(g[0]) + 1
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    top = hexc('ff7088')
    mid = hexc('e0294a')
    bot = hexc('7a1428')
    for y in range(H):
        for x in range(W):
            if mask.getpixel((x, y)):
                t = (y - pad * scale) / (h * scale)
                if t < 0.12:
                    c = hexc('ffd0d8')
                elif t < 0.5:
                    c = top if t < 0.3 else mid
                else:
                    c = mid if t < 0.75 else bot
                im.putpixel((x, y), c)
    out = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    # The drop (extruded down-right), then the outline, then the letters.
    for d in (2, 1):
        for y in range(H):
            for x in range(W):
                if mask.getpixel((x, y)) and x + d < W and y + d < H:
                    out.putpixel((x + d, y + d), hexc('3a0812'))
    for y in range(H):
        for x in range(W):
            if mask.getpixel((x, y)):
                for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < W and 0 <= ny < H and not mask.getpixel((nx, ny)):
                        out.putpixel((nx, ny), K)
    out.alpha_composite(im)
    return out


save('logo', logo('RUBY WARS', 2))
print('wrote', len(os.listdir(OUT)), 'files to', os.path.normpath(OUT))
