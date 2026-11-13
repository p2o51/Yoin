import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';

/// How long a detail load waits for the active source on a cold start
/// (Android DETAIL_SOURCE_WAIT_MS).
const detailSourceWaitMs = 4000;

/// Thrown when no account is active (or it never came up in time).
final class NoActiveSourceException implements Exception {
  const NoActiveSourceException();
}

/// Detail reads, single-flight and memory-cached per account (Android
/// DetailPrefetch + the repository's detail cache): a tap-time prefetch and
/// the page's own load join the same Future; a landed result is kept (LRU)
/// so reopening a page is instant. Failures are not cached. Emptied when the
/// active account changes.
final class DetailCache {
  DetailCache(this._source, {this.capacity = 48});

  final Future<MusicSource?> Function() _source;
  final int capacity;

  final _inFlight = <String, Future<Object?>>{};
  final _cache = <String, Object?>{};
  int _generation = 0;

  Future<Album?> album(MediaId id) => _load('album:$id', (s) => s.library.getAlbum(id));
  Future<ArtistDetail?> artist(MediaId id) => _load('artist:$id', (s) => s.library.getArtist(id));
  Future<Playlist?> playlist(MediaId id) => _load('playlist:$id', (s) => s.library.getPlaylist(id));

  /// The landed album, if cached (no read).
  Album? cachedAlbum(MediaId id) => _cache['album:$id'] as Album?;

  /// Starts the load in the background (tap-time prefetch); errors are dropped.
  void prefetchAlbum(MediaId id) => unawaited(album(id).then((_) {}, onError: (_) {}));
  void prefetchArtist(MediaId id) => unawaited(artist(id).then((_) {}, onError: (_) {}));
  void prefetchPlaylist(MediaId id) => unawaited(playlist(id).then((_) {}, onError: (_) {}));

  /// Forgets [id] so the next read goes to the server (pull to refresh).
  void evict(String kind, MediaId id) => _cache.remove('$kind:$id');

  Future<T?> _load<T>(String key, Future<T?> Function(MusicSource source) read) {
    if (_cache.containsKey(key)) {
      final hit = _cache.remove(key);
      _cache[key] = hit; // most recent last
      return Future.value(hit as T?);
    }
    final pending = _inFlight[key];
    if (pending != null) return pending.then((v) => v as T?);
    final generation = _generation;
    final future = () async {
      final source = await _source();
      if (source == null) throw const NoActiveSourceException();
      return read(source);
    }();
    _inFlight[key] = future;
    unawaited(
      future
          .then<void>((value) {
            if (generation != _generation) return;
            _inFlight.remove(key);
            if (value != null) {
              _cache[key] = value;
              while (_cache.length > capacity) {
                _cache.remove(_cache.keys.first);
              }
            }
          })
          .catchError((Object _) {
            if (generation == _generation) _inFlight.remove(key);
          }),
    );
    return future;
  }

  /// Forgets everything (the account changed); reads in flight land nowhere.
  void clear() {
    _generation++;
    _inFlight.clear();
    _cache.clear();
  }
}

/// Waits for the active source up to [detailSourceWaitMs]; null if none.
Future<MusicSource?> awaitActiveSource(Ref ref) async {
  try {
    return await ref.read(activeSourceProvider.future).timeout(const Duration(milliseconds: detailSourceWaitMs));
  } on TimeoutException {
    return null;
  }
}

final detailCacheProvider = Provider<DetailCache>((ref) {
  final cache = DetailCache(() => awaitActiveSource(ref));
  // One account's reads only: emptied when a different source takes over
  // (not when the first one finishes loading).
  ref.listen(activeSourceProvider, (previous, next) {
    final before = previous?.value;
    if (before != null && !identical(before, next.value)) cache.clear();
  });
  return cache;
});
