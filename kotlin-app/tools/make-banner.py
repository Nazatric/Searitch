#!/usr/bin/env python3
"""Renders docs/banner.png from the app's own materials.

    python3 tools/make-banner.py

Nothing here is invented: the hide, the paper, the brass and the stitching are
the same assets and the same palette the app paints at runtime, and the
wordmark uses the bundled Cinzel. The banner therefore cannot drift away from
the app it advertises.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "composeApp/src/commonMain/composeResources"
LEATHER = RES / "drawable/leather_grain.png"
PAPER = RES / "drawable/paper_grain.png"
CINZEL = RES / "font/cinzel_bold.ttf"
ICON = ROOT / "assets-src/icon-src.png"
OUT = ROOT.parent / "docs/banner.png"

# docs/banner.png sits at the top of the README; docs/social-preview.png is the
# 1280x640 card GitHub shows when the repo is linked. Same materials, same type.
SIZES = {
    "banner.png": (1280, 420),
    "social-preview.png": (1280, 640),
}
W, H = SIZES["banner.png"]

# palette (Theme.kt, "stocked hide / brass / cream")
LEATHER_BASE = (0x43, 0x22, 0x1A)
LEATHER_DARK = (0x2A, 0x14, 0x0E)
CREAM = (0xF3, 0xE6, 0xCB)
CREAM_SOFT = (0xD8, 0xC3, 0xA2)
BRASS_LIGHT = (0xE3, 0xC2, 0x77)
BRASS = (0xC6, 0x9B, 0x3F)
BRASS_DEEP = (0x8A, 0x66, 0x24)
THREAD = (0xD9, 0xB1, 0x6A)


def tiled(path: Path, size: tuple[int, int]) -> Image.Image:
    """Tiles a seamless texture to cover `size`, as the shader does at runtime."""
    tex = Image.open(path).convert("RGB")
    out = Image.new("RGB", size)
    for y in range(0, size[1], tex.height):
        for x in range(0, size[0], tex.width):
            out.paste(tex, (x, y))
    return out


def leather_field() -> Image.Image:
    """Base colour + grain through an overlay blend, exactly as leatherSurface does."""
    grain = tiled(LEATHER, (W, H)).convert("RGB")
    base = Image.new("RGB", (W, H), LEATHER_BASE)
    out = ImageChops.overlay(base, grain)
    # page sheen: warm highlight at the top, deep shadow at the foot
    sheen = Image.new("L", (1, H))
    for y in range(H):
        t = y / (H - 1)
        sheen.putpixel((0, y), int(255 * (0.10 * (1 - t) ** 3 + 0.42 * t**2.2)))
    sheen = sheen.resize((W, H))
    out = Image.composite(Image.new("RGB", (W, H), LEATHER_DARK), out, sheen)
    # a soft top light, like the sheen on a stretched hide
    glow = Image.new("L", (1, H))
    for y in range(H):
        glow.putpixel((0, y), max(0, int(52 * (1 - y / (H * 0.55)))))
    out = Image.composite(Image.new("RGB", (W, H), (0xB6, 0x7A, 0x5A)), out, glow.resize((W, H)))
    return out


def stitches(draw: ImageDraw.ImageDraw, y: int, inset: int = 34) -> None:
    """The slanted saddle stitch used by Modifier.stitchRow()."""
    step, length, thick = 17, 12, 3
    dx, dy = length * 0.91, length * 0.40
    x = inset
    while x + dx <= W - inset:
        draw.line([(x, y + dy / 2), (x + dx, y - dy / 2)], fill=THREAD, width=thick)
        draw.line(
            [(x, y + dy / 2 + 2), (x + dx, y - dy / 2 + 2)],
            fill=(0, 0, 0, 90),
            width=thick,
        )
        x += step


def brass_ring(size: int, width: int) -> Image.Image:
    """A brass ring with a lit top edge and a dark bottom edge."""
    ring = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(ring)
    for i in range(width):
        t = i / max(1, width - 1)
        shade = tuple(
            int(a + (b - a) * abs(0.5 - t) * 2)
            for a, b in zip(BRASS_DEEP, BRASS_LIGHT)
        )
        inset = i
        draw.ellipse(
            [inset, inset, size - 1 - inset, size - 1 - inset],
            outline=shade,
            width=1,
        )
    return ring


def compose() -> Image.Image:
    img = leather_field().convert("RGBA")
    # taller canvases (the social card) keep the lock-up centred
    oy = max(0, (H - 420) // 2)

    # stitched border, top and bottom
    d = ImageDraw.Draw(img)
    stitches(d, 18, inset=28)
    stitches(d, H - 12, inset=28)

    # brass hairline above the top stitch line
    d.line([(28, 30), (W - 28, 30)], fill=BRASS_DEEP, width=1)

    # medallion: the user's magnifier photo, seated in a brass ring
    if ICON.is_file():
        dia = 216
        pic = Image.open(ICON).convert("RGB").resize((dia, dia), Image.LANCZOS)
        mask = Image.new("L", (dia, dia), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, dia - 1, dia - 1], fill=255)
        sx, sy = 74, (H - dia) // 2
        # soft shadow under the disc
        shadow = Image.new("RGBA", (dia + 60, dia + 60), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).ellipse([20, 24, dia + 20, dia + 24], fill=(0, 0, 0, 120))
        img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14)), (sx - 30, sy - 30))
        img.paste(pic, (sx, sy), mask)
        ring = brass_ring(dia + 14, 7)
        img.alpha_composite(ring, (sx - 7, sy - 7))

    # wordmark
    text_x = 74 + 216 + 56
    title = ImageFont.truetype(str(CINZEL), 92)
    tagline_font = ImageFont.truetype(str(CINZEL), 27)

    def tracked(draw: ImageDraw.ImageDraw, xy, text, font, fill, tracking):
        x, y = xy
        for ch in text:
            draw.text((x, y), ch, font=font, fill=fill)
            x += draw.textlength(ch, font=font) + tracking

    shadow_title = Image.new("RGBA", img.size, (0, 0, 0, 0))
    td = ImageDraw.Draw(shadow_title)
    tracked(td, (text_x, 152 + oy), "SEARITCH", title, (0, 0, 0, 170), 9)
    img.alpha_composite(shadow_title.filter(ImageFilter.GaussianBlur(5)), (0, 3))

    d = ImageDraw.Draw(img)
    tracked(d, (text_x, 152 + oy), "SEARITCH", title, CREAM, 9)

    # brass rule under the wordmark
    d.line([(text_x + 4, 272 + oy), (text_x + 4 + 268, 272 + oy)], fill=BRASS, width=2)
    d.line([(text_x + 4, 275 + oy), (text_x + 4 + 268, 275 + oy)], fill=(0, 0, 0, 90), width=1)

    tracked(d, (text_x + 4, 296 + oy), "IMAGE SEARCH · THIRTEEN SOURCES · NO KEYS", tagline_font, CREAM_SOFT, 3.4)

    return img


def main() -> int:
    if not LEATHER.is_file() or not CINZEL.is_file():
        raise SystemExit("missing textures or Cinzel")
    global W, H
    for name, (w, h) in SIZES.items():
        W, H = w, h
        path = OUT.parent / name
        path.parent.mkdir(parents=True, exist_ok=True)
        compose().convert("RGB").save(path, optimize=True)
        print(f"{path}  {w}x{h}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
