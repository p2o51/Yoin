/**
 * What a system backup (Phone Clone, Huawei Cloud backup) carries of Yoin: the HarmonyOS equivalent of Android's
 * `yoin-database` + `yoin_profiles.xml` (app/src/main/res/xml/data_extraction_rules.xml). Accounts and their
 * metadata, ratings, album scores and reviews, notes, plays (Memories' candidates), the activity log, Home's
 * layout, the NeoDB instance and album map, AI copy and settings without keys, lyric picks, highlights and paid
 * translations, playlist backups.
 *
 * Never carried: credentials and keys (Asset Store, which never leaves the device, as Android's noBackupFilesDir),
 * this device's settings (Android keeps them in SharedPreferences outside the backup), cloud sync's own state
 * (`haps/entry/files/yoin-sync.json.gz`, Android's separate SyncDatabase), offline downloads, the stream and cover
 * caches, Home's snapshot and the detail cache.
 *
 * The Dart mirror is `lib/data/backup/device_backup_scope.dart`; `test/data/backup/device_backup_scope_test.dart`
 * fails when the two lists differ, so keep one entry per line.
 */

/** The settings store's Preferences file (StoragePlugin's PREFS_NAME). */
export const PREFS_FILE: string = 'yoin_prefs';

/** The re-fetchable caches' Preferences file (StoragePlugin's CACHE_PREFS_NAME). */
export const CACHE_FILE: string = 'yoin_cache';

/** Keys of [PREFS_FILE] carried as they are. */
export const CARRIED_PREF_KEYS: string[] = [
  'profiles.v1',
  'profiles.active.v1',
  'song_about.v1',
  'yoin.ai.prefs',
  'neodb.instance',
  'neodb.external_mappings.v1',
];

/** Key prefixes of [PREFS_FILE] carried (one key per account, provider or song). */
export const CARRIED_PREF_PREFIXES: string[] = [
  'local_ratings.',
  'album_ratings.v1.',
  'song_notes.v1.',
  'album_notes.v1.',
  'album_plays.v1.',
  'track_plays.v1.',
  'memory_titles.v1.',
  'activity.v1.',
  'home_layout.v1.',
  'home_layout.updatedAt.v1.',
  'memory_copy_cache.v1.',
  'track_album_links.v1.',
  'lyric_highlights.v1.',
  'yoin.lyrics.',
];

/** Key prefixes of [CACHE_FILE] carried: lyrics, the user's picks and paid translations among them. */
export const CARRIED_CACHE_PREFIXES: string[] = [
  'yoin.lyrics.',
];

/** Whether [key] of a file with these [keys] / [prefixes] goes into the backup. */
export function isCarried(key: string, keys: string[], prefixes: string[]): boolean {
  if (keys.indexOf(key) >= 0) {
    return true;
  }
  for (const prefix of prefixes) {
    if (key.startsWith(prefix)) {
      return true;
    }
  }
  return false;
}

/** The payload's folder under the app's filesDir: backup_config.json includes it. */
export const PAYLOAD_DIR: string = 'backup';

/** The payload file inside [PAYLOAD_DIR]. */
export const PAYLOAD_FILE: string = 'yoin-backup.json';

/** The payload's format tag. */
export const PAYLOAD_FORMAT: string = 'yoin.device-backup.v1';

/**
 * Left under the app's filesDir by a restore (never backed up, so it can only come from this device's restore):
 * the next launch drops the old phone's NeoDB dirty flags (Dart NeoDbRestoreGuard), then deletes it.
 */
export const RESTORE_MARKER: string = 'neodb/restored_pending_clear';
