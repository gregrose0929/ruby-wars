"""Ground vehicle entity models (tank, jeep): geometry, painted textures, Java model code.

Same conventions as aircraft_models.py: x to the side, y up (0 = the ground),
z forward (the front points +z), in pixels (16 per block). The renderer draws
them at full size (1 pixel = 1/16 block).

Animated parts (VehicleModel.java):
  turret (yaw) > gun (pitch) > barrel (recoil) > flash
  wheel_* (spin; the jeep's front wheels also steer: wheel_fl / wheel_fr)
  cleats_top_l/r, cleats_bottom_l/r (track links that slide as the tank drives)
  steering (the jeep's steering wheel)

The tank's gun pivot sits 25.6 px up and 13.5 px ahead of the turret ring, and
the muzzle 46.5 px down the barrel: VehicleEntity.muzzlePosition() relies on it.

Running this writes:
  src/client/java/dev/rubymod/client/model/VehicleModels.java
  src/main/resources/assets/rubymod/textures/entity/vehicle/<name>.png and <name>_glow.png
"""
import math
import os
import random

from aircraft_models import Box, Part, B, mirror_x, all_boxes, write_png, f, clamp, mul

TEX = 1024


def footprint(b):
    w, h, d = (math.ceil(v) for v in b.size)
    return 2 * d + 2 * w, d + h


def pack(root):
    boxes = sorted(all_boxes(root), key=lambda b: -footprint(b)[1])
    x = y = row = 0
    for b in boxes:
        fw, fh = footprint(b)
        if x + fw > TEX:
            x = 0
            y += row
            row = 0
        if y + fh > TEX:
            raise SystemExit("texture too small")
        b.uv = (x, y)
        x += fw + 1
        row = max(row, fh + 1)


def face_rects(b):
    u, v = b.uv
    w, h, d = (math.ceil(x) for x in b.size)
    return {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "side_a": (u, v + d, d, h), "back": (u + d, v + d, w, h),
        "side_b": (u + d + w, v + d, d, h), "front": (u + 2 * d + w, v + d, w, h),
    }


def mix(a, b, t):
    return tuple(x + (y - x) * t for x, y in zip(a, b))


# ------------------------------------------------------------------ designs

def tank():
    hull = []
    # Lower hull between the tracks, and the upper hull / fender deck over them.
    hull += [
        B(-17, 3, -31, 17, 12, 29, "paint", panels=True),
        B(-23.5, 12, -33, 23.5, 16.5, 28, "paint", panels=True, deck=True),
        B(-23.8, 11.4, -33, 23.8, 12, 30, "dark"),                          # fender lip
        B(-16, 13, 28, 16, 16.5, 36, "paint", panels=True),                 # upper glacis
        B(-17, 5, 29, 17, 13, 33, "paint", panels=True),                    # lower front plate
        B(-14, 16.5, -30, 14, 17.2, -8, "grille"),                          # engine deck grilles
        B(-14, 16.5, -6, 14, 17.0, 6, "paint", panels=True),
        B(-20, 4, -35, 20, 15.5, -33, "paint", panels=True),                # rear plate
        B(-18, 9, -35.6, -14, 12, -35, "taillight", light=True),             # tail lights
        B(14, 9, -35.6, 18, 12, -35, "taillight", light=True),
        B(-5, 6, -35.8, 5, 8, -35, "dark"),                                 # tow pintle
        B(-21, 14.5, 35.6, -17, 16.5, 36.4, "headlight", light=True),       # headlights with guards
        B(17, 14.5, 35.6, 21, 16.5, 36.4, "headlight", light=True),
        B(-21.5, 14, 35.6, -16.5, 14.4, 37, "dark"),
        B(16.5, 14, 35.6, 21.5, 14.4, 37, "dark"),
        B(-12, 8, 33, -8, 11, 34.4, "dark"), B(8, 8, 33, 12, 11, 34.4, "dark"),   # tow hooks
        B(-4, 16.5, 30, 4, 17.8, 34, "dark"),                               # driver's hatch
        B(-3, 17.8, 31, 3, 18.3, 33.2, "glass"),                            # driver periscope
        B(-23.5, 16.5, -28, -20, 19, -18, "stowage"),                       # stowage bins on the fenders
        B(20, 16.5, -28, 23.5, 19, -18, "stowage"),
        B(-23, 16.5, 10, -19.5, 21, 15, "jerrycan"), B(19.5, 16.5, 10, 23, 21, 15, "jerrycan"),
    ]
    # Side skirts with panel seams.
    for s in (1, -1):
        x0, x1 = (23.5, 24.5) if s > 0 else (-24.5, -23.5)
        hull.append(B(x0, 7, -32, x1, 12.2, 30, "paint", skirt=True))
    # Running gear: road wheels, sprocket (rear), idler (front), return rollers.
    gear = []
    wheels = []
    for s, side in ((1, "l"), (-1, "r")):
        xi, xo = (17.5, 23) if s > 0 else (-23, -17.5)
        for i, z in enumerate((-22, -14.5, -7, 0.5, 8, 15.5, 23)):
            wheels.append(Part(f"wheel_{side}{i}", pivot=(0, 4.5, z), boxes=[
                B(xi, -4, -4, xo, 4, 4, "roadwheel"),
                B(xi + (1 if s > 0 else -1) * 0.0, -1.5, -1.5, xo + (0.4 if s > 0 else -0.4), 1.5, 1.5, "hub"),
            ]))
        wheels.append(Part(f"wheel_{side}s", pivot=(0, 8, -29), boxes=[B(xi, -4.5, -4.5, xo, 4.5, 4.5, "sprocket")]))
        wheels.append(Part(f"wheel_{side}i", pivot=(0, 8, 28.5), boxes=[B(xi, -3.8, -3.8, xo, 3.8, 3.8, "roadwheel")]))
        for z in (-12, 4, 18):
            gear.append(B(xi + 1, 10.4, z - 1, xo - 1, 12, z + 1, "dark"))      # return rollers
        # Track: bottom run, top run, and the angled ends.
        gear += [
            B(xi - 0.3, 0, -26, xo + 0.3, 1.2, 26, "track"),
            B(xi - 0.3, 12, -30, xo + 0.3, 13.2, 29, "track"),
        ]
        wheels.append(Part(f"track_front_{side}", pivot=(0, 1.2, 26), rot=(-50, 0, 0), boxes=[B(xi - 0.3, -1.2, 0, xo + 0.3, 0, 12.5, "track")]))
        wheels.append(Part(f"track_rear_{side}", pivot=(0, 1.2, -26), rot=(42, 0, 0), boxes=[B(xi - 0.3, -1.2, -16, xo + 0.3, 0, 0, "track")]))
        # Track links (cleats) that move as the tank drives: one set on the bottom run, one on top.
        cl_b = [B(xi - 0.4, -0.35, z, xo + 0.4, 0.0, z + 1.2, "cleat") for z in range(-26, 26, 3)]
        cl_t = [B(xi - 0.4, 13.2, z, xo + 0.4, 13.55, z + 1.2, "cleat") for z in range(-29, 29, 3)]
        wheels.append(Part(f"cleats_bottom_{side}", boxes=cl_b))
        wheels.append(Part(f"cleats_top_{side}", boxes=cl_t))

    # Turret: angular, with cheeks, bustle rack, cupola, smoke launchers, antennas.
    tz = -2.0
    turret_boxes = [
        B(-14, 0, -14, 14, 8.6, 12, "paint", panels=True, decal="24"),
        B(-12, 8.6, -12, 12, 9.6, 9, "paint", panels=True),                  # roof
        B(-15, 0.5, -24, 15, 7, -14, "paint", panels=True),                  # bustle
        B(-16, 7, -26, 16, 7.6, -14, "dark"),                                # bustle rack rails
        B(-16, 7.6, -26, -15.4, 10, -14, "dark"), B(15.4, 7.6, -26, 16, 10, -14, "dark"),
        B(-14.5, 7.6, -25, -2, 10.5, -16, "stowage"), B(1, 7.6, -25, 14.5, 9.8, -17, "stowage"),
        B(-6, 0, 12, 6, 7.8, 15.5, "paint"),                                # mantlet
        B(-11, 9.6, -6, -4, 11, 1, "dark"),                                  # commander's cupola
        B(-10.5, 11, -5.5, -4.5, 11.6, 0.5, "paint"),                        # hatch
        B(-11.2, 9.8, 0.6, -10.2, 10.8, 1.6, "glass"), B(-5, 9.8, 0.6, -4, 10.8, 1.6, "glass"),   # vision blocks
        B(4, 9.6, -6, 10, 10.6, 0, "paint"),                                 # loader's hatch
        B(6, 8.6, 6, 11, 12.2, 11.5, "dark"),                                # gunner's primary sight
        B(6.5, 9.0, 11.5, 10.5, 11.8, 11.8, "glass"),
        B(-13, 9.6, -11, -12.4, 30, -10.4, "dark"),                          # antennas
        B(12.4, 9.6, -11, 13, 24, -10.4, "dark"),
        B(-14.6, 3, -10, -14, 5, 6, "dark"),                                 # grab rails
        B(14, 3, -10, 14.6, 5, 6, "dark"),
    ]
    for s in (1, -1):                                                        # smoke grenade launchers: two banks of 4
        x = 14.5 * s
        for k in range(4):
            y = 2.5 + (k % 2) * 2.2
            zz = 4 + (k // 2) * 2.2
            turret_boxes.append(B(x - 1, y, zz, x + 1, y + 1.8, zz + 2.4, "smoke_tube"))
    mg = Part("cupola_mg", pivot=(-7.5, 11.6, -2.5), boxes=[
        B(-0.6, 0, -0.6, 0.6, 3, 0.6, "dark"),                               # mount
        B(-1.2, 3, -3, 1.2, 5, 4, "dark"),                                   # receiver
        B(-0.4, 3.6, 4, 0.4, 4.4, 13, "dark", muzzle=True),                  # barrel
        B(-0.9, 3.4, 4, 0.9, 4.6, 6, "dark"),
        B(1.2, 2.8, -1, 3.4, 4.6, 2, "ammo_can"),                            # ammo can
        B(-1.4, 5, -1, 1.4, 6.6, 0, "dark"),                                 # shield
    ])
    barrel = Part("barrel", boxes=[
        B(-1.6, -1.6, 0, 1.6, 1.6, 20, "paint", sleeve=True),                # thermal sleeve
        B(-2.2, -2.2, 20, 2.2, 2.2, 26, "paint", sleeve=True),               # bore evacuator
        B(-1.4, -1.4, 26, 1.4, 1.4, 44, "paint", sleeve=True),
        B(-1.9, -1.9, 44, 1.9, 1.9, 46.5, "dark", muzzle=True),              # muzzle reference sensor ring
    ], children=[Part("flash", pivot=(0, 0, 46.5), boxes=[
        B(-0.2, -6, 0, 0.2, 6, 10, "flash", light=True), B(-6, -0.2, 0, 6, 0.2, 10, "flash", light=True),
        B(-2.5, -2.5, 0, 2.5, 2.5, 4, "flash", light=True)])])
    gun = Part("gun", pivot=(0, 25.6 - 17, 13.5 - tz), boxes=[B(-3, -3, -4, 3, 3, 2, "dark")], children=[barrel])
    turret = Part("turret", pivot=(0, 17, tz), boxes=[shift(b, 0, 0, 0) for b in turret_boxes], children=[gun, mg])
    return Part("root", children=[Part("hull", boxes=hull + gear), turret] + wheels)


def shift(b, dx, dy, dz):
    return Box((b.frm[0] + dx, b.frm[1] + dy, b.frm[2] + dz), b.size, b.mat, **b.flags)


def jeep():
    body = [
        B(-17, 6, -28, 17, 14, 26, "paint", panels=True),                   # body tub
        B(-18, 13.5, 8, 18, 15.5, 28, "paint", panels=True),                # hood
        B(-16, 15.5, 10, 16, 16, 26, "paint"),                              # hood bulge
        B(-17, 6, 26, 17, 14, 30, "dark", grille=True),                     # grille
        B(-15, 11, 30, -10, 13.5, 30.6, "headlight", light=True),
        B(10, 11, 30, 15, 13.5, 30.6, "headlight", light=True),
        B(-19, 4, 28, 19, 7, 32, "dark"),                                   # bumper
        B(-20, 12, 12, 20, 14, 26, "paint"),                                # front fenders
        B(-20, 12, -26, 20, 14, -12, "paint"),                              # rear fenders
        B(-17.6, 15.5, 6, 17.6, 26, 7.2, "dark"),                           # windshield frame
        B(-16.4, 16.4, 6.2, -0.6, 25, 7.0, "glass"), B(0.6, 16.4, 6.2, 16.4, 25, 7.0, "glass"),
        B(-17.6, 26, -24, 17.6, 27, 7.2, "dark"),                           # roof frame
        B(-17.6, 14, -24, -16.6, 26, -22.8, "dark"), B(16.6, 14, -24, 17.6, 26, -22.8, "dark"),
        B(-17.6, 14, -8, -16.6, 26, -6.8, "dark"), B(16.6, 14, -8, 17.6, 26, -6.8, "dark"),
        B(-17, 26.2, -24, 17, 27.2, 7, "canvas"),                           # canvas roof
        B(-18, 14, -6, -17, 18, 5, "paint", panels=True), B(17, 14, -6, 18, 18, 5, "paint", panels=True),   # doors
        B(-19, 18, 5.5, -18, 20, 7.5, "glass"), B(18, 18, 5.5, 19, 20, 7.5, "glass"),        # mirrors
        B(-14, 14, -2, -2, 15, 4, "seat"), B(2, 14, -2, 14, 15, 4, "seat"),                  # seats
        B(-14, 15, -3, -2, 21, -2, "seat"), B(2, 15, -3, 14, 21, -2, "seat"),
        B(-14, 14, -20, 14, 15, -10, "seat"),
        B(-12, 14, 4, -4, 17, 6, "dark"),                                   # dashboard
        B(-17, 6, -32, 17, 16, -28, "paint", panels=True),                  # tailgate
        B(-16, 10, -32.6, -12, 12.5, -32, "taillight", light=True),
        B(12, 10, -32.6, 16, 12.5, -32, "taillight", light=True),
        B(-6, 9, -36, 6, 21, -32, "tire_spare"),                            # spare tire
        B(16.8, 15.5, -26, 17.4, 34, -25.4, "dark"),                        # antenna
        B(-16, 14, -28, -10, 19, -22, "jerrycan"),
        B(-18, 4, -30, 18, 6, 28, "dark"),                                  # chassis
    ]
    ring = Part("gunner", pivot=(0, 27.2, -9), boxes=[
        B(-6, 0, -6, 6, 1.2, 6, "dark"),                                    # turret ring
        B(-5, 1.2, 3, 5, 6, 3.8, "paint", panels=True),                     # gun shield
        B(-0.5, 1.2, -0.5, 0.5, 4, 0.5, "dark"),
        B(-1.3, 4, -3, 1.3, 6.2, 4, "dark"),                                # MG receiver
        B(-0.45, 4.6, 4, 0.45, 5.4, 14, "dark", muzzle=True),
        B(-1, 4.4, 4, 1, 5.6, 6, "dark"),
        B(1.3, 3.6, -1, 3.6, 5.6, 2, "ammo_can"),
    ])
    wheels = []
    for name, x, z in (("wheel_fl", 16, 19), ("wheel_fr", -16, 19), ("wheel_rl", 16, -19), ("wheel_rr", -16, -19)):
        xi, xo = (x - 1, x + 4) if x > 0 else (x - 4, x + 1)
        wheels.append(Part(name, pivot=(0, 6.5, z), boxes=[
            B(xi, -6.5, -6.5, xo, 6.5, 6.5, "tire"),
            B(xi - 0.2 if x < 0 else xi, -2.8, -2.8, xo + 0.2 if x > 0 else xo, 2.8, 2.8, "hub"),
        ]))
    steering = Part("steering", pivot=(-8, 18.5, 2.5), rot=(-35, 0, 0), boxes=[B(-2.5, -2.5, -0.3, 2.5, 2.5, 0.3, "dark")])
    return Part("root", children=[Part("body", boxes=body), ring, steering] + wheels)


VEHICLES = {
    "tank": (tank, {"paint": (90, 102, 64), "camo": (62, 72, 44), "camo2": (118, 108, 78)}),
    "jeep": (jeep, {"paint": (122, 112, 82), "camo": (98, 88, 62), "camo2": None}),
}


# ------------------------------------------------------------------ painting

MATERIALS = {
    "dark": (48, 50, 52), "glass": (40, 62, 80), "grille": (34, 36, 36), "stowage": (110, 102, 76), "jerrycan": (78, 92, 54),
    "track": (46, 44, 42), "cleat": (88, 86, 82), "roadwheel": (40, 40, 40), "hub": (96, 104, 76), "sprocket": (60, 62, 60),
    "smoke_tube": (62, 70, 48), "ammo_can": (84, 96, 58), "canvas": (150, 136, 98), "seat": (46, 44, 40), "tire": (30, 30, 32),
    "tire_spare": (30, 30, 32), "headlight": (255, 248, 220), "taillight": (255, 40, 30), "flash": (255, 220, 140),
}

DIGITS = {
    "0": ["111", "101", "101", "101", "111"], "1": ["010", "110", "010", "010", "111"], "2": ["111", "001", "111", "100", "111"],
    "3": ["111", "001", "111", "001", "111"], "4": ["101", "101", "111", "001", "001"],
}


def paint(img, glow, root, pal, seed):
    rnd = random.Random(seed)
    for b in all_boxes(root):
        for face, (fx, fy, fw, fh) in face_rects(b).items():
            for py in range(fh):
                for px in range(fw):
                    c, g = pixel(b, face, px, py, fw, fh, pal, rnd)
                    if g != "only":
                        img[fy + py][fx + px] = clamp(c) + (255,)
                    if g:
                        glow[fy + py][fx + px] = clamp(c) + (255,)


def pixel(b, face, px, py, fw, fh, pal, rnd):
    mat, fl = b.mat, b.flags
    n = rnd.uniform(-4, 4)
    edge = px == 0 or py == 0 or px == fw - 1 or py == fh - 1
    side = face in ("side_a", "side_b", "front", "back")
    shade = {"top": 1.14, "bottom": 0.6, "side_a": 0.95, "side_b": 0.95, "back": 0.86, "front": 1.0}[face]
    if mat == "flash":
        r = math.hypot(px + 0.5 - fw / 2, py + 0.5 - fh / 2) / max(1.0, max(fw, fh) / 2)
        c = mix((255, 250, 220), (255, 110, 20), min(1.0, r * 1.3))
        return mul(c, max(0.0, 1.0 - r)), "only"
    if mat in ("headlight", "taillight"):
        c = MATERIALS[mat]
        if edge:
            c = (40, 40, 40)
        return c, face in ("front", "back") and not edge
    if mat == "paint":
        base = pal["paint"]
        # Camo blotches (top and sides), from overlapping waves keyed on the texture position.
        if face != "bottom" and pal.get("camo"):
            k = math.sin((px + b.uv[0]) * 0.17) + math.sin((py + b.uv[1]) * 0.21 + px * 0.07) + math.sin((px - py) * 0.11 + b.uv[1])
            if k > 1.1:
                base = pal["camo"]
            elif k < -1.6 and pal.get("camo2"):
                base = pal["camo2"]
        c = mul(base, shade)
        if side and fh > 3:
            c = mul(c, 1.06 - 0.12 * py / (fh - 1))
            # Dust and mud toward the bottom of side faces.
            dirt = max(0.0, (py / (fh - 1)) - 0.55) * 1.6
            c = mix(c, (120, 104, 78), min(0.55, dirt))
        if fl.get("panels") and fw > 6 and fh > 4 and (px % 12 == 6 or py % 9 == 4):
            c = mul(c, 0.84)
        if fl.get("panels") and fw > 8 and fh > 5 and px % 12 == 8 and py % 9 == 6:
            c = mul(c, 0.7)                                         # bolts
        if fl.get("skirt") and side and px % 8 == 0:
            c = mul(c, 0.78)
        if fl.get("deck") and face == "top" and px % 16 == 0:
            c = mul(c, 0.85)
        if fl.get("sleeve") and face != "bottom" and (px if face in ("top",) else py) % 5 == 0:
            c = mul(c, 0.86)
        decal = fl.get("decal")
        if decal and face in ("side_a", "side_b") and fh >= 8 and fw >= 16:
            # Unit chevron and a two-digit number on the turret sides.
            ox, oy = int(fw * 0.55), 2
            dx, dy = px - ox, py - oy
            if 0 <= dy < 5:
                for i, ch in enumerate(decal):
                    gx = dx - i * 4
                    if 0 <= gx < 3 and DIGITS[ch][dy][gx] == "1":
                        c = (226, 222, 200)
            cx, cy = int(fw * 0.3), 3
            if abs(abs(px - cx) - (py - cy)) < 0.8 and cy <= py <= cy + 3:
                c = (226, 222, 200)
        if edge:
            c = mul(c, 1.18) if (side and py == 0) else mul(c, 0.82)
            if rnd.random() < 0.12:
                c = mix(c, (150, 146, 136), 0.5)                    # chipped paint
        return (c[0] + n, c[1] + n, c[2] + n), False
    c = MATERIALS.get(mat, (255, 0, 255))
    c = mul(c, shade)
    if mat == "glass":
        c = (30 + py * 3, 52 + py * 3, 70 + py * 3) if (px + py) % 7 > 1 else (150, 180, 200)
        if edge:
            c = (36, 38, 40)
        return c, False
    if mat == "grille" and (px % 3 == 0):
        c = (14, 14, 14)
    if mat == "grille" and fl.get("grille") is None and face == "top" and py % 3 == 0:
        c = (14, 14, 14)
    if fl.get("grille") and face == "front" and px % 3 == 0:
        c = (16, 16, 16)
    if mat == "track":
        along = px if face in ("side_a", "side_b") else py
        if along % 3 == 0:
            c = mul(c, 0.65)
        if face in ("top", "bottom") and (px == fw // 2 or px == fw // 2 - 1):
            c = mul(c, 1.3)                                         # guide teeth
    if mat in ("roadwheel", "tire", "tire_spare", "sprocket") and face in ("side_a", "side_b", "front", "back"):
        rr = math.hypot(px + 0.5 - fw / 2, py + 0.5 - fh / 2) / max(1, min(fw, fh) / 2)
        if face in ("side_a", "side_b") and mat != "tire_spare" or (mat == "tire_spare" and face in ("front", "back")):
            if mat == "sprocket":
                ang = math.atan2(py + 0.5 - fh / 2, px + 0.5 - fw / 2)
                c = (40, 40, 40) if rr > 0.86 and math.sin(ang * 10) > 0 else (78, 80, 74) if rr > 0.4 else (100, 106, 80)
            elif mat == "roadwheel":
                c = (26, 26, 26) if rr > 0.78 else (92, 104, 70) if rr > 0.3 else (60, 66, 50)
                if 0.45 < rr < 0.55 and (px + py) % 3 == 0:
                    c = (50, 52, 50)                               # bolt circle
            else:
                c = (28, 28, 30) if rr > 0.62 else (104, 98, 72) if rr > 0.25 else (60, 60, 58)
            if rr > 1.0:
                c = (28, 28, 30)
        elif mat in ("tire", "tire_spare"):
            c = (22, 22, 24) if (px + py * 2) % 4 < 2 else (36, 36, 38)   # tread
    if mat == "stowage" and (px % 6 == 0 or py % 5 == 2):
        c = mul(c, 0.78)                                            # straps
    if mat == "jerrycan" and side and abs(px - fw / 2) + abs(py - fh / 2) < 2:
        c = mul(c, 0.75)
    if mat == "smoke_tube" and face == "front":
        rr = math.hypot(px + 0.5 - fw / 2, py + 0.5 - fh / 2)
        if rr < min(fw, fh) * 0.35:
            c = (20, 20, 20)
    if b.flags.get("muzzle") and face == "front":
        rr = math.hypot(px + 0.5 - fw / 2, py + 0.5 - fh / 2)
        c = (10, 10, 10) if rr < min(fw, fh) * 0.3 else c
    if edge and mat not in ("track", "cleat"):
        c = mul(c, 0.82)
    return (c[0] + n, c[1] + n, c[2] + n), False


# ------------------------------------------------------------------ Java

def java_part(part, parent_var, out, depth):
    var = "p" + str(depth) + "_" + part.name
    px, py, pz = part.pivot
    rx, ry, rz = (math.radians(a) for a in part.rot)
    cubes = "CubeListBuilder.create()"
    for b in part.boxes:
        (x0, y0, z0), (w, h, d) = b.frm, b.size
        mx, my, mz = -(x0 + w), -(y0 + h), z0
        cubes += f".texOffs({b.uv[0]}, {b.uv[1]}).addBox({f(mx)}, {f(my)}, {f(mz)}, {f(w)}, {f(h)}, {f(d)})"
    pose = f"PartPose.offsetAndRotation({f(-px)}, {f(-py)}, {f(pz)}, {f(-rx)}, {f(-ry)}, {f(rz)})"
    out.append(f"\t\tPartDefinition {var} = {parent_var}.addOrReplaceChild(\"{part.name}\", {cubes}, {pose});")
    for c in part.children:
        java_part(c, var, out, depth + 1)


def relativize(part, parent_pivot=(0, 0, 0)):
    """Designs give every pivot and box in vehicle space; the model wants them relative to the parent's pivot."""
    piv = part.pivot
    for b in part.boxes:
        b.frm = (b.frm[0] - piv[0], b.frm[1] - piv[1], b.frm[2] - piv[2])
    for c in part.children:
        relativize(c, piv)
    part.pivot = (piv[0] - parent_pivot[0], piv[1] - parent_pivot[1], piv[2] - parent_pivot[2])


def main(root_dir):
    lines = ["package dev.rubymod.client.model;", "",
             "import net.minecraft.client.model.geom.PartPose;",
             "import net.minecraft.client.model.geom.builders.CubeListBuilder;",
             "import net.minecraft.client.model.geom.builders.LayerDefinition;",
             "import net.minecraft.client.model.geom.builders.MeshDefinition;",
             "import net.minecraft.client.model.geom.builders.PartDefinition;", "",
             "/** Generated by tools/vehicle_models.py - edit the designs there, not here. */",
             "public final class VehicleModels {",
             "\tprivate VehicleModels() {", "\t}", ""]
    for i, (name, (design, palette)) in enumerate(VEHICLES.items()):
        root = design()
        pack(root)
        img = [[(0, 0, 0, 0)] * TEX for _ in range(TEX)]
        glow = [[(0, 0, 0, 0)] * TEX for _ in range(TEX)]
        paint(img, glow, root, palette, 300 + i)
        out_dir = os.path.join(root_dir, "src/main/resources/assets/rubymod/textures/entity/vehicle")
        write_png(os.path.join(out_dir, name + ".png"), img)
        write_png(os.path.join(out_dir, name + "_glow.png"), glow)

        lines.append(f"\tpublic static LayerDefinition {name}() {{")
        lines.append("\t\tMeshDefinition mesh = new MeshDefinition();")
        lines.append("\t\tPartDefinition root = mesh.getRoot();")
        out = []
        for c in root.children:
            java_part(c, "root", out, 1)
        lines += out
        lines.append(f"\t\treturn LayerDefinition.create(mesh, {TEX}, {TEX});")
        lines.append("\t}")
        lines.append("")
        print(f"  {name}: {sum(1 for _ in all_boxes(root))} boxes")
    lines.append("}")
    with open(os.path.join(root_dir, "src/client/java/dev/rubymod/client/model/VehicleModels.java"), "w", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    print("vehicle models written")


if __name__ == "__main__":
    main(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
