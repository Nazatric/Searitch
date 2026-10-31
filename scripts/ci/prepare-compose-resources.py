#!/usr/bin/env python3
"""Recover the Compose Multiplatform resources the source expects.

The bundled zip ships `composeApp/src/commonMain/kotlin/...` which imports
`app.searitch.resources.cinzel_bold` (font), `leather` and `paper` (drawables),
but the `composeResources/` directory itself is not in the archive, so the
Kotlin sources cannot compile.

Those exact assets *are* present in the archive, embedded as data URIs in the
web app's generated stylesheet (`src/app/textures.css`, produced by
`scripts/make-textures.mjs`). This script extracts them, converts them to the
formats Skiko/Android can load (WOFF2 -> TTF, WebP -> PNG) and writes them to
the Compose resources tree.

No file that came out of the zip is modified.
"""
from __future__ import annotations

import argparse
import base64
import io
import re
import sys
from pathlib import Path

FONT_RE = re.compile(r'@font-face\s*\{(?P<body>.*?)\}', re.S)
FONT_SRC_RE = re.compile(r'url\("data:font/(?P<fmt>[a-z0-9]+);base64,(?P<b64>[^"]+)"\)')
BG_RE = re.compile(r'(?P<sel>[^{}]*)\{[^{}]*?background-image:\s*url\("data:image/(?P<fmt>[a-z0-9]+);base64,(?P<b64>[^"]+)"\)', re.S)


def font_name_for(css: str) -> str:
    m = FONT_RE.search(css)
    body = m.group("body") if m else ""
    family = re.search(r'font-family:\s*"([^"]+)"', body)
    weight = re.search(r"font-weight:\s*(\d+|bold|normal)", body)
    fam = (family.group(1) if family else "cinzel").strip().lower().replace(" ", "_")
    w = (weight.group(1) if weight else "700")
    w = {"bold": "700", "normal": "400"}.get(w, w)
    # Compose resource accessors derive from the file name; the Kotlin sources
    # import `cinzel_bold`, so 700 maps to "bold".
    style = {"700": "bold", "400": "regular"}.get(w, w)
    return f"{fam}_{style}", body


def woff2_to_ttf(data: bytes) -> bytes:
    try:
        from fontTools.ttLib import TTFont  # noqa: WPS433
        import brotli  # noqa: F401,WPS433
    except Exception as exc:  # pragma: no cover - diagnostic path
        sys.exit(f"fonttools+brotli required to convert the embedded font: {exc}")
    font = TTFont(io.BytesIO(data))
    font.flavor = None  # TTFont auto-decompresses WOFF2 when reading
    out = io.BytesIO()
    font.save(out)
    return out.getvalue()


def webp_to_png(data: bytes) -> bytes:
    from PIL import Image  # noqa: WPS433

    img = Image.open(io.BytesIO(data))
    if img.mode not in ("RGB", "RGBA"):
        img = img.convert("RGB")
    out = io.BytesIO()
    img.save(out, format="PNG", optimize=True)
    return out.getvalue()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--css", required=True, help="path to src/app/textures.css from the zip")
    ap.add_argument("--resources", required=True, help="composeResources output directory")
    args = ap.parse_args()

    css_path = Path(args.css)
    css = css_path.read_text("utf-8")
    res = Path(args.resources)
    fonts = res / "font"
    drawables = res / "drawable"
    fonts.mkdir(parents=True, exist_ok=True)
    drawables.mkdir(parents=True, exist_ok=True)

    name, body = font_name_for(css)
    m = FONT_SRC_RE.search(body)
    if not m:
        sys.exit("no embedded @font-face found in textures.css")
    ttf = woff2_to_ttf(base64.b64decode(m.group("b64")))
    (fonts / f"{name}.ttf").write_bytes(ttf)
    print(f"font      -> font/{name}.ttf ({len(ttf) / 1024:.1f} KiB)")

    wanted = {"leather": "leather", "paper": "paper"}
    seen = set()
    for m in BG_RE.finditer(css):
        sel = m.group("sel").lower()
        for key, fname in wanted.items():
            if key in sel and key not in seen:
                png = webp_to_png(base64.b64decode(m.group("b64")))
                (drawables / f"{fname}.png").write_bytes(png)
                print(f"drawable  -> drawable/{fname}.png ({len(png) / 1024:.1f} KiB)")
                seen.add(key)
    missing = set(wanted) - seen
    if missing:
        sys.exit(f"missing embedded textures: {sorted(missing)}")

    print("composeResources tree:")
    for p in sorted(res.rglob("*")):
        if p.is_file():
            print(f"  {p.relative_to(res)} ({p.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
