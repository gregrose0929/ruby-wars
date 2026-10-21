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


# ----------------------------------------------------------------- vehicles
# Vehicles face the low-Z side (front = -Z). VehicleRenderer scales these up.

def vbox(frm, to, swatch, rotation=None, front=None):
    element = box(frm, to, swatch, rotation)
    for face in element["faces"].values():
        face["texture"] = "#parts"
    if front is not None:
        element["faces"]["north"]["uv"] = uv(front)
    return element


TANK_HULL = [
    # Tracks with road wheels.
    vbox([0, 0, 0.5], [3.5, 4, 15.5], RUBBER),
    vbox([12.5, 0, 0.5], [16, 4, 15.5], RUBBER),
    *[vbox([-0.3, 0.8, z], [0.2, 3.2, z + 2.4], DARK) for z in (1.5, 4.5, 7.5, 10.5, 13)],
    *[vbox([15.8, 0.8, z], [16.3, 3.2, z + 2.4], DARK) for z in (1.5, 4.5, 7.5, 10.5, 13)],
    vbox([0, 4, 0], [3.6, 4.4, 16], OLIVE_DARK),                 # track guards
    vbox([12.4, 4, 0], [16, 4.4, 16], OLIVE_DARK),
    # Hull.
    vbox([3.5, 1, 1], [12.5, 4.5, 15], OLIVE_DARK),
    vbox([2, 4.4, 2], [14, 7, 14.5], OLIVE),
    vbox([2, 3.6, 0.6], [14, 5.6, 2.6], OLIVE, tilt([8, 4.6, 1.6], -22.5, "x")),  # sloped glacis
    vbox([5, 7, 11], [11, 7.3, 14], BLACK),                      # engine grille
    vbox([2.5, 5.2, 1.6], [4, 6.2, 2.1], WHITE),                 # headlights
    vbox([12, 5.2, 1.6], [13.5, 6.2, 2.1], WHITE),
    vbox([13, 7, 2.5], [14, 8, 6], BRASS),                       # stowed crates
    vbox([2, 7, 12.5], [5, 8.2, 14.2], OLIVE_DARK),
]

# The turret is drawn on top of the hull and spins on its own; its center (8, *, 8) is the pivot.
TANK_TURRET = [
    vbox([4, 0, 4], [12, 3, 12.5], OLIVE),
    vbox([4.5, 3, 4.5], [11.5, 3.4, 12], OLIVE_DARK),
    vbox([6, 0.4, 2.8], [10, 2.6, 4], OLIVE_DARK),               # gun mantlet
    vbox([6, 3.4, 7.5], [8.8, 3.9, 10.3], DARK),                 # hatch
    vbox([9.5, 3.4, 6], [10, 4.4, 9.5], BLACK),                  # roof machine gun
    vbox([11, 3.4, 10.5], [11.3, 10, 10.8], BLACK),              # antenna
    vbox([12, 0.5, 6], [13, 2.5, 10], OLIVE_DARK),               # side stowage
]

# The barrel pivots at (8, 1.6, 3.5) in turret space and points toward -Z.
TANK_BARREL = [
    vbox([7.35, 1, -11], [8.65, 2.2, 3.5], STEEL),
    vbox([7.1, 0.7, -12.2], [8.9, 2.5, -11], DARK),              # muzzle brake
    vbox([7.2, 0.85, -2], [8.8, 2.35, 0.5], DARK),               # fume extractor
]

JEEP = [
    # Wheels and hubs.
    *[vbox([x, 0, z], [x + 3, 4, z + 4], RUBBER) for x in (0, 13) for z in (1, 11)],
    *[vbox([x, 1.3, z + 1.3], [x + 0.2, 2.7, z + 2.7], LIGHT) for x in (-0.1, 15.9) for z in (1, 11)],
    vbox([2, 2.5, 0], [14, 5, 16], OLIVE_DARK),                  # chassis
    vbox([2.5, 5, 0], [13.5, 7, 5.5], OLIVE),                    # hood
    vbox([3.5, 3, -0.2], [12.5, 6.5, 0], BLACK),                 # grille
    vbox([3.5, 5.5, -0.4], [5, 6.5, 0], WHITE),                  # headlights
    vbox([11, 5.5, -0.4], [12.5, 6.5, 0], WHITE),
    vbox([0, 4, 0.5], [3.2, 4.6, 5.5], OLIVE),                   # fenders
    vbox([12.8, 4, 0.5], [16, 4.6, 5.5], OLIVE),
    vbox([0, 4, 10.5], [3.2, 4.6, 15.5], OLIVE),
    vbox([12.8, 4, 10.5], [16, 4.6, 15.5], OLIVE),
    vbox([2, 5, 5.5], [3, 8, 16], OLIVE),                        # tub sides and back
    vbox([13, 5, 5.5], [14, 8, 16], OLIVE),
    vbox([2, 5, 15], [14, 8, 16], OLIVE),
    vbox([4, 5, 8], [7, 7.2, 10], TAN),                          # seats
    vbox([9, 5, 8], [12, 7.2, 10], TAN),
    vbox([4, 5, 11.5], [12, 7.2, 13.5], TAN),
    vbox([3, 7, 5.5], [13, 10.5, 6], LENS),                      # windshield
    vbox([2.5, 7, 5.4], [3, 11, 6.1], BLACK),
    vbox([13, 7, 5.4], [13.5, 11, 6.1], BLACK),
    vbox([2.5, 10.5, 5.4], [13.5, 11, 6.1], BLACK),
    vbox([2, 8, 12.5], [3, 12.5, 13.5], DARK),                   # roll bar
    vbox([13, 8, 12.5], [14, 12.5, 13.5], DARK),
    vbox([2, 11.5, 12.5], [14, 12.5, 13.5], DARK),
    vbox([7.5, 12.5, 8], [8.5, 13.5, 13.5], BLACK),              # mounted machine gun
    vbox([7.6, 12.6, 6.5], [8.4, 13.4, 8], STEEL),
    vbox([6, 5, 16], [10, 9, 17], RUBBER),                       # spare tire
]

JET = [
    vbox([6.5, 3, -8], [9.5, 6, 20], LIGHT),                     # fuselage
    vbox([7, 2.6, -6], [9, 3, 18], STEEL),                       # belly
    vbox([7, 3.5, -10.5], [9, 5.5, -8], DARK),                   # nose cone
    vbox([7.5, 4, -12], [8.5, 5, -10.5], BLACK),                 # pitot
    vbox([7, 6, -5], [9, 7.6, 3], LENS),                         # canopy
    vbox([-8, 3.8, 4], [24, 4.6, 11], STEEL),                    # wings
    vbox([-8.5, 3.6, 7.5], [-7.5, 4.8, 11.5], RUBY),             # wing tips
    vbox([23.5, 3.6, 7.5], [24.5, 4.8, 11.5], RUBY),
    vbox([7.6, 6, 14], [8.4, 12.5, 20], STEEL),                  # tail fin
    vbox([7.5, 11.5, 17.5], [8.5, 12.5, 20], RUBY),
    vbox([2, 4.2, 16], [14, 4.8, 20], STEEL),                    # stabilizers
    vbox([7, 3.4, 20], [9, 5.6, 22], BLACK),                     # engine nozzle
    vbox([7.3, 3.7, 21.5], [8.7, 5.3, 22.2], RED_LIGHT),         # afterburner glow
    vbox([0, 2.6, 3], [1.2, 3.8, 10], WHITE),                    # wing missiles
    vbox([0, 2.6, 2], [1.2, 3.8, 3], RUBY),
    vbox([14.8, 2.6, 3], [16, 3.8, 10], WHITE),
    vbox([14.8, 2.6, 2], [16, 3.8, 3], RUBY),
    vbox([7.4, 1.4, 0], [8.6, 2.6, 3], BLACK),                   # landing gear
    vbox([3, 1.4, 9], [4, 3.8, 11], BLACK),
    vbox([12, 1.4, 9], [13, 3.8, 11], BLACK),
]

VEHICLES = {
    "tank_model": TANK_HULL,
    "tank_turret_model": TANK_TURRET,
    "tank_barrel_model": TANK_BARREL,
    "jeep_model": JEEP,
    "jet_model": JET,
}


def vehicle_model(name):
    return {"textures": {"parts": "rubymod:block/vehicle_parts", "particle": "rubymod:block/vehicle_parts"},
            "ambientocclusion": False, "elements": VEHICLES[name]}


# ----------------------------------------------------------------- projectiles (front = -Z, centered on 8,8)

PROJECTILES = {
    "rocket_model": [
        vbox([7.25, 7.25, 2], [8.75, 8.75, 13], OLIVE),
        vbox([7.5, 7.5, 0.5], [8.5, 8.5, 2], RUBY),
        vbox([5.5, 7.75, 11], [10.5, 8.25, 13.5], DARK),
        vbox([7.75, 5.5, 11], [8.25, 10.5, 13.5], DARK),
        vbox([7.4, 7.4, 13], [8.6, 8.6, 14], RED_LIGHT),
    ],
    "tank_shell_model": [
        vbox([7.2, 7.2, 4], [8.8, 8.8, 12], BRASS),
        vbox([7.4, 7.4, 2.5], [8.6, 8.6, 4], DARK),
        vbox([7.6, 7.6, 1.5], [8.4, 8.4, 2.5], BLACK),
        vbox([7.3, 7.3, 12], [8.7, 8.7, 13], RED_LIGHT),
    ],
    "mortar_shell_model": [
        vbox([7, 7, 3], [9, 9, 11], OLIVE),
        vbox([7.5, 7.5, 1.5], [8.5, 8.5, 3], DARK),
        vbox([6, 7.8, 10.5], [10, 8.2, 13], DARK),
        vbox([7.8, 6, 10.5], [8.2, 10, 13], DARK),
    ],
    "cruise_missile_model": [
        vbox([7, 7, -2], [9, 9, 16], WHITE),
        vbox([7.4, 7.4, -4], [8.6, 8.6, -2], RUBY),
        vbox([3, 7.8, 6], [13, 8.2, 10], LIGHT),
        vbox([5.5, 7.8, 14], [10.5, 8.2, 16], STEEL),
        vbox([7.8, 5.5, 14], [8.2, 10.5, 16], STEEL),
        vbox([7.3, 7.3, 16], [8.7, 8.7, 17], RED_LIGHT),
    ],
    "bomb_model": [
        vbox([6.5, 6.5, 3], [9.5, 9.5, 12], DARK),
        vbox([7, 7, 1.5], [9, 9, 3], STEEL),
        vbox([5, 7.8, 12], [11, 8.2, 15], OLIVE_DARK),
        vbox([7.8, 5, 12], [8.2, 11, 15], OLIVE_DARK),
        vbox([6.4, 6.4, 6], [9.6, 9.6, 7], BRASS),
    ],
}
