#!/usr/bin/env python3
"""Builds the leather/paper *grain maps* baked into the app.

    python3 tools/make-textures.py

Why grain maps instead of coloured textures
-------------------------------------------
Settings lets you pick a leather tone, a paper tone and a metal tone. Shipping
one coloured texture per tone would mean ~9 large bitmaps in the APK and a
visible flicker when switching (a different file has to load).

Instead we bake one grey *detail* map per material, centred on mid-grey, and
overlay it on the chosen tone colour when drawing (see `Modifier.tiledTexture`).
Overlay leaves the tone colour untouched where the map is 0.5, darkens it where
it dips and lifts it where it rises — one asset, any tone, instant switching,
and the grain sits on the real colour rather than a coloured fill.

Output: two seamless grayscale PNG tiles, tiled at 288dp (3 texture px per dp,
i.e. 1:1 on a 3x screen, downsampled — crisp — on 2x).
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "assets-src"
OUT = ROOT / "composeApp/src/commonMain/composeResources/drawable"

JOBS = [
    # name, source, tile px, overlap px, grain range around mid-grey (0.5).
    # These are drawn with BlendMode.Overlay, where 0.5 = "leave the tone
    # alone", below it shadows the tone and above it catches the light — so the
    # grain modulates around the chosen colour instead of just darkening it.
    ("leather_grain", "leather-src.png", 864, 140, (0.34, 0.66)),
    ("paper_grain", "paper-src.png", 576, 110, (0.45, 0.55)),
]


def seamless(src: Image.Image, size: int, overlap: int) -> Image.Image:
    """Make [src] tile: cross-fade a narrow overlap band between opposite edges.

    Only the seam band is blended; the interior keeps untouched source pixels,
    which is what stops the grain from going soft the way a whole-image blend
    does.
    """
    src = src.convert("L")
    t = size
    out = src.resize((t + overlap, t + overlap), Image.LANCZOS)
    tile = out.crop((0, 0, t, t))

    band = out.crop((t, 0, t + overlap, t))          # the strip that would be cut
    left = tile.crop((0, 0, overlap, t))
    blended = Image.blend(left, band, 0.5)
    tile.paste(blended, (0, 0))

    band_v = out.crop((0, t, t, t + overlap)).resize((t, overlap), Image.LANCZOS)
    top = tile.crop((0, 0, t, overlap))
    tile.paste(Image.blend(top, band_v, 0.5), (0, 0))
    return tile


def to_grain(tile: Image.Image, lo: float, hi: float) -> Image.Image:
    """Map luminance into [lo, hi] of white, so multiply() keeps the tone colour.

    Percentile normalisation (2%-98%) rather than min/max: a couple of specks in
    the source would otherwise flatten all the real grain into one value.
    """
    px = sorted(tile.tobytes())
    n = len(px)
    lo_v = px[int(n * 0.02)]
    hi_v = px[int(n * 0.98)]
    span = max(1, hi_v - lo_v)
    table = []
    for v in range(256):
        t = (v - lo_v) / span
        t = 0.0 if t < 0 else 1.0 if t > 1 else t
        table.append(int(round(255 * (lo + (hi - lo) * t))))
    return tile.point(table)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    missing = [j[1] for j in JOBS if not (SRC / j[1]).is_file()]
    if missing:
        print(f"missing sources in {SRC}: {missing}", file=sys.stderr)
        return 1

    for name, src_name, size, overlap, rng in JOBS:
        src = Image.open(SRC / src_name)
        tile = seamless(src, size, overlap)
        tile = tile.filter(ImageFilter.UnsharpMask(radius=1.0, percent=45, threshold=3))
        grain = to_grain(tile, *rng)
        dest = OUT / f"{name}.png"
        grain.save(dest, optimize=True)
        print(f"{dest.relative_to(ROOT)}  {grain.size[0]}px  {dest.stat().st_size / 1024:.0f} KiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
