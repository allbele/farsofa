#!/usr/bin/env python3
"""Frames dark-background headset captures for the store: finds what is lit in the levelled picture,
then centres it and sizes it to fill the frame with a margin.

Usage: frame-shots.py NAME [NAME...]   (reads store/screenshots/raw/NAME.png, writes store/screenshots/NAME.png)
"""
import math
import os
import struct
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "store", "screenshots")
COMPOSE = os.path.join(ROOT, "tools", "compose-shot.py")
ANGLE = 22.0
FILL = 0.88


def lit_box(png):
    """Bounding box of everything brighter than near-black, in pixels."""
    bmp = png + ".bmp"
    subprocess.run(["sips", "-s", "format", "bmp", png, "--out", bmp], check=True, stdout=subprocess.DEVNULL)
    data = open(bmp, "rb").read()
    os.remove(bmp)
    offset = struct.unpack_from("<I", data, 10)[0]
    width, height = struct.unpack_from("<ii", data, 18)
    step = struct.unpack_from("<H", data, 28)[0] // 8
    row = (width * step + 3) // 4 * 4
    flipped = height > 0
    height = abs(height)
    x0, y0, x1, y1 = width, height, 0, 0
    for y in range(0, height, 4):
        line = offset + ((height - 1 - y) if flipped else y) * row
        for x in range(0, width, 4):
            p = line + x * step
            if data[p] + data[p + 1] + data[p + 2] > 60:
                x0, y0, x1, y1 = min(x0, x), min(y0, y), max(x1, x), max(y1, y)
    return x0, y0, x1, y1


def compose(name, cx, cy, scale):
    subprocess.run([sys.executable, COMPOSE, os.path.join(SHOTS, "raw", name + ".png"),
                    os.path.join(SHOTS, name + ".png"), str(cx), str(cy), str(scale), str(ANGLE)], check=True)


for name in sys.argv[1:]:
    cx, cy, scale = 1105.0, 900.0, 0.8
    for _ in range(2):
        compose(name, cx, cy, scale)
        x0, y0, x1, y1 = lit_box(os.path.join(SHOTS, name + ".png"))
        # Move the raw centre by the lit area's offset from the middle, undoing the scale and the rotation.
        dx, dy = ((x0 + x1) / 2 - 1280) / scale, ((y0 + y1) / 2 - 720) / scale
        c, s = math.cos(math.radians(ANGLE)), math.sin(math.radians(ANGLE))
        cx, cy = cx + dx * c + dy * s, cy - dx * s + dy * c
        scale *= min(FILL * 2560 / max(1, x1 - x0), FILL * 1440 / max(1, y1 - y0))
    compose(name, cx, cy, scale)
    print(name, round(cx), round(cy), round(scale, 3))
