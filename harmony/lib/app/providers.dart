/// App-wide dependencies (Android: AppContainer). Riverpod providers, no
/// code generation. Platform-backed providers throw until main() overrides
/// them with the ArkTS channel implementations; tests override with fakes.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/activity/activity.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../data/profile/profile.dart';
import '../data/profile/profile_repository.dart';
import '../data/source/music_source.dart';
import '../platform/applemusic/apple_music_auth.dart';
import '../platform/playback/playback_engine.dart';
import '../platform/storage/stores.dart';

final keyValueStoreProvider = Provider<KeyValueStore>(
  (ref) => throw UnimplementedError('Override keyValueStoreProvider in main() or the test.'),
);

/// Re-fetchable caches (the lyrics cache): apart from the settings on a real
/// platform (`YoinPlatform.cacheStore`); tests share [keyValueStoreProvider].
final cacheStoreProvider = Provider<KeyValueStore>((ref) => ref.watch(keyValueStoreProvider));

final secretStoreProvider = Provider<SecretStore>(
  (ref) => throw UnimplementedError('Override secretStoreProvider in main() or the test.'),
);

final playbackEngineProvider = Provider<PlaybackEngine>(
  (ref) => throw UnimplementedError('Override playbackEngineProvider in main() or the test.'),
);

/// Runs the Apple Music ArkWeb sign-in. main() wires the real channel; tests
/// and previews pass a fake.
final appleMusicAuthProvider = Provider<AppleMusicAuth>(
  (ref) => throw UnimplementedError('Override appleMusicAuthProvider in main() or the test.'),
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

/// A source an account switch already built and pinged (Android
/// ProfileManager.switchTo commits the source it asked): [activeSourceProvider]
/// takes it for that account instead of building a second one.
class PreparedSource {
  String? _profileId;
  MusicSource? _source;

  /// Holds [source] for [profileId]; one held for another account is released.
  void hand(String profileId, MusicSource source) {
    if (!identical(_source, source)) _source?.dispose();
    _profileId = profileId;
    _source = source;
  }

  /// The source held for [profileId] (it is the caller's now), else null.
  MusicSource? take(String profileId) {
    final source = _source;
    if (source == null || _profileId != profileId) return null;
    _source = null;
    _profileId = null;
    return source;
  }
}

final preparedSourceProvider = Provider<PreparedSource>((ref) => PreparedSource());

/// The active account's source, rebuilt (and the old one disposed) when the
/// active profile changes — invalidate this after adding/switching accounts.
/// null = no account configured yet.
final activeSourceProvider = FutureProvider<MusicSource?>((ref) async {
  final repo = ref.watch(profileRepositoryProvider);
  final profile = await repo.activeProfile();
  if (profile == null) return null;
  final prepared = ref.read(preparedSourceProvider).take(profile.id);
  if (prepared != null) {
    ref.onDispose(prepared.dispose);
    return prepared;
  }
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

/// The active account's Memory rows (album scores and reviews, song notes,
/// plays in Yoin). null with no account. Rebuilt when the account changes.
final memoryStoreProvider = FutureProvider<MemoryStore?>((ref) async {
  await ref.watch(activeSourceProvider.future);
  final profile = await ref.watch(profileRepositoryProvider).activeProfile();
  if (profile == null) return null;
  // Shared with cloud sync (MemoryStore.shared), so its writes and the screens' go through one queue.
  return MemoryStore.shared(ref.watch(keyValueStoreProvider), profile.id);
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
