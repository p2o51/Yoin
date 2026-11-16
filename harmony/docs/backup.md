# Backup and moving to a new phone

Android Yoin takes part in Auto Backup and device transfer
(`app/src/main/res/xml/data_extraction_rules.xml`, `YoinBackupAgent.kt`):
`yoin-database` and `yoin_profiles.xml` travel, credentials stay in
`noBackupFilesDir`, and `NeoDbRestoreGuard` drops the old phone's NeoDB dirty
flags after a restore. HarmonyOS Yoin does the same through a
`BackupExtensionAbility`, so Phone Clone (手机克隆) and a Huawei Cloud backup
restore bring back the user's data instead of an empty Yoin.

## What travels

| Travels | Where it lives here | Android table |
| --- | --- | --- |
| Accounts (name, server, kind) and which one is in use | `profiles.v1`, `profiles.active.v1` | `profiles`, `yoin_profiles.xml` |
| Song ratings | `local_ratings.<account>` | `local_ratings` |
| Album scores and reviews (with their NeoDB flags, cleared on arrival) | `album_ratings.v1.<account>` | `album_ratings` |
| Notes | `song_notes.v1.<account>` | `song_notes` |
| Plays: Memories' candidates, Most Played | `album_plays.v1.<account>`, `track_plays.v1.<account>` | `play_history` |
| Activity (Home's Jump Back In, recents) | `activity.v1.<account>` | `activity_events` |
| Memory titles, Yoin's AI copy | `memory_titles.v1.<account>`, `memory_copy_cache.v1.<account>` | `album_memory_title`, `memory_copy_cache` |
| Home's layout | `home_layout.v1.…`, `home_layout.updatedAt.v1.…` | `home_layout` |
| Track ↔ album links, lyric highlights | `track_album_links.v1.<account>`, `lyric_highlights.v1.…` | `track_album_link`, `lyric_highlight` |
| Song About answers, AI settings (no keys) | `song_about.v1`, `yoin.ai.prefs` | `song_about`, `gemini_config` |
| NeoDB instance and album ↔ item map (no token) | `neodb.instance`, `neodb.external_mappings.v1` | `neodb_config`, `external_mappings` |
| Lyrics, the user's picks, paid translations | `yoin.lyrics.*` (both Preferences files) | `lyrics_cache`, `lyrics_translation_cache` |
| Playlist backups | `files/playlist_backups/` | `playlist_backup` |

Stays on the phone:

- **Secrets**: account passwords and tokens, the NeoDB token, AI keys, the Google
  refresh token. They are in Asset Store, which never leaves the device (Android:
  `noBackupFilesDir`). After a move the account asks to be reconnected (Home's
  avatar dot, Reconnect on the account card, `lib/settings/account_reconnect.dart`)
  and NeoDB and cloud sync are signed out.
- **This phone's settings and hints**: scroll edge style, screen corners, Tips
  read, streaming and download quality, ReplayGain, spatial audio, keep screen
  on, Library sort, Apple Music name region, the landing (an install with
  accounts skips it anyway). Android keeps all of these in SharedPreferences
  outside its backup.
- **Device state**: cloud sync's replica (`haps/entry/files/yoin-sync.json.gz`,
  Android's separate `yoin-sync` database), scrobbles waiting to be sent, a
  Continue On hand-off, offline downloads, Home's snapshot, the detail, stream
  and cover caches, Made for You.

Android also carries its re-fetchable caches (Spotify home rows, the detail
cache, the Home grid pools) because they share the Room file; its own rules call
that waste. Here they stay, except lyrics, whose rows hold the user's picks and
paid translations.

## How it works

| Piece | Where |
| --- | --- |
| The extension (`type: backup`) | `ohos/entry/src/main/ets/yoin/backup/YoinBackupExtension.ets`, declared in `module.json5` |
| What the system copies | `ohos/entry/src/main/resources/base/profile/backup_config.json`: `files/backup/` (the payload) and `files/playlist_backups/` |
| What the payload carries | `ets/yoin/backup/BackupScope.ets`; Dart mirror `lib/data/backup/device_backup_scope.dart` (a test keeps them equal and checks every store's key against it) |
| Export / import | `ets/yoin/backup/BackupTransfer.ets` |
| NeoDB restore guard | `lib/data/neodb/neodb_restore_guard.dart`, run once per launch by `neoDbRestoreGuardProvider` (`lib/neodb/neodb_providers.dart`, listened to in `main.dart`) |

Yoin keeps its data as values in two Preferences files (`yoin_prefs`,
`yoin_cache`), and Android's backup carries some of the matching data, not all.
A Preferences file can't be filtered by key, so the files themselves aren't in
the backup:

1. **Backup** (`onBackupEx`): the extension reads both files and writes the
   values whose keys are in `BackupScope.ets` to
   `files/backup/yoin-backup.json` (any older payload is deleted first). The
   system copies that folder and `files/playlist_backups/`. `onRelease` deletes
   the payload afterwards.
2. **Restore** (`onRestoreEx`): the system puts both folders back, then the
   extension replaces every key in scope in both Preferences files with the
   payload's values. This is Android's "the database file is replaced": what this
   install held of them goes; its own settings, downloads and secrets stay. Keys
   this build doesn't carry are skipped, even if a newer build's backup holds them.
   Then the extension deletes the payload and leaves
   `files/neodb/restored_pending_clear`.
3. **Next launch**: `NeoDbRestoreGuard` sees the marker and clears
   `ratingNeedsSync` / `reviewNeedsSync` on every account's album scores
   (`MemoryStore.clearAllNeoDbPending`). Scores, words and the remote review's
   uuid stay. The marker is deleted only after the clear succeeds, so a failure
   is tried again next launch. `files/neodb/` isn't in the backup, so the marker
   can only come from this phone's restore.

All paths are under the application context (`/data/storage/el2/base/`), the
folders `YoinStoragePlugin` and `AppFilesHost` use, not the extension's module
folders (`…/haps/entry/…`).

A restore that brings no payload, such as one from an Android phone (its Room
database isn't ours to read), changes no values and leaves no marker.

## Verifying with Phone Clone

You need two HarmonyOS phones: the old one with the **release** HAP and some data,
the new one with no Yoin.

1. On the old phone, set up a Subsonic account. Then:
   - rate a few songs and score and review an album;
   - leave a note;
   - play a few albums so Memories has candidates;
   - move a Home section;
   - make a playlist backup in Settings;
   - change a device setting, for example the scroll edge style, so you can check
     that it does **not** travel.

   If NeoDB is signed in, score an album while offline so its row stays dirty.
2. Watch the extension's log on the old phone while the move runs:
   `hdc -t <old> shell "hilog -T YoinBackup"` (domain `0x590a`). A backup logs
   `backup payload: N values, M cached`.
3. On the new phone open Phone Clone (手机克隆), pick "This is the new device",
   and connect the old phone. In the data list, select **Apps and data** and make
   sure Yoin is included with its data.
4. When the move is done, check the new phone's log
   (`hdc -t <new> shell "hilog -T YoinBackup"`). It should read
   `restored N values, M cached`, with N matching the backup.
5. Open Yoin on the new phone:
   - there is no landing, Home's avatar has the reconnect dot, and the account
     card offers Reconnect;
   - after you enter the password again, Library, ratings, the album score and
     review, the note, Memories (candidates, titles, AI copy), Home's layout and
     the playlist backup are all there;
   - the scroll edge style is back to the default, NeoDB and cloud sync are
     signed out, and no download is present;
   - after you sign in to NeoDB, the album scored offline on the old phone shows
     no "Couldn't sync" / pending state, because the restore dropped its dirty
     flag. `files/neodb/restored_pending_clear` is gone after the first launch.
6. Huawei Cloud backup goes through the same extension: Settings › Huawei ID ›
   Cloud › Cloud Backup, back up, then restore on another phone or after a reset.
   Check it the same way.

Without a second phone, a developer device can drive the same extension from the
shell with the backup service's debugging tool (Huawei's backup and restore
debugging guide; check `backup_tool --help` on the device first, since its flags
change between releases):

```sh
hdc shell "mkdir -p /data/backup/tmp && touch /data/backup/tmp/yoin.txt"
hdc shell "backup_tool backup --isLocal=true --bundle com.gpo.yoin --pathCapFile /data/backup/tmp/yoin.txt"
# change or clear Yoin's data, then:
hdc shell "backup_tool restore --bundle com.gpo.yoin --pathCapFile /data/backup/tmp/yoin.txt"
```

## Differences from Android

- Android backs up the Room file whole. Here a payload of the matching values
  travels (above), and pure re-fetchable caches are left out.
- Android's restore guard marks every restore. Here only a restore that put
  values back leaves the marker, because a restore without them changed nothing
  NeoDB could push.
- Android's Spotify "Restore sign-in" reason (`ReconnectReason.BackupRestored`)
  has no counterpart: there is no Spotify here. Subsonic and Apple Music accounts
  without their secrets use the existing reconnect path.
- Windows has no Phone Clone. The guard runs there too, but no marker ever
  appears (`docs/windows.md`).
