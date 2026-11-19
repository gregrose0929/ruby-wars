"""Textures and models for the Belt Loader and the Belt Sorter.

Usage:  python tools/belt_textures.py   (from the project root; needs Pillow)
"""
import json
import os

from PIL import Image, ImageDraw

ROOT = "src/main/resources/assets/rubymod"
TEX = f"{ROOT}/textures/block"

STEEL = (58, 62, 70)
STEEL_HI = (92, 98, 110)
STEEL_LO = (34, 36, 42)
YELLOW = (240, 190, 40)
RUBY = (212, 30, 64)
RUBY_HI = (255, 110, 140)


def panel(size=16, base=STEEL):
    im = Image.new("RGBA", (size, size), base + (255,))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, size - 1, size - 1], outline=STEEL_LO + (255,))
    d.line([1, 1, size - 2, 1], fill=STEEL_HI + (255,))
    d.line([1, 1, 1, size - 2], fill=STEEL_HI + (255,))
    for (x, y) in ((2, 2), (size - 3, 2), (2, size - 3), (size - 3, size - 3)):
        d.point((x, y), fill=(140, 146, 156, 255))
    return im


def loader_top():
    # An arrow pointing north (the way it pushes), on a hazard-striped steel plate.
    im = panel()
    d = ImageDraw.Draw(im)
    for i in range(-16, 16, 4):
        d.line([i, 15, i + 3, 12], fill=YELLOW + (255,))
    d.rectangle([0, 12, 15, 12], fill=STEEL_LO + (255,))
    d.polygon([(8, 2), (3, 7), (6, 7), (6, 11), (10, 11), (10, 7), (13, 7)], fill=RUBY + (255,))
    d.line([(8, 3), (5, 6)], fill=RUBY_HI + (255,))
    return im


def loader_front():
    # The mouth it pushes things out of.
    im = panel()
    d = ImageDraw.Draw(im)
    d.rectangle([3, 4, 12, 13], fill=(16, 16, 20, 255))
    d.rectangle([3, 4, 12, 4], fill=YELLOW + (255,))
    for x in range(4, 12, 2):
        d.point((x, 8), fill=(60, 60, 66, 255))
    return im


def loader_back():
    # The intake, against the chest.
    im = panel()
    d = ImageDraw.Draw(im)
    d.rectangle([2, 2, 13, 13], fill=(24, 24, 28, 255))
    for y in range(3, 13, 3):
        d.line([3, y, 12, y], fill=STEEL_HI + (255,))
    return im


def loader_side():
    im = panel()
    d = ImageDraw.Draw(im)
    d.rectangle([2, 9, 13, 10], fill=RUBY + (255,))
    d.rectangle([4, 4, 11, 6], fill=STEEL_LO + (255,))
    return im


def sorter_mark(right):
    # A curved arrow off to one side, on a clear background, laid over the moving belt.
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    # Pointing north is "forward"; left is west.
    d.rectangle([7, 9, 9, 15], fill=RUBY + (255,))
    d.rectangle([2, 7, 9, 9], fill=RUBY + (255,))
    d.polygon([(0, 8), (4, 4), (4, 12)], fill=RUBY + (255,))
    d.line([(7, 15), (7, 10)], fill=RUBY_HI + (255,))
    # Little side rails, so it reads as a different piece.
    d.rectangle([0, 0, 15, 0], fill=YELLOW + (255,))
    d.rectangle([15, 0, 15, 15], fill=YELLOW + (255,))
    return im.transpose(Image.FLIP_LEFT_RIGHT) if right else im


def face(tex, uv=None):
    f = {"texture": tex}
    if uv:
        f["uv"] = uv
    return f


def write(path, data):
    with open(path, "w", newline="\n") as f:
        json.dump(data, f, indent="\t")
        f.write("\n")


def main():
    loader_top().save(f"{TEX}/belt_loader_top.png")
    loader_front().save(f"{TEX}/belt_loader_front.png")
    loader_back().save(f"{TEX}/belt_loader_back.png")
    loader_side().save(f"{TEX}/belt_loader_side.png")
    sorter_mark(False).save(f"{TEX}/belt_sorter_left.png")
    sorter_mark(True).save(f"{TEX}/belt_sorter_right.png")
    tex = {"particle": "rubymod:block/belt_loader_side", "top": "rubymod:block/belt_loader_top", "front": "rubymod:block/belt_loader_front",
           "back": "rubymod:block/belt_loader_back", "side": "rubymod:block/belt_loader_side", "frame": "rubymod:block/conveyor_frame"}
    write(f"{ROOT}/models/block/belt_loader.json", {
        "parent": "minecraft:block/block",
        "textures": tex,
        "elements": [
            # The body (front is north).
            {"from": [1, 0, 1], "to": [15, 9, 15], "faces": {
                "north": face("#front", [1, 7, 15, 16]), "south": face("#back", [1, 7, 15, 16]), "east": face("#side", [1, 7, 15, 16]),
                "west": face("#side", [1, 7, 15, 16]), "up": face("#top"), "down": face("#frame")}},
            # A lip round the top and a little chute out the front.
            {"from": [4, 2, 0], "to": [12, 7, 1], "faces": {
                "north": face("#front", [4, 4, 12, 9]), "east": face("#frame"), "west": face("#frame"), "up": face("#frame"), "down": face("#frame")}},
            {"from": [1, 9, 1], "to": [15, 10, 2], "faces": {"north": face("#frame"), "up": face("#frame"), "south": face("#frame")}},
            {"from": [1, 9, 14], "to": [15, 10, 15], "faces": {"south": face("#frame"), "up": face("#frame"), "north": face("#frame")}},
        ]})
    variants = {}
    for d, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        variants[f"facing={d}"] = {"model": "rubymod:block/belt_loader", "y": y}
    write(f"{ROOT}/blockstates/belt_loader.json", {"variants": variants})
    # The sorter: a belt with its arrow laid over the top.
    base = json.load(open(f"{ROOT}/models/block/conveyor.json"))
    for side in ("left", "right"):
        m = json.loads(json.dumps(base))
        m["textures"]["mark"] = f"rubymod:block/belt_sorter_{side}"
        m["elements"].append({"from": [0, 4.02, 0], "to": [16, 4.02, 16], "faces": {"up": face("#mark")}})
        write(f"{ROOT}/models/block/belt_sorter_{side}.json", m)
    variants = {}
    for d, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for right in ("false", "true"):
            variants[f"facing={d},right={right}"] = {"model": f"rubymod:block/belt_sorter_{'right' if right == 'true' else 'left'}", "y": y}
    write(f"{ROOT}/blockstates/belt_sorter.json", {"variants": variants})
    for name, model in (("belt_loader", "belt_loader"), ("belt_sorter", "belt_sorter_left")):
        write(f"{ROOT}/items/{name}.json", {"model": {"type": "minecraft:model", "model": f"rubymod:block/{model}"}})
        write(f"src/main/resources/data/rubymod/loot_table/blocks/{name}.json", {
            "type": "minecraft:block", "pools": [{"rolls": 1, "condition": {"type": "minecraft:survives_explosion"},
                                                  "entries": [{"type": "minecraft:item", "name": f"rubymod:{name}"}]}],
            "random_sequence": f"rubymod:blocks/{name}"})
    print("ok")


if __name__ == "__main__":
    main()
