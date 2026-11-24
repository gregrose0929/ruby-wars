"""Help clips: turn the frames the "clips" client test filmed into the help menu's animations.

Usage:
    CLIENT_SIZE=960x540 bash tools/client-test.sh clips     (films run/screenshots/autotest-clip-<id>-NN.png)
    python tools/make_clips.py                                (from the project root)

Each clip becomes one sprite sheet, src/main/resources/assets/rubymod/textures/gui/clips/<id>.png: its frames in rows
of COLS, each FRAME_W x FRAME_H, in a 256-colour palette to keep the mod small. clips.txt beside them lists
"id frames" per line, which HelpClips reads.
"""
import glob
import os
import re

from PIL import Image

SHOTS = "run/screenshots"
OUT = "src/main/resources/assets/rubymod/textures/gui/clips"
FRAME_W, FRAME_H = 256, 144
COLS = 8


def main():
    os.makedirs(OUT, exist_ok=True)
    clips = {}
    for f in glob.glob(os.path.join(SHOTS, "autotest-clip-*-*.png")):
        m = re.match(r"autotest-clip-(.+)-(\d+)\.png$", os.path.basename(f))
        if m:
            clips.setdefault(m.group(1), []).append((int(m.group(2)), f))
    lines = []
    for clip_id in sorted(clips):
        frames = [f for _, f in sorted(clips[clip_id])]
        rows = (len(frames) + COLS - 1) // COLS
        sheet = Image.new("RGB", (FRAME_W * COLS, FRAME_H * rows))
        for i, f in enumerate(frames):
            im = Image.open(f).convert("RGB")
            # 16:9, the middle of it.
            w, h = im.size
            want = w * 9 // 16
            if want < h:
                top = (h - want) // 2
                im = im.crop((0, top, w, top + want))
            sheet.paste(im.resize((FRAME_W, FRAME_H), Image.LANCZOS), ((i % COLS) * FRAME_W, (i // COLS) * FRAME_H))
        sheet = sheet.quantize(colors=256, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
        path = os.path.join(OUT, clip_id + ".png")
        sheet.save(path, optimize=True)
        lines.append(f"{clip_id} {len(frames)}")
        print(f"{clip_id}: {len(frames)} frames, {os.path.getsize(path) // 1024} KB")
    with open(os.path.join(OUT, "clips.txt"), "w", newline="\n") as out:
        out.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
