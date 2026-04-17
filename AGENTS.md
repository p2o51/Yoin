# AGENTS.md

## Scope

This file applies to the entire repository.

## Project

This repository is **Yoin**, an Android Compose music client focused on MD3
Expressive, immersive surfaces, and continuous motion. The app is multi-
provider by design — Subsonic/OpenSubsonic is the only shipped backend today,
Spotify (via Android App Remote + Web API) is planned as the second
implementation.

`docs/design.md` is the source of truth for UI and product decisions. If the
design doc and local code disagree, follow the design doc unless the task
explicitly requires a different direction.

## Build & Run

- `./gradlew assembleDebug` builds the debug APK.
- `./gradlew test` runs unit tests.
- `./gradlew connectedAndroidTest` runs instrumented tests.
- `./gradlew ktlintCheck` runs lint checks.
- `./gradlew ktlintFormat` formats Kotlin sources.

## Tech Stack

- Kotlin, Jetpack Compose, single-activity Compose Navigation
- Material 3 stable plus scoped MD3 Expressive opt-in where needed
- Media3, MediaSession, CacheDataSource
- Room for local persistence
- MVVM with `ViewModel`, `StateFlow`, and `UiState` sealed hierarchies

## Core Product Constraints

### Animation is mandatory

Animation quality is the app's primary differentiator.

- Every meaningful screen transition and interaction must animate.
- Do not introduce hard cuts when a transition should exist.
- Prefer spring-based motion.
- Use spatial springs for movement and size changes.
- Use effect springs for color and alpha changes.
- Do not default to tween-based motion for core navigation and surface changes.

### MD3 Expressive is intentional, not optional

When a task touches Expressive components, tokens, or motion APIs:

- Check `docs/design.md` first.
- Do not guess newer MD3 Expressive behavior.
- Do not silently replace an Expressive design with standard Material 3.

### YAGNI applies

Only build what is in the MVP or explicitly requested.

Do not add out-of-scope features such as podcast, radio, chat, user management,
jukebox, bookmarks, shares, video, or phase-2 playlist CRUD unless the task
explicitly asks for them.

### Dark theme defaults

- Default theme is dynamic dark.
- Playing-state palette extraction may replace global color tokens.
- Color changes should animate, not snap.

## Architecture & Code Style

- Follow Kotlin naming conventions.
- Public Composables should provide a `@Preview` unless there is a concrete
  reason not to.
- Keep Composables stateless by default and hoist state to ViewModels.
- Prefer feature-based package structure.
- Prefer `StateFlow` over `LiveData`.
- Keep provider-specific transport code in `data/remote/<provider>/`
  (e.g. `data/remote/` currently houses Subsonic — future providers get their
  own subpackage).
- Keep Room entities and local persistence code in `data/local/`.
- Use `sealed interface` and `data class` patterns for UI state modeling.

## Multi-Provider Architecture

The data layer is built to support multiple music backends behind a single
provider-agnostic interface. Phase 1 (landed) wires the abstraction and the
Profile/Room plumbing; phase 2 will migrate the ViewModel/Composable layer to
consume neutral models.

### Core abstractions

- `data/model/` — neutral domain models used by every provider. `MediaId`
  namespaces every remote id as `(provider, rawId)`; `Track`, `Album`,
  `Artist`, `Playlist`, `Lyrics`, `SearchResults`, `Starred`, `CoverRef`, and
  `PlaybackHandle` all carry `MediaId`s. Do not leak provider-shaped DTOs
  (e.g. `com.gpo.yoin.data.remote.Song`) across this boundary.
- `data/source/MusicSource` — the provider interface. Sliced by concern
  (`MusicLibrary`, `MusicMetadata`, `MusicWriteActions`, `MusicPlayback`) and
  gated by a `Capability` set so the UI can hide affordances a provider can't
  support (e.g. five-star rating for Spotify). `MusicPlayback.handleFor(track)`
  returns a `PlaybackHandle`: `DirectStream(uri)` for servers the player can
  stream directly (Subsonic), or `ExternalController(...)` for providers that
  require delegation (Spotify App Remote, phase 2).
- `data/source/subsonic/SubsonicMusicSource` — the only shipped implementation
  today. New providers (Spotify, etc.) go next to it: `data/source/spotify/`.

### Profiles & credentials

- `data/local/Profile` + `ProfileDao` — Room-backed list of configured
  accounts. Each profile carries a `provider` token and an opaque
  `credentialsJson` blob. Multiple profiles per provider are allowed (e.g.
  two Subsonic servers).
- `data/profile/ProfileCredentials` — sealed, `@Serializable`. Add a subtype
  per provider (`Subsonic`, `Spotify`, ...).
- `data/profile/ProfileCredentialsCodec` — serialises credentials. v1 uses
  `PlaintextProfileCredentialsCodec` matching the existing posture; an
  encrypted impl (EncryptedSharedPreferences / Tink) should replace it before
  shipping.
- `data/profile/ProfileManager` — owns the profile list and the active
  profile. Exposes `activeProfile: StateFlow<Profile?>` and
  `activeSource: StateFlow<MusicSource?>`. On profile switch, disposes the
  previous source and builds a new one. `migrateLegacyServerConfigIfNeeded()`
  is idempotent and runs on app start to seed a Subsonic Profile from a v3
  `server_config` row.

### Room schema & the `provider` column

Every table that references a remote entity id carries a `provider` column
and, where applicable, includes it in the primary key. This prevents cross-
provider collisions when two profiles are configured.

- `local_ratings`, `song_info`, `cache_metadata` — composite PK
  `(songId, provider)`.
- `play_history`, `activity_events` — `provider` as a regular column.
- Default value for migrated rows and for Kotlin defaults is
  `MediaId.PROVIDER_SUBSONIC`.

When adding a new table that references remote ids, always include a
`provider: String` column. DAO queries must filter on both `songId` and
`provider` (see `LocalRatingDao.getRating`, `PlayHistoryDao.getMostRecentPlay`,
`SongInfoDao.getBySongId`, `CacheMetadataDao.getBySongId`).

### When implementing a new feature that hits a remote backend

1. If the feature maps to an existing `MusicLibrary` / `MusicMetadata` /
   `MusicWriteActions` / `MusicPlayback` method, reuse it.
2. If it needs a new capability, add a method to the appropriate sliced
   interface in `data/source/MusicSource.kt`, declare a matching
   `Capability`, and implement it in every `MusicSource` subclass. Providers
   that cannot support it return `Result.failure(UnsupportedOperationException)`.
3. If the feature needs local persistence of remote ids, add a `provider`
   column to the Room entity and include it in the DAO signature.
4. Gate UI affordances on `source.capabilities.contains(Capability.X)` so
   providers that don't support the feature degrade gracefully.

### Phase 2 (not yet done — tracked here to keep the intent visible)

- Slim `YoinRepository` onto `ProfileManager.activeSource`; signatures switch
  to `MediaId` + neutral models.
- Migrate all ViewModels and Composables off `data.remote.*` DTOs onto
  `data.model.*`.
- Split `PlaybackManager.buildMediaItem` on `PlaybackHandle` so Spotify's
  `ExternalController` path can land without touching the Media3 flow.
- Add Profile list / switch / create UI to `SettingsScreen`.

## Testing

- ViewModels: unit test Flow behavior, preferably with `turbine`.
- Composables: use previews and UI tests for key interactions.
- API layer: test parsing with mocked responses.
- Test names should follow `should_expectedBehavior_when_condition`.

## Navigation Model

- The app uses single-activity Compose Navigation.
- Root destinations are Home and Library.
- Settings and detail pages are pushed destinations.
- Now Playing is a fullscreen shell-owned overlay.
- Bottom navigation is the MD3 Expressive button group.

## Predictive Back Is Part Of The Product

Back behavior is not a generic navigation detail. It is part of the page
experience and must feel consistent across the app.

For every returnable screen:

- The user should see a clear destination preview or recovery direction during
  system back.
- Gesture progress must directly drive UI state.
- Cancel and commit must use the same motion tokens.
- Custom dismiss gestures and system predictive back must share one controller.
- Pages of the same class must use the same back language.

Do not implement a page by starting with UI. First classify its back surface.

## Back Surface Types

Every new page must belong to exactly one of these categories.

### RootSection

Examples: `Home`, `Library`.

- Root content, not a pushed page
- Do not intercept root back for local UI behavior
- Must preserve system back-to-home behavior
- Do not wrap it in a local predictive back surface

### PushPage

Examples: `Settings`, `AlbumDetail`, `ArtistDetail`, `PlaylistDetail`

- Must be a pushed NavHost route
- Must use the shared PushPage back implementation
- Must expose an explicit back affordance, usually the top app bar back button
- Feature screens must not implement raw `PredictiveBackHandler`
- Do not invent page-local back curves, thresholds, scales, or corner behavior

### ShellOverlayDown

Example: `Now Playing`

- Shell-owned overlay, not a regular route
- Back semantics are "collapse downward to host/anchor"
- System predictive back, drag-to-dismiss, and dismiss buttons must share one
  controller
- Do not maintain multiple primary displacement states

### ShellOverlayUp

Example: `Memories`

- Shell-owned secondary surface
- Back semantics are "retreat upward to the home/host surface"
- System predictive back and gesture dismissal must share one controller
- Nested scroll is only an input source, not a separate motion system

## Required Design Questions Before Implementing A Screen

Before building a new page, answer these five questions:

1. Which back surface type is it?
2. Where does back return to?
3. Does it need an explicit back button?
4. Does it support gesture dismissal, and if so, does that gesture share the
   same controller as system predictive back?
5. Does it fully reuse the existing back tokens, motion, and wrappers?

If these are not answered, implementation is not ready.

## Existing Repository Constraints

- `Shell` owns the root experience and immersive overlays.
- `Settings` and detail pages are pushed routes.
- `Now Playing` and `Memories` are shell overlays, not ordinary routes.
- Default to `PushPage` unless the screen clearly expands from or collapses back
  into a shell-owned anchor.
- Do not model the same page as both a route and an overlay.
- Keep `Now Playing` as a shell overlay; do not reintroduce mixed route/overlay
  behavior.
- `Settings` must align with other pushed pages and always expose a clear back
  affordance.

## Hard Prohibitions

- Do not write raw `PredictiveBackHandler` directly in feature screens such as
  detail, settings, now playing, memories, or other feature packages unless the
  task is explicitly about back infrastructure.
- Do not implement system back and drag dismissal as separate motion systems.
- Do not add new back-related magic numbers in feature screens.
- Do not omit the explicit back button on `PushPage`.
- Do not consume root back in a way that breaks system back-to-home animation.

## Required Implementation Patterns

### PushPage

- Add a Nav route.
- Use the shared push-page back surface/wrapper.
- Provide a top-level back icon.
- Route explicit back clicks and system back to the same destination.

Prefer existing shared infrastructure such as:

- `YoinBackSurface`
- `ShellBackResolver`
- Shared back controllers
- Shared motion tokens such as `YoinMotion`

### Shell overlays

- Dismiss button, system back, and gesture input must feed the same controller.
- The screen should read one primary progress/displacement model.
- Shared element, scrim, and shell feedback should stay in the same progress
  family whenever possible.

## Pre-Submission Checklist For Screen Work

Before considering a screen task done, verify:

- The page type is explicit.
- The back destination is explicit.
- No feature screen contains raw `PredictiveBackHandler`.
- No new back magic numbers were introduced.
- The page does not maintain multiple primary displacement states.
- `PushPage` screens expose an explicit back button.
- Gesture dismissal and system back share one controller.
- Root back-to-home behavior is preserved.
- Shared back wrappers, tokens, and motion systems were reused.

## Output Expectations

When producing code for this repository:

- Prefer clear, directly usable Compose code.
- Do not scatter temporary back logic through page implementations.
- Preserve repository-wide consistency over one-off cleverness.
- Optimize for all pages feeling like they belong to the same motion system.
