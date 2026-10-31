#!/usr/bin/env python3
"""Builds the Android launcher icon from assets-src/icon-src.png.

    python3 tools/make-icon.py

Produces, in composeApp/src/androidMain/res/:
  mipmap-mdpi..xxxhdpi/ic_launcher.png        legacy square ic_launcher
  mipmap-mdpi..xxxhdpi/ic_launcher_round.png  legacy round icon
  mipmap-anydpi-v26/ic_launcher.xml           adaptive icon (API 26+)

The adaptive foreground is the source artwork scaled to the 66% safe zone so the
magnifier never clips against a launcher's mask, on top of the same leather.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "assets-src/icon-src.png"
RES = ROOT / "composeApp/src/androidMain/res"

# legacy launcher sizes (px)
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# adaptive foreground: 108dp canvas
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}



def rounded(img: Image.Image, radius_ratio: float) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, img.size[0] - 1, img.size[1] - 1],
        radius=int(img.size[0] * radius_ratio),
        fill=255,
    )
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def circle(img: Image.Image) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse([0, 0, img.size[0] - 1, img.size[1] - 1], fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def main() -> int:
    if not SRC.is_file():
        raise SystemExit(f"missing {SRC}")
    src = Image.open(SRC).convert("RGB")

    for density, size in LEGACY.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        square = src.resize((size, size), Image.LANCZOS)
        rounded(square, 0.22).save(d / "ic_launcher.png")
        circle(square).save(d / "ic_launcher_round.png")
        print(f"mipmap-{density}/ic_launcher.png  {size}px")

    for density, size in ADAPTIVE.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        canvas = Image.new("RGB", (size, size))
        # background layer: the leather, slightly darkened so the brass pops
        bg = src.resize((size, size), Image.LANCZOS)
        bg = Image.blend(bg, Image.new("RGB", bg.size, (0, 0, 0)), 0.12)
        canvas.paste(bg, (0, 0))
        # Foreground is the artwork full-bleed. Insetting it instead leaves a
        # visible square edge wherever a launcher mask cuts inside the inset.
        # The magnifier already sits inside the 66% safe circle, so the mask only
        # ever trims plain leather.
        fg = src.resize((size, size), Image.LANCZOS).convert("RGBA")
        fg.save(d / "ic_launcher_foreground.png")
        bg.save(d / "ic_launcher_background.png")
        print(f"mipmap-{density}/ic_launcher_foreground.png  {size}px")

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    colour = RES / "values"
    colour.mkdir(parents=True, exist_ok=True)
    (colour / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        '    <color name="ic_launcher_background">#2A1A10</color>\n'
        "</resources>\n"
    )
    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    (anydpi / "ic_launcher.xml").write_text(adaptive)
    (anydpi / "ic_launcher_round.xml").write_text(adaptive)
    print("mipmap-anydpi-v26/ic_launcher.xml")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
