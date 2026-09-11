"""Ana sayfa simgeleri: make_art.py'deki 9x9 piksel haritalarindan SVG maskeleri uretir.

Oyun icindeki GUI ile web sayfalari ayni piksel kaynagini kullansin diye simgeler
yeniden cizilmiyor, make_art.py'deki ICONS sozlugu okunuyor.

Cikti: home/icons.css - her simge bir CSS degiskeni (data URI). Maske olarak kullanilir,
yani rengi sayfadan verilir:  background:#fff; mask:var(--ic-play) center/contain no-repeat;
"""
import ast
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.join(HERE, "make_art.py")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "doomscroll", "home", "icons.css")

# Sayfalarda kullanilanlar (hepsini gomup boyutu sisirmeyelim)
WANTED = ["play", "home", "cinema", "plus", "close", "trash", "clock", "cast",
          "queue", "tv", "go", "star", "star_filled", "menu", "back", "forward",
          "reload", "broadcast", "speaker", "lock", "search"]


def icons_from_make_art():
    """make_art.py'yi calistirmadan ICONS sozlugunu okur."""
    tree = ast.parse(open(ART, encoding="utf-8").read())
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
                isinstance(t, ast.Name) and t.id == "ICONS" for t in node.targets):
            return ast.literal_eval(node.value)
    raise SystemExit("make_art.py icinde ICONS bulunamadi")


def svg(rows):
    """Bos olmayan her pikseli 1x1 kare olarak cizer; maske oldugu icin renk onemsiz."""
    h = len(rows)
    w = len(rows[0])
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges">' % (w, h)]
    for y, row in enumerate(rows):
        x = 0
        while x < w:
            if row[x] == ".":
                x += 1
                continue
            run = x
            while run < w and row[run] != ".":
                run += 1
            parts.append('<rect x="%d" y="%d" width="%d" height="1"/>' % (x, y, run - x))
            x = run
    parts.append("</svg>")
    return "".join(parts)


def data_uri(s):
    """Yalnizca gerekli karakterleri kacir: tam quote() dosyayi uc katina cikariyor."""
    t = (s.replace('"', "'").replace("%", "%25").replace("<", "%3C")
          .replace(">", "%3E").replace("#", "%23").replace(" ", "%20"))
    return 'url("data:image/svg+xml,%s")' % t


icons = icons_from_make_art()
missing = [n for n in WANTED if n not in icons]
if missing:
    raise SystemExit("make_art.py'de olmayan simge: " + ", ".join(missing))

lines = ["/* Uretilen dosya: tools/make_web_icons.py - elle duzenleme.",
         "   Kaynak piksel haritalari make_art.py icindeki ICONS. */",
         ":root{"]
for name in WANTED:
    lines.append("  --ic-%s:%s;" % (name.replace("_", "-"), data_uri(svg(icons[name]))))
lines.append("}")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("yazildi", os.path.relpath(OUT), os.path.getsize(OUT), "bayt,", len(WANTED), "simge")
