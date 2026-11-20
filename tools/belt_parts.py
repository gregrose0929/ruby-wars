"""Curved belts, still (stopped) belts, the Belt Splitter, the Belt Tunnel and the down Belt Ramp: textures, models,
blockstates, item models and loot tables.

Usage:  python tools/belt_parts.py   (from the project root; needs Pillow; run after tools/textures.py and
tools/belt_textures.py, since it builds on the belt texture and the conveyor and ramp models)

The curve textures are drawn to match the straight belt (tools/textures.py, "conveyor_belt"): 8 frames, ridges every
4 pixels moving 2 pixels a frame along the middle of the belt, so a curve and the straight belt next to it run at the
same speed. (No yellow chevrons on the bend: at 16 pixels they break up; the ridges sweeping round read better.)
"""
import json
import math

from PIL import Image, ImageDraw

ROOT = "src/main/resources/assets/rubymod"
TEX = f"{ROOT}/textures/block"
DATA = "src/main/resources/data/rubymod"

EDGE = (60, 64, 70)
ARROW = (210, 170, 50)
RIDGE = (46, 46, 50)
RUBBER = (28, 28, 31)
OUTSIDE = (44, 47, 54)
RUBY = (212, 30, 64)
RUBY_HI = (255, 110, 140)
YELLOW = (240, 190, 40)
DIRECTIONS = (("north", 0), ("east", 90), ("south", 180), ("west", 270))


def write(path, data):
    with open(path, "w", newline="\n") as f:
        json.dump(data, f, indent="\t")
        f.write("\n")


def curve_frames():
    """A left curve for a belt facing north: in from the west edge, out of the north edge, round the north-west corner."""
    frames = []
    arc = math.pi / 2 * 8  # the length of the middle of the belt round the bend
    for k in range(8):
        im = Image.new("RGBA", (16, 16))
        for y in range(16):
            for x in range(16):
                px, py = x + 0.5, y + 0.5
                r = math.hypot(px, py)
                s = math.atan2(py, px) / (math.pi / 2) * arc  # 0 where it leaves, larger back toward where it comes in
                if r > 16:
                    im.putpixel((x, y), OUTSIDE + (255,))
                    continue
                if r < 1 or r > 15:
                    im.putpixel((x, y), EDGE + (255,))
                    continue
                # Bands as wide in pixels at every radius (s counts pixels along the middle of the belt, r = 8).
                width = 8 / r
                # Just the ridges sweeping round (chevrons don't survive a bend this tight at 16 pixels).
                if (s + 2 * k) % 4 < width:
                    c = RIDGE
                else:
                    c = RUBBER
                im.putpixel((x, y), c + (255,))
        frames.append(im)
    return frames


def strip(frames):
    im = Image.new("RGBA", (16, 16 * len(frames)))
    for i, f in enumerate(frames):
        im.paste(f, (0, 16 * i))
    return im


def animated(name, frames):
    strip(frames).save(f"{TEX}/{name}.png")
    write(f"{TEX}/{name}.png.mcmeta", {"animation": {"frametime": 2}})


def splitter_mark():
    """Arrows off to both sides, laid over the moving belt."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.rectangle([7, 9, 8, 15], fill=RUBY + (255,))
    d.rectangle([3, 7, 12, 8], fill=RUBY + (255,))
    d.polygon([(0, 7.5), (3, 4.5), (3, 10.5)], fill=RUBY + (255,))
    d.polygon([(15, 7.5), (12, 4.5), (12, 10.5)], fill=RUBY + (255,))
    d.line([(7, 15), (7, 10)], fill=RUBY_HI + (255,))
    d.rectangle([0, 0, 15, 0], fill=YELLOW + (255,))
    return im


def tunnel_mouth():
    """The dark opening, with hazard stripes round it."""
    im = Image.new("RGBA", (16, 16), (12, 12, 15, 255))
    d = ImageDraw.Draw(im)
    for i in range(-16, 32, 4):
        d.line([(i, 0), (i + 3, 3)], fill=YELLOW + (255,))
    for x in range(16):
        for y in range(16):
            inside = 2 <= x <= 13 and 3 <= y <= 15
            if not inside and y > 3:
                im.putpixel((x, y), (58, 62, 70, 255))
    d.rectangle([2, 3, 13, 15], fill=(8, 8, 10, 255))
    d.line([(2, 3), (13, 3)], fill=(30, 30, 36, 255))
    return im


def conveyor_model(belt, rails):
    m = {"parent": "minecraft:block/block",
         "textures": {"particle": "rubymod:block/conveyor_frame", "frame": "rubymod:block/conveyor_frame", "belt": belt},
         "elements": [{"from": [0, 0, 0], "to": [16, 4, 16], "faces": {
             "north": {"texture": "#frame"}, "south": {"texture": "#frame"}, "east": {"texture": "#frame"}, "west": {"texture": "#frame"},
             "down": {"texture": "#frame"}, "up": {"texture": "#belt"}}}]}
    for fr, to in rails:
        m["elements"].append({"from": fr, "to": to, "faces": {k: {"texture": "#frame"} for k in ("north", "south", "east", "west", "up")}})
    return m


STRAIGHT_RAILS = [([0, 4, 0], [1, 5, 16]), ([15, 4, 0], [16, 5, 16])]
# A left curve (facing north, in from the west): rails round the outside (east and south), a post on the inside corner.
LEFT_RAILS = [([15, 4, 0], [16, 5, 16]), ([0, 4, 15], [15, 5, 16]), ([0, 4, 0], [1, 5, 1])]
RIGHT_RAILS = [([0, 4, 0], [1, 5, 16]), ([1, 4, 15], [16, 5, 16]), ([15, 4, 0], [16, 5, 1])]


def main():
    straight = Image.open(f"{TEX}/conveyor_belt.png").convert("RGBA")
    straight.crop((0, 0, 16, 16)).save(f"{TEX}/conveyor_belt_still.png")
    left = curve_frames()
    right = [f.transpose(Image.FLIP_LEFT_RIGHT) for f in left]
    animated("conveyor_curve_left", left)
    animated("conveyor_curve_right", right)
    left[0].save(f"{TEX}/conveyor_curve_left_still.png")
    right[0].save(f"{TEX}/conveyor_curve_right_still.png")
    splitter_mark().save(f"{TEX}/belt_splitter_mark.png")
    tunnel_mouth().save(f"{TEX}/belt_tunnel_mouth.png")

    models = {
        "conveyor": ("rubymod:block/conveyor_belt", STRAIGHT_RAILS),
        "conveyor_still": ("rubymod:block/conveyor_belt_still", STRAIGHT_RAILS),
        "conveyor_curve_left": ("rubymod:block/conveyor_curve_left", LEFT_RAILS),
        "conveyor_curve_left_still": ("rubymod:block/conveyor_curve_left_still", LEFT_RAILS),
        "conveyor_curve_right": ("rubymod:block/conveyor_curve_right", RIGHT_RAILS),
        "conveyor_curve_right_still": ("rubymod:block/conveyor_curve_right_still", RIGHT_RAILS),
    }
    for name, (belt, rails) in models.items():
        write(f"{ROOT}/models/block/{name}.json", conveyor_model(belt, rails))
    variants = {}
    for d, y in DIRECTIONS:
        for turn in ("none", "left", "right"):
            for powered in ("false", "true"):
                name = "conveyor" if turn == "none" else f"conveyor_curve_{turn}"
                if powered == "true":
                    name += "_still"
                variants[f"facing={d},powered={powered},turn={turn}"] = {"model": f"rubymod:block/{name}", "y": y}
    write(f"{ROOT}/blockstates/conveyor.json", {"variants": variants})

    # The splitter: a belt with its double arrow on top.
    m = conveyor_model("rubymod:block/conveyor_belt", STRAIGHT_RAILS)
    m["textures"]["mark"] = "rubymod:block/belt_splitter_mark"
    m["elements"].append({"from": [0, 4.02, 0], "to": [16, 4.02, 16], "faces": {"up": {"texture": "#mark"}}})
    write(f"{ROOT}/models/block/belt_splitter.json", m)
    write(f"{ROOT}/blockstates/belt_splitter.json",
          {"variants": {f"facing={d}": {"model": "rubymod:block/belt_splitter", "y": y} for d, y in DIRECTIONS}})

    # The tunnel: a belt going in under a hood (the entrance's hood is at the front, the exit's at the back).
    for kind, hood_from, hood_to, mouth in (("entrance", [0, 4, 0], [16, 12, 9], "south"), ("exit", [0, 4, 7], [16, 12, 16], "north")):
        m = conveyor_model("rubymod:block/conveyor_belt", [])
        m["textures"]["mouth"] = "rubymod:block/belt_tunnel_mouth"
        faces = {k: {"texture": "#frame"} for k in ("north", "south", "east", "west", "up", "down")}
        faces[mouth] = {"texture": "#mouth"}
        m["elements"].append({"from": hood_from, "to": hood_to, "faces": faces})
        write(f"{ROOT}/models/block/belt_tunnel_{kind}.json", m)
    variants = {}
    for d, y in DIRECTIONS:
        for exit_ in ("false", "true"):
            variants[f"facing={d},exit={exit_}"] = {"model": f"rubymod:block/belt_tunnel_{'exit' if exit_ == 'true' else 'entrance'}", "y": y}
    write(f"{ROOT}/blockstates/belt_tunnel.json", {"variants": variants})

    # The down ramp: the up ramp turned round, with its belt running the other way (downhill).
    ramp = json.load(open(f"{ROOT}/models/block/belt_ramp.json"))
    down = json.loads(json.dumps(ramp))
    for e in down["elements"]:
        up = e["faces"].get("up")
        if up and up.get("texture") == "#belt":
            up["uv"] = [16, 16, 0, 0]
    write(f"{ROOT}/models/block/belt_ramp_down.json", down)
    variants = {}
    for d, y in DIRECTIONS:
        variants[f"down=false,facing={d}"] = {"model": "rubymod:block/belt_ramp", "y": y}
        variants[f"down=true,facing={d}"] = {"model": "rubymod:block/belt_ramp_down", "y": (y + 180) % 360}
    write(f"{ROOT}/blockstates/belt_ramp.json", {"variants": variants})

    for name, model in (("belt_splitter", "belt_splitter"), ("belt_tunnel", "belt_tunnel_entrance")):
        write(f"{ROOT}/items/{name}.json", {"model": {"type": "minecraft:model", "model": f"rubymod:block/{model}"}})
        write(f"{DATA}/loot_table/blocks/{name}.json", {
            "type": "minecraft:block", "pools": [{"rolls": 1, "condition": {"type": "minecraft:survives_explosion"},
                                                  "entries": [{"type": "minecraft:item", "name": f"rubymod:{name}"}]}],
            "random_sequence": f"rubymod:blocks/{name}"})
    print("ok")


if __name__ == "__main__":
    main()
