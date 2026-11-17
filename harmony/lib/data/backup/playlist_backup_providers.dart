/// Playlist backups in the dependency graph (Android AppContainer's
/// playlistBackupStore / playlistBackupService and PlaylistBackupFollower).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../detail/detail_cache.dart';
import '../../library/playlist_writes.dart';
import '../../offline/offline_providers.dart';
import '../profile/profile.dart';
import '../source/music_source.dart';
import '../sync/cloud_sync_providers.dart';
import '../sync/identity/account_keys.dart';
import 'playlist_backup_models.dart';
import 'playlist_backup_service.dart';
import 'playlist_backup_store.dart';

/// Every backup, one file each under the app's files folder.
final playlistBackupStoreProvider = Provider<PlaylistBackupStore>((ref) {
  final store = PlaylistBackupStore(DirectoryPlaylistBackupStorage(ref.watch(appFilesHostProvider)));
  ref.onDispose(store.dispose);
  return store;
});

final playlistBackupServiceProvider = Provider<PlaylistBackupService>(
  (ref) => PlaylistBackupService(
    profiles: AppBackupProfiles(ref),
    store: ref.watch(playlistBackupStoreProvider),
    onPlaylistCreated: (profileId) async {
      // A restore into the account in use: Library and an open page read their playlists again.
      final active = await ref.read(profileRepositoryProvider).activeProfile();
      if (active?.id != profileId) return;
      ref.read(detailCacheProvider).clear();
      ref.read(playlistsRevisionProvider.notifier).bump();
    },
  ),
);

/// Settings › Playlist backups: every backup, newest change first, kept up to date.
final playlistBackupsProvider = StreamProvider<List<PlaylistBackupRecord>>(
  (ref) => ref.watch(playlistBackupStoreProvider).watchAll(),
);

/// One backup's page: the backup, null once it is gone (deleted here or on another device).
final playlistBackupProvider = StreamProvider.family<PlaylistBackupRecord?, String>(
  (ref, id) => ref.watch(playlistBackupStoreProvider).watch(id),
);

/// The app's profiles for backups: the repository, the active source as it
/// is, another profile's source built for one step and disposed after it.
final class AppBackupProfiles implements BackupProfiles {
  AppBackupProfiles(this._ref);

  final Ref _ref;

  @override
  Future<List<Profile>> profiles() => _ref.read(profileRepositoryProvider).profiles();

  @override
  Future<Profile?> activeProfile() => _ref.read(profileRepositoryProvider).activeProfile();

  @override
  Future<T?> withSource<T>(String profileId, Future<T> Function(MusicSource source) block) async {
    final repo = _ref.read(profileRepositoryProvider);
    if ((await repo.activeProfile())?.id == profileId) {
      final source = await _ref.read(activeSourceProvider.future);
      return source == null ? null : await block(source);
    }
    final credentials = await repo.credentials(profileId);
    if (credentials == null) return null;
    final source = _ref.read(musicSourceFactoryProvider)(credentials);
    try {
      return await block(source);
    } finally {
      source.dispose();
    }
  }

  @override
  Future<String?> accountKey(Profile profile) async {
    try {
      final manager = _ref.read(cloudSyncProvider);
      final repo = _ref.read(profileRepositoryProvider);
      return await AccountKeys(binding: manager.bindingFor, credentials: repo.credentials).of(profile);
    } catch (e) {
      debugPrint('playlist backup: account key unavailable (${e.runtimeType})');
      return null;
    }
  }
}

/// Keeps playlist backups following their playlists (Android
/// PlaylistBackupFollower): each time the app comes to the front, once the
/// account is up, the backups due a check ([PlaylistBackupService.followIntervalMs])
/// are read again, [startDelay] after, off the return-to-app path. Never in
/// the background. main() keeps it alive for the app's lifetime.
final playlistBackupFollowerProvider = Provider<void>((ref) {
  final follower = PlaylistBackupFollower(
    ready: () => ref.read(activeSourceProvider.future),
    followDue: () => ref.read(playlistBackupServiceProvider).followDue(),
  );
  follower.onForeground();
  AppLifecycleListener? listener;
  try {
    listener = AppLifecycleListener(onResume: follower.onForeground);
  } catch (_) {
    listener = null; // No binding (plain unit tests).
  }
  ref.onDispose(() {
    listener?.dispose();
    follower.dispose();
  });
});

class PlaylistBackupFollower {
  PlaylistBackupFollower({required this.ready, required this.followDue, this.startDelay = const Duration(seconds: 8)});

  /// Completes when the profiles are up (the active source built).
  final Future<void> Function() ready;
  final Future<FollowSummary> Function() followDue;
  final Duration startDelay;

  bool _running = false;
  bool _disposed = false;
  Timer? _timer;

  /// The app came to the front: one follow-up, unless one is under way.
  void onForeground() {
    if (_running || _disposed) return;
    _running = true;
    unawaited(_run());
  }

  Future<void> _run() async {
    try {
      await ready();
      if (_disposed) return;
      final waited = Completer<void>();
      _timer = Timer(startDelay, waited.complete);
      await waited.future;
      if (_disposed) return;
      final summary = await followDue();
      if (summary.checked > 0) debugPrint('PlaylistBackupFollower: followed $summary');
    } catch (e) {
      debugPrint('PlaylistBackupFollower: stopped (${e.runtimeType})');
    } finally {
      _running = false;
    }
  }

  void dispose() {
    _disposed = true;
    _timer?.cancel();
  }
}
