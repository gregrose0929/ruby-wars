"""3D models for the hub's theme park: coaster cars, Ferris wheel gondolas, carousel horses, bumper cars, gallery
ducks, mini-golf putter, ball and flag. Boxes in 16ths of a block, front = -Z (north), drawn with a bright swatch
atlas (textures/item/park_parts.png). The server shows them through item displays (Props.model / ITEM_MODEL).

Usage:  python tools/park_models.py      (run from the project root; safe to re-run)
"""
import json
import os
import random

from textures import write_png

NS = "rubymod"
ASSETS = f"src/main/resources/assets/{NS}"

BLACK, GRAY, STEEL, WHITE, RED, RED_DARK, YELLOW, GOLD, BLUE, SKY, GREEN, LIME, PINK, PURPLE, ORANGE, WOOD = range(16)
COLORS = [
    (34, 34, 40), (92, 96, 108), (176, 182, 192), (242, 242, 242),
    (226, 40, 52), (150, 22, 34), (255, 212, 44), (232, 168, 36),
    (44, 112, 232), (116, 204, 255), (52, 182, 72), (146, 232, 64),
    (255, 124, 192), (152, 72, 224), (255, 142, 32), (150, 100, 60),
]


def atlas():
    rnd = random.Random(7)
    img = [[(0, 0, 0, 255)] * 16 for _ in range(16)]
    for i, (r, g, b) in enumerate(COLORS):
        ox, oy = (i % 4) * 4, (i // 4) * 4
        for y in range(4):
            for x in range(4):
                n = rnd.randint(-5, 5) + (8 if y == 0 else (-10 if y == 3 else 0))
                img[oy + y][ox + x] = (max(0, min(255, r + n)), max(0, min(255, g + n)), max(0, min(255, b + n)), 255)
    return img


def uv(s):
    u, v = (s % 4) * 4, (s // 4) * 4
    return [u + 0.5, v + 0.5, u + 3.5, v + 3.5]


def b(frm, to, s, rot=None):
    e = {"from": frm, "to": to, "faces": {f: {"uv": uv(s), "texture": "#parts"} for f in ("north", "south", "east", "west", "up", "down")}}
    if rot:
        e["rotation"] = rot
    return e


def coaster_car(body, trim):
    return [
        b([3, 0, 1], [13, 2, 15], BLACK),                               # chassis
        *[b([x, 0, z], [x + 1.5, 2.5, z + 3], GRAY) for x in (1.5, 13) for z in (2, 11)],   # wheels
        b([2, 2, 1], [14, 4, 15], body),                                # floor
        b([2, 4, 1], [3, 8, 15], body),                                 # sides
        b([13, 4, 1], [14, 8, 15], body),
        b([2, 4, 15], [14, 9, 16], body),                               # back
        b([2, 2, -1.5], [14, 7, 1], body),                              # rounded nose
        b([3, 7, -1], [13, 8, 1], trim),
        b([4, 4, -1.7], [6, 5.5, -1.5], YELLOW),                        # headlights
        b([10, 4, -1.7], [12, 5.5, -1.5], YELLOW),
        b([2.2, 6, 1], [2.4, 7, 15], trim),                             # side stripes
        b([13.6, 6, 1], [13.8, 7, 15], trim),
        b([3, 4, 3], [13, 6, 7], BLACK),                                # front seat
        b([3, 6, 6], [13, 10, 7.5], BLACK),
        b([3, 4, 9.5], [13, 6, 13.5], BLACK),                           # back seat
        b([3, 6, 12.5], [13, 11, 14], BLACK),
        b([3, 8.5, 2.5], [13, 9.2, 3.2], STEEL),                        # lap bars
        b([3, 8.5, 9], [13, 9.2, 9.7], STEEL),
    ]


def gondola(color):
    return [
        b([7.4, 15, 7.4], [8.6, 20, 8.6], STEEL),                       # hanger
        b([1.5, 14, 1.5], [14.5, 15, 14.5], color),                     # roof
        b([4, 15, 4], [12, 16, 12], WHITE),
        b([2, 0, 2], [14, 1, 14], GRAY),                                # floor
        b([2, 1, 2], [14, 6, 3], color), b([2, 1, 13], [14, 6, 14], color),
        b([2, 1, 3], [3, 6, 13], color), b([13, 1, 3], [14, 6, 13], color),
        b([2, 5.5, 2], [14, 6.3, 14], WHITE),                           # rim
        *[b([x, 6, z], [x + 1, 14, z + 1], WHITE) for x in (2, 13) for z in (2, 13)],   # posts
        b([3, 1, 6], [13, 3.5, 10], WOOD),                              # bench
    ]


def carousel_horse(body, saddle):
    return [
        b([7.4, -8, 7.4], [8.6, 26, 8.6], GOLD),                        # pole
        b([5, 7, 3], [11, 12, 13], body),                               # body
        b([5.5, 11, 0.5], [10.5, 16, 4], body),                         # neck
        b([5.5, 14, -3], [10.5, 18, 1.5], body),                        # head
        b([6, 14.5, -4], [10, 16.5, -3], body),                         # muzzle
        b([5.4, 16, -3.1], [6, 17, -2.2], BLACK), b([10, 16, -3.1], [10.6, 17, -2.2], BLACK),   # eyes
        b([7, 13, 2], [9, 19, 5], saddle),                              # mane
        b([6, 17.5, -0.5], [7, 19.5, 0.5], body), b([9, 17.5, -0.5], [10, 19.5, 0.5], body),     # ears
        b([4.8, 11.8, 5.5], [11.2, 12.6, 10.5], saddle),                # saddle
        b([4.6, 9, 7.5], [5, 12, 8.5], GOLD), b([11, 9, 7.5], [11.4, 12, 8.5], GOLD),            # stirrups
        b([7, 9, 13], [9, 12, 16], saddle),                             # tail
        b([5.2, 1, 3.5], [6.8, 7, 5], body), b([9.2, 1, 3.5], [10.8, 7, 5], body),             # legs
        b([5.2, 1, 11], [6.8, 7, 12.5], body), b([9.2, 1, 11], [10.8, 7, 12.5], body),
        b([5.2, 0, 3.5], [6.8, 1, 5], GOLD), b([9.2, 0, 3.5], [10.8, 1, 5], GOLD),             # hooves
        b([5.2, 0, 11], [6.8, 1, 12.5], GOLD), b([9.2, 0, 11], [10.8, 1, 12.5], GOLD),
    ]


def bumper_car(color):
    return [
        b([0, 0, 0], [16, 2.5, 16], BLACK),                             # rubber bumper
        b([1, 2.5, 1], [15, 6, 15], color),                             # body
        b([1.5, 6, 0.5], [14.5, 7, 4], color),                          # bonnet
        b([3, 6, 6], [13, 7, 10.5], BLACK),                             # seat
        b([3, 6, 10.5], [13, 11, 12.5], BLACK),                         # seat back
        b([1, 6, 12.5], [15, 8, 15], color),                            # rear
        b([7.5, 6, 3.5], [8.5, 10, 4.5], STEEL),                        # steering column
        b([5.5, 9.6, 2.8], [10.5, 10.4, 5.2], BLACK),                   # wheel
        b([7.7, 8, 13.5], [8.3, 26, 14.1], STEEL),                      # trolley pole
        b([7.2, 26, 13], [8.8, 27, 14.6], YELLOW),
        b([3, 3.5, -0.2], [5, 5, 1], YELLOW), b([11, 3.5, -0.2], [13, 5, 1], YELLOW),   # headlights
        b([0.8, 4, 5], [1.1, 5, 11], WHITE), b([14.9, 4, 5], [15.2, 5, 11], WHITE),       # stripes
    ]


DUCK = [
    b([4, 2, 4], [12, 8, 14], YELLOW),                                  # body
    b([5, 7, 1.5], [11, 12, 6.5], YELLOW),                              # head
    b([6.5, 8, -0.8], [9.5, 9.8, 1.5], ORANGE),                         # beak
    b([4.8, 10, 2.5], [5.2, 11, 3.5], BLACK), b([10.8, 10, 2.5], [11.2, 11, 3.5], BLACK),   # eyes
    b([5, 5, 13], [11, 9, 16], YELLOW),                                 # tail
    b([3.6, 4, 7], [4.2, 7, 12], GOLD), b([11.8, 4, 7], [12.4, 7, 12], GOLD),               # wings
    b([7, 0, 3], [9, 2, 15], RED),                                      # target band
    b([7.5, -8, 8], [8.5, 2, 9], WOOD),                                 # stick
]

GOLF_BALL = [
    b([5, 5, 5], [11, 11, 11], WHITE),
    b([4.4, 6, 6], [11.6, 10, 10], WHITE),
    b([6, 4.4, 6], [10, 11.6, 10], WHITE),
    b([6, 6, 4.4], [10, 10, 11.6], WHITE),
]

PUTTER = [
    b([7.4, 2, 7.4], [8.6, 16, 8.6], STEEL),                            # shaft
    b([7.1, 11, 7.1], [8.9, 16, 8.9], BLACK),                           # grip
    b([4, 0, 6.6], [11, 2.5, 9.4], GOLD),                               # head
    b([4, 0, 6.4], [11, 2.5, 6.6], STEEL),                              # face
]

GOLF_FLAG = [
    b([7.6, 0, 7.6], [8.4, 30, 8.4], WHITE),
    b([8.4, 22, 7.8], [17, 29, 8.2], RED),
    b([7.4, 30, 7.4], [8.6, 31, 8.6], YELLOW),
]

ZIP_HANDLE = [
    b([1, 0, 7], [15, 1.5, 9], RED),                                    # handlebar
    b([1, 0, 7], [2, 12, 9], STEEL), b([14, 0, 7], [15, 12, 9], STEEL),  # straps
    b([1, 12, 6], [15, 14, 10], GRAY),                                  # trolley
    b([3, 14, 7], [5, 16, 9], BLACK), b([11, 14, 7], [13, 16, 9], BLACK),  # pulleys
]

GHOST = [
    b([3, 2, 4], [13, 14, 12], WHITE),                                  # sheet
    b([4, 14, 5], [12, 17, 11], WHITE),                                 # head
    b([5, 17, 6], [11, 18, 10], WHITE),
    b([2, 0, 4], [5, 3, 12], WHITE), b([7, -1, 4], [9, 2, 12], WHITE), b([11, 0, 4], [14, 3, 12], WHITE),  # wavy hem
    b([5, 13, 3.6], [7, 16, 4.1], BLACK), b([9, 13, 3.6], [11, 16, 4.1], BLACK),   # eyes
    b([6.5, 9, 3.6], [9.5, 12, 4.1], BLACK),                            # "oooo" mouth
    b([0, 8, 6], [3, 10, 9], WHITE), b([13, 8, 6], [16, 10, 9], WHITE),  # arms out
]

SKELETON = [
    b([5, 18, 5], [11, 24, 11], WHITE),                                 # skull
    b([6, 20, 4.6], [7.5, 22, 5.1], BLACK), b([8.5, 20, 4.6], [10, 22, 5.1], BLACK),   # eye sockets
    b([7, 18.5, 4.6], [9, 19.2, 5.1], BLACK),                           # teeth
    b([7.5, 6, 7.5], [8.5, 18, 8.5], STEEL),                            # spine
    *[b([5, y, 6], [11, y + 0.8, 10], WHITE) for y in (11, 13, 15)],    # ribs
    b([2, 16, 7.5], [5, 17, 8.5], WHITE), b([1, 16, 7.5], [2, 22, 8.5], WHITE),     # arms up
    b([11, 16, 7.5], [14, 17, 8.5], WHITE), b([14, 16, 7.5], [15, 22, 8.5], WHITE),
    b([5.5, 6, 7], [10.5, 7, 9], WHITE),                                # hips
    b([6, -4, 7.5], [7, 6, 8.5], WHITE), b([9, -4, 7.5], [10, 6, 8.5], WHITE),     # legs
]

SPIDER = [
    b([5, 3, 6], [11, 8, 14], BLACK),                                   # body
    b([5.5, 3.5, 2], [10.5, 7, 6], GRAY),                               # head
    b([6, 5.5, 1.8], [7, 6.5, 2.1], RED), b([9, 5.5, 1.8], [10, 6.5, 2.1], RED),   # eyes
    b([7, 4.5, 1.8], [7.6, 5.1, 2.1], RED), b([8.4, 4.5, 1.8], [9, 5.1, 2.1], RED),
    *[b([0, 4, z], [5, 5, z + 1], BLACK) for z in (4, 7, 10, 13)],      # legs
    *[b([11, 4, z], [16, 5, z + 1], BLACK) for z in (4, 7, 10, 13)],
    *[b([0, 0, z], [1, 4, z + 1], BLACK) for z in (4, 7, 10, 13)],
    *[b([15, 0, z], [16, 4, z + 1], BLACK) for z in (4, 7, 10, 13)],
    b([7.5, 8, 9.5], [8.5, 32, 10.5], WHITE),                           # its thread
]

BAT = [
    b([6, 5, 6], [10, 10, 10], BLACK),                                  # body
    b([6.5, 10, 6.5], [9.5, 13, 9.5], BLACK),                           # head
    b([6.5, 11, 6.3], [7.5, 12, 6.5], RED), b([8.5, 11, 6.3], [9.5, 12, 6.5], RED),
    b([0, 8, 7], [6, 9, 9], GRAY), b([10, 8, 7], [16, 9, 9], GRAY),     # wings
    b([0, 6, 7], [3, 8, 9], GRAY), b([13, 6, 7], [16, 8, 9], GRAY),
]

LOG_BOAT = [
    b([2, 0, -2], [14, 2, 18], WOOD),                                   # hull
    b([1, 2, -2], [3, 8, 18], WOOD), b([13, 2, -2], [15, 8, 18], WOOD),  # sides
    b([2, 2, -3], [14, 9, -1], WOOD), b([2, 2, 17], [14, 8, 19], WOOD),  # ends
    b([3, 8.5, -3.2], [13, 9, -2.8], ORANGE),                           # cut log rings
    b([0.8, 3, 2], [1, 7, 5], ORANGE), b([15, 3, 9], [15.2, 7, 12], ORANGE),   # bark knots
    b([3, 2, 2], [13, 5, 6], GRAY), b([3, 2, 10], [13, 5, 14], GRAY),    # seats
]

SWAN = [
    b([1, 0, 2], [15, 6, 20], WHITE),                                   # body (wraps the boat)
    b([3, 6, 14], [13, 9, 21], WHITE),                                  # tail feathers
    b([0, 2, 4], [1, 7, 17], WHITE), b([15, 2, 4], [16, 7, 17], WHITE),  # wings
    b([6.5, 5, 0], [9.5, 15, 3], WHITE),                                # neck
    b([6, 14, -2], [10, 17.5, 2], WHITE),                               # head
    b([7, 14.6, -5], [9, 16.2, -2], ORANGE),                            # beak
    b([5.8, 15.6, -1], [6.2, 16.6, 0], BLACK), b([9.8, 15.6, -1], [10.2, 16.6, 0], BLACK),   # eyes
    b([7, 17.5, -0.5], [9, 18.5, 1.5], PINK),                           # a little bow
]

def go_kart(color):
    return [
        b([2, 0, 0], [14, 1.5, 16], BLACK),                             # chassis
        *[b([x, 0, z], [x + 2.5, 3.5, z + 3.5], GRAY) for x in (-0.5, 14) for z in (0.5, 12)],   # fat tyres
        b([3, 1.5, -1], [13, 4, 4], color),                             # nose
        b([4, 1.5, 4], [12, 5, 9], color),                              # body
        b([3, 1.5, 12], [13, 3, 16], color),                            # back
        b([5, 3, 9], [11, 4, 13], BLACK),                               # seat
        b([5, 4, 12], [11, 9, 13.5], BLACK),                            # seat back
        b([7.5, 4, 4], [8.5, 7, 5], STEEL), b([5.5, 6.6, 3.8], [10.5, 7.4, 4.6], BLACK),   # wheel
        b([2, 6, 15], [14, 7, 17], color),                              # spoiler
        b([3, 3, 15], [4, 6, 16], STEEL), b([12, 3, 15], [13, 6, 16], STEEL),
        b([6, 4.2, -1.2], [10, 5.6, -0.8], WHITE),                      # number plate
    ]


def teacup(color, trim):
    return [
        b([2, 0, 2], [14, 2, 14], trim),                                # foot
        b([0, 2, 0], [16, 9, 16], color),                               # cup
        b([1, 9, 1], [15, 10, 15], trim),                               # rim
        b([2, 2, 2], [14, 9.5, 14], WHITE),                             # inside (seen from above)
        b([16, 3, 6], [19, 8, 10], color), b([17, 4, 7], [18, 7, 9], WHITE),   # handle
        b([6, 5, -0.2], [10, 7, 0], trim), b([6, 5, 16], [10, 7, 16.2], trim),  # dots
        b([7, 2, 7], [9, 6, 9], STEEL),                                 # wheel in the middle
    ]


BALLOON = [
    b([2, -16, 2], [14, -9, 14], WOOD),                                 # basket
    b([1.5, -10, 1.5], [14.5, -9, 14.5], ORANGE),                       # basket rim
    *[b([x, -9, z], [x + 0.5, 4, z + 0.5], GRAY) for x in (2.5, 13) for z in (2.5, 13)],   # ropes
    b([6, -6, 6], [10, -4, 10], ORANGE),                                # burner
    b([0, 4, 0], [16, 8, 16], RED),                                     # envelope, striped
    b([-4, 8, -4], [20, 14, 20], YELLOW),
    b([-6, 14, -6], [22, 22, 22], RED),
    b([-5, 22, -5], [21, 28, 21], YELLOW),
    b([-2, 28, -2], [18, 31, 18], RED),
    b([3, 31, 3], [13, 32, 13], YELLOW),
]

PIRATE_SHIP = [
    b([4, 0, -16], [12, 3, 32], WOOD),                                  # keel
    b([2, 3, -14], [14, 7, 30], WOOD),                                  # hull
    b([1, 7, -12], [15, 9, 28], RED_DARK),                              # rail
    b([4, 6, -13], [12, 8, 29], GRAY),                                  # deck (seen from above)
    b([3, 7, -16], [13, 13, -12], WOOD), b([3, 7, 28], [13, 13, 32], WOOD),   # raised ends
    b([7.5, 7, 7], [8.5, 32, 8], WOOD),                                 # mast
    b([3, 18, 7.2], [13, 30, 7.8], WHITE),                              # sail
    b([6, 21, 7.0], [10, 25, 7.1], BLACK), b([7, 22, 6.9], [9, 24, 7.0], WHITE),   # skull on the sail
    b([5, 31, 7.6], [11, 32, 8.4], BLACK),                              # flag
    *[b([1.8, 4, z], [2, 6, z + 2], YELLOW) for z in (-8, 0, 8, 16, 24)],   # portholes
    *[b([14, 4, z], [14.2, 6, z + 2], YELLOW) for z in (-8, 0, 8, 16, 24)],
]

DROP_SEATS = [
    b([0, 0, 0], [16, 3, 16], RED),                                     # the ring of seats round the tower
    b([1, 3, 1], [15, 4, 15], YELLOW),
    *[b([x, 3, z], [x + 3, 9, z + 1], BLACK) for x in (1, 6.5, 12) for z in (0, 15)],
    *[b([x, 3, z], [x + 1, 9, z + 3], BLACK) for x in (0, 15) for z in (1, 6.5, 12)],
]

BASKETBALL = [
    b([5, 5, 5], [11, 11, 11], ORANGE), b([4.4, 6, 6], [11.6, 10, 10], ORANGE), b([6, 4.4, 6], [10, 11.6, 10], ORANGE),
    b([6, 6, 4.4], [10, 10, 11.6], ORANGE),
    b([7.8, 4.3, 4.3], [8.2, 11.7, 11.7], BLACK), b([4.3, 7.8, 4.3], [11.7, 8.2, 11.7], BLACK),
]

TOSS_RING = [
    b([3, 7, 3], [13, 9, 5], PINK), b([3, 7, 11], [13, 9, 13], PINK),
    b([3, 7, 5], [5, 9, 11], PINK), b([11, 7, 5], [13, 9, 11], PINK),
]

MOLE = [
    b([4, 0, 4], [12, 10, 12], WOOD),                                   # body
    b([5, 10, 5], [11, 13, 11], WOOD),                                  # head
    b([6.5, 9, 3.6], [9.5, 11, 4.1], PINK),                             # nose
    b([5.5, 11, 4.6], [6.8, 12.3, 5.1], BLACK), b([9.2, 11, 4.6], [10.5, 12.3, 5.1], BLACK),   # eyes
    b([7, 7, 3.6], [9, 8, 4.1], WHITE),                                 # teeth
    b([3, 5, 6], [4, 7, 9], PINK), b([12, 5, 6], [13, 7, 9], PINK),     # paws
]

TEDDY = [
    b([4, 0, 5], [12, 8, 11], WOOD),                                    # body
    b([5, 8, 5.5], [11, 14, 10.5], WOOD),                               # head
    b([4, 13, 7], [6, 15, 9], WOOD), b([10, 13, 7], [12, 15, 9], WOOD), # ears
    b([7, 9.5, 4.8], [9, 11.5, 5.5], ORANGE),                           # muzzle
    b([6, 11.5, 5.2], [7, 12.5, 5.5], BLACK), b([9, 11.5, 5.2], [10, 12.5, 5.5], BLACK),
    b([2, 3, 6.5], [4, 7, 9.5], WOOD), b([12, 3, 6.5], [14, 7, 9.5], WOOD),   # arms
    b([6, 6.5, 4.8], [10, 7.5, 5.2], RED),                              # bow
]

MALLET = [
    b([7.4, 0, 7.4], [8.6, 12, 8.6], WOOD),                             # handle
    b([4, 11, 5], [12, 16, 11], RED),                                   # head
]

MODELS = {
    "coaster_car": coaster_car(RED, YELLOW),
    "coaster_car_blue": coaster_car(BLUE, WHITE),
    "gondola_red": gondola(RED), "gondola_blue": gondola(BLUE), "gondola_yellow": gondola(YELLOW), "gondola_green": gondola(GREEN),
    "horse_white": carousel_horse(WHITE, PINK), "horse_brown": carousel_horse(WOOD, BLUE),
    "horse_black": carousel_horse(BLACK, RED), "horse_gold": carousel_horse(GOLD, PURPLE),
    "bumper_red": bumper_car(RED), "bumper_blue": bumper_car(BLUE), "bumper_green": bumper_car(GREEN),
    "bumper_yellow": bumper_car(YELLOW), "bumper_pink": bumper_car(PINK), "bumper_purple": bumper_car(PURPLE),
    "ghost": GHOST, "skeleton": SKELETON, "spider": SPIDER, "bat": BAT, "log_boat": LOG_BOAT, "swan": SWAN,
    "ghost_car": coaster_car(PURPLE, LIME),
    "kart_red": go_kart(RED), "kart_blue": go_kart(BLUE), "kart_green": go_kart(GREEN), "kart_yellow": go_kart(YELLOW),
    "kart_pink": go_kart(PINK), "kart_purple": go_kart(PURPLE), "kart_orange": go_kart(ORANGE), "kart_white": go_kart(WHITE),
    "teacup_pink": teacup(PINK, WHITE), "teacup_blue": teacup(SKY, WHITE), "teacup_yellow": teacup(YELLOW, ORANGE),
    "teacup_green": teacup(LIME, WHITE), "teacup_purple": teacup(PURPLE, PINK), "teacup_red": teacup(RED, YELLOW),
    "balloon": BALLOON, "pirate_ship": PIRATE_SHIP, "drop_seats": DROP_SEATS, "basketball": BASKETBALL, "toss_ring": TOSS_RING,
    "mole": MOLE, "teddy": TEDDY,
    "duck": DUCK, "golf_ball": GOLF_BALL, "golf_flag": GOLF_FLAG, "zip_handle": ZIP_HANDLE,
}
HANDHELD = {"putter": PUTTER, "mallet": MALLET}


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as f:
        json.dump(obj, f, indent="\t")
        f.write("\n")


def main():
    write_png(f"{ASSETS}/textures/item/park_parts.png", atlas())
    tex = {"parts": f"{NS}:item/park_parts", "particle": f"{NS}:item/park_parts"}
    for name, elements in MODELS.items():
        write(f"{ASSETS}/models/item/park_{name}.json", {"textures": tex, "elements": elements})
        write(f"{ASSETS}/items/park_{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/park_{name}"}})
    for name, elements in HANDHELD.items():
        write(f"{ASSETS}/models/item/park_{name}.json", {"parent": "minecraft:item/handheld", "textures": tex, "elements": elements})
        write(f"{ASSETS}/items/park_{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/park_{name}"}})
    print(f"park models written: {len(MODELS) + len(HANDHELD)}")


if __name__ == "__main__":
    main()
