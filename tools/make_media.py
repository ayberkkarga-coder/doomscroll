# -*- coding: utf-8 -*-
"""Modrinth / GitHub sayfa gorselleri: banner, ambilight kolaji, film gecesi ve tablet kartlari.

Kullanim:
    python tools/make_media.py --src KLASOR [--out docs/media]

KLASOR icinde su adlarla 1920x1080 oyun ekran goruntuleri beklenir:
    neon.png       ekran + mavi neon (banner arka plani, kolaj)
    lanterns.png   kirmizi fenerler (kolaj)
    charade.png    Charade (1963) film karesi (film gecesi karti, kolaj)
    mclintock.png  McLintock! (1963) film karesi (kolaj)
    tablet.png     elde dik tablet (tablet karti; HUD alttan kirpilir, arti isareti kapatilir)

Yazi tipi: jar'daki Jersey 10 (home/font.css icindeki woff2), ikon: assets/doomscroll/icon.png.
Slogan icin Windows'taki Segoe UI Semibold kullanilir; yoksa Jersey 10'a duser.

pip install pillow
"""
import argparse
import base64
import os
import re
import tempfile

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "src", "main", "resources", "assets", "doomscroll")
FONT_CSS = os.path.join(RES, "home", "font.css")
ICON = os.path.join(RES, "icon.png")

K = (11, 11, 15)                                   # ikonla ayni dis kontur rengi
WHITE = (246, 246, 250)
CORAL = (224, 86, 76)                              # oyun ici marka: doom<em>scroll</em> rengi
STOPS = [(255, 150, 60), (255, 96, 82), (242, 48, 130), (150, 60, 235)]   # ikonun gun batimi gradyani


def lerp_stop(t):
    t = max(0.0, min(1.0, t))
    pos = t * (len(STOPS) - 1)
    i = min(int(pos), len(STOPS) - 2)
    fr = pos - i
    a, b = STOPS[i], STOPS[i + 1]
    return tuple(round(a[k] + (b[k] - a[k]) * fr) for k in range(3))


def jersey_font(size):
    """font.css icindeki latin @font-face blogunu cozer; Pillow woff2 dosyasini dogrudan acar."""
    css = open(FONT_CSS, encoding="utf-8").read()
    blocks = re.findall(r"@font-face\{[^}]*\}", css)
    chosen = None
    for b in blocks:
        m = re.search(r"data:font/woff2;base64,([A-Za-z0-9+/=]+)", b)
        if m and (chosen is None or "U+0000-00FF" in b):
            chosen = m.group(1)
    data = base64.b64decode(chosen)
    path = os.path.join(tempfile.gettempdir(), "doomscroll-jersey10-latin.woff2")
    with open(path, "wb") as f:
        f.write(data)
    return ImageFont.truetype(path, size)


def ui_font(size):
    for p in ("C:/Windows/Fonts/seguisb.ttf", "C:/Windows/Fonts/segoeui.ttf",
              "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return jersey_font(size)


def pixel_text(text, small=20, scale=12, fill="gradient", split_at=None, outline=1):
    """Jersey 10'u kucuk boyda cizip NEAREST buyutur: ikonla ayni sert piksel gorunumu.

    fill: "gradient", bir (r,g,b) rengi ya da (sol, sag) ikilisi; ikili verilirse metnin
    split_at'inci karakterinden itibaren sag dolgu kullanilir.
    Dondurur: (RGBA katman, metin kutusu (x0,y0,x1,y1) katman icinde).
    """
    font = jersey_font(small)
    d0 = ImageDraw.Draw(Image.new("L", (1, 1)))
    bb = d0.textbbox((0, 0), text, font=font)
    pad = outline + 1
    w, h = bb[2] - bb[0] + 2 * pad, bb[3] - bb[1] + 2 * pad
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).text((pad - bb[0], pad - bb[1]), text, font=font, fill=255)
    mask = mask.point(lambda v: 255 if v > 127 else 0)
    edge = mask.filter(ImageFilter.MaxFilter(2 * outline + 1)) if outline else mask
    big = (w * scale, h * scale)
    mask_b = mask.resize(big, Image.NEAREST)
    edge_b = edge.resize(big, Image.NEAREST)

    layer = Image.new("RGBA", big, (0, 0, 0, 0))
    shadow = Image.new("RGBA", big, K + (150,))          # golge: kontur 1 kucuk piksel sag-alta
    layer.paste(shadow, (scale, scale), edge_b)
    layer.paste(Image.new("RGBA", big, K + (255,)), (0, 0), edge_b)

    two = isinstance(fill, tuple) and len(fill) == 2 and isinstance(fill[0], (tuple, str))
    fills = fill if two else (fill, fill)
    split_x = big[0]
    if split_at is not None:
        split_x = round((pad - bb[0] + font.getlength(text[:split_at])) * scale)
    fillimg = Image.new("RGBA", big, (0, 0, 0, 0))
    for i, f in enumerate(fills):
        x0, x1 = (0, split_x) if i == 0 else (split_x, big[0])
        if x1 <= x0:
            continue
        if f == "gradient":
            part = Image.new("RGBA", (x1 - x0, big[1]))
            top, bot = pad * scale, big[1] - pad * scale
            for y in range(big[1]):
                c = lerp_stop((y - top) / max(1, bot - top)) + (255,)
                part.paste(c, (0, y, x1 - x0, y + 1))
        else:
            part = Image.new("RGBA", (x1 - x0, big[1]), tuple(f) + (255,))
        fillimg.paste(part, (x0, 0))
    layer.paste(fillimg, (0, 0), mask_b)
    box = (pad * scale, pad * scale, big[0] - pad * scale, big[1] - pad * scale)
    return layer, box


def icon_pixels(px_size):
    """512'lik ikonu 32'lik izgaraya indirip tam kat buyutur; piksel sanati bozulmaz."""
    ic = Image.open(ICON).convert("RGBA").resize((32, 32), Image.NEAREST)
    k = max(1, px_size // 32)
    return ic.resize((32 * k, 32 * k), Image.NEAREST)


def soft_box(size, box, alpha, radius):
    """Yazi arkasina yumusak kenarli koyu dikdortgen maskesi."""
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle(box, radius=40, fill=alpha)
    return m.filter(ImageFilter.GaussianBlur(radius))


def banner(src, out, style="lower", blur=3, size=(1920, 640)):
    W, H = size
    bg = Image.open(os.path.join(src, "neon.png")).convert("RGB")
    top = (bg.height - H) // 2 - 50                     # ekran biraz yukarida kalsin, altta oyuncu basi
    bg = bg.crop((0, top, W, top + H))
    if blur:
        bg = bg.filter(ImageFilter.GaussianBlur(blur))
    bg = ImageEnhance.Brightness(bg).enhance(0.48)
    canvas = bg.convert("RGBA")

    if style == "upper":
        word, wbox = pixel_text("DOOMSCROLL", fill="gradient")
    elif style == "coral":
        word, wbox = pixel_text("doomscroll", fill=(WHITE, CORAL), split_at=4)
    else:
        word, wbox = pixel_text("doomscroll", fill=(WHITE, "gradient"), split_at=4)
    tag_font = ui_font(46)
    tagline = "You were going to scroll anyway."
    d = ImageDraw.Draw(canvas)
    tb = d.textbbox((0, 0), tagline, font=tag_font)
    tag_h = tb[3] - tb[1]

    icon = icon_pixels(192)
    gap = 40
    word_w = wbox[2] - wbox[0]
    word_h = wbox[3] - wbox[1]
    block_w = icon.width + gap + word_w
    block_h = word_h + 28 + tag_h
    x0 = (W - block_w) // 2
    y0 = (H - block_h) // 2 - 8

    sb = soft_box(canvas.size, (x0 - 70, y0 - 60, x0 + block_w + 70, y0 + block_h + 60), 150, 45)
    canvas.paste(Image.new("RGBA", canvas.size, K + (255,)), (0, 0), sb)

    icon_y = y0 + (block_h - icon.height) // 2
    canvas.alpha_composite(icon, (x0, icon_y))
    wx = x0 + icon.width + gap
    canvas.alpha_composite(word, (wx - wbox[0], y0 - wbox[1]))
    d = ImageDraw.Draw(canvas)
    ty = y0 + word_h + 28
    d.text((wx + 2, ty + 2 - tb[1]), tagline, font=tag_font, fill=K + (200,))
    d.text((wx, ty - tb[1]), tagline, font=tag_font, fill=WHITE + (255,))
    canvas.convert("RGB").save(out)
    return canvas


def fit_crop(im, box, size):
    """box'u kirp, size'a sigdir (oran farkini ortadan kirparak)."""
    im = im.crop(box)
    tw, th = size
    s = max(tw / im.width, th / im.height)
    im = im.resize((round(im.width * s), round(im.height * s)), Image.LANCZOS)
    x = (im.width - tw) // 2
    y = (im.height - th) // 2
    return im.crop((x, y, x + tw, y + th))


def collage(src, out, gutter=8, size=(1920, 1080)):
    W, H = size
    cw, ch = (W - 3 * gutter) // 2, (H - 3 * gutter) // 2
    sheet = Image.new("RGB", size, K)
    order = ["neon", "lanterns", "charade", "mclintock"]
    for i, n in enumerate(order):
        im = Image.open(os.path.join(src, n + ".png")).convert("RGB")
        cell = fit_crop(im, (80, 45, 1840, 1035), (cw, ch))
        x = gutter + (i % 2) * (cw + gutter)
        y = gutter + (i // 2) * (ch + gutter)
        sheet.paste(cell, (x, y))
    sheet.save(out)
    return sheet


def film_night(src, out):
    im = Image.open(os.path.join(src, "charade.png")).convert("RGB")
    im.crop((200, 60, 1720, 915)).save(out)          # 1520x855, ekran + isiyan duvarlar


def tablet_card(src, out):
    im = Image.open(os.path.join(src, "tablet.png")).convert("RGB")
    patch = im.crop((948, 553, 972, 577))            # arti isaretini hemen altindaki dokuyla kapat
    im.paste(patch, (948, 528))
    im.crop((0, 0, 1706, 960)).save(out)              # HUD (can, aclik, hotbar) disarida kalir


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", default=os.path.join(ROOT, "docs", "media"))
    ap.add_argument("--style", default="lower", choices=["lower", "coral", "upper"])
    ap.add_argument("--blur", type=float, default=3)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    banner(a.src, os.path.join(a.out, "banner.png"), style=a.style, blur=a.blur)
    collage(a.src, os.path.join(a.out, "ambilight.png"))
    film_night(a.src, os.path.join(a.out, "film-night.png"))
    tablet_card(a.src, os.path.join(a.out, "tablet.png"))
    for n in ("banner", "ambilight", "film-night", "tablet"):
        p = os.path.join(a.out, n + ".png")
        print(n, Image.open(p).size, f"{os.path.getsize(p) / 1e6:.2f} MB")


if __name__ == "__main__":
    main()
