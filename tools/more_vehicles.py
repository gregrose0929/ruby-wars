"""Designs for the Tunnel Borer, Dirt Bike, Quad and the three boats, in vehicle_models.py's style (imported by it).

Same conventions: x to the side, y up, z forward (+z is the front), in pixels. Animated parts:
  drill (spins about z), wheel_* (spin about x), bars (the handlebars steer about y),
  turret (yaw) > gun (pitch), ramp (swings down about x).
"""
from aircraft_models import B, Part, mirror_x


def _local(part, parent_pivot=(0, 0, 0)):
    """These designs give every box and pivot in vehicle space; the model wants boxes relative to their part's pivot
    and pivots relative to the parent's (as the tank's design is written)."""
    piv = part.pivot
    for b in part.boxes:
        b.frm = (b.frm[0] - piv[0], b.frm[1] - piv[1], b.frm[2] - piv[2])
    for c in part.children:
        _local(c, piv)
    part.pivot = (piv[0] - parent_pivot[0], piv[1] - parent_pivot[1], piv[2] - parent_pivot[2])
    return part


def _round_wheel(name, x0, x1, cy, cz, r, mat="tire", hub="chrome"):
    """A wheel that reads as round: a cross of two boxes, and the same again turned 45 degrees, with a hub."""
    s = r * 0.42
    boxes = [B(x0, cy - r, cz - s, x1, cy + r, cz + s, mat), B(x0, cy - s, cz - r, x1, cy + s, cz + r, mat)]
    rim = [B(x0 - 0.2, cy - r * 0.42, cz - r * 0.42, x1 + 0.2, cy + r * 0.42, cz + r * 0.42, hub)]
    turned = Part(name + "_x", pivot=(0, cy, cz), rot=(45, 0, 0),
                  boxes=[B(x0 + 0.05, cy - r, cz - s, x1 - 0.05, cy + r, cz + s, mat), B(x0 + 0.05, cy - s, cz - r, x1 - 0.05, cy + s, cz + r, mat)])
    return Part(name, pivot=(0, cy, cz), boxes=boxes + rim, children=[turned])


def tunnel_borer():
    body = [
        B(-9, 5, -16, 9, 15, 12, "paint", panels=True),                     # hull
        B(-8, 15, -16, 8, 16.5, -7, "grille"),                              # engine deck
        B(-10, 5, 12, 10, 8, 13.5, "hazard"),                               # front bumper, striped
        B(-10, 5, -17.5, 10, 8, -16, "hazard"),                             # rear bumper
        B(-8.5, 11.5, 12, -5, 14, 12.6, "headlight", light=True),
        B(5, 11.5, 12, 8.5, 14, 12.6, "headlight", light=True),
        B(-8, 9, -17.6, -5, 11, -17, "taillight", light=True),
        B(5, 9, -17.6, 8, 11, -17, "taillight", light=True),
        B(-7, 6, 12, 7, 16, 16, "dark"),                                    # drill housing
        B(-8, 15, 9, 8, 16, 12, "hazard"),
        # The cab, at the back: glass all round, a roof and a beacon.
        B(-6, 16.5, -6, 6, 18, 4, "paint"),
        B(-6, 18, 3.4, 6, 23.5, 4, "dark"), B(-5.2, 18.4, 3.6, 5.2, 23, 4.4, "glass"),
        B(-6.2, 18, -6, -5.6, 23.5, 4, "dark"), B(-6.6, 18.6, -5, -6.0, 22.8, 2.6, "glass"),
        B(5.6, 18, -6, 6.2, 23.5, 4, "dark"), B(6.0, 18.6, -5, 6.6, 22.8, 2.6, "glass"),
        B(-6, 18, -6.6, 6, 23.5, -6, "dark"),
        B(-6.6, 23.5, -6.8, 6.6, 24.6, 4.6, "paint"),
        B(-1, 24.6, -2, 1, 26.2, 0, "beacon", light=True),
        B(-3, 18, -2, 3, 19, 2, "seat"),
        # Exhaust stack and a ladder up the side.
        B(5.2, 16.5, -14.5, 6.8, 27, -13, "dark"), B(4.8, 26.5, -15, 7.2, 27.2, -12.5, "dark"),
        B(-9.6, 8, -4, -9, 15, -3.4, "steel"), B(-9.6, 8, -1, -9, 15, -0.4, "steel"),
        # Tool boxes either side.
        B(-10.2, 10, -14, -9, 14, -6, "stowage"), B(9, 10, -14, 10.2, 14, -6, "stowage"),
    ]
    # Tracks: top and bottom runs, side plates, and road wheels that turn.
    tracks, wheels = [], []
    for side, (x0, x1) in (("l", (-15.5, -9)), ("r", (9, 15.5))):
        tracks += [B(x0, 7, -17, x1, 8.4, 15, "track"), B(x0, 0, -15.5, x1, 1.4, 13.5, "track"),
                   B(x0 - 0.2 if side == "l" else x0, 5.6, -16, x1 if side == "l" else x1 + 0.2, 7, 14, "paint")]
        for i, z in enumerate((-12, -4.5, 3, 10.5)):
            wheels.append(_round_wheel(f"wheel_{side}{i}", x0 + 0.4, x1 - 0.4, 3.8, z, 3.6, mat="roadwheel", hub="hub"))
        wheels.append(_round_wheel(f"wheel_{side}s", x0 + 0.2, x1 - 0.2, 4.4, -16, 4.2, mat="sprocket", hub="hub"))
        wheels.append(_round_wheel(f"wheel_{side}i", x0 + 0.2, x1 - 0.2, 4.4, 14.5, 4.0, mat="sprocket", hub="hub"))
    # The drill: rings getting smaller to a point, cutting teeth round each ring, all of it spinning.
    cy = 11
    rings = []
    for i, (r, z0) in enumerate(((9, 16), (7.2, 19), (5.4, 22), (3.6, 25), (1.8, 28))):
        rings.append(B(-r, cy - r, z0, r, cy + r, z0 + 3, "steel"))
        rings.append(B(-r - 0.8, cy - 0.8, z0 + 0.5, r + 0.8, cy + 0.8, z0 + 2.5, "dark"))   # teeth across
        rings.append(B(-0.8, cy - r - 0.8, z0 + 0.5, 0.8, cy + r + 0.8, z0 + 2.5, "dark"))
    rings.append(B(-0.8, cy - 0.8, 31, 0.8, cy + 0.8, 33.5, "dark"))           # the tip
    turned = Part("drill_x", pivot=(0, cy, 16), rot=(0, 0, 45),
                  boxes=[B(-r * 0.98, cy - r * 0.98, z0 + 0.3, r * 0.98, cy + r * 0.98, z0 + 2.7, "steel")
                         for r, z0 in ((9, 16), (7.2, 19), (5.4, 22), (3.6, 25))])
    drill = Part("drill", pivot=(0, cy, 16), boxes=rings, children=[turned])
    return _local(Part("root", children=[Part("body", boxes=body + tracks), drill] + wheels))


def dirt_bike():
    body = [
        B(-3, 12, 0, 3, 16, 8, "paint", panels=True),                       # fuel tank
        B(-3.4, 13, 1, -3, 15.5, 6, "plate"), B(3, 13, 1, 3.4, 15.5, 6, "plate"),   # tank badges
        B(-2.5, 12, -12, 2.5, 14.2, 1, "seat"),
        B(-3, 4.5, -4, 3, 10.5, 4, "dark", grille=True),                    # engine
        B(-1, 9, -9, 1, 12, 7, "dark"),                                     # frame spine
        B(3, 6.5, -13, 4.6, 8.5, -2, "chrome"), B(2.8, 8, -17, 5, 10.6, -10, "dark"),   # exhaust and muffler
        B(-2, 12, -18, 2, 13, -9, "paint"),                                 # rear fender
        B(-3.6, 9.5, -10, -3, 13, -3, "number"), B(3, 9.5, -10, 3.6, 13, -3, "number"),
        B(-1, 12.2, -18.6, 1, 13.2, -18, "taillight", light=True),
        B(-2.6, 5, -9.5, -1.8, 6.6, -1, "dark"), B(1.8, 5, -9.5, 2.6, 6.6, -1, "dark"),   # swing arm
        B(-0.6, 4, 0, 0.6, 7, 1.2, "dark"),                                 # kickstand mount
    ]
    bars = Part("bars", pivot=(0, 14, 9), boxes=[
        B(-2.2, 5.6, 9.5, -1.4, 15, 10.5, "chrome"), B(1.4, 5.6, 9.5, 2.2, 15, 10.5, "chrome"),   # fork
        B(-2.4, 14.5, 8.5, 2.4, 16, 10.5, "dark"),                          # top clamp
        B(-7, 16.4, 8.4, 7, 17.4, 9.4, "dark"),                             # handlebar
        B(-8.4, 16.1, 8.1, -6.4, 17.7, 9.7, "tire"), B(6.4, 16.1, 8.1, 8.4, 17.7, 9.7, "tire"),   # grips
        B(-2, 11.6, 5.5, 2, 12.6, 15, "paint"),                             # front fender
        B(-3, 13, 10.6, 3, 17.5, 11.2, "number"),                           # number plate
        B(-1.4, 13.8, 11.2, 1.4, 15.6, 11.8, "headlight", light=True),
    ])
    wheels = [_round_wheel("wheel_f", -1.5, 1.5, 5.6, 10.5, 5.6), _round_wheel("wheel_r", -1.7, 1.7, 5.6, -9.5, 5.6)]
    return _local(Part("root", children=[Part("body", boxes=body), bars] + wheels))


def quad():
    body = [
        B(-6, 4, -11, 6, 9, 11, "dark"),                                    # frame
        B(-4, 10, 2, 4, 13, 7, "paint", panels=True),                       # tank
        B(-3.5, 10, -9, 3.5, 12.6, 2, "seat"),
        B(-7, 9, 6, 7, 10.4, 14, "paint", panels=True),                     # front body
        B(-7, 9, -14, 7, 10.4, -6, "paint", panels=True),                   # rear body
        B(-6, 10.4, 9, 6, 11, 14, "dark"), B(-6, 10.4, -14, 6, 11, -9, "dark"),   # racks
        B(-6, 5, 13.6, 6, 7, 15.6, "dark"), B(-6, 5, -15.6, 6, 7, -13.6, "dark"),  # bumpers
        B(-5, 9.6, 14, -2, 11.4, 14.6, "headlight", light=True), B(2, 9.6, 14, 5, 11.4, 14.6, "headlight", light=True),
        B(-5, 9.6, -14.6, -3, 10.8, -14, "taillight", light=True), B(3, 9.6, -14.6, 5, 10.8, -14, "taillight", light=True),
        B(-3, 4.5, -4, 3, 9.5, 3, "dark", grille=True),                     # engine
        B(3.2, 6.5, -13, 4.6, 8.2, -4, "chrome"),                           # exhaust
    ]
    fenders = []
    for x0, x1 in ((6.6, 12.6), (-12.6, -6.6)):
        for z0, z1 in ((4, 14), (-14, -4)):
            fenders.append(B(x0, 9.4, z0, x1, 10.6, z1, "paint"))
    bars = Part("bars", pivot=(0, 13, 7), boxes=[
        B(-0.7, 10, 6.3, 0.7, 16, 7.7, "dark"),
        B(-7, 16, 6.5, 7, 17, 7.5, "dark"),
        B(-8.4, 15.7, 6.2, -6.4, 17.3, 7.8, "tire"), B(6.4, 15.7, 6.2, 8.4, 17.3, 7.8, "tire"),
    ])
    wheels = []
    for name, x0, x1, z in (("wheel_fl", 7, 12, 9), ("wheel_fr", -12, -7, 9), ("wheel_rl", 7, 12, -9), ("wheel_rr", -12, -7, -9)):
        wheels.append(_round_wheel(name, x0, x1, 4.8, z, 4.8))
    return _local(Part("root", children=[Part("body", boxes=body + fenders), bars] + wheels))


def _hull(half_w, length_back, length_front, low, high, bow):
    """A boat hull: red below the waterline, painted above, tapering to a bow."""
    boxes = [
        B(-half_w + 2.4, low, -length_back, half_w - 2.4, 2, length_front, "antifoul"),
        B(-half_w, 2, -length_back, half_w, high, length_front, "paint", panels=True),
        B(-half_w + 0.4, 1.4, -length_back, half_w - 0.4, 2.2, length_front, "number"),    # boot stripe
    ]
    w = half_w
    for step in range(bow):
        w *= 0.7
        z0 = length_front + step * 3.4
        boxes.append(B(-w, low + 1 + step, z0, w, high, z0 + 3.4, "paint"))
    return boxes


def patrol_boat():
    hull = _hull(14.4, 27, 24, -2, 7, 3)
    deck = [
        B(-13.6, 7, -26, 13.6, 7.6, 23, "deck"),
        B(-14.4, 7.6, -27, -13.6, 9.4, 24, "paint"), B(13.6, 7.6, -27, 14.4, 9.4, 24, "paint"),   # gunwales
        B(-9, 7.6, -6, 9, 17, 6, "paint", panels=True),                     # cabin
        B(-8.4, 12, 6, 8.4, 16.4, 6.6, "glass"),
        B(-9.6, 12, -4, -9, 16, 4, "glass"), B(9, 12, -4, 9.6, 16, 4, "glass"),
        B(-10, 17, -7, 10, 18, 7, "dark"),                                  # roof
        B(-1.5, 18, 1, 1.5, 20, 4, "headlight", light=True),                # searchlight
        B(-0.4, 18, -4, 0.4, 30, -3.2, "dark"), B(-3, 27, -3.9, 3, 27.6, -3.3, "dark"),   # mast
        B(-5, 3, -30, 5, 10, -27, "dark", grille=True),                     # outboard engine
        B(-1, -3, -29, 1, 3, -28, "dark"),
        B(-8, 7.6, -22, -4, 11, -14, "stowage"), B(4, 7.6, -22, 8, 11, -14, "stowage"),
        B(-14.8, 9, -18, -14.4, 13, -14, "buoy"), B(14.4, 9, 10, 14.8, 13, 14, "buoy"),
        B(-12, 7.6, 12, -10, 10, 14, "dark"), B(10, 7.6, 12, 12, 10, 14, "dark"),   # cleats
    ]
    return _local(Part("root", children=[Part("body", boxes=hull + deck)]))


def gunboat():
    hull = _hull(17.6, 35, 33, -2.4, 8.5, 3)
    deck = [
        B(-16.8, 8.5, -34, 16.8, 9.1, 32, "deck"),
        B(-17.6, 9.1, -35, -16.8, 11, 33, "paint"), B(16.8, 9.1, -35, 17.6, 11, 33, "paint"),
        B(-10.4, 9.1, -19, 10.4, 23, -1, "paint", panels=True),             # bridge
        B(-9.8, 17, -1, 9.8, 22, -0.4, "glass"),
        B(-11, 17, -17, -10.4, 21.4, -3, "glass"), B(10.4, 17, -17, 11, 21.4, -3, "glass"),
        B(-11.4, 23, -20, 11.4, 24.2, 0, "dark"),
        B(-0.5, 24.2, -12, 0.5, 40, -11, "dark"), B(-5, 36, -12.2, 5, 36.8, -10.8, "dark"),   # mast and yard
        B(-2, 38, -13, 2, 40, -9, "plate"),                                 # radar
        B(-2, 24.2, -6, 2, 26, -2, "headlight", light=True),
        B(-6, 4, -38, 6, 12, -35, "dark", grille=True),
        B(-14, 9.1, -32, -9, 13, -24, "ammo_can"), B(9, 9.1, -32, 14, 13, -24, "ammo_can"),
        B(-18, 11, -10, -17.6, 15, -6, "buoy"), B(17.6, 11, -10, 18, 15, -6, "buoy"),
    ]
    gun = Part("gun", pivot=(0, 17, 22), boxes=[
        B(-1.4, 15.6, 22, 1.4, 18.4, 44, "dark", muzzle=True),
        B(-2, 15, 42, 2, 19, 46, "dark", muzzle=True),
        B(-2.6, 15, 20, 2.6, 19, 25, "dark"),
    ])
    turret = Part("turret", pivot=(0, 9.1, 22), boxes=[
        B(-7, 9.1, 15, 7, 11, 29, "dark"),                                  # ring
        B(-6, 11, 16, 6, 17, 28, "paint", panels=True),
        B(-6.6, 13, 27.5, 6.6, 21, 28.4, "paint"),                          # shield
        B(-6.6, 13, 16, -6, 21, 28, "paint"), B(6, 13, 16, 6.6, 21, 28, "paint"),
    ], children=[gun])
    return _local(Part("root", children=[Part("body", boxes=hull + deck), turret]))


def landing_craft():
    body = [
        B(-22.4, -2.4, -46, 22.4, 2, 36, "antifoul"),
        B(-22.4, 2, -46, -20.6, 15, 36, "paint", panels=True), B(20.6, 2, -46, 22.4, 15, 36, "paint", panels=True),   # sides
        B(-22.8, 6, -46, -22.4, 8, 36, "number"), B(22.4, 6, -46, 22.8, 8, 36, "number"),
        B(-20.6, 2, -46, 20.6, 8.8, 36, "paint"),
        B(-20.6, 8.8, -44, 20.6, 9.2, 36, "deck"),
        B(-22.4, 2, -48, 22.4, 15, -46, "paint", panels=True),              # stern
        # The pilot house at the back, with windows.
        B(-12.8, 9.2, -46, 12.8, 25, -36, "paint", panels=True),
        B(-12, 19, -36, 12, 23.5, -35.4, "glass"),
        B(-13.4, 19, -44, -12.8, 23, -38, "glass"), B(12.8, 19, -44, 13.4, 23, -38, "glass"),
        B(-13.6, 25, -47, 13.6, 26.2, -35, "dark"),
        B(-0.5, 26.2, -42, 0.5, 36, -41, "dark"),
        B(-2, 26.2, -40, 2, 28, -37, "headlight", light=True),
        # Benches down the sides for the troops, and two machine guns on the walls.
        B(-20.6, 9.2, -30, -16.6, 12, 30, "seat"), B(16.6, 9.2, -30, 20.6, 12, 30, "seat"),
        B(-20, 15, 20, -18, 17, 24, "dark"), B(-19.6, 16, 24, -18.4, 17, 32, "dark", muzzle=True),
        B(18, 15, 20, 20, 17, 24, "dark"), B(18.4, 16, 24, 19.6, 17, 32, "dark", muzzle=True),
        B(-8, -1, -49, 8, 6, -48, "dark"),
    ]
    ramp = Part("ramp", pivot=(0, 8.8, 37), boxes=[
        B(-20.8, 8.8, 37, 20.8, 29.6, 39, "paint", panels=True),
        B(-21.2, 8, 36, 21.2, 10.4, 38.4, "dark"),                          # hinge
        B(-18, 12, 39, 18, 13, 39.4, "hazard"), B(-18, 26, 39, 18, 27, 39.4, "hazard"),
    ])
    return _local(Part("root", children=[Part("body", boxes=body), ramp]))


DESIGNS = {
    "tunnel_borer": (tunnel_borer, {"paint": (226, 176, 40), "camo": None, "camo2": None}),
    "dirt_bike": (dirt_bike, {"paint": (232, 112, 30), "camo": None, "camo2": None}),
    "quad": (quad, {"paint": (96, 160, 50), "camo": None, "camo2": None}),
    "patrol_boat": (patrol_boat, {"paint": (98, 110, 104), "camo": None, "camo2": None}),
    "gunboat": (gunboat, {"paint": (84, 94, 98), "camo": (68, 76, 80), "camo2": None}),
    "landing_craft": (landing_craft, {"paint": (104, 112, 82), "camo": (84, 92, 64), "camo2": None}),
}


# ------------------------------------------------------------------ gear (drawn in the hand and the Armory as items)

def flamethrower():
    boxes = [
        B(-2.5, -6, -11, 2.5, 0.5, -2, "paint", panels=True),             # fuel tank
        B(-2.7, -3.6, -11.2, 2.7, -2.4, -1.8, "hazard"),
        B(-1, 0.5, -9, 1, 1.6, -7, "dark"), B(-1.6, 1.6, -9.4, 1.6, 2, -6.6, "dark"),   # filler cap
        B(-1.6, 0, -4, 1.6, 3.2, 6.5, "dark", panels=True),                 # body
        B(-1.4, 0.5, 6.5, 1.4, 2.9, 11, "plate"),                           # heat shroud
        B(-0.8, 0.9, 11, 0.8, 2.5, 17, "steel"),                            # wand
        B(-1.3, 0.4, 17, 1.3, 3.0, 19.2, "dark", muzzle=True),              # nozzle
        B(-0.6, -0.7, 16, 0.6, 0.5, 18, "beacon", light=True),              # pilot flame
        B(-1, -5.5, 0, 1, 0, 2.6, "seat"),                                  # grip
        B(-0.3, -2.2, 2.6, 0.3, -1.6, 4.4, "dark"),                         # trigger guard
        B(-1, -4.5, 8, 1, 0.5, 9.8, "seat"),                                # front grip
        B(1.6, -2.4, -3, 2.4, -1.6, 7, "tire"),                             # hose
        B(-0.4, 3.2, -2, 0.4, 4, 4, "dark"),                                # carry handle
    ]
    return _local(Part("root", children=[Part("body", boxes=boxes)]))


def grappling_hook():
    boxes = [
        B(-1.6, 0, -6, 1.6, 3.4, 6, "paint", panels=True),                  # launcher body
        B(-1.7, 1.2, -6.2, 1.7, 1.8, 6.2, "ruby"),                          # ruby stripe
        B(-1, -5, -2, 1, 0, 0.8, "seat"),                                   # grip
        B(-0.3, -2, 0.8, 0.3, -1.5, 2.6, "dark"),
        B(-2.4, -2.6, -5, 2.4, 0, 1, "rope"),                               # rope drum
        B(-2.6, -2.2, -4.6, 2.6, -0.4, 0.6, "dark"),
        B(-0.9, 0.8, 6, 0.9, 2.6, 10, "steel"),                             # barrel
        B(-0.5, 1.2, 10, 0.5, 2.2, 13, "steel"),                            # hook shaft
        B(-3.6, 1.2, 12.4, 3.6, 2.2, 13.4, "steel"),                        # claws across
        B(-0.5, -1.6, 12.4, 0.5, 5, 13.4, "steel"),
        B(-3.6, 1.2, 13.4, -2.8, 2.2, 15, "steel"), B(2.8, 1.2, 13.4, 3.6, 2.2, 15, "steel"),   # claw tips
        B(-0.5, -1.6, 13.4, 0.5, -0.8, 15, "steel"), B(-0.5, 4.2, 13.4, 0.5, 5, 15, "steel"),
        B(-0.7, 3.4, -3, 0.7, 4.4, 2, "dark"),                              # sight
    ]
    return _local(Part("root", children=[Part("body", boxes=boxes)]))


def night_goggles():
    boxes = [
        B(-5.5, 0, 0, -1.2, 4.4, 4.5, "dark"), B(1.2, 0, 0, 5.5, 4.4, 4.5, "dark"),     # lens tubes
        B(-5.1, 0.4, 4.5, -1.6, 4, 5.2, "lens", light=True), B(1.6, 0.4, 4.5, 5.1, 4, 5.2, "lens", light=True),
        B(-1.2, 1.2, 0.5, 1.2, 3.4, 3, "dark"),                             # bridge
        B(-2.2, 4.4, -0.5, 2.2, 5.6, 3.5, "paint", panels=True),            # mount
        B(-0.6, 5.6, 0, 0.6, 7, 2.4, "dark"),
        B(-6.2, 1, -6, -5.5, 3.4, 3, "tire"), B(5.5, 1, -6, 6.2, 3.4, 3, "tire"),       # strap
        B(-5.5, 1, -6.6, 5.5, 3.4, -6, "tire"),
        B(-5.7, 1.6, 1.6, -5.5, 2.8, 2.8, "ruby"),                          # power light
    ]
    # Lenses toward -z, so the Armory's view (and the shelf) shows them.
    for b in boxes:
        x0, y0, z0 = b.frm
        b.frm = (x0, y0, -(z0 + b.size[2]))
    return _local(Part("root", children=[Part("body", boxes=boxes)]))


def keycard():
    boxes = [
        B(-5, 0, -3.4, 5, 0.6, 3.4, "number"),                              # the card
        B(-5, 0.6, -3.4, 5, 0.72, -1.8, "ruby"),                            # ruby stripe
        B(-3.8, 0.6, -0.4, -1.4, 0.78, 1.8, "brass"),                       # chip
        B(0.8, 0.6, -1.2, 4, 0.74, 2.6, "plate"),                           # photo
        B(4, -0.05, -0.6, 4.8, 0.65, 0.6, "dark"),                          # clip hole
    ]
    return _local(Part("root", children=[Part("body", boxes=boxes)]))


def fire_bomb():
    boxes = [
        B(-2.2, 0, -2.2, 2.2, 6, 2.2, "bottle"),                            # bottle
        B(-2.3, 1.6, -2.3, 2.3, 4, 2.3, "paint"),                           # label
        B(-0.9, 6, -0.9, 0.9, 8.6, 0.9, "bottle"),                          # neck
        B(-1.1, 8.6, -1.1, 1.1, 10, 1.1, "canvas"),                         # rag
        B(-0.5, 10, -0.5, 0.5, 11.6, 0.5, "beacon", light=True),            # lit
        B(-1.6, 9.2, 0.4, -0.4, 10.4, 1.6, "canvas"),
    ]
    return _local(Part("root", children=[Part("body", boxes=boxes)]))


GEAR = {
    "flamethrower": (flamethrower, {"paint": (190, 40, 36), "camo": None, "camo2": None}),
    "grappling_hook": (grappling_hook, {"paint": (64, 70, 80), "camo": None, "camo2": None}),
    "night_goggles": (night_goggles, {"paint": (70, 84, 60), "camo": None, "camo2": None}),
    "keycard": (keycard, {"paint": (212, 30, 64), "camo": None, "camo2": None}),
    "fire_bomb": (fire_bomb, {"paint": (200, 70, 30), "camo": None, "camo2": None}),
}
