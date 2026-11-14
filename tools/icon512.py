#!/usr/bin/env python3
"""Exports the launcher icon (res/drawable/ic_launcher_foreground.xml on #2E7D6B) as a 512 px PNG, drawn with the same
shapes: python -I tools/icon512.py OUT.png"""
import sys

from PIL import Image, ImageDraw

S = 512 / 108.0
im = Image.new("RGBA", (512, 512), (0x2E, 0x7D, 0x6B, 255))
d = ImageDraw.Draw(im)
white = (255, 255, 255, 235)
for path in ([(28, 28), (42, 28), (42, 33), (33, 33), (33, 42), (28, 42)], [(80, 28), (66, 28), (66, 33), (75, 33), (75, 42), (80, 42)],
             [(28, 80), (42, 80), (42, 75), (33, 75), (33, 66), (28, 66)], [(80, 80), (66, 80), (66, 75), (75, 75), (75, 66), (80, 66)]):
    d.polygon([(x * S, y * S) for x, y in path], fill=white)
photo = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
p = ImageDraw.Draw(photo)
p.rectangle([39 * S, 43 * S, 69 * S, 65 * S], fill=(0xFF, 0xF4, 0xE0, 255))
p.polygon([(x * S, y * S) for x, y in [(42, 61), (50, 52), (56, 58), (60, 54), (67, 61)]], fill=(0x9A, 0xC2, 0xB6, 255))
photo = photo.rotate(8, center=(54 * S, 54 * S), resample=Image.BICUBIC)
im.alpha_composite(photo)
d = ImageDraw.Draw(im)
d.ellipse([(68 - 4.5) * S, (42 - 4.5) * S, (68 + 4.5) * S, (42 + 4.5) * S], fill=(0xFF, 0xB7, 0x4D, 255))
im.save(sys.argv[1])
