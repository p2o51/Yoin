# Shared Kotlin (`:shared` → YoinShared)

Yoin's Android app (Compose) and iOS app (SwiftUI) share their design numbers and their pure algorithms through one Kotlin Multiplatform module, `:shared`. Android depends on it like any library. iOS links it as the static XCFramework `YoinShared`.

## What is shared

| Area | Kotlin (shared/src/commonMain/kotlin) | Android uses it in | iOS uses it in |
|---|---|---|---|
| Material Color Utilities (MCU) | `hct`, `utils`, `palettes`, `dynamiccolor`, `scheme`, `temperature`, `contrast`, `dislike` (the vendored Google code, moved unchanged apart from JVM-only calls) | `ExpressiveColorSchemeFactory`, `DefaultColorScheme` | `CoverScheme` via `ExpressiveRoles` |
| Cover colour roles | `com.gpo.yoin.shared.theme.ExpressiveRoles` (SchemeExpressive SPEC_2025 → ARGB per Compose role) | — (Compose builds its `ColorScheme` directly) | `CoverScheme.argbRoles` |
| Palette | `com.gpo.yoin.shared.palette` (androidx.palette's `ColorCutQuantizer`, `Palette`, `Target`, default filter) | `CoverSeedExtractor` (decode + Builder resize stay on Android bitmaps) | `CoverSeed.pick` (`Palette.scaleDownPixels` stands in for the resize) |
| Tokens | `com.gpo.yoin.shared.tokens.YoinTokens`: `Bar`, `Shape`, `NowPlaying`, `Detail`, `MetaLine`, `Home`, `Library`, `Search`, `Typography`, `Motion`, `Glass` | `FloatingBottomBar`, `WindowAdaptiveRuntime`, `Shape.kt`, `Type.kt`, `GrooveType`, `Motion.kt`, `NowPlayingMetrics`, the NP phone column, the album hero cover | `Tokens.swift` → `NowPlayingView`, `ShellBar` (split button), `YoinFont`, `GlassLab` (Balanced preset), `AlbumDetailView`, `AlbumScrapbookView`, `ArtistDetailView`, `HomeView`, `LibraryView`, `SearchView`, `TypeRoles` |
| NP height budget | `com.gpo.yoin.shared.nowplaying` (`resolveStageColumnBudget`, DualPane, landscape, `squeezeGaps`) | `ui/nowplaying/NowPlayingBudget.kt` (Dp wrapper) | `NowPlayingBudget` (CGFloat wrapper) |
| Album spectrum | `com.gpo.yoin.shared.detail.computeAlbumSpectrum` + tone lock | `ui/detail/AlbumSpectrum.kt` | `AlbumSpectrum.strips(for:)` |
| Fast scroller maths | `com.gpo.yoin.shared.library.FastScrollMath` | `FastScrollMath` in `YoinFastScroller.kt` | `LibraryFastScroller`, `LibraryView.ScrollMetrics` |
| Scrapbook tilts | `com.gpo.yoin.shared.detail.ScrapbookTilt` (FNV-1a, tilt sets) | `AlbumScrapbook.kt` | `AlbumScrapbookView` |

How it is kept honest:

- `McuGoldenFixtureTest` (Android) writes and asserts `iosApp/YoinTests/Fixtures/mcu-golden.json`. `McuGoldenTests` (iOS) checks the shared MCU against it: 1152 / 1152 roles.
- `PaletteParityTest` (Android, Robolectric) runs androidx.palette and the shared port on the same bitmaps: every swatch, the dominant swatch and the six targets match exactly.
- `SharedParityTest` (Android) compares each moved algorithm with a test-only copy of the Android code it replaced (`app/src/test/.../shared/reference`).
- `YoinTokensTest` (Android) checks every adopted token against the literal it replaced, and the mirrored type scale and motion schemes against Material's own.
- `CrossPlatformDigestTest` (`shared/src/commonTest`) folds MCU, Palette, budget, spectrum, scroller and hash results into digests. The JVM and the iOS simulator must produce the same ones (`./gradlew :shared:allTests`).
- `SharedKotlinTests` (iOS) checks the Swift wrappers: the Balanced glass preset, the tilt hash, the budget filling the column, the spectrum slots.

## iOS parity tokens (2026-11-04)

The per-screen fidelity passes (`docs/fidelity/*.md`) moved the constants iOS matched by hand into shared groups. Android doesn't read these yet; the values are the ones Android uses at the cited files, and adopting them there is a later, separate change.

- `Typography.GSF_NATURAL_LINE_HEIGHT` (1.252): Google Sans Flex's own line on iOS. `Theme/TypeRoles.swift` holds the one set of role helpers that read it: `yoinRole` (one-line roles: line box as min height), `homeType` / `homeLine` (wrapping text: the extra leading on every line), `YoinType.lineSpacing` (album and scrapbook paragraphs).
- `MetaLine`: group gap 12, Stat number → unit 4, muted 0.6. Home, Library, Search, album and artist meta lines.
- `Home`: header breathing, Activities row and gap, card wash, Jump Back In cover and gaps, Recently Added covers and tile gap, Rediscover card, backdrop, padding, shelf gap and score badge.
- `Library`: segment chips (height, corner, padding, gap, ✕ glyph), the grid (gutters, top padding, cover → title), bottom group padding, fast-scroller top inset, sort-row press bleed, song row (padding, thumb, gap), meta pill, empty state. Search reuses the chip, grid, row, pill and empty-state values.
- `Search`: filter-row padding, results bottom reserve, section header top / bottom.
- `NowPlaying`: ⌄ glyph, tab gap and selected stretch, transport gap, time-label widths and gap, one-row hero gap and artist share, rating label crossover.
- `Detail`: header back → title gap, album stamp (size, end inset, title gap), playlist ⋮ reserve and gap, the scrapbook (piece gap, cover, emblem and its overhang, overlap, sticky tuck and width cap, ticket stub and notch, dot paper, pager parallax and lean), the artist page (portrait, pinwheel band, mark scale / rest angle / turn per px, year column, collapsed count, bottom padding).

## Changing a token so both apps follow

Example: the album page's hero cover goes from 74 % of the width to 70 %.

1. Edit `shared/src/commonMain/kotlin/com/gpo/yoin/shared/tokens/YoinTokens.kt`:

   ```kotlin
   object Detail {
       const val ALBUM_COVER_WIDTH_FRACTION = 0.70
   ```

2. Android reads it in `AlbumDetailScreen` (`maxW * YoinTokens.Detail.ALBUM_COVER_WIDTH_FRACTION.toFloat()`). iOS reads it in `AlbumDetailView` (`Tokens.detail.ALBUM_COVER_WIDTH_FRACTION.cg * w`). Neither needs a code change.
3. `YoinTokensTest` pins the values Android had when it adopted the tokens. A deliberate change updates the expected value there in the same commit.
4. Build both apps (below). The iOS build regenerates the framework by itself.

Values are Doubles so iOS gets the exact decimal. Android converts them with `.dp` / `.toFloat()`, the same Float it had as a literal. In Swift they're `Tokens.<group>.<NAME>` (a `Double`; `.cg` gives a `CGFloat`).

To add a token, put it in the right `YoinTokens` group with a one-line comment. Then replace the literal on both sides. If Android adopts it, add the old literal to `YoinTokensTest`.

## Building the framework

```sh
# All: Debug + Release XCFrameworks (shared/build/XCFrameworks/{debug,release}/YoinShared.xcframework)
./gradlew :shared:assembleYoinSharedXCFramework

# What the iOS project links (Debug)
./gradlew :shared:assembleYoinSharedDebugXCFramework

# Shared tests on the JVM (Android host) and the iOS simulator
./gradlew :shared:allTests
```

JDK: Android Studio's bundled JBR (`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`). The first build downloads the Kotlin/Native toolchain into `~/.konan`.

In Xcode (`iosApp/project.yml`, regenerate with `cd iosApp && xcodegen generate`):

- The `Yoin` scheme's **build pre-action** runs `./gradlew :shared:assembleYoinSharedDebugXCFramework` before Xcode plans the build. Gradle is up to date, and takes a few seconds, when nothing in `:shared` changed. Its output goes to `iosApp/build/yoin-shared-gradle.log`.
- The `Yoin` target's **"Check YoinShared (Kotlin) is current"** build phase fails the build if the framework is missing, or older than a file in `shared/src` or `shared/build.gradle.kts`. Scheme pre-actions can't fail a build themselves, so this catches a failed Gradle run.
- `YOIN_SKIP_SHARED_BUILD=1` skips both and links the framework as it is. Use it for repeated iOS-only builds:

  ```sh
  YOIN_SKIP_SHARED_BUILD=1 xcodebuild -project Yoin.xcodeproj -scheme Yoin ... build
  ```

- The framework is static (`isStatic = true`). The app links it and nothing is embedded. `YoinTests` gets its headers only (`link: false`) and resolves the symbols through the app.

If Swift reports a Kotlin type or member missing right after you change `:shared`, delete `iosApp/build/dd/ModuleCache.noindex` (a stale clang module) and build again.

### Swift names

Kotlin/Native exports to Objective-C, so a few names change in Swift:

- `object`s and companions are `.shared` / `.companion`: `YoinTokens.Bar.shared.SPLIT_WIDTH`, `Palette.companion.generate(…)`, `Hct.companion.fromInt(argb:)`.
- Top-level functions live on `<File>Kt`: `NowPlayingBudgetKt.resolveStageColumnBudget(…)`, `AlbumSpectrumKt.computeAlbumSpectrum(pixels:n:)`.
- Enum entries are lower camel case: `LyricFrame.oneline`, `ColorSpecSpecVersion.spec2025`.
- `IntArray` / `FloatArray` are `KotlinIntArray` / `KotlinFloatArray` (`get(index:)`, `set(index:value:)`). `Int` is `Int32`, and ARGB colours come back signed (`Int(UInt32(bitPattern:))`).
- Default arguments are not exported, so Swift passes every argument.
- `DynamicScheme`'s `Int` role getters export as `primary_` and so on, because they clash with `MaterialDynamicColors`. Use `ExpressiveRoles` instead.

## What is still done twice

Only numbers and pure functions are shared. Everything that is UI is written once per platform:

- **Layout and structure.** Compose screens and SwiftUI views are separate code: Home, Library, Now Playing, the detail pages, the bars, Search, the scrapbook. They read the same tokens and call the same algorithms, but the view trees, modifiers, animations and gestures are per platform.
- **Motion.** Android takes its spatial and effects springs from M3's `MotionScheme`; `YoinTokens.Motion` mirrors them. iOS still uses its own `Yoin.spatial` / `spatialFast` / `effects` (SwiftUI response + damping). To align them, convert with `response = 2π / √stiffness`.
- **Image decoding and scaling.** Android decodes with Coil and resizes with `Bitmap.createScaledBitmap`. iOS renders with `ImageRenderer` and resizes with `Palette.scaleDownPixels`, which is pixel-exact against Skia for the 200 px decode both apps use.
- **Glass.** It is iOS-only. Only the preset values are shared.
- **Fonts and shapes.** Each platform loads Google Sans Flex itself (Compose `FontVariation`, CoreText variation axes), using the shared axis values. Continuous corners, MaterialShapes and the Yoin mark paths are drawn per platform.
- **Remaining literals.** Values not yet in `YoinTokens` stay as literals on each side: Home card insides (text gaps, small/wide card padding, memory marks), one-off text sizes and line heights, the scrapbook's piece-local insets and ticket widths, the album header paddings, the iOS-only glass and bar sizes. Move them over when you next touch them. `fidelity/*.md` maps them to Android.

## Release builds and motion (2026-11-03)

- Xcode links `shared/build/XCFrameworks/current/YoinShared.xcframework`, a symlink the scheme pre-action points at `debug` or `release` to match `$CONFIGURATION` (Release / Archive run `:shared:assembleYoinSharedReleaseXCFramework`). Verified: Debug (simulator), Release (generic simulator) and Release (generic iOS device, unsigned) all build. The framework has arm64 slices only, so simulator builds exclude x86_64 (`EXCLUDED_ARCHS[sdk=iphonesimulator*]`).
- Motion: Glass Lab › Motion switches `Yoin.spatial / spatialFast / effects` between the current iOS springs (default) and Android's `YoinTokens.Motion` springs (expressiveDefaultSpatial, expressiveFastSpatial, expressiveDefaultEffects; damping ratio → SwiftUI damping = ratio × 2√stiffness, mass 1).
- Decision (51, 2026-11-03): iOS deliberately does **not** use `YoinTokens.Motion`. It keeps its own platform springs (`Yoin.spatial` 0.42 s / 0.78, `spatialFast` 0.3 s / 0.72, `effects` 0.35 s / 1.0). The Glass Lab Motion toggle stays (default "Current iOS") for future comparison only.
