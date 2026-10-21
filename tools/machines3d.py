"""3D models for the industry machines (front = north, the low-Z side). Same swatch
atlas as the defenses (textures/block/vehicle_parts.png). Imported by gen_assets.py."""
from models3d import (BLACK, BRASS, DARK, LENS, LIGHT, OLIVE, OLIVE_DARK, RED_LIGHT, RUBY, RUBY_DARK, STEEL, TAN, WHITE, WOOD,
                      WOOD_DARK, tilt, vbox)


def casing(top=14, trim=True):
    """The common machine body: plinth, steel casing, ruby trim band, corner posts."""
    parts = [
        vbox([0, 0, 0], [16, 2, 16], DARK),                                    # plinth
        vbox([1, 2, 1], [15, top, 15], STEEL),                                 # casing
        vbox([0.5, 2, 0.5], [2, top, 2], DARK), vbox([14, 2, 0.5], [15.5, top, 2], DARK),     # corner posts
        vbox([0.5, 2, 14], [2, top, 15.5], DARK), vbox([14, 2, 14], [15.5, top, 15.5], DARK),
        vbox([0.5, top, 0.5], [15.5, top + 1, 15.5], DARK),                  # lid
    ]
    if trim:
        parts.append(vbox([0.9, top - 2.5, 0.9], [15.1, top - 1.5, 15.1], RUBY))  # ruby band
    return parts


def vents(z, y0, y1, x0=4, x1=12):
    return [vbox([x0, y, z], [x1, y + 0.6, z + 0.3], BLACK) for y in range(int(y0), int(y1), 2)]


MACHINES = {
    "combustion_generator": casing() + [
        vbox([3.5, 3, 0.6], [12.5, 9.5, 1.1], BLACK),                          # firebox door frame
        *[vbox([4.5, y, 0.4], [11.5, y + 0.8, 0.7], RED_LIGHT) for y in (4, 5.6, 7.2)],  # glowing grill
        vbox([3, 10, 0.6], [5, 11, 1], BRASS),                                 # door hinge
        vbox([10, 14, 10], [13.5, 16, 13.5], DARK),                            # exhaust stack
        vbox([10.5, 15.5, 10.5], [13, 16, 13], BLACK),
        *vents(15.2, 4, 10),
        vbox([12, 10, 0.4], [13.5, 11.5, 0.8], LENS),                          # gauge
    ],
    "solar_panel": [
        vbox([1, 0, 1], [15, 1.5, 15], DARK),                                  # base frame
        vbox([7, 1.5, 7], [9, 3.5, 9], STEEL),                                 # mount post
        vbox([0.5, 3.5, 0.5], [15.5, 4.5, 15.5], DARK, tilt([8, 4, 8], 22.5, "x")),   # panel frame, tilted to the sun
        vbox([1, 4.5, 1], [15, 4.9, 15], LENS, tilt([8, 4, 8], 22.5, "x")),           # cells
        *[vbox([x, 4.9, 1], [x + 0.3, 5, 15], STEEL, tilt([8, 4, 8], 22.5, "x")) for x in (4.5, 8, 11.5)],
        *[vbox([1, 4.9, z], [15, 5, z + 0.3], STEEL, tilt([8, 4, 8], 22.5, "x")) for z in (4.5, 8, 11.5)],
    ],
    "battery": [
        vbox([0, 0, 0], [16, 2, 16], DARK),
        vbox([1, 2, 1], [15, 3, 15], STEEL),
        *[vbox([x, 3, z], [x + 5, 13, z + 5], DARK) for x in (1.5, 9.5) for z in (1.5, 9.5)],          # four cells
        *[vbox([x - 0.1, 9, z - 0.1], [x + 5.1, 10, z + 5.1], RUBY) for x in (1.5, 9.5) for z in (1.5, 9.5)],
        *[vbox([x + 1.5, 13, z + 1.5], [x + 3.5, 14.5, z + 3.5], BRASS) for x in (1.5, 9.5) for z in (1.5, 9.5)],  # terminals
        vbox([3, 4, 1.3], [5, 12, 1.5], LENS), vbox([11, 4, 1.3], [13, 12, 1.5], LENS),                  # charge windows
    ],
    "ruby_drill": [
        vbox([0, 0, 0], [16, 2, 16], DARK),
        vbox([5, 0, 5], [11, 2.1, 11], BLACK),                                 # bore hole
        *[vbox([x, 2, z], [x + 1.5, 13, z + 1.5], STEEL) for x in (0.5, 14) for z in (0.5, 14)],    # derrick legs
        *[vbox([0.5, y, 0.5], [15.5, y + 0.8, 2], DARK) for y in (6, 10)],    # cross bracing
        *[vbox([0.5, y, 14], [15.5, y + 0.8, 15.5], DARK) for y in (6, 10)],
        vbox([2, 12, 2], [14, 16, 14], DARK),                                  # motor housing
        vbox([1.9, 13, 1.9], [14.1, 14, 14.1], RUBY),                          # ruby band
        vbox([5, 9, 5], [11, 12, 11], STEEL),                                  # gearbox
        vbox([6.5, 5, 6.5], [9.5, 9, 9.5], BRASS),                             # drill shaft
        vbox([7, 2, 7], [9, 5, 9], RUBY),                                      # ruby bit
        vbox([12, 14.5, 1.5], [13.5, 15.5, 1.9], RED_LIGHT),                   # status light
    ],
    "ore_refinery": casing() + [
        vbox([3, 14, 3], [13, 16, 13], BRASS),                                 # intake hopper
        vbox([5, 15, 5], [11, 16.01, 11], BLACK),
        vbox([4, 4, 0.6], [12, 10, 1.1], BLACK),                               # crusher window
        vbox([5, 5, 0.4], [7.5, 9, 0.8], STEEL), vbox([8.5, 5, 0.4], [11, 9, 0.8], STEEL),  # crusher rollers
        vbox([6, 2, -0.5], [10, 4, 1], DARK),                                  # output chute
        *vents(15.2, 4, 10),
    ],
    "ammo_factory": [
        vbox([0, 0, 0], [16, 2, 16], DARK),
        vbox([1, 2, 1], [15, 12, 15], OLIVE),                                  # olive body
        vbox([0.9, 9.5, 0.9], [15.1, 10.5, 15.1], OLIVE_DARK),
        vbox([2, 12, 4], [14, 13, 12], DARK),                                  # press bed
        vbox([6, 13, 6], [10, 16, 10], STEEL),                                 # press ram
        vbox([5, 15, 5], [11, 16, 11], DARK),
        vbox([1, 3, -0.5], [15, 4.5, 2], BLACK),                               # output conveyor
        *[vbox([x, 4.5, 0], [x + 1, 6.5, 1], BRASS) for x in (3, 5.5, 8, 10.5, 13)],     # cartridges
        vbox([2, 6.5, 0.6], [6, 9, 1], LENS),                                  # control screen
        vbox([11, 7, 0.6], [14, 8, 1], RED_LIGHT),
    ],
    "trade_depot": [
        vbox([0, 0, 0], [16, 1.5, 16], DARK),
        vbox([1, 1.5, 1], [15, 13, 15], WOOD),                                 # crate
        *[vbox([0.8, y, 0.8], [15.2, y + 1.2, 15.2], STEEL) for y in (3, 10)],          # steel bands
        vbox([0.8, 1.5, 0.8], [2.5, 13, 2.5], WOOD_DARK), vbox([13.5, 1.5, 0.8], [15.2, 13, 2.5], WOOD_DARK),
        vbox([0.8, 1.5, 13.5], [2.5, 13, 15.2], WOOD_DARK), vbox([13.5, 1.5, 13.5], [15.2, 13, 15.2], WOOD_DARK),
        vbox([2, 13, 2], [14, 14, 14], DARK),                                  # counter top
        vbox([6, 14, 7], [10, 14.5, 9], BLACK),                                # coin slot
        vbox([7, 14, 7.5], [9, 16, 8.5], RUBY),                                # ruby on the slot
        vbox([4, 5, 0.6], [12, 9, 0.9], LENS),                                 # price board
    ],
    "vault": [
        vbox([0, 0, 0], [16, 16, 16], DARK),                                   # armored box
        vbox([0.6, 1, 0.6], [15.4, 15, 15.4], STEEL),
        vbox([2, 2, -0.2], [14, 14, 0.6], DARK),                               # door frame
        vbox([3, 3, -0.6], [13, 13, 0.2], LIGHT),                              # round door (stepped)
        vbox([4, 2.5, -0.6], [12, 3, 0.2], LIGHT), vbox([4, 13, -0.6], [12, 13.5, 0.2], LIGHT),
        vbox([2.5, 4, -0.6], [3, 12, 0.2], LIGHT), vbox([13, 4, -0.6], [13.5, 12, 0.2], LIGHT),
        vbox([7, 7, -1.2], [9, 9, -0.6], BRASS),                               # handle hub
        vbox([5, 7.6, -1], [11, 8.4, -0.7], BRASS), vbox([7.6, 5, -1], [8.4, 11, -0.7], BRASS),  # spokes
        vbox([11.5, 11.5, -0.8], [12.5, 12.5, -0.5], RED_LIGHT),               # lock light
        *[vbox([x, 1, -0.4], [x + 1, 2, 0.6], BRASS) for x in (3, 12)],       # hinges
        vbox([0.4, 14.5, 0.4], [15.6, 15.5, 15.6], RUBY),
    ],
    "shield_generator": [
        vbox([0, 0, 0], [16, 3, 16], DARK),
        vbox([2, 3, 2], [14, 5, 14], STEEL),
        vbox([5, 5, 5], [11, 11, 11], DARK),                                   # pylon
        *[vbox([x, 5, z], [x + 2, 9, z + 2], STEEL) for x in (2, 12) for z in (2, 12)],   # emitter posts
        *[vbox([x, 9, z], [x + 2, 10, z + 2], RED_LIGHT) for x in (2, 12) for z in (2, 12)],
        vbox([6, 11, 6], [10, 15, 10], RUBY),                                  # ruby crystal
        vbox([7, 15, 7], [9, 16, 9], RED_LIGHT),
        vbox([4.5, 12, 4.5], [11.5, 12.6, 11.5], BRASS),                       # focusing ring
    ],
    "point_defense": [
        vbox([0, 0, 0], [16, 3, 16], DARK),
        vbox([3, 3, 3], [13, 5, 13], STEEL),                                   # turntable
        vbox([4, 5, 5], [12, 12, 14], WHITE),                                  # turret body
        vbox([5, 12, 7], [11, 16, 13], WHITE),                                 # radar dome
        vbox([6, 12.5, 6.8], [10, 15, 7.2], LENS),
        vbox([6, 6, -2], [10, 10, 5], DARK, tilt([8, 8, 5], 22.5, "x")),      # gatling barrel cluster
        *[vbox([x, y, -4], [x + 1, y + 1, -2], BLACK, tilt([8, 8, 5], 22.5, "x")) for x in (6.5, 8.5) for y in (6.5, 8.5)],
        vbox([12, 6, 8], [14, 10, 12], BRASS),                                 # ammo drum
    ],
    "constructor": casing(top=11) + [
        vbox([3, 12, 3], [13, 13, 13], DARK),                                  # gantry plate
        vbox([2, 12, 2], [3.5, 16, 3.5], STEEL), vbox([12.5, 12, 2], [14, 16, 3.5], STEEL),   # gantry
        vbox([2, 15, 2], [14, 16, 3.5], STEEL),
        vbox([7, 12.5, 2.5], [9, 15, 4.5], BRASS),                             # print head
        vbox([7.5, 11.5, 0.5], [8.5, 12.5, 3], RUBY),                          # ruby nozzle
        vbox([3, 4, 0.6], [13, 9, 1], LENS),                                   # blueprint display
        *[vbox([x, 5, 0.4], [x + 0.3, 8, 0.6], WHITE) for x in (5, 8, 11)],
    ],
    "tesla_coil": [
        vbox([3, 0, 3], [13, 2, 13], DARK),                                    # base
        vbox([4, 2, 4], [12, 4, 12], STEEL),
        vbox([6, 4, 6], [10, 11, 10], DARK),                                   # insulator column
        *[vbox([5.5, y, 5.5], [10.5, y + 0.8, 10.5], BRASS) for y in (4.6, 6.2, 7.8, 9.4)],   # copper windings
        vbox([4.5, 11, 4.5], [11.5, 13, 11.5], STEEL),                         # toroid
        vbox([5, 13, 5], [11, 14.5, 11], LIGHT),
        vbox([7, 14.5, 7], [9, 16, 9], RUBY),                                  # ruby tip
    ],
}

# Items / icons.
RAILGUN_ICON = [
    vbox([1, 0, 1], [15, 2, 15], DARK),                                         # base plate
    vbox([4, 2, 4], [12, 5, 12], STEEL),                                        # turntable
    vbox([3, 5, 5], [5, 10, 11], DARK), vbox([11, 5, 5], [13, 10, 11], DARK),   # supports
    vbox([6, 6, -8], [7.2, 7.2, 10], LIGHT, tilt([8, 7, 8], 22.5, "x")),         # rails, raised
    vbox([8.8, 6, -8], [10, 7.2, 10], LIGHT, tilt([8, 7, 8], 22.5, "x")),
    vbox([7, 6.2, -6], [9, 7, 6], RED_LIGHT, tilt([8, 7, 8], 22.5, "x")),        # glowing coil
    vbox([5.5, 5.5, 8], [10.5, 9, 12], DARK, tilt([8, 7, 8], 22.5, "x")),        # breech
    *[vbox([x, 2, z], [x + 2, 4, z + 2], RUBY) for x in (1.5, 12.5) for z in (1.5, 12.5)],   # capacitors
]

HEAVY_AA_ICON = [
    vbox([0, 0, 6], [16, 1.5, 10], OLIVE_DARK), vbox([6, 0, 0], [10, 1.5, 16], OLIVE_DARK),    # outriggers
    vbox([5, 1.5, 5], [11, 5, 11], DARK),                                       # pedestal
    vbox([3, 5, 4], [13, 6.5, 13], OLIVE),                                      # turntable
    vbox([3.5, 6.5, 6], [5, 11, 12], OLIVE), vbox([11, 6.5, 6], [12.5, 11, 12], OLIVE),     # cheeks
    vbox([2.5, 6.5, 4], [13.5, 11, 4.8], OLIVE_DARK),                           # shield
    vbox([6, 8, -6], [7.3, 9.3, 8], STEEL, tilt([8, 8.5, 8], 22.5, "x")),       # barrels, raised
    vbox([8.7, 8, -6], [10, 9.3, 8], STEEL, tilt([8, 8.5, 8], 22.5, "x")),
    vbox([5.8, 7.8, -7.5], [7.5, 9.5, -6], BLACK, tilt([8, 8.5, 8], 22.5, "x")),
    vbox([8.5, 7.8, -7.5], [10.2, 9.5, -6], BLACK, tilt([8, 8.5, 8], 22.5, "x")),
    vbox([6, 9.3, 6], [10, 12, 9], BRASS),                                      # clips
]
