/// The song → album linker for the app (Android AppContainer
/// trackAlbumLinkEnricher, kicked from YoinApplication): one per app,
/// enriching the account in use when it becomes active and whenever the app
/// comes back to the foreground. The memory ledger asks it for the songs it
/// still can't place ([TrackAlbumLinker.resolve]).
library;

import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/memory/memory_store.dart';
import '../data/memory/track_album_links.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../platform/storage/stores.dart';
import '../playback/local_ratings.dart';

/// [store]'s account as the linker reads it, asking [source]; null when the
/// source has no song lookup.
TrackAlbumAccount? trackAlbumAccount({
  required KeyValueStore kv,
  required MemoryStore store,
  required MusicSource source,
  Map<MediaId, LocalRating> Function()? liveRatings,
}) {
  final lookup = source.trackLookup;
  if (lookup == null) return null;
  return TrackAlbumAccount(
    profileId: store.profileId,
    provider: source.provider,
    lookup: lookup,
    memory: store,
    links: TrackAlbumLinkStore.shared(kv, store.profileId),
    ratings: () async => {...await LocalRatingStore(kv).read(store.profileId), ...?liveRatings?.call()},
  );
}

/// Read once (Home's memory signals and the ledger do) and kept for the app's
/// life: it kicks a pass for each account that becomes active and on every
/// return to the foreground.
final trackAlbumLinkerProvider = Provider<TrackAlbumLinker>((ref) {
  final kv = ref.watch(keyValueStoreProvider);
  final linker = TrackAlbumLinker(
    activeAccount: () async {
      final source = await ref.read(activeSourceProvider.future);
      final store = await ref.read(memoryStoreProvider.future);
      if (source == null || store == null) return null;
      return trackAlbumAccount(kv: kv, store: store, source: source, liveRatings: () => ref.read(localRatingsProvider));
    },
    isActive: (profileId) => ref.read(memoryStoreProvider).value?.profileId == profileId,
  );
  // A newly active account (one just added included) gets its pass at once.
  ref.listen(memoryStoreProvider, (previous, next) {
    final store = next.value;
    if (store != null && store.profileId != previous?.value?.profileId) linker.kick();
  }, fireImmediately: true);
  AppLifecycleListener? lifecycle;
  try {
    lifecycle = AppLifecycleListener(onResume: linker.kick);
  } catch (_) {
    lifecycle = null; // no binding (pure unit tests)
  }
  ref.onDispose(() {
    lifecycle?.dispose();
    linker.dispose();
  });
  return linker;
});
