#!/usr/bin/env python3
"""Frames the headset's native screenshots for the store.

A native capture is square (1440 x 1440), straight-lined, but tilted if the headset was not level and
with the screen small in the middle. This finds what is lit, measures the tilt from the screen's top
edge, levels it, and sizes it to fill 2560 x 1440 with a margin.

Usage: frame-native.py [--tilt DEGREES] NAME [NAME...]   (reads store/screenshots/raw/NAME.jpg, writes store/screenshots/NAME.png)
"""
import math
import os
import struct
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "store", "screenshots")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
FILL = 0.86


def pixels(path):
    bmp = path + ".bmp"
    subprocess.run(["sips", "-s", "format", "bmp", path, "--out", bmp], check=True, stdout=subprocess.DEVNULL)
    data = open(bmp, "rb").read()
    os.remove(bmp)
    offset = struct.unpack_from("<I", data, 10)[0]
    width, height = struct.unpack_from("<ii", data, 18)
    step = struct.unpack_from("<H", data, 28)[0] // 8
    row = (width * step + 3) // 4 * 4
    flipped = height > 0
    height = abs(height)

    def lit(x, y):
        p = offset + ((height - 1 - y) if flipped else y) * row + x * step
        return data[p] + data[p + 1] + data[p + 2] > 70
    return width, height, lit


def box(path):
    width, height, lit = pixels(path)
    x0, y0, x1, y1 = width, height, 0, 0
    for y in range(0, height, 3):
        for x in range(0, width, 3):
            if lit(x, y):
                x0, y0, x1, y1 = min(x0, x), min(y0, y), max(x1, x), max(y1, y)
    return (x0, y0, x1, y1), (width, height, lit)


def render(raw, out, cx, cy, scale, angle):
    html = out + ".html"
    with open(html, "w") as f:
        f.write(f'''<!doctype html><body style="margin:0;background:#000;overflow:hidden;width:2560px;height:1440px">
<img src="file://{raw}" style="position:absolute;left:0;top:0;image-rendering:auto;transform-origin:{cx}px {cy}px;
transform:translate({1280 - cx}px,{720 - cy}px) scale({scale}) rotate({angle}deg)"></body>''')
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                    "--window-size=2560,1440", f"--screenshot={out}", "file://" + html],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.remove(html)


# A curved screen has no straight top edge to measure, so its tilt can be given: --tilt DEGREES.
names = sys.argv[1:]
fixed_tilt = None
if names and names[0] == "--tilt":
    fixed_tilt = float(names[1])
    names = names[2:]

for name in names:
    raw = os.path.join(SHOTS, "raw", name + ".jpg")
    out = os.path.join(SHOTS, name + ".png")
    (x0, y0, x1, y1), (width, height, lit) = box(raw)
    # Tilt: where the top edge of the lit area is at 30% and at 70% of its width.
    xa, xb = int(x0 + 0.3 * (x1 - x0)), int(x0 + 0.7 * (x1 - x0))
    ya = next(y for y in range(height) if lit(xa, y))
    yb = next(y for y in range(height) if lit(xb, y))
    angle = fixed_tilt if fixed_tilt is not None else -math.degrees(math.atan2(yb - ya, xb - xa))
    cx, cy, scale = (x0 + x1) / 2, (y0 + y1) / 2, 1.0
    for _ in range(2):
        render(raw, out, cx, cy, scale, angle)
        (bx0, by0, bx1, by1), _unused = box(out)
        dx, dy = ((bx0 + bx1) / 2 - 1280) / scale, ((by0 + by1) / 2 - 720) / scale
        c, s = math.cos(math.radians(-angle)), math.sin(math.radians(-angle))
        cx, cy = cx + dx * c - dy * s, cy + dx * s + dy * c
        scale *= min(FILL * 2560 / max(1, bx1 - bx0), FILL * 1440 / max(1, by1 - by0))
    render(raw, out, cx, cy, scale, angle)
    print(name, "tilt", round(angle, 1), "scale", round(scale, 2))
