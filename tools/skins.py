"""Soldier skins (64x64 player-skin layout) for hired guards and raiders.

Run from the project root: python tools/skins.py
Writes src/main/resources/assets/rubymod/textures/entity/soldier/<name>.png
"""
import math
import os
import random

from aircraft_models import write_png

OUT = "src/main/resources/assets/rubymod/textures/entity/soldier"

# Face rectangles of each part in the standard 64x64 skin: name -> {face: (x, y, w, h)}
def part(x, y, w, h, d):
    return {"top": (x + d, y, w, d), "bottom": (x + d + w, y, w, d), "right": (x, y + d, d, h), "front": (x + d, y + d, w, h),
            "left": (x + d + w, y + d, d, h), "back": (x + 2 * d + w, y + d, w, h)}


PARTS = {
    "head": part(0, 0, 8, 8, 8), "hat": part(32, 0, 8, 8, 8),
    "body": part(16, 16, 8, 12, 4), "jacket": part(16, 32, 8, 12, 4),
    "right_arm": part(40, 16, 4, 12, 4), "left_arm": part(32, 48, 4, 12, 4),
    "right_leg": part(0, 16, 4, 12, 4), "left_leg": part(16, 48, 4, 12, 4),
}


def camo(x, y, base, dark, light, seed):
    k = math.sin(x * 0.9 + seed) + math.sin(y * 0.7 + x * 0.3 + seed * 2) + math.sin((x - y) * 0.5 + seed)
    return dark if k > 0.9 else light if k < -1.1 else base


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def skin(kind):
    rnd = random.Random(7 if kind == "guard" else 11)
    img = [[(0, 0, 0, 0)] * 64 for _ in range(64)]
    if kind == "guard":
        base, dark, light = (86, 98, 62), (52, 62, 38), (122, 112, 78)
        vest, band, helmet = (112, 104, 72), (205, 30, 60), (78, 90, 56)
        skin_c = (198, 150, 116)
    else:
        base, dark, light = (168, 146, 104), (120, 100, 70), (196, 178, 136)
        vest, band, helmet = (52, 50, 46), (190, 30, 30), (40, 40, 40)
        skin_c = (40, 38, 38)                    # balaclava

    def put(px, py, c):
        n = rnd.randint(-5, 5)
        img[py][px] = tuple(max(0, min(255, v + n)) for v in c) + (255,)

    def fill(part_name, painter):
        for face, (fx, fy, fw, fh) in PARTS[part_name].items():
            for y in range(fh):
                for x in range(fw):
                    c = painter(face, x, y, fw, fh)
                    if c is not None:
                        put(fx + x, fy + y, c)

    # Head: face (or balaclava), eyes, ears; the hat layer is the helmet.
    def head(face, x, y, w, h):
        c = skin_c
        if face == "front":
            if y == 4 and x in (1, 2, 5, 6):
                c = (245, 245, 245) if x in (1, 6) else (40, 60, 90)       # eyes
            if kind == "raider":
                if 3 <= y <= 5:
                    c = (150, 110, 90) if not (y == 4 and x in (1, 2, 5, 6)) else ((245, 245, 245) if x in (1, 6) else (60, 40, 30))
                if y >= 6:
                    c = (178, 34, 34)                                        # bandana
            else:
                if y == 6 and 2 <= x <= 5:
                    c = shade(skin_c, 0.8)                                   # mouth
                if y >= 7:
                    c = shade(skin_c, 0.9)
        if face == "top":
            c = (60, 46, 34) if kind == "guard" else skin_c
        return c
    fill("head", head)

    def helm(face, x, y, w, h):
        if face == "bottom":
            return None
        if face == "front" and y >= 3:
            if y == 3 and 1 <= x <= 6 and kind == "guard":
                return (40, 40, 40) if x in (1, 6) else (90, 140, 170)    # goggles on the brim
            return None
        if face in ("right", "left", "back") and y >= 5:
            return None
        c = camo(x, y, helmet, shade(helmet, 0.75), shade(helmet, 1.2), 3)
        if (x + y) % 3 == 0 and kind == "guard":
            c = shade(c, 0.85)                                               # helmet netting
        return c
    fill("hat", helm)

    # Body: camo shirt with a plate carrier, pouches and belt.
    def body(face, x, y, w, h):
        c = camo(x + (8 if face == "back" else 0), y, base, dark, light, 1)
        if face in ("front", "back") and 2 <= y <= 9:
            c = vest
            if face == "front" and 5 <= y <= 8 and x % 3 != 2:
                c = shade(vest, 0.8)                                         # mag pouches
            if face == "front" and y == 2 and x in (1, 6):
                c = shade(vest, 0.7)
        if face in ("right", "left") and 3 <= y <= 9:
            c = shade(vest, 0.9)
        if y == 10:
            c = (44, 38, 30)                                                 # belt
            if face == "front" and x in (3, 4):
                c = (170, 160, 120)                                          # buckle
        return c
    fill("body", body)

    def arm(face, x, y, w, h, banded):
        c = camo(x, y + 20, base, dark, light, 2)
        if y >= 10:
            c = (36, 34, 32)                                                 # gloves
        if banded and 3 <= y <= 4:
            c = band
        return c
    fill("right_arm", lambda f, x, y, w, h: arm(f, x, y, w, h, False))
    fill("left_arm", lambda f, x, y, w, h: arm(f, x, y, w, h, True))

    def leg(face, x, y, w, h):
        c = camo(x, y + 40, base, dark, light, 4)
        if face == "front" and 5 <= y <= 6:
            c = (50, 50, 46)                                                 # knee pads
        if y >= 9:
            c = (54, 42, 30) if kind == "guard" else (30, 28, 26)            # boots
        if face == "right" and 2 <= y <= 4:
            c = shade(c, 0.85)                                               # thigh pocket
        return c
    fill("right_leg", leg)
    fill("left_leg", leg)
    return img


def main():
    os.makedirs(OUT, exist_ok=True)
    for kind in ("guard", "raider"):
        write_png(os.path.join(OUT, kind + ".png"), skin(kind))
    print("soldier skins written")


if __name__ == "__main__":
    main()
