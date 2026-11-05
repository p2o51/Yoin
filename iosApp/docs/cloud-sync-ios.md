# Cloud sync on iOS

Product rules: `docs/cloud-sync.md`. This page covers what the iPhone app adds and how to check it.

## Google client

| | |
|---|---|
| Google Cloud project | `yoin-510700` (number 747031130557), the same project as Android's clients, so both apps see one appDataFolder |
| iOS OAuth client ID | `747031130557-i0s8qtqf65u29nabsbsnfls42a10mncr.apps.googleusercontent.com` |
| URL scheme (reversed ID) | `com.googleusercontent.apps.747031130557-i0s8qtqf65u29nabsbsnfls42a10mncr` |
| Bundle / team | `com.gpo.yoin` / `M75T74PTC4` |
| Scope | `https://www.googleapis.com/auth/drive.appdata` only |

Both values live in `iosApp/Config/GoogleSignIn.xcconfig` (an iOS client ID is public, not a secret). `project.yml` uses that file as the Yoin target's config and writes `GIDClientID` and the `CFBundleURLTypes` scheme into Info.plist. When the values say `YOIN_GOOGLE_NOT_CONFIGURED`, the Cloud sync page shows "Not configured" and sign-in is off. Sign-in uses GoogleSignIn-iOS 9.x (Swift Package, `project.yml › packages`); `.onOpenURL` hands Google's redirect back (`App/YoinApp.swift`); tokens are refreshed before each Drive call (`refreshTokensIfNeeded`).

## What's shared, what's iOS

Shared Kotlin (`shared/src/commonMain/kotlin/com/gpo/yoin/shared/sync`, YoinShared):

- `CanonicalJson` (+ pure-Kotlin SHA-256), `SyncContracts` (wire records, envelope, file names, appProperties), `engine/SnapshotCodec` (gzip via java.util.zip / system zlib), `engine/SyncEngine` (merge, capture, apply, publish, tombstones and the mass-delete valve, keep-both review text, epochs via the coordinator): line-for-line ports of Android's `data/sync`.
- `SyncStore` (Android's SyncDao surface) and `InMemorySyncStore` (iOS persists it as one JSON file).
- `drive/DriveAppDataClient`: Drive v3 appDataFolder on Ktor (Darwin on iOS), same requests and error mapping as Android's OkHttp client.
- `identity/AccountFingerprints` (Subsonic server + user → `s-…` scope, same bytes as Android's HttpUrl normalisation), `AccountBinder`, `CloudAccountDescriptor`.
- `adapters/*`: track rating, album rating, album review (keep-both), album title, memory copy / memory title, song note, song About, account descriptor — Android's rules over a `SyncDomain` interface.
- `CloudSyncCore`: Android `CloudSyncManager`'s cycle (reset markers and epochs, own-file bookkeeping, merge → capture → apply → publish, delete cloud data with its resumable marker, account actions) plus the iOS safety gate below. `CloudSyncKit` builds it from what Swift can provide.

Swift (`iosApp/Yoin/Data/Sync`, `Data/Memories`, `Settings/CloudSyncView.swift`): Google sign-in and tokens, `SyncDomainBridge` over `NotesStore`, `LocalRatings` (now with change times), `AlbumMemoryStore` (album rating / review, Memory titles and copy — new) and `Data/About/SongAboutStore`; when cycles run (foreground once a minute, 8 s after a local edit, background with pending changes, Sync now); the store file (`Application Support/Yoin/sync/yoin-sync.json`, excluded from backup); backups; the page.

Not synced from iOS yet (kept and re-published unchanged, so nothing is lost): home layout, settings, AI lyric translations, playlist backups. Each account in the account list (`Data/Profiles.swift`) is a sync profile: every Subsonic server + user (the one migrated from the single-server build keeps `ios-subsonic`), every Spotify account (its identity is the Spotify user id, `spotify:v1:<id>`, so the same user binds to the same `s-…` scope as on Android; its rows live under `spotify|<id>`; Codex N08), and `ios-applemusic` (Apple Music only after "Start syncing" / "Link", as on Android). `SyncDomainBridge` reads and writes an account that isn't in use through `AccountScope.reading`, without switching. A different Subsonic server under the same profile pauses the binding ("Paused": keep syncing as before / start fresh).

## Safety with a real account

- First cycle after turning on (and after switching Google accounts) is read-only: it pulls every peer file, maps it into the iOS stores and saves every appDataFolder file (raw bytes + `manifest.json` with ids, versions, appProperties) under `Application Support/Yoin/sync-backups/<time>/`. Nothing is written to Drive. Only when the mapping had no failures and the backup is on disk does the next cycle become two-way (`ios_two_way` in the store).
- This device only ever creates and updates its own `yoin-state-<device>.json.gz` / `yoin-artifacts-<device>-<n>.json.gz`; other devices' files are only read.
- "Delete cloud data…" (confirmed in an alert) takes another full backup before writing the reset marker.
- Settings › Storage › Cloud sync › Backups › "Back up and export" saves a fresh backup and opens the share sheet with a .zip (AirDrop it to the Mac).

## Compatibility proof

- `app/src/test/.../data/sync/SyncGoldenFixtureTest.kt` runs Android's real adapters and engine on a seeded Room DB and writes `iosApp/YoinTests/Fixtures/sync-golden.json` (+ the Kotlin mirror `shared/src/commonTest/.../golden/AndroidSyncGolden.kt`).
- `AndroidGoldenCompatTest` (JVM and iOS simulator): the shared codec re-encodes Android's files byte for byte and recomputes the same content hashes; shared adapters over the same rows produce exactly Android's records; a device joining from Android's files restores every row and republishes Android's records unchanged (including kinds it has no adapter for).
- Android's own sync tests ported to `shared/src/commonTest`: SyncEngineTest (43), SnapshotCodecTest, AccountFingerprintsTest, AccountBinderTest, plus Ktor Drive client tests and CloudSyncCoreTest (read-only first pull, reset elsewhere). `./gradlew :shared:allTests`.

## Fake Drive QA (no Google)

```sh
python3 iosApp/tools/sync/fake_drive.py --port 8765 --seed iosApp/YoinTests/Fixtures/sync-golden.json --state /tmp/fake-drive --token x
python3 ../Yoin-archive/promo-demo-2026-11-02/demo_subsonic.py 4977      # the golden's Subsonic account (demo @ 127.0.0.1:4977)
xcrun simctl launch <sim> com.gpo.yoin -subsonicURL http://127.0.0.1:4977 -subsonicUser demo -subsonicPass demo \
  -syncDebugBaseURL http://127.0.0.1:8765/ -syncDebugToken x -syncDebugAutoEnable YES
```

Debug builds only: `-syncDebugBaseURL` / `-syncDebugToken` point sync at the fake Drive with a fixed token; `-syncDebugAutoEnable YES` turns it on at launch; `-syncOpenPage YES` (with `-screen settings`) opens the Cloud sync page; `-syncDebugGoogleSignIn YES` starts the real Google sign-in at launch. In zsh, expand a variable of arguments with `${=A}`.
