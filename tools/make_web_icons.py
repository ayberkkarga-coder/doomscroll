"""Home page icons: generates SVG masks from the 9x9 pixel maps in make_art.py.

So that the in-game GUI and the web pages share the same pixel source, the icons are
not redrawn; the ICONS dictionary in make_art.py is read instead.

Output: home/icons.css - each icon is a CSS variable (data URI). They are used as masks,
so the color is set by the page:  background:#fff; mask:var(--ic-play) center/contain no-repeat;
"""
import ast
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ART = os.path.join(HERE, "make_art.py")
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "doomscroll", "home", "icons.css")

# Icons used by the pages (don't embed them all and bloat the size)
WANTED = ["play", "home", "cinema", "plus", "close", "trash", "clock", "cast",
          "queue", "tv", "go", "star", "star_filled", "menu", "back", "forward",
          "reload", "broadcast", "speaker", "lock", "search"]


def icons_from_make_art():
    """Reads the ICONS dictionary without running make_art.py."""
    tree = ast.parse(open(ART, encoding="utf-8").read())
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(
                isinstance(t, ast.Name) and t.id == "ICONS" for t in node.targets):
            return ast.literal_eval(node.value)
    raise SystemExit("ICONS not found in make_art.py")


def svg(rows):
    """Draws every non-empty pixel as a 1x1 square; since it is a mask, the color doesn't matter."""
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
    """Escape only the necessary characters: a full quote() makes the file three times larger."""
    t = (s.replace('"', "'").replace("%", "%25").replace("<", "%3C")
          .replace(">", "%3E").replace("#", "%23").replace(" ", "%20"))
    return 'url("data:image/svg+xml,%s")' % t


icons = icons_from_make_art()
missing = [n for n in WANTED if n not in icons]
if missing:
    raise SystemExit("icons missing from make_art.py: " + ", ".join(missing))

lines = ["/* Generated file: tools/make_web_icons.py - do not edit by hand.",
         "   Source pixel maps: ICONS in make_art.py. */",
         ":root{"]
for name in WANTED:
    lines.append("  --ic-%s:%s;" % (name.replace("_", "-"), data_uri(svg(icons[name]))))
lines.append("}")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")
print("wrote", os.path.relpath(OUT), os.path.getsize(OUT), "bytes,", len(WANTED), "icons")
