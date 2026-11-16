/// Cloud sync in the dependency graph (Android AppContainer.cloudSync +
/// YoinApplication's foreground hooks). One [CloudSyncManager] for the app.
library;

import 'dart:async';

import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import '../../app/providers.dart';
import '../ai/ai_settings.dart';
import '../backup/playlist_backup_providers.dart';
import '../backup/playlist_backup_store.dart';
import '../lyrics/lyrics_cache.dart';
import '../lyrics/lyrics_repository.dart' show lyricsCacheStoreProvider;
import '../../home/home_section.dart' show homeLayoutProvider;
import '../../playback/local_ratings.dart';
import '../../platform/storage/observed_store.dart';
import '../../platform/storage/stores.dart';
import '../../platform/sync/cloud_sync_host.dart';
import '../../settings/licenses.dart' show yoinVersionCode, yoinVersionName;
import '../memory/memory_store.dart';
import '../model/media_id.dart';
import '../profile/profile_repository.dart';
import 'adapters/account_descriptor_sync_adapter.dart';
import 'adapters/home_layout_sync_adapter.dart';
import 'adapters/lyrics_translation_sync_adapter.dart';
import 'adapters/memory_sync_adapters.dart';
import 'adapters/playlist_backup_sync_adapter.dart';
import 'adapters/settings_sync_adapter.dart';
import 'adapters/track_rating_sync_adapter.dart';
import 'auth/google_oauth.dart';
import 'cloud_sync_manager.dart';
import 'drive/drive_app_data_client.dart';
import 'identity/account_binder.dart';
import 'sync_contracts.dart';
import 'sync_database.dart';

/// The platform's side of cloud sync. main() wires it from the YoinPlatform
/// bundle; without it (tests, previews) sync stays off and stores nothing.
final cloudSyncHostProvider = Provider<CloudSyncHost>((ref) => const InertCloudSyncHost());

/// The Google OAuth client this build signs in with (`--dart-define`).
final googleOAuthConfigProvider = Provider<GoogleOAuthConfig>((ref) => GoogleOAuthConfig.fromEnvironment());

/// The package Google's client is registered for (the details row).
const yoinPackageName = 'com.gpo.yoin';

/// KeyValueStore keys whose writes mean "a synced row changed here".
bool isSyncedKey(String key) =>
    key.startsWith('song_notes.v1.') ||
    key.startsWith('local_ratings.') ||
    key.startsWith('album_ratings.v1.') ||
    key.startsWith('memory_titles.v1.') ||
    key.startsWith('home_layout.v1.') ||
    key == AiSettingsStore.prefsKey ||
    key == KeyValueSeamStyleGateway.storeKey ||
    key == ProfileRepository.profilesKey;

/// The v1 allowlist as HarmonyOS holds it: one adapter per kind this app
/// keeps (Android YoinSyncAdapters). Kinds HarmonyOS doesn't have (About/Ask,
/// AI Memory copy and titles) are carried in the replica untouched and
/// published again, never dropped; so is the Spotify Client ID setting.
List<SyncAdapter> yoinSyncAdapters({
  required SyncDatabase db,
  required MemoryStoreFor memory,
  required TrackRatingAccess ratings,
  required ProfileRepository profiles,
  required KeyValueStore store,
  PlaylistBackupStore? backups,
  LyricsCacheStore? lyricsCache,
  SeamStyleGateway? seam,
  Future<void> Function(String songKey)? onLyricsSeeded,
}) => [
  SongNoteSyncAdapter(memory),
  TrackRatingSyncAdapter(ratings),
  AlbumRatingSyncAdapter(memory),
  AlbumReviewSyncAdapter(memory),
  HomeLayoutSyncAdapter(store: store, profiles: profiles.profiles),
  SettingsSyncAdapter(
    language: KeyValueTranslationLanguageGateway(store),
    seam: seam ?? KeyValueSeamStyleGateway(store),
    tracked: (key) async => db.getLocalState(SyncKinds.setting, SyncFormat.globalScope, key)?.localHash != null,
  ),
  if (lyricsCache != null) LyricsTranslationSyncAdapter(lyricsCache, onLyricsSeeded: onLyricsSeeded),
  AlbumTitleSyncAdapter(memory),
  if (backups != null) PlaylistBackupSyncAdapter(backups),
  AccountDescriptorSyncAdapter(db: db, profiles: profiles.profiles, credentials: (p) => profiles.credentials(p.id)),
];

final cloudSyncProvider = Provider<CloudSyncManager>((ref) {
  final store = ref.watch(keyValueStoreProvider);
  final secrets = ref.watch(secretStoreProvider);
  final host = ref.watch(cloudSyncHostProvider);
  final profiles = ref.watch(profileRepositoryProvider);
  final client = http.Client();
  final auth = GoogleOAuth(config: ref.watch(googleOAuthConfigProvider), host: host, http: client, secrets: secrets);
  final ratings = _NotifierTrackRatingAccess(ref);
  final backups = ref.watch(playlistBackupStoreProvider);
  final lyricsCache = ref.watch(lyricsCacheStoreProvider);
  final lyricsBudget = lyricsCache == null ? null : LyricsCacheBudget(lyricsCache);
  final manager = CloudSyncManager(
    db: SyncDatabase(IoSyncDbFile(host.filesDirectory)),
    auth: auth,
    transportFor: (tokens) =>
        DriveAppDataClient(http: client, tokens: tokens, userAgent: 'Yoin/$yoinVersionName (gzip)'),
    adapters: (db) => yoinSyncAdapters(
      db: db,
      memory: (profileId) => MemoryStore.shared(store, profileId),
      ratings: ratings,
      profiles: profiles,
      store: store,
      backups: backups,
      lyricsCache: lyricsCache,
      onLyricsSeeded: lyricsBudget?.onWrite,
    ),
    binder: (db) => AccountBinder(db: db, profiles: profiles.profiles, credentials: (p) => profiles.credentials(p.id)),
    deviceName: host.deviceName,
    packageName: yoinPackageName,
    versionCode: yoinVersionCode,
    onDomainChanged: (kinds) {
      // Notes, album rows and titles refresh through MemoryStore.changes and
      // ratings through their notifier; the Home layout is read again.
      if (kinds.contains(SyncKinds.homeLayout)) ref.invalidate(homeLayoutProvider);
      // The translation language (and the scroll edge style's own reader, once it exists) read again.
      if (kinds.contains(SyncKinds.setting)) ref.invalidate(aiSettingsProvider);
    },
    localChanges: _localChanges(store is ObservedKeyValueStore ? store.writes.where(isSyncedKey) : null, [
      backups.changes,
      ?lyricsCache?.paidChanges,
    ]),
  );
  ref.onDispose(() {
    manager.dispose();
    client.close();
  });
  return manager;
});

/// A synced key written, or a change in a store of its own (playlist backups, paid lyric translations).
Stream<void> _localChanges(Stream<String>? keys, List<Stream<void>> others) {
  final merged = StreamController<void>.broadcast();
  final subscriptions = <StreamSubscription<void>>[];
  merged.onListen = () {
    if (keys != null) subscriptions.add(keys.listen((_) => merged.add(null)));
    for (final other in others) {
      subscriptions.add(other.listen((_) => merged.add(null)));
    }
  };
  merged.onCancel = () async {
    for (final s in subscriptions) {
      await s.cancel();
    }
    subscriptions.clear();
  };
  return merged.stream;
}

/// Runs cloud sync with the app: a cycle when it comes to the front (at most
/// once a minute), an upload when it leaves with changes waiting. main()
/// keeps it alive for the app's lifetime.
final cloudSyncLifecycleProvider = Provider<void>((ref) {
  final manager = ref.watch(cloudSyncProvider);
  manager.onAppForeground();
  AppLifecycleListener? listener;
  try {
    listener = AppLifecycleListener(onResume: manager.onAppForeground, onPause: manager.onAppBackground);
  } catch (_) {
    listener = null; // No binding (plain unit tests).
  }
  ref.onDispose(() => listener?.dispose());
});

/// Ratings through the LocalRatings notifier, so the active account's screen
/// state and its own writes stay in step with what sync writes.
final class _NotifierTrackRatingAccess implements TrackRatingAccess {
  _NotifierTrackRatingAccess(this._ref);

  final Ref _ref;

  @override
  Future<Map<MediaId, LocalRating>> read(String profileId) =>
      _ref.read(localRatingsProvider.notifier).syncEdit(profileId, (rows) => Map.of(rows));

  @override
  Future<R> edit<R>(String profileId, R Function(Map<MediaId, LocalRating> rows) edit) =>
      _ref.read(localRatingsProvider.notifier).syncEdit(profileId, edit);
}
