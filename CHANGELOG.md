## v0.12 — 1 Nov 2026

`versionCode 12` · `versionName 1.8.0`

**"Similar" now means similar.** The old strip re-ran the search text, so a
Wallhaven wall that arrived without tags was "similar" to the literal word
`wallpaper` — hence mountains, paper rolls and a group photo. Two things had to
be true before that could be fixed. First, every reverse-image engine was
checked, and none of them can serve a phone app: SauceNAO answers *"the
anonymous account type does not permit API usage"*, trace.moe refuses to fetch a
URL at all, Bing's `searchbyimage?imgurl=` GET redirects to its trending page,
and Yandex only assembles its similar/sizes/sites blocks in JavaScript — the raw
HTML a client receives has none of it. Second, the picture itself. So the
comparison is made in the app: a 64-bit difference hash of the opened photo, the
same hash of each candidate, ordered by how few bits differ, and anything more
than half the bits apart is dropped instead of being called similar. Candidates
come from the photo's own subject, and results that don't name that subject are
filtered out before the hashing even starts.

**Wallhaven photos have titles now.** Its search endpoint returns no title *and*
no tags — the app was labelling walls `Wallpaper 1964×3500`, which the caption
treated as noise and replaced with `Untitled Photo`. The wall's own record is now
read when you open it, so the title is the site's real tags: *Fox Girl · Flute ·
Blue Eyes*. Nothing shows "Untitled" any more; a photo with no title at all is
described by the collection it came from.

**Three things worth having when you find a picture.** Share, with the page that
credits whoever made it. Set as wallpaper, straight from the panel — no download,
no gallery hop. And pinch to scale the picture, with a readout that tells you how
far in you are and takes you back to whole with a tap.

The web build keeps the subject ranking but not the look ranking: reading pixels
back out of a cross-origin image in a browser is refused by several of these
sources, so it reports what it actually did instead of claiming the rest.
