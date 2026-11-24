"""Help films: turn the frames the "clips" client test filmed into the help menu's animations (How It Works).

Usage:
    CLIENT_SIZE=960x540 bash tools/client-test.sh clips      (films run/screenshots/autotest-clip-<id>-NN.png and clips-index.txt)
    RUBYMOD_CLIPS=jeep,Guns ...                               (the same, for just those films or sections)
    python tools/make_clips.py                                 (from the project root)

Each film becomes one sprite sheet, src/main/resources/assets/rubymod/textures/gui/clips/<id>.png: its frames in rows
of COLS, each FRAME_W x FRAME_H, in a 256-colour palette to keep the mod small. clips.txt beside them lists
"id frames sheet section" per item, in the Armory's order; an item that shares another's film (ammo has its gun's)
names that film's sheet. Films not filmed this time keep what they had.
"""
import glob
import os
import re

from PIL import Image

SHOTS = "run/screenshots"
OUT = "src/main/resources/assets/rubymod/textures/gui/clips"
FRAME_W, FRAME_H = 320, 180
COLS = 8


def pack(clip_id, frames):
    rows = (len(frames) + COLS - 1) // COLS
    sheet = Image.new("RGB", (FRAME_W * COLS, FRAME_H * rows))
    for i, f in enumerate(frames):
        im = Image.open(f).convert("RGB")
        w, h = im.size
        want = w * 9 // 16
        if want < h:
            top = (h - want) // 2
            im = im.crop((0, top, w, top + want))
        sheet.paste(im.resize((FRAME_W, FRAME_H), Image.LANCZOS), ((i % COLS) * FRAME_W, (i // COLS) * FRAME_H))
    sheet = sheet.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    path = os.path.join(OUT, clip_id + ".png")
    sheet.save(path, optimize=True)
    return os.path.getsize(path)


def main():
    os.makedirs(OUT, exist_ok=True)
    # What was there before (id -> frames, sheet, section), in order.
    old = {}
    listing = os.path.join(OUT, "clips.txt")
    if os.path.exists(listing):
        for line in open(listing, encoding="utf-8"):
            f = line.split()
            if len(f) >= 4:
                old[f[0]] = (int(f[1]), f[2], " ".join(f[3:]))
    frames = {}
    for f in glob.glob(os.path.join(SHOTS, "autotest-clip-*-*.png")):
        m = re.match(r"autotest-clip-(.+)-(\d+)\.png$", os.path.basename(f))
        if m:
            frames.setdefault(m.group(1), []).append((int(m.group(2)), f))
    index = os.path.join(SHOTS, "clips-index.txt")
    lines = [l.split() for l in open(index, encoding="utf-8")] if os.path.exists(index) else []
    new = {}
    order = []
    total = 0
    for f in lines:
        if f and f[0] == "film" and f[1] in frames:
            shots = [x for _, x in sorted(frames[f[1]])]
            size = pack(f[1], shots)
            total += size
            new[f[1]] = (len(shots), f[1], " ".join(f[2:]))
            order.append(f[1])
            print(f"{f[1]}: {len(shots)} frames, {size // 1024} KB")
    for f in lines:
        if f and f[0] == "alias":
            target = new.get(f[2]) or old.get(f[2])
            if target:
                new[f[1]] = (target[0], target[1], " ".join(f[3:]))
                order.append(f[1])
    # Keep the ones not filmed this time, where they were.
    merged = []
    for k in old:
        if k not in new:
            merged.append((k, old[k]))
    final = [(k, new[k]) for k in order] + merged if not old else []
    if old:
        seen = set()
        for k in list(old) + order:
            if k in seen:
                continue
            seen.add(k)
            final.append((k, new.get(k, old.get(k))))
    with open(listing, "w", newline="\n", encoding="utf-8") as out:
        for k, (n, sheet, section) in final:
            out.write(f"{k} {n} {sheet} {section}\n")
    print(f"{len(final)} items, {len(new)} updated, new sheets {total // 1024} KB")


if __name__ == "__main__":
    main()
