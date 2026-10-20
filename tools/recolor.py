"""Make ruby versions of Minecraft's diamond armor and tool textures by recoloring them.

Usage:  python tools/recolor.py [path to minecraft-client.jar]
Run from the project root. Needs Pillow (pip install pillow).

The textures are derived from Mojang's art, so they're fine for a private
server mod; replace them with original art before publishing the mod publicly.
"""
import colorsys
import io
import os
import sys
import zipfile

from PIL import Image

NS = "rubymod"
OUT = f"src/main/resources/assets/{NS}/textures"

JOBS = {
    "assets/minecraft/textures/entity/equipment/humanoid/diamond.png": f"{OUT}/entity/equipment/humanoid/ruby.png",
    "assets/minecraft/textures/entity/equipment/humanoid_leggings/diamond.png": f"{OUT}/entity/equipment/humanoid_leggings/ruby.png",
    "assets/minecraft/textures/item/diamond_helmet.png": f"{OUT}/item/ruby_helmet.png",
    "assets/minecraft/textures/item/diamond_chestplate.png": f"{OUT}/item/ruby_chestplate.png",
    "assets/minecraft/textures/item/diamond_leggings.png": f"{OUT}/item/ruby_leggings.png",
    "assets/minecraft/textures/item/diamond_boots.png": f"{OUT}/item/ruby_boots.png",
    "assets/minecraft/textures/item/diamond_sword.png": f"{OUT}/item/ruby_sword.png",
    "assets/minecraft/textures/item/diamond_pickaxe.png": f"{OUT}/item/ruby_pickaxe.png",
}


def to_ruby(img):
    img = img.convert("RGBA")
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            # Diamond is cyan (hue ~0.5). Shift only the gem-colored pixels to ruby red;
            # leave wood handles and dark outlines alone.
            if s > 0.25 and 0.35 < h < 0.65:
                nr, ng, nb = colorsys.hsv_to_rgb(0.97, min(1.0, s * 1.1), v * 0.95)
                px[x, y] = (int(nr * 255), int(ng * 255), int(nb * 255), a)
    return img


def main():
    jar = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.gradle/caches/fabric-loom/26.3/minecraft-client.jar")
    with zipfile.ZipFile(jar) as z:
        for src, dst in JOBS.items():
            img = to_ruby(Image.open(io.BytesIO(z.read(src))))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            img.save(dst)
    print(f"{len(JOBS)} textures recolored")


if __name__ == "__main__":
    main()
