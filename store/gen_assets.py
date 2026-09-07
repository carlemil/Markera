"""Generate the store graphics and the launcher mipmaps from one drawing.

    python store/gen_assets.py

Writes store/icon-512.png, store/feature-1024x500.png and the legacy
ic_launcher(.round).webp mipmaps (API < 26; API 26+ uses the adaptive vector
in res/drawable, drawn to match). Design: dark ground (#12160F), green rings
(#9CCC65), bright centre (#00E676) — the app's theme.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "composeApp/src/androidMain/res"
OUT = ROOT / "store"

BG = (0x12, 0x16, 0x0F)
RING = (0x9C, 0xCC, 0x65)
CENTRE = (0x00, 0xE6, 0x76)
TEXT = (0xE6, 0xEA, 0xD9)


def target(size: int, pad: float = 0.0, background=BG) -> Image.Image:
    """The icon at [size] px, drawn 4x and downsampled for smooth rings."""
    s = size * 4
    img = Image.new("RGBA", (s, s), background + (255,) if background else (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = s / 2
    r_max = s / 2 * (1 - pad)
    stroke = max(2, int(s * 0.035))
    for f in (1.0, 0.72, 0.44):
        r = r_max * f
        d.ellipse((c - r, c - r, c + r, c + r), outline=RING, width=stroke)
    r = r_max * 0.17
    d.ellipse((c - r, c - r, c + r, c + r), fill=CENTRE)
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
    print("wrote", OUT / "icon-512.png", OUT / "feature-1024x500.png", "and the mipmaps")


if __name__ == "__main__":
    main()
