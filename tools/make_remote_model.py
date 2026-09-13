"""The remote's 3D in-hand model (models/item/remote.json) and its texture (textures/item/remote_model.png).
Body 4x12x1.5, rounded ends, raised buttons: power (red), D-pad, two colored shortcuts, bottom row.
GUI/ground/fixed contexts use the 2D icon (remote_icon); this model is only visible in hand."""
import json
import os
from PIL import Image  # pip install pillow

A = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "doomscroll")

# ---------- texture: regions (u0, v0, u1, v1) -> color ----------
COL = {
    "L": (61, 61, 70, 255), "B": (44, 44, 51, 255), "D": (38, 38, 44, 255), "K": (30, 30, 35, 255),
    "cap": (51, 51, 59, 255), "ir": (16, 16, 20, 255),
    "R": (217, 67, 59, 255), "r": (140, 35, 31, 255),
    "G": (154, 154, 166, 255), "g": (110, 110, 122, 255), "W": (232, 232, 238, 255),
    "A": (63, 141, 224, 255), "a": (34, 88, 154, 255), "E": (79, 191, 107, 255), "e": (39, 110, 58, 255),
    "d": (110, 110, 122, 255), "dd": (74, 74, 85, 255),
}
REG = {
    "front": (0, 0, 4, 12), "side": (4, 0, 6, 12), "back": (6, 0, 10, 12), "cap": (10, 0, 13, 2), "capside": (10, 4, 13, 5),
    "power": (13, 0, 15, 1), "powerside": (13, 1, 15, 2), "ir": (15, 0, 16, 1),
    "gray": (10, 2, 11, 3), "grayside": (11, 2, 12, 3), "center": (12, 2, 13, 3),
    "blue": (13, 2, 14, 3), "blueside": (13, 3, 14, 4), "green": (14, 2, 15, 3), "greenside": (14, 3, 15, 4),
    "dgray": (10, 3, 11, 4), "dgrayside": (11, 3, 12, 4),
}
FILL = {
    "side": "D", "cap": "cap", "capside": "B", "power": "R", "powerside": "r", "ir": "ir",
    "gray": "G", "grayside": "g", "center": "W", "blue": "A", "blueside": "a", "green": "E", "greenside": "e",
    "dgray": "d", "dgrayside": "dd",
}
im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
px = im.load()
def fill(reg, col):
    u0, v0, u1, v1 = REG[reg]
    for y in range(v0, v1):
        for x in range(u0, u1):
            px[x, y] = COL[col]
for reg, col in FILL.items():
    fill(reg, col)
# front face: light left column, dark right column, body in between; a slightly darker band at the bottom end
for y in range(0, 12):
    px[0, y] = COL["L"]; px[1, y] = COL["B"]; px[2, y] = COL["B"]; px[3, y] = COL["D"]
# back face: battery cover line
fill("back", "K")
for y in range(3, 10):
    px[7, y] = COL["D"]; px[8, y] = COL["D"]
os.makedirs(os.path.join(A, "textures", "item"), exist_ok=True)
im.save(os.path.join(A, "textures", "item", "remote_model.png"))

# ---------- model ----------
def uv(reg):
    return list(REG[reg])

def elem(frm, to, faces):
    return {"from": frm, "to": to, "faces": {f: {"uv": uv(r), "texture": "#0"} for f, r in faces.items()}}

def button(frm, to, top, side):
    # front face (south, +Z) in the button color; sides shaded; the back is inside the body (not drawn)
    return elem(frm, to, {"south": top, "up": side, "down": side, "east": side, "west": side})

elements = [
    # body 4x12x1.5
    elem([6, 2, 7.25], [10, 14, 8.75], {"south": "front", "north": "back", "east": "side", "west": "side", "up": "cap", "down": "cap"}),
    # rounded ends
    elem([6.5, 14, 7.5], [9.5, 14.5, 8.5], {"up": "cap", "north": "capside", "south": "capside", "east": "capside", "west": "capside"}),
    elem([6.5, 1.5, 7.5], [9.5, 2, 8.5], {"down": "cap", "north": "capside", "south": "capside", "east": "capside", "west": "capside"}),
    # infrared window (on top)
    elem([7.5, 14.5, 7.75], [8.5, 14.75, 8.25], {"up": "ir", "north": "ir", "south": "ir", "east": "ir", "west": "ir"}),
    # power button
    button([7, 12.25, 8.75], [9, 13.25, 9.25], "power", "powerside"),
    # D-pad: horizontal, vertical, center
    button([6.5, 8.5, 8.75], [9.5, 9.5, 9.1], "gray", "grayside"),
    button([7.5, 7.5, 8.75], [8.5, 10.5, 9.12], "gray", "grayside"),
    button([7.5, 8.5, 9.12], [8.5, 9.5, 9.3], "center", "grayside"),
    # colored shortcuts
    button([6.5, 5.5, 8.75], [7.5, 6.5, 9.15], "blue", "blueside"),
    button([8.5, 5.5, 8.75], [9.5, 6.5, 9.15], "green", "greenside"),
    # bottom row
    button([6.5, 3.5, 8.75], [7.25, 4.25, 9.1], "dgray", "dgrayside"),
    button([7.625, 3.5, 8.75], [8.375, 4.25, 9.1], "dgray", "dgrayside"),
    button([8.75, 3.5, 8.75], [9.5, 4.25, 9.1], "dgray", "dgrayside"),
]
model = {
    "credit": "doomscroll",
    "texture_size": [16, 16],
    "gui_light": "front",
    "textures": {"0": "doomscroll:item/remote_model", "particle": "doomscroll:item/remote_model"},
    "elements": elements,
    "display": {
        # first person: toward the screen, slightly up and toward the center (the left hand is mirrored automatically)
        "firstperson_righthand": {"rotation": [-60, 0, 20], "translation": [0, 3, -1], "scale": [0.8, 0.8, 0.8]},
        # third person: in the fist, pointing forward, slightly up (model +Y = forward)
        "thirdperson_righthand": {"rotation": [15, 0, 0], "translation": [0, 3, 0], "scale": [0.8, 0.8, 0.8]},
        "gui": {"rotation": [-67.5, 0, 45], "scale": [1.35, 1.35, 1.35]},
        "ground": {"rotation": [90, 0, 0], "scale": [0.7, 0.7, 0.7]},
        "fixed": {"translation": [0, 0, -1.5], "scale": [1.5, 1.5, 1.5]},
        "head": {"rotation": [90, 0, 0], "translation": [0, 4, -8], "scale": [1, 1, 1]},
    },
}
os.makedirs(os.path.join(A, "models", "item"), exist_ok=True)
with open(os.path.join(A, "models", "item", "remote.json"), "w", encoding="utf-8") as f:
    json.dump(model, f, indent=1)
print("remote_model.png and models/item/remote.json written:", len(elements), "elements")
