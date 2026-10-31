# Changelog

Every release ships `arm64-v8a` and `armeabi-v7a` APKs, signed with the key in
`kotlin-app/keystore/`, with checksums attached. Older notes live on the
[releases page](../../releases) — this file only carries the current version.

---

## v0.8 — 31 Oct 2026

`versionCode 8` · `versionName 1.5.0`

**The status bar belongs to the hide.** Settings opens on a leather strap now —
the same hide, sheen and saddle stitching as the search bar — running up behind
the status bar instead of leaving the clock and battery over bare window. The
sheet meets the top of the screen square, with no grab pill sitting in a flat
gutter above the title.

**One signing key from here on.** Releases were signed with a key generated
inside each build, so every version had a different certificate and Android
refused the upgrade. The key is committed now: v0.8 installs over itself, and
every release after it will too. Coming from v0.7 or earlier needs one uninstall.

**Docs rebuilt on the app's own materials.** The banner and social card are
rendered from the real leather, paper and brass assets and the bundled Cinzel, so
they can't drift from the thing they advertise.
