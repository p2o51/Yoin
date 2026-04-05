# Yoin — Android Subsonic Music Client

Lightweight, animation-first Subsonic/OpenSubsonic music client for Android.
Design doc: `docs/design.md` (source of truth for all UI decisions).

## Build & Run

- `./gradlew assembleDebug` — build debug APK
- `./gradlew test` — run unit tests
- `./gradlew connectedAndroidTest` — run instrumented tests
- `./gradlew ktlintCheck` — lint check
- `./gradlew ktlintFormat` — auto-fix lint

## Tech Stack

- Kotlin · Jetpack Compose · Single Activity + Compose Navigation
- Material 3 `1.4.x` stable + `1.5.x` alpha (Expressive opt-in)
- Media3 (ExoPlayer) + MediaSession + CacheDataSource
- Room (local ratings, cache metadata, play history)
- Architecture: MVVM (ViewModel + StateFlow + UiState sealed class)

## ⚠️ Critical Constraints

### 1. Animation is the #1 priority

Every screen transition and interaction MUST have a Spring animation.
Zero hard cuts. This is the core differentiator of the app.

- ✅ `spring(stiffness = Spring.StiffnessMediumLow)` for spatial movement
- ✅ `spring(stiffness = Spring.StiffnessLow)` for color/opacity
- ❌ `tween(300)` or `animateXAsState` with easing — NEVER use these
- ❌ Pages that appear without transition — NEVER acceptable

Use **Spatial Spring** for position/size, **Effects Spring** for color/alpha.

### 2. MD3 Expressive — search, don't guess

MD3 Expressive is newer than your training data. When unsure about any
component, animation API, or token:

1. Search m3.material.io official docs
2. Search Jetpack Compose release notes
3. Search community examples / blog posts

Do NOT guess implementation. Do NOT fall back to standard M3 when the
design doc specifies an Expressive component.

### 3. Experimental API strategy

- Core UI: stable Material3 `1.4.x`
- Expressive components: `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`
  scoped to specific Composables only
- Shape Morph: `androidx.graphics:graphics-shapes:1.0.x` (stable, no opt-in)
- Spring animations: Compose Foundation (stable, use freely)

### 4. YAGNI — only build what's in the design doc

If a feature is not listed in the MVP section of `docs/design.md`, do NOT
implement it. Do not add "nice to have" features. Do not refactor beyond
the current task scope. When in doubt, ask.

Explicitly excluded: Podcast, Internet Radio, Chat, User Management,
Jukebox, Bookmarks, Shares, Video, Playlist CRUD (phase 2).

### 5. Dark theme is default

- Default: `dynamicDarkColorScheme()` following system wallpaper
- Playing state: Palette API extracts album cover colors → replaces
  MD3 color tokens globally
- Color transitions: Effects Spring, never instant

## Code Style

- Kotlin naming conventions (camelCase functions, PascalCase classes)
- Every public Composable must have a `@Preview`
- Composables: stateless by default, state hoisted to ViewModel
- Package structure: `feature/` based (home, nowplaying, library, settings)
- Use `sealed interface` for UiState, `data class` for each state
- Prefer `StateFlow` over `LiveData`
- Subsonic API layer: separate `data/remote/` package, suspend functions
- Room entities: `data/local/` package

## Subsonic API

- Server: Navidrome (Subsonic API compatible + OpenSubsonic extensions)
- Auth: token-based (`u`, `t`, `s` parameters)
- Base URL pattern: `{server}/rest/{endpoint}?u={user}&t={token}&s={salt}&v=1.16.1&c=Yoin&f=json`
- Key endpoints (MVP): `ping`, `getAlbumList2`, `getAlbum`, `getArtists`,
  `getArtist`, `search3`, `stream`, `getCoverArt`, `getLyricsBySongId`,
  `star`/`unstar`, `getStarred2`, `getRandomSongs`, `setRating`
- See design doc for full API scope and phase 2 features

## Testing

- ViewModel: unit tests with `turbine` for Flow testing
- Composables: `@Preview` + Compose UI tests for key interactions
- Subsonic API: mock server responses, test parsing
- Name pattern: `should_expectedBehavior_when_condition`

## Navigation

- Single Activity, Compose Navigation
- 3 destinations: Home, NowPlaying (fullscreen overlay), Library
- Settings: sub-navigation from Home or Library
- Bottom Button Group (MD3 Expressive) is the only nav element
- Now Playing opens via shared element transition from Button Group center

## Key Files

- `docs/design.md` — full design specification (UI decisions are final)
- `app/build.gradle.kts` — dependencies and version catalog
- `app/src/main/.../ui/theme/` — MD3 theme, color, shape tokens
- `app/src/main/.../ui/component/` — shared components (ButtonGroup, etc.)
