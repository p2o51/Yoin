/// Cloud sync in the dependency graph (Android AppContainer.cloudSync +
/// YoinApplication's foreground hooks). One [CloudSyncManager] for the app.
library;

import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import '../../app/providers.dart';
import '../../home/home_section.dart' show homeLayoutProvider;
import '../../playback/local_ratings.dart';
import '../../platform/storage/observed_store.dart';
import '../../platform/storage/stores.dart';
import '../../platform/sync/cloud_sync_host.dart';
import '../../settings/licenses.dart' show yoinVersionCode, yoinVersionName;
import '../about/song_about_store.dart';
import '../ai/ai_settings.dart';
import '../memory/memory_store.dart';
import '../model/media_id.dart';
import '../profile/profile_repository.dart';
import 'adapters/account_descriptor_sync_adapter.dart';
import 'adapters/home_layout_sync_adapter.dart';
import 'adapters/memory_sync_adapters.dart';
import 'adapters/song_about_sync_adapter.dart';
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
    key == SongAboutStore.key ||
    key == ProfileRepository.profilesKey;

/// The v1 allowlist as HarmonyOS holds it: one adapter per kind this app
/// keeps (Android YoinSyncAdapters). Kinds HarmonyOS doesn't have (settings,
/// AI translations, AI Memory copy and titles, playlist backups) are carried
/// in the replica untouched and published again, never dropped.
List<SyncAdapter> yoinSyncAdapters({
  required SyncDatabase db,
  required MemoryStoreFor memory,
  required TrackRatingAccess ratings,
  required ProfileRepository profiles,
  required KeyValueStore store,
  Future<String> Function()? aboutLanguage,
}) => [
  SongNoteSyncAdapter(memory),
  TrackRatingSyncAdapter(ratings),
  AlbumRatingSyncAdapter(memory),
  AlbumReviewSyncAdapter(memory),
  HomeLayoutSyncAdapter(store: store, profiles: profiles.profiles),
  AlbumTitleSyncAdapter(memory),
  AccountDescriptorSyncAdapter(db: db, profiles: profiles.profiles, credentials: (p) => profiles.credentials(p.id)),
  SongAboutSyncAdapter(
    store: SongAboutStore.shared(store),
    language: aboutLanguage ?? () async => AiLanguages.defaultTarget,
  ),
];

final cloudSyncProvider = Provider<CloudSyncManager>((ref) {
  final store = ref.watch(keyValueStoreProvider);
  final secrets = ref.watch(secretStoreProvider);
  final host = ref.watch(cloudSyncHostProvider);
  final profiles = ref.watch(profileRepositoryProvider);
  final client = http.Client();
  final auth = GoogleOAuth(config: ref.watch(googleOAuthConfigProvider), host: host, http: client, secrets: secrets);
  final ratings = _NotifierTrackRatingAccess(ref);
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
      aboutLanguage: () async => AiLanguages.normalize((await ref.read(aiSettingsProvider.future)).targetLanguage),
    ),
    binder: (db) => AccountBinder(db: db, profiles: profiles.profiles, credentials: (p) => profiles.credentials(p.id)),
    deviceName: host.deviceName,
    packageName: yoinPackageName,
    versionCode: yoinVersionCode,
    onDomainChanged: (kinds) {
      // Notes, album rows and titles refresh through MemoryStore.changes and
      // ratings through their notifier; the Home layout is read again.
      if (kinds.contains(SyncKinds.homeLayout)) ref.invalidate(homeLayoutProvider);
    },
    localChanges: store is ObservedKeyValueStore ? store.writes.where(isSyncedKey) : null,
  );
  ref.onDispose(() {
    manager.dispose();
    client.close();
  });
  return manager;
});

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
