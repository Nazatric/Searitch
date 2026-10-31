# Changelog

Android builds of Searitch. Every release ships `arm64-v8a` and `armeabi-v7a`
APKs, signed, with the keystore and checksums attached.

---

## v0.7 — 31 Oct 2026

`versionCode 7` · `versionName 1.4.0`

**Settings sits on the hide again.**

- The sheet was filled with a flat colour while the cards on it were textured,
  which is why it read as a different app. It is the same tiled hide as the home
  screen now — same grain, same sheen — with the status-bar inset respected so
  the title never runs under the clock.
- Switches went back to the paper trough with the brass knob. The dark leather
  channel they had become looked like a black pill dropped into the card. The
  trough is the same debossed field as the search bar; the knob is the same
  metal as every other control.
- New in Settings → Home screen: a brass slider with three detents that moves
  the search bar to the top, middle or bottom of the landing screen. The
  wordmark travels with it, so the page stays balanced.
- The app icon is the magnifier photograph you sent, used as it is — no
  regenerating, no recolouring — cut for every density, with a full-bleed
  adaptive icon for Android 8+.

---

## v0.6 — 31 Oct 2026

`versionCode 6` · `versionName 1.3.1`

**Sharp photos immediately.**

- The detail hero was stretching the grid thumbnail across the screen and waiting
  seconds for the original — for Wallhaven that thumbnail is a 300×200 file, so
  the first thing you saw was a blur. Every source now carries a
  screen-resolution preview (Wallhaven's ~1920px `large`, Wikimedia's 1280px
  rendition, and so on), which paints at once. The original is only fetched when
  the preview is genuinely smaller than the screen, and a small brass
  `FINISHING` tag shows while that happens.
- Toggles were a black track with a black knob and read as a cheap 3D widget in a
  paper card. They are a stitched leather channel with a travelling knob now:
  brass when on, paper when off.
- The cog was near-black and looked like a sticker on the medallion. It is
  engraved brass — darker metal in a lighter disc, edge-lit instead of filled
  black.
- New app icon: the brass magnifier on saddle leather, generated for this build
  and cut for every density, with a full-bleed adaptive icon for Android 8+.

---

## v0.5 — 31 Oct 2026

`versionCode 5` · `versionName 1.3.0`

**Smooth, and a gear worth pressing.**

- Textures are tiled by the GPU through a repeated shader. Every leather and
  paper surface used to re-blit its bitmap in a nested loop on each frame; with
  around twenty textured surfaces on screen that was the cause of the stutter in
  the settings sheet and while scrolling. One draw call per surface now, and the
  grain still lands on exact device pixels.
- `SearitchImage`, `Filters`, `AppSettings` and `Palette` are immutable, so grid
  items and rows can be skipped instead of recomposed.
- System-bar colour and icon work is keyed to the tone rather than repeated on
  every recomposition.
- The gear is drawn as a brass medallion: turned-metal face, raised rim, seated
  shadow, and a filled cog with a light catch above and a cut shadow below. The
  cog turns when the sheet opens; the medallion stays put.
- Fixed "Clear" wrapping to `Clea` / `r`. Labels can no longer wrap inside a
  chip, and the cache controls get their own full-width row.

---

## v0.4 — 31 Oct 2026

`versionCode 4` · `versionName 1.2.1`

**Classic hide back to how it was.**

- Classic hide and Cream stock draw the original bitmaps from the first release,
  untinted. The default look is identical to v0.1 again.
- The generated grain maps are used only by the other tones, where there is no
  original texture to preserve.

---

## v0.3 — 31 Oct 2026

`versionCode 3` · `versionName 1.2.0`

**Settings that actually change the app.**

- Tones restyle the whole UI. Leather, paper, brass and ink used to be fixed
  colours read directly by the straps, cards, chips and lettering, so picking a
  tone only repainted the background. They all follow the live palette now.
- Ink is derived from the paper tone, so every stock keeps its contrast.
- Settings apply live — the app repaints behind the sheet as you tap. A preview
  at the top shows hide, stock and brass rendered as the app paints them.
- New grain maps drawn over the tone colour, so Oxblood reads as dyed hide rather
  than brown with a red tint.
- System bars take the hide colour and flip icon contrast per tone.
- On/off is a brass switch; choices stay chips.

---

## v0.2 — 31 Oct 2026

`versionCode 2` · `versionName 1.1.0`

**Downloads, settings, and Back that behaves.**

- Download saves the file into `Download/Searitch` instead of opening the image
  in a browser. MediaStore on Android 10+, DownloadManager below, with Saving and
  Saved states.
- Settings gear on every screen with search-field placement, card density, card
  style, materials, motion, haptics, download quality, search defaults and cache.
- Back no longer re-runs the search. Results, paging and scroll position live in
  a session above the navigation graph, so returning from a photo lands where you
  left it.
- Two-layer feed cache: memory plus one file per feed, with a `CACHED` tag while
  a stale feed refreshes.
- Recent searches on Home.

---

## v0.1 — 31 Oct 2026

`versionCode 1` · `versionName 1.0.0`

**First Android build.**

- Universal image search across nine keyless sources, streaming results,
  filters, facets, detail view with similar images.
- Leather and paper UI with real textures, Cinzel headings, vector icons.
- `arm64-v8a` and `armeabi-v7a` APKs, signed, built from the original archive.
