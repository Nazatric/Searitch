## 2.0.0 — 1 Nov 2026

`versionCode 20` · `versionName 2.0.0`

**Wallpaper is your phone's job now.** The button used to call Android's
wallpaper API directly. It set the picture, full stop: no crop, no choice of home
screen or lock screen, no route to a live wallpaper — none of what the setter
already on the phone does. It now hands the file to that setter, the one you
reach from Gallery. On HyperOS/MIUI and stock Android the full-screen wallpaper
editor opens with home / lock / both; on the devices whose cropper isn't
exported, the "Set as" sheet appears instead. The direct set stays as the last
resort, so the button is never dead. The file travels as a `content://` URI
through a `FileProvider`, which is the only kind Android 7 and later will accept.

**Any colour, not twelve.** The named swatches stay, because the sources that
filter by colour only understand names — Bing's index, Openverse's colour word.
Beside them is a wheel: drag anywhere in it, read the hex, tap the disc to use
it. A wheel pick goes to Wallhaven as its exact hex and reaches the name-only
sources as its nearest name, so a pick never quietly searches for nothing. The
filter chip says which colour is on and what it is near.

**Transparent pictures get a black stage.** A PNG with holes in it sat on cream
paper in the detail view, and the paper showed through the holes and tinted
everything. Transparent formats — PNG, WebP, SVG — now get full black behind them
in the detail view. Grid cards keep their paper mount: at thumbnail size the
shape reads fine and the gallery stays one material.

**Coming back from a photo lands where you left it.** A photo used to replace the
results screen. That tore the grid down; on the way back it was rebuilt and the
shared-element flight measured it before the scroll had been restored, so the
picture flew to the wrong place and only found its slot once you dragged the
list. The results screen now stays composed and the photo opens over it, with
only the photo animating in — the card it came from never stops existing, so the
return always has somewhere true to land.

**Search is tidier and reads stems.** Queries are cleaned once — stray quotes,
doubled spaces, non-breaking spaces — so every source is asked the same thing,
and matching now accepts a five-letter stem, so `cathedral` also answers
`cathedrals`. One image loader is configured for the whole app with an inexact
decode size, so a card at 212 px and a card at 218 px share one cached bitmap
instead of keeping two.

**Housekeeping.** Only this release exists. Earlier ones are gone, along with the
stale build archive that had been sitting in the repository, and the release
workflow now watches this branch alone.
