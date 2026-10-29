# SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generates the neutral Android TV banner (320x180 dp, written at xhdpi = 640x360 px).

Placeholder until the LaHo branding branch lands; rerun with a new icon to replace it.
Usage: python scripts/tv/gen_tv_banner.py app/src/main/res/drawable-xhdpi/laho_tv_banner.png
"""
import sys
from PIL import Image, ImageDraw, ImageFont

W, H = 640, 360
out = sys.argv[1]
img = Image.new("RGB", (W, H))
top, bottom = (24, 28, 40), (52, 40, 86)
for y in range(H):
    t = y / (H - 1)
    c = tuple(round(a + (b - a) * t) for a, b in zip(top, bottom))
    ImageDraw.Draw(img).line([(0, y), (W, y)], fill=c)
d = ImageDraw.Draw(img)

def font(names, size):
    for n in names:
        try:
            return ImageFont.truetype(n, size)
        except OSError:
            pass
    return ImageFont.load_default()

# Simple waveform glyph on the left
cx, cy = 150, H // 2
bars = [40, 90, 140, 70, 110, 50]
for i, h in enumerate(bars):
    x = cx - 75 + i * 28
    d.rounded_rectangle([x, cy - h // 2, x + 16, cy + h // 2], radius=8, fill=(167, 139, 250))

title = font(["C:/Windows/Fonts/segoeuib.ttf", "DejaVuSans-Bold.ttf"], 84)
sub = font(["C:/Windows/Fonts/segoeui.ttf", "DejaVuSans.ttf"], 34)
d.text((270, cy - 70), "LaHo", font=title, fill=(255, 255, 255))
d.text((274, cy + 30), "Music Player", font=sub, fill=(210, 210, 230))
img.save(out, optimize=True)
print("wrote", out, img.size)
