#!/usr/bin/env python3
"""Places a dark-background capture of the app's screen into a photo of a room.

The result is a staged picture of the mixed-reality mode, not a capture: the screen is the app's real
output, the room is a stand-in for whatever the buyer's own room looks like.

Usage: compose-room.py SHOT ROOM OUT CENTRE_X CENTRE_Y SCALE
(SHOT and OUT are names in store/screenshots/, ROOM a path; CENTRE is where the shot's middle goes in the 2560 x 1440 result.)
"""
import os
import struct
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "store", "screenshots")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
shot, room, out = os.path.join(SHOTS, sys.argv[1] + ".png"), os.path.abspath(sys.argv[2]), os.path.join(SHOTS, sys.argv[3] + ".png")
cx, cy, scale = float(sys.argv[4]), float(sys.argv[5]), float(sys.argv[6])

# Outline of everything lit in the shot, row by row, so the black around the screen can be cut away.
bmp = shot + ".bmp"
subprocess.run(["sips", "-s", "format", "bmp", shot, "--out", bmp], check=True, stdout=subprocess.DEVNULL)
data = open(bmp, "rb").read()
os.remove(bmp)
offset = struct.unpack_from("<I", data, 10)[0]
width, height = struct.unpack_from("<ii", data, 18)
step = struct.unpack_from("<H", data, 28)[0] // 8
row = (width * step + 3) // 4 * 4
flipped = height > 0
height = abs(height)
left, right = [], []
for y in range(0, height, 6):
    line = offset + ((height - 1 - y) if flipped else y) * row
    lit = [x for x in range(0, width, 2) if data[line + x * step] + data[line + x * step + 1] + data[line + x * step + 2] > 70]
    if len(lit) > 40:
        left.append((lit[0] + 3, y))
        right.append((lit[-1] - 3, y))
points = ",".join(f"{x}px {y}px" for x, y in left + right[::-1])

html = out + ".html"
place = f"position:absolute;left:0;top:0;transform-origin:1280px 720px;transform:translate({cx - 1280}px,{cy - 720}px) scale({scale})"
with open(html, "w") as f:
    f.write(f'''<!doctype html><body style="margin:0;overflow:hidden;width:2560px;height:1440px;background:#000">
<img src="file://{room}" style="position:absolute;left:0;top:0;width:2560px;height:1440px;object-fit:cover;filter:brightness(0.82) saturate(0.95)">
<img src="file://{shot}" style="{place};clip-path:polygon({points});filter:blur(70px) brightness(1.3);opacity:0.4">
<img src="file://{shot}" style="{place};clip-path:polygon({points})">
</body>''')
subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                "--window-size=2560,1440", f"--screenshot={out}", "file://" + html],
               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
os.remove(html)
print(out)
