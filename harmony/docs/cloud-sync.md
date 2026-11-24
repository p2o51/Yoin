# Cloud sync on HarmonyOS

The Android feature (repo root `docs/cloud-sync.md`) ported to the Flutter app:
notes, album notes, song and album ratings, album reviews, your own Memory titles, Yoin's AI
Memory titles and narration, the Home layout and the account descriptions go
into the hidden `appDataFolder` of the user's own Google Drive, one set of files
per device, merged on every device.
It is **the same wire format and the same Drive files as Android**: a Mate 70 and
a Pixel signed in to the same Google account (and an OAuth client of the same
Cloud project) read each other's data.

## What syncs here

| Kind | HarmonyOS | Notes |
| --- | --- | --- |
| `song_note` | ✔ | Plus an extra `albumId` field (Android keeps it, ignores it). |
| `album_note` (album stickies, several per album) | ✔ | Android's payload as is (`albumId`, `provider`, `content` markup, `createdAt`, `updatedAt`, `albumName`, `artist`), key = note id; last edit wins, a live edit beats a concurrent delete, deletes travel as tombstones. Kept per account in `album_notes.v1.<account>`. |
| `track_rating`, `album_rating`, `album_review` | ✔ | Same 0-vs-“not rated” rule (`zero: true`); reviews edited on two devices at once keep both texts. |
| `album_title` (your Memory title) | ✔ | Restore Yoin's title = tombstone. |
| `home_layout` | ✔ | The sections JSON travels verbatim. Restore default now deletes the saved layout (as Android), so it syncs as “default”. |
| `account` | ✔ | Provider, name, created time, “user @ host”. Never credentials. |
| `song_about` (About, Ask answers, your own About entries) | ✔ | Global like Android's table; the AI's rows are stamped with the answer language and only applied in that language, your own entries in every language. Deletes never propagate (a removed row is a blank one). |
| `setting` | ✔ | Translation language (written into the AI settings' prefs, field-level; declined while song About entries exist) and the scroll edge style (`seam_top_style` on the key-value store, Android's key and values). `spotify_client_id` is declined as unsupported (no Spotify here) and carried. |
| `lyrics_translation` | ✔ | Paid (Gemini) translations only, write-once, with the lyrics snapshot (`lyrics`) stamped on and seeded as Android does. The cache keeps a paid index (`yoin.lyrics.translation.paid`) since the store can't list keys; translations written before it join it when read. A paid translation of other lines is kept beside the song's slot, never replaced. |
| `playlist_backup` | ✔ | Settings › Playlist cloud backup. `content`/`versions`/`restores` travel as the exact JSON text; `sourceProfileId` never travels. |
| `memory_copy`, `memory_title` (Yoin's AI narration and AI Memory title) | ✔ | Two kinds over one `MemoryCopyCache` row, each writing only its own half; the prompt hashes travel along, so the other device reuses the words instead of asking the model again. Never deleted. Album rows only (Android's only entity type today); any other is carried. The Memories deck, Home's memory card and the album page read them again after a sync. |

Play history, credentials, the Apple Music token service and caches never leave
the device. Apple Music accounts never auto-bind (start syncing or link by hand),
exactly as on Android. There is no Spotify on HarmonyOS, so its identity step is gone.

## How it is built

| Piece | Where |
| --- | --- |
| Engine (merge / capture / apply / publish, valve, keep-both) | `lib/data/sync/engine/` — line-by-line port of `SyncEngine.kt` |
| Replica store (Room `yoin-sync.db` on Android) | `lib/data/sync/sync_database.dart`: the same five tables in memory, saved as one gzip JSON (`yoin-sync.json.gz`) in the ability's `filesDir`, replaced atomically |
| Adapters | `lib/data/sync/adapters/`: writes go through the account's one `MemoryStore` (`MemoryStore.shared`, the same instance the screens get from `memoryStoreProvider`), its one `MemoryCopyCache` (`MemoryCopyCache.shared`) and the `LocalRatings` notifier, so what's on screen follows a sync and a local edit never overwrites one |
| Bindings and fingerprints | `lib/data/sync/identity/` (fp:v1 byte-identical to Android: the same server gets the same `s-…` scope on both) |
| Drive REST | `lib/data/sync/drive/drive_app_data_client.dart` (Dart `http`, no logging) |
| Google sign-in | `lib/data/sync/auth/google_oauth.dart` |
| Manager (cycles, reset and scope purge markers, devices, states) | `lib/data/sync/cloud_sync_manager.dart` |
| Platform seam | `lib/platform/sync/cloud_sync_host.dart` → ArkTS `ohos/entry/src/main/ets/yoin/sync/CloudSyncPlugin.ets` (`yoin/cloud_sync`); Windows: `DesktopCloudSyncHost` |
| Per-account cloud data (counts, browser; Android `CloudDataViews`) | `lib/data/sync/cloud_data_views.dart` |
| Settings › Sync and storage › Cloud sync | `lib/settings/sync/` (one account's cloud data: `cloud_account_page.dart`) |

When it runs: when the app comes to the front (at most once a minute), about 8 s
after a local change to a synced row (the observed key-value store, a playlist
backup, or a paid lyric translation in the lyrics cache), when the app
leaves with changes waiting, and on Sync now. As on Android, no background job.

### Google sign-in without Play services

OAuth 2.0 for native apps (RFC 8252) with PKCE (RFC 7636):

1. Dart listens on `http://127.0.0.1:<random port>`.
2. ArkTS opens Google's consent page in the system browser (a view-data Want) and
   holds a transient task (`backgroundTaskManager.requestSuspendDelay`) so the app
   is not suspended while the user signs in.
3. Google sends the browser back to the loopback address with the code; Dart checks
   `state`, answers with a tiny “Yoin ✓” page, and exchanges code + verifier for
   tokens at `oauth2.googleapis.com`.
4. Only the refresh token is kept, in the Asset Store (`SecretStore`). Turning sync
   off on this device forgets it; the Google grant itself stays (other devices need
   it to read a reset marker), as on Android.

Coming back to Yoin without finishing on Google's page cancels the wait after 3 s.

## What the owner must do

The Android app uses an **Android**-type client (package + SHA-1) and a Web client
for the account sheet; neither works here (no Play services; a Web client needs a
secret and registered redirect URIs). HarmonyOS needs its own client **in the same
Google Cloud project** as Android's (otherwise it sees a different, empty
`appDataFolder`):

1. Google Cloud console → the project that holds Android's clients
   (Web client `747031130557-…`) → APIs & Services → Credentials →
   **Create credentials → OAuth client ID → Application type: Desktop app**,
   name it e.g. “Yoin HarmonyOS”. Desktop clients accept any loopback redirect, so
   there is nothing to register.
2. Note the client ID and the client secret Google shows. (Google documents a
   desktop client's secret as not confidential, but keep it out of git anyway.)
3. The consent screen and the Drive API are already set up for Android (scope
   `drive.appdata`, published). Nothing else to change.
4. Build with the client:

   ```sh
   cat > harmony/google_oauth.local.json <<'JSON'
   {"YOIN_GOOGLE_CLIENT_ID": "<id>.apps.googleusercontent.com", "YOIN_GOOGLE_CLIENT_SECRET": "<secret>"}
   JSON
   flutter build hap --release --dart-define-from-file=google_oauth.local.json
   ```

   `google_oauth.local.json` is git-ignored. Without the defines the app builds and
   runs, and Cloud sync says “Cloud sync isn't available in this build of Yoin.”
   with the package name under Details.

## Export, import from backup, Transfer Memories

Android's「导出与导入」and「转移 Memories」(repo root `docs/cloud-sync.md`), the same
file format and rules, so a backup made on any platform imports on any other.

| Piece | Where |
| --- | --- |
| The core, ported from `:shared` `portability/` | `lib/data/portability/`: `backup_format.dart` (manifest, dates), `backup_files.dart` (`BackupExport`, `BackupParser`), `readable_export.dart`, `snapshot_assembler.dart`, `backup_import.dart` (`ImportPlanner`, `BackupMerger`), `memories/` (`MemoryItems`, `TransferRules`, `MigrationIds`, `MemoryTransferApplier`) |
| Golden checks | `test/data/portability/backup_export_golden_test.dart`, `backup_import_golden_test.dart` (on the real sync adapters and stores), `memories_transfer_golden_test.dart`, against `shared/src/commonTest/resources/golden/*.json`; regenerate those only from the Kotlin side |
| The ports on this app's stores | `harmony_stores.dart`: `MemoryStore`, `MemoryCopyCache`, the `LocalRatings` notifier, `TrackAlbumLinkStore`, plays (names for the readable Markdown, facts for matching) |
| Matching for transfers | `memories/memory_matching.dart` (Android `MemoryTransfer.plan`: ISRC, `TrackMatcher` search, title only; albums by the votes of matched songs, then album search) over the target account's `MusicSource` (`SourceMemoryCatalog`) |
| Service | `memories_portability.dart` (`MemoriesPortability`), `portability_providers.dart`; runs under the sync lock (`CloudSyncManager.runPortability`) |
| Zip | `backup_zip.dart`, pure-Dart `archive` 3.x (MIT; 4.x pulls in `posix`/FFI) |
| Files | `backup_file_io.dart` → `lib/platform/share/document_files.dart` → ArkTS `SharePlugin.ets` `saveFile` / `pickFile` (`picker.DocumentViewPicker`; Android's SAF CreateDocument / OpenDocument) |
| Screens | `lib/settings/sync/`: `backup_screens.dart` / `backup_view_model.dart` (export, import review per account: into an account here / keep in the cloud only / skip, global data, SHA-256 status, result), `transfer_screens.dart` / `transfer_view_model.dart` (source, target, summary, matching, review, result), `portability_strings.dart` (Android's EN + zh, keys in comments) |
| Entries | Cloud sync page: a "Backup file" group with sync off (Download all data, Import from backup, Transfer Memories; Android keeps Transfer on each account's page, but HarmonyOS ships with sync off) and Android's backup group with sync on; an account's cloud data page has Transfer Memories |

Cloud sync is off in builds without the Google client (above), so all three work
without it:

- With no sync store (sync never turned on) nothing creates one: scopes are the ones a
  binding would get (Subsonic fingerprint, else `local-<account id>`), the transfer
  ledger is kept on the key-value store (`memories_transfer_ledger.v1`, the same keys
  Android keeps in `sync_meta`), and backups are signed with a device id kept under
  `portability_device_id.v1`. The manifest's location is `this-device`.
- "Keep in the cloud only" is offered only when a sync store exists (as Android: it
  merges into the replica, uploaded by the next sync). Accounts only in the cloud are
  transfer sources only while sync is on.
- There is no Spotify here. A backup's (or the replica's) Spotify accounts are carried
  into exports untouched; on import they can only stay in the file or go cloud-only,
  and they can be the source of a Memories transfer into an account on this device.
- Yoin's AI words are kept for albums only (Android's only entity type today); a
  transfer leaves any other entity type out.
- Dates in the readable Markdown and the file name use the device's time zone (Dart
  has no tz database; the golden tests carry the two zone rules they use).

## Differences from Android

- No “Needs Google Play services” states; the account picker is Google's own page
  in the browser instead of the Sign in with Google sheet.
- The Settings group is “Sync and storage” with Cloud sync and Playlist cloud backup
  (NeoDB and the cache row don't exist here yet).
- The “What syncs” list leaves out Android's settings line (it names the Spotify
  Client ID, which HarmonyOS doesn't have); new wording for HarmonyOS should come
  from the owner's copy model.
- The browser's return page shows only “Yoin ✓” (no copy exists for it).
- Remove from the cloud (one account, all devices) works both ways, exactly as
  `CloudSyncCore` / Android (`docs/cloud-sync.md`「从云端移除一个账号」). Started here
  (`CloudSyncManager.removeAccountFromCloud`, from an account row's ⋮ or its cloud
  data page, after Android's confirmation): the same Google account check, a
  `yoin-purge-<epoch>-<device>.json` marker with Android's `appProperties` and body,
  the epoch at or after every version of the account here, after any earlier marker
  of it and any purge applied here, not before the reset epoch; then the purge is
  applied locally and this device re-publishes at once. Markers other devices write
  are applied before peers are merged: the account's records and descriptor leave
  the replica, its binding becomes Removed from the cloud (not captured, applied or
  described; a profile added later binds that way too), this device's files carry
  `"purges"`, and records of the scope are taken only from files that acknowledge
  the purge and only after its epoch. Local data stays. Resume syncing on the
  account row joins the scope again, stamped after the epoch; Delete cloud data
  deletes the markers and clears the removal. The engine is checked against the
  golden vectors `:shared` writes (`shared/src/commonTest/resources/golden/sync-purge.json`,
  `test/data/sync/sync_purge_golden_test.dart`); a marker's numbers are read as
  kotlinx reads a Long (an integral number, or a string like `"5"`).
- The cloud data page of one account (tap an account row, or ⋮ › View cloud data)
  is Android's `CloudAccountPage` (Transfer Memories from it included) without the
  crumb (Harmony's sub-pages have none). Names
  come from the account's own notes and album notes only (Android also asks this
  device's plays). As on Android, the Notes tab lists song notes while the counts
  include album notes too. Android's All files and Global data pages aren't ported.
- The replica is a JSON snapshot rather than SQLite; a crash between a domain write
  and the snapshot save is repaired on the next capture, as on Android.
