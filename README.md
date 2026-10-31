<img src="docs/banner.png" alt="Searitch" width="100%">

**Search anything.** One query, thirteen image sources answering at once — the
open web alongside open-access libraries and museums. No API keys, no accounts,
no server in between. One Kotlin codebase builds the Android app and the web app.

[![Latest release](https://img.shields.io/github/v/release/Nazatric/Searitch?label=release&color=C69B3F)](../../releases/latest)
[![Android CI](https://github.com/Nazatric/Searitch/actions/workflows/release-android.yml/badge.svg)](../../actions/workflows/release-android.yml)
![Android 7.0+](https://img.shields.io/badge/android-7.0%2B-C69B3F)
![Compose Multiplatform](https://img.shields.io/badge/compose-multiplatform-C69B3F)
![No API keys](https://img.shields.io/badge/api%20keys-none-C69B3F)

---

**Contents** · [What it does](#what-it-does) · [Materials](#materials) ·
[Settings](#settings) · [Install](#install) · [Build](#build) ·
[Layout](#layout) · [Notes](#notes)

---

## What it does

Type a word, get pictures. Thirteen sources are queried at once and their results
stream in as each one answers, so the first photos appear before the slowest
site has finished.

| | |
| --- | --- |
| **Thirteen sources** | Bing (full web), Openverse, Wikimedia Commons, Wallhaven, Flickr, iNaturalist, NASA, The Met, Art Institute of Chicago, Cleveland Museum of Art, Library of Congress, Europeana, the V&A |
| **Filters** | file type, orientation, size, twelve-colour palette, licence, source, safe search, anti-AI |
| **Smart facets** | the app counts what it actually got back and offers drill-downs with real numbers — *JPG only 12*, *High-res 7* — instead of guessing |
| **Detail view** | full image, creator, licence, dimensions, download, similar images |
| **Downloads** | streamed straight into `Download/Searitch` through MediaStore on Android 10+, no permission needed |

Each request goes from your device straight to the source.

### Sources

| Source | What it is | Colour | Licence | Safe search |
| --- | --- | --- | --- | --- |
| Bing | the open web | yes, `filterui:color2` | no | `adlt=strict` |
| Openverse | CC index of 50+ sites | as a search word | yes | mature flag |
| Wikimedia Commons | 100M+ media files | no | yes | curated |
| Wallhaven | community wallpapers | yes, `colors=` | no | `purity` |
| Flickr | public photo feed | no | no | — (skipped) |
| iNaturalist | observation photos | no | yes | curated |
| NASA | image library | no | public domain | curated |
| The Met | museum collection | no | yes | curated |
| Art Institute of Chicago | public-domain works | no | public domain | curated |
| Cleveland Museum of Art | CC0 works | no | CC0 | curated |
| Library of Congress | prints & photographs | no | yes | curated |
| Europeana | European heritage | no | yes | curated |
| V&A | museum collection | no | CC0 | curated |

That table is the contract the filters work to, not decoration:

- **A source that can't honour a filter isn't searched.** Pick a colour and only
  Bing, Wallhaven and Openverse are asked — the three that really filter by
  colour. Pick a licence and the sources that publish no licence (Bing,
  Wallhaven, Flickr) drop out rather than have "commercial use" printed next to
  them.
- **Orientation and size are tested against reported dimensions.** A picture
  whose size is unknown is excluded while one of those filters is on, so
  *Landscape* and *Large* mean what they say.
- **Safe search switches off what it can't verify.** Flickr's public feed has no
  rating metadata, so it is skipped while strict safe search is on instead of
  being trusted.

> [!NOTE]
> Bing, Wallhaven and Flickr send no CORS headers, so those three answer in the
> Android build only and stay quiet in the browser build. Everything else works
> in both.

## Materials

The UI is not a skin over stock Material components. Every surface is drawn from
three materials, and all three are yours to change in Settings.

| Material | Role | Default |
| --- | --- | --- |
| Leather | page, straps, wells | `#3A2418` classic hide |
| Paper | cards, fields, chips | `#F1E8D4` cream stock |
| Brass | buttons, knobs, gear | `#C9A24B` brass |

Grain is real: two seamless texture maps, tiled at 288 dp — one texture pixel per
device pixel on a 3× screen — so the grain stays sharp instead of going soft.
Classic hide and cream stock are the original bitmaps, untinted. The other tones
(espresso, oxblood, midnight, olive / parchment, bone, kraft) are painted by
overlaying a grey detail map on the chosen colour, so they look dyed rather than
tinted.

> [!NOTE]
> Textures are tiled on the GPU through a repeated shader — one draw call per
> surface. That is what keeps the page moving at full refresh rate with the
> stitching on it.

Type is **Cinzel**, bundled with the app. Every icon is a vector drawn at run
time; there is no icon font anywhere in the APK.

## Settings

The gear sits in the top corner of every screen and turns a third of a rotation
as it opens. Settings opens on the same stitched hide as the rest of the app, and
everything applies live — no Apply button, no draft state to lose.

| Group | What you can change |
| --- | --- |
| Home screen | where the search bar sits — top, middle or bottom |
| Search field | top strap or bottom dock |
| Gallery | card density, card style, similar images on/off |
| Materials | leather tone, paper tone, metal tone, real textures on/off |
| Motion & feel | animations, haptics |
| Downloads | the original file, or a ≤2000 px preview |
| Search defaults | safe search, anti-AI — applied to new searches |
| Cache | keep searched feeds, live size, clear |

A live sample at the top of the sheet paints the chosen hide, stock and metal
exactly as the app will, so a tone is judged on the real thing and not on a
swatch.

## Navigation

Home → Search → Detail, on a real back stack. Android back and gesture navigation
pop it; at Home they close the app, the way any other app behaves.

Returning from a photo lands exactly where you left — same results, same paging,
same scroll position. Results live in a session above the navigation graph, so
screens can be rebuilt without re-running a search. Photos fly between the grid
and the detail hero; the search field glides between screens.

Two cache layers keep a repeat search instant:

- **Memory** — the last 24 feeds, evicted least-recently-used.
- **Disk** — one JSON file per feed, written temp-then-rename, so a killed
  process can't leave half a feed behind.

A cached feed paints immediately and shows a small `CACHED` tag while a stale one
refreshes quietly behind it.

## Install

Grab the APK for your device from [Releases](../../releases/latest).

| File | For |
| --- | --- |
| `Searitch-v0.8-arm64-v8a.apk` | almost every phone since 2017 |
| `Searitch-v0.8-armeabi-v7a.apk` | older 32-bit devices |

Release APKs are signed with the key committed at
`kotlin-app/keystore/searitch-release.keystore`, so every release carries the same
certificate and installs straight over the one before it. The keystore is attached
to each release so any build can be verified.

> [!IMPORTANT]
> Releases before v0.8 were signed with a key generated per build, so their
> certificates differ. If you are coming from v0.7 or earlier, uninstall once —
> after that, upgrades install over the top.

## Build

<details>
<summary>Android and web, from source</summary>

```bash
cd kotlin-app

./gradlew :composeApp:assembleRelease          # release APK, split by ABI
./gradlew :composeApp:assembleDebug            # debug
./gradlew :composeApp:wasmJsBrowserDevelopmentRun   # web, hot reload
```

JDK 17 and the Android SDK are needed (`ANDROID_HOME`, or `sdk.dir` in
`local.properties`). The Gradle wrapper jar isn't in the original archive;
regenerate it once with `gradle wrapper --gradle-version 8.11.1`.

Both docs images are generated from the app's own assets:

```bash
python3 tools/make-icon.py     # every launcher mipmap
python3 tools/make-banner.py   # docs/banner.png, docs/social-preview.png
```

Both read `assets-src/` and `composeResources/`, so the docs images are built
from the same leather, paper and Cinzel the app paints with.

</details>

<details>
<summary>What CI does on every push</summary>

[`.github/workflows/release-android.yml`](.github/workflows/release-android.yml)
builds both ABIs, signs them with the committed key, records badging and
signature hashes in the release notes, and publishes the release. A second job
compiles the web target so a mistake in shared code can't hide behind an
Android-only build.

</details>

## Layout

<details>
<summary>Where everything lives</summary>

```text
kotlin-app/composeApp/src/
  commonMain/kotlin/app/searitch/
    Api.kt            thirteen sources, streaming aggregation, filters, related
    App.kt            back stack, screen transitions, session host
    SearchScreen.kt   results grid, facets, filter sheet
    DetailScreen.kt   hero image, metadata, download, similar
    HomeScreen.kt     wordmark, search field, recent searches
    SettingsSheet.kt  settings, the gear medallion, home-bar slider
    Settings.kt       settings model, tone palettes, haptics
    FeedCache.kt      memory + disk feed cache, search session
    Downloader.kt     download URL and filename rules
    Theme.kt          materials, textures, tiling, straps, stitches
    Components.kt     buttons, chips, photo prints, skeletons
  androidMain/        activity, MediaStore downloads, prefs, system bars
  wasmJsMain/         browser entry point
  commonMain/composeResources/   leather, paper, grain maps, Cinzel
kotlin-app/tools/    icon and docs-image generators
kotlin-app/keystore/ the release signing key
docs/                banner and social card used by this file
```

</details>

## Notes

- Europeana runs on its public demo key, which is rate-limited. The other eight
  need nothing.
- Wallhaven and the Flickr feed send no CORS headers, so they answer in the
  Android build only. Openverse and Wikimedia work everywhere.
- A source that fails twice is skipped for the next few requests, so one dead
  endpoint can't hold up a search.
- Animations are spring-driven and transform-only, which is what lets them run at
  whatever refresh rate the display has.

---

<p align="center">
  <sub>Leather, paper and brass. Cinzel by Natanael Gama, SIL Open Font License.</sub>
</p>
