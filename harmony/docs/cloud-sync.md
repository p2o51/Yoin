# Cloud sync on HarmonyOS

The Android feature (repo root `docs/cloud-sync.md`) ported to the Flutter app:
notes, song and album ratings, album reviews, your own Memory titles, the Home
layout and the account descriptions go into the hidden `appDataFolder` of the
user's own Google Drive, one set of files per device, merged on every device.
It is **the same wire format and the same Drive files as Android**: a Mate 70 and
a Pixel signed in to the same Google account (and an OAuth client of the same
Cloud project) read each other's data.

## What syncs here

| Kind | HarmonyOS | Notes |
| --- | --- | --- |
| `song_note` | ✔ | Plus an extra `albumId` field (Android keeps it, ignores it). |
| `track_rating`, `album_rating`, `album_review` | ✔ | Same 0-vs-“not rated” rule (`zero: true`); reviews edited on two devices at once keep both texts. |
| `album_title` (your Memory title) | ✔ | Restore Yoin's title = tombstone. |
| `home_layout` | ✔ | The sections JSON travels verbatim. Restore default now deletes the saved layout (as Android), so it syncs as “default”. |
| `account` | ✔ | Provider, name, created time, “user @ host”. Never credentials. |
| `setting` | ✔ | Translation language (written into the AI settings' prefs, field-level; declined while song About entries exist) and the scroll edge style (`seam_top_style` on the key-value store, Android's key and values). `spotify_client_id` is declined as unsupported (no Spotify here) and carried. |
| `lyrics_translation` | ✔ | Paid (Gemini) translations only, write-once, with the lyrics snapshot (`lyrics`) stamped on and seeded as Android does. The cache keeps a paid index (`yoin.lyrics.translation.paid`) since the store can't list keys; translations written before it join it when read. A paid translation of other lines is kept beside the song's slot, never replaced. |
| `playlist_backup` | ✔ | Settings › Playlist cloud backup. `content`/`versions`/`restores` travel as the exact JSON text; `sourceProfileId` never travels. |
| `song_about`, `memory_copy`, `memory_title` | carried | HarmonyOS has no such feature yet: the records are kept in the replica and published again unchanged, so an Android phone never loses them through this device. |

Play history, credentials, the Apple Music token service and caches never leave
the device. Apple Music accounts never auto-bind (start syncing or link by hand),
exactly as on Android. There is no Spotify on HarmonyOS, so its identity step is gone.

## How it is built

| Piece | Where |
| --- | --- |
| Engine (merge / capture / apply / publish, valve, keep-both) | `lib/data/sync/engine/` — line-by-line port of `SyncEngine.kt` |
| Replica store (Room `yoin-sync.db` on Android) | `lib/data/sync/sync_database.dart`: the same five tables in memory, saved as one gzip JSON (`yoin-sync.json.gz`) in the ability's `filesDir`, replaced atomically |
| Adapters | `lib/data/sync/adapters/`: writes go through the account's one `MemoryStore` (`MemoryStore.shared`, the same instance the screens get from `memoryStoreProvider`) and the `LocalRatings` notifier, so what's on screen follows a sync and a local edit never overwrites one |
| Bindings and fingerprints | `lib/data/sync/identity/` (fp:v1 byte-identical to Android: the same server gets the same `s-…` scope on both) |
| Drive REST | `lib/data/sync/drive/drive_app_data_client.dart` (Dart `http`, no logging) |
| Google sign-in | `lib/data/sync/auth/google_oauth.dart` |
| Manager (cycles, reset markers, devices, states) | `lib/data/sync/cloud_sync_manager.dart` |
| Platform seam | `lib/platform/sync/cloud_sync_host.dart` → ArkTS `ohos/entry/src/main/ets/yoin/sync/CloudSyncPlugin.ets` (`yoin/cloud_sync`); Windows: `DesktopCloudSyncHost` |
| Settings › Sync and storage › Cloud sync | `lib/settings/sync/` |

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

## Differences from Android

- No “Needs Google Play services” states; the account picker is Google's own page
  in the browser instead of the Sign in with Google sheet.
- The Settings group is “Sync and storage” with Cloud sync and Playlist cloud backup
  (NeoDB and the cache row don't exist here yet).
- The “What syncs” list leaves out Android's settings line (it names the Spotify
  Client ID, which HarmonyOS doesn't have); new wording for HarmonyOS should come
  from the owner's copy model.
- The browser's return page shows only “Yoin ✓” (no copy exists for it).
- The replica is a JSON snapshot rather than SQLite; a crash between a domain write
  and the snapshot save is repaired on the next capture, as on Android.
