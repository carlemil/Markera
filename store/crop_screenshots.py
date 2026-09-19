"""Crop phone screencaps (1440x3216) to Play's limit (height <= 2x width): drop the
status bar and the navigation bar, keep the top 2880 px. Usage:
    python store/crop_screenshots.py <locale> <in.png>...   (locale = en-US, sv-SE)
-> writes store/screenshots/<locale>/NN-name.png and copies them into that Play
listing's phone-screenshots dir."""
import shutil, sys
from pathlib import Path
from PIL import Image
ROOT = Path(__file__).resolve().parent.parent
LOCALE = sys.argv[1]
OUT = ROOT / "store/screenshots" / LOCALE
GRAPHICS = ROOT / "composeApp/src/main/play/listings" / LOCALE / "graphics"
PLAY = GRAPHICS / "phone-screenshots"
OUT.mkdir(parents=True, exist_ok=True)
PLAY.mkdir(parents=True, exist_ok=True)
# Play wants tablet shots too (9:16, 7-inch >= 320 px, 10-inch >= 1080 px per side):
# the phone crop centred on a 9:16 canvas in the app's background colour.
TABLETS = {"tablet-screenshots": (1620, 2880), "large-tablet-screenshots": (2160, 3840)}
TOP = 110  # status bar
for i, src in enumerate(sys.argv[2:], 1):
    im = Image.open(src)
    w, h = im.size
    im = im.crop((0, TOP, w, min(h, TOP + 2 * w)))
    name = f"{i:02d}-{Path(src).stem}.png"
    im.save(OUT / name)
    shutil.copy(OUT / name, PLAY / f"{i}.png")
    print(name, im.size)
    for sub, size in TABLETS.items():
        canvas = Image.new("RGB", size, im.getpixel((5, 5)))
        canvas.paste(im, ((size[0] - im.width) // 2, (size[1] - im.height) // 2))
        (GRAPHICS / sub).mkdir(exist_ok=True)
        canvas.save(GRAPHICS / sub / f"{i}.png")
