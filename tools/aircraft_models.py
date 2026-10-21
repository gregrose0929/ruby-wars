"""Aircraft entity models: geometry, Java model code, and painted textures.

Each aircraft is a tree of parts made of boxes, written in "world" coordinates
of the model: x to the side, y up (0 = the ground under the wheels), z forward
(the nose points +z), in model pixels (16 per block before the renderer's scale).
Parts can be rotated (any angle), so wings can sweep and fins can lean.

Running this writes:
  src/client/java/dev/rubymod/client/model/AircraftModels.java   (LayerDefinitions)
  src/main/resources/assets/rubymod/textures/entity/aircraft/<name>.png
The texture is painted per box face to match Minecraft's box UV layout: base
paint (camo where asked), lighter tops, a different underside, panel lines,
canopy glass, intakes, glowing nozzles, insignia and nav lights.

Named parts the game animates (AircraftModel.java): gear, elevator_l/elevator_r,
aileron_l/aileron_r, rudder (and rudder_l/rudder_r), rotor, tail_rotor, lights.
"""
import math
import os
import random
import struct
import zlib

TEX = 256


class Box:
    def __init__(self, frm, size, mat, **flags):
        self.frm = frm
        self.size = size
        self.mat = mat
        self.flags = flags
        self.uv = None


class Part:
    def __init__(self, name, pivot=(0, 0, 0), rot=(0, 0, 0), boxes=(), children=()):
        self.name = name
        self.pivot = pivot
        self.rot = rot
        self.boxes = list(boxes)
        self.children = list(children)


def B(x0, y0, z0, x1, y1, z1, mat, **flags):
    """A box from corner to corner."""
    return Box((min(x0, x1), min(y0, y1), min(z0, z1)), (abs(x1 - x0), abs(y1 - y0), abs(z1 - z0)), mat, **flags)


def mirror_x(boxes):
    out = []
    for b in boxes:
        x0, y0, z0 = b.frm
        w, h, d = b.size
        out.append(Box((-x0 - w, y0, z0), (w, h, d), b.mat, **b.flags))
    return out


# ------------------------------------------------------------------ designs

def fighter():
    body = [
        B(-0.75, 9.25, 33, 0.75, 10.75, 35, "nose"),                 # radome tip
        B(-1.5, 8.5, 29, 1.5, 11.5, 33, "nose"),
        B(-2.5, 8, 24, 2.5, 12, 29, "skin"),
        B(-3.5, 7, 12, 3.5, 13, 24, "skin"),                          # forward fuselage
        B(-2, 13, 12, 2, 15.5, 21, "glass"),                         # canopy
        B(-1.5, 13, 21, 1.5, 14.5, 23, "glass"),
        B(-1.5, 13, 9, 1.5, 14.5, 12, "skin"),                        # canopy fairing
        B(-4.5, 6.5, -8, 4.5, 12.5, 12, "skin"),                      # center fuselage
        B(-2, 12.5, -14, 2, 14, 9, "skin"),                           # spine
        B(-7, 6.5, 2, -4.5, 10.5, 10, "skin", intake=True),          # intakes
        B(4.5, 6.5, 2, 7, 10.5, 10, "skin", intake=True),
        B(-4, 6.5, -24, 4, 12.5, -8, "skin"),                         # rear fuselage
        B(-2.75, 7, -28, 2.75, 12, -24, "nozzle"),                    # engine nozzle
        B(-1.5, 5.5, 4, 1.5, 6.5, 10, "dark"),                        # belly
        B(-0.4, 4.5, 13, 0.4, 5.5, 20, "dark"),                       # cannon fairing
    ]
    gear = Part("gear", boxes=[
        B(-0.5, 2, 20, 0.5, 7, 21, "strut"), B(-1, 0, 19.5, 1, 2.5, 22, "tire"),               # nose gear
        B(-5, 2, -6, -4, 6.5, -5, "strut"), B(-6, 0, -7, -4, 2.5, -4, "tire"),                  # mains
        B(4, 2, -6, 5, 6.5, -5, "strut"), B(4, 0, -7, 6, 2.5, -4, "tire"),
    ])
    wing_l = Part("wing_l", pivot=(4.5, 9, 6), rot=(0, 32, 0), boxes=[
        B(0, -0.6, -12, 14, 0.6, 0, "skin", roundel=True),
        B(14, -0.5, -9, 22, 0.5, 0, "skin"),
        B(22, -0.4, -6, 23, 0.4, 0, "light_red", light=True),
        B(16, -2, -6, 17, -0.5, -1, "dark"),                          # pylon
        B(15.5, -3.5, -8, 17.5, -2, 3, "missile"),                    # wing missile
    ], children=[Part("aileron_l", pivot=(14, 0, -9), boxes=[B(0, -0.45, -3, 8, 0.45, 0, "skin")])])
    wing_r = Part("wing_r", pivot=(-4.5, 9, 6), rot=(0, -32, 0), boxes=[
        B(-14, -0.6, -12, 0, 0.6, 0, "skin", roundel=True),
        B(-22, -0.5, -9, -14, 0.5, 0, "skin"),
        B(-23, -0.4, -6, -22, 0.4, 0, "light_green", light=True),
        B(-17, -2, -6, -16, -0.5, -1, "dark"),
        B(-17.5, -3.5, -8, -15.5, -2, 3, "missile"),
    ], children=[Part("aileron_r", pivot=(-14, 0, -9), boxes=[B(-8, -0.45, -3, 0, 0.45, 0, "skin")])])
    elev_l = Part("elevator_l", pivot=(4, 9, -21), rot=(0, 30, 0), boxes=[B(0, -0.4, -6, 10, 0.4, 2, "skin")])
    elev_r = Part("elevator_r", pivot=(-4, 9, -21), rot=(0, -30, 0), boxes=[B(-10, -0.4, -6, 0, 0.4, 2, "skin")])
    fin = Part("fin", pivot=(0, 12.5, -16), rot=(-28, 0, 0), boxes=[
        B(-0.5, 0, -8, 0.5, 13, 0, "skin", flash=True),
        B(-0.4, 13, -6, 0.4, 13.8, -1, "light_white", light=True),
    ], children=[Part("rudder", pivot=(0, 1, -8), boxes=[B(-0.4, 0, -3, 0.4, 10, 0, "skin")])])
    return Part("root", children=[Part("body", boxes=body), gear, wing_l, wing_r, elev_l, elev_r, fin])


def attack_jet():
    body = [
        B(-0.5, 7.5, 34, 0.5, 8.5, 39, "dark"),                       # 30 mm cannon barrel
        B(-2, 6, 30, 2, 10, 34, "skin"),                              # nose
        B(-3.5, 5, 18, 3.5, 12, 30, "skin"),                          # forward fuselage
        B(-2.5, 12, 19, 2.5, 15, 28, "glass"),                        # bubble canopy
        B(-4, 5, -6, 4, 12, 18, "skin"),                              # center fuselage
        B(-3, 6, -26, 3, 11, -6, "skin"),                             # tail cone
        B(-1, 11, -10, 1, 12.5, 18, "skin"),                          # spine
    ]
    # Engines on pylons above the rear fuselage (the A-10 look).
    for side in (-1, 1):
        x = 6.5 * side
        body += [
            B(x - 3, 12, -16, x + 3, 18, -4, "skin2"),                # nacelle
            B(x - 2.6, 12.4, -4, x + 2.6, 17.6, -3, "dark", intake=True),
            B(x - 2.5, 12.5, -18, x + 2.5, 17.5, -16, "nozzle"),
            B(x - 1, 10, -12, x + 1, 12, -6, "skin"),                 # pylon
        ]
    gear = Part("gear", boxes=[
        B(-0.5, 2, 26, 0.5, 6, 27, "strut"), B(-1, 0, 25.5, 1, 2.5, 28, "tire"),
        B(-9, 2, 2, -8, 6.5, 3, "strut"), B(-10, 0, 1, -8, 2.5, 4, "tire"),
        B(8, 2, 2, 9, 6.5, 3, "strut"), B(8, 0, 1, 10, 2.5, 4, "tire"),
    ])

    def wing(side):
        s = side
        boxes = [
            B(0, -0.75, -8, 30 * s, 0.75, 6, "skin2", roundel=True),  # straight wing
            B(30 * s, -0.6, -6, 31 * s, 0.6, 4, "light_red" if s > 0 else "light_green", light=True),
        ]
        for i, x in enumerate((9, 15, 21, 26)):                         # pylons and stores
            store = "bomb" if i < 2 else "missile"
            boxes += [B((x - 0.5) * s, -2.5, -2, (x + 0.5) * s, -0.75, 2, "dark"),
                      B((x - 1.2) * s, -4.5, -6, (x + 1.2) * s, -2.5, 5, store)]
        return Part("wing_" + ("l" if s > 0 else "r"), pivot=(4 * s, 7, 2), boxes=boxes,
                    children=[Part("aileron_" + ("l" if s > 0 else "r"), pivot=(18 * s, 0, -8),
                                   boxes=[B(0, -0.5, -3, 12 * s, 0.5, 0, "skin2")])])

    elev_l = Part("elevator_l", pivot=(3, 9, -22), boxes=[B(0, -0.5, -5, 13, 0.5, 2, "skin2")])
    elev_r = Part("elevator_r", pivot=(-3, 9, -22), boxes=[B(-13, -0.5, -5, 0, 0.5, 2, "skin2")])
    fins = []
    for s, n in ((1, "rudder_l"), (-1, "rudder_r")):
        fins.append(Part("fin_" + n[-1], pivot=(15 * s, 9, -22), boxes=[
            B(-0.5, 0, -4, 0.5, 9, 2, "skin2", flash=True),
            B(-0.5, -4, -4, 0.5, 0, 1, "skin2")], children=[Part(n, pivot=(0, -3, -4), boxes=[B(-0.4, 0, -3, 0.4, 11, 0, "skin2")])]))
    return Part("root", children=[Part("body", boxes=body), gear, wing(1), wing(-1), elev_l, elev_r] + fins)


def bomber():
    body = [
        B(-2.5, 9, 44, 2.5, 13, 48, "nose"),
        B(-4, 8, 34, 4, 15, 44, "skin"),
        B(-3, 15, 34, 3, 17.5, 42, "glass"),                          # flight deck windows
        B(-5, 7, -20, 5, 16, 34, "skin"),                             # long fuselage
        B(-3.5, 6.5, -5, 3.5, 7, 15, "dark"),                         # bomb bay doors
        B(-4, 8, -40, 4, 15, -20, "skin"),
        B(-2.5, 9, -46, 2.5, 14, -40, "skin"),                        # tail cone
        B(-1, 14, -46, 1, 15.5, -44, "light_white", light=True),
    ]
    gear = Part("gear", boxes=[
        B(-0.6, 2, 30, 0.6, 7.5, 31.2, "strut"), B(-1.5, 0, 29.5, 1.5, 3, 32.5, "tire"),
        B(-4, 2, -2, -3, 7, -1, "strut"), B(-5, 0, -4, -2.5, 3, 1, "tire"),
        B(3, 2, -2, 4, 7, -1, "strut"), B(2.5, 0, -4, 5, 3, 1, "tire"),
    ])

    def wing(side):
        s = side
        boxes = [
            B(0, -0.75, -16, 26 * s, 0.75, 0, "skin", roundel=True),
            B(26 * s, -0.6, -12, 40 * s, 0.6, 0, "skin"),
            B(40 * s, -0.5, -8, 41 * s, 0.5, 0, "light_red" if s > 0 else "light_green", light=True),
        ]
        for x in (12, 26):                                            # engine pods (2 per wing)
            boxes += [B((x - 0.6) * s, -3, -6, (x + 0.6) * s, -0.75, 0, "dark"),
                      B((x - 2.5) * s, -7, -10, (x + 2.5) * s, -3, 4, "skin2"),
                      B((x - 2.2) * s, -6.7, 4, (x + 2.2) * s, -3.3, 5, "dark", intake=True),
                      B((x - 2) * s, -6.5, -12, (x + 2) * s, -3.5, -10, "nozzle")]
        return Part("wing_" + ("l" if s > 0 else "r"), pivot=(5 * s, 12, 14), rot=(0, 25 * s, 0), boxes=boxes,
                    children=[Part("aileron_" + ("l" if s > 0 else "r"), pivot=(26 * s, 0, -12),
                                   boxes=[B(0, -0.5, -3, 13 * s, 0.5, 0, "skin")])])

    elev_l = Part("elevator_l", pivot=(2.5, 12, -38), rot=(0, 28, 0), boxes=[B(0, -0.5, -8, 16, 0.5, 2, "skin")])
    elev_r = Part("elevator_r", pivot=(-2.5, 12, -38), rot=(0, -28, 0), boxes=[B(-16, -0.5, -8, 0, 0.5, 2, "skin")])
    fin = Part("fin", pivot=(0, 15, -32), rot=(-25, 0, 0), boxes=[B(-0.6, 0, -12, 0.6, 20, 0, "skin", flash=True)],
               children=[Part("rudder", pivot=(0, 1, -12), boxes=[B(-0.5, 0, -4, 0.5, 17, 0, "skin")])])
    return Part("root", children=[Part("body", boxes=body), gear, wing(1), wing(-1), elev_l, elev_r, fin])


def helicopter():
    body = [
        B(-2.5, 6, 20, 2.5, 11, 26, "skin"),                          # nose / sensor
        B(-1.5, 7, 26, 1.5, 10, 28, "glass"),                         # sensor turret
        B(-3, 5, 2, 3, 14, 20, "skin"),                               # tandem cockpit section
        B(-2.6, 11, 12, 2.6, 15.5, 20, "glass"),                      # front seat glass
        B(-2.6, 12, 3, 2.6, 17, 12, "glass"),                         # rear seat glass (higher)
        B(-4, 5, -10, 4, 15, 2, "skin"),                              # engine section
        B(-6, 11, -8, -4, 15, 0, "skin2", intake=True),               # engine nacelles
        B(4, 11, -8, 6, 15, 0, "skin2", intake=True),
        B(-5.5, 11.5, -10, -4.5, 14.5, -8, "nozzle"),
        B(4.5, 11.5, -10, 5.5, 14.5, -8, "nozzle"),
        B(-2, 15, -6, 2, 18, 2, "dark"),                              # rotor mast fairing
        B(-1.5, 8, -40, 1.5, 12, -10, "skin"),                        # tail boom
        B(-0.6, 12, -44, 0.6, 22, -36, "skin", flash=True),           # tail fin
        B(-5, 10.5, -40, 5, 11.3, -36, "skin2"),                      # tail stabilizer
        B(-1, 3, 22, 1, 6, 24, "dark"),                               # chin gun turret
        B(-0.4, 3.8, 24, 0.4, 4.6, 30, "dark"),                       # chin gun barrel
        B(-0.4, 14, -44, 0.4, 14.8, -43, "light_white", light=True),
    ]
    for s in (1, -1):                                                 # stub wings with rocket pods and missiles
        body += [
            B(3 * s, 9, -4, 12 * s, 10, 2, "skin2"),
            B(7 * s - 1.5, 5, -6, 7 * s + 1.5, 9, 4, "dark"),         # rocket pod
            B(7 * s - 1.2, 5.3, 4, 7 * s + 1.2, 8.7, 4.3, "intake", intake=True),
            B(11 * s - 0.8, 6.5, -5, 11 * s + 0.8, 9, 4, "missile"),
            B(12 * s, 9, -3, 12.5 * s, 10, 1, "light_red" if s > 0 else "light_green", light=True),
        ]
    gear = Part("gear", boxes=[
        B(-4, 2, 14, -3, 5, 15, "strut"), B(-4.5, 0, 13, -2.5, 2.5, 16, "tire"),
        B(3, 2, 14, 4, 5, 15, "strut"), B(2.5, 0, 13, 4.5, 2.5, 16, "tire"),
        B(-0.5, 3, -38, 0.5, 8, -37, "strut"), B(-1, 1, -39, 1, 3.5, -36, "tire"),
    ])
    rotor = Part("rotor", pivot=(0, 18.5, -2), boxes=[
        B(-1.5, 0, -1.5, 1.5, 2, 1.5, "dark"),
        B(-0.8, 1, 1.5, 0.8, 1.6, 34, "rotor"), B(-0.8, 1, -34, 0.8, 1.6, -1.5, "rotor"),
        B(1.5, 1, -0.8, 34, 1.6, 0.8, "rotor"), B(-34, 1, -0.8, -1.5, 1.6, 0.8, "rotor"),
    ])
    tail_rotor = Part("tail_rotor", pivot=(1.2, 18, -42), boxes=[
        B(0, -0.5, -0.5, 0.6, 0.5, 0.5, "dark"),
        B(0, -6, -0.4, 0.4, 6, 0.4, "rotor"), B(0, -0.4, -6, 0.4, 0.4, 6, "rotor"),
    ])
    return Part("root", children=[Part("body", boxes=body), gear, rotor, tail_rotor])


AIRCRAFT = {
    "fighter_jet": (fighter, {"skin": (128, 136, 146), "skin2": (100, 108, 118), "under": (172, 178, 184), "camo": None}),
    "attack_jet": (attack_jet, {"skin": (86, 98, 70), "skin2": (78, 90, 64), "under": (150, 156, 150), "camo": (62, 70, 50)}),
    "bomber": (bomber, {"skin": (158, 162, 166), "skin2": (120, 124, 130), "under": (64, 68, 74), "camo": None}),
    "attack_heli": (helicopter, {"skin": (70, 78, 62), "skin2": (60, 68, 54), "under": (70, 78, 62), "camo": (48, 54, 42)}),
}


# ------------------------------------------------------------------ UV packing

def all_boxes(part):
    for b in part.boxes:
        yield b
    for c in part.children:
        yield from all_boxes(c)


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
            raise SystemExit("texture too small for " + str(len(boxes)) + " boxes")
        b.uv = (x, y)
        x += fw + 1
        row = max(row, fh + 1)


# ------------------------------------------------------------------ painting

def clamp(c):
    return tuple(max(0, min(255, int(v))) for v in c)


def mul(c, f):
    return clamp(tuple(v * f for v in c))


MATERIALS = {
    "nose": (70, 74, 80), "dark": (52, 55, 60), "strut": (170, 172, 176), "tire": (24, 24, 26),
    "nozzle": (60, 58, 56), "glass": (40, 70, 95), "missile": (225, 226, 222), "bomb": (70, 80, 60),
    "rotor": (36, 38, 40), "intake": (18, 18, 20),
    "light_red": (255, 40, 40), "light_green": (40, 255, 80), "light_white": (255, 255, 240),
}


def face_rects(b):
    u, v = b.uv
    w, h, d = (math.ceil(x) for x in b.size)
    return {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "side_a": (u, v + d, d, h), "back": (u + d, v + d, w, h),
        "side_b": (u + d + w, v + d, d, h), "front": (u + 2 * d + w, v + d, w, h),
    }


def paint(img, root, palette, seed):
    rnd = random.Random(seed)
    for b in all_boxes(root):
        for face, (fx, fy, fw, fh) in face_rects(b).items():
            for py in range(fh):
                for px in range(fw):
                    c = pixel(b, face, px, py, fw, fh, palette, rnd)
                    if 0 <= fx + px < TEX and 0 <= fy + py < TEX:
                        img[fy + py][fx + px] = c


def pixel(b, face, px, py, fw, fh, pal, rnd):
    mat = b.mat
    edge = px == 0 or py == 0 or px == fw - 1 or py == fh - 1
    n = rnd.uniform(-5, 5)
    if mat in ("skin", "skin2"):
        base = pal[mat]
        if face == "bottom":
            base = pal["under"]
        elif pal["camo"] is not None and face in ("top", "side_a", "side_b"):
            # Blotchy camo from a few overlapping waves.
            k = math.sin((px + b.uv[0]) * 0.31) + math.sin((py + b.uv[1]) * 0.27 + px * 0.13) + math.sin((px - py) * 0.19)
            if k > 0.9:
                base = pal["camo"]
        if face == "top":
            base = mul(base, 1.12)
        if b.flags.get("intake") and face == "front":
            base = (16, 16, 18)
        c = (base[0] + n, base[1] + n, base[2] + n)
        if fw > 6 and fh > 4 and (px % 9 == 4 or py % 7 == 3):
            c = mul(c, 0.86)                                          # panel lines
        if fw > 8 and fh > 4 and px % 9 == 6 and py % 7 == 5:
            c = mul(c, 0.75)                                          # rivets
        if b.flags.get("roundel") and face in ("top", "bottom"):
            cx, cy = fw * 0.62, fh * 0.5
            r = math.hypot(px + 0.5 - cx, py + 0.5 - cy)
            rad = min(fw, fh) * 0.32
            if r < rad:
                c = (190, 20, 45) if r < rad * 0.4 or r > rad * 0.75 else (235, 235, 230)
        if b.flags.get("flash") and face in ("side_a", "side_b") and fh > 6 and py < fh * 0.22:
            c = (180, 20, 40)
        if edge:
            c = mul(c, 0.8)
        return clamp(c) + (255,)
    if mat == "glass":
        t = (px + py) % 9
        c = (30 + py * 2, 60 + py * 2, 90 + py * 2) if t > 1 else (170, 200, 220)
        if edge:
            c = (40, 44, 50)
        return clamp(c) + (255,)
    if mat == "nozzle":
        if face == "back":
            cx, cy = fw / 2, fh / 2
            r = math.hypot(px + 0.5 - cx, py + 0.5 - cy) / (min(fw, fh) / 2)
            c = (255, 150, 40) if r < 0.35 else (20, 18, 18) if r < 0.75 else (70, 66, 62)
            return clamp(c) + (255,)
        c = mul(MATERIALS["nozzle"], 1.0 + 0.15 * ((px // 2) % 2))
        return clamp((c[0] + n, c[1] + n, c[2] + n)) + (255,)
    if mat == "missile":
        c = MATERIALS["missile"]
        if face == "front" or (face in ("top", "bottom", "side_a", "side_b") and py >= fh - 2):
            c = (190, 25, 40)
        return clamp((c[0] + n, c[1] + n, c[2] + n)) + (255,)
    if mat == "tire" and face in ("side_a", "side_b"):
        r = math.hypot(px + 0.5 - fw / 2, py + 0.5 - fh / 2)
        c = (150, 150, 155) if r < min(fw, fh) * 0.25 else (24, 24, 26)
        return c + (255,)
    c = MATERIALS.get(mat, (255, 0, 255))
    if mat.startswith("light"):
        return c + (255,)
    if edge:
        c = mul(c, 0.8)
    return clamp((c[0] + n, c[1] + n, c[2] + n)) + (255,)


def write_png(path, pixels):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = b"".join(b"\0" + bytes(v for p in row for v in p) for row in pixels)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", len(pixels[0]), len(pixels), 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


# ------------------------------------------------------------------ Java

def f(v):
    return f"{v:.3f}F"


def java_part(part, parent_var, out, depth):
    var = "p" + str(depth) + "_" + part.name
    px, py, pz = part.pivot
    rx, ry, rz = (math.radians(a) for a in part.rot)
    cubes = "CubeListBuilder.create()"
    for b in part.boxes:
        (x0, y0, z0), (w, h, d) = b.frm, b.size
        # Boxes are given in the part's own frame (relative to its pivot). World (x, y, z) -> model (-x, -y, z).
        mx, my, mz = -(x0 + w), -(y0 + h), z0
        cubes += f".texOffs({b.uv[0]}, {b.uv[1]}).addBox({f(mx)}, {f(my)}, {f(mz)}, {f(w)}, {f(h)}, {f(d)})"
    pose = f"PartPose.offsetAndRotation({f(-px)}, {f(-py)}, {f(pz)}, {f(-rx)}, {f(-ry)}, {f(rz)})"
    out.append(f"\t\tPartDefinition {var} = {parent_var}.addOrReplaceChild(\"{part.name}\", {cubes}, {pose});")
    for c in part.children:
        # Children pivots are given relative to the parent's pivot.
        java_part(c, var, out, depth + 1)


def main(root_dir):
    lines = ["package dev.rubymod.client.model;", "",
             "import net.minecraft.client.model.geom.PartPose;",
             "import net.minecraft.client.model.geom.builders.CubeListBuilder;",
             "import net.minecraft.client.model.geom.builders.LayerDefinition;",
             "import net.minecraft.client.model.geom.builders.MeshDefinition;",
             "import net.minecraft.client.model.geom.builders.PartDefinition;", "",
             "/** Generated by tools/aircraft_models.py - edit the designs there, not here. */",
             "public final class AircraftModels {",
             "\tprivate AircraftModels() {", "\t}", ""]
    for i, (name, (design, palette)) in enumerate(AIRCRAFT.items()):
        root = design()
        pack(root)
        img = [[(0, 0, 0, 0)] * TEX for _ in range(TEX)]
        paint(img, root, palette, 100 + i)
        write_png(os.path.join(root_dir, "src/main/resources/assets/rubymod/textures/entity/aircraft", name + ".png"), img)
        # Glow layer (drawn full-bright): nav lights and the hot center of each nozzle.
        glow = [[(0, 0, 0, 0)] * TEX for _ in range(TEX)]
        for y in range(TEX):
            for x in range(TEX):
                r, g, bl, a = img[y][x]
                if a and ((r > 230 and g < 80) or (g > 230 and r < 80) or (r > 245 and g > 245) or (r == 255 and 140 <= g <= 160 and bl < 60)):
                    glow[y][x] = (r, g, bl, 255)
        write_png(os.path.join(root_dir, "src/main/resources/assets/rubymod/textures/entity/aircraft", name + "_glow.png"), glow)
        method = "".join(w.capitalize() if j else w for j, w in enumerate(name.split("_")))
        lines.append(f"\tpublic static LayerDefinition {method}() {{")
        lines.append("\t\tMeshDefinition mesh = new MeshDefinition();")
        lines.append("\t\tPartDefinition root = mesh.getRoot();")
        out = []
        for c in root.children:
            java_part(c, "root", out, 1)
        lines += out
        lines.append(f"\t\treturn LayerDefinition.create(mesh, {TEX}, {TEX});")
        lines.append("\t}")
        lines.append("")
    lines.append("}")
    path = os.path.join(root_dir, "src/client/java/dev/rubymod/client/model/AircraftModels.java")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    print("aircraft models written")


if __name__ == "__main__":
    main(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
