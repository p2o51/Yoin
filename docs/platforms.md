# Yoin Multi-Platform Architecture & Parity (0.6.3+)

Yoin runs natively across **four operating systems** — **Android**, **iOS & iPadOS**, **macOS** (iPad-on-Mac multiplatform), and **HarmonyOS** — backed by three music providers (**Subsonic / OpenSubsonic**, **Spotify**, and **Apple Music**).

> **Branch Note:** All four platforms — `app/` (Android `0.6.3`), `harmony/` (HarmonyOS), `shared/` (KMP), and `iosApp/` (`0.6.3`, `:shared` parity & Spotify fixes) — are unified on `main`. Native `YoinMac` is **frozen**; macOS ships via the iPadOS multiplatform target ("Designed for iPad", `Platform.current.isMac`).

---

## 1. Platform Stack & Core Sharing (`:shared`)

| Layer | Android (`app/`) | iOS & iPadOS (`iosApp/`) | macOS (iPad-on-Mac via `iosApp/`) | HarmonyOS (`harmony/`) |
| :--- | :--- | :--- | :--- | :--- |
| **UI & App Shell** | Jetpack Compose + Material 3 Expressive, `MainActivity` shell + PushPage Activities (Nav 3 two-pane on wide windows) | SwiftUI + UIKit/AppKit bridges, Liquid Glass (`YoinGlass`) + custom Metal shaders (`DotDissolve.metal`, `Grain.metal`) | Same `iosApp/` binary running under `"Designed for iPad"` (`Platform.current.isMac`), single window (`UIApplicationSupportsMultipleScenes: false`) | Flutter + Dart Material/custom UI (`harmony/lib/`), ArkTS native plugins (`harmony/ohos/entry/src/main/ets/yoin/`), plus desktop/Windows dev seam (`harmony/docs/windows.md`) |
| **Shared Logic** | Android currently retains local Room-coupled implementations for lyrics/sync while aligning contracts with `:shared` | Imports `YoinShared.xcframework` directly from `:shared` (`shared/src/commonMain`) | Same `YoinShared.xcframework` | Pure Dart ports in `harmony/lib/` matching `:shared` / Android math, tokens & wire schemas |
| **Iconography** | `com.gpo.yoin.symbols.YoinSymbols.*` from `../yoin-symbols` via Gradle `includeBuild` | Asset catalog SF Symbolsets generated from `yoin-symbols` (`WidgetSymbols.xcassets` + app symbols; see `iosApp/docs/symbols-ios.md`) | Same as iPadOS | Icon pack ported in Flutter/OHOS widgets |

### What `:shared` (KMP) Provides Today

Located in `shared/src/commonMain/kotlin/com/gpo/yoin/shared/` (on `main`):

- **Theme & Color Science (`dynamiccolor/`, `hct/`, `scheme/`, `theme/`, `tokens/`)**: Material Color Utilities `ColorSpec2025` / `SchemeExpressive`, `ExpressiveRoles`, `Palette` & `ColorCutQuantizer`, `YoinTokens`.
- **UI Math (`detail/`, `library/`, `nowplaying/`)**: `AlbumSpectrum` (cover spectrum extraction), `ScrapbookTilt`, `FastScrollMath`, `NowPlayingBudget`.
- **Subsonic Network Core (`data/subsonic/`, `data/model/`, `data/StreamQualityPolicy.kt`)**: Ktor `SubsonicClient`, auth salting/MD5, DTOs & mappers.
- **Lyrics Engine (`data/lyrics/`)**: `LrcParser`, `LyricCandidateMatcher`, `LyricCreditsExtractor`, `LyricTranslations`, `NativeTranslation`, and `LyricsRegistry` (`QQLyricsProvider`, `NetEaseLyricsProvider`, `LrclibLyricsProvider`, `HuaweiLyricsProvider`).
- **Cloud Sync (`sync/`)**: `CloudSyncCore`, `SyncEngine`, `SnapshotCodec`, `DriveAppDataClient`, `AccountBinder`, `AccountFingerprints`, `CanonicalJson`, and domain sync adapters.
- **Playlist Transfer (`backup/`, `transfer/`)**: `PlaylistSnapshots`, `TrackMatcher`, `RestoreReview`.
- **Note Text (`notes/`)**: `NoteMarkup` (light Markdown + `[@Title](yoin:kind/provider:id?a=&al=)` mentions: parse / serialize, plain-text and NeoDB projections, `rewriteMentions`) and `MentionMatcher` (name fallback). Android renders it since the notes revamp phase A and edits it (@ mentions, chips, formatting row, backlinks, several album stickies) since phase B; iOS renders it read-only since notes phase 1a (`YoinNoteReader`, the flat Swift-facing reading; `iosApp/Yoin/Notes/NoteMarkupText.swift`): NP notes, the scrapbook's notes / review / sticky, the album page's comment, the Memories diary and All Memories draw headings, lists, to-dos, bold, italic and mentions (resolved: SemiBold in the surface's mention colour, tapping opens the album's scrapbook, the song's album at its scrapbook piece, or the artist; unresolved: plain words), and Home's excerpts use the plain-text projection; iOS still edits the stored text as plain text (kept byte for byte when the words don't change) and has no @ editor, checkable to-dos or backlinks yet (gap). Since notes phase 1b iOS has the album stickies (one sticky per album note, the dashed sticky and a tap on bare paper write a new one, a tap opens one in place, clearing it deletes it with Undo; the `AlbumStickies` tip) and syncs `album_note` (shared `AlbumNoteSyncAdapter`, Drive and iCloud). HarmonyOS has a pure-Dart port (`harmony/lib/data/notes/`) checked against shared golden vectors that the Kotlin writes and verifies (`shared/src/commonTest/resources/golden/note-markup.json`, `NoteMarkupGoldenTest`), and draws notes and reviews read-only from their markup (Now Playing notes, album review, Scrapbook and its track sheet, Memories diary and ledger; mentions resolve by id then by name and open their album scrapbook / song / artist; plain-text projection for Home note cards, Memory copy and accessibility labels); its write fields still edit the stored text as plain text (no @ editor, formatting row, to-do ticking or 被提及 yet); since notes phase 1b it shows an album's several notes as Scrapbook stickies (a dashed sticky or bare paper writes one in place, a sticky edits in place, clearing deletes with Undo), keeps them per account and syncs them as `album_note` in Android's wire shape, and pushes NeoDB the projections (Review body via `toNeoDb`, short comment via `plainText`). Android-only since 0.6.4 (2026-11-24): mentions drawn as a cover thumbnail on a tinted pill with a long-press peek card, live @ filtering ranked prefix > initials > substring > artist with pinyin (full / initials, composing text included; ICU Han-Latin on Android 10+), soft-keyboard Enter picking the top row, long-press-to-edit on Now Playing's expanded Note page, scrapbook note taps opening the note (play is an explicit action), and collapsed Now Playing notes taking no mention taps (gap on iOS / HarmonyOS).

---

## 2. Subsystem Architecture Across Platforms

### Playback & Audio Output Routing

| Subsystem | Android | iOS & iPadOS | macOS (iPad-on-Mac) | HarmonyOS |
| :--- | :--- | :--- | :--- | :--- |
| **Subsonic Stream** | Media3 `ExoPlayer` (`PlaybackService`) + `OfflineFirstDataSource` (~500 MiB cache + offline downloads) | `AVAudioOutput` (`AVQueuePlayer` with preloaded next item + `CachedAudio`) | Same `AVAudioOutput` | ArkTS `PlaybackPlugin.ets` / `PlaybackHost.ets` / `EnginePlayer.ets` + `SystemSession.ets` (`AVSession`) & `ContinuousTasks.ets` (separate `AUDIO_PLAYBACK` & `DATA_TRANSFER` tasks on API 21+) |
| **Spotify** | `SpotifyAppRemotePlayer` (Android App Remote SDK) + Web API `[current, next]` start window (`SPOTIFY_START_MAX_URIS = 100`) | `SpotifyAudioOutput` (`SPTAppRemote` + Web API `[current, next]` queue) | **No `SPTAppRemote` on Mac.** `SpotifyAudioOutput` drives the macOS Spotify desktop app as a Spotify Connect device via Web API (targets active device or local Mac client; refuses idle remote devices) | **Not supported** (no Spotify App Remote SDK for HarmonyOS) |
| **Apple Music** | `AppleMusicMedia3Player` + `AppleMusicPlaybackService` wrapping MusicKit Android SDK (with 16 KB ELF RELRO patch, large catalog ID ASM patch, `AppleMusicNativeMemoryPolicy`, `MusicKitStallWatch`, `MusicKitEndGate`) | `AppleMusicAudioOutput` via native `MusicKit` framework (`ApplicationMusicPlayer`) | Native `MusicKit` framework | ArkWeb MusicKit JS overlay (`AppleMusicAuthHost.ets`, `AppleMusicAuthView.ets`) + Apple Music API playback path (`harmony/docs/applemusic.md`) |
| **External Cast / Speakers** | **Google Cast** (`CastManager`, `CastOptionsProvider`) + **DLNA** (`DlnaManager`, `DlnaMedia3Player` for Subsonic LAN speakers) | **AirPlay** (`AirPlayRoutePicker`); no Google Cast SDK | AirPlay / macOS system output | OHOS **`AVCastPickerHelper`** (`CastPlugin.ets` / `CastBridge.ets`) + LAN DLNA speaker discovery for Subsonic |
| **ReplayGain (Volume Leveling)** | `ReplayGainAudioProcessor` (`Off` / `By track` / `By album`, Subsonic tags + pre-amp + peak ceiling) | `ReplayGainSettings` via `AVAudioMix` (`AppliedReplayGain`). **Limitation:** `AVAudioMix` volume `<= 1.0`, so positive gain plays at full scale (`0 dB`) | Same as iOS (`AVAudioMix` clamp `<= 1.0`) | `harmony/lib/playback/replay_gain.dart` + `Settings › Account › Volume leveling` |
| **Sing (vocal removal)** | **Shipped** (Subsonic only): Now Playing cover menu → Sing, lyrics capsule + vocal slider (`ui/nowplaying/SingControls.kt`); `player/sing/` — on-device Lyra-Sing-Lite on **LiteRT** (router: Tensor TPU with the SoC's AOT build → GPU fp16 → CPU fp16/XNNPACK, arm64; models delivered per SoC by the on-demand Play AI Pack `:sing-model-pack` — Tensor G6/G5 AOT or fp16 — none in the base APK; `-PyoinSingBundledModels=true` test builds bundle them) separates chunk by chunk while the song plays, decoding progressively, into a memory-mapped vocal track (partial tracks kept, LRU-cached); `SingMixerAudioProcessor` in `DefaultAudioSink` subtracts it live from the playing original (X − (1−g)·V, position from Media3's `StreamMetadata` + cross-correlation alignment), or mixes a 4-ch quad `.m4a` [I_L, I_R, V_L, V_R] → stereo I + g·V for the Lyra server path (`LyraSingStreamProvider`) | **Pending** | **Pending** | **Pending** |
| **Spatial Audio** | `SpatialAudio` sets Media3 `SPATIALIZATION_BEHAVIOR_AUTO` and reports live system `Spatializer` availability | `SpatialAudioMonitor` observes system audio route / Spatial Audio capability | System audio route monitoring | Handled by OHOS audio framework |

### Credential Storage & System Backup Boundaries

> Never describe Yoin as having "no data inside system backups" globally: Android's Gemini key placement and OHOS's explicit `BackupScope` follow each OS's storage rules.

- **Android**:
  - `FileBackedProfileCredentialsStore` + `EncryptedProfileCredentialsCodec` writes one AndroidKeyStore AES-GCM encrypted JSON envelope per profile under `context.noBackupFilesDir/profile_credentials/`, automatically excluded from Android Auto Backup. NeoDB tokens use encrypted no-backup storage as well.
  - DeepSeek / Qwen / Custom AI / External Search API keys live in `noBackupFilesDir/ai_provider.json` (`FileAiProviderStore`), also excluded from Android Backup.
  - Room database (`YoinDatabase`) stores library metadata, play history, ratings, notes, reviews, and currently `gemini_config` (which means the Gemini API key **may** be included if Android Auto Backup is enabled on the device).
- **iOS & macOS**:
  - Profile credentials, NeoDB tokens, Drive auth tokens, and **all Online AI v2 provider & external search API keys** (`com.gpo.yoin.gemini`, `com.gpo.yoin.ai`) live in the **Apple Keychain** (`KeychainItem.upsert`).
  - App Group `group.com.gpo.yoin` and `keychain-access-groups` (`$(AppIdentifierPrefix)com.gpo.yoin`) in `Yoin.entitlements` share playback/memory snapshots with `YoinWidgets` and back macOS iPad-on-Mac sandboxed Keychain/GoogleSignIn persistence.
- **HarmonyOS**:
  - `StoragePlugin.ets` stores all secret credentials — Subsonic passwords, Apple Music user tokens, NeoDB tokens, Google Drive OAuth tokens, and **all Online AI v2 API keys** — in HarmonyOS **Asset Store Kit** (`SecretStore`), excluded from backup.
  - Non-secret app state uses `KeyValueStore` (`yoin.ai.prefs`, etc.) and SQLite; `YoinBackupExtension.ets` / `BackupScope.ets` govern OHOS `BackupExtensionAbility` transfers.

### Cloud Sync (`drive.appdata` & CloudKit)

See [cloud-sync.md](cloud-sync.md) for schema and conflict rules. On Google Drive (`appDataFolder`), all platforms exchange the shared `SyncContracts` wire files (`yoin-state-<deviceId>.json.gz`, `yoin-artifacts-<deviceId>-<0..3>.json.gz`, and `yoin-reset-<epoch>-<deviceId>.json`) over HTTPS (no client-side E2E encryption). On Apple platforms, Settings › Cloud sync adds a `Sync location` selector supporting Google Drive or Apple iCloud (CloudKit private database `iCloud.com.gpo.yoin`, zone `YoinSyncZone`, syncing the same logical envelope) with a 4-step verified migration (export & verify → import → verify → optional old-copy cleanup); each OS maintains its own backup-excluded local replica store:

- **Android**: Google Credential Manager / Drive OAuth -> local replica in `no_backup/yoin-sync.db`.
- **iOS & macOS**: `GoogleSignIn` -> `:shared` `CloudSyncCore` (`SyncEngine` + `DriveAppDataClient`) -> local replica at `Application Support/Yoin/sync/yoin-sync.json` (excluded from device backups).
- **HarmonyOS**: Loopback OAuth PKCE -> Dart `SyncEngine` (`CloudSyncPlugin.ets`) -> local replica at `yoin-sync.json.gz` in ability `filesDir`.

### Widgets, Live Activities & Live Views

- **Android (`app/.../widget/`)**: Jetpack Glance `PlayWidget` & `MemoryWidget`; `RestoreLiveUpdate` publishes Android Live Updates for playlist restore progress.
- **iOS & iPadOS / macOS (`iosApp/YoinWidgets/`)**: WidgetKit `PlayWidgetView` & `MemoryWidgetView` + `ActivityKit` Live Activities (`DownloadActivityViews`, `RestoreActivityViews`).
- **HarmonyOS (`harmony/ohos/.../widget/`)**: FormKit Service Cards (`服务卡片`: `PlayCard.ets`, `MemoryCard.ets` in 1×2, 2×2, 2×4 sizes via `EntryFormAbility.ets` + `CardStore.ets` + `CardsPlugin.ets`); `TransferPlugin.ets` publishes OHOS `NotificationSystemLiveViewContent` Live Views during downloads.

### Adaptive Windowing, Keyboard Shortcuts & Desktop Input

- **Adaptive Layout**: Follow [adaptive-principles.md](adaptive-principles.md).
  - Android: `MainActivity` adapts from compact handset to medium/expanded foldable/tablet with 2-column detail panes (`ui/navigation/pane/`), `feedUnits` (`N`) density, edge-bleed shelves, and a two-column unit-height Home grid (`HomeGridUnits`, side-by-side layout in Edit Home, shelf page arrows). One section order is shared by both widths; the two-column pairing is remembered apart in the layout document's optional `wide` key (design.md「两栏的并排记忆」). iOS and HarmonyOS have no two-column Home. iOS and HarmonyOS write `wide` (and any other key they don't read) back unchanged when they write a layout, as Android's single column does, and drop it only on Reset (`harmony/lib/home/home_section.dart`).
  - iPadOS / macOS: `PadShell` supports 2-column / 3-column layouts with a draggable column split handle (`iosApp/docs/ipad.md`). Single-scene on iOS/Mac (`UIApplicationSupportsMultipleScenes: false`).
  - HarmonyOS: Responsive Dart shell + `PosturePlugin.ets` (fold posture) & `WindowPlugin.ets` + desktop/Windows development runner (`harmony/docs/windows.md`).
- **Keyboard Shortcuts & Pointer**:
  - **Android (`ShellShortcuts.kt`)**: 12 hardware keyboard chords mapped to `Ctrl`/`Alt` (avoiding OS-reserved `Meta`): playback transport, volume, tabs/search, Now Playing, Lyrics, Close.
  - **HarmonyOS (`desktop_shortcuts.dart`, `desktop_input.dart`)**: Includes Android's 12 chords plus desktop shortcuts (`Ctrl+L` Now Playing panel ⇄ full window, `Ctrl+F` Now Playing focus mode, `←`/`→` seek ±5s, `Esc` back / leave search field, `Shift+F10`/`Menu` context menu; note `Ctrl+K` is Library search) plus desktop hover highlights and right-click menus.
  - **iPadOS & macOS (`YoinCommands.swift`, `PointerHover.swift`, `SecondaryClick.swift`)**: `⌘`-keyed hardware keyboard & macOS menu bar commands (Controls & Go menus, Repeat submenu, `⌘F` focus, `⌘L` lyrics, `Space` play/pause, `←`/`→` ±5s seek, `Esc` layered back) with `TextInputFocus` first-responder guards so Space/arrows never fire while typing, plus discoverable `AppIntents` (`YoinShortcuts.swift`); `PointerHover` uses subtle `.brightness()` on macOS (`Platform.current.isMac`, because iPadOS `.hoverEffect` does not propagate cleanly to Designed-for-iPad) and system hover on iPadOS; secondary-click context menus enabled across Home, Library, Search, Artist releases, Queue, and mini player.

### Continue On / Handoff

See [continue-on.md](continue-on.md):
- **Android 17 (API 37)**: `ContinueOn` (`ContinueOnPauseServer` TCP pause channel + confirmation `ContinueOnCard`).
- **iOS, iPadOS & macOS**: Apple Handoff (`NSUserActivity` type `com.gpo.yoin.playing`, 600s clock tolerance catch-up, opens directly without an in-app confirmation card).
- **HarmonyOS**: 应用接续 (`ContinueOnPlugin.ets` + `EntryAbility.onContinue` + TCP pause channel + `ContinueOnCard`).

---

## 3. Feature & Capability Parity Matrix (0.6.3+)

Legend: **Shipped** | **Partial** (scoped difference noted) | **Planned / Absent**

| Feature Area | Android (`app/`) | iOS & iPadOS (`iosApp/`) | macOS (iPad-on-Mac) | HarmonyOS (`harmony/`) |
| :--- | :--- | :--- | :--- | :--- |
| **Subsonic / OpenSubsonic** | Shipped | Shipped (via `:shared` `SubsonicClient`) | Shipped | Shipped (Dart client) |
| **Spotify** | Shipped (`SPTAppRemote` + Web API) | Shipped (`SPTAppRemote` + Web API) | **Partial** (Web API + Connect desktop client target; no `SPTAppRemote`) | **Absent** (no Spotify SDK on OHOS) |
| **Apple Music** | Shipped (MusicKit Android SDK) | Shipped (native `MusicKit`) | Shipped (native `MusicKit`) | Shipped (ArkWeb MusicKit JS + Music API) |
| **Online AI** | **Shipped (v2)**: Gemini, DeepSeek, Qwen, Custom OpenAI/Anthropic; External Search (DeepSeek/Exa/Tavily/Brave/Doubao); Good/Best tiers; "Try a song" (recent 6) | **Shipped (v2)**: Gemini, DeepSeek (`中国直连`), Qwen (`中国直连`), Custom OpenAI/Anthropic + 5 External Search providers + Good/Best tiers + "Try a song" (recent 6) | Same as iOS (AI v2) | **Shipped (v2)**: Gemini, DeepSeek, Qwen, Custom + External Search + "Try a song" |
| **Lyric Cards (`HomeSection.lyricHighlight`)** | **On-Device AI (Gemini Nano)** on supported phones | **On-Device AI (Apple Intelligence `LanguageModelSession`)** on supported devices | **Apple Intelligence** on Apple Silicon Macs supporting it | **User Picks**: explicit lines added when sharing/saving lyric cards (no local LLM) |
| **Lyrics Fetch & Localized Name Fallback** | QQ → NetEase → LRCLIB (+ Huawei manual); full `otherNames` (`alternateNames` + `Accept-Language`/ISRC + MusicBrainz ISRC + AI) + `AppleMusicNameRegion` + `LyricCandidateMatcher` + credits | **Shipped**: `:shared` `LyricsRegistry` (`LyricFetchException` transient retry) + `LyricCandidateMatcher` (`offLength` fix) + `AppleMusicNameRegion` + full PR #226 chain (`alternateNames`, `SpotifyNameLanguages`, `MusicBrainzIsrcClient`, AI release fallback) + persistent `LyricsPrefetchHost` | Same as iOS | QQ → NetEase → LRCLIB (+ Huawei manual) + `alternateNames` + `AppleMusicNameRegion` + `LyricCandidateMatcher` + `Trad->Simp` + credits (**no** MusicBrainz/AI fallback) |
| **Lyrics Translation Wave & Pluck Haptic** | Shipped (wave unroll from focus line + `performPluck` haptic) | **Shipped**: full-height `TranslationWave` glow + `CoreHaptics` `performPluck` + row/tab feedback + `16pt` settled-seat debounce | Supported (`TranslationWave` UI; desktop no-op haptics) | **Partial**: `HapticsPlugin.ets` maps 13 semantic presets (including `frequentTick`, without `performPluck`) + custom `VibratorPattern`; `lyrics_view.dart` animates per-line `expandVertically` without staggered focus-outward translation wave |
| **Home Sections (`10` total in catalog)** | All 10 (`activities`, `jump_back_in`, `recently_added`, `rediscover`, `recently_played`, `your_playlists`, `other_profiles`, `lyric_highlight`, `memory_grid`, `made_for_you`) + in-place drag reorder (`HomeDragEngine`) + pinch list (`HomeEditList`) + `ShellBarScrollHide` | **All 10**: includes `MadeForYou` (`pl.pm-*` + Spotify App Remote hub) + `ShellBarScrollHide` + `SpotifyElsewherePill` | Same as iOS (`MadeForYou` skips App Remote gracefully when unavailable) | All core sections including `OtherProfiles`, `LyricHighlight`, `MemoryGrid`, `MadeForYou` + `HomeCarryStack` + `ShellBarScrollHide` (no `SpotifyElsewherePill` since no Spotify); Jump Back In's memory 1×2s take turns like Android's `JbiMemoryTurn` (next turn on cold start or after 30 min away, shuffled deck; `jbi_memory_turn.dart`) — iOS still shows the newest memory |
| **Memories & All Memories Ledger** | Shipped (Pull-down Aurora deck, ScoreEmblem/Groove Award beats, Scrapbook, `MemoryLedgerScreen` calendar grid + pixel bar lift + Timeline/Cards/List, cross-account switch notice) | Shipped (Memories deck, Groove Award haptics, All Memories ledger, NeoDB Voices, cross-account notice) | Same as iOS | Shipped (Memories deck, Groove geometry + HD haptics pattern, All Memories ledger + pixel bar lift, cross-account notice) |
| **NeoDB Sync & "What others say" (Voices)** | Shipped (score OR review independently syncs; `<=360` short vs long review; Voices + Discogs/MusicBrainz scores + Douban/RYM/AOTY outlinks) | Shipped (`NeoDBSync` + `NeoDBVoicesView` + Discogs/MusicBrainz + outlinks) | Same as iOS | Shipped (`neodb_sync_service.dart`, review sent as the NoteMarkup projections like Android, + `NeoDbVoicesScreen` + Discogs/MusicBrainz + outlinks) |
| **Google Drive / iCloud Sync** | Shipped (Play Services / Credential Manager; replica `yoin-sync.db`, plus Cloud data export/import, per-account purge & Memories transfer) | Shipped (`:shared` `CloudSyncCore` + `GoogleSignIn` `yoin-sync.json` + Apple iCloud CloudKit private DB `iCloud.com.gpo.yoin` with 4-step location migration) | **Shipped**: `GoogleSignIn` (`keychain-access-groups`) + iCloud CloudKit | Shipped (Loopback OAuth PKCE; replica `yoin-sync.json.gz`; shared Drive wire files; applies other devices' per-account purge markers and can resume, can't start a removal yet) |
| **Playlist Cloud Backup & Restore** | Shipped + `RestoreLiveUpdate` | Shipped (`PlaylistRestore`, `TrackMatcher` `Trad->Simp`, Apple Music 401/403/429 interrupt) + `RestoreActivityViews` | Same as iOS | Shipped + `TransferPlugin` Live View |
| **Offline Downloads (Subsonic)** | Shipped + ~500 MiB playback cache | Shipped + `CachedAudio` + `DownloadActivityViews` | Same as iOS | Shipped (`offline/`) + `TransferPlugin` Live View |
| **Continue On / Handoff** | Android 17+ (`ContinueOnPauseServer` + card) | Apple Handoff (`com.gpo.yoin.playing`) | Apple Handoff | HarmonyOS 应用接续 (`ContinueOnPlugin.ets` + TCP pause channel) |
| **Widgets & Service Cards** | Glance `PlayWidget` & `MemoryWidget` | WidgetKit `PlayWidgetView` & `MemoryWidgetView` | Same WidgetKit targets | FormKit `PlayCard.ets` & `MemoryCard.ets` (`1×2`, `2×2`, `2×4`) |
| **Screen Corners & Scroll Edge** | `ScreenCorners` (0–64 dp slider + device display detection) + `ScrollEdge` | **Absent** (system handles corners/edges via iOS Liquid Glass / standard scroll) | **Absent** | `ScreenCornerControl` (for devices/emulators) + `ScrollEdge` |
| **Sing (karaoke, Subsonic only)** | **Shipped**: UI + live on-device separation on LiteRT (Tensor TPU / GPU / CPU, models from a Play AI Pack) and subtraction, 4-ch mixer and client for the Lyra server's quad `.m4a`, cache, device gate | **Absent** (pending: needs an AVAudioMix/`MTAudioProcessingTap` 4-ch mixer reading the same quad `.m4a`; Ogg Opus is not an option on iOS) | **Absent** | **Absent** |
| **Tips (`HomeBubbleTip`)** | 34 tips (with interactive real-component demos, including `MemorySearch`, `NoteMentions`, `AlbumStickies`, `MentionedIn`, `CloudExportTransfer`, `AppleMusicSongNames`, and updated `EditHome`) | **29 tips** on iPad/Mac (**28** on iPhone; includes `AiServices`, `CloudSync`, `AppleMusicSongNames`, `ICloudSync` & `AlbumStickies`; 100% live `DemoPhone` coverage; omits Android `ScreenCorners`, `ScrollEdge`, `SettingsRegrouped` & Gemini Nano diagnostics) | Same as iOS iPad (`29` tips) | **37** entries in `home_bubble_tips.dart` (`available()` returns **37** on foldable, **36** on non-foldable, **34** on desktop; includes `AppleMusicSongNames` with a live demo of the real Song names group; omits Spotify-only & Nano-only tips; covers Cast/DLNA and user-picked `LyricHighlight`) |

---

## 4. Known Provider & Platform Limits

### Spotify Limits & 2026-02 Development Mode Changes

1. **Development Mode Allowlist (`403`)**: Spotify Development Mode apps only accept user accounts explicitly added to the app's allowlist in the Spotify Developer Dashboard (other accounts receive HTTP `403` during `/me`). Yoin supports user-supplied Spotify Client IDs so listeners can run their own Development Mode app.
2. **February 2026 Web API Breaking Changes** (verified across Android & Apple):
   - Artist `top-tracks` removed -> Artist Detail "Most Played" section stays empty for Spotify.
   - Foreign / non-owned, non-collaborative playlists return HTTP `403` on item reads -> those playlists open without a tracklist and are skipped by Playlist Cloud Backup.
   - `GET /artists/{id}/albums` limited to **`10`** releases per page (artist page caps total at `100` releases; `appears_on` omitted).
   - Search capped at **`10`** results per entity type per page (`MAX_SEARCH_LIMIT = 10`).
   - Library save/remove migrated to `PUT/DELETE /v1/me/library`; `GET /v1/me/library/contains` accepts at most **`40`** URIs per request; playlist creation uses `POST /v1/me/playlists`; item add/remove uses `POST/DELETE /v1/playlists/{id}/items` (batches of `100` URIs).
3. **Sync Mirror vs Full Read Caps**:
   - **Android**: `SpotifyLibrarySyncCoordinator` caches the newest **`200`** saved albums/tracks in its local mirror for fast checks (verified by `contains` batches of `40`), and `SpotifyMadeForYou` caps content browse at `200`. Full paginated Web API reads on `SpotifyApiClient` cover up to **`2,000`** saved tracks, **`1,000`** saved albums/playlists/followed artists, **`1,000`** tracks per album/playlist detail, and **`10,000`** tracks for full playlist cloud backup. Playback start context sends at most **`100`** URIs (`SPOTIFY_START_MAX_URIS = 100`).
   - **iOS / macOS**: Paginates Spotify Liked Songs and Apple Music library songs up to **`2,000`** tracks (up from the earlier `300`-track cap), includes Spotify `isRestricted` decoding + persistent per-profile `SpotifyRateGate` backoff + `SpotifyThisDevice` local `deviceId` persistence, and reads `MadeForYou` containers via App Remote when connected (`iosApp/`).
   - **Spotify Editorial Playlists (`37i9…`)**: Return `404` on Web API under Development Mode; Android supplements Made For You via App Remote `SpotifyContentBrowser` (`CONTENT_READ_MAX = 200`) when connected.
4. **HarmonyOS**: No Spotify App Remote SDK exists for HarmonyOS, so Spotify is excluded from the HarmonyOS build.

### Apple Music Limits & Platform Quirks

- **Playback Start Window**: On multi-provider list starts (`ArtistPlayTracks.kt`), Android caps initial queue handoff to `SPOTIFY_START_MAX_URIS = 100` tracks. `AppleMusicSource` album embedded `tracks` stops at `300` items; `AppleMusicNameResolver` batches `300` songs / `100` albums per storefront lookup.
- **Android MusicKit SDK Workarounds** (see [integrations/apple-music.md](integrations/apple-music.md)): Requires 16 KB ELF RELRO alignment fix for newer Android devices, ASM patch for catalog IDs `> Int.MAX_VALUE`, `AppleMusicNativeMemoryPolicy` guards around JavaCPP native allocations, and periodic developer-token refreshes.
- **Write Constraints**: Apple Music's public API has no playlist deletion or single-track "unfavorite" mirror matching Subsonic/Spotify; adding an album/track mutates the user's Music Library (`+`) and waits for catalog-library sync to confirm `✓`.

### OS-Level Quirks

- **Android Cast SDK Telemetry**: Android integrates Google Cast SDK (`CastManager`), which transmits anonymous Cast usage and performance telemetry to Google as part of system Cast functionality (`docs/privacy-policy-draft.md`).
- **iOS/macOS `AVAudioMix` ReplayGain Ceiling**: `AppliedReplayGain` cannot amplify quiet tracks above `1.0` (`0 dB`), only attenuate loud tracks.
- **iPad-on-Mac Windowing & Hover**: Only one primary scene is supported (`UIApplicationSupportsMultipleScenes: false`), `Hover` falls back to `.brightness()`, and drag-handle cursor styling is iPad/Mac-specific (`iosApp/docs/ipad.md`).
