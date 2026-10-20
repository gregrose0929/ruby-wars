"""Generate 16x16 pixel-art textures for Ruby Mod (pure Python, no deps)."""
import struct, sys, zlib, os

def write_png(path, pixels):
    h, w = len(pixels), len(pixels[0])
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)

PAL = {
    ".": (0, 0, 0, 0),
    "o": (58, 6, 18, 255),     # outline
    "d": (120, 10, 30, 255),   # dark
    "m": (180, 20, 45, 255),   # mid
    "r": (225, 40, 65, 255),   # bright
    "l": (255, 110, 130, 255), # light
    "w": (255, 215, 225, 255), # highlight
}

RUBY = [
    "................",
    "................",
    "....oooooooo....",
    "...olwwlrrrmo...",
    "..olwlrrrrmmdo..",
    ".olllrrrrrmmmdo.",
    ".oooooooooooooo.",
    "..olrrrrrrmmdo..",
    "...olrrrrmmdo...",
    "....olrrrmdo....",
    ".....olrmmdo....",
    "......olmdo.....",
    ".......omo......",
    "........o.......",
    "................",
    "................",
]

BLOCK = [
    "wwwwwwwwwwwwwwwl",
    "wllllllllllllllm",
    "wlrrrrrrrrrrrrmd",
    "wlrlwlrrrrrrrmmd",
    "wlrwlrrrrrrrmmmd",
    "wlrlrrrrrrrrmmmd",
    "wlrrrrrrrrrrmmmd",
    "wlrrrrrrrrrmmmmd",
    "wlrrrrrrrrmmmmmd",
    "wlrrrrrrrmmmmmmd",
    "wlrrrrrrmmmmmdmd",
    "wlrrrrrmmmmmdmmd",
    "wlrrrrmmmmmmmmmd",
    "wlrmmmmmmmmmmmmd",
    "wmddddddddddddmd",
    "lddddddddddddddo",
]

root = sys.argv[1]
write_png(os.path.join(root, "textures/item/ruby.png"), [[PAL[c] for c in row] for row in RUBY])
write_png(os.path.join(root, "textures/block/ruby_block.png"), [[PAL[c] for c in row] for row in BLOCK])
# Upscaled mod icon (128x128) from the gem sprite
icon = [[PAL[RUBY[y // 8][x // 8]] for x in range(128)] for y in range(128)]
write_png(os.path.join(root, "icon.png"), icon)
print("textures written")
