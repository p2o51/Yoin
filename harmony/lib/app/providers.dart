/// App-wide dependencies (Android: AppContainer). Riverpod providers, no
/// code generation. Platform-backed providers throw until main() overrides
/// them with the ArkTS channel implementations; tests override with fakes.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

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
