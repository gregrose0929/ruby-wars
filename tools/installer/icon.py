"""Draws the installer's icon (a cut ruby) at the sizes Windows uses: tools/installer/ruby.ico."""
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))


def gem(size):
    s = size * 4  # drawn big, then shrunk, for smooth edges
    img = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = s * 0.08
    w = s - 2 * m
    top = m + w * 0.12
    belt = m + w * 0.40
    d.polygon([(m + w * 0.2, top), (m + w * 0.8, top), (m + w, belt), (m, belt)], fill=(240, 80, 104))
    d.polygon([(m, belt), (m + w, belt), (m + w * 0.5, m + w * 1.0)], fill=(214, 32, 62))
    d.polygon([(m + w * 0.5, belt), (m + w, belt), (m + w * 0.5, m + w * 1.0)], fill=(150, 16, 40))
    d.polygon([(m + w * 0.3, top + w * 0.05), (m + w * 0.5, top + w * 0.05), (m + w * 0.42, belt - w * 0.05)], fill=(255, 176, 190))
    return img.resize((size, size), Image.LANCZOS)


big = gem(256)
big.save(os.path.join(HERE, 'ruby.ico'), sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print('icon written')
