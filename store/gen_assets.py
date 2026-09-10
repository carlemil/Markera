"""Generate the store graphics and the launcher mipmaps from one drawing.

    python store/gen_assets.py

Writes store/icon-512.png, store/feature-1024x500.png, the iOS
AppIcon.appiconset/icon-1024.png and the legacy ic_launcher(.round).webp
mipmaps (API < 26; API 26+ uses the adaptive vector in res/drawable, drawn to
match). Design: the Statistik target (paper, black centre, ring lines, coloured
hits) on the theme's dark ground (#12160F).
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
# The Statistik target (ui/stats/StatsScreen.kt): paper out to ring 5, black to
# the 6/7 edge, ring lines every 25 mm, hits in the date scale's colours.
PAPER = (0xE8, 0xDE, 0xC8)
BLACK = (0x15, 0x15, 0x1A)
LINE_ON_BLACK = (0xED, 0xED, 0xED)
LINE_ON_PAPER = (0x6B, 0x64, 0x55)
PLOT_RADIUS_MM = 150.0
BLACK_RADIUS_MM = 100.0
RING_LINES_MM = (12.5, 25.0, 50.0, 75.0, 125.0)
# (x mm, y mm, colour) — a five-shot group around the ten, oldest to newest.
HITS = (
    (-18, 12, (0x7C, 0x4D, 0xFF)),
    (24, -16, (0x00, 0xB0, 0xFF)),
    (6, 30, (0x00, 0xE5, 0xCC)),
    (-34, -26, (0xFF, 0xD6, 0x00)),
    (38, 34, (0xFF, 0x3D, 0x00)),
)
HIT_RADIUS_MM = 8.0


def target(size: int, pad: float = 0.0, background=BG) -> Image.Image:
    """The icon at [size] px, drawn 4x and downsampled for smooth rings."""
    s = size * 4
    img = Image.new("RGBA", (s, s), background + (255,) if background else (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = s / 2
    k = s / 2 * (1 - pad) / PLOT_RADIUS_MM  # px per mm

    def disc(x, y, r_mm, **kw):
        r = r_mm * k
        d.ellipse((c + x * k - r, c + y * k - r, c + x * k + r, c + y * k + r), **kw)

    disc(0, 0, PLOT_RADIUS_MM, fill=PAPER)
    disc(0, 0, BLACK_RADIUS_MM, fill=BLACK)
    line = max(2, int(s * 0.006))
    for mm in RING_LINES_MM:
        disc(0, 0, mm, outline=LINE_ON_BLACK if mm <= BLACK_RADIUS_MM else LINE_ON_PAPER, width=line)
    for x, y, colour in HITS:
        disc(x, y, HIT_RADIUS_MM, fill=colour, outline=BLACK, width=line)
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


def main() -> None:
    OUT.mkdir(exist_ok=True)
    # Play store icon: 512 px, opaque, no rounding (Play masks it).
    target(512, pad=0.18).convert("RGB").save(OUT / "icon-512.png")

    # iOS app icon: 1024 px, no alpha and no rounding (iOS masks it, and the
    # App Store rejects an icon with an alpha channel). Contents.json is
    # checked in by hand next to it.
    IOS_ICON.mkdir(parents=True, exist_ok=True)
    target(1024, pad=0.18).convert("RGB").save(IOS_ICON / "icon-1024.png")

    # Feature graphic 1024x500.
    fg = Image.new("RGB", (1024, 500), BG)
    icon = target(300, pad=0.12)
    fg.paste(icon, (90, 100), icon)
    d = ImageDraw.Draw(fg)
    title = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 110)
    sub = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 40)
    d.text((440, 150), "Markera", font=title, fill=CENTRE)
    d.text((444, 290), "Räkna träffarna med kameran", font=sub, fill=TEXT)
    fg.save(OUT / "feature-1024x500.png")

    # Legacy launcher mipmaps (pre-26 devices): rounded square + circle.
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        folder = RES / f"mipmap-{density}"
        folder.mkdir(exist_ok=True)
        base = target(px, pad=0.2)
        rounded(base, px * 0.18).save(folder / "ic_launcher.webp", lossless=True)
        circle(base).save(folder / "ic_launcher_round.webp", lossless=True)
    print(
        "wrote",
        OUT / "icon-512.png",
        OUT / "feature-1024x500.png",
        IOS_ICON / "icon-1024.png",
        "and the mipmaps",
    )


if __name__ == "__main__":
    main()
