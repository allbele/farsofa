#!/usr/bin/env python3
"""Renders the store artwork from one vector scene, at the exact sizes the Meta Horizon Store asks for.

One scene, drawn once in "world" units, is framed differently for each format: a player in a headset
on a sofa, seen from behind, in front of a screen far bigger than they are.

Usage: python3 store/art/build.py   (needs Google Chrome, used headless as the renderer)
"""
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

INK = "#0B0D1E"
CREAM = "#F4EFE6"
BLUE = "#6F9BFF"
CLAY = "#F0683C"
FONT = "'Avenir Next', 'Avenir', 'Helvetica Neue', sans-serif"

# The screen in world units: 16:9, with gently bowed top and bottom edges like a curved display.
SX, SY, SW, SH = 600, 80, 1800, 1012
BOW = 22


def game():
    """What is on the screen, in the screen's own 1800 x 1012 space: a dusk road and a car, with a HUD."""
    dashes = "".join(
        f'<polygon points="{900 - w:.0f},{y} {900 + w:.0f},{y} {900 + w * 1.5:.0f},{y + h} {900 - w * 1.5:.0f},{y + h}" fill="{CREAM}" opacity="0.85"/>'
        for y, h, w in ((690, 22, 3), (740, 38, 5), (820, 62, 9), (930, 82, 15))
    )
    segments = "".join(
        f'<rect x="{70 + i * 70}" y="64" width="58" height="20" rx="10" fill="{CREAM}" opacity="{1 if i < 4 else 0.3}"/>'
        for i in range(6)
    )
    return f'''
      <rect width="1800" height="1012" fill="#2748B8"/>
      <rect y="300" width="1800" height="240" fill="#4C74E6"/>
      <rect y="470" width="1800" height="200" fill="#FFB877"/>
      <circle cx="1210" cy="600" r="170" fill="#FFE7B8"/>
      <polygon points="0,640 180,520 360,610 560,470 800,620 1010,540 1250,640 1440,500 1640,600 1800,540 1800,700 0,700" fill="#2B3F97"/>
      <polygon points="0,680 240,600 520,670 1260,660 1500,590 1800,660 1800,720 0,720" fill="#1C2B6B"/>
      <rect y="660" width="1800" height="352" fill="#111A3D"/>
      <polygon points="822,660 978,660 1560,1012 240,1012" fill="#1F2D66"/>
      {dashes}
      <ellipse cx="900" cy="944" rx="200" ry="26" fill="#0A1028"/>
      <rect x="752" y="812" width="296" height="124" rx="34" fill="{CLAY}"/>
      <path d="M792 822 L826 752 Q834 740 848 740 L952 740 Q966 740 974 752 L1008 822 Z" fill="#C84E28"/>
      <path d="M826 812 L848 764 L952 764 L974 812 Z" fill="#1A2350"/>
      <rect x="774" y="850" width="64" height="26" rx="13" fill="#FFE1D2"/>
      <rect x="962" y="850" width="64" height="26" rx="13" fill="#FFE1D2"/>
      {segments}
      <rect x="1540" y="52" width="196" height="54" rx="27" fill="#0A0F19" opacity="0.5"/>
      <circle cx="1578" cy="79" r="10" fill="{CLAY}"/>
      <rect x="1604" y="70" width="104" height="18" rx="9" fill="{CREAM}"/>
      <circle cx="158" cy="852" r="98" fill="#0A0F19" opacity="0.5"/>
      <circle cx="158" cy="852" r="98" fill="none" stroke="{CREAM}" stroke-width="7"/>
      <path d="M96 890 Q150 850 140 810 T214 790" fill="none" stroke="{CREAM}" stroke-width="9" stroke-linecap="round" opacity="0.7"/>
      <circle cx="140" cy="838" r="13" fill="{CLAY}"/>
      <path d="M1520 900 A150 150 0 0 1 1730 900" fill="none" stroke="{CREAM}" stroke-width="14" stroke-linecap="round"/>
      <path d="M1625 900 L1690 818" stroke="{CLAY}" stroke-width="12" stroke-linecap="round"/>'''


def scene(tx, ty, scale, key, room=True, seam=True):
    """The whole scene, placed so world point (0, 0) lands at (tx, ty) and one world unit is `scale` pixels."""
    x1, y1, x2, y2 = SX, SY, SX + SW, SY + SH
    outline = (f"M{x1 + 40} {y1 + BOW} Q{(x1 + x2) / 2} {y1 - BOW} {x2 - 40} {y1 + BOW} Q{x2} {y1 + BOW} {x2} {y1 + BOW + 40} "
               f"L{x2} {y2 - BOW - 40} Q{x2} {y2 - BOW} {x2 - 40} {y2 - BOW} Q{(x1 + x2) / 2} {y2 + BOW} {x1 + 40} {y2 - BOW} "
               f"Q{x1} {y2 - BOW} {x1} {y2 - BOW - 40} L{x1} {y1 + BOW + 40} Q{x1} {y1 + BOW} {x1 + 40} {y1 + BOW} Z")
    seam = '<path d="M1500 1432 L1500 1900" stroke="#0A0F22" stroke-width="9" stroke-linecap="round"/>' if seam else ""
    furniture = f'''
      <g fill="none" stroke="#1E2942" stroke-width="7" stroke-linecap="round" stroke-linejoin="round">
        <path d="M2700 1560 L2700 760 M2600 760 L2800 760 L2760 620 L2640 620 Z"/>
        <path d="M150 330 L470 330 L470 900 L150 900 Z M310 330 L310 900 M150 615 L470 615"/>
      </g>''' if room else ""
    return f'''
    <g transform="translate({tx} {ty}) scale({scale})">
      {furniture}
      <!-- the screen's light on the floor -->
      <polygon points="700,1085 2300,1085 2900,2600 100,2600" fill="#131C36"/>
      <polygon points="900,1085 2100,1085 2440,2600 560,2600" fill="#1B2747"/>
      <clipPath id="screen-{key}"><path d="{outline}"/></clipPath>
      <g clip-path="url(#screen-{key})"><g transform="translate({SX} {SY})">{game()}</g></g>
      <!-- the player and the sofa, seen from behind; the head cuts into the bottom of the picture -->
      <g transform="translate(0 -128)">
        <!-- sitting on the right-hand cushion, so the head does not line up with the car on the screen -->
        <g transform="translate(236 0)">
          <ellipse cx="1500" cy="1456" rx="270" ry="128" fill="#161E40"/>
          <rect x="1454" y="1320" width="92" height="90" fill="#161E40"/>
          <circle cx="1500" cy="1232" r="122" fill="#0F1634"/>
          <path d="M1398 1166 A122 122 0 0 1 1602 1166" fill="none" stroke="#B4C8FF" stroke-width="9" stroke-linecap="round"/>
          <rect x="1362" y="1206" width="276" height="54" rx="27" fill="{CREAM}"/>
        </g>
        <rect x="884" y="1474" width="190" height="2600" rx="80" fill="#0D1228"/>
        <rect x="1926" y="1474" width="190" height="2600" rx="80" fill="#0D1228"/>
        <rect x="1000" y="1388" width="1000" height="2800" rx="96" fill="#121935"/>
        <path d="M1090 1391 L1910 1391" stroke="{BLUE}" stroke-width="9" stroke-linecap="round" opacity="0.85"/>
        {seam}
      </g>
    </g>'''


def wordmark(x, baseline, size, anchor="start"):
    """The title, with its "o" drawn as a small screen. Laid out as HTML so the letters space themselves."""
    width = size * 4.4
    left = x if anchor == "start" else x - width / 2
    align = "left" if anchor == "start" else "center"
    return f'''
    <foreignObject x="{left}" y="{baseline - size * 1.1}" width="{width}" height="{size * 1.5}">
      <div xmlns="http://www.w3.org/1999/xhtml" style="font:700 {size}px/1.1 {FONT};color:{CREAM};letter-spacing:-0.035em;text-align:{align};white-space:nowrap">Fars<span style="display:inline-block;box-sizing:border-box;width:0.68em;height:0.52em;margin:0 0.05em 0 0.035em;border:0.14em solid {BLUE};border-radius:0.17em"></span>fa</div>
    </foreignObject>'''


def page(width, height, body, background=INK):
    bg = f'<rect width="{width}" height="{height}" fill="{background}"/>' if background else ""
    grain = (f'<filter id="grain"><feTurbulence type="fractalNoise" baseFrequency="0.8" numOctaves="2" seed="7"/>'
             f'<feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 0.07 0"/></filter>'
             f'<rect width="{width}" height="{height}" filter="url(#grain)"/>') if background else ""
    return f'''<!doctype html><html><head><meta charset="utf-8"><style>
html,body{{margin:0;padding:0;background:transparent}}svg{{display:block}}</style></head><body>
<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">{bg}{body}{grain}</svg>
</body></html>'''


def assets():
    yield "icon-512", 512, 512, scene(-224, -20, 0.32, "icon", room=False), INK
    yield ("cover-landscape-2560x1440", 2560, 1440,
           scene(760, 40, 0.8, "land", room=False) + wordmark(150, 790, 250), INK)
    yield ("cover-square-1440", 1440, 1440,
           scene(-180, 12, 0.6, "square", seam=False) + wordmark(720, 1108, 196, "middle"), INK)
    yield ("cover-portrait-1008x1440", 1008, 1440,
           scene(-246, 110, 0.5, "portrait", room=False, seam=False) + wordmark(504, 1046, 150, "middle"), INK)
    yield ("hero-3000x900", 3000, 900,
           scene(1120, -80, 0.62, "hero", room=False) + wordmark(240, 540, 250), INK)
    yield ("mini-landscape-1080x360", 1080, 360,
           scene(310, -10, 0.34, "mini", room=False) + wordmark(56, 222, 112), INK)
    yield "logo-transparent-3000x900", 3000, 900, wordmark(1500, 600, 520, "middle"), None


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, width, height, body, background in assets():
        html = os.path.join(OUT, name + ".html")
        png = os.path.join(OUT, name + ".png")
        with open(html, "w") as f:
            f.write(page(width, height, body, background))
        subprocess.run([
            CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
            "--default-background-color=00000000", f"--window-size={width},{height}", f"--screenshot={png}",
            "file://" + html,
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.remove(html)
        print(name, width, height)


if __name__ == "__main__":
    main()
