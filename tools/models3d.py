"""3D models for guns and vehicles, built from boxes ("voxel" style, like Blockbench).

Every box picks a color swatch from a small atlas texture (gun_parts.png for
guns, vehicle_parts.png for vehicles), so the shapes carry the look and the
textures stay simple. Imported by gen_assets.py.

Guns are laid out along the X axis with the muzzle at +X, up is +Y, and the
gun's thickness is along Z centered on 8. That matches the old flat sprites, so
the hand transforms in gen_assets.GUN_DISPLAY point them forward.
"""

# Swatch indexes in the 4x4 grid of 4x4-pixel swatches (see textures.py: parts_atlas).
BLACK, DARK, STEEL, LIGHT, WOOD_DARK, WOOD, OLIVE, OLIVE_DARK, RUBY, RUBY_DARK, BRASS, LENS, RUBBER, WHITE, RED_LIGHT, TAN = range(16)


def uv(swatch):
    u = (swatch % 4) * 4
    v = (swatch // 4) * 4
    return [u + 0.5, v + 0.5, u + 3.5, v + 3.5]


def box(frm, to, swatch, rotation=None, front=None):
    """A box. `front` optionally gives the +X face (the muzzle side) its own swatch, e.g. a lens."""
    faces = {}
    for face in ("north", "south", "east", "west", "up", "down"):
        s = front if (front is not None and face == "east") else swatch
        faces[face] = {"uv": uv(s), "texture": "#parts"}
    element = {"from": frm, "to": to, "faces": faces}
    if rotation:
        element["rotation"] = rotation
    return element


def tilt(origin, angle, axis="z"):
    return {"origin": origin, "axis": axis, "angle": angle}


GUNS = {
    "service_pistol": [
        box([4, 7, 7.2], [12, 9, 8.8], STEEL),               # slide
        box([4.5, 8.6, 7.6], [11.5, 9.1, 8.4], LIGHT),      # slide top highlight
        box([4, 6, 7.3], [11, 7, 8.7], BLACK),               # frame
        box([12, 7.6, 7.6], [12.4, 8.4, 8.4], BLACK),        # muzzle
        box([6.5, 5, 7.6], [8, 6, 8.4], BLACK),              # trigger guard
        box([4, 1.5, 7.25], [6.6, 6, 8.75], RUBBER, tilt([5, 6, 8], -22.5)),  # grip
        box([11, 9, 7.8], [11.5, 9.5, 8.2], BLACK),          # front sight
    ],
    "arr_rifle": [
        box([0, 4, 7.25], [4, 8.5, 8.75], OLIVE),            # stock
        box([0, 3.5, 7.4], [1, 8.5, 8.6], OLIVE_DARK),       # butt pad
        box([4, 5, 7], [10, 8.5, 9], DARK),                  # receiver
        box([5, 8.5, 7.5], [9.5, 9.25, 8.5], BLACK),         # sight rail
        box([6, 9.25, 7.25], [7.5, 10.5, 8.75], BLACK, front=LENS),  # red dot
        box([10, 5.5, 6.9], [14, 8, 9.1], OLIVE),            # handguard
        box([14, 6.4, 7.6], [16.5, 7.2, 8.4], BLACK),        # barrel
        box([16.5, 6.2, 7.4], [17.2, 7.4, 8.6], DARK),       # muzzle
        box([13, 8, 7.75], [13.5, 9, 8.25], BLACK),          # front sight
        box([7.5, 1.5, 7.3], [9.5, 5, 8.7], DARK, tilt([8.5, 5, 8], 22.5)),  # magazine
        box([4.5, 1.5, 7.3], [6, 5, 8.7], RUBBER, tilt([5, 5, 8], -22.5)),   # grip
    ],
    "combat_shotgun": [
        box([0, 3.5, 7.25], [5, 8, 8.75], WOOD),             # stock
        box([0, 3, 7.4], [0.8, 8, 8.6], WOOD_DARK),          # butt
        box([5, 5, 7], [10, 8.5, 9], DARK),                  # receiver
        box([10, 7.2, 7.4], [17, 8.4, 8.6], STEEL),          # barrel
        box([10, 5.8, 7.5], [15.5, 7, 8.5], DARK),           # magazine tube
        box([11, 5.4, 7], [14, 7.4, 9], WOOD),               # pump
        box([11.2, 5.6, 6.9], [13.8, 7.2, 7], WOOD_DARK),    # pump grooves
        box([16.4, 8.4, 7.8], [16.8, 8.9, 8.2], BRASS),      # bead sight
        box([5.2, 1.5, 7.3], [6.8, 5, 8.7], RUBBER, tilt([6, 5, 8], -22.5)),  # grip
    ],
    "longshot_sniper": [
        box([-2, 3.5, 7.25], [4, 8.5, 8.75], OLIVE),         # stock
        box([0, 8.5, 7.4], [3, 9.3, 8.6], OLIVE_DARK),       # cheek rest
        box([4, 5.5, 7], [10, 8, 9], DARK),                  # receiver
        box([10, 6.5, 7.6], [18, 7.3, 8.4], BLACK),          # barrel
        box([18, 6.2, 7.3], [19, 7.6, 8.7], DARK),           # muzzle brake
        box([4.5, 8.75, 7.4], [10.5, 10, 8.6], BLACK),       # scope tube
        box([3.5, 8.5, 7.2], [4.5, 10.3, 8.8], BLACK),       # scope eyepiece
        box([10.5, 8.5, 7.2], [11.5, 10.3, 8.8], BLACK, front=LENS),  # scope objective
        box([6.5, 8, 7.7], [7.5, 8.75, 8.3], DARK),          # scope mount
        box([8, 7, 9], [8.6, 7.6, 10.5], STEEL),             # bolt handle
        box([6, 3.5, 7.4], [8, 5.5, 8.6], DARK),             # magazine
        box([3.5, 1.5, 7.3], [5, 5.5, 8.7], RUBBER, tilt([4.5, 5.5, 8], -22.5)),  # grip
    ],
    "ruby_blaster": [
        box([3, 5, 6.8], [11, 9, 9.2], WHITE),               # body
        box([3.5, 5.2, 6.7], [10.5, 5.8, 9.3], LIGHT),       # trim
        box([5, 9, 7], [9, 10, 9], RUBY),                    # ruby core
        box([5.5, 10, 7.5], [8.5, 10.5, 8.5], RED_LIGHT),    # core glow
        box([11, 6, 7.25], [14, 8, 8.75], STEEL),            # emitter
        box([12, 5.5, 6.75], [12.75, 8.5, 9.25], RUBY),      # ring
        box([13.5, 5.5, 6.75], [14.25, 8.5, 9.25], RUBY),    # ring
        box([14.25, 6.5, 7.5], [14.75, 7.5, 8.5], RED_LIGHT),  # tip
        box([4, 1.5, 7.3], [6, 5, 8.7], RUBBER, tilt([5, 5, 8], -22.5)),  # grip
    ],
    "rocket_launcher": [
        box([-4, 6, 6.5], [18, 9, 9.5], OLIVE),              # tube
        box([-3, 9, 7], [17, 9.3, 9], OLIVE_DARK),           # top strip
        box([18, 5.75, 6.25], [19, 9.25, 9.75], DARK),       # front ring
        box([-4.5, 5.75, 6.25], [-3.5, 9.25, 9.75], DARK),   # rear ring
        box([19, 6.5, 7], [20, 8.5, 9], RUBY),               # warhead
        box([6, 9, 5.5], [8, 10.5, 6.5], BLACK, front=LENS), # sight
        box([6, 2, 7.25], [7.5, 6, 8.75], RUBBER),           # rear grip
        box([10, 2.5, 7.25], [11.5, 6, 8.75], RUBBER),       # front grip
    ],
}


def gun_model(name):
    return {"textures": {"parts": "rubymod:item/gun_parts", "particle": "rubymod:item/gun_parts"}, "elements": GUNS[name]}
