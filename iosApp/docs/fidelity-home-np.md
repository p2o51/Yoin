# Home and Now Playing: Android → iOS fidelity spec

Every value below comes from the Android Compose source (1 dp = 1 pt). Paths are relative to `app/src/main/java/com/gpo/yoin/ui/`. The iOS code is in `iosApp/Yoin/NowPlaying/NowPlayingView.swift` and `iosApp/Yoin/Home/HomeView.swift`.

## Shared

| Element | Android source | Android value | iOS |
|---|---|---|---|
| Fonts | theme/Type.kt | Google Sans Flex (GSF) static cuts 400/500/600/700; letterSpacing 0, except labelMedium/labelSmall +0.1sp | Same static files, plus the variable file through `YoinFont` (CoreText `kCTFontVariationAttribute`) |
| Rounded cut | theme/Type.kt:27-44 | Variable GSF, ROND 100, at Medium or SemiBold | `YoinFont.rounded(size, semibold:)` |
| Score numerals | memories/emblem/GrooveType.kt | wght 690/640, ROND 50, wdth 76 ("10.0") or 92, opsz = size | `YoinFont.score` (cookie badge on Album/Library) |
| Color scheme | theme/CoverSeedExtractor.kt, theme/ExpressiveColorSchemeFactory.kt | AndroidX Palette (200 px, 112×112 resize, 16 colors, default filter; vibrant → dominant → muted), then MCU SchemeExpressive (SPEC_2025, contrast 0) | Faithful Swift port in `Theme/MCU/` (Palette, ColorCutQuantizer, Target; Hct, Cam16, HctSolver, TonalPalette, DynamicColor, ColorSpec2025, SchemeExpressive). `CoverScheme(album:dark:)`. **Verified:** Palette is byte-identical to Android (Robolectric, same pixels, 12 covers); MCU golden test passes 1152/1152 roles (`YoinTests/McuGoldenTests` against the fixture written by `app/src/test/.../McuGoldenFixtureTest.kt`) |
| Card backdrop colors | component/ExpressiveBackdropPalette.kt:226-333 | base = S×0.82 ∈ [0.18, 0.78], L×0.72+0.12 ∈ [0.24, 0.62]; accent = S×1.08 ∈ [0.24, 0.92], L+0.14 ∈ [0.36, 0.82] | `BackdropColors`, same formulas (accent from the mock's second cover color) |

## Now Playing (phone portrait, `CompactPlayingContent`)

| # | Element | Android source | Android value | iOS |
|---|---|---|---|---|
| — | Page background | NowPlayingAuroraBackground.kt:402 | Vertical gradient surfaceContainer → background | Frosted glass over neighbouring album colors (see iOS-only decisions) |
| — | Horizontal padding | NowPlayingScreen.kt:1263 | 24 | 24 |
| 1 | Top bar | NowPlayingScreen.kt:3840-3937; NowPlayingBudget.kt:512 | Height 56 (min 48); padding start +4 | Same |
| 1a | Collapse ⌄ | NowPlayingScreen.kt:3847 | 48 touch target, 28 glyph, onSurfaceVariant, 4 gap | Same. **iOS: no sheet grabber; ⌄ kept** |
| 1b | Playing from | NowPlayingScreen.kt:4981-5089 | labelSmall "PLAYING FROM ALBUM", onSurfaceVariant at 55%; labelMedium album name, onSurface; padding v2 h4 | Same |
| 2a | Cover | NowPlayingBudget.kt:134; theme/ContinuousRoundedCornerShape.kt:152 | min(W − 48 − 12 − 56, 312), square, `ContinuousRoundedCornerShape(16)`, smoothing 0.6 | Same size rule; `RoundedRectangle(16, style: .continuous)` (Apple's continuous corner ≈ smoothing 0.6) |
| 2b | Rating column | NowPlayingScreen.kt:1611-1657 | 56 wide, 12 gap; bar height cover − 58; 8 gap; heart 44; 6 gap | Same |
| 2c | Rating bar | component/RatingSlider.kt:98-226 | 48 wide capsule; track surfaceVariant; fill primary from the bottom; number titleLarge (22) tabular at top + 8; onSurfaceVariant → onPrimary above 80% | Same |
| 2d | Heart | NowPlayingScreen.kt:5122 | 44 circle, 24 glyph; tertiary/onTertiary when starred | Same size and roles, as tinted glass |
| 3 | Cover gap | NowPlayingBudget.kt:514 | 16 (min 8) | Same |
| 4 | Tabs | NowPlayingScreen.kt:4100-4151 | Lyrics / About / Note, labelLarge, 12 apart, height 30. Selected: Bold, scaleX 1.08. Others: α 0.5. Then a 4 gap | Same |
| 5 | Lyric window | component/LyricsDisplay.kt:208-305 | Leftover height. Active line: bodyLarge Rounded SemiBold, primary, at 22% of the window. Others: bodyMedium, onSurfaceVariant, α 0.55/0.40/0.28, scale 0.94 | Same (static mock lyrics) |
| 5' | One-line lyric | NowPlayingScreen.kt:4204 | Short screens only: 44 tall, bodyLarge Rounded SemiBold, primary | Same (`-screen np-short`) |
| 6 | Transport slot | NowPlayingBudget.kt:520; PlaybackControls.kt:934 | 148 (min 124); rows 56 + 4 + 56 | Same; buttons are tinted glass over the lyrics |
| 6b | Pause pill | PlaybackControls.kt:169-332 | 56 tall capsule, primary/onPrimary, padding h24, "PAUSE" titleLarge×0.9 = 19.8 Bold +0.5 tracking, scaleX 1.06 | Same |
| 6c | Next | PlaybackControls.kt:334 | 56 circle, primary, 28 glyph, 8 after the pill | Same |
| 6d | Repeat | PlaybackControls.kt:590-760 | 56 circle at the row end; tertiaryContainer in "repeat all" | Same (no long-press split yet) |
| 6f | Progress row | PlaybackControls.kt:452-533 | Prev 56 circle secondary; 8; elapsed (min 44); 8; wave; 8; remaining (min 52, end) | Same |
| 6g | Times | PlaybackControls.kt:539; component/DurationFormat.kt | labelLarge tabular, onSurfaceVariant, y+6, "1:14" / "-2:41" | Same |
| 6h | Wave | component/WaveProgressBar.kt:52-54, 253-364 | Box 40, wave 18; stroke 3.68; wavelength 24; amplitude 0.28; 24 dp/s | `WavyProgressBar` with `.yoin` metrics (the M3 port) |
| 7 | Hero (stacked) | NowPlayingBudget.kt:522; NowPlayingScreen.kt:4772 | Slot 86. Artist titleMedium×0.9 (14.4), onSurfaceVariant; 2 gap; title displaySmall 36/44 w400, onSurface; scaleX 1.02/1.03 | Same |
| 7' | Hero (inline) | NowPlayingScreen.kt:4700-4770 | Title, 12 gap, artist on the shared baseline | Same, in short mode |
| 8 | Pills | BottomPills.kt:100-317 | Slot 68; pill 44 tall, padding h13.2, icon 15.8, 4 gap, labelMedium; primary/onPrimary; 2 apart; Devices is rounded 20, the others capsules; "Queue" / "Devices" / "Write" | Same |
| — | Budget | NowPlayingBudget.kt:125-285 | Lower gaps squeeze first, then upper; then inline hero; then one-line lyric | `NowPlayingBudget` struct, same order |

**Which mode shows when.** The iPhone 17 Pro column is 778 pt tall, so the budget resolves to the full layout: tabs, the lyric list and the stacked hero. The Android reference shots come from a phone about 700 dp tall, which resolves to the one-line lyric with the inline hero. `-screen np-short` forces that mode for a like-for-like comparison. The height iOS has left over in that mode goes between transport and hero.

## Home (phone, feed density N = 2)

| Element | Android source | Android value | iOS |
|---|---|---|---|
| Background | HomeScreen.kt:221 | Gradient surfaceContainer → background → surfaceContainerLow | Same |
| Padding and spacing | experience/FeedUnits.kt:158; HomeEditorialContent.kt:532, 959 | 16 sides; sections 18 apart; bottom 108 | Same |
| Header | HomeEditorialContent.kt:1541; HomeGreetingTitle.kt:184-247 | Top 12, min height 48. Greeting titleMedium 16/24 Rounded Medium, onSurfaceVariant. "Home" headlineMedium 28/36 Rounded Medium. Title end padding 24 | Same |
| Avatar | HomeAccountSwitcher.kt:552-635; settings/AccountIdentity.kt:171 | 32 avatar in a 48 target, 1.5 ring in surfaceContainer, Cookie6Sided | Same shape and size (monogram stands in for the glyph and badge) |
| Section title | HomeWidgetGrid.kt:124-140 | 18sp SemiBold, line height 28, onSurface | Same |
| Card wash | HomeEditorialContent.kt:1987-2011 | lerp(surfaceContainerLow, base, 0.30), Card 16 continuous | Same |
| Hero card | HomeEditorialContent.kt:2018-2112 | Padding 14, gap 14, 96 Bun backdrop. Column gap 3: timeAgo labelSmall; title titleLarge SemiBold; subtitle bodySmall; MetaLine | Same |
| Small card | HomeEditorialContent.kt:2115-2192 | (C − 10)/3 × 118; padding 10; 48 backdrop + type/time labelSmall; title titleSmall SemiBold, 2 lines | Same |
| Wide card | HomeEditorialContent.kt:2195-2262 | 118 tall; padding 12; 80 backdrop; gap 2 | Same |
| Strip | HomeEditorialContent.kt:2265-2330 | Pill, padding 16×12; title bodyMedium SemiBold; 12 gap; subtitle at 60%; timeAgo at the end | Same (fill: low container mixed 10% with primary) |
| Entity backdrop | HomeWidgetGrid.kt:880-980 | Album = MaterialShapes.Bun, Song = Circle, Playlist = Ghostish; artwork 0.72, bottom-end, Thumb 4 | **iOS change:** no backdrop shape. Bare square cover, continuous corner 3 + 0.045 × size (≈ 7.5 at 100, 11 at 170), 0.5 inner stroke in primary at 8%, cover-tinted shadow (22%, y 6, blur 16). Artists stay circles. Activities/Rediscover covers grow into the space the shape took (hero 104, small 52, wide 88, Rediscover 104) |
| JBI grid | HomeWidgetGrid.kt:205, 473-512 | 3 columns, column gap 12, row gap 16. Signal cards alternate sides (wide-left, then compact-left) | Same packing |
| JBI 1×1 | HomeWidgetGrid.kt:772-868 | Backdrop max 100; 5 gap; title titleMedium SemiBold 16/22; subtitle bodySmall 12 | Same |
| 1×2 signal | HomeWidgetGrid.kt:608-755 | 100 cover block; 16 gap; score headlineSmall Bold tabular in ink; basis labelSmall at 85%; comment titleMedium SemiBold 17 (rated album) or bodyMedium (noted track), 3 lines | Same |
| Memory mark | HomeAlbumMemoryMark.kt:135-215 | 16 disc in background color + 12 Cookie12Sided: solid / 1.25 outline / dashed 2-1.5 at 60% | Same; position per design.md (centered on the artwork's top edge, right edges flush) |
| Recently Added | HomeEditorialContent.kt:2355-2632 | Shelf bleeds to the screen edge, item gap 14. 2×2 track grid, width clamp((C − 14) × 2.6/3.6, 226.8, 340); track tile 52 cover + bodyMedium 13 Medium + labelSmall. Album card 82 Bun | Same |
| Rediscover | RediscoverSection.kt:171-450 | Card 0.86 C × 132; padding 14; 104 Bun; eyebrow labelMedium in ink; title titleLarge SemiBold 20; MetaLine. Score badge: 24 tall pill, overhanging 6 | Same |

## iOS-only decisions

- **Home album items:** Mobbin check (Apple Music, Spotify, YouTube Music, Deezer, SoundCloud): iOS music apps show bare square covers with 1–2 lines of text, never a shape behind them. Decided (2026-11-03): bare cover (option A) is the only style; a tinted-tile alternative was tried and dropped. Title in GSF SemiBold ROND 100. The memory mark sits on the cover's top-trailing corner, inset 8. The Rediscover score stays a solid capsule.
- **Home cards as glass:** the Activities hero, small and wide cards, the strip and the Rediscover cards are Liquid Glass. Behind each card is its own cover, scaled ×1.6, blurred 26 and clipped to the card. On top is `.regular` glass tinted with Android's card wash (lerp(surfaceContainerLow, base, 0.30)) at 60%, so text keeps the solid card's contrast. Decided (2026-11-03): plain glass; a grainy "gummy" variant was tried and dropped. Covers, artist circles, the score capsule and the bare-cover grids stay solid.
- **Bottom bar:** stays Liquid Glass (Home · Now Playing pill · Library + Search circle), not Android's 68 dp surfaceContainerHigh bar. It's attached with `.safeAreaBar(edge: .bottom)` so the system soft scroll-edge effect runs under it.
  - Selected button (default): Apple Music iOS 26 style. The icon and label are in the accent on a lighter selection pill inside the glass. Accent: Home = primary, Library = tertiary.
  - Accent source: the brand scheme (MCU from YoinSeedArgb 0xFF7B3FE4, Android DefaultColorScheme.kt) when nothing is playing, the cover scheme while playing.
  - Unselected buttons are plain glass. A "tinted glass" selected style is kept in Glass Lab.
  - Nothing playing: the pill folds away and Home / Library split the bar with labels (Android idle bar).
- **Mini player progress:** Android NowPlayingPill wash, primary at 25% from the leading edge with a moving sine edge (4 pt, one wave per pill height), under the cover and text.
- **Top edge:** the system edge effect needs a real bar and the root pages hide the navigation bar, so a material band fading out over safe-area top + 22 pt approximates it. Yoin's halftone seam dots are not used on iOS.
- **Glass Lab:** a debug panel (`-glassLab 1`, or a three-finger long-press) for every glass parameter; defaults are the values above. Launch it with `iosApp/scripts/glass-lab.sh`.
- **Opening Now Playing:** the full page grows out of the bar's pill (matched geometry), with a 62 pt radius so it lands on the screen's own corners.
- **Now Playing background:** frosted glass over this album's primary, with the previous album's primaryContainer at the top edge and the next album's at the bottom (each from its own scheme). Android uses a surfaceContainer → background gradient; the iPad panel keeps that gradient.
- **Transport and heart:** Pause, Next, Prev, Repeat and the heart are Liquid Glass, interactive, at Android's sizes and positions.
  - Tint: their Android color roles (primary / secondary / tertiaryContainer / tertiary) at 55%, so the lyrics behind lens through.
  - Legibility: glyphs and the PAUSE label get a 1.5 pt black shadow at 22% so they stay readable. Each button casts a soft shadow in its own role color.
  - Grouping: Pause, Next and Prev share one GlassEffectContainer (spacing 3). They stay separate at rest; a spacing of 10 bridged Pause and Prev into one blob. They blend as interactive glass swells on press.
- **Rating rail:** the track is glass tinted with surfaceVariant at 30%; the fill (the score) stays solid primary.
- **Lyrics under the buttons:** the lyric list continues below its Android window, under the transport, fading out by the progress row. The current line always stays in the clear band above the buttons. In the short layout the clear band is Android's 44 pt one-line row and the following lines run under the buttons.
- **Dismissing Now Playing:** ⌄ and drag-down; there is no sheet grabber.

## Album detail, page 1 (phone portrait)

| Element | Android source | Android value | iOS |
|---|---|---|---|
| Header | AlbumDetailScreen.kt:928-1004 | Status bar + 4 + 52 (row) + 25 (dots) + 6; padding start 8, end 16. Back = 40 tonal circle (surfaceContainer), 22 icon, 14 gap. Title headlineSmall in the cover's primary. MetaLine [artist] [Album, W500 app primary] [year], 12 apart, bodyMedium. Two page dots 24×20 cells, 4 radius, active cover secondary | Same; back is a glass circle |
| Cover | AlbumDetailScreen.kt:639-686; AlbumSpectrumBar.kt | min(0.74 W, 300), centred, 28 below the header, Hero 8 corner | Same |
| Butterfly | AlbumSpectrumBar.kt:355-391 | Yoin mark arms 0 and 2 (cover primary and secondary), scaled 1.8, squashed, clipped at the header | Two rotated capsules approximating the arms |
| Emblem row | AlbumDetailComponents.kt:250-309 | 64 ScoreEmblem; "%d/%d rated" (labelSmall) only for Average; comment bodyMedium 3 lines + date labelMedium; empty = 40 tonal edit button | Same (ScoreEmblem ported: rings, rated arcs, dotted unrated, Cookie12 label, GrooveType score) |
| Count line | AlbumDetailScreen.kt:1245-1258 | "12 tracks  44 min" (number W500 tnum, unit 60 %), then last play labelMedium W500 | Same |
| Flowing titles | AlbumDetailComponents.kt:425-506 | headlineMedium, single-space joined, even onSurface / odd onSurfaceVariant, ≤ 16-unit titles glued | Same |
| Pull-up | DetailPullUpReshape.kt | travel 0.55 × height, release velocity ±1.6 travel/s else nearest; hero fades and lifts 40 px | Same |
| Spectrum bar | AlbumSpectrum.kt | 28 strips, OKLab k-means (k 5, 10 rounds), tone lock L' = 0.30 + 0.16 L, white ≥ 7:1, per-strip lag 0.22 | Ported (strips fade in with the lag; the per-strip flight from the cover slice is not ported) |
| Stamp | AlbumSpectrumBar.kt:125-127 | 48, end 16 from the edge, centred on the title row, corner 8 → 4 | Same |
| Track row | AlbumDetailComponents.kt:514-718 | 56 tall, padding 8 × 10, 12 gaps; number labelMedium min 18; title titleMedium; duration labelLarge m:ss; heart 28 circle in 36 (off: 1.5 outline ring, on: accent fill) | Same (heart on = mock rating ≥ 9) |
| Bottom bar | DetailBottomBar.kt; PlaySplitButton.kt | [Play split 156][8][pill]; cover primary / onPrimary; ▾ menu order Shuffle play, Play next, Add to queue, Add to playlist, Open in…, Go to artist, Share | Same, glass tinted with the cover primary |

## Search (Apple Music iOS 26 form, Android content)

| Element | Android source | Android value | iOS |
|---|---|---|---|
| Scopes | library/LibraryUiState.kt:150; strings_library.xml:10-15 | CurrentLibrary + SpotifyGlobal / AppleMusicGlobal ("Library", "Spotify", "Apple Music"); placeholders "Search library" / "Search Apple Music" | Full-width glass segmented control; the selected scope is a lighter pill |
| Type filters | library/LibrarySearchFilter.kt | All, Artists, Albums, Songs, Playlists (Playlists only where the service supports it) | Chips under the scopes once there is a query |
| All results | LibraryScreen.kt SearchAllResults | Sections Artists → Albums → Playlists → Songs | Same; bare covers, artists as circles |
| One type | LibraryScreen.kt SearchResultsGrid / SearchResultsList | Artists and Albums in a grid (16 sides, 12 / 16 gaps); Songs and Playlists in a list | Same |
| Song row | LibraryScreen.kt SearchSongRow | 48 cover, title over artist and album | Same |
| Empty | strings_library.xml:28 | "No results found" (per shown type) | Same; Android has no recent searches, so the idle state only names the scope |
| iOS form | n/a | n/a | The bar's search circle grows into a glass field (magnifier, field, mic) with a separate glass close circle. The keyboard comes up at once, the bar slides under it, and the header is a top safe-area bar so the system soft edge runs under it |

## Gaps not closed

- **Color scheme:** closed. Palette and MCU are ported and verified (see Shared).
- **Shapes:** MaterialShapes.Bun and Ghostish are hand-drawn approximations. Bun keeps the published waist and lobe points but not RoundedPolygon's corner smoothing.
- **Behavior not ported:**
  - Lyrics don't scroll or sync (static mock).
  - The repeat button has no long-press split.
  - Tapping the cover doesn't enter focus mode.
  - The title doesn't marquee.
  - Pressed pills don't widen.
  - The Home cold-start mark animation, account switcher card and memory bubble are not built.
- **Line heights:** SwiftUI can't set line height directly. Fixed-height frames mimic it where the layout depends on it (hero title 44, artist 24, section title 28); multi-line text uses SwiftUI's natural leading.
