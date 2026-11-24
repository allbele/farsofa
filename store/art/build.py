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
MINT = "#5FD0A6"
GOLD = "#FFD66B"
FONT = "'Avenir Next', 'Avenir', 'Helvetica Neue', sans-serif"

# The screen in world units: 16:9, with gently bowed top and bottom edges like a curved display.
SX, SY, SW, SH = 600, 80, 1800, 1012
BOW = 22


def game(hud=True, detail=True):
    """What is on the screen, in the screen's own 1800 x 1012 space: a platform game at dusk, with a HUD."""
    def platform(x, y, w, h):
        return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="26" fill="#1A2864"/>'
                f'<rect x="{x}" y="{y}" width="{w}" height="34" rx="17" fill="{MINT}"/>')
    coins = "".join(f'<circle cx="{x}" cy="{y}" r="24" fill="{GOLD}"/><circle cx="{x}" cy="{y}" r="11" fill="#F0B63C"/>'
                    for x, y in ((700, 470), (800, 400), (910, 372), (1020, 400), (1310, 400)))
    pips = "".join(
        f'<rect x="{70 + i * 70}" y="64" width="58" height="22" rx="11" fill="{CREAM}" opacity="{1 if i < 4 else 0.3}"/>'
        for i in range(6)
    )
    counter = (f'<rect x="1528" y="50" width="208" height="58" rx="29" fill="#0B0D1E" opacity="0.5"/>'
               f'<circle cx="1566" cy="79" r="15" fill="{GOLD}"/><rect x="1596" y="69" width="110" height="20" rx="10" fill="{CREAM}"/>')
    trail = (f'<path d="M452 620 Q520 470 640 500" fill="none" stroke="{CREAM}" stroke-width="8" '
             f'stroke-dasharray="4 26" stroke-linecap="round" opacity="0.8"/>') if detail else ""
    return f'''
      <rect width="1800" height="1012" fill="#2748B8"/>
      <rect y="330" width="1800" height="260" fill="#4C74E6"/>
      <rect y="560" width="1800" height="460" fill="#FFB877"/>
      <circle cx="1420" cy="250" r="128" fill="#FFF1CF"/>
      <path d="M0 720 Q200 560 420 690 T860 660 T1300 700 T1800 620 L1800 1012 L0 1012 Z" fill="#3A53B8"/>
      <path d="M0 820 Q260 700 520 800 T1080 790 T1800 760 L1800 1012 L0 1012 Z" fill="#2B3F97"/>
      {platform(-40, 700, 560, 400)}
      {platform(640, 610, 300, 110)}
      {platform(1150, 520, 320, 110)}
      {platform(1560, 690, 320, 400)}
      {coins if detail else ""}
      <!-- the hero, mid-jump between two platforms -->
      {trail}
      <path d="M560 452 L498 520 L566 512 Z" fill="#C84E28"/>       <rect x="556" y="440" width="76" height="92" rx="26" fill="{CLAY}"/>
      <circle cx="600" cy="412" r="38" fill="#FFE1D2"/>
      {pips if hud else ""}
      {counter if hud else ""}'''


def scene(tx, ty, scale, key, figure=1.0, hud=True, detail=True):
    """The whole scene, placed so world point (0, 0) lands at (tx, ty) and one world unit is `scale` pixels.

    `figure` enlarges the player and sofa about the bottom centre of the screen, for small formats.
    """
    x1, y1, x2, y2 = SX, SY, SX + SW, SY + SH
    outline = (f"M{x1 + 40} {y1 + BOW} Q{(x1 + x2) / 2} {y1 - BOW} {x2 - 40} {y1 + BOW} Q{x2} {y1 + BOW} {x2} {y1 + BOW + 40} "
               f"L{x2} {y2 - BOW - 40} Q{x2} {y2 - BOW} {x2 - 40} {y2 - BOW} Q{(x1 + x2) / 2} {y2 + BOW} {x1 + 40} {y2 - BOW} "
               f"Q{x1} {y2 - BOW} {x1} {y2 - BOW - 40} L{x1} {y1 + BOW + 40} Q{x1} {y1 + BOW} {x1 + 40} {y1 + BOW} Z")
    cushions = "".join(
        f'<rect x="{x}" y="1262" width="268" height="150" rx="52" fill="#2E3E7E"/>'
        f'<path d="M{x + 50} 1264 L{x + 218} 1264" stroke="{BLUE}" stroke-width="8" stroke-linecap="round"/>'
        for x in (1082, 1366, 1650)
    )
    return f'''
    <g transform="translate({tx} {ty}) scale({scale})">
      <!-- the screen's light on the floor: a soft pool, no hard beams -->
      <polygon points="760,1085 2240,1085 3300,5000 -300,5000" fill="#101533"/>
      <ellipse cx="1500" cy="1560" rx="980" ry="205" fill="#161D42"/>
      <clipPath id="screen-{key}"><path d="{outline}"/></clipPath>
      <g clip-path="url(#screen-{key})"><g transform="translate({SX} {SY})">{game(hud, detail)}</g></g>
      <g transform="translate(1500 1092) scale({figure}) translate(-1500 -1092)">
        <ellipse cx="1500" cy="1606" rx="640" ry="44" fill="#080A18"/>
        <!-- the player, seen from behind, head cutting into the bottom of the picture -->
        <g transform="translate(-284 0)">
        <clipPath id="head-{key}"><circle cx="1500" cy="1096" r="112"/></clipPath>
        <ellipse cx="1500" cy="1300" rx="236" ry="108" fill="#43549A"/>
        <rect x="1456" y="1180" width="88" height="70" fill="#43549A"/>
        <circle cx="1500" cy="1096" r="112" fill="#43549A"/>
        <g clip-path="url(#head-{key})">
          <path d="M1500 980 L1500 1104" stroke="{CREAM}" stroke-width="20"/>
          <path d="M1380 1090 Q1500 1130 1620 1090" fill="none" stroke="{CREAM}" stroke-width="50"/>
        </g>
        </g>
        <!-- a low, wide sofa: arms, a back made of three cushions, short legs -->
        <rect x="1016" y="1560" width="60" height="64" rx="14" fill="#0A0D20"/>
        <rect x="1924" y="1560" width="60" height="64" rx="14" fill="#0A0D20"/>
        <rect x="1056" y="1320" width="888" height="270" rx="64" fill="#222F66"/>
        {cushions}
        <rect x="960" y="1352" width="150" height="250" rx="62" fill="#283671"/>
        <rect x="1890" y="1352" width="150" height="250" rx="62" fill="#283671"/>
      </g>
    </g>'''


def wordmark(x, baseline, size, anchor="start"):
    """The title. Its "o" is a small lit screen: a cream ring like the other letters, glowing blue inside."""
    width = size * 4.4
    left = x if anchor == "start" else x - width / 2
    align = "left" if anchor == "start" else "center"
    return f'''
    <foreignObject x="{left}" y="{baseline - size * 1.1}" width="{width}" height="{size * 1.5}">
      <div xmlns="http://www.w3.org/1999/xhtml" style="font:700 {size}px/1.1 {FONT};color:{CREAM};letter-spacing:-0.035em;text-align:{align};white-space:nowrap">Fars<span style="display:inline-block;box-sizing:border-box;width:0.61em;height:0.52em;margin:0 0.055em 0 0.045em;border:0.165em solid {CREAM};border-radius:0.18em;background:{BLUE}"></span>fa</div>
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
    yield "icon-512", 512, 512, scene(-51.5, 54, 0.205, "icon", figure=1.9, hud=False, detail=False), INK
    yield ("cover-landscape-2560x1440", 2560, 1440,
           scene(1064, 175, 0.56, "land") + wordmark(150, 800, 285), INK)
    yield ("cover-square-1440", 1440, 1440,
           scene(-30, 60, 0.5, "square") + wordmark(720, 1196, 250, "middle"), INK)
    yield ("cover-portrait-1008x1440", 1008, 1440,
           scene(-246, 100, 0.5, "portrait") + wordmark(504, 1140, 180, "middle"), INK)
    yield ("hero-3000x900", 3000, 900,
           scene(1470, 36, 0.46, "hero") + wordmark(300, 560, 300), INK)
    yield ("mini-landscape-1080x360", 1080, 360,
           scene(540, 6, 0.2, "mini", figure=1.12, hud=False) + wordmark(60, 228, 130), INK)
    yield "logo-transparent-3000x900", 3000, 900, wordmark(1500, 770, 520, "middle"), None
    # The picture shown on the virtual screen when taking store screenshots, squeezed into video range
    # (black 16, white 235) because the app stretches whatever it shows back out to full range.
    yield ("demo-screen-1920x1080", 1920, 1080,
           f'<filter id="video-range"><feComponentTransfer><feFuncR type="linear" slope="0.859" intercept="0.063"/>'
           f'<feFuncG type="linear" slope="0.859" intercept="0.063"/><feFuncB type="linear" slope="0.859" intercept="0.063"/>'
           f'</feComponentTransfer></filter><g filter="url(#video-range)" transform="scale({1920 / 1800})">'
           f'<rect width="1800" height="1012" fill="#000"/>{game()}</g>', None)


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
