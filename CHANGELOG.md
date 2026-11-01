## 2.1.1 — 2 Nov 2026

`versionCode 23` · `versionName 2.1.1`

**The search box reads before it asks.** Words used to be a pipe: whatever was
typed went to fourteen sources unchanged, and the only intelligence sat
downstream, throwing away answers that didn't mention the query. A query is now
read once, before a single request goes out. Format words — `png`,
`transparent`, `svg` — describe the file itself, so they are applied and appear
as a chip you can remove. Colour, size and intent words — `red`, `4k`,
`wallpaper` — are *offered* rather than silently applied, because "red panda" is
not a request for red things; the home screen shows what it understood as you
type, and the results screen offers it as one tap.

**Results are ranked now, not just mixed.** Every result is scored on how much
of the query it actually names — the title counts for most, then the source's
curated tags, then its site — plus how large it is, whether its shape suits what
was asked, and how much the source knows about what it is showing: a museum
catalogue outranks a reposted thumbnail. Near-copies arriving from different
sources collapse onto their best copy, and no single source may own a page —
except when one source is all you selected, where all of it comes through.

**Sources are measured instead of assumed.** Each one now carries a moving
average of how long it takes, and its timeout follows that measurement with
headroom, so a source that answers in 400 ms is no longer waited on for six
seconds. Settings → Sources lists every source with its speed, how much it
returned and whether it is resting after failures.

**Refinements come from what came back.** Tags shared by several returned
pictures, and not already in your words, are offered as "narrow it" chips. They
are the catalogue's own vocabulary, not a generated guess.

**A photo steps through the strip it came from.** Previous and next in the detail
strap, with a counter, staying inside the grid or the similar-photos strip you
opened it from. Back still returns to where you were, not through every picture
you looked at.

**The detail page reads the picture.** Its decoded colours are offered as
swatches — tap one to search exactly that colour — and its own tags are buttons,
so "common dolphin" asks the catalogue for what it called the thing.

**Recent searches keep their filters.** Tapping a past search restores the
format, colour and orientation it was made with, instead of replaying the words
alone. A search that found nothing now offers a shorter version of itself.

**Faster.** Cards decode at card size rather than at source size, the next page
is fetched before you reach the bottom, and an empty result no longer waits for
the slowest source to fail.

**Smaller things that were wrong.**
- The filter sheet now says how many sources a choice would search ("3 of 14")
  before you commit to it, instead of leaving you with an empty grid.
- Paging de-duplicates with the same idea of "same picture" the ranker uses, so
  page two no longer re-introduces what page one dropped.
- When *no* source answers — no connection, servers down — the grid says so
  instead of claiming nothing matched the search.
- "Screen-size" downloads now really use the rendition a source publishes for
  screens instead of quietly fetching the original, and the label says what it
  does.
- The settings sheet prints the real version (it was four releases behind), and
  feed size and image size are counted separately rather than twice.
