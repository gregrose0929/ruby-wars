"""Textures and models for the security blocks and moving parts (they used to borrow vanilla blocks' looks).

Usage:  python tools/security_models.py   (from the project root; needs Pillow)

Keycard Reader, Laser Tripwire, Monitor, Security Camera, Seismic Sensor, Ruby Bearing, Ruby Piston, Keycard Door.
"""
import json
import math
import os

from PIL import Image, ImageDraw

from belt_textures import STEEL, STEEL_HI, STEEL_LO, YELLOW, RUBY, RUBY_HI, panel

ROOT = "src/main/resources/assets/rubymod"
TEX = f"{ROOT}/textures/block"
GREEN = (80, 230, 120)
RED = (240, 50, 50)
SCREEN = (20, 40, 30)


def write(path, data):
    with open(path, "w", newline="\n") as f:
        json.dump(data, f, indent="\t")
        f.write("\n")


def save(im, name):
    im.save(f"{TEX}/{name}.png")


def px(d, x, y, c):
    d.point((x, y), fill=c + (255,))


# ------------------------------------------------------------------ textures

def reader_side(on):
    im = panel()
    d = ImageDraw.Draw(im)
    d.rectangle([3, 2, 12, 6], fill=SCREEN + (255,))                         # little screen
    d.line([4, 4, 9 if on else 6, 4], fill=(GREEN if on else (60, 90, 70)) + (255,))
    d.rectangle([3, 8, 12, 9], fill=(14, 14, 16, 255))                       # card slot
    d.line([3, 10, 12, 10], fill=RUBY + (255,))
    for y in (12, 14):
        for x in (4, 7, 10):
            d.rectangle([x, y, x + 1, y], fill=(110, 116, 126, 255))         # keypad
    d.rectangle([12, 12, 13, 13], fill=(GREEN if on else RED) + (255,))      # status light
    return im


def vents(base=None):
    im = panel() if base is None else base
    d = ImageDraw.Draw(im)
    for y in range(4, 13, 2):
        d.line([4, y, 11, y], fill=STEEL_LO + (255,))
    return im


def tripwire_front():
    im = panel()
    d = ImageDraw.Draw(im)
    d.ellipse([3, 3, 12, 12], fill=(24, 24, 28, 255))
    d.ellipse([5, 5, 10, 10], fill=(150, 20, 30, 255))
    d.ellipse([6, 6, 9, 9], fill=RED + (255,))
    px(d, 7, 6, (255, 200, 200))
    return im


def tripwire_side():
    im = panel()
    d = ImageDraw.Draw(im)
    for i in range(-16, 16, 4):
        d.line([i, 10, i + 3, 7], fill=YELLOW + (255,))
    d.rectangle([0, 6, 15, 6], fill=STEEL_LO + (255,))
    d.rectangle([0, 11, 15, 11], fill=STEEL_LO + (255,))
    for x in range(0, 16):
        for y in (7, 8, 9, 10):
            if ((x + y) // 2) % 2:
                px(d, x, y, (30, 30, 32))
    return im


def monitor_side():
    im = Image.new("RGBA", (16, 16), (30, 32, 36, 255))
    d = ImageDraw.Draw(im)
    d.rectangle([1, 1, 14, 11], fill=SCREEN + (255,))
    # A camera picture: a horizon, a wall, scan lines.
    d.rectangle([2, 7, 13, 10], fill=(28, 70, 40, 255))
    d.rectangle([9, 4, 12, 7], fill=(40, 90, 56, 255))
    for y in range(2, 11, 2):
        d.line([2, y, 13, y], fill=(34, 56, 42, 255))
    d.rectangle([11, 2, 12, 3], fill=RED + (255,))                           # REC
    d.rectangle([0, 12, 15, 15], fill=STEEL + (255,))
    d.rectangle([6, 13, 9, 14], fill=STEEL_LO + (255,))
    px(d, 2, 13, (80, 230, 120))
    return im


def camera_body():
    im = Image.new("RGBA", (16, 16), (196, 200, 206, 255))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=(150, 154, 160, 255))
    d.line([0, 11, 15, 11], fill=(150, 154, 160, 255))
    d.rectangle([2, 2, 5, 3], fill=RUBY + (255,))
    return im


def camera_lens():
    im = Image.new("RGBA", (16, 16), (20, 20, 24, 255))
    d = ImageDraw.Draw(im)
    d.ellipse([2, 2, 13, 13], fill=(40, 44, 60, 255))
    d.ellipse([5, 5, 10, 10], fill=(30, 60, 120, 255))
    px(d, 6, 6, (180, 210, 255))
    return im


def dark():
    im = Image.new("RGBA", (16, 16), (40, 42, 48, 255))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=(26, 28, 32, 255))
    return im


def sensor_base():
    im = panel()
    d = ImageDraw.Draw(im)
    for i in range(-16, 16, 4):
        d.line([i, 15, i + 3, 12], fill=YELLOW + (255,))
    d.ellipse([4, 4, 11, 11], outline=(70, 200, 210, 255))
    return im


def sensor_dome():
    im = Image.new("RGBA", (16, 16), (40, 90, 100, 255))
    d = ImageDraw.Draw(im)
    for y in range(1, 16, 3):
        d.line([0, y, 15, y], fill=(80, 200, 210, 255))
    d.rectangle([0, 0, 15, 15], outline=(24, 50, 56, 255))
    return im


def bearing_front():
    im = panel()
    d = ImageDraw.Draw(im)
    d.ellipse([1, 1, 14, 14], fill=(30, 32, 36, 255))
    d.ellipse([2, 2, 13, 13], outline=RUBY + (255,))
    for a in range(0, 360, 45):
        x = 7.5 + math.cos(math.radians(a)) * 4.2
        y = 7.5 + math.sin(math.radians(a)) * 4.2
        d.ellipse([x - 1, y - 1, x + 1, y + 1], fill=(190, 196, 206, 255))   # ball bearings
    d.ellipse([5, 5, 10, 10], fill=STEEL_HI + (255,))
    d.rectangle([7, 7, 8, 8], fill=RUBY_HI + (255,))
    return im


def bearing_side():
    im = panel()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 6, 15, 9], fill=RUBY + (255,))
    d.line([0, 6, 15, 6], fill=RUBY_HI + (255,))
    for x in (3, 12):
        d.rectangle([x, 2, x + 1, 3], fill=(140, 146, 156, 255))
        d.rectangle([x, 12, x + 1, 13], fill=(140, 146, 156, 255))
    return im


def piston_top():
    im = Image.new("RGBA", (16, 16), STEEL_HI + (255,))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=STEEL_LO + (255,))
    d.rectangle([2, 2, 13, 13], fill=RUBY + (255,))
    d.rectangle([2, 2, 13, 2], fill=RUBY_HI + (255,))
    d.rectangle([6, 6, 9, 9], fill=(130, 16, 40, 255))
    for (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        px(d, x, y, (200, 206, 214))
    return im


def piston_side():
    im = panel()
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 3], fill=RUBY + (255,))                          # the pushing plate's edge
    d.line([0, 0, 15, 0], fill=RUBY_HI + (255,))
    d.line([0, 4, 15, 4], fill=STEEL_LO + (255,))
    d.polygon([(8, 6), (5, 9), (7, 9), (7, 13), (9, 13), (9, 9), (11, 9)], fill=YELLOW + (255,))
    return im


def piston_bottom():
    return vents()


def door(half):
    im = Image.new("RGBA", (16, 16), (88, 94, 104, 255))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], outline=STEEL_LO + (255,))
    d.rectangle([1, 1, 14, 14], outline=STEEL_HI + (255,))
    for y in range(3, 14, 4):
        d.line([2, y, 13, y], fill=(70, 76, 84, 255))
    if half == "top":
        d.rectangle([4, 3, 11, 8], fill=(30, 50, 64, 255))                  # window
        d.line([4, 3, 11, 3], fill=(120, 160, 190, 255))
        d.rectangle([0, 12, 15, 13], fill=RUBY + (255,))
    else:
        d.rectangle([0, 1, 15, 2], fill=RUBY + (255,))
        d.rectangle([10, 4, 13, 9], fill=(24, 24, 28, 255))                 # the card reader
        d.rectangle([11, 5, 12, 5], fill=GREEN + (255,))
        d.line([10, 7, 13, 7], fill=RUBY + (255,))
        d.rectangle([2, 6, 3, 8], fill=(200, 206, 214, 255))                # handle
        for i in range(-16, 16, 4):
            d.line([i, 15, i + 3, 12], fill=YELLOW + (255,))
    return im


def door_item():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    top, bottom = door("top"), door("bottom")
    small_t = top.resize((8, 8), Image.NEAREST)
    small_b = bottom.resize((8, 8), Image.NEAREST)
    im.paste(small_t, (4, 0))
    im.paste(small_b, (4, 8))
    return im


# ------------------------------------------------------------------ models

def cube(faces, particle):
    """A full block with a texture per face (north/south/east/west/up/down)."""
    return {"parent": "minecraft:block/block", "textures": {**{k: v for k, v in faces.items()}, "particle": particle},
            "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                          "faces": {f: {"texture": "#" + f, "cullface": f} for f in ("north", "south", "east", "west", "up", "down")}}]}


def el(frm, to, tex, faces=("north", "south", "east", "west", "up", "down"), per=None):
    per = per or {}
    return {"from": frm, "to": to, "faces": {f: {"texture": "#" + per.get(f, tex)} for f in faces}}


def main():
    t = "rubymod:block/"
    save(reader_side(False), "keycard_reader_side")
    save(reader_side(True), "keycard_reader_side_on")
    save(vents(), "security_vents")
    save(panel(), "security_steel")
    save(tripwire_front(), "laser_tripwire_front")
    save(tripwire_side(), "laser_tripwire_side")
    save(monitor_side(), "monitor_screen")
    save(camera_body(), "security_camera_body")
    save(camera_lens(), "security_camera_lens")
    save(dark(), "security_dark")
    save(sensor_base(), "seismic_sensor_base")
    save(sensor_dome(), "seismic_sensor_dome")
    save(bearing_front(), "ruby_bearing_front")
    save(bearing_side(), "ruby_bearing_side")
    save(piston_top(), "ruby_piston_top")
    save(piston_side(), "ruby_piston_side")
    save(piston_bottom(), "ruby_piston_bottom")
    save(door("top"), "keycard_door_top")
    save(door("bottom"), "keycard_door_bottom")
    door_item().save(f"{ROOT}/textures/item/keycard_door.png")
    M = f"{ROOT}/models/block"
    S = f"{ROOT}/blockstates"
    I = f"{ROOT}/items"

    # Keycard Reader: a reader panel on every side; the light and screen go green when it's powered.
    for on in (False, True):
        side = t + ("keycard_reader_side_on" if on else "keycard_reader_side")
        write(f"{M}/keycard_reader{'_on' if on else ''}.json", cube({"north": side, "south": side, "east": side, "west": side,
                                                                      "up": t + "security_vents", "down": t + "security_steel"}, side))
    write(f"{S}/keycard_reader.json", {"variants": {"powered=false": {"model": "rubymod:block/keycard_reader"},
                                                    "powered=true": {"model": "rubymod:block/keycard_reader_on"}}})
    write(f"{I}/keycard_reader.json", {"model": {"type": "minecraft:model", "model": "rubymod:block/keycard_reader"}})

    # Laser Tripwire: the red lens on the front (north, like the observer it replaces), hazard stripes round the sides.
    write(f"{M}/laser_tripwire.json", cube({"north": t + "laser_tripwire_front", "south": t + "security_vents", "east": t + "laser_tripwire_side",
                                            "west": t + "laser_tripwire_side", "up": t + "laser_tripwire_side", "down": t + "laser_tripwire_side"},
                                           t + "laser_tripwire_side"))
    states = json.load(open(f"{S}/laser_tripwire.json"))
    for v in states["variants"].values():
        v["model"] = "rubymod:block/laser_tripwire"
    write(f"{S}/laser_tripwire.json", states)
    write(f"{I}/laser_tripwire.json", {"model": {"type": "minecraft:model", "model": "rubymod:block/laser_tripwire"}})

    # Monitor: a screen showing the camera picture on each side.
    write(f"{M}/monitor.json", cube({"north": t + "monitor_screen", "south": t + "monitor_screen", "east": t + "monitor_screen",
                                     "west": t + "monitor_screen", "up": t + "security_vents", "down": t + "security_steel"}, t + "monitor_screen"))
    write(f"{S}/monitor.json", {"variants": {"": {"model": "rubymod:block/monitor"}}})
    write(f"{I}/monitor.json", {"model": {"type": "minecraft:model", "model": "rubymod:block/monitor"}})

    # Security Camera: a mount on top, an arm, the camera body and its lens looking north.
    write(f"{M}/security_camera.json", {
        "parent": "minecraft:block/block",
        "textures": {"body": t + "security_camera_body", "lens": t + "security_camera_lens", "dark": t + "security_dark", "particle": t + "security_camera_body"},
        "elements": [
            el([4, 11, 4], [12, 12, 12], "dark"),
            el([7.5, 9.5, 7.5], [8.5, 11, 8.5], "dark"),
            el([5.5, 6, 5], [10.5, 9.5, 12], "body"),
            el([6, 6.4, 4], [10, 9.1, 5], "dark", per={"north": "lens"}),
            el([6.5, 9.5, 6], [9.5, 10, 11], "dark"),
        ],
        "display": {"gui": {"rotation": [30, 225, 0], "scale": [1.1, 1.1, 1.1]}}})
    write(f"{S}/security_camera.json", {"variants": {"": {"model": "rubymod:block/security_camera"}}})
    write(f"{I}/security_camera.json", {"model": {"type": "minecraft:model", "model": "rubymod:block/security_camera"}})

    # Seismic Sensor: a striped base plate, a dome, and an antenna.
    write(f"{M}/seismic_sensor.json", {
        "parent": "minecraft:block/block",
        "textures": {"base": t + "seismic_sensor_base", "dome": t + "seismic_sensor_dome", "dark": t + "security_dark", "particle": t + "seismic_sensor_base"},
        "elements": [
            el([0, 0, 0], [16, 3, 16], "base", per={"north": "dark", "south": "dark", "east": "dark", "west": "dark"}),
            el([4, 3, 4], [12, 6.5, 12], "dome"),
            el([5, 6.5, 5], [11, 7.5, 11], "dark"),
            el([7.5, 7.5, 7.5], [8.5, 13, 8.5], "dark"),
            el([6.5, 12.5, 6.5], [9.5, 13.5, 9.5], "dome"),
        ]})
    write(f"{S}/seismic_sensor.json", {"variants": {"": {"model": "rubymod:block/seismic_sensor"}}})
    write(f"{I}/seismic_sensor.json", {"model": {"type": "minecraft:model", "model": "rubymod:block/seismic_sensor"}})

    # Ruby Bearing: the bearing face where the dropper's front was.
    write(f"{M}/ruby_bearing.json", {"parent": "minecraft:block/orientable", "textures": {
        "top": t + "ruby_bearing_side", "front": t + "ruby_bearing_front", "side": t + "ruby_bearing_side"}})
    write(f"{M}/ruby_bearing_vertical.json", {"parent": "minecraft:block/orientable_vertical", "textures": {
        "front": t + "ruby_bearing_front", "side": t + "ruby_bearing_side"}})
    states = json.load(open(f"{S}/ruby_bearing.json"))
    for v in states["variants"].values():
        v["model"] = "rubymod:block/ruby_bearing_vertical" if "vertical" in v["model"] else "rubymod:block/ruby_bearing"
    write(f"{S}/ruby_bearing.json", states)
    write(f"{I}/ruby_bearing.json", {"model": {"type": "minecraft:model", "model": "rubymod:block/ruby_bearing"}})

    # Ruby Piston: a ruby pushing plate, steel sides with an arrow.
    write(f"{M}/ruby_piston.json", {"parent": "minecraft:block/template_piston", "textures": {
        "bottom": t + "ruby_piston_bottom", "platform": t + "ruby_piston_top", "side": t + "ruby_piston_side"}})
    write(f"{M}/ruby_piston_inventory.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
        "top": t + "ruby_piston_top", "bottom": t + "ruby_piston_bottom", "side": t + "ruby_piston_side"}})
    states = json.load(open(f"{S}/ruby_piston.json"))
    for v in states["variants"].values():
        v["model"] = "rubymod:block/ruby_piston"
    write(f"{S}/ruby_piston.json", states)
    write(f"{I}/ruby_piston.json", {"model": {"type": "minecraft:model", "model": "rubymod:block/ruby_piston_inventory"}})

    # Keycard Door: a steel security door with a window and a card reader, like the Blast Door is built.
    for part in ("bottom_left", "bottom_left_open", "bottom_right", "bottom_right_open", "top_left", "top_left_open", "top_right", "top_right_open"):
        write(f"{M}/keycard_door_{part}.json", {"parent": f"minecraft:block/door_{part}", "textures": {
            "bottom": t + "keycard_door_bottom", "top": t + "keycard_door_top"}})
    states = json.load(open(f"{S}/keycard_door.json"))
    for v in states["variants"].values():
        v["model"] = v["model"].replace("minecraft:block/iron_door_", "rubymod:block/keycard_door_")
    write(f"{S}/keycard_door.json", states)
    write(f"{ROOT}/models/item/keycard_door.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "rubymod:item/keycard_door"}})
    write(f"{I}/keycard_door.json", {"model": {"type": "minecraft:model", "model": "rubymod:item/keycard_door"}})
    print("ok")


if __name__ == "__main__":
    main()
