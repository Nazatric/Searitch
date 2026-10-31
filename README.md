# Searitch

**Search anything.** A universal image search app — one Kotlin codebase, a native
Android build and a browser build, wrapped in leather, paper and brass.

`v1.3.0` · Android 7.0+ (API 24) · arm64 + arm32 · web via Kotlin/Wasm

---

## WHAT IT DOES

Type a word, get images from roughly a billion licensed pictures across nine
keyless sources. Results stream in as each source answers, so the first photos
appear before the slowest site has finished.

- **Parallel search** — Openverse, Wikimedia Commons, Wallhaven, Flickr,
  iNaturalist, NASA, The Met, Europeana and the V&A, queried at once and
  interleaved, deduplicated by file URL.
- **Filters** — file type, orientation, size, twelve-colour palette, licence
  (commercial / allows editing), source. Plus **safe search** and an
  **anti-AI filter** over titles, tags and creators.
- **Smart facets** — the app counts what it actually got back and offers
  drill-downs with real numbers ("JPG only 12", "High-res 7") instead of
  guessing.
- **Detail view** — full image, source, creator, licence, dimensions, a
  **Download** that saves into `Download/Searitch`, and a *Similar images* grid.
- **Downloads** — streamed straight to storage via MediaStore on Android 10+,
  no permission needed. Older versions use DownloadManager.

---

## MATERIALS

The UI isn't a skin over stock Material components. Every surface is drawn from
three materials, and all of them are yours to change in **Settings**.

| Material | Role | Default |
| --- | --- | --- |
| Leather | page, straps, wells | `#3A2418` Classic hide |
| Paper | cards, fields, chips | `#F1E8D4` Cream stock |
| Brass | buttons, knobs, gear | `#C9A24B` Brass |

Grain is real: two seamless texture maps, tiled at 288dp — exactly one texture
pixel per device pixel on a 3× screen, so the grain stays sharp rather than
soft. Classic hide and Cream stock are the original bitmaps; the other tones
(Espresso, Oxblood, Midnight, Olive / Parchment, Bone, Kraft) are painted by
overlaying a grey detail map on the chosen colour, which is why they look dyed
rather than tinted.

Textures are tiled by the GPU through a repeated shader — one draw call per
surface, no per-frame filtering.

Type is **Cinzel** for the wordmark and headings, bundled with the app. Icons are
drawn as vectors at run time; there is no icon font anywhere.

---

## SETTINGS

The gear sits in the top corner of every screen and spins a third of a turn when
it opens. Everything applies live — no Apply button, no draft state to lose.

| Group | What you can change |
| --- | --- |
| Search field | Top strap or bottom dock |
| Gallery | Card density, card style, similar images on/off |
| Materials | Leather tone, paper tone, metal tone, real textures on/off |
| Motion & feel | Animations, haptics |
| Downloads | Original file, or a ≤2000px preview |
| Search defaults | Safe search, anti-AI, applied to new searches |
| Cache | Keep searched feeds, live size, clear |

A live preview at the top of the sheet shows the hide, stock and metal exactly as
the app paints them, so a tone is judged on the real thing.

---

## NAVIGATION

Home → Search → Detail, on a real back stack. Android back and gesture
navigation pop it; at Home they close the app, like any other app should.

Returning from a photo lands exactly where you left — same results, same paging,
same scroll position. Results live in a session above the navigation graph, so
screens can be rebuilt without re-running the search.

Photos fly between the grid and the detail hero, and the search field glides
between screens. Cross-fades are kept short so the moving element does the work.

---

## CACHE

Two layers, so a search you've already run is instant:

- **Memory** — the last 24 feeds, evicted least-recently-used.
- **Disk** — one JSON file per feed, written temp-file-then-rename so a killed
  process can't leave half a feed behind.

A cached feed paints immediately and shows a small `CACHED` tag while a stale one
refreshes quietly behind it. Similar-image sets and your recent searches are kept
too.

---

## INSTALL

Grab the APK for your device from [Releases](../../releases/latest):

| File | For |
| --- | --- |
| `Searitch-v0.5-arm64-v8a.apk` | almost every phone since 2017 |
| `Searitch-v0.5-armeabi-v7a.apk` | older 32-bit devices |

Release APKs are signed with a key generated during the build; the keystore is
attached to each release so a build can be verified. Installing a new version
over an older one needs the `versionCode` to be higher — it is, every release.

---

## BUILD

```bash
cd kotlin-app

# Android release APK (arm64 + arm32, split by ABI)
./gradlew :composeApp:assembleRelease

# debug build
./gradlew :composeApp:assembleDebug

# web, with hot reload
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

Needs JDK 17 and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in
`local.properties`). If the Gradle wrapper jar is missing — it isn't in the
original archive — regenerate it once: `gradle wrapper --gradle-version 8.11.1`.

Builds run on GitHub Actions on every push to the working branch:
[`.github/workflows/release-android.yml`](.github/workflows/release-android.yml)
builds both ABIs, signs them, records badging and signature hashes, and publishes
the release. A second job compiles the web target so a mistake in shared code
can't hide behind an Android-only build.

---

## LAYOUT

```
kotlin-app/composeApp/src/
  commonMain/kotlin/app/searitch/
    Api.kt           nine sources, streaming aggregation, related images
    App.kt           back stack, screen transitions, session host
    SearchScreen.kt  results grid, facets, filter sheet
    DetailScreen.kt  hero image, metadata, download, similar
    HomeScreen.kt    wordmark, search field, recent searches
    SettingsSheet.kt settings + the gear medallion
    Settings.kt      settings model, tone palettes, haptics
    FeedCache.kt     memory + disk feed cache, search session
    Downloader.kt    download URL/filename rules (platform impls separate)
    Theme.kt         materials, textures, tiling, straps, stitches
    Components.kt    buttons, chips, photo prints, skeletons
  androidMain/       activity, MediaStore downloads, prefs, system bars
  wasmJsMain/        browser entry point
  commonMain/composeResources/  leather, paper, grain maps, Cinzel
kotlin-app/tools/make-textures.py   builds the grain maps from assets-src/
```

---

## NOTES

- No API keys needed. Europeana uses its public demo key, which is rate-limited.
- Wallhaven and the Flickr feed send no CORS headers, so they answer in the
  Android build only. Openverse and Wikimedia work everywhere.
- If a source fails twice it's skipped for a few requests, so one dead endpoint
  can't hold up a search.
- Animations are spring-driven and transform-only, which is what lets them run at
  whatever refresh rate the display has.
