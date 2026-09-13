# -*- coding: utf-8 -*-
"""doomscroll mod icon: 32x32 pixel art, scaled up 16x for Modrinth (512x512).

Design: a screen block with an embossed dark frame; on the screen, a Reels sunset gradient and
an outlined white play triangle; outside the frame, an ambilight glow that takes on the screen's
color row by row (the mod's distinguishing feature). Same pixel style as the in-game UI.

pip install pillow
"""
import os

from PIL import Image

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "src", "main", "resources", "assets", "doomscroll", "icon.png")

N = 32
SCALE = 16

K = (11, 11, 15, 255)        # outer outline
D = (30, 30, 37, 255)        # frame shadow
F = (48, 48, 57, 255)        # frame
H = (78, 78, 92, 255)        # frame highlight
B = (20, 20, 25, 255)        # bottom band
LED = (88, 214, 107, 255)
W = (246, 246, 250, 255)

# screen gradient (top to bottom); the glow reads from the same stops too
STOPS = [
    (255, 150, 60),
    (255, 96, 82),
    (242, 48, 130),
    (150, 60, 235),
]


def put(px, x, y, c):
    if 0 <= x < N and 0 <= y < N:
        px[x, y] = c


def rect(px, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(px, x, y, c)


def stop_at(y):
    """Gradient color at row y (relative to the screen's row range)."""
    t = (y - 5) / 21.0
    pos = max(0.0, min(1.0, t)) * (len(STOPS) - 1)
    i = min(int(pos), len(STOPS) - 2)
    fr = pos - i
    a, b = STOPS[i], STOPS[i + 1]
    return tuple(round(a[k] + (b[k] - a[k]) * fr) for k in range(3))


def glow(px, x0, y0, x1, y1, rings):
    """Glow in rings around the outside of the box; corners left empty, the last ring thinned out with a checkerboard pattern."""
    for i, (alpha, dither) in enumerate(rings, start=1):
        for y in range(y0 - i, y1 + i + 1):
            for x in range(x0 - i, x1 + i + 1):
                if x0 - i + 1 <= x <= x1 + i - 1 and y0 - i + 1 <= y <= y1 + i - 1:
                    continue
                cx = x < x0 - i + 1 or x > x1 + i - 1
                cy = y < y0 - i + 1 or y > y1 + i - 1
                if cx and cy:
                    continue
                if dither and (x + y) % 2:
                    continue
                put(px, x, y, stop_at(min(max(y, y0), y1)) + (alpha,))


def frame(px, x0, y0, x1, y1):
    """Outer outline + 1 px embossed frame + screen outline (3 px in total); returns the screen area."""
    rect(px, x0, y0, x1, y1, K)
    rect(px, x0 + 1, y0 + 1, x1 - 1, y1 - 1, F)
    rect(px, x0 + 1, y0 + 1, x1 - 2, y0 + 1, H)      # top highlight
    rect(px, x0 + 1, y0 + 1, x0 + 1, y1 - 2, H)      # left highlight
    rect(px, x0 + 1, y1 - 1, x1 - 1, y1 - 1, D)      # bottom shadow
    rect(px, x1 - 1, y0 + 1, x1 - 1, y1 - 1, D)      # right shadow
    rect(px, x0 + 2, y0 + 2, x1 - 2, y1 - 2, K)      # screen outline
    for (x, y) in ((x0, y0), (x1, y0), (x0, y1), (x1, y1)):
        put(px, x, y, (0, 0, 0, 0))
    return x0 + 3, y0 + 3, x1 - 3, y1 - 3


def stand(px, x0, y1, x1):
    cx = (x0 + x1) // 2
    rect(px, cx - 3, y1 + 1, cx + 2, y1 + 2, K)
    rect(px, cx - 2, y1 + 1, cx + 1, y1 + 2, D)
    rect(px, cx - 6, y1 + 3, cx + 5, y1 + 4, K)
    rect(px, cx - 5, y1 + 3, cx + 4, y1 + 3, F)
    rect(px, cx - 5, y1 + 4, cx + 4, y1 + 4, D)


def play(px, cx, cy, size, color, outline, aspect=0.58):
    """Right-pointing triangle; the outline is the fill grown by 1 px in every direction."""
    pts = []
    for col in range(size + 1):
        h = round(size * aspect * (size - col) / size)
        for y in range(cy - h, cy + h + 1):
            pts.append((cx - size // 2 + col, y))
    for x, y in pts:
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                put(px, x + dx, y + dy, outline)
    for x, y in pts:
        put(px, x, y, color)


def vgradient(px, x0, y0, x1, y1):
    """Vertical color bands; the transition at band boundaries uses checkerboard thinning."""
    rows = y1 - y0 + 1
    for y in range(y0, y1 + 1):
        pos = (y - y0) / max(1, rows - 1) * (len(STOPS) - 1)
        i = min(int(pos), len(STOPS) - 2)
        fr = pos - i
        a, b = STOPS[i] + (255,), STOPS[i + 1] + (255,)
        for x in range(x0, x1 + 1):
            use_b = fr > 0.66 or (fr > 0.33 and (x + y) % 2 == 0)
            put(px, x, y, b if use_b else a)


def draw():
    im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = im.load()
    # body 26x22 with a 4-row stand below it: 26 rows in total, vertically centered on the 32 px canvas
    X0, Y0, X1, Y1 = 3, 5, 28, 26
    glow(px, X0, Y0, X1, Y1, [(120, False), (70, False), (36, True)])
    sx0, sy0, sx1, sy1 = frame(px, X0, Y0, X1, Y1)        # screen 20x16
    vgradient(px, sx0, sy0, sx1, sy1 - 1)                 # 15 rows of gradient
    rect(px, sx0, sy1, sx1, sy1, B)                       # single-row bottom band
    rect(px, sx1 - 2, sy1, sx1 - 1, sy1, LED)
    play(px, (sx0 + sx1) // 2 + 1, (sy0 + sy1 - 1) // 2, 7, W, K, aspect=0.6)
    stand(px, X0, Y1, X1)
    return im


if __name__ == "__main__":
    big = draw().resize((N * SCALE, N * SCALE), Image.NEAREST)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    big.save(OUT)
    print("wrote", os.path.normpath(OUT), big.size)
