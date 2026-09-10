"""Generate the store graphics and the launcher icons from one drawing.

    python store/gen_assets.py

Writes store/icon-512.png, store/feature-1024x500.png, the iOS
AppIcon.appiconset/icon-1024.png, the adaptive-icon foreground bitmaps
(mipmap-*/ic_launcher_foreground.webp — a bitmap because a VectorDrawable
cannot draw the ring digits) and the legacy ic_launcher(.round).webp mipmaps
(API < 26). Design: the Statistik target zoomed in — paper edge to edge, the
black centre 55 % of the icon width, every ring line with the outer ones
cropped by the icon shape, ring digits on the four arms, and five black holes
low left on the paper.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "composeApp/src/androidMain/res"
OUT = ROOT / "store"
IOS_ICON = ROOT / "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"

BG = (0x12, 0x16, 0x0F)
CENTRE = (0x00, 0xE6, 0x76)
TEXT = (0xE6, 0xEA, 0xD9)
# Colours from ui/stats/StatsScreen.kt.
PAPER = (0xE8, 0xDE, 0xC8)
BLACK = (0x15, 0x15, 0x1A)
LINE_ON_BLACK = (0xED, 0xED, 0xED)
LINE_ON_PAPER = (0x6B, 0x64, 0x55)
HOLE_OUTLINE = (0x6A, 0x6A, 0x70)
BLACK_MM = 100.0
RINGS_MM = [12.5] + [25.0 * i for i in range(1, 11)]  # inner ten + rings 10..1
BLACK_FRAC = 0.55  # black diameter / icon width
# (x mm, y mm): a five-shot group low left in rings 5-6.
HOLES = [(-58, 104), (-98, 60), (-13, 140), (-99, 125), (-139, 92)]
HOLE_MM = 7.0
DIGIT_MM = 11.0
FONT = "C:/Windows/Fonts/segoeui.ttf"


def target(size: int, black_frac: float = BLACK_FRAC) -> Image.Image:
    """The full-bleed icon at [size] px, drawn 4x and downsampled."""
    s = size * 4
    img = Image.new("RGB", (s, s), PAPER)
    d = ImageDraw.Draw(img)
    c = s / 2
    k = s / 2 * black_frac / BLACK_MM  # px per mm

    def disc(x, y, r_mm, **kw):
        r = r_mm * k
        d.ellipse((c + x * k - r, c + y * k - r, c + x * k + r, c + y * k + r), **kw)

    disc(0, 0, BLACK_MM, fill=BLACK)
    line = max(2, int(s * 0.005))
    for mm in RINGS_MM:
        disc(0, 0, mm, outline=LINE_ON_BLACK if mm <= BLACK_MM else LINE_ON_PAPER, width=line)
    font = ImageFont.truetype(FONT, int(DIGIT_MM * k * 1.35))
    for ring in range(1, 10):
        mid = (21 - 2 * ring) * 12.5  # centre of the ring's band
        colour = LINE_ON_BLACK if mid <= BLACK_MM else LINE_ON_PAPER
        for x, y in ((-mid, 0), (mid, 0), (0, -mid), (0, mid)):
            d.text((c + x * k, c + y * k), str(ring), fill=colour, font=font, anchor="mm")
    for x, y in HOLES:
        disc(x, y, HOLE_MM, fill=BLACK, outline=HOLE_OUTLINE, width=line)
    return img.resize((size, size), Image.LANCZOS)


def rounded(img: Image.Image, radius: float) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius, fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


def circle(img: Image.Image) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, img.width - 1, img.height - 1), fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out


DENSITIES = (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4))


def main() -> None:
    OUT.mkdir(exist_ok=True)
    # Play store icon: 512 px, opaque, no rounding (Play masks it).
    target(512).save(OUT / "icon-512.png")

    # iOS app icon: 1024 px, no alpha and no rounding (iOS masks it, and the
    # App Store rejects an icon with an alpha channel). Contents.json is
    # checked in by hand next to it.
    IOS_ICON.mkdir(parents=True, exist_ok=True)
    target(1024).save(IOS_ICON / "icon-1024.png")

    # Feature graphic 1024x500.
    fg = Image.new("RGB", (1024, 500), BG)
    icon = rounded(target(300), 300 * 0.22)
    fg.paste(icon, (90, 100), icon)
    d = ImageDraw.Draw(fg)
    title = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 110)
    sub = ImageFont.truetype(FONT, 40)
    d.text((440, 150), "Markera", font=title, fill=CENTRE)
    d.text((444, 290), "Räkna träffarna med kameran", font=sub, fill=TEXT)
    fg.save(OUT / "feature-1024x500.png")

    for density, scale in DENSITIES:
        folder = RES / f"mipmap-{density}"
        folder.mkdir(exist_ok=True)
        # Adaptive foreground: the 108 dp canvas, of which the launcher shows
        # the middle 72 dp, so the black scales down by 72/108 to look like
        # the store icon after masking.
        px = int(108 * scale)
        target(px, BLACK_FRAC * 72 / 108).save(folder / "ic_launcher_foreground.webp", lossless=True)
        # Legacy launcher icons (pre-26 devices): rounded square + circle.
        px = int(48 * scale)
        base = target(px)
        rounded(base, px * 0.18).save(folder / "ic_launcher.webp", lossless=True)
        circle(base).save(folder / "ic_launcher_round.webp", lossless=True)
    print("wrote", OUT / "icon-512.png", OUT / "feature-1024x500.png", IOS_ICON / "icon-1024.png", "and the mipmaps")


if __name__ == "__main__":
    main()
