"""Generate Ruby Wars' 16x16 pixel-art textures (pure Python, no dependencies).

Usage:  python tools/textures.py src/main/resources/assets/rubymod

Item sprites are drawn as character grids below; block textures are
procedural. Edit a grid and re-run to change the art.
"""
import os
import random
import struct
import sys
import zlib


def write_png(path, pixels):
    h, w = len(pixels), len(pixels[0])
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


PAL = {
    ".": (0, 0, 0, 0),
    "k": (28, 28, 32, 255),      # outline
    "d": (58, 60, 66, 255),      # dark steel
    "g": (96, 100, 108, 255),    # steel
    "l": (150, 155, 165, 255),   # light steel
    "w": (225, 230, 235, 255),   # highlight
    "b": (100, 64, 36, 255),     # wood
    "B": (140, 95, 55, 255),     # light wood
    "o": (78, 92, 48, 255),      # olive
    "O": (112, 130, 70, 255),    # light olive
    "G": (48, 60, 30, 255),      # dark olive
    "r": (210, 30, 55, 255),     # ruby
    "R": (120, 10, 30, 255),     # dark ruby
    "p": (255, 120, 140, 255),   # ruby highlight
    "y": (190, 150, 50, 255),    # brass
    "Y": (235, 200, 90, 255),    # light brass
    "c": (180, 110, 50, 255),    # copper
    "s": (205, 185, 135, 255),   # sand
    "S": (160, 140, 95, 255),    # dark sand
    "e": (255, 70, 40, 255),     # red light
    "f": (255, 170, 40, 255),    # flame
    "n": (60, 80, 110, 255),     # navy
    "N": (95, 120, 160, 255),    # light navy
    "x": (20, 200, 255, 255),    # lens cyan
}


def grid(rows):
    assert len(rows) == 16, len(rows)
    out = []
    for r in rows:
        r = r.ljust(16, ".")
        assert len(r) == 16, r
        out.append([PAL[c] for c in r])
    return shade(out, rows)


def shade(img, rows):
    """Give flat sprites depth: lighten pixels lit from the top-left, darken the bottom-right edges."""
    def edge(x, y):
        return x < 0 or y < 0 or x > 15 or y > 15 or rows[y][x] in ".k"

    out = [row[:] for row in img]
    for y in range(16):
        for x in range(16):
            if rows[y][x] in ".k":
                continue
            r, g, b, a = img[y][x]
            delta = 0
            if edge(x, y - 1) or edge(x - 1, y):
                delta += 28
            if edge(x, y + 1) or edge(x + 1, y):
                delta -= 24
            out[y][x] = (max(0, min(255, r + delta)), max(0, min(255, g + delta)), max(0, min(255, b + delta)), a)
    return out


ITEMS = {
    "ruby": [
        "................",
        "................",
        "....kkkkkkkk....",
        "...kpwwprrrRk...",
        "..kpwprrrrRRRk..",
        ".kpppprrrrRRRRk.",
        ".kkkkkkkkkkkkkk.",
        "..kprrrrrrRRRk..",
        "...kprrrrRRRk...",
        "....kprrrRRk....",
        ".....kprRRk.....",
        "......kpRk......",
        ".......kk.......",
        "................",
        "................",
        "................",
    ],
    "service_pistol": [
        "................",
        "................",
        "................",
        "................",
        "...kkkkkkkkkkk..",
        "..kglllllllllgk.",
        "..kgddddddddddk.",
        "..kkkkdgdkkkkk..",
        ".....kddk.......",
        ".....kddk.......",
        "....kbbdk.......",
        "....kbbk........",
        "....kbbk........",
        "....kkkk........",
        "................",
        "................",
    ],
    "combat_shotgun": [
        "................",
        "................",
        "................",
        "................",
        "kkkkkkkkkkkkkkk.",
        "kBBbkllllllllllk",
        "kbbbkddddddddddk",
        ".kbbkkdkbbbbkkk.",
        "..kbbbdkBBBBk...",
        "...kkkdkkkkk....",
        "......kk........",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "arr_rifle": [
        "................",
        "................",
        "......kkkk......",
        "......kxdk......",
        "kkk..kkkkkkkkk..",
        "kOokkgllllllllkk",
        "kooooddddddddddk",
        ".kookkdkkdkkkkk.",
        "..kk.kdk.kdk....",
        ".....kdk.kok....",
        ".....kkk.kok....",
        "..........kk....",
        "................",
        "................",
        "................",
        "................",
    ],
    "longshot_sniper": [
        "................",
        "................",
        ".....kkkkkk.....",
        "....kxddddxk....",
        ".....kkkkkk.....",
        "kkkk..kk........",
        "kooookkkkkkkkkkk",
        "kGooodglllllllll",
        ".kGoodddkkkkkkkk",
        "..kkkdkk........",
        ".....kk.........",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "ruby_blaster": [
        "................",
        "................",
        "................",
        "....kkkkkkk.....",
        "...kllllllkkk...",
        "..klwwlllrrrrk..",
        "..kgggggkrprpek.",
        "..kkkdgkkrrrrk..",
        "....kdgk.kkkk...",
        "...kdgk.........",
        "...kdgk.........",
        "..kddk..........",
        "..kkk...........",
        "................",
        "................",
        "................",
    ],
    "light_ammo": [
        "................",
        "................",
        "................",
        "...kk..kk..kk...",
        "..kYyk.kYyk.kYyk",
        "..kyyk.kyyk.kyyk",
        "..kyyk.kyyk.kyyk",
        "..kcck.kcck.kcck",
        "..kcck.kcck.kcck",
        "..kcck.kcck.kcck",
        "..kkkk.kkkk.kkkk",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "heavy_ammo": [
        "................",
        "...k...k...k....",
        "..kYk.kYk.kYk...",
        "..kyk.kyk.kyk...",
        "..kyk.kyk.kyk...",
        ".kcck.kcck.kcck.",
        ".kcck.kcck.kcck.",
        ".kcck.kcck.kcck.",
        ".kcck.kcck.kcck.",
        ".kcck.kcck.kcck.",
        ".kyyk.kyyk.kyyk.",
        ".kkkk.kkkk.kkkk.",
        "................",
        "................",
        "................",
        "................",
    ],
    "shotgun_shell": [
        "................",
        "................",
        "....kkkk.kkkk...",
        "....keRk.keRk...",
        "....keRk.keRk...",
        "....keRk.keRk...",
        "....keRk.keRk...",
        "....keRk.keRk...",
        "....kyyk.kyyk...",
        "....kYyk.kYyk...",
        "....kkkk.kkkk...",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "sniper_round": [
        "................",
        "......k.........",
        ".....kgk........",
        ".....kgk........",
        ".....klk........",
        "....kYyyk.......",
        "....kyyyk.......",
        "....kyyyk.......",
        "....kyyyk.......",
        "....kyyyk.......",
        "....kyyyk.......",
        "....kyyyk.......",
        "....kYYYk.......",
        "....kkkkk.......",
        "................",
        "................",
    ],
    "ruby_cell": [
        "................",
        "................",
        ".....kkkkkk.....",
        ".....kllllk.....",
        "....kkkkkkkk....",
        "....kdprrrdk....",
        "....kdprrrdk....",
        "....kdprrRdk....",
        "....kdrrrRdk....",
        "....kdrrRRdk....",
        "....kdrRRRdk....",
        "....kkkkkkkk....",
        "................",
        "................",
        "................",
        "................",
    ],
    "frag_grenade": [
        "................",
        "......kkk.......",
        ".....klllk......",
        "....kkkgkkk.....",
        "...kOOoooook....",
        "..kOkokokoGk....",
        "..kOoooooooGk...",
        "..kOkokokoGk....",
        "..kooooooooGk...",
        "..kokokokoGGk...",
        "...kooooooGk....",
        "....kGGGGGk.....",
        ".....kkkkk......",
        "................",
        "................",
        "................",
    ],
    "smoke_grenade": [
        "................",
        "......kkk.......",
        ".....klllk......",
        "....kkkgkkk.....",
        "....klllllk.....",
        "....kwwwwlk.....",
        "....kllllgk.....",
        "....kddddgk.....",
        "....klllllk.....",
        "....kllllgk.....",
        "....kllllgk.....",
        "....kgggggk.....",
        "....kkkkkkk.....",
        "................",
        "................",
        "................",
    ],
    "air_strike_flare": [
        "................",
        ".......f........",
        "......fef.......",
        ".......f........",
        "......kkk.......",
        "......keek......",
        "......keRk......",
        "......keRk......",
        "......keRk......",
        "......keRk......",
        "......keRk......",
        "......keRk......",
        "......kddk......",
        "......kkkk......",
        "................",
        "................",
    ],
    "rocket_launcher": [
        "................",
        "................",
        "................",
        "kkkkkkkkkkkkkkkk",
        "kGoooooooooooOkk",
        "kdooooooooooooRk",
        "kGooooooooooooRk",
        "kkkkkdkkkdkkkkkk",
        ".....kdk.kdk....",
        ".....kdk.kok....",
        ".....kkk.kok....",
        "..........kk....",
        "................",
        "................",
        "................",
        "................",
    ],
    "aa_missile": [
        "................",
        "................",
        "................",
        "................",
        "................",
        "..k..........k..",
        ".kOk.kkkkkkkkxk.",
        "kOOOkoooyooooxxk",
        "kOOOkoooyooooxxk",
        ".kOk.kkkkkkkkxk.",
        "..k..........k..",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "at_missile": [
        "................",
        "................",
        "................",
        "................",
        "..kk..........k.",
        ".kGGkkkkkkkkkkdk",
        "kGGGGGGGyGGGGGddk"[:16],
        "kGGGGGGGyGGGGGdk",
        "kGGGGGGGyGGGGGdk",
        ".kGGkkkkkkkkkkdk",
        "..kk..........k.",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "c4": [
        "................",
        "................",
        "................",
        ".......ee.......",
        "......e..e......",
        "..kkkkkkkkkkkk..",
        "..kSSSkddkSSSk..",
        "..kssskdekSssk..",
        "..kssskddksssk..",
        "..kSSSkkkkSSSk..",
        "..kssssssssssk..",
        "..kSSSSSSSSSSk..",
        "..kkkkkkkkkkkk..",
        "................",
        "................",
        "................",
    ],
    "rocket": [
        "................",
        "................",
        "................",
        "................",
        "..k.........kk..",
        ".kgk.kkkkkkkrRk.",
        "kgggkooooooorRRk",
        "kgggkooooooorRRk",
        ".kgk.kkkkkkkrRk.",
        "..k.........kk..",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "mortar_shell": [
        "................",
        ".......kk.......",
        "......kYYk......",
        ".....kyyyyk.....",
        ".....koooOk.....",
        ".....koooOk.....",
        ".....koooOk.....",
        ".....koooOk.....",
        ".....kGooOk.....",
        "......kGok......",
        ".......kk.......",
        "......kggk......",
        ".....kgkkgk.....",
        ".....kk..kk.....",
        "................",
        "................",
    ],
    "cruise_missile": [
        "................",
        ".......kk.......",
        "......kRRk......",
        "......krRk......",
        "......kwlk......",
        "......kwlk......",
        ".....kkwlkk.....",
        "....kgkwlkgk....",
        "......kwlk......",
        "......kwlk......",
        "......kwlk......",
        ".....kkwlkk.....",
        "....kgkddkgk....",
        "......kffk......",
        ".......ff.......",
        "................",
    ],
    "rangefinder": [
        "................",
        "................",
        "................",
        "..kkkkk.kkkkk...",
        ".kdddddkdddddk..",
        ".kxNddkkkxNddk..",
        ".kNxddkkkNxddk..",
        ".kdddddkdddddk..",
        "..kkkkkkkkkkk...",
        "...kddddddddk...",
        "....kkkkkkkk....",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "breaching_charge": [
        "................",
        "................",
        "................",
        "..kkkkkkkkkkk...",
        "..ksssssssssk...",
        "..ksSSSkeekSk...",
        "..ksSSSkeekSk...",
        "..ksSSSkkkkSk...",
        "..ksssssssssk...",
        "..kSSSSSSSSSk...",
        "..kkkkkkkkkkk...",
        "...kk.....kk....",
        "................",
        "................",
        "................",
        "................",
    ],
    "architect_wand": [
        "................",
        "...........kkk..",
        "..........kxxxk.",
        "..........kxwxk.",
        "..........kxxxk.",
        ".........kYkkk..",
        "........kYk.....",
        ".......klk......",
        "......kgk.......",
        ".....kYk........",
        "....klk.........",
        "...kgk..........",
        "..kdk...........",
        "..kk............",
        "................",
        "................",
    ],
    "remodeler": [
        "................",
        "......kkkkkk....",
        ".....kllllllk...",
        "....kllwllllk...",
        "....klllllllk...",
        ".....kggggggk...",
        "......kkkdkk....",
        ".........kdk....",
        "..........krk...",
        "...........krk..",
        "............krk.",
        ".............kk.",
        "................",
        "................",
        "................",
        "................",
    ],
    "blueprint": [
        "................",
        "..kkkkkkkkkkk...",
        ".kNNNNNNNNNNNk..",
        ".kNwwwNNNNwNNk..",
        ".kNwNwNNNwNwNk..",
        ".kNwwwNwwwNNNk..",
        ".kNNNNNwNNNNNk..",
        ".kNwwwwwNNNwNk..",
        ".kNwNNNwNNwNwk..",
        ".kNwwwwwNNNwNk..",
        ".kNNNNNNNNNNNk..",
        "..kkkkkkkkkkk...",
        "...knnnnnnnnk...",
        "....kkkkkkkk....",
        "................",
        "................",
    ],
    "builders_wand": [
        "................",
        "...........kk...",
        "..........kprk..",
        ".........kprrk..",
        "..........krk...",
        ".........kbk....",
        "........kbk.....",
        ".......kBk......",
        "......kbk.......",
        ".....kbk........",
        "....kBk.........",
        "...kbk..........",
        "..kbk...........",
        "..kk............",
        "................",
        "................",
    ],
    "jet": [
        "................",
        "................",
        "...........kk...",
        "..........kgk...",
        "..........klk...",
        ".kk......kllk...",
        "kxxkkkkkkkllllk.",
        "kllllllllllllllk",
        ".kgggggglllllllk",
        "..kkkkkkllllkkk.",
        ".......kllk.....",
        "......kllk......",
        ".....kkkk.......",
        "................",
        "................",
        "................",
    ],
    "extended_mag": [
        "................",
        "................",
        "......kkkk......",
        "......kYyk......",
        ".....kddddk.....",
        ".....kdggdk.....",
        ".....kdggdk.....",
        ".....kdggdk.....",
        ".....kdggdk.....",
        ".....kdggdk.....",
        ".....kdggdk.....",
        "......kdggdk....",
        "......kdggdk....",
        ".......kkkkk....",
        "................",
        "................",
    ],
    "red_dot_sight": [
        "................",
        "................",
        "................",
        "....kkkkkkk.....",
        "...kdddddddk....",
        "...kdxxxxxdk....",
        "...kdxxexxdk....",
        "...kdxxxxxdk....",
        "...kdddddddk....",
        "....kkkkkkk.....",
        "....kgk.kgk.....",
        "...kkkkkkkkk....",
        "................",
        "................",
        "................",
        "................",
    ],
    "vertical_grip": [
        "................",
        "................",
        "...kkkkkkkkk....",
        "...kgggggggk....",
        "...kkkkdkkkk....",
        "......kddk......",
        "......kdgk......",
        "......kddk......",
        "......kdgk......",
        "......kddk......",
        "......kdgk......",
        "......kddk......",
        "......kkkk......",
        "................",
        "................",
        "................",
    ],
    "suppressor": [
        "................",
        "................",
        "................",
        "................",
        "................",
        "..kkkkkkkkkkkk..",
        ".kdgggggggggddk.",
        ".kddddddddddddk.",
        ".kdkdkdkdkdkddk.",
        "..kkkkkkkkkkkk..",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "jeep": [
        "................",
        "................",
        "................",
        "........kkkk....",
        "........kddk....",
        ".......kxxxk....",
        "......kxxxxk....",
        "..kkkkkkkkkkkkk.",
        ".kOOOOOOOOOOOOOk",
        ".koooooooooooook",
        ".kGGGGGGGGGGGGGk",
        "..kkkk.....kkkk.",
        "..kddk.....kddk.",
        "..kddk.....kddk.",
        "...kk.......kk..",
        "................",
    ],
    "tank": [
        "................",
        "................",
        "................",
        "................",
        "kkkkkkk.........",
        "kgggggkkkkk.....",
        ".kkkkkOOOOOk....",
        "......kooooOk...",
        "...kkkkkkkkkkkk.",
        "..kOOOOOOOOOOOOk",
        "..kooooooooooook",
        ".kdkdkdkdkdkdkdk",
        ".kdddddddddddddk",
        "..kdkdkdkdkdkdk.",
        "...kkkkkkkkkkk..",
        "................",
    ],
    "excavator": [
        "................",
        "...kkkkkkkk.....",
        "..krrrrrrrrk....",
        "..kRkkkkkrRk....",
        "...k...kbkRk....",
        "......kbk.kk....",
        ".....kbk........",
        "....kbk.........",
        "...kbk..........",
        "..kbk...........",
        ".kbk............",
        ".kk.............",
        "................",
        "................",
        "................",
        "................",
    ],
}


def noise_tile(base, spread, seed):
    rnd = random.Random(seed)
    out = []
    for y in range(16):
        row = []
        for x in range(16):
            v = rnd.randint(-spread, spread)
            row.append(tuple(max(0, min(255, c + v)) for c in base) + (255,))
        out.append(row)
    return out


def put(img, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        img[y][x] = color if len(color) == 4 else color + (255,)


def rect(img, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(img, x, y, color)


def outline(img, color=(28, 28, 32)):
    rect(img, 0, 0, 15, 0, color)
    rect(img, 0, 15, 15, 15, color)
    rect(img, 0, 0, 0, 15, color)
    rect(img, 15, 0, 15, 15, color)


def ore(base_seed, stone, spread):
    img = noise_tile(stone, spread, base_seed)
    gems = [(3, 3), (10, 2), (6, 7), (12, 9), (2, 11), (8, 12)]
    for gx, gy in gems:
        put(img, gx, gy, (255, 120, 140))
        put(img, gx + 1, gy, (210, 30, 55))
        put(img, gx, gy + 1, (210, 30, 55))
        put(img, gx + 1, gy + 1, (120, 10, 30))
    return img


def blocks():
    b = {}
    # Ruby block: faceted, bright edges.
    img = noise_tile((200, 25, 50), 10, 1)
    rect(img, 0, 0, 15, 0, (255, 120, 140))
    rect(img, 0, 0, 0, 15, (255, 120, 140))
    rect(img, 15, 0, 15, 15, (120, 10, 30))
    rect(img, 0, 15, 15, 15, (120, 10, 30))
    for i in range(2, 7):
        put(img, i, 9 - i, (255, 180, 190))
    b["ruby_block"] = img
    b["ruby_ore"] = ore(2, (125, 125, 125), 12)
    b["deepslate_ruby_ore"] = ore(3, (75, 75, 82), 10)

    # Ruby Heart: dark frame with a glowing gem.
    img = noise_tile((40, 30, 35), 6, 4)
    outline(img, (90, 70, 40))
    rect(img, 1, 1, 14, 1, (200, 160, 60))
    rect(img, 1, 14, 14, 14, (200, 160, 60))
    heart = ["..pp..pp..", ".prrrprrr.", "prrrrrrrrR", "prrrrrrrRR", ".prrrrrRR.", "..prrrRR..", "...prRR...", "....RR...."]
    for y, line in enumerate(heart):
        for x, ch in enumerate(line):
            if ch != ".":
                put(img, 3 + x, 4 + y, PAL[ch])
    b["ruby_heart"] = img

    # Armory: olive crate with a white stencil gun.
    img = noise_tile((80, 95, 50), 8, 5)
    outline(img, (40, 48, 25))
    rect(img, 1, 7, 14, 8, (55, 66, 33))
    rect(img, 3, 4, 12, 5, (220, 220, 210))
    rect(img, 4, 6, 5, 6, (220, 220, 210))
    rect(img, 3, 10, 12, 11, (220, 220, 210))
    b["armory"] = img
    img = noise_tile((80, 95, 50), 8, 6)
    outline(img, (40, 48, 25))
    rect(img, 1, 1, 14, 2, (55, 66, 33))
    rect(img, 1, 13, 14, 14, (55, 66, 33))
    b["armory_top"] = img

    # Sandbags: rows of rounded tan bags.
    img = noise_tile((195, 175, 125), 10, 7)
    for row, y in enumerate((4, 9, 14)):
        rect(img, 0, y, 15, y, (130, 110, 75))
        offset = 0 if row % 2 == 0 else 4
        for x in range(offset, 16, 8):
            rect(img, x, y - 4, x, y, (150, 130, 90))
    rect(img, 0, 15, 15, 15, (120, 100, 70))
    b["sandbags"] = img

    # Concrete wall: gray panel with seams.
    img = noise_tile((140, 140, 138), 9, 8)
    rect(img, 0, 7, 15, 7, (105, 105, 103))
    rect(img, 7, 0, 7, 6, (105, 105, 103))
    rect(img, 3, 8, 3, 15, (105, 105, 103))
    rect(img, 12, 8, 12, 15, (105, 105, 103))
    b["concrete_wall"] = img

    # Reinforced concrete: darker with rebar grid.
    img = noise_tile((110, 112, 115), 8, 9)
    for i in (3, 8, 13):
        rect(img, 0, i, 15, i, (80, 60, 45))
        rect(img, i, 0, i, 15, (80, 60, 45))
    outline(img, (70, 72, 75))
    b["reinforced_concrete"] = img

    # Blast door halves.
    for half, seed in (("blast_door_top", 10), ("blast_door_bottom", 11)):
        img = noise_tile((85, 90, 98), 6, seed)
        outline(img, (40, 42, 46))
        for y in range(2, 15, 4):
            rect(img, 2, y, 13, y, (60, 63, 68))
        if half == "blast_door_top":
            rect(img, 4, 3, 11, 6, (40, 40, 45))
            rect(img, 5, 4, 10, 5, (90, 200, 255))
        else:
            rect(img, 11, 4, 13, 6, (200, 160, 60))
            for i in range(0, 16, 4):
                rect(img, i, 13, min(15, i + 1), 14, (230, 190, 40))
        b[half] = img

    # Sentry turret.
    img = noise_tile((70, 74, 80), 6, 12)
    outline(img, (30, 30, 34))
    rect(img, 6, 3, 9, 6, (30, 30, 34))
    rect(img, 7, 4, 8, 5, (255, 60, 40))
    rect(img, 2, 10, 13, 11, (200, 160, 60))
    b["sentry_turret"] = img
    img = noise_tile((70, 74, 80), 6, 13)
    outline(img, (30, 30, 34))
    rect(img, 5, 5, 10, 10, (45, 48, 52))
    rect(img, 7, 0, 8, 7, (30, 30, 34))
    b["sentry_turret_top"] = img

    # Missile interceptor.
    img = noise_tile((95, 100, 108), 6, 14)
    outline(img, (30, 30, 34))
    for x in (3, 7, 11):
        rect(img, x, 2, x + 2, 9, (40, 42, 46))
        rect(img, x + 1, 2, x + 1, 3, (220, 40, 40))
    rect(img, 1, 12, 14, 13, (200, 160, 60))
    b["missile_interceptor"] = img

    # Radar station.
    img = noise_tile((80, 85, 92), 6, 15)
    outline(img, (30, 30, 34))
    for r, col in ((6, (40, 160, 70)), (4, (30, 120, 55)), (2, (40, 200, 90))):
        for x in range(16):
            for y in range(16):
                if abs(((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5 - r) < 0.5:
                    put(img, x, y, col)
    put(img, 10, 5, (200, 255, 200))
    b["radar_station"] = img
    img = noise_tile((80, 85, 92), 6, 16)
    outline(img, (30, 30, 34))
    rect(img, 3, 3, 12, 12, (220, 225, 230))
    rect(img, 7, 7, 8, 8, (40, 42, 46))
    b["radar_station_top"] = img

    # Alarm block.
    img = noise_tile((150, 30, 30), 8, 17)
    outline(img, (60, 10, 10))
    rect(img, 5, 4, 10, 11, (255, 210, 60))
    rect(img, 6, 5, 9, 10, (255, 120, 40))
    b["alarm_block"] = img

    # Claymore.
    img = noise_tile((70, 85, 45), 6, 18)
    outline(img, (35, 45, 22))
    rect(img, 3, 6, 12, 7, (200, 200, 190))
    b["claymore"] = img

    # Conveyor: steel frame, and an animated rubber belt (8 frames, the ridges move "up" = the way the belt runs).
    frame = noise_tile((88, 92, 100), 6, 31)
    outline(frame, (50, 54, 60))
    rect(frame, 0, 6, 15, 7, (200, 160, 40))
    b["conveyor_frame"] = frame
    belt = []
    for k in range(8):
        for y in range(16):
            row = []
            for x in range(16):
                ridge = (y + 2 * k) % 4 == 0
                edge = x in (0, 15)
                c = (60, 64, 70) if edge else (46, 46, 50) if ridge else (28, 28, 31)
                row.append(c + (255,))
            belt.append(row)
    b["conveyor_belt"] = belt

    # Reinforced glass: tinted pane with a steel frame and wire mesh.
    img = [[(150, 210, 230, 90)] * 16 for _ in range(16)]
    for i in range(16):
        for j in range(16):
            if i in (0, 15) or j in (0, 15):
                img[j][i] = (70, 76, 86, 255)
            elif i in (1, 14) or j in (1, 14):
                img[j][i] = (110, 118, 128, 255)
            elif (i + j) % 5 == 0 or (i - j) % 5 == 0:
                img[j][i] = (120, 130, 140, 150)
    for i in range(3, 7):
        img[i][i + 1] = (235, 250, 255, 170)
    b["reinforced_glass"] = img

    # Landmine.
    img = noise_tile((70, 72, 70), 6, 19)
    for x in range(16):
        for y in range(16):
            if ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5 > 7:
                img[y][x] = (0, 0, 0, 0)
    rect(img, 6, 6, 9, 9, (40, 40, 40))
    rect(img, 7, 7, 8, 8, (200, 40, 40))
    b["landmine"] = img

    # Barbed wire (transparent cross texture).
    img = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
    for x in range(16):
        y1 = 4 + int(1.5 * ((x % 4) - 1.5) ** 2 / 2)
        y2 = 11 - int(1.5 * ((x % 4) - 1.5) ** 2 / 2)
        put(img, x, y1, (150, 150, 155))
        put(img, x, y2, (150, 150, 155))
        if x % 4 == 1:
            for dy in (-1, 1):
                put(img, x, y1 + dy, (110, 110, 115))
                put(img, x, y2 + dy, (110, 110, 115))
    for y in range(16):
        put(img, 2, y, (95, 80, 60))
        put(img, 13, y, (95, 80, 60))
    b["barbed_wire"] = img

    # Mortar.
    img = noise_tile((80, 95, 50), 7, 20)
    outline(img, (40, 48, 25))
    rect(img, 5, 1, 10, 14, (55, 66, 33))
    rect(img, 6, 1, 9, 2, (30, 30, 30))
    b["mortar"] = img
    img = noise_tile((80, 95, 50), 7, 21)
    outline(img, (40, 48, 25))
    rect(img, 4, 4, 11, 11, (20, 20, 20))
    b["mortar_top"] = img

    # Missile silo.
    img = noise_tile((105, 110, 115), 6, 22)
    outline(img, (40, 42, 46))
    for i in range(0, 16, 4):
        rect(img, i, 1, min(15, i + 1), 2, (230, 190, 40))
    b["missile_silo"] = img
    img = noise_tile((105, 110, 115), 6, 23)
    outline(img, (40, 42, 46))
    rect(img, 3, 3, 12, 12, (60, 62, 66))
    rect(img, 3, 7, 12, 8, (230, 190, 40))
    rect(img, 7, 3, 8, 12, (230, 190, 40))
    b["missile_silo_top"] = img
    # Vehicle parts (used by the jeep and tank block models).
    b["vehicle_olive"] = noise_tile((88, 104, 56), 7, 30)
    b["vehicle_olive_dark"] = noise_tile((62, 74, 38), 6, 31)
    img = noise_tile((45, 45, 48), 5, 32)
    for y in range(0, 16, 3):
        rect(img, 0, y, 15, y, (25, 25, 28))
    b["vehicle_tread"] = img
    img = noise_tile((35, 35, 38), 4, 33)
    rect(img, 5, 5, 10, 10, (120, 120, 125))
    rect(img, 7, 7, 8, 8, (60, 60, 62))
    b["vehicle_tire"] = img
    img = noise_tile((140, 190, 210), 6, 34)
    rect(img, 2, 2, 5, 3, (230, 245, 255))
    b["vehicle_glass"] = img
    b["vehicle_steel"] = noise_tile((70, 74, 80), 6, 35)
    return b


def parts_atlas(seed):
    """4x4 grid of 4x4 color swatches used by the 3D gun and vehicle models (see tools/models3d.py)."""
    colors = [
        (32, 32, 36), (62, 64, 70), (104, 108, 116), (168, 172, 180),   # black, dark steel, steel, light steel
        (92, 58, 32), (140, 96, 56), (92, 108, 58), (64, 76, 40),       # wood dark, wood, olive, olive dark
        (205, 30, 55), (120, 12, 32), (196, 156, 60), (90, 210, 245),   # ruby, ruby dark, brass, lens
        (44, 44, 48), (228, 230, 234), (255, 90, 70), (200, 180, 132),  # rubber, white, red light, tan
    ]
    rnd = random.Random(seed)
    img = [[(0, 0, 0, 255)] * 16 for _ in range(16)]
    for i, (r, g, b) in enumerate(colors):
        ox, oy = (i % 4) * 4, (i // 4) * 4
        for y in range(4):
            for x in range(4):
                shade = 10 if y == 0 else (-12 if y == 3 else 0)
                n = rnd.randint(-6, 6) + shade
                img[oy + y][ox + x] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)), 255)
    return img


def main(root):
    write_png(os.path.join(root, "textures/item", "gun_parts.png"), parts_atlas(40))
    write_png(os.path.join(root, "textures/block", "vehicle_parts.png"), parts_atlas(41))
    for name, rows in ITEMS.items():
        write_png(os.path.join(root, "textures/item", name + ".png"), grid(rows))
    # Door item icon reuses the door halves.
    for name, img in blocks().items():
        write_png(os.path.join(root, "textures/block", name + ".png"), img)
    with open(os.path.join(root, "textures/block", "conveyor_belt.png.mcmeta"), "w") as f:
        f.write('{"animation": {"frametime": 2}}' + chr(10))
    door = [row[:] for row in blocks()["blast_door_top"][:8]] + [row[:] for row in blocks()["blast_door_bottom"][8:]]
    write_png(os.path.join(root, "textures/item", "blast_door.png"), door)
    icon = [[PAL[ITEMS["ruby"][y // 8][x // 8]] for x in range(128)] for y in range(128)]
    write_png(os.path.join(root, "icon.png"), icon)
    print("textures written")


if __name__ == "__main__":
    main(sys.argv[1])
