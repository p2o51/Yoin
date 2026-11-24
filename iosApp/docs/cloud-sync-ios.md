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

## iCloud (CloudKit Private Database)

Apple devices (iOS / iPadOS / macOS) also support **iCloud** as a sync location with zero OAuth sign-in:

- **Container & Entitlements**: `iCloud.com.gpo.yoin` (`com.apple.developer.icloud-services = [CloudKit]`, `com.apple.developer.icloud-container-identifiers = [iCloud.com.gpo.yoin]` in `Yoin.entitlements` / `project.yml`).
- **Storage layout (`CloudKitSyncTransport`)**: Private Database custom zone `YoinSyncZone`, record type `YoinSyncFile` keyed by `recordName = fileId`. Metadata fields (`name`, `mimeType`, `size`, `version`, `modifiedAt`, `appPropertiesJson`) live directly on the `CKRecord`; the `.json.gz` / `.json` bytes live in a `CKAsset` (`payload`), bypassing CloudKit's 1 MB record limit.
- **Zero-index, strongly consistent listing**: `list()` uses `CKFetchRecordZoneChangesOperation` with `desiredKeys` omitting `payload`, so listing never downloads `CKAsset` bytes and requires no `CKQuery` indexes in CloudKit Dashboard.
- **Switching locations**: Settings › Cloud sync › Sync location (`SyncLocationView`) runs the 4-step verified migration (export & verify SHA-256 → connect & merge target → verify uploaded files & counts → write `yoin-migrated-*` marker to the old location and optionally clear old files).
- **One-time CloudKit Dashboard step before TestFlight / App Store release**: Run a Debug build once on a signed-in Apple device (creating `YoinSyncZone` and `YoinSyncFile` in Development via Just-in-Time Schema), then open **CloudKit Console › `iCloud.com.gpo.yoin` › Deploy Schema Changes… → Production**.

## What's shared, what's iOS

Shared Kotlin (`shared/src/commonMain/kotlin/com/gpo/yoin/shared/sync`, YoinShared):

- `CanonicalJson` (+ pure-Kotlin SHA-256), `SyncContracts` (wire records, envelope, file names, appProperties), `engine/SnapshotCodec` (gzip via java.util.zip / system zlib), `engine/SyncEngine` (merge, capture, apply, publish, tombstones and the mass-delete valve, keep-both review text, epochs via the coordinator): line-for-line ports of Android's `data/sync`.
- `SyncStore` (Android's SyncDao surface) and `InMemorySyncStore` (iOS persists it as one JSON file).
- `drive/DriveAppDataClient`: Drive v3 appDataFolder on Ktor (Darwin on iOS), same requests and error mapping as Android's OkHttp client.
- `identity/AccountFingerprints` (Subsonic server + user → `s-…` scope, same bytes as Android's HttpUrl normalisation), `AccountBinder`, `CloudAccountDescriptor`.
- `adapters/*`: track rating, album rating, album review (keep-both), album title, memory copy / memory title, song note, song About, settings, paid lyric translations, home layout, playlist backups, account descriptor — Android's rules over a `SyncDomain` interface (`SharedSyncAdapters`).
- `CloudSyncCore`: Android `CloudSyncManager`'s cycle (reset markers and epochs, own-file bookkeeping, merge → capture → apply → publish, delete cloud data with its resumable marker, account actions) plus the iOS safety gate below. `CloudSyncKit` builds it from what Swift can provide.

Swift (`iosApp/Yoin/Data/Sync`, `Data/Memories`, `Settings/CloudSyncView.swift`): Google sign-in and tokens, `SyncDomainBridge` over `NotesStore`, `LocalRatings` (now with change times), `AlbumMemoryStore` (album rating / review, Memory titles and copy — new), `Data/About/SongAboutStore`, `GeminiSettings` and `SpotifyConfig` (settings), `LyricsUserStore` (paid lyric translations), `HomeLayoutStore` (home layout, per account) and `PlaylistBackupStore` (playlist backups, `SyncDomainBridge+PlaylistBackups.swift`); when cycles run (foreground once a minute, 8 s after a local edit, background with pending changes, Sync now); the store file (`Application Support/Yoin/sync/yoin-sync.json`, excluded from backup); backups; the page.

Synced as on Android: album notes (`album_note`, per account, since notes phase 1b: the shared `AlbumNoteSyncAdapter` over `AlbumNotesStore` (`Yoin/album_notes.json`); records this device carried opaquely before are written into the store on the first cycle after the update, once each, and republished unchanged), home layout (per account), playlist backups (global: every device holds every backup), paid (Gemini) lyric translations (write-once; free `provider:*` rows never sync), and two settings: `translation_language` (only while there are no About / Ask rows) and `spotify_client_id` (bootstrap-only: it arrives only on a device with no Client ID of its own and no Spotify account yet). Not applied on iOS: the `seam_top_style` setting (iOS has no Scroll edge style); its records are kept and re-published unchanged, as is any kind or key iOS has no adapter for, so nothing is lost. Each account in the account list (`Data/Profiles.swift`) is a sync profile: every Subsonic server + user (the one migrated from the single-server build keeps `ios-subsonic`), every Spotify account (its identity is the Spotify user id, `spotify:v1:<id>`, so the same user binds to the same `s-…` scope as on Android; its rows live under `spotify|<id>`; Codex N08), and `ios-applemusic` (Apple Music only after "Start syncing" / "Link", as on Android). `SyncDomainBridge` reads and writes an account that isn't in use through `AccountScope.reading`, without switching. A different Subsonic server under the same profile pauses the binding ("Paused": keep syncing as before / start fresh).

Start fresh removes the old account's synced rows from this device, so it waits for them to be in the Drive (Android `CloudSyncManager.startFreshAccount`): `CloudSyncCore.startFreshAccount` runs a cycle first, and when the old account still has changes that didn't upload (offline, a Drive error, the read-only first pull) it changes nothing and returns their count. The account stays "Paused" with both buttons and reads "Changes waiting to upload" under it (`CloudSyncFreshHold`) until they are up or the account is no longer paused; Start fresh is then asked again.

## Safety with a real account

- First cycle after turning on (and after switching Google accounts) is read-only: it pulls every peer file, maps it into the iOS stores and saves every appDataFolder file (raw bytes + `manifest.json` with ids, versions, appProperties) under `Application Support/Yoin/sync-backups/<time>/`. Nothing is written to Drive. Only when the mapping had no failures and the backup is on disk does the next cycle become two-way (`ios_two_way` in the store).
- This device only ever creates and updates its own `yoin-state-<device>.json.gz` / `yoin-artifacts-<device>-<n>.json.gz`; other devices' files are only read.
- "Delete cloud data…" (confirmed in an alert) takes another full backup before writing the reset marker.
- Settings › Storage › Cloud sync › Backups › "Back up and export" saves a fresh backup and opens the share sheet with a .zip (AirDrop it to the Mac).

## Compatibility proof

- `app/src/test/.../data/sync/SyncGoldenFixtureTest.kt` runs Android's real adapters and engine on a seeded Room DB and writes `iosApp/YoinTests/Fixtures/sync-golden.json` (+ the Kotlin mirror `shared/src/commonTest/.../golden/AndroidSyncGolden.kt`).
- `AndroidGoldenCompatTest` (JVM and iOS simulator): the shared codec re-encodes Android's files byte for byte and recomputes the same content hashes; shared adapters over the same rows produce exactly Android's records; a device joining from Android's files restores every row and republishes Android's records unchanged (including kinds it has no adapter for).
- Android's own sync tests ported to `shared/src/commonTest`: SyncEngineTest (43), SnapshotCodecTest, AccountFingerprintsTest, AccountBinderTest, plus Ktor Drive client tests and CloudSyncCoreTest (read-only first pull, reset elsewhere, Start fresh put off while the old account's changes are not uploaded). `./gradlew :shared:allTests`.

## Backup file and Memories transfer (parity p2 step B)

Settings › Cloud sync has Android's backup group: **Download all cloud data** (sync off: **Download all data**), **Import from backup** and, with sync on, **Transfer Memories**. Pages: `Settings/BackupFlowView.swift` (export → save sheet → summary; import → read → review → result) and `Settings/TransferFlowView.swift` (source → target → summary → matching → review → copy → done), built from `Settings/SyncFlowPieces.swift`. Copy is Android's, keyed by its resource names in `Localizable.xcstrings` (`SyncCopy.s` keeps Android's argument order; plurals are `%lld <unit>`).

- `Data/Sync/Portability.swift`: export (`YoinBackup.exportBackup` with the sync location, the UI language, `TimeZone.current` and the song / album names this device's plays know), unzip + `readBackup` (`ReadFailure`: not a backup / too new / checksum / unreadable), import plan and apply (global data always, like Android), the import targets (`targetOptions`), and transfer matching (`TransferMatching`: Android's matching order with the shared `TrackMatcher`; review rows → `TrackPick` / `AlbumPick`).
- `Data/Sync/BackupZip.swift`: Android BackupWriter's entries and order, DEFLATE, UTF-8 names; reads through the central directory (Android's data descriptors work too), with the manifest / snapshot size limits.
- `Data/Sync/MemoryCatalogs.swift`: the target account's catalog, opened on its own (Subsonic `search3`, Apple Music catalog + ISRC, Spotify search + `isrc:`), and the source service's ISRC when the source is on this device.
- `Data/Memories/TrackAlbumLinkStore.swift`: `track_album_links` (`Application Support/Yoin/track-album-links.json`), not synced, written by imports and transfers.
- Every call runs inside `CloudSync.withBackup` (the sync lock; no cycle meanwhile); an import or copy that wrote something syncs at once when sync is on.

Debug builds: `-screen settings -portabilityDemo <step>` opens Cloud sync and one page at a fixed step on made-up accounts (nothing is written): `page`, `export-running`, `exported`, `export-failed`, `reading`, `read-failed`, `review`, `importing`, `imported`, `import-failed`, `xfer-source`, `xfer-target`, `xfer-summary`, `xfer-matching`, `xfer-review`, `xfer-copying`, `xfer-done`, `xfer-failed`.

Not on iOS yet: transfer counts per account (Android reads them from its cloud data summary), the transfer entries on an account's row / page and after a re-sign-in, a backup file as a transfer source, the Tips entry.

## Fake Drive QA (no Google)

```sh
python3 iosApp/tools/sync/fake_drive.py --port 8765 --seed iosApp/YoinTests/Fixtures/sync-golden.json --state /tmp/fake-drive --token x
python3 ../Yoin-archive/promo-demo-2026-11-02/demo_subsonic.py 4977      # the golden's Subsonic account (demo @ 127.0.0.1:4977)
xcrun simctl launch <sim> com.gpo.yoin -subsonicURL http://127.0.0.1:4977 -subsonicUser demo -subsonicPass demo \
  -syncDebugBaseURL http://127.0.0.1:8765/ -syncDebugToken x -syncDebugAutoEnable YES
```

Debug builds only: `-syncDebugBaseURL` / `-syncDebugToken` point sync at the fake Drive with a fixed token; `-syncDebugAutoEnable YES` turns it on at launch; `-syncOpenPage YES` (with `-screen settings`) opens the Cloud sync page; `-syncDebugGoogleSignIn YES` starts the real Google sign-in at launch; `-syncDemo fresh-held` (with `-screen settings -syncOpenPage YES`, no Drive at all) shows the page with one paused account whose Start fresh comes back put off 4 s after launch. In zsh, expand a variable of arguments with `${=A}`.
