## v0.13 — 1 Nov 2026

`versionCode 13` · `versionName 1.9.0`

**Results now answer what you typed.** Searching `android` was returning beach
wallpapers and Spanish university thesis covers. Two separate causes, both
fixed. Bing's image endpoint answers some single words with a *trending* set
instead of the query — ask it for `android` and it hands back "Another Round"
film posters — and nothing downstream ever checked a result against the query,
so anything a source said went straight into the grid. Every result is now
scored against the words you typed, in its title, site, page address, file
address and tags; a result that mentions none of them is dropped, and a source
whose whole page misses the query is discarded rather than shown. Results that
do match are ordered by how much of the query they contain.

**Yandex is in.** ImageSearchMan searches Google, Bing and Yandex; Google's
results are base64 strings assembled by its own JavaScript and its old JSON
endpoint now 404s, DuckDuckGo answers a plain client with a `botnet_challenge`,
Baidu answers `Forbid spider access`, and Yahoo renders its grid in JavaScript —
all four were tried and none can serve a plain HTTP client, so none is claimed.
Yandex renders a full page of results server-side, with the picture's real title,
its real dimensions and its original file, and it answers the query it is given.
That is fourteen sources.

**The photo panel scrolls again.** Pinch-to-scale arrived in v0.12 using the
general transform detector, which reads a one-finger drag as a pan and swallowed
the gesture — so the biggest thing on the detail page was the one part you
couldn't drag, and the page only scrolled from the strip below it. Two fingers
zoom and pan the picture now; one finger scrolls the page, exactly as before.
