/// What a system backup — Phone Clone, Huawei Cloud backup — carries of Yoin
/// (Android: Auto Backup and device transfer of `yoin-database` and
/// `yoin_profiles.xml`, res/xml/data_extraction_rules.xml).
///
/// The backup itself runs without Flutter, in the ArkTS backup extension
/// (ohos/entry/src/main/ets/yoin/backup/): before a backup it copies these
/// keys of the settings store (`yoin_prefs`) and the caches' store
/// (`yoin_cache`) into a payload file, and after a restore it puts them back.
/// This class is the same list for Dart: the contract the stores' keys must
/// keep (a renamed key that falls out of it would no longer move to a new
/// phone), and a test holds it equal to `BackupScope.ets`.
///
/// Carried, as on Android: the accounts (their metadata and which one is in
/// use; credentials never), song ratings, album scores and reviews, notes,
/// plays (Memories' candidates), the activity log, Memory titles and AI copy,
/// Home's layout, the NeoDB instance and album map, AI settings without their
/// keys, song "About" answers, track ↔ album links, lyric highlights, lyrics
/// with the user's picks and paid translations, and the playlist backups
/// ([fileDirs]).
///
/// Not carried: secrets (Asset Store, which never leaves the device — Android's
/// noBackupFilesDir), this device's own settings and hints (Android keeps them
/// in SharedPreferences outside the backup), cloud sync's state (its own file,
/// Android's separate SyncDatabase), offline downloads, Home's snapshot, the
/// detail, stream and cover caches, scrobbles waiting to be sent and a Continue
/// On hand-off.
library;

/// The carried keys of each store and the carried folders.
abstract final class DeviceBackupScope {
  /// Keys of the settings store carried as they are.
  static const Set<String> prefKeys = {
    'profiles.v1', // ProfileRepository.profilesKey (Android profiles)
    'profiles.active.v1', // ProfileRepository.activeKey (Android yoin_profiles.xml)
    'song_about.v1', // SongAboutStore.key (song_about)
    'album_about.v1', // AlbumAboutStore.key (album_about_entries)
    'yoin.ai.prefs', // AiSettingsStore.prefsKey (gemini_config without the key)
    'neodb.instance', // NeoDbConfigStore.instanceKey (neodb_config without the token)
    'neodb.external_mappings.v1', // NeoDbMappings.storeKey (external_mappings)
  };

  /// Key prefixes of the settings store carried (one key per account,
  /// provider or song).
  static const List<String> prefPrefixes = [
    'local_ratings.', // LocalRatingStore (local_ratings)
    'album_ratings.v1.', // MemoryStore (album_ratings, with the NeoDB flags)
    'song_notes.v1.', // MemoryStore (song_notes)
    'album_notes.v1.', // MemoryStore (album_notes)
    'album_plays.v1.', // MemoryStore (play_history by album)
    'track_plays.v1.', // MemoryStore (play_history by song)
    'memory_titles.v1.', // MemoryStore (album_memory_title)
    'activity.v1.', // StoredActivityLog (activity_events)
    'home_layout.v1.', // HomeLayoutNotifier (home_layout)
    'home_layout.updatedAt.v1.', // HomeLayoutNotifier (home_layout.updatedAt)
    'memory_copy_cache.v1.', // MemoryCopyCache (memory_copy_cache)
    'track_album_links.v1.', // TrackAlbumLinkStore (track_album_link)
    'lyric_highlights.v1.', // LyricHighlightStore (lyric_highlight)
    'yoin.lyrics.', // the user's lyric picks from before the caches' own store (lyrics_cache)
  ];

  /// Key prefixes of the caches' store carried: lyrics and their
  /// translations (Android lyrics_cache + lyrics_translation_cache), the user's
  /// picks and paid translations among them. Home's Made for You shelf stays.
  static const List<String> cachePrefixes = ['yoin.lyrics.'];

  /// Folders under the app's files folder the backup copies as they are
  /// (backup_config.json): the playlist backups (Android playlist_backup).
  static const List<String> fileDirs = ['playlist_backups'];

  /// Whether the settings store's [key] moves to a new phone.
  static bool carriesPref(String key) => prefKeys.contains(key) || prefPrefixes.any(key.startsWith);

  /// Whether the caches' store's [key] moves to a new phone.
  static bool carriesCache(String key) => cachePrefixes.any(key.startsWith);
}
