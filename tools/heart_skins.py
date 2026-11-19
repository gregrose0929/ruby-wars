"""Ruby Heart skins: recolour the Heart's texture into its cosmetic looks.

Usage:  python tools/heart_skins.py   (from the project root; needs Pillow)

Writes textures/block/ruby_heart_<look>.png plus a blockstate with one variant per look.
"""
import json
import os

from PIL import Image

ROOT = "src/main/resources/assets/rubymod"
SRC = f"{ROOT}/textures/block/ruby_heart.png"

# The original's colours: heart (main, highlight, shade), frame, trim; the background is a dark ~(40, 30, 35).
HEART, HIGH, SHADE, FRAME, TRIM = (210, 30, 55), (255, 120, 140), (120, 10, 30), (90, 70, 40), (200, 160, 60)
BG = (40, 30, 35)

LOOKS = {
    "frost": dict(heart=(120, 200, 240), high=(235, 250, 255), shade=(50, 110, 170), frame=(150, 170, 190), trim=(220, 235, 245), bg=(30, 40, 55)),
    "emerald": dict(heart=(40, 190, 90), high=(160, 255, 185), shade=(10, 100, 40), frame=FRAME, trim=TRIM, bg=(28, 38, 30)),
    "lava": dict(heart=(255, 120, 20), high=(255, 230, 90), shade=(170, 40, 10), frame=(55, 42, 40), trim=(230, 90, 20), bg=(45, 25, 20)),
    "crystal": dict(heart=(170, 90, 230), high=(235, 195, 255), shade=(90, 40, 150), frame=(70, 50, 90), trim=(205, 175, 240), bg=(35, 25, 45)),
    "gold": dict(heart=(240, 190, 40), high=(255, 245, 165), shade=(170, 110, 10), frame=(120, 90, 30), trim=(255, 225, 95), bg=BG),
    "shadow": dict(heart=(95, 30, 125), high=(225, 85, 255), shade=(40, 10, 60), frame=(28, 22, 32), trim=(125, 65, 165), bg=(14, 11, 19)),
}


def near(a, b, d=6):
    return all(abs(x - y) <= d for x, y in zip(a, b))


def main():
    src = Image.open(SRC).convert("RGBA")
    for look, pal in LOOKS.items():
        out = src.copy()
        px = out.load()
        for y in range(out.height):
            for x in range(out.width):
                r, g, b, a = px[x, y]
                c = (r, g, b)
                for orig, new in ((HEART, pal["heart"]), (HIGH, pal["high"]), (SHADE, pal["shade"]), (FRAME, pal["frame"]), (TRIM, pal["trim"])):
                    if near(c, orig):
                        px[x, y] = new + (a,)
                        break
                else:
                    if all(v < 60 for v in c):
                        # The dark background: keep its speckle, shifted to the look's tint.
                        px[x, y] = tuple(max(0, min(255, v - o + n)) for v, o, n in zip(c, BG, pal["bg"])) + (a,)
        out.save(f"{ROOT}/textures/block/ruby_heart_{look}.png")
        with open(f"{ROOT}/models/block/ruby_heart_{look}.json", "w", newline="\n") as f:
            json.dump({"parent": "minecraft:block/cube_all", "textures": {"all": f"rubymod:block/ruby_heart_{look}"}}, f, indent="\t")
            f.write("\n")
    variants = {"look=ruby": {"model": "rubymod:block/ruby_heart"}}
    for look in LOOKS:
        variants[f"look={look}"] = {"model": f"rubymod:block/ruby_heart_{look}"}
    with open(f"{ROOT}/blockstates/ruby_heart.json", "w", newline="\n") as f:
        json.dump({"variants": variants}, f, indent="\t")
        f.write("\n")
    print("wrote", len(LOOKS), "looks")


if __name__ == "__main__":
    main()
