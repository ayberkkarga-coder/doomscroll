"""Home page background textures: 16x16 pixel tiles, written into home/tiles.css as base64.

Why CSS rather than files: the page is served from the doomscroll:// scheme as a single HTML document,
so it cannot request files next to it. The tiles come embedded as data URIs.
"""
import base64
import io
import os
import random

from PIL import Image  # pip install pillow

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "src", "main", "resources", "assets", "doomscroll", "home", "tiles.css")


def tile(size, base, spots, seed):
    """Flat base + random but fixed (seeded) dark/light pixels: a vanilla texture feel."""
    rnd = random.Random(seed)
    im = Image.new("RGBA", (size, size), base)
    px = im.load()
    for color, count in spots:
        for _ in range(count):
            px[rnd.randrange(size), rnd.randrange(size)] = color
    return im


def data_uri(im):
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


# Tablet wallpaper: midnight-blue stone, lightly speckled
wall = tile(16, (22, 24, 33, 255), [
    ((27, 30, 41, 255), 46),
    ((17, 19, 27, 255), 34),
    ((31, 35, 48, 255), 12),
], seed=7)

# Screen (TV) menu: darker, neutral gray-black
screen = tile(16, (17, 17, 21, 255), [
    ((22, 22, 27, 255), 40),
    ((13, 13, 17, 255), 30),
    ((26, 26, 32, 255), 10),
], seed=13)

# Panel surface: dark stone, very faint texture
panel = tile(16, (43, 43, 51, 255), [
    ((47, 47, 56, 255), 28),
    ((38, 38, 46, 255), 22),
], seed=21)

CSS = "\n".join([
    "/* Generated file: tools/make_ui_tiles.py — do not edit by hand. */",
    ":root{",
    "  --tile-wall:url(%s);" % data_uri(wall),
    "  --tile-screen:url(%s);" % data_uri(screen),
    "  --tile-panel:url(%s);" % data_uri(panel),
    "}",
])

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write(CSS + "\n")
print("wrote", os.path.relpath(OUT), os.path.getsize(OUT), "bytes")
