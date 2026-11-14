"""3D models for the hub's theme park: coaster cars, Ferris wheel gondolas, carousel horses, bumper cars, gallery
ducks, mini-golf putter, ball and flag. Boxes in 16ths of a block, front = -Z (north), drawn with a bright swatch
atlas (textures/item/park_parts.png). The server shows them through item displays (Props.model / ITEM_MODEL).

Usage:  python tools/park_models.py      (run from the project root; safe to re-run)
"""
import json
import os
import random

from textures import write_png

NS = "rubymod"
ASSETS = f"src/main/resources/assets/{NS}"

BLACK, GRAY, STEEL, WHITE, RED, RED_DARK, YELLOW, GOLD, BLUE, SKY, GREEN, LIME, PINK, PURPLE, ORANGE, WOOD = range(16)
COLORS = [
    (34, 34, 40), (92, 96, 108), (176, 182, 192), (242, 242, 242),
    (226, 40, 52), (150, 22, 34), (255, 212, 44), (232, 168, 36),
    (44, 112, 232), (116, 204, 255), (52, 182, 72), (146, 232, 64),
    (255, 124, 192), (152, 72, 224), (255, 142, 32), (150, 100, 60),
]


def atlas():
    rnd = random.Random(7)
    img = [[(0, 0, 0, 255)] * 16 for _ in range(16)]
    for i, (r, g, b) in enumerate(COLORS):
        ox, oy = (i % 4) * 4, (i // 4) * 4
        for y in range(4):
            for x in range(4):
                n = rnd.randint(-5, 5) + (8 if y == 0 else (-10 if y == 3 else 0))
                img[oy + y][ox + x] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)), 255)
    return img


def uv(s):
    u, v = (s % 4) * 4, (s // 4) * 4
    return [u + 0.5, v + 0.5, u + 3.5, v + 3.5]


def b(frm, to, s, rot=None):
    e = {"from": frm, "to": to, "faces": {f: {"uv": uv(s), "texture": "#parts"} for f in ("north", "south", "east", "west", "up", "down")}}
    if rot:
        e["rotation"] = rot
    return e


def coaster_car(body, trim):
    return [
        b([3, 0, 1], [13, 2, 15], BLACK),                               # chassis
        *[b([x, 0, z], [x + 1.5, 2.5, z + 3], GRAY) for x in (1.5, 13) for z in (2, 11)],   # wheels
        b([2, 2, 1], [14, 4, 15], body),                                # floor
        b([2, 4, 1], [3, 8, 15], body),                                 # sides
        b([13, 4, 1], [14, 8, 15], body),
        b([2, 4, 15], [14, 9, 16], body),                               # back
        b([2, 2, -1.5], [14, 7, 1], body),                              # rounded nose
        b([3, 7, -1], [13, 8, 1], trim),
        b([4, 4, -1.7], [6, 5.5, -1.5], YELLOW),                        # headlights
        b([10, 4, -1.7], [12, 5.5, -1.5], YELLOW),
        b([2.2, 6, 1], [2.4, 7, 15], trim),                             # side stripes
        b([13.6, 6, 1], [13.8, 7, 15], trim),
        b([3, 4, 3], [13, 6, 7], BLACK),                                # front seat
        b([3, 6, 6], [13, 10, 7.5], BLACK),
        b([3, 4, 9.5], [13, 6, 13.5], BLACK),                           # back seat
        b([3, 6, 12.5], [13, 11, 14], BLACK),
        b([3, 8.5, 2.5], [13, 9.2, 3.2], STEEL),                        # lap bars
        b([3, 8.5, 9], [13, 9.2, 9.7], STEEL),
    ]


def gondola(color):
    return [
        b([7.4, 15, 7.4], [8.6, 20, 8.6], STEEL),                       # hanger
        b([1.5, 14, 1.5], [14.5, 15, 14.5], color),                     # roof
        b([4, 15, 4], [12, 16, 12], WHITE),
        b([2, 0, 2], [14, 1, 14], GRAY),                                # floor
        b([2, 1, 2], [14, 6, 3], color), b([2, 1, 13], [14, 6, 14], color),
        b([2, 1, 3], [3, 6, 13], color), b([13, 1, 3], [14, 6, 13], color),
        b([2, 5.5, 2], [14, 6.3, 14], WHITE),                           # rim
        *[b([x, 6, z], [x + 1, 14, z + 1], WHITE) for x in (2, 13) for z in (2, 13)],   # posts
        b([3, 1, 6], [13, 3.5, 10], WOOD),                              # bench
    ]


def carousel_horse(body, saddle):
    return [
        b([7.4, -8, 7.4], [8.6, 26, 8.6], GOLD),                        # pole
        b([5, 7, 3], [11, 12, 13], body),                               # body
        b([5.5, 11, 0.5], [10.5, 16, 4], body),                         # neck
        b([5.5, 14, -3], [10.5, 18, 1.5], body),                        # head
        b([6, 14.5, -4], [10, 16.5, -3], body),                         # muzzle
        b([5.4, 16, -3.1], [6, 17, -2.2], BLACK), b([10, 16, -3.1], [10.6, 17, -2.2], BLACK),   # eyes
        b([7, 13, 2], [9, 19, 5], saddle),                              # mane
        b([6, 17.5, -0.5], [7, 19.5, 0.5], body), b([9, 17.5, -0.5], [10, 19.5, 0.5], body),     # ears
        b([4.8, 11.8, 5.5], [11.2, 12.6, 10.5], saddle),                # saddle
        b([4.6, 9, 7.5], [5, 12, 8.5], GOLD), b([11, 9, 7.5], [11.4, 12, 8.5], GOLD),            # stirrups
        b([7, 9, 13], [9, 12, 16], saddle),                             # tail
        b([5.2, 1, 3.5], [6.8, 7, 5], body), b([9.2, 1, 3.5], [10.8, 7, 5], body),             # legs
        b([5.2, 1, 11], [6.8, 7, 12.5], body), b([9.2, 1, 11], [10.8, 7, 12.5], body),
        b([5.2, 0, 3.5], [6.8, 1, 5], GOLD), b([9.2, 0, 3.5], [10.8, 1, 5], GOLD),             # hooves
        b([5.2, 0, 11], [6.8, 1, 12.5], GOLD), b([9.2, 0, 11], [10.8, 1, 12.5], GOLD),
    ]


def bumper_car(color):
    return [
        b([0, 0, 0], [16, 2.5, 16], BLACK),                             # rubber bumper
        b([1, 2.5, 1], [15, 6, 15], color),                             # body
        b([1.5, 6, 0.5], [14.5, 7, 4], color),                          # bonnet
        b([3, 6, 6], [13, 7, 10.5], BLACK),                             # seat
        b([3, 6, 10.5], [13, 11, 12.5], BLACK),                         # seat back
        b([1, 6, 12.5], [15, 8, 15], color),                            # rear
        b([7.5, 6, 3.5], [8.5, 10, 4.5], STEEL),                        # steering column
        b([5.5, 9.6, 2.8], [10.5, 10.4, 5.2], BLACK),                   # wheel
        b([7.7, 8, 13.5], [8.3, 26, 14.1], STEEL),                      # trolley pole
        b([7.2, 26, 13], [8.8, 27, 14.6], YELLOW),
        b([3, 3.5, -0.2], [5, 5, 1], YELLOW), b([11, 3.5, -0.2], [13, 5, 1], YELLOW),   # headlights
        b([0.8, 4, 5], [1.1, 5, 11], WHITE), b([14.9, 4, 5], [15.2, 5, 11], WHITE),       # stripes
    ]


DUCK = [
    b([4, 2, 4], [12, 8, 14], YELLOW),                                  # body
    b([5, 7, 1.5], [11, 12, 6.5], YELLOW),                              # head
    b([6.5, 8, -0.8], [9.5, 9.8, 1.5], ORANGE),                         # beak
    b([4.8, 10, 2.5], [5.2, 11, 3.5], BLACK), b([10.8, 10, 2.5], [11.2, 11, 3.5], BLACK),   # eyes
    b([5, 5, 13], [11, 9, 16], YELLOW),                                 # tail
    b([3.6, 4, 7], [4.2, 7, 12], GOLD), b([11.8, 4, 7], [12.4, 7, 12], GOLD),               # wings
    b([7, 0, 3], [9, 2, 15], RED),                                      # target band
    b([7.5, -8, 8], [8.5, 2, 9], WOOD),                                 # stick
]

GOLF_BALL = [
    b([5, 5, 5], [11, 11, 11], WHITE),
    b([4.4, 6, 6], [11.6, 10, 10], WHITE),
    b([6, 4.4, 6], [10, 11.6, 10], WHITE),
    b([6, 6, 4.4], [10, 10, 11.6], WHITE),
]

PUTTER = [
    b([7.4, 2, 7.4], [8.6, 16, 8.6], STEEL),                            # shaft
    b([7.1, 11, 7.1], [8.9, 16, 8.9], BLACK),                           # grip
    b([4, 0, 6.6], [11, 2.5, 9.4], GOLD),                               # head
    b([4, 0, 6.4], [11, 2.5, 6.6], STEEL),                              # face
]

GOLF_FLAG = [
    b([7.6, 0, 7.6], [8.4, 30, 8.4], WHITE),
    b([8.4, 22, 7.8], [17, 29, 8.2], RED),
    b([7.4, 30, 7.4], [8.6, 31, 8.6], YELLOW),
]

ZIP_HANDLE = [
    b([1, 0, 7], [15, 1.5, 9], RED),                                    # handlebar
    b([1, 0, 7], [2, 12, 9], STEEL), b([14, 0, 7], [15, 12, 9], STEEL),  # straps
    b([1, 12, 6], [15, 14, 10], GRAY),                                  # trolley
    b([3, 14, 7], [5, 16, 9], BLACK), b([11, 14, 7], [13, 16, 9], BLACK),  # pulleys
]

MODELS = {
    "coaster_car": coaster_car(RED, YELLOW),
    "coaster_car_blue": coaster_car(BLUE, WHITE),
    "gondola_red": gondola(RED), "gondola_blue": gondola(BLUE), "gondola_yellow": gondola(YELLOW), "gondola_green": gondola(GREEN),
    "horse_white": carousel_horse(WHITE, PINK), "horse_brown": carousel_horse(WOOD, BLUE),
    "horse_black": carousel_horse(BLACK, RED), "horse_gold": carousel_horse(GOLD, PURPLE),
    "bumper_red": bumper_car(RED), "bumper_blue": bumper_car(BLUE), "bumper_green": bumper_car(GREEN),
    "bumper_yellow": bumper_car(YELLOW), "bumper_pink": bumper_car(PINK), "bumper_purple": bumper_car(PURPLE),
    "duck": DUCK, "golf_ball": GOLF_BALL, "golf_flag": GOLF_FLAG, "zip_handle": ZIP_HANDLE,
}
HANDHELD = {"putter": PUTTER}


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as f:
        json.dump(obj, f, indent="\t")
        f.write("\n")


def main():
    write_png(f"{ASSETS}/textures/item/park_parts.png", atlas())
    tex = {"parts": f"{NS}:item/park_parts", "particle": f"{NS}:item/park_parts"}
    for name, elements in MODELS.items():
        write(f"{ASSETS}/models/item/park_{name}.json", {"textures": tex, "elements": elements})
        write(f"{ASSETS}/items/park_{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/park_{name}"}})
    for name, elements in HANDHELD.items():
        write(f"{ASSETS}/models/item/park_{name}.json", {"parent": "minecraft:item/handheld", "textures": tex, "elements": elements})
        write(f"{ASSETS}/items/park_{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/park_{name}"}})
    print(f"park models written: {len(MODELS) + len(HANDHELD)}")


if __name__ == "__main__":
    main()
