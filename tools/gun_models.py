"""Gun models: detailed geometry, painted textures, and the animated Java model.

Each gun is a tree of named parts made of boxes, in model pixels: x across the
gun (+x is the gun's LEFT side, so the ejection port at -x faces right in your
hands), y up, z forward (the muzzle points +z). The origin is the top of the
pistol grip, where the right hand holds it.

Geometry is generated at 4 texels per pixel (every box is scaled by S = 4), so
a 1-pixel-thick rail still gets 4 texels of painted detail: bevelled edges,
worn corners, rail notches, vents, stippled grips, wood grain, engraved
markings, glowing sights.

Running this writes:
  src/client/java/dev/rubymod/client/model/GunModels.java   (LayerDefinitions + anchor points)
  src/main/resources/assets/rubymod/textures/entity/gun/<name>.png and <name>_glow.png

Named parts the game animates (GunModel.java): mag (and mag_std / mag_ext),
bolt, slide, pump, trigger, hammer, rocket, cell, and the attachment parts
att_red_dot, att_suppressor, att_grip, plus irons and muzzle_device which hide
when a red dot or suppressor replaces them.

Anchors (not drawn): sight (the eye point for aiming down sights), red_dot_sight,
muzzle, eject, hand_r, hand_l, mag_hand (where the left hand grabs the magazine).
"""
import math
import os
import random
import struct
import zlib

S = 4  # texels (and model units) per model pixel


class Box:
    def __init__(self, frm, size, mat, flags):
        self.frm = frm
        self.size = size
        self.mat = mat
        self.flags = flags
        self.uv = None


class Part:
    def __init__(self, name, pivot=None, rot=(0, 0, 0), boxes=(), children=(), rel=False):
        self.name = name
        self.pivot = pivot      # absolute (gun space); None = the parent's pivot
        self.rel = rel          # True: boxes are given relative to the pivot
        self.rot = rot
        self.boxes = list(boxes)
        self.children = list(children)


def B(x0, y0, z0, x1, y1, z1, mat, **flags):
    """A box from corner to corner, in pixels (snapped to the 1/4-pixel grid)."""
    q = lambda v: round(v * S) / S
    x0, y0, z0, x1, y1, z1 = (q(v) for v in (x0, y0, z0, x1, y1, z1))
    return Box((min(x0, x1), min(y0, y1), min(z0, z1)), (abs(x1 - x0), abs(y1 - y0), abs(z1 - z0)), mat, flags)


def P(name, boxes=(), pivot=None, rot=(0, 0, 0), children=(), rel=False):
    return Part(name, pivot, rot, boxes, children, rel)


def R(name, boxes=(), pivot=(0, 0, 0), rot=(0, 0, 0), children=()):
    """A part whose boxes are relative to its pivot (handy for parts that rotate or move)."""
    return Part(name, pivot, rot, boxes, children, True)


def ring(x, y, z0, z1, r, t, mat, **flags):
    """A square ring (four bars) around the axis (x, y), from z0 to z1: scope tubes, muzzle shrouds."""
    return [B(x - r, y + r - t, z0, x + r, y + r, z1, mat, **flags), B(x - r, y - r, z0, x + r, y - r + t, z1, mat, **flags),
            B(x - r, y - r + t, z0, x - r + t, y + r - t, z1, mat, **flags), B(x + r - t, y - r + t, z0, x + r, y + r - t, z1, mat, **flags)]


def tube(x, y, z0, z1, r, mat, **flags):
    """A round-ish tube: a cross of two boxes (reads as round at a glance)."""
    c = r * 0.62
    return [B(x - c, y - r, z0, x + c, y + r, z1, mat, **flags), B(x - r, y - c, z0, x + r, y + c, z1, mat, **flags)]


def picatinny(z0, z1, y, w=0.75):
    return [B(-w, y, z0, w, y + 0.5, z1, "rail"), B(-w - 0.15, y + 0.1, z0, w + 0.15, y + 0.35, z1, "rail")]


# Anchors are recorded as (name, x, y, z) relative to the gun origin.
class Gun:
    def __init__(self, root, anchors, palette=None):
        self.root = root
        self.anchors = anchors
        self.palette = palette or {}


def red_dot(z0, y):
    """Holographic red dot sitting on a rail top at height y, from z0 to z0 + 3.2."""
    return [
        B(-0.8, y, z0, 0.8, y + 0.6, z0 + 3.2, "black_metal"),                 # mount
        B(-1.0, y + 0.6, z0 + 0.2, -0.8, y + 2.6, z0 + 3.0, "black_metal"),    # hood sides (thin, for a wide window)
        B(0.8, y + 0.6, z0 + 0.2, 1.0, y + 2.6, z0 + 3.0, "black_metal"),
        B(-1.0, y + 2.6, z0 + 0.2, 1.0, y + 2.85, z0 + 3.0, "black_metal"),    # hood top
        B(-0.8, y + 0.6, z0 + 2.7, 0.8, y + 2.6, z0 + 2.8, "lens"),            # front window
        B(-0.8, y + 0.6, z0 + 0.3, 0.8, y + 2.6, z0 + 0.35, "lens_clear"),     # rear window
        B(-0.1, y + 1.4, z0 + 2.6, 0.1, y + 1.6, z0 + 2.7, "glow_red"),        # the dot
        B(0.9, y + 0.8, z0 + 1.0, 1.15, y + 1.6, z0 + 2.2, "black_metal", knurl=True),  # brightness knob
    ]


def suppressor(z0, y, r=1.0, length=6.5):
    return tube(0, y, z0, z0 + length, r, "suppressor") + [B(-0.4, y - 0.4, z0 + length, 0.4, y + 0.4, z0 + length + 0.05, "black_metal", bore=True)]


def vertical_grip(z, y):
    return [B(-0.6, y - 3.4, z, 0.6, y, z + 1.3, "polymer", stipple=True), B(-0.75, y - 3.6, z - 0.1, 0.75, y - 3.2, z + 1.4, "polymer"),
            B(-0.7, y - 0.4, z - 0.2, 0.7, y, z + 1.5, "black_metal")]


# ------------------------------------------------------------------ designs

def arr_rifle():
    bore = 3.5
    body = [
        B(-1.0, 0.0, -2.2, 1.0, 2.6, 4.2, "black_metal", mark=True),          # lower receiver
        B(-1.15, -0.8, 1.6, 1.15, 0.6, 4.2, "black_metal"),                     # magwell flare
        B(-1.1, 2.6, -3.2, 1.1, 4.6, 5.6, "black_metal", mark=True),           # upper receiver
        B(-1.25, 3.0, 0.4, -1.1, 4.1, 3.2, "steel"),                            # ejection port cover
        B(-1.3, 3.2, 3.4, -1.1, 3.8, 4.0, "black_metal"),                       # forward assist
        B(1.0, 1.4, 0.2, 1.2, 2.1, 0.9, "steel"),                               # safety selector
        B(1.0, 0.6, 3.6, 1.2, 1.2, 4.1, "steel"),                               # mag release
        B(-0.85, 2.7, -9.0, 0.85, 4.2, -3.2, "black_metal"),                    # buffer tube
        B(-1.25, 0.8, -11.6, 1.25, 4.3, -6.6, "olive_poly"),                    # stock
        B(-1.15, 0.2, -11.0, 1.15, 1.2, -7.6, "olive_poly"),                    # stock toe
        B(-1.3, -0.2, -12.0, 1.3, 4.4, -11.5, "rubber", stipple=True),          # butt pad
        B(-1.3, 4.3, -10.6, 1.3, 4.6, -7.0, "olive_poly"),                      # cheek riser
        B(-0.5, 2.0, -6.4, 0.5, 2.6, -5.2, "steel"),                            # stock lever
        B(-0.4, -1.3, -0.1, 0.4, -1.05, 2.0, "black_metal"),                    # trigger guard
        B(-0.4, -1.3, 1.8, 0.4, 0.0, 2.05, "black_metal"),
        B(-1.45, 2.1, 5.6, 1.45, 5.0, 13.4, "olive_poly", vents=True),          # handguard
        B(-1.2, 1.8, 5.8, 1.2, 2.1, 13.2, "olive_poly"),                        # handguard belly
        B(-0.65, 3.8, 13.4, 0.65, 5.0, 14.4, "steel"),                          # gas block
        *tube(0, bore, 13.4, 18.2, 0.5, "barrel"),                              # barrel
        B(-0.35, 4.4, 5.8, 0.35, 4.8, 13.4, "steel"),                           # gas tube (seen through vents)
        B(1.1, 2.4, -1.0, 1.35, 2.8, 0.2, "steel"),                             # sling loop
        B(-0.6, 4.2, -4.4, 0.6, 4.7, -3.2, "black_metal"),                      # charging handle
        B(-1.0, 4.25, -4.6, 1.0, 4.65, -4.2, "black_metal"),
    ]
    body += picatinny(-2.8, 5.4, 4.6) + picatinny(5.8, 13.2, 5.0)
    body += red_dot(0.2, 5.1)                                                   # built-in red dot sight
    grip = R("grip", pivot=(0, 0.2, -0.4), rot=(-20, 0, 0), boxes=[
        B(-0.95, -5.0, -1.3, 0.95, 0.0, 0.4, "polymer", stipple=True),
        B(-1.0, -5.4, -1.5, 1.0, -5.0, 0.6, "polymer"),
        B(-0.8, -4.6, 0.4, 0.8, -0.6, 0.55, "polymer"),                       # finger grooves
    ])
    mag_std = P("mag_std", boxes=[
        B(-0.85, -3.0, 1.75, 0.85, 0.5, 3.95, "tan_poly", ribs=True),
        B(-0.85, -6.0, 2.15, 0.85, -3.0, 4.35, "tan_poly", ribs=True),
        B(-0.95, -6.5, 2.05, 0.95, -6.0, 4.45, "polymer"),                     # base plate
        B(-0.5, 0.5, 2.0, 0.5, 0.75, 3.6, "brass"),                            # top round
    ])
    mag_ext = P("mag_ext", boxes=[
        B(-0.85, -3.0, 1.75, 0.85, 0.5, 3.95, "polymer", ribs=True),
        B(-0.85, -6.5, 2.2, 0.85, -3.0, 4.4, "polymer", ribs=True),
        B(-0.85, -9.5, 2.9, 0.85, -6.5, 5.1, "polymer", ribs=True),
        B(-0.95, -10.0, 2.8, 0.95, -9.5, 5.2, "black_metal"),
        B(-0.5, 0.5, 2.0, 0.5, 0.75, 3.6, "brass"),
    ])
    mag = P("mag", pivot=(0, 0.5, 2.8), children=[mag_std, mag_ext])
    bolt = P("bolt", boxes=[B(-1.15, 3.2, 0.6, -1.0, 3.9, 3.0, "steel_bright")])
    trigger = R("trigger", pivot=(0, 0, 0.9), boxes=[B(-0.15, -1.0, 0.0, 0.15, 0.0, 0.35, "steel")])
    irons = P("irons")  # none: the AR-R comes with its red dot
    muzzle_device = P("muzzle_device", boxes=[
        B(-0.75, bore - 0.75, 18.2, 0.75, bore + 0.75, 20.4, "black_metal", ports=True),
        B(-0.4, bore - 0.4, 20.4, 0.4, bore + 0.4, 20.45, "black_metal", bore=True)])
    att = [
        P("att_red_dot"),  # already built in
        P("att_suppressor", boxes=suppressor(18.2, bore, 1.0, 6.8)),
        P("att_grip", boxes=vertical_grip(9.0, 1.9)),
    ]
    root = P("root", children=[P("body", body), grip, mag, bolt, trigger, irons, muzzle_device] + att)
    return Gun(root, {
        "sight": (0, 5.1 + 1.5, 0.3), "red_dot_sight": (0, 5.1 + 1.5, 0.3), "muzzle": (0, bore, 20.6), "eject": (-1.2, 3.6, 1.8),
        "hand_r": (0, -1.6, -1.0), "hand_l": (0, 1.6, 9.5), "mag_hand": (0, -4.0, 3.0), "grip_hand_l": (0, -1.0, 9.6),
    })


def b_shift(b, dx, dy, dz):
    (x, y, z), (w, h, d) = b.frm, b.size
    return (x + dx, y + dy, z + dz, x + dx + w, y + dy + h, z + dz + d, b.mat)


def service_pistol():
    bore = 2.6
    body = [
        B(-0.85, 0.2, -0.6, 0.85, 1.9, 5.6, "polymer"),                        # frame
        B(-0.75, 0.3, 4.2, 0.75, 0.9, 6.4, "polymer"),                          # dust cover / accessory rail
        B(-0.9, 0.35, 4.6, 0.9, 0.75, 6.2, "rail"),
        B(-0.4, -1.2, 0.5, 0.4, -0.95, 2.6, "polymer"),                         # trigger guard
        B(-0.4, -1.2, 2.4, 0.4, 0.2, 2.65, "polymer"),
        B(-0.5, 1.5, -1.2, 0.5, 2.0, -0.4, "polymer"),                          # beaver tail
        B(0.85, 0.9, 1.0, 1.0, 1.4, 1.6, "steel"),                              # slide stop
        *tube(0, bore, 6.6, 7.0, 0.32, "barrel"),                               # barrel crown
    ]
    grip = R("grip", pivot=(0, 0.2, 0.0), rot=(-15, 0, 0), boxes=[
        B(-0.95, -4.4, -1.2, 0.95, 0.2, 0.9, "polymer", stipple=True),
        B(-1.0, -4.8, -1.3, 1.0, -4.4, 1.0, "polymer"),
    ])
    slide = P("slide", boxes=[
        B(-0.85, 1.9, -1.4, 0.85, 3.5, 6.8, "black_metal", serrations=True, mark=True),
        B(-0.7, 3.5, -1.0, 0.7, 3.65, 6.4, "black_metal"),
        B(-0.95, 2.4, 1.6, -0.85, 3.3, 3.6, "steel_bright"),                   # ejection port / barrel hood
        B(-0.4, 3.65, -1.2, 0.4, 4.15, -0.6, "black_metal", aperture=True),     # rear sight
        B(-0.15, 3.65, 6.0, 0.15, 4.2, 6.4, "black_metal"),                     # front sight
        B(-0.06, 3.95, 6.38, 0.06, 4.07, 6.42, "glow_green"),
        B(-0.35, 2.35, 6.8, 0.35, 3.05, 6.85, "black_metal", bore=True),
    ])
    mag = P("mag", pivot=(0, 0.0, 0.0), rot=(-15, 0, 0), children=[
        P("mag_std", boxes=[B(-0.8, -4.9, -0.9, 0.8, 0.0, 0.7, "steel"), B(-0.9, -5.2, -1.05, 0.9, -4.9, 0.85, "polymer")]),
        P("mag_ext", boxes=[B(-0.8, -7.0, -0.9, 0.8, 0.0, 0.7, "steel"), B(-0.9, -7.3, -1.05, 0.9, -7.0, 0.85, "polymer"),
                            B(-0.95, -6.9, -1.0, 0.95, -5.0, 0.8, "polymer", stipple=True)]),
    ])
    hammer = R("hammer", pivot=(0, 2.2, -1.3), boxes=[B(-0.25, 0.0, -0.4, 0.25, 0.9, 0.0, "steel")])
    trigger = R("trigger", pivot=(0, 0, 1.5), boxes=[B(-0.15, -0.9, 0.0, 0.15, 0.2, 0.3, "steel")])
    att = [
        R("att_red_dot", pivot=(0, 3.65, 0.6), boxes=[
            B(-0.65, 0.0, 0.0, 0.65, 0.35, 1.8, "black_metal"),
            B(-0.65, 0.35, 0.0, -0.45, 1.5, 1.8, "black_metal"), B(0.45, 0.35, 0.0, 0.65, 1.5, 1.8, "black_metal"),
            B(-0.65, 1.5, 0.0, 0.65, 1.7, 1.8, "black_metal"),
            B(-0.45, 0.35, 1.55, 0.45, 1.5, 1.6, "lens"),
            B(-0.08, 0.86, 1.45, 0.08, 1.02, 1.55, "glow_red"),
        ]),
        P("att_suppressor", boxes=suppressor(6.85, bore, 0.75, 4.5)),
        P("att_grip", boxes=[B(-0.5, -0.6, 4.7, 0.5, 0.35, 5.9, "black_metal"), B(-0.45, -0.5, 5.9, 0.45, 0.25, 6.0, "lens_clear"),
                             B(-0.3, -0.35, 6.0, 0.3, 0.1, 6.05, "glow_white")]),           # pistols get a weapon light
    ]
    root = P("root", children=[P("body", body), grip, slide, mag, hammer, trigger] + att)
    return Gun(root, {
        "sight": (0, 4.0, -1.1), "red_dot_sight": (0, 3.65 + 0.94, 0.8), "muzzle": (0, bore, 7.2), "eject": (-1.0, 3.0, 2.6),
        "hand_r": (0, -1.5, -0.5), "hand_l": (0, -1.8, 0.2), "mag_hand": (0, -4.5, -0.3), "grip_hand_l": (0, -1.8, 0.2),
    })


def combat_shotgun():
    bore = 3.6
    body = [
        B(-1.1, 1.4, -2.6, 1.1, 4.6, 4.4, "black_metal", mark=True),          # receiver
        B(-1.2, 1.6, 0.2, -1.1, 3.8, 3.4, "steel_bright"),                      # loading/ejection port
        B(-0.95, 1.0, 0.6, 0.95, 1.4, 3.8, "black_metal"),                      # loading gate
        B(-0.4, -0.3, -0.4, 0.4, -0.05, 1.8, "black_metal"),                    # trigger guard
        B(-0.4, -0.3, 1.6, 0.4, 1.4, 1.85, "black_metal"),
        *tube(0, bore + 0.3, 4.4, 17.6, 0.55, "barrel"),                        # barrel
        *tube(0, 2.25, 4.4, 15.2, 0.6, "steel"),                                # magazine tube
        B(-0.55, 1.65, 15.2, 0.55, 2.85, 15.6, "black_metal"),                  # mag cap
        B(-0.5, 2.85, 15.6, 0.5, 3.55, 16.2, "black_metal"),                    # barrel clamp
        B(-0.5, 4.6, -2.0, 0.5, 4.75, 3.8, "black_metal"),                     # receiver rib
        B(-0.7, 4.75, -1.9, -0.32, 5.35, -1.65, "black_metal"),                 # rear notch: two thin posts,
        B(0.32, 4.75, -1.9, 0.7, 5.35, -1.65, "black_metal"),                   # the front post shows in the gap
        B(-0.7, 4.6, -1.9, 0.7, 4.75, -1.4, "black_metal"),
        B(-0.3, bore + 0.85, 16.8, 0.3, bore + 1.05, 17.4, "black_metal"),      # front sight ramp
        B(-0.08, bore + 1.05, 17.0, 0.08, 5.25, 17.3, "black_metal"),
        B(-0.06, 5.15, 16.98, 0.06, 5.25, 17.0, "glow_red"),
        B(-0.4, bore - 0.1, 17.6, 0.4, bore + 0.7, 17.65, "black_metal", bore=True),
    ]
    stock = P("stock", boxes=[
        B(-1.15, 1.0, -9.6, 1.15, 3.6, -2.6, "wood", grain=True),
        B(-1.1, -0.4, -8.6, 1.1, 1.2, -4.0, "wood", grain=True),
        B(-1.25, -0.8, -10.2, 1.25, 3.8, -9.6, "rubber", stipple=True),
    ])
    grip = R("grip", pivot=(0, 1.2, -1.6), rot=(-25, 0, 0), boxes=[B(-0.95, -4.2, -1.1, 0.95, 0.2, 0.9, "wood", grain=True, checker=True)])
    pump = P("pump", boxes=[
        B(-1.2, 1.4, 6.8, 1.2, 3.3, 12.4, "wood", grain=True, grooves=True),
        B(-0.9, 1.2, 7.0, 0.9, 1.4, 12.2, "wood"),
        B(-0.25, 1.6, 4.4, 0.25, 2.0, 6.8, "steel"),                           # action bars
    ])
    shells = P("mag", pivot=(1.2, 1.2, -2.0), children=[P("mag_std", rel=True, boxes=[
        B(0.0, 0.0, 0.0, 0.5, 2.6, 0.5, "shell_red"), B(0.0, 0.0, 0.6, 0.5, 2.6, 1.1, "shell_red"),
        B(0.0, 0.0, 1.2, 0.5, 2.6, 1.7, "shell_red"), B(-0.05, -0.05, -0.05, 0.55, 0.5, 1.75, "brass"),
        B(-0.2, -0.2, -0.2, 0.0, 2.8, 1.9, "polymer")])])                       # side saddle
    trigger = R("trigger", pivot=(0, 1.2, 1.0), boxes=[B(-0.15, -1.2, 0.0, 0.15, 0.0, 0.35, "steel")])
    att = [
        P("att_red_dot", boxes=red_dot(-0.4, 5.2) + [B(-0.75, 4.6, -0.4, 0.75, 5.2, 2.8, "rail")]),
        P("att_suppressor", boxes=suppressor(17.6, bore + 0.3, 1.0, 5.0)),
        P("att_grip", boxes=vertical_grip(9.0, 1.2)),
    ]
    root = P("root", children=[P("body", body), stock, grip, pump, shells, trigger] + att)
    return Gun(root, {
        "sight": (0, 5.25, -1.9), "red_dot_sight": (0, 5.2 + 1.5, -0.1), "muzzle": (0, bore + 0.3, 17.8),
        "eject": (-1.2, 2.8, 1.6), "hand_r": (0, -1.6, -2.0), "hand_l": (0, 1.6, 9.6), "mag_hand": (1.4, 2.4, -1.2),
        "grip_hand_l": (0, -1.6, 9.6),
    })


def longshot_sniper():
    bore = 3.3
    body = [
        B(-1.2, 1.2, -3.0, 1.2, 4.2, 6.0, "black_metal", mark=True),          # action / receiver
        B(-0.9, 0.0, 0.6, 0.9, 1.2, 4.0, "black_metal"),                        # bottom metal
        B(-0.4, -1.0, 0.0, 0.4, -0.75, 1.9, "black_metal"),                     # trigger guard
        B(-0.4, -1.0, 1.7, 0.4, 0.0, 1.95, "black_metal"),
        *tube(0, bore, 6.0, 21.0, 0.6, "barrel", fluted=True),                  # fluted barrel
        B(-0.5, 4.2, -2.8, 0.5, 4.7, 6.0, "rail"),
        B(-0.55, 4.25, -3.0, 0.55, 4.6, 6.2, "rail"),
    ]
    chassis = P("chassis", boxes=[
        B(-1.4, 0.6, 4.4, 1.4, 3.6, 12.6, "olive_poly", vents=True),            # forend
        B(-1.3, 0.3, 5.0, 1.3, 0.6, 12.0, "olive_poly"),
        B(-1.1, 1.4, -6.0, 1.1, 4.0, -3.0, "olive_poly"),                       # stock neck
        B(-1.25, 0.0, -13.0, 1.25, 4.6, -6.0, "olive_poly"),                    # stock
        B(-1.15, 4.6, -11.5, 1.15, 5.6, -6.8, "olive_poly"),                    # adjustable cheek piece
        B(-0.3, 4.0, -9.0, 0.3, 4.6, -8.0, "steel"),
        B(-1.35, -0.4, -13.5, 1.35, 4.8, -13.0, "rubber", stipple=True),        # butt pad
        B(-0.7, -1.6, -11.5, 0.7, 0.0, -10.4, "olive_poly"),                    # monopod
    ])
    bipod = R("bipod", pivot=(0, 0.6, 11.0), rot=(70, 0, 0), boxes=[
        B(-1.0, -0.4, -0.6, 1.0, 0.0, 0.6, "black_metal"),
        B(-1.0, -5.5, -0.3, -0.6, -0.4, 0.3, "black_metal"), B(0.6, -5.5, -0.3, 1.0, -0.4, 0.3, "black_metal"),
        B(-1.1, -6.0, -0.4, -0.5, -5.5, 0.4, "rubber"), B(0.5, -6.0, -0.4, 1.1, -5.5, 0.4, "rubber"),
    ])
    grip = R("grip", pivot=(0, 0.6, -1.6), rot=(-18, 0, 0), boxes=[B(-0.95, -4.4, -1.3, 0.95, 0.3, 0.6, "polymer", stipple=True)])
    sy = 7.0  # scope axis height
    scope = P("scope", boxes=[
        *tube(0, sy, -1.4, 7.2, 0.85, "black_metal"),                            # main tube
        *tube(0, sy, 7.2, 10.6, 1.35, "black_metal"),                            # objective bell
        *tube(0, sy, -4.4, -1.4, 1.15, "black_metal"),                           # ocular
        B(-0.9, sy - 0.9, 10.6, 0.9, sy + 0.9, 10.7, "lens"),                    # objective lens
        B(-0.75, sy - 0.75, -4.45, 0.75, sy + 0.75, -4.4, "lens_clear"),         # eyepiece lens
        B(-0.45, sy + 0.85, 2.4, 0.45, sy + 1.9, 3.6, "black_metal", knurl=True), # elevation turret
        B(0.85, sy - 0.45, 2.4, 1.9, sy + 0.45, 3.6, "black_metal", knurl=True), # windage turret
        B(-1.25, sy - 0.5, -3.6, 1.25, sy + 0.5, -2.2, "black_metal", knurl=True),  # magnification ring
        B(-0.9, 4.7, 0.0, 0.9, sy - 0.4, 1.0, "black_metal"),                    # rings
        B(-0.9, 4.7, 4.8, 0.9, sy - 0.4, 5.8, "black_metal"),
        B(-1.0, sy + 0.4, 0.0, 1.0, sy + 1.0, 1.0, "black_metal"),
        B(-1.0, sy + 0.4, 4.8, 1.0, sy + 1.0, 5.8, "black_metal"),
    ])
    bolt = R("bolt", pivot=(-1.2, 3.0, 0.6), boxes=[
        B(-0.1, -0.35, -0.35, 0.0, 0.35, 0.35, "steel"),
        B(-1.6, -0.2, -0.2, -0.1, 0.2, 0.2, "steel_bright"),                    # bolt handle
        B(-2.2, -0.45, -0.45, -1.4, 0.45, 0.45, "black_metal", knurl=True),     # knob
    ])
    mag = P("mag", pivot=(0, 0, 2.3), children=[
        P("mag_std", boxes=[B(-0.8, -1.6, 0.9, 0.8, 0.0, 3.6, "black_metal"), B(-0.85, -1.9, 0.8, 0.85, -1.6, 3.7, "polymer")]),
        P("mag_ext", boxes=[B(-0.8, -3.4, 0.9, 0.8, 0.0, 3.6, "black_metal"), B(-0.85, -3.7, 0.8, 0.85, -3.4, 3.7, "polymer")]),
    ])
    trigger = R("trigger", pivot=(0, 0, 0.9), boxes=[B(-0.15, -0.8, 0.0, 0.15, 0.0, 0.3, "steel")])
    att = [
        P("att_suppressor", boxes=suppressor(21.0, bore, 1.0, 7.0)),
        P("att_grip", boxes=vertical_grip(9.0, 0.3)),
    ]
    muzzle_device = P("muzzle_device", boxes=[B(-0.85, bore - 0.85, 21.0, 0.85, bore + 0.85, 23.0, "black_metal", ports=True),
                                              B(-0.4, bore - 0.4, 23.0, 0.4, bore + 0.4, 23.05, "black_metal", bore=True)])
    root = P("root", children=[P("body", body), chassis, bipod, grip, scope, bolt, mag, trigger, muzzle_device] + att)
    return Gun(root, {
        "sight": (0, sy, -4.4), "red_dot_sight": (0, sy, -4.4), "muzzle": (0, bore, 23.2), "eject": (-1.3, 3.0, 2.0),
        "hand_r": (0, -1.5, -2.2), "hand_l": (0, 0.4, 9.0), "mag_hand": (0, -1.6, 2.3), "grip_hand_l": (0, -1.2, 9.6),
        "bolt_hand": (-2.0, 3.0, 0.6),
    })


def ruby_blaster():
    bore = 3.4
    body = [
        B(-1.3, 1.0, -3.4, 1.3, 5.0, 6.0, "white_poly", panels=True, mark=True),   # main shell
        B(-1.4, 1.8, -2.4, 1.4, 4.2, 5.0, "white_poly"),
        B(-1.0, 5.0, -2.6, 1.0, 5.6, 4.6, "graphite"),                              # spine
        B(-1.45, 2.4, -1.8, -1.3, 3.6, 3.8, "glow_ruby"),                            # side light strips
        B(1.3, 2.4, -1.8, 1.45, 3.6, 3.8, "glow_ruby"),
        B(-0.4, -0.2, 0.0, 0.4, 0.05, 2.2, "graphite"),                             # trigger guard
        B(-0.4, -0.2, 2.0, 0.4, 1.0, 2.25, "graphite"),
        B(-1.0, 0.6, -8.4, 1.0, 4.4, -3.4, "white_poly", panels=True),              # stock
        B(-1.1, 0.2, -9.0, 1.1, 4.8, -8.4, "graphite"),
        B(-0.6, 1.6, -7.6, 0.6, 3.4, -4.2, "glow_ruby_dim"),                         # stock window
        B(-1.0, 1.4, 6.0, 1.0, 5.0, 9.2, "graphite", vents=True),                   # emitter housing
        *tube(0, bore, 9.2, 13.4, 0.7, "steel_bright"),                             # emitter
        B(-1.5, bore - 1.5, 10.0, 1.5, bore + 1.5, 10.6, "ruby_metal"),             # focusing rings
        B(-1.4, bore - 1.4, 11.6, 1.4, bore + 1.4, 12.2, "ruby_metal"),
        B(-1.2, bore - 1.2, 13.0, 1.2, bore + 1.2, 13.4, "ruby_metal"),
        B(-0.5, bore - 0.5, 13.4, 0.5, bore + 0.5, 13.5, "glow_ruby"),               # emitter tip
        B(-0.7, 5.6, -2.0, -0.32, 6.3, -1.75, "graphite"),                          # rear notch posts
        B(0.32, 5.6, -2.0, 0.7, 6.3, -1.75, "graphite"),
        B(-0.12, 5.6, 7.6, 0.12, 6.2, 8.0, "graphite"),
        B(-0.06, 6.1, 7.98, 0.06, 6.2, 8.02, "glow_ruby"),
    ]
    core = R("core", pivot=(0, 5.6, 1.0), boxes=[                                    # exposed ruby core on top (low, under the sights)
        B(-0.8, 0.0, -0.8, 0.8, 0.4, 1.6, "ruby"),
        B(-0.5, 0.4, -0.4, 0.5, 0.52, 1.2, "glow_ruby"),
    ])
    grip = R("grip", pivot=(0, 0.6, -0.6), rot=(-20, 0, 0), boxes=[B(-0.95, -4.6, -1.3, 0.95, 0.4, 0.5, "graphite", stipple=True)])
    cell = P("mag", pivot=(0, 1.0, 3.2), children=[P("mag_std", rel=True, boxes=[
        B(-0.8, -2.8, -1.0, 0.8, 0.0, 1.0, "white_poly"),
        B(-0.6, -2.6, -1.05, 0.6, -0.4, 1.05, "glow_ruby"),
        B(-0.85, -3.2, -1.1, 0.85, -2.8, 1.1, "graphite")]),
        P("mag_ext", rel=True, boxes=[B(-0.8, -4.6, -1.0, 0.8, 0.0, 1.0, "white_poly"),
                            B(-0.6, -4.4, -1.05, 0.6, -0.4, 1.05, "glow_ruby"),
                            B(-0.85, -5.0, -1.1, 0.85, -4.6, 1.1, "graphite")])])
    trigger = R("trigger", pivot=(0, 1.0, 1.0), boxes=[B(-0.15, -1.0, 0.0, 0.15, 0.0, 0.3, "steel")])
    att = [
        P("att_red_dot", boxes=red_dot(2.2, 5.6)),
        P("att_suppressor", boxes=tube(0, bore, 13.4, 17.6, 1.0, "graphite") + [B(-0.5, bore - 0.5, 17.6, 0.5, bore + 0.5, 17.65, "glow_ruby")]),
        P("att_grip", boxes=vertical_grip(7.0, 1.4)),
    ]
    root = P("root", children=[P("body", body), core, grip, cell, trigger] + att)
    return Gun(root, {
        "sight": (0, 6.2, -2.0), "red_dot_sight": (0, 5.6 + 1.5, 2.5), "muzzle": (0, bore, 13.8), "eject": (-1.4, 4.0, 1.0),
        "hand_r": (0, -1.6, -1.2), "hand_l": (0, 1.2, 7.6), "mag_hand": (0, -1.8, 3.2), "grip_hand_l": (0, -1.6, 7.6),
    }, palette={"glow_ruby": True})


def rocket_launcher():
    ty = 5.0  # tube axis
    body = [
        *tube(0, ty, -9.0, 14.0, 1.6, "olive_paint", stripes_at=(2.0, 3.0)),     # launch tube
        *tube(0, ty, 14.0, 15.6, 1.95, "black_metal"),                          # muzzle bell
        *tube(0, ty, -11.0, -9.0, 1.95, "black_metal"),                         # venturi
        B(-1.2, ty - 1.2, -11.05, 1.2, ty + 1.2, -11.0, "black_metal", bore=True),
        B(-0.8, ty - 2.0, -2.0, 0.8, ty - 1.4, 6.0, "black_metal"),             # rail under tube
        B(-0.9, ty + 1.4, -6.0, 0.9, ty + 2.0, 8.0, "olive_paint"),
        B(-0.5, ty + 1.5, -1.0, 0.5, ty + 2.2, 3.0, "black_metal"),             # carry handle base
        B(-0.4, ty + 2.2, -1.0, 0.4, ty + 3.2, -0.4, "black_metal"),
        B(-0.4, ty + 2.2, 2.4, 0.4, ty + 3.2, 3.0, "black_metal"),
        B(-0.4, ty + 3.2, -1.0, 0.4, ty + 3.6, 3.0, "rubber"),
        B(0.6, ty + 1.4, -2.4, 1.6, ty + 1.8, -1.6, "black_metal"),              # rear sight leaf
        *ring(1.1, ty + 2.5, -2.3, -2.0, 0.5, 0.15, "black_metal"),
        B(0.6, ty + 1.4, 9.0, 1.6, ty + 1.8, 9.6, "black_metal"),                # front sight with range bars
        B(0.6, ty + 1.8, 9.2, 0.75, ty + 3.2, 9.4, "black_metal"),
        B(1.45, ty + 1.8, 9.2, 1.6, ty + 3.2, 9.4, "black_metal"),
        B(0.75, ty + 2.45, 9.2, 1.45, ty + 2.55, 9.4, "black_metal"),
        B(0.75, ty + 2.95, 9.2, 1.45, ty + 3.05, 9.4, "black_metal"),
        B(-2.8, ty - 0.4, 2.0, -1.6, ty + 1.2, 5.4, "black_metal"),             # optic (stowed, right side)
        B(-2.7, ty - 0.2, 5.4, -1.7, ty + 1.0, 5.45, "lens"),
        B(-0.5, ty - 1.4, -9.2, 0.5, ty + 1.4, -8.8, "warning"),               # warning band
        B(-0.8, ty - 4.8, 4.6, 0.8, ty - 2.0, 5.8, "polymer", stipple=True),    # front grip
        B(-0.4, ty - 2.6, 0.4, 0.4, ty - 2.35, 2.0, "black_metal"),             # trigger guard
        B(-1.7, ty - 0.6, -7.0, -1.5, ty + 0.6, -5.0, "steel"),                 # sling mount
    ]
    grip = R("grip", pivot=(0, ty - 2.0, 0.0), rot=(-15, 0, 0), boxes=[B(-0.9, -4.2, -1.2, 0.9, 0.0, 0.6, "polymer", stipple=True)])
    rocket = R("rocket", pivot=(0, ty, 15.6), boxes=[
        *tube(0, 0, -1.2, 1.6, 1.35, "olive_paint"),
        *tube(0, 0, 1.6, 3.6, 1.0, "rocket_head"),
        B(-0.4, -0.4, 3.6, 0.4, 0.4, 4.4, "brass"),                            # fuze tip
    ])
    trigger = R("trigger", pivot=(0, ty - 2.0, 1.2), boxes=[B(-0.15, -0.6, 0.0, 0.15, 0.0, 0.3, "steel")])
    root = P("root", children=[P("body", body), grip, rocket, trigger])
    return Gun(root, {
        "sight": (1.1, ty + 2.5, -2.3), "red_dot_sight": (1.1, ty + 2.5, -2.3), "muzzle": (0, ty, 16.0), "eject": (0, ty, -11.2),
        "hand_r": (0, ty - 3.4, -0.5), "hand_l": (0, ty - 3.6, 5.2), "mag_hand": (0, ty, 16.0), "grip_hand_l": (0, ty - 3.6, 5.2),
    })


def skyhammer():
    ty = 5.2
    body = [
        *tube(0, ty, -12.0, 16.0, 1.2, "olive_paint", stripes_at=(10.0,)),        # long launch tube
        *tube(0, ty, 16.0, 16.8, 1.4, "black_metal"),
        *tube(0, ty, -12.6, -12.0, 1.4, "black_metal"),
        B(-0.8, ty - 0.8, 16.8, 0.8, ty + 0.8, 17.6, "seeker"),                    # seeker dome
        B(-1.25, ty - 2.4, -3.0, 1.25, ty - 1.0, 5.0, "black_metal"),             # gripstock
        B(-1.4, ty - 2.2, -2.6, -1.25, ty - 1.2, 0.6, "battery"),                  # battery coolant unit
        B(-2.8, ty - 2.6, -3.6, -1.4, ty - 0.8, 0.6, "battery"),
        B(-0.4, ty + 1.2, -2.0, 0.4, ty + 2.6, -1.4, "black_metal"),              # sight posts
        B(-0.4, ty + 1.2, 2.4, 0.4, ty + 2.6, 3.0, "black_metal"),
        *ring(0, ty + 3.0, 2.4, 2.6, 1.3, 0.15, "black_metal"),                  # range ring
        *ring(0, ty + 3.0, -2.0, -1.7, 0.6, 0.18, "black_metal"),
        B(1.4, ty - 0.4, 0.0, 3.6, ty + 0.4, 0.6, "black_metal"),                 # IFF antenna arm
        *[B(3.6, ty - 3.0 + i * 1.2, -0.6, 3.8, ty - 2.8 + i * 1.2, 1.4, "steel") for i in range(5)],  # IFF antenna grid
        B(3.6, ty - 3.0, -0.6, 3.8, ty + 2.0, -0.4, "steel"),
        B(-0.4, ty - 2.6, 0.4, 0.4, ty - 2.4, 2.2, "black_metal"),
        B(-0.5, ty - 1.2, 15.0, 0.5, ty + 1.2, 15.4, "warning"),
    ]
    grip = R("grip", pivot=(0, ty - 2.4, 0.0), rot=(-12, 0, 0), boxes=[B(-0.9, -4.0, -1.2, 0.9, 0.0, 0.6, "polymer", stipple=True)])
    trigger = R("trigger", pivot=(0, ty - 2.4, 1.2), boxes=[B(-0.15, -0.6, 0.0, 0.15, 0.0, 0.3, "steel")])
    rocket = R("rocket", pivot=(0, ty, 16.8), boxes=[B(-0.6, -0.6, -0.2, 0.6, 0.6, 0.9, "seeker_glow")])  # seeker glows when loaded
    root = P("root", children=[P("body", body), grip, trigger, rocket])
    return Gun(root, {
        "sight": (0, ty + 3.0, -2.0), "red_dot_sight": (0, ty + 3.0, -2.0), "muzzle": (0, ty, 17.8), "eject": (0, ty, -12.8),
        "hand_r": (0, ty - 3.8, -0.5), "hand_l": (0, ty - 1.4, 7.5), "mag_hand": (0, ty, 17.0), "grip_hand_l": (0, ty - 1.4, 7.5),
    })


def lancer():
    ty = 5.4
    body = [
        *tube(0, ty, -11.0, 15.0, 2.0, "olive_paint", stripes_at=(12.5,)),      # fat launch tube
        *tube(0, ty, 15.0, 16.0, 2.3, "black_metal"),
        *tube(0, ty, -12.0, -11.0, 2.3, "black_metal"),
        B(-1.6, ty - 1.6, 16.0, 1.6, ty + 1.6, 16.05, "seeker"),                 # front cap seeker window
        B(-0.4, ty - 2.4, 0.4, 0.4, ty - 2.15, 2.0, "black_metal"),
        B(-2.2, ty - 0.8, -8.0, -2.0, ty + 0.8, 10.0, "rubber"),                 # shoulder pad / straps
    ]
    clu = P("clu", boxes=[                                                      # command launch unit
        B(-2.6, ty + 2.0, -3.6, 2.6, ty + 5.4, 3.4, "olive_paint", panels=True, mark=True),
        B(-2.4, ty + 2.2, 3.4, 2.4, ty + 5.2, 4.0, "black_metal"),
        B(-1.6, ty + 3.0, 4.0, -0.2, ty + 4.6, 4.05, "lens"),                    # day sight
        B(0.4, ty + 3.0, 4.0, 1.8, ty + 4.6, 4.05, "lens_thermal"),              # thermal sight
        B(-0.9, ty + 3.0, -5.0, 0.9, ty + 4.6, -3.6, "rubber"),                  # eyecup
        B(-0.7, ty + 3.2, -5.05, 0.7, ty + 4.4, -5.0, "lens_clear"),
        B(-3.6, ty + 1.0, -1.4, -2.6, ty + 2.6, 1.4, "black_metal", knurl=True), # handles
        B(2.6, ty + 1.0, -1.4, 3.6, ty + 2.6, 1.4, "black_metal", knurl=True),
        B(-2.8, ty + 4.6, -2.6, -2.6, ty + 5.0, -1.4, "glow_green"),             # status lights
        B(-2.8, ty + 4.0, -2.6, -2.6, ty + 4.4, -1.4, "glow_red"),
        B(-1.4, ty + 1.6, -3.0, 1.4, ty + 2.0, 3.0, "black_metal"),
    ])
    grip = R("grip", pivot=(0, ty - 2.2, 0.0), rot=(-10, 0, 0), boxes=[B(-0.9, -4.2, -1.2, 0.9, 0.0, 0.6, "polymer", stipple=True)])
    trigger = R("trigger", pivot=(0, ty - 2.2, 1.2), boxes=[B(-0.15, -0.6, 0.0, 0.15, 0.0, 0.3, "steel")])
    rocket = R("rocket", pivot=(0, ty, 16.0), boxes=[B(-1.0, -1.0, -0.4, 1.0, 1.0, 0.2, "seeker_glow")])
    root = P("root", children=[P("body", body), clu, grip, trigger, rocket])
    return Gun(root, {
        "sight": (0, ty + 3.8, -5.2), "red_dot_sight": (0, ty + 3.8, -5.2), "muzzle": (0, ty, 16.4), "eject": (0, ty, -12.2),
        "hand_r": (0, ty - 3.6, -0.5), "hand_l": (2.8, ty + 1.8, 0.0), "mag_hand": (0, ty, 16.0), "grip_hand_l": (2.8, ty + 1.8, 0.0),
    })


GUNS = {
    "arr_rifle": arr_rifle,
    "service_pistol": service_pistol,
    "combat_shotgun": combat_shotgun,
    "longshot_sniper": longshot_sniper,
    "ruby_blaster": ruby_blaster,
    "rocket_launcher": rocket_launcher,
    "skyhammer": skyhammer,
    "lancer": lancer,
}


# ------------------------------------------------------------------ UV packing

def all_boxes(part):
    for b in part.boxes:
        yield b
    for c in part.children:
        yield from all_boxes(c)


def dims(b):
    return tuple(max(1, round(v * S)) for v in b.size)


def footprint(b):
    w, h, d = dims(b)
    return 2 * d + 2 * w, d + h


def pack(root, tex):
    boxes = sorted(all_boxes(root), key=lambda b: -footprint(b)[1])
    x = y = row = 0
    for b in boxes:
        fw, fh = footprint(b)
        if x + fw > tex:
            x = 0
            y += row
            row = 0
        if y + fh > tex:
            return False
        b.uv = (x, y)
        x += fw
        row = max(row, fh)
    return True


# ------------------------------------------------------------------ painting

def clamp(c):
    return tuple(max(0, min(255, int(round(v)))) for v in c)


def mul(c, f):
    return tuple(v * f for v in c)


def mix(a, b, t):
    return tuple(x + (y - x) * t for x, y in zip(a, b))


MATERIALS = {
    "black_metal": (44, 46, 51), "steel": (92, 96, 104), "steel_bright": (150, 154, 160), "barrel": (36, 37, 40),
    "polymer": (34, 35, 38), "olive_poly": (88, 94, 64), "tan_poly": (168, 144, 104), "rubber": (28, 28, 30),
    "rail": (40, 42, 46), "wood": (128, 82, 46), "brass": (200, 160, 70), "suppressor": (40, 41, 45),
    "white_poly": (222, 224, 228), "graphite": (52, 54, 60), "ruby_metal": (168, 30, 52), "ruby": (205, 24, 60),
    "olive_paint": (82, 92, 58), "warning": (230, 186, 40), "battery": (70, 74, 60), "shell_red": (170, 34, 34),
    "rocket_head": (96, 102, 70), "seeker": (40, 30, 30), "lens": (60, 110, 160), "lens_clear": (40, 60, 80),
    "lens_thermal": (90, 40, 110),
}
GLOW = {"flash": (255, 230, 170), "glow_red": (255, 40, 40), "glow_green": (80, 255, 120), "glow_white": (255, 250, 230), "glow_ruby": (255, 50, 100),
        "glow_ruby_dim": (200, 30, 80), "seeker_glow": (255, 80, 60)}
METALS = {"black_metal", "steel", "steel_bright", "barrel", "rail", "suppressor", "graphite", "ruby_metal", "olive_paint", "rocket_head"}


def face_rects(b):
    u, v = b.uv
    w, h, d = dims(b)
    return {
        "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
        "side_a": (u, v + d, d, h), "back": (u + d, v + d, w, h),
        "side_b": (u + d + w, v + d, d, h), "front": (u + 2 * d + w, v + d, w, h),
    }


def paint(img, glow, root, seed):
    rnd = random.Random(seed)
    for b in all_boxes(root):
        for face, (fx, fy, fw, fh) in face_rects(b).items():
            for py in range(fh):
                for px in range(fw):
                    c, g = pixel(b, face, px, py, fw, fh, rnd)
                    if g == "clear":
                        continue                                  # see-through glass (cutout discards it)
                    if g != "only":
                        img[fy + py][fx + px] = clamp(c) + (255,)
                    if g:
                        glow[fy + py][fx + px] = clamp(c) + (255,)


def pixel(b, face, px, py, fw, fh, rnd):
    """One texel. Side faces (side_a/side_b) run along the gun's length in px; top/bottom run along it in py."""
    mat, fl = b.mat, b.flags
    n = rnd.uniform(-3.5, 3.5)
    if mat == "flash":
        # Muzzle flash: white-hot center fading to orange, drawn only in the glow pass.
        r = math.hypot(px + 0.5 - fw / 2, py + 0.5 - fh / 2) / max(1.0, max(fw, fh) / 2)
        if face in ("side_a", "side_b", "top", "bottom"):
            r = max(r, (px / max(1, fw - 1)) if face in ("side_a", "side_b") else (py / max(1, fh - 1)))
        c = mix((255, 250, 225), (255, 120, 20), min(1.0, r * 1.2))
        c = mul(c, max(0.0, 1.0 - r) * 1.3)
        return c, "only"
    if mat in GLOW:
        c = GLOW[mat]
        edge = px == 0 or py == 0 or px == fw - 1 or py == fh - 1
        if edge and fw > 2 and fh > 2:
            c = mul(c, 0.75)
        return c, True
    base = MATERIALS.get(mat, (255, 0, 255))
    along = px if face in ("side_a", "side_b") else py   # texel index along the gun's length (z)
    across = py if face in ("side_a", "side_b", "back", "front") else px
    length = fw if face in ("side_a", "side_b") else fh
    c = base
    # Lighting baked in, like a studio key light from above-front.
    shade = {"top": 1.2, "bottom": 0.62, "side_a": 1.0, "side_b": 1.0, "back": 0.86, "front": 0.92}[face]
    if face in ("side_a", "side_b", "back", "front") and fh > 2:
        shade *= 1.08 - 0.16 * (py / (fh - 1))            # lighter toward the top of each side
    c = mul(c, shade)

    if mat in ("lens", "lens_clear") and b.size[2] < 0.3:
        # Thin sight windows are glass you look through: clear, with a faint glint stripe.
        if mat == "lens" and abs(px - py - fw * 0.15) < 0.6 and face in ("front", "back") and px < fw * 0.4:
            return (190, 220, 240), False
        return (0, 0, 0), "clear"
    if mat in ("lens", "lens_clear", "lens_thermal"):
        t = (px + py * 0.6) / max(1, fw + fh * 0.6)
        c = mix(mul(base, 1.5), mul(base, 0.6), t)
        if abs(px - py - fw * 0.25) < 1.0:
            c = mix(c, (230, 240, 255), 0.6)               # glint
        return c, False
    if mat == "seeker":
        r = math.hypot(px + 0.5 - fw / 2, py + 0.5 - fh / 2) / max(1, min(fw, fh) / 2)
        c = (180, 40, 40) if r < 0.4 else (60, 30, 34) if r < 0.85 else (30, 30, 32)
        return c, r < 0.4 and face == "front"
    if mat == "wood":
        g = math.sin(across * 1.7 + math.sin(along * 0.21 + across) * 1.4)
        c = mul(c, 1.0 + 0.1 * g)
        if (across * 7 + along // 9) % 11 == 0:
            c = mul(c, 0.82)                                 # grain lines
        if fl.get("grooves") and face in ("side_a", "side_b", "bottom") and along % 5 == 0:
            c = mul(c, 0.6)
        if fl.get("checker") and (px + py) % 2 == 0:
            c = mul(c, 0.8)
    if mat == "ruby":
        k = ((px * 3 + py * 5) % 7) / 7.0
        c = mix((255, 90, 130), (120, 0, 30), k)
        return c, False
    if mat == "warning":
        if ((px + py) // 3) % 2 == 0:
            c = (30, 30, 30)
        return c, False
    if mat == "shell_red" and across >= (fh if face in ("side_a", "side_b") else fw) - 3:
        c = MATERIALS["brass"]

    # Surface treatments.
    if fl.get("stipple") or mat == "rubber":
        if rnd.random() < 0.35:
            c = mul(c, 1.25 if rnd.random() < 0.5 else 0.78)
    if fl.get("knurl") and (px + py) % 2 == 0:
        c = mul(c, 0.7)
    if mat == "rail" and face in ("top", "side_a", "side_b"):
        if along % 4 in (0, 1):
            c = mul(c, 0.55)                                 # picatinny slots
    if fl.get("ribs") and face in ("side_a", "side_b") and along % 3 == 0:
        c = mul(c, 0.8)
    if fl.get("vents") and face in ("side_a", "side_b") and fh >= 8:
        if (along % 10) in range(2, 8) and fh * 0.35 <= py <= fh * 0.6:
            c = (14, 14, 16)                                 # M-LOK slots
    if fl.get("serrations") and face in ("side_a", "side_b") and along < 8 and along % 2 == 0 and py < fh - 2:
        c = mul(c, 0.6)
    if fl.get("panels") and face in ("side_a", "side_b", "top") and (along % 12 == 0 or across == 2):
        c = mul(c, 0.82)                                     # panel seams
    if fl.get("ports") and face in ("side_a", "side_b", "top") and along % 3 == 1 and 1 <= across:
        c = (12, 12, 12)
    if fl.get("fluted") and face in ("side_a", "side_b", "top") and across % 2 == 0 and fw > 20:
        c = mul(c, 0.78)
    if fl.get("aperture") and face in ("back", "front"):
        if math.hypot(px + 0.5 - fw / 2, py + 0.5 - fh / 2) < min(fw, fh) * 0.3:
            c = (8, 8, 8)
    if fl.get("bore") and face in ("front", "back"):
        r = math.hypot(px + 0.5 - fw / 2, py + 0.5 - fh / 2) / max(1, min(fw, fh) / 2)
        c = (6, 6, 6) if r < 0.7 else mul(c, 0.8)
    stripes = fl.get("stripes_at")
    if stripes and face in ("side_a", "side_b", "top"):
        for z in stripes:
            zt = int((z - b.frm[2]) * S)
            if zt <= along < zt + 3:
                c = (200, 170, 50)
    if fl.get("mark") and face in ("side_a", "side_b") and fh >= 8 and length >= 24:
        # Engraved markings: a small ruby emblem and serial-number dashes near the back of the part.
        mx, my = 4, fh - 6
        if face == "side_b":
            mx = fw - 12
        rx, ry = px - mx, py - my
        if 0 <= rx < 8 and 0 <= ry < 3 and (rx % 3 != 2):
            c = mix(c, (190, 190, 190), 0.45)
        ex, ey = px - (mx + 2 if face == "side_a" else mx + 6), py - (my - 4)
        if abs(ex) + abs(ey) <= 1:
            c = (200, 30, 60)

    # Bevelled edges and worn corners.
    edge_top = (py == 0) if face in ("side_a", "side_b", "back", "front") else False
    edge = px == 0 or py == 0 or px == fw - 1 or py == fh - 1
    if edge_top and fh > 2:
        c = mul(c, 1.3)
    elif edge and fw > 2 and fh > 2:
        c = mul(c, 0.78)
    if mat in METALS and edge and rnd.random() < 0.18:
        c = mix(c, (150, 152, 156), 0.45)                    # worn edges show bare metal
    return (c[0] + n, c[1] + n, c[2] + n), False


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


def java_part(part, parent_var, out, depth, parent_pivot):
    var = "p" + str(depth) + "_" + part.name
    if part.pivot is None:
        part.pivot = parent_pivot
    px, py, pz = part.pivot
    rx, ry, rz = (math.radians(a) for a in part.rot)
    cubes = "CubeListBuilder.create()"
    for b in part.boxes:
        (x0, y0, z0), _ = b.frm, b.size
        w, h, d = dims(b)
        # Box corners are in gun space; make them relative to this part's pivot, scale by S,
        # and flip to model space (x and y negated; the renderer flips back).
        if part.rel:
            lx, ly, lz = x0 * S, y0 * S, z0 * S
        else:
            lx, ly, lz = (x0 - px) * S, (y0 - py) * S, (z0 - pz) * S
        mx, my, mz = -(lx + w), -(ly + h), lz
        cubes += f".texOffs({b.uv[0]}, {b.uv[1]}).addBox({f(mx)}, {f(my)}, {f(mz)}, {w}.0F, {h}.0F, {d}.0F)"
    ox, oy, oz = (px - parent_pivot[0]) * S, (py - parent_pivot[1]) * S, (pz - parent_pivot[2]) * S
    pose = f"PartPose.offsetAndRotation({f(-ox)}, {f(-oy)}, {f(oz)}, {f(-rx)}, {f(-ry)}, {f(rz)})"
    out.append(f"\t\tPartDefinition {var} = {parent_var}.addOrReplaceChild(\"{part.name}\", {cubes}, {pose});")
    for c in part.children:
        java_part(c, var, out, depth + 1, part.pivot)


def camel(name):
    return "".join(w.capitalize() if j else w for j, w in enumerate(name.split("_")))


def main(root_dir):
    lines = ["package dev.rubymod.client.model;", "",
             "import java.util.Map;", "",
             "import net.minecraft.client.model.geom.PartPose;",
             "import net.minecraft.client.model.geom.builders.CubeListBuilder;",
             "import net.minecraft.client.model.geom.builders.LayerDefinition;",
             "import net.minecraft.client.model.geom.builders.MeshDefinition;",
             "import net.minecraft.client.model.geom.builders.PartDefinition;", "",
             "/** Generated by tools/gun_models.py - edit the designs there, not here. Units: 1/" + str(S) + " pixel. */",
             "public final class GunModels {",
             "\tprivate GunModels() {", "\t}", "",
             "\t/** Model units per pixel (the renderer scales by 1/16/UNITS per block). */",
             f"\tpublic static final float UNITS = {S}.0F;", "",
             "\t/** Anchor points per gun, in gun pixels (x left, y up, z forward; origin at the top of the grip). */",
             "\tpublic static final Map<String, Map<String, float[]>> ANCHORS = Map.of("]
    anchor_lines = []
    bodies = []
    for i, (name, design) in enumerate(GUNS.items()):
        gun = design()
        add_extras(name, gun)
        gun.anchors.update(bounds(gun.root))
        tex = 256
        while not pack(gun.root, tex):
            tex *= 2
        img = [[(0, 0, 0, 0)] * tex for _ in range(tex)]
        glow = [[(0, 0, 0, 0)] * tex for _ in range(tex)]
        paint(img, glow, gun.root, 500 + i)
        out_dir = os.path.join(root_dir, "src/main/resources/assets/rubymod/textures/entity/gun")
        write_png(os.path.join(out_dir, name + ".png"), img)
        write_png(os.path.join(out_dir, name + "_glow.png"), glow)
        anchor_lines.append(f"\t\t\t\"{name}\", Map.ofEntries({', '.join('Map.entry(' + e + ')' for e in _pairs(gun.anchors))})")
        bodies.append(f"\tpublic static LayerDefinition {camel(name)}() {{")
        bodies.append("\t\tMeshDefinition mesh = new MeshDefinition();")
        bodies.append("\t\tPartDefinition root = mesh.getRoot();")
        out = []
        for c in gun.root.children:
            java_part(c, "root", out, 1, (0, 0, 0))
        bodies += out
        bodies.append(f"\t\treturn LayerDefinition.create(mesh, {tex}, {tex});")
        bodies.append("\t}")
        bodies.append("")
        print(f"  {name}: {sum(1 for _ in all_boxes(gun.root))} boxes, {tex}x{tex} texture")
    write_png(os.path.join(root_dir, "src/main/resources/assets/rubymod/textures/misc/scope.png"), scope_texture(512))
    lines.append(",\n".join(anchor_lines) + ");")
    lines.append("")
    lines += bodies
    lines.append("}")
    path = os.path.join(root_dir, "src/client/java/dev/rubymod/client/model/GunModels.java")
    with open(path, "w", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    print("gun models written")


CASINGS = {"arr_rifle": "brass", "service_pistol": "brass_short", "combat_shotgun": "shell", "longshot_sniper": "brass_long"}


def add_extras(name, gun):
    """Every gun gets a muzzle flash (glow pass only) and, if it ejects anything, a spent casing part."""
    mx, my, mz = gun.anchors["muzzle"]
    big = 1.6 if name in ("rocket_launcher", "skyhammer", "lancer") else 1.0
    gun.root.children.append(R("flash", pivot=(mx, my, mz), boxes=[
        B(-0.05, -1.7 * big, 0.0, 0.05, 1.7 * big, 3.6 * big, "flash"),
        B(-1.7 * big, -0.05, 0.0, 1.7 * big, 0.05, 3.6 * big, "flash"),
        B(-0.7 * big, -0.7 * big, 0.0, 0.7 * big, 0.7 * big, 1.4 * big, "flash"),
    ]))
    kind = CASINGS.get(name)
    if kind == "shell":
        boxes = [B(-0.3, -0.3, -1.2, 0.3, 0.3, 0.4, "shell_red"), B(-0.32, -0.32, 0.4, 0.32, 0.32, 1.0, "brass")]
    elif kind:
        half = {"brass": 0.7, "brass_short": 0.45, "brass_long": 1.0}[kind]
        boxes = [B(-0.2, -0.2, -half, 0.2, 0.2, half, "brass"), B(-0.12, -0.12, half, 0.12, 0.12, half + 0.25, "brass")]
    else:
        return
    gun.root.children.append(R("casing", pivot=(0, 0, 0), boxes=boxes))


def bounds(root):
    """Axis-aligned bounds of the gun itself (no attachments, flash or casing), in gun pixels."""
    lo, hi = [1e9] * 3, [-1e9] * 3

    def walk(part, pivot):
        piv = part.pivot if part.pivot is not None else pivot
        if part.name.startswith("att_") or part.name in ("flash", "casing", "mag_ext"):
            return
        for b in part.boxes:
            o = piv if part.rel else (0, 0, 0)
            for a in range(3):
                lo[a] = min(lo[a], b.frm[a] + o[a])
                hi[a] = max(hi[a], b.frm[a] + b.size[a] + o[a])
        for c in part.children:
            walk(c, piv)

    walk(root, (0, 0, 0))
    return {"bounds_min": tuple(lo), "bounds_max": tuple(hi)}


def scope_texture(n):
    """The sniper scope: black outside the lens, a soft inner shadow, mil-dot crosshair with thick posts, a lit red dot."""
    img = [[(0, 0, 0, 0)] * n for _ in range(n)]
    c = n / 2
    for y in range(n):
        for x in range(n):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            r = math.hypot(dx, dy) / c
            if r >= 0.97:
                img[y][x] = (0, 0, 0, 255)
                continue
            a = 0
            if r > 0.62:
                a = int(min(255, ((r - 0.62) / 0.35) ** 2.2 * 255))
            col = (0, 0, 0, a)
            if 0.9 < r < 0.97:
                col = (6, 10, 14, max(a, 230))
            ax, ay = abs(dx), abs(dy)
            thin = 0.9
            post = 3.2
            if (ay < thin and ax > n * 0.02) or (ax < thin and ay > n * 0.02):
                col = (0, 0, 0, 255)
            if (ay < post and ax > n * 0.22) or (ax < post and ay > n * 0.22):
                col = (0, 0, 0, 255)
            for k in range(1, 5):                       # mil-dots
                d = k * n * 0.045
                if math.hypot(ax - d, ay) < 2.2 or math.hypot(ax, ay - d) < 2.2:
                    col = (0, 0, 0, 255)
            if math.hypot(dx, dy) < 2.0:
                col = (255, 40, 40, 255)
            img[y][x] = col
    return img


def _pairs(anchors):
    for k, (x, y, z) in anchors.items():
        yield f"\"{k}\", new float[] {{{f(x)}, {f(y)}, {f(z)}}}"


if __name__ == "__main__":
    main(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
