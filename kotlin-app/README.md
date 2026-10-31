# Searitch — app module

Developer notes for the Compose Multiplatform app. For what the app is and how to
install it, see the [top-level README](../README.md).

Two targets, one shared codebase:

| Target | Output |
| --- | --- |
| `android` | native APK |
| `wasmJs` | Kotlin/Wasm web app |

## Requirements

- JDK 17
- Android SDK — `ANDROID_HOME`, or `sdk.dir` in `local.properties`
- No API keys

## Build

```bash
./gradlew :composeApp:assembleRelease        # APKs
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
./gradlew :composeApp:wasmJsBrowserDistribution
```

The archive this project came from has no `gradle/wrapper/gradle-wrapper.jar`.
Generate it once if you need it:

```bash
gradle wrapper --gradle-version 8.11.1
```

## Architecture

**Search** — `Api.searchStream()` is a `Flow`: each source is launched in
parallel and emitted the moment it answers. The opening batch is gathered for
320 ms and interleaved so the first screen is a real mix of sites, not one
source. A source that fails twice is skipped for five requests.

`Api.providers()` is the single place that decides which sources a query
touches. Filters are never simulated: a source that cannot honour one is left
out of that search. `COLOUR_AWARE` and `LICENCE_AWARE` are the two lists that
make that decision, and the detail screen shows the keywords `relatedKeywords()`
chose, so a "similar images" set can be explained rather than guessed at.

**State** — a `SearchSession` owns results, paging, scroll offset and errors, and
lives in a `SessionHost` above the navigation graph. That's what stops Back from
re-running a search. `SettingsState` is a single observable object that persists
itself to `PlatformStore` on every change.

**Materials** — `Theme.kt` exposes `Leather`, `Paper`, `Brass` and `Ink` as
getters over the live palette, so every call site repaints when a tone changes.
Surfaces tile their texture through a repeated `ImageShader` (see
`rememberTextureBrush`); Classic hide and Cream stock draw the original bitmaps
untinted, the other tones overlay a grey grain map on the chosen colour.

**Platform seams** — all `expect/actual`:

| Expect | Android | Wasm |
| --- | --- | --- |
| `PlatformStore` | SharedPreferences + `filesDir` | `localStorage` |
| `ImageDownloader` | MediaStore (API 29+), DownloadManager below | opens the file |
| `rememberHaptics` | view + Compose haptics | no-op |
| `PlatformBackHandler` | `BackHandler` | no-op |
| `PlatformSystemBars` | window insets controller | no-op |
| `PlatformHdrWindow` | Ultra HDR gain-map check | no-op |
| `currentTimeMillis` | `System.currentTimeMillis()` | monotonic from start-up |

## Textures

`tools/make-textures.py` builds `composeResources/drawable/leather_grain.png` and
`paper_grain.png` from the sources in `assets-src/`. It crops a seamless tile,
sharpens lightly, and normalises luminance into a narrow band around mid-grey so
the grain can be overlaid on any tone without clipping.

```bash
python3 tools/make-textures.py    # needs pillow
```

## Model

`SearitchImage` and `Filters` are `@Immutable` on purpose — grid items and rows
can then be skipped during recomposition instead of rebuilt on every scroll.

## Gotchas

- `gradle.properties` enables the configuration cache; the release workflow
  disables it for the ABI-split init script.
- The web target has no epoch clock, so cache ages are measured from start-up
  there. A negative age means "unknown" and is treated as fresh.
- `PlatformStore.attach()` must run before the first composition (see
  `MainActivity`).
