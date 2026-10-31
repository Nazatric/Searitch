## v0.11 — 1 Nov 2026

`versionCode 11` · `versionName 1.7.1`

**The hero actually finishes.** The detail view read the image library's load
state once, when the screen opened, and never again — the state is a stream, not a
value, so it always read "loading". The `FINISHING` tag therefore never cleared
and the sharp image never faded in, even though the full-size file had already
been fetched and cached. (That is why Download gave you the original instantly
while the screen was still "finishing".) The state is now subscribed to, so the
tag clears and the sharp version appears as soon as the decode lands.

**A failure says so.** If the full-size file really can't be fetched, the tag
becomes `TAP TO RETRY` and issues a fresh request instead of replaying the cached
failure. The tag also waits 400 ms before appearing, so cached images open
straight to sharp with no flashing.

---

## v0.10 — 1 Nov 2026

`versionCode 10` · `versionName 1.7.0`

**Every picture, whole.** Wallhaven's two thumbnail renditions are both fixed
landscape crops — 300×200 and 432×243 whatever the wallpaper's shape — so a
1964×3500 phone wall arrived as a wide slice of its middle and the grid cropped
that slice again. Cards and the detail panel now size their frame to the picture
that actually arrives and draw it whole: no crop, and no letterbox either.

**Sharper where it counts.** The detail view fetches the full wallpaper rather
than upscaling Wallhaven's small ratio-honest thumb. Bing uses its raw cached
rendition instead of the `w=215&h=180` crop, and iNaturalist uses the 500px
`small` photo instead of the 75px square.

---

## v0.9 — 1 Nov 2026

`versionCode 9` · `versionName 1.6.0`

**Thirteen sources, and Bing searches the open web.** Bing's images feed answers
without a key and accepts colour, orientation and size as real parameters, so it
is in. The Art Institute of Chicago, the Cleveland Museum of Art and the Library
of Congress join it — public domain or CC0, no keys.

**Filters are only applied where they can be.** Selecting a colour searches the
three sources that filter by colour and skips the ones with no colour data;
selecting a licence searches only the sources that publish one. Orientation and
size are tested against the dimensions a source reports — an image of unknown
size is left out rather than counted as a match. Nothing is guessed, and nothing
is passed off as licensed when it isn't.

**Similar images use the subject.** Related sets come from the source's tags, or
from the subject words of the title with catalogue noise removed.

**Broken sources are skipped again.** Transport failures and 5xx count against a
source, and non-200 search responses are no longer parsed as empty results.

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
