/// App-wide dependencies (Android: AppContainer). Riverpod providers, no
/// code generation. Platform-backed providers throw until main() overrides
/// them with the ArkTS channel implementations; tests override with fakes.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../data/profile/profile.dart';
import '../data/profile/profile_repository.dart';
import '../data/source/music_source.dart';
import '../platform/playback/playback_engine.dart';
import '../platform/storage/stores.dart';

final keyValueStoreProvider = Provider<KeyValueStore>(
  (ref) => throw UnimplementedError('Override keyValueStoreProvider in main() or the test.'),
);

final secretStoreProvider = Provider<SecretStore>(
  (ref) => throw UnimplementedError('Override secretStoreProvider in main() or the test.'),
);

final playbackEngineProvider = Provider<PlaybackEngine>(
  (ref) => throw UnimplementedError('Override playbackEngineProvider in main() or the test.'),
);

final profileRepositoryProvider = Provider<ProfileRepository>(
  (ref) => ProfileRepository(ref.watch(keyValueStoreProvider), ref.watch(secretStoreProvider)),
);

/// Builds a source for one account's credentials. main() wires the real
/// providers (Subsonic …); tests pass fakes.
typedef MusicSourceFactory = MusicSource Function(ProfileCredentials credentials);

final musicSourceFactoryProvider = Provider<MusicSourceFactory>(
  (ref) => throw UnimplementedError('Override musicSourceFactoryProvider in main() or the test.'),
);

/// The active account's source, rebuilt (and the old one disposed) when the
/// active profile changes — invalidate this after adding/switching accounts.
/// null = no account configured yet.
final activeSourceProvider = FutureProvider<MusicSource?>((ref) async {
  final repo = ref.watch(profileRepositoryProvider);
  final profile = await repo.activeProfile();
  if (profile == null) return null;
  final credentials = await repo.credentials(profile.id);
  if (credentials == null) return null;
  final source = ref.watch(musicSourceFactoryProvider)(credentials);
  ref.onDispose(source.dispose);
  return source;
});

/// The active account's activity log (plays and visits). null with no account.
final activityLogProvider = FutureProvider<ActivityLog?>((ref) async {
  // Rebuilt with the active source, i.e. when the account changes.
  await ref.watch(activeSourceProvider.future);
  final profile = await ref.watch(profileRepositoryProvider).activeProfile();
  if (profile == null) return null;
  return StoredActivityLog(ref.watch(keyValueStoreProvider), profileId: profile.id);
});

/// Heart / star states toggled this session, over every surface that shows
/// them (Android applyFavoriteOverrides): album and playlist rows, the artist
/// star, Now Playing and the system heart all write through [setFavorite], so
/// a toggle made anywhere shows everywhere without a reload. The playback
/// controller mirrors it onto its queue and the session heart. Reset when the
/// account changes.
class FavoriteOverrides extends Notifier<Map<MediaId, bool>> {
  final Set<MediaId> _inFlight = {};

  @override
  Map<MediaId, bool> build() {
    ref.listen(activeSourceProvider, (previous, next) {
      final before = previous?.value;
      if (before != null && !identical(before, next.value)) {
        _inFlight.clear();
        state = const {};
      }
    });
    return const {};
  }

  /// [server] unless this session toggled [id].
  bool resolve(MediaId id, bool server) => state[id] ?? server;

  /// Whether a write for [id] is still on its way (a second tap is ignored).
  bool isWriting(MediaId id) => _inFlight.contains(id);

  void _put(MediaId id, bool value) => state = {...state, id: value};

  /// Shows [target] at once, writes it, and puts [previous] back if the write
  /// fails. Returns whether the write landed; false at once (nothing
  /// written) while an earlier write for [id] is in flight.
  Future<bool> setFavorite(MediaId id, {required bool target, required bool previous}) async {
    if (!_inFlight.add(id)) return false;
    _put(id, target);
    try {
      final source = await ref.read(activeSourceProvider.future);
      if (source == null) throw StateError('no source');
      await source.writeActions.setFavorite(id, target);
      return true;
    } catch (_) {
      if (ref.mounted) _put(id, previous);
      return false;
    } finally {
      _inFlight.remove(id);
    }
  }
}

final favoriteOverridesProvider = NotifierProvider<FavoriteOverrides, Map<MediaId, bool>>(FavoriteOverrides.new);
