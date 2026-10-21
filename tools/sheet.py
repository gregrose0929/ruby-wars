"""Combine screenshots into one contact sheet: python tools/sheet.py OUT.png PATTERN [columns] [width]"""
import glob
import sys

from PIL import Image, ImageDraw

out, pattern = sys.argv[1], sys.argv[2]
cols = int(sys.argv[3]) if len(sys.argv) > 3 else 3
width = int(sys.argv[4]) if len(sys.argv) > 4 else 420
files = sorted(glob.glob(pattern))
thumbs = []
for f in files:
    im = Image.open(f).convert("RGB")
    h = int(im.height * width / im.width)
    thumbs.append((f.split("autotest-")[-1].replace(".png", ""), im.resize((width, h))))
rows = (len(thumbs) + cols - 1) // cols
h = thumbs[0][1].height + 16
sheet = Image.new("RGB", (cols * width, rows * h), (30, 30, 30))
d = ImageDraw.Draw(sheet)
for i, (name, im) in enumerate(thumbs):
    x, y = (i % cols) * width, (i // cols) * h
    sheet.paste(im, (x, y + 16))
    d.text((x + 4, y + 2), name, fill=(255, 255, 0))
sheet.save(out)
print(out, len(thumbs))
