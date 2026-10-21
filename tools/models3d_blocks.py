"""3D models for defense and launcher blocks, plus helpers for stitching models
together and fitting them into inventory slots. Imported by gen_assets.py.
Uses the same swatch atlas as the vehicles (textures/block/vehicle_parts.png)."""
from models3d import (BLACK, BRASS, DARK, LENS, LIGHT, OLIVE, OLIVE_DARK, RED_LIGHT, RUBY, STEEL, TANK_BARREL, TANK_HULL,
                      TANK_TURRET, WHITE, tilt, vbox)

BLOCKS = {
    "sentry_turret": [
        vbox([3, 0, 3], [13, 2, 13], DARK),                          # base plate
        vbox([6.5, 2, 6.5], [9.5, 7, 9.5], STEEL),                   # pillar
        vbox([4.5, 7, 5], [11.5, 11, 12], OLIVE),                    # gun housing
        vbox([5, 11, 6], [11, 11.5, 11], OLIVE_DARK),
        vbox([6, 8.5, -1], [7, 9.5, 5], BLACK),                      # twin barrels
        vbox([9, 8.5, -1], [10, 9.5, 5], BLACK),
        vbox([5.6, 8.1, -1.6], [7.4, 9.9, -0.6], DARK),              # muzzles
        vbox([8.6, 8.1, -1.6], [10.4, 9.9, -0.6], DARK),
        vbox([11.5, 7, 7], [13.5, 10, 11], BRASS),                   # ammo box
        vbox([7.5, 9.5, 4.6], [8.5, 10.5, 5], RED_LIGHT),            # sensor eye
    ],
    "missile_interceptor": [
        vbox([0, 0, 0], [16, 4, 16], DARK),                          # base
        vbox([1, 4, 1], [15, 4.4, 15], BRASS),                       # hazard trim
        vbox([3, 4, 3], [13, 6, 13], STEEL),                         # turntable
        vbox([2, 6, 2], [14, 13, 14], OLIVE, tilt([8, 6, 8], 22.5, "x")),  # launcher pod, tilted up
        *[vbox([x, y, 1.6], [x + 3, y + 3, 2], BLACK, tilt([8, 6, 8], 22.5, "x")) for x in (3.5, 9.5) for y in (6.7, 9.7)],
        *[vbox([x + 1, y + 1, 1.4], [x + 2, y + 2, 1.7], RUBY, tilt([8, 6, 8], 22.5, "x")) for x in (3.5, 9.5) for y in (6.7, 9.7)],
    ],
    "radar_station": [
        vbox([2, 0, 2], [14, 6, 14], DARK),                          # equipment cabinet
        vbox([2.5, 2, 1.9], [13.5, 5, 2], LENS),                     # screen
        vbox([7, 6, 7], [9, 10, 9], STEEL),                          # mast
        vbox([1, 9, 7], [15, 16, 8], LIGHT, tilt([8, 12, 7.5], -22.5, "x")),  # dish
        vbox([2, 10, 8], [14, 15, 8.4], STEEL, tilt([8, 12, 7.5], -22.5, "x")),
        vbox([7.5, 11.5, 2], [8.5, 12.5, 7], BLACK, tilt([8, 12, 7.5], -22.5, "x")),  # feed horn
        vbox([12.5, 6, 3], [13.5, 7, 4], RED_LIGHT),                 # status light
    ],
    "alarm_block": [
        vbox([1, 0, 1], [15, 9, 15], DARK),                          # housing
        vbox([2, 3, 0.9], [14, 7, 1], BRASS),                        # warning plate
        vbox([4, 9, 4], [12, 13.5, 12], RED_LIGHT),                  # siren dome
        vbox([5, 13.5, 5], [11, 14.5, 11], RUBY),
        vbox([6.5, 14.5, 6.5], [9.5, 15.5, 9.5], BLACK),             # cap
    ],
    "mortar": [
        vbox([1, 0, 1], [15, 1.5, 15], DARK),                        # base plate
        vbox([6.5, 1, 6.5], [9.5, 15, 9.5], OLIVE, tilt([8, 1, 8], -22.5, "x")),   # tube, tilted forward
        vbox([6, 13.5, 6], [10, 15, 10], BLACK, tilt([8, 1, 8], -22.5, "x")),      # muzzle ring
        vbox([4, 1, 3], [5, 9, 4], STEEL, tilt([4.5, 1, 3.5], 22.5, "z")),         # bipod legs
        vbox([11, 1, 3], [12, 9, 4], STEEL, tilt([11.5, 1, 3.5], -22.5, "z")),
        vbox([4, 8, 2.5], [12, 9, 4.5], DARK),                       # traverse bar
    ],
    "missile_silo": [
        vbox([0, 0, 0], [16, 13, 16], STEEL),                        # silo body
        vbox([-0.01, 11, -0.01], [16.01, 12, 16.01], BRASS),         # hazard bands
        vbox([-0.01, 2, -0.01], [16.01, 3, 16.01], BRASS),
        vbox([1, 13, 1], [7.8, 14, 15], DARK),                       # hatch doors
        vbox([8.2, 13, 1], [15, 14, 15], DARK),
        vbox([7, 13, 7], [9, 15.5, 9], RUBY),                        # missile tip
        vbox([7.5, 15.5, 7.5], [8.5, 16, 8.5], RED_LIGHT),
    ],
    "landmine": [
        vbox([2, 0, 2], [14, 1, 14], DARK),
        vbox([4, 1, 4], [12, 1.8, 12], OLIVE_DARK),
        vbox([7, 1.8, 7], [9, 2.4, 9], RED_LIGHT),                   # pressure trigger
    ],
    "at_mine": [
        vbox([1, 0, 1], [15, 2, 15], OLIVE_DARK),
        vbox([2, 2, 2], [14, 2.6, 14], OLIVE),
        vbox([6, 2.6, 6], [10, 3.2, 10], DARK),                      # pressure plate
        vbox([7.5, 3.2, 7.5], [8.5, 3.6, 8.5], BRASS),
    ],
    "flak_cannon": [
        vbox([1, 0, 1], [15, 3, 15], DARK),                          # base
        vbox([3, 3, 3], [13, 5, 13], STEEL),                         # turntable
        vbox([4, 5, 5], [12, 10, 12], OLIVE),                        # mount
        vbox([4.5, 10, 6], [11.5, 10.5, 11], OLIVE_DARK),
        vbox([5, 8, -3], [6.5, 9.5, 9], BLACK, tilt([8, 8.75, 8], 45, "x")),   # twin barrels, raised
        vbox([9.5, 8, -3], [11, 9.5, 9], BLACK, tilt([8, 8.75, 8], 45, "x")),
        vbox([4.6, 7.6, -4], [6.9, 9.9, -3], DARK, tilt([8, 8.75, 8], 45, "x")),  # muzzle brakes
        vbox([9.1, 7.6, -4], [11.4, 9.9, -3], DARK, tilt([8, 8.75, 8], 45, "x")),
        vbox([12, 5, 6], [14, 9, 11], BRASS),                        # shell box
    ],
    "sam_site": [
        vbox([0, 0, 0], [16, 3, 16], DARK),                          # base
        vbox([1, 3, 1], [15, 3.4, 15], BRASS),                       # hazard trim
        vbox([3, 3, 3], [13, 5, 13], STEEL),                         # turntable
        vbox([2, 5, 2], [14, 13, 14], LIGHT, tilt([8, 9, 8], 22.5, "x")),   # launcher box, raised
        *[vbox([x, y, 1.6], [x + 4, y + 3.5, 2], BLACK, tilt([8, 9, 8], 22.5, "x")) for x in (3, 9) for y in (5.5, 9.5)],
        *[vbox([x + 1.5, y + 1.25, 1.4], [x + 2.5, y + 2.25, 1.7], RED_LIGHT, tilt([8, 9, 8], 22.5, "x")) for x in (3, 9) for y in (5.5, 9.5)],
    ],
    "airfield_pad": [
        vbox([0, 0, 0], [16, 1.5, 16], DARK),
        vbox([0.5, 1.5, 0.5], [15.5, 1.7, 1.5], BRASS),              # hazard edges
        vbox([0.5, 1.5, 14.5], [15.5, 1.7, 15.5], BRASS),
        vbox([0.5, 1.5, 1.5], [1.5, 1.7, 14.5], BRASS),
        vbox([14.5, 1.5, 1.5], [15.5, 1.7, 14.5], BRASS),
        vbox([4, 1.5, 7], [12, 1.7, 9], WHITE),                      # "H" marking
        vbox([4, 1.5, 4], [5.5, 1.7, 12], WHITE),
        vbox([10.5, 1.5, 4], [12, 1.7, 12], WHITE),
    ],
    "floodlight": [
        vbox([3, 0, 3], [13, 1.5, 13], DARK),                        # base
        vbox([7, 1.5, 7], [9, 9, 9], STEEL),                         # pole
        vbox([4, 9, 4], [12, 13, 12], DARK),                         # lamp housing
        vbox([4.5, 13, 4.5], [11.5, 13.6, 11.5], WHITE),             # lens (glows)
        vbox([3.6, 12, 3.6], [12.4, 12.6, 12.4], BRASS),
    ],
    "claymore": [
        vbox([4, 1, 7], [12, 6, 8.5], OLIVE),                        # curved face
        vbox([3.2, 1, 7.8], [4.2, 6, 9], OLIVE),
        vbox([11.8, 1, 7.8], [12.8, 6, 9], OLIVE),
        vbox([5, 0, 8], [5.6, 1, 9.5], BLACK),                       # legs
        vbox([10.4, 0, 8], [11, 1, 9.5], BLACK),
        vbox([7.5, 6, 7.5], [8.5, 7, 8.5], BRASS),                   # detonator
    ],
}


def block_model(name):
    return {"parent": "minecraft:block/block",
            "textures": {"parts": "rubymod:block/vehicle_parts", "particle": "rubymod:block/vehicle_parts"},
            "elements": BLOCKS[name]}


def shifted(elements, dx=0.0, dy=0.0, dz=0.0):
    """Copy of elements moved by (dx, dy, dz), for stitching models together."""
    out = []
    for e in elements:
        e2 = {**e, "from": [e["from"][0] + dx, e["from"][1] + dy, e["from"][2] + dz],
              "to": [e["to"][0] + dx, e["to"][1] + dy, e["to"][2] + dz]}
        if "rotation" in e:
            o = e["rotation"]["origin"]
            e2["rotation"] = {**e["rotation"], "origin": [o[0] + dx, o[1] + dy, o[2] + dz]}
        out.append(e2)
    return out


# The whole tank in one model, for its inventory icon.
TANK_ICON = TANK_HULL + shifted(TANK_TURRET, dy=7) + shifted(TANK_BARREL, dy=7)


def extent(elements, axis):
    lo = min(min(e["from"][axis], e["to"][axis]) for e in elements)
    hi = max(max(e["from"][axis], e["to"][axis]) for e in elements)
    return lo, hi
