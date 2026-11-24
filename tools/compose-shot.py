#!/usr/bin/env python3
"""Turns a raw headset capture into a store screenshot.

The headset's own capture holds both eyes side by side, each rotated because the displays inside are
canted. This keeps the left eye, turns it level, centres it on a chosen point and saves 2560 x 1440.

Usage: compose-shot.py RAW.png OUT.png CENTRE_X CENTRE_Y SCALE [ANGLE_DEGREES]
(CENTRE in raw pixels; ANGLE defaults to the Quest 3's left-eye cant.)
"""
import os
import subprocess
import sys

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
raw, out, cx, cy, scale = sys.argv[1], sys.argv[2], float(sys.argv[3]), float(sys.argv[4]), float(sys.argv[5])
angle = float(sys.argv[6]) if len(sys.argv) > 6 else 19.0
size = subprocess.run(["sips", "-g", "pixelWidth", "-g", "pixelHeight", raw], capture_output=True, text=True).stdout.split()
width, height = int(size[size.index("pixelWidth:") + 1]), int(size[size.index("pixelHeight:") + 1])
html = out + ".html"
with open(html, "w") as f:
    f.write(f'''<!doctype html><body style="margin:0;background:#000;overflow:hidden;width:2560px;height:1440px">
<img src="file://{os.path.abspath(raw)}" style="position:absolute;left:0;top:0;width:{width}px;height:{height}px;
clip-path:inset(0 {width / 2}px 0 0);transform-origin:{cx}px {cy}px;
transform:translate({1280 - cx}px,{720 - cy}px) scale({scale}) rotate({angle}deg)"></body>''')
subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
                "--window-size=2560,1440", f"--screenshot={os.path.abspath(out)}", "file://" + os.path.abspath(html)],
               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
os.remove(html)
