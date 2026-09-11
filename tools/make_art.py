"""Doomscroll piksel sanati: blok/esya dokulari, GUI simgeleri ve mod simgesi (vanilla paletine yakin)."""
import os
from PIL import Image  # pip install pillow

A = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "doomscroll")
TEX = A + "/textures"

PAL = {
    ".": None,
    "K": (11, 11, 14, 255),      # kontur
    "D": (30, 30, 35, 255),      # koyu govde / golge
    "B": (44, 44, 51, 255),      # govde
    "L": (61, 61, 70, 255),      # acik govde
    "H": (89, 89, 99, 255),      # vurgu
    "G": (154, 154, 166, 255),   # acik gri (tus)
    "g": (110, 110, 122, 255),   # orta gri
    "W": (232, 232, 238, 255),   # beyaz
    "R": (217, 67, 59, 255),     # kirmizi (guc)
    "r": (140, 35, 31, 255),     # koyu kirmizi
    "A": (63, 141, 224, 255),    # mavi
    "a": (34, 88, 154, 255),     # koyu mavi
    "E": (79, 191, 107, 255),    # yesil
    "e": (39, 110, 58, 255),     # koyu yesil
    "S": (7, 8, 12, 255),        # ekran cami
    "s": (18, 21, 31, 255),      # cam parlama
    "t": (13, 15, 22, 255),      # cam ust ton
    "u": (4, 5, 8, 255),         # cam alt ton
    "Y": (226, 176, 74, 255),    # sari LED
}


def paint(rows, path, scale=1):
    h = len(rows)
    w = len(rows[0])
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = im.load()
    for y, row in enumerate(rows):
        assert len(row) == w, "satir genisligi: " + path + " satir " + str(y)
        for x, ch in enumerate(row):
            c = PAL[ch]
            if c is not None:
                px[x, y] = c
    if scale != 1:
        im = im.resize((w * scale, h * scale), Image.NEAREST)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)
    print("yazildi", os.path.relpath(path, A), im.size)


# ---------------- ekran blogu ----------------
screen_front = [
    "KKKKKKKKKKKKKKKK",
    "KHLLLLLLLLLLLLBK",
    "KLtttttttttttttDK"[:16],
]
screen_front = [
    "KKKKKKKKKKKKKKKK",
    "KHLLLLLLLLLLLLBK",
    "KLttttttttttttDK",
    "KLsSttttttttttDK",
    "KLSsSSSSSSSSSSDK",
    "KLSSSSSSSSSSSSDK",
    "KLSSSSSSSSSSSSDK",
    "KLSSSSSSSSSSSSDK",
    "KLSSSSSSSSSSSSDK",
    "KLSSSSSSSSSSSSDK",
    "KLuuuuuuuuuuuuDK",
    "KLuuuuuuuuuuuuDK",
    "KLBBBBBBBBBBBBDK",
    "KLBBBBBBBBBRBBDK",
    "KBDDDDDDDDDDDDDK",
    "KKKKKKKKKKKKKKKK",
]
paint(screen_front, TEX + "/block/screen.png")

# Kapali panel: renderer tum paneli tek dortgenle kaplar; desen gerilmesin diye duz cam
paint(["S" * 16] * 16, TEX + "/block/screen_off.png")

screen_side = [
    "DDDDDDDDDDDDDDDD",
    "DHHHHHHHHHHHHHHD",
    "DLLLLLLLLLLLLLLD",
    "DBBBBBBBBBBBBBBD",
    "DBBBBBBBBBBBBBBD",
    "DBDDDDDDDDDDDDBD",
    "DBBBBBBBBBBBBBBD",
    "DBDDDDDDDDDDDDBD",
    "DBBBBBBBBBBBBBBD",
    "DBDDDDDDDDDDDDBD",
    "DBBBBBBBBBBBBBBD",
    "DBBBBBBBBBBBBBBD",
    "DBBBBBBBBBBBBBBD",
    "DDDDDDDDDDDDDDDD",
    "DKKKKKKKKKKKKKKD",
    "DDDDDDDDDDDDDDDD",
]
paint(screen_side, TEX + "/block/screen_side.png")

screen_back = [
    "DDDDDDDDDDDDDDDD",
    "DLLLLLLLLLLLLLLD",
    "DLBBBBBBBBBBBBDD",
    "DLBDBDBDBDBDBBDD",
    "DLBDBDBDBDBDBBDD",
    "DLBDBDBDBDBDBBDD",
    "DLBDBDBDBDBDBBDD",
    "DLBBBBBBBBBBBBDD",
    "DLBBBBBBBBBBBBDD",
    "DLBKKKKBBKKBBBDD",
    "DLBKAAKBBKKBBBDD",
    "DLBKKKKBBKKBBBDD",
    "DLBBBBBBBBBBBBDD",
    "DLBBBBBBBBBBBBDD",
    "DDDDDDDDDDDDDDDD",
    "DDDDDDDDDDDDDDDD",
]
paint(screen_back, TEX + "/block/screen_back.png")

# ---------------- kumanda ----------------
remote_icon = [
    "....KKKKKKK.....",
    "...KHLLLLLDK....",
    "...KLBRRBBDK....",
    "...KLBrrBBDK....",
    "...KLBBBBBDK....",
    "...KLBGGGBDK....",
    "...KLGGWGGDK....",
    "...KLBGGGBDK....",
    "...KLBBBBBDK....",
    "...KLABBEBDK....",
    "...KLaBBeBDK....",
    "...KLBBBBBDK....",
    "...KLgBgBgDK....",
    "...KLBBBBBDK....",
    "...KDgggggDK....",
    "....KKKKKKK.....",
]
paint(remote_icon, TEX + "/item/remote_icon.png")

# 3B el modeli dokusu (remote_model.png) tools/make_remote_model.py ile uretilir.

# ---------------- tablet ----------------
tablet_icon = [
    "................",
    "................",
    ".KKKKKKKKKKKKKK.",
    "KHLLLLLLgLLLLLBK",
    "KLttttttttttttDK",
    "KLsSttttttttttDK",
    "KLSsSSSSSSSSSSDK",
    "KLSSSSSSSSSSSSDK",
    "KLSSSSSSSSSSSSDK",
    "KLuuuuuuuuuuuuDK",
    "KLBBBBBBBBBBBBDK",
    "KBDDDDDDgDDDDDDK",
    ".KKKKKKKKKKKKKK.",
    "................",
    "................",
    "................",
]
paint(tablet_icon, TEX + "/item/tablet_icon.png")

# ---------------- GUI simgeleri (9x9, beyaz; cizimde renklendirilir) ----------------
ICONS = {
    "power": [
        "....W....",
        "....W....",
        "..W.W.W..",
        ".W.....W.",
        "W.......W",
        "W.......W",
        ".W.....W.",
        "..WWWWW..",
        ".........",
    ],
    "speaker": [
        ".........",
        "....W....",
        "...WW.W..",
        ".WWWW.W.W",
        ".WWWW.W.W",
        ".WWWW.W.W",
        "...WW.W..",
        "....W....",
        ".........",
    ],
    "speaker_off": [
        ".........",
        "....W....",
        "...WW....",
        ".WWWW.W.W",
        ".WWWW..W.",
        ".WWWW.W.W",
        "...WW....",
        "....W....",
        ".........",
    ],
    "back": [
        ".........",
        "...W.....",
        "..WW.....",
        ".WWWWWWW.",
        "WWWWWWWWW",
        ".WWWWWWW.",
        "..WW.....",
        "...W.....",
        ".........",
    ],
    "forward": [
        ".........",
        ".....W...",
        ".....WW..",
        ".WWWWWWW.",
        "WWWWWWWWW",
        ".WWWWWWW.",
        ".....WW..",
        ".....W...",
        ".........",
    ],
    "reload": [
        "...WWW...",
        ".WW...WWW",
        ".W.....WW",
        "W.......W",
        "W........",
        "W........",
        ".W.....W.",
        ".WW...WW.",
        "...WWW...",
    ],
    "home": [
        "....W....",
        "...WWW...",
        "..WWWWW..",
        ".WWWWWWW.",
        "WWWWWWWWW",
        "..WWWWW..",
        "..WW.WW..",
        "..WW.WW..",
        "..WW.WW..",
    ],
    "cinema": [
        "WWW...WWW",
        "W.......W",
        "W.......W",
        ".........",
        ".........",
        ".........",
        "W.......W",
        "W.......W",
        "WWW...WWW",
    ],
    "plus": [
        ".........",
        "...WWW...",
        "...WWW...",
        "...WWW...",
        "WWWWWWWWW",
        "...WWW...",
        "...WWW...",
        "...WWW...",
        ".........",
    ],
    "to_tablet": [
        ".....WWWW",
        ".....W..W",
        "W....W..W",
        "WW...W..W",
        "WWW..W..W",
        "WW...W..W",
        "W....W..W",
        ".....W..W",
        ".....WWWW",
    ],
    "cast": [
        ".........",
        ".........",
        "W...WWWWW",
        "WW..W...W",
        "WWW.W...W",
        "WW..W...W",
        "W...WWWWW",
        "......W..",
        ".....WWW.",
    ],
    "star": [
        ".WWWWWWW.",
        ".W.....W.",
        ".W.....W.",
        ".W.....W.",
        ".W.....W.",
        ".W.....W.",
        ".W..W..W.",
        ".W.W.W.W.",
        ".WW...WW.",
    ],
    "star_filled": [
        ".WWWWWWW.",
        ".WWWWWWW.",
        ".WWWWWWW.",
        ".WWWWWWW.",
        ".WWWWWWW.",
        ".WWWWWWW.",
        ".WWW.WWW.",
        ".WW...WW.",
        ".W.....W.",
    ],
    "menu": [
        ".........",
        ".WWWWWWW.",
        ".........",
        ".WWWWWWW.",
        ".........",
        ".WWWWWWW.",
        ".........",
        ".WWWWWWW.",
        ".........",
    ],
    "close": [
        ".........",
        ".W.....W.",
        ".WW...WW.",
        "..WW.WW..",
        "...WWW...",
        "..WW.WW..",
        ".WW...WW.",
        ".W.....W.",
        ".........",
    ],
    "play": [
        ".W.......",
        ".WW......",
        ".WWW.....",
        ".WWWW....",
        ".WWWWW...",
        ".WWWW....",
        ".WWW.....",
        ".WW......",
        ".W.......",
    ],
    "trash": [
        "...WWW...",
        ".WWWWWWW.",
        ".........",
        ".WWWWWWW.",
        ".W.W.W.W.",
        ".W.W.W.W.",
        ".W.W.W.W.",
        ".W.....W.",
        ".WWWWWWW.",
    ],
    "clock": [
        "...WWW...",
        ".WW...WW.",
        ".W..W..W.",
        "W...W...W",
        "W...WWW.W",
        "W.......W",
        ".W.....W.",
        ".WW...WW.",
        "...WWW...",
    ],
    "queue": [
        ".WWWWWWW.",
        ".........",
        ".WWWWWWW.",
        ".........",
        ".WWWWW.W.",
        "......WW.",
        ".WWWWWWWW",
        "......WW.",
        ".WWWWW.W.",
    ],
    "tv": [
        "WWWWWWWWW",
        "W.......W",
        "W.......W",
        "W.......W",
        "W.......W",
        "W.......W",
        "WWWWWWWWW",
        "...W.W...",
        "..WWWWW..",
    ],
    "search": [
        "..WWW....",
        ".W...W...",
        ".W...W...",
        ".W...W...",
        "..WWW....",
        "....WW...",
        ".....WW..",
        "......WW.",
        ".........",
    ],
    "go": [
        ".........",
        ".....W...",
        ".....WW..",
        ".WWWWWWW.",
        "WWWWWWWWW",
        ".WWWWWWW.",
        ".....WW..",
        ".....W...",
        ".........",
    ],
    "lock": [
        "...WWW...",
        "..W...W..",
        "..W...W..",
        ".WWWWWWW.",
        ".W.....W.",
        ".W..W..W.",
        ".W..W..W.",
        ".W.....W.",
        ".WWWWWWW.",
    ],
    "broadcast": [
        ".........",
        "..W...W..",
        ".W.....W.",
        "W...W...W",
        "W..WWW..W",
        "W...W...W",
        ".W.....W.",
        "..W...W..",
        ".........",
    ],
}
for name, rows in ICONS.items():
    paint(rows, TEX + "/gui/sprites/icon/" + name + ".png")

# ---------------- mod simgesi (16x16 piksel sanati, 8x buyutulmus) ----------------
mod_icon = [
    "................",
    ".KKKKKKKKKKKKKK.",
    "KHLLLLLLLLLLLLBK",
    "KLSSSSSSSSSSSSDK",
    "KLSSSWSSSSSSSSDK",
    "KLSSSWWSSSSSSSDK",
    "KLSSSWWWSSSSSSDK",
    "KLSSSWWWWSSSSSDK",
    "KLSSSWWWSSSSSSDK",
    "KLSSSWWSSSSSSSDK",
    "KLSSSWSSSSSSSSDK",
    "KLSSSSSSSSSSSSDK",
    "KLBBBBBBBBBEBBDK",
    "KBDDDDDDDDDDDDDK",
    ".KKKKKKKKKKKKKK.",
    "....KKKKKKKK....",
]
paint(mod_icon, A + "/icon.png", scale=32)  # Modrinth 512x512 istiyor
print("tamam")
