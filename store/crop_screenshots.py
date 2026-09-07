"""Crop phone screencaps (1440x3216) to Play's limit (height <= 2x width): drop the
status bar and the navigation bar, keep the top 2880 px. Usage:
    python store/crop_screenshots.py <in.png>... -> writes store/screenshots/NN-name.png
and copies them into the Play listing's phone-screenshots dir."""
import shutil, sys
from pathlib import Path
from PIL import Image
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "store/screenshots"
PLAY = ROOT / "composeApp/src/main/play/listings/sv-SE/graphics/phone-screenshots"
TOP = 110  # status bar
for i, src in enumerate(sys.argv[1:], 1):
    im = Image.open(src)
    w, h = im.size
    im = im.crop((0, TOP, w, min(h, TOP + 2 * w)))
    name = f"{i:02d}-{Path(src).stem}.png"
    im.save(OUT / name)
    shutil.copy(OUT / name, PLAY / f"{i}.png")
    print(name, im.size)
