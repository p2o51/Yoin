import 'dart:async';
import 'dart:math' as math;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/applemusic/apple_music_api.dart' show AppleMusicApiException, AppleMusicFailure;
import '../data/cache/detail_cache_store.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../data/offline/offline_downloads.dart';
import '../offline/offline_providers.dart';
import '../platform/storage/app_files.dart';

/// How long a detail load waits for the active source on a cold start
/// (Android DETAIL_SOURCE_WAIT_MS).
const detailSourceWaitMs = 4000;

/// Thrown when no account is active (or it never came up in time).
final class NoActiveSourceException implements Exception {
  const NoActiveSourceException();
}

/// Detail reads (Android YoinRepository `loadCachedDetail` + DetailPrefetch),
/// layered: memory (an LRU per account, [memoryTtlMs] fresh) → disk
/// ([DetailCacheStore], profile-scoped, served without a network hit while
/// [diskFreshMs] fresh) → the network (writing both layers) → on a failure,
/// any disk / memory copy, then a download's (offline resilience).
///
///  · Single-flight: a tap-time prefetch and the page's own load join the
///    same Future; a failure reaches every waiter and never poisons the key.
///  · Generations: [evict] (an edit) and [clear] (the account changed) bump
///    them and detach the loads in flight, which then write nothing back —
///    neither to memory nor to disk — so a fetch that took off before an edit
///    can't re-persist stale data.
///  · Playlists are mutable: they always revalidate online; the caches only
///    serve as the offline fallback, save for a copy the network handed over
///    within [handoffMs] (a tap's own prefetch IS the open's revalidation).
///  · Stale-while-revalidate: an album / artist served from disk older than
///    [revalidateAfterMs] refreshes in the background, [revalidateParallelism]
///    at a time, pausing while the service rate-limits them.
final class DetailCache {
  DetailCache(
    this._source, {
    this.capacity = 48,
    this.offline,
    this.disk,
    Future<String?> Function()? profileId,
    int Function()? clock,
    this.memoryTtlMs = defaultMemoryTtlMs,
  }) : _profileId = profileId ?? _noProfile,
       _clock = clock ?? _now;

  /// Memory entries stay fresh this long (Android 20 min).
  static const defaultMemoryTtlMs = 20 * 60 * 1000;

  /// An album / artist on disk is served without a network hit for a week.
  static const diskFreshMs = 7 * 24 * 60 * 60 * 1000;

  /// A disk-served album / artist older than this refreshes in the background.
  static const revalidateAfterMs = 2 * 60 * 60 * 1000;

  /// A playlist fetched this recently is the open's revalidation.
  static const handoffMs = 5000;

  /// Background refreshes in flight at once.
  static const revalidateParallelism = 2;

  /// How long background refreshes wait after Apple Music rate-limits one.
  static const rateLimitPauseMs = 5 * 60 * 1000;

  static int _now() => DateTime.now().millisecondsSinceEpoch;
  static Future<String?> _noProfile() async => null;

  final Future<MusicSource?> Function() _source;
  final Future<String?> Function() _profileId;
  final int Function() _clock;
  final int capacity;
  final int memoryTtlMs;

  /// What a downloaded album or playlist reads as when the server can't be
  /// reached: its page opens offline and plays from the downloads (Android
  /// keeps such pages in its detail cache). Never cached: the next read
  /// online gets the server's.
  final OfflineDetails? offline;

  /// The on-disk layer (null: memory only).
  final DetailCacheStore? disk;

  final _inFlight = <String, Future<Object?>>{};
  final _cache = <String, _MemEntry>{};
  final _generations = <String, int>{};
  int _epoch = 0;
  int _revalidating = 0;
  final _revalidations = <Future<void> Function()>[];
  final _pausedUntil = <String, int>{};

  Future<Album?> album(MediaId id) => _load(
    'album',
    id,
    (s) => s.library.getAlbum(id),
    diskRead: disk == null ? null : (p) => disk!.readAlbum(p, id.toString()),
    diskWrite: disk == null ? null : (p, v, ok) => disk!.writeAlbum(p, id.toString(), v, stillCurrent: ok),
    offline: () async => offline?.album(id),
  );

  Future<ArtistDetail?> artist(MediaId id) => _load(
    'artist',
    id,
    (s) => s.library.getArtist(id),
    diskRead: disk == null ? null : (p) => disk!.readArtist(p, id.toString()),
    diskWrite: disk == null ? null : (p, v, ok) => disk!.writeArtist(p, id.toString(), v, stillCurrent: ok),
  );

  Future<Playlist?> playlist(MediaId id) => _load(
    'playlist',
    id,
    (s) => s.library.getPlaylist(id),
    trusted: false,
    diskRead: disk == null ? null : (p) => disk!.readPlaylist(p, id.toString()),
    diskWrite: disk == null ? null : (p, v, ok) => disk!.writePlaylist(p, id.toString(), v, stillCurrent: ok),
    offline: () async => offline?.playlist(id),
  );

  /// The landed album, if cached in memory (no read).
  Album? cachedAlbum(MediaId id) => _cache['album:$id']?.value as Album?;

  /// Starts the load in the background (tap-time prefetch); errors are dropped.
  void prefetchAlbum(MediaId id) => unawaited(album(id).then((_) {}, onError: (_) {}));
  void prefetchArtist(MediaId id) => unawaited(artist(id).then((_) {}, onError: (_) {}));
  void prefetchPlaylist(MediaId id) => unawaited(playlist(id).then((_) {}, onError: (_) {}));

  /// Forgets [id] (an edit, pull to refresh): the memory entry, a load in
  /// flight (it writes nothing back) and the disk copy. The next read goes to
  /// the server.
  void evict(String kind, MediaId id) {
    final key = '$kind:$id';
    _invalidate(key);
    final store = disk;
    if (store == null) return;
    final diskKind = switch (kind) {
      'album' => DetailCacheStore.kindAlbum,
      'artist' => DetailCacheStore.kindArtist,
      _ => DetailCacheStore.kindPlaylist,
    };
    unawaited(() async {
      final profile = await _safeProfileId();
      if (profile != null) await store.remove(profile, diskKind, id.toString());
    }());
  }

  void _invalidate(String key) {
    _generations[key] = (_generations[key] ?? 0) + 1;
    _inFlight.remove(key);
    _cache.remove(key);
  }

  /// Forgets every memory entry and load in flight (the account changed);
  /// reads in flight land nowhere. The disk stays: it is per account.
  void clear() {
    _epoch++;
    for (final key in {..._inFlight.keys, ..._cache.keys}) {
      _invalidate(key);
    }
    _revalidations.clear();
  }

  Future<String?> _safeProfileId() async {
    try {
      return await _profileId();
    } catch (_) {
      return null;
    }
  }

  Future<T?> _load<T extends Object>(
    String kind,
    MediaId id,
    Future<T?> Function(MusicSource source) read, {
    bool trusted = true,
    Future<Cached<T>?> Function(String profileId)? diskRead,
    Future<void> Function(String profileId, T value, bool Function() stillCurrent)? diskWrite,
    Future<T?> Function()? offline,
  }) {
    final key = '$kind:$id';
    final hit = _memHit<T>(key, trusted: trusted);
    if (hit != null) return Future.value(hit);
    final pending = _inFlight[key];
    if (pending != null) return pending.then((v) => v as T?);
    final epoch = _epoch;
    final generation = _generations[key] ?? 0;
    bool current() => epoch == _epoch && (_generations[key] ?? 0) == generation;
    final future = _loadShared<T>(
      key,
      read,
      trusted: trusted,
      current: current,
      diskRead: diskRead,
      diskWrite: diskWrite,
      offline: offline,
    );
    _inFlight[key] = future;
    unawaited(
      future.then<void>((_) {}, onError: (Object _) {}).whenComplete(() {
        if (identical(_inFlight[key], future)) _inFlight.remove(key);
      }),
    );
    return future;
  }

  /// The memory entry a load answers with: any within [memoryTtlMs] (albums,
  /// artists), or for playlists only a fetch within [handoffMs].
  T? _memHit<T>(String key, {required bool trusted}) {
    final entry = _cache[key];
    if (entry == null) return null;
    final age = _clock() - entry.at;
    final ok = trusted ? age <= memoryTtlMs : entry.fetched && age <= handoffMs;
    if (!ok) return null;
    _cache.remove(key);
    _cache[key] = entry; // most recent last
    return entry.value as T?;
  }

  void _put(String key, Object value, {required bool fetched}) {
    _cache.remove(key);
    _cache[key] = _MemEntry(value, _clock(), fetched);
    while (_cache.length > capacity) {
      _cache.remove(_cache.keys.first);
    }
  }

  Future<T?> _loadShared<T extends Object>(
    String key,
    Future<T?> Function(MusicSource source) read, {
    required bool trusted,
    required bool Function() current,
    Future<Cached<T>?> Function(String profileId)? diskRead,
    Future<void> Function(String profileId, T value, bool Function() stillCurrent)? diskWrite,
    Future<T?> Function()? offline,
  }) async {
    final profile = diskRead == null ? null : await _safeProfileId();
    Future<Cached<T>?> diskRow() async {
      if (profile == null || diskRead == null) return null;
      try {
        return await diskRead(profile);
      } catch (_) {
        return null;
      }
    }

    // Albums / artists: a disk copy within the week skips the network.
    final row = trusted ? await diskRow() : null;
    if (row != null && _clock() - row.cachedAt <= diskFreshMs) {
      final value = await row.value();
      if (value != null) {
        if (current()) _put(key, value, fetched: false);
        if (_clock() - row.cachedAt > revalidateAfterMs) {
          _revalidate(key, read, profile, current, diskWrite);
        }
        return value;
      }
    }

    final source = await _source();
    if (source == null) throw const NoActiveSourceException();
    try {
      final value = await read(source);
      if (value != null && current()) {
        _put(key, value, fetched: true);
        _persist(profile, value, current, diskWrite);
      }
      return value;
    } on Exception {
      // The network failed: any copy beats an error page (not marked fetched,
      // so a playlist's hand-off never passes it off as a revalidation).
      final fallback = await (row ?? await diskRow())?.value();
      if (fallback != null) {
        if (current()) _put(key, fallback, fetched: false);
        return fallback;
      }
      final stale = _cache[key]?.value as T?;
      if (stale != null) return stale;
      final kept = await _offline(offline);
      if (kept == null) rethrow;
      return kept;
    }
  }

  /// A fetched value's disk copy, off the load's path; [current] is asked
  /// again under the store's write lock, so an edit meanwhile still wins.
  void _persist<T extends Object>(
    String? profile,
    T value,
    bool Function() current,
    Future<void> Function(String profileId, T value, bool Function() stillCurrent)? diskWrite,
  ) {
    if (profile == null || diskWrite == null) return;
    unawaited(diskWrite(profile, value, current).catchError((Object _) {}));
  }

  /// Background refresh of an already-served disk copy, a few at a time; one
  /// that waited its turn is dropped if the account changed or an edit dropped
  /// its key meanwhile, and none is sent while the service rate-limits them.
  void _revalidate<T extends Object>(
    String key,
    Future<T?> Function(MusicSource source) read,
    String? profile,
    bool Function() current,
    Future<void> Function(String profileId, T value, bool Function() stillCurrent)? diskWrite,
  ) {
    final pauseKey = profile ?? '';
    bool paused() => _clock() < (_pausedUntil[pauseKey] ?? 0);
    if (paused()) return;
    _revalidations.add(() async {
      if (paused() || !current()) return;
      T? value;
      try {
        final source = await _source();
        if (source == null) return;
        value = await read(source);
      } on AppleMusicApiException catch (e) {
        if (e.failure == AppleMusicFailure.rateLimited) {
          final until = _clock() + rateLimitPauseMs;
          _pausedUntil[pauseKey] = math.max(until, _pausedUntil[pauseKey] ?? 0);
        }
        return;
      } catch (_) {
        return;
      }
      if (value == null || !current()) return;
      _put(key, value, fetched: true);
      _persist(profile, value, current, diskWrite);
    });
    _drainRevalidations();
  }

  void _drainRevalidations() {
    while (_revalidating < revalidateParallelism && _revalidations.isNotEmpty) {
      final next = _revalidations.removeAt(0);
      _revalidating++;
      unawaited(
        next().catchError((Object _) {}).whenComplete(() {
          _revalidating--;
          _drainRevalidations();
        }),
      );
    }
  }

  static Future<T?> _offline<T>(Future<T?> Function()? read) async {
    try {
      return await read?.call();
    } catch (_) {
      return null;
    }
  }
}

final class _MemEntry {
  _MemEntry(this.value, this.at, this.fetched);
  final Object value;
  final int at;

  /// The network handed it over (not a disk or fallback copy).
  final bool fetched;
}

/// Waits for the active source up to [detailSourceWaitMs]; null if none.
Future<MusicSource?> awaitActiveSource(Ref ref) async {
  try {
    return await ref.read(activeSourceProvider.future).timeout(const Duration(milliseconds: detailSourceWaitMs));
  } on TimeoutException {
    return null;
  }
}

/// The downloaded albums and playlists, as their pages read with no network.
abstract interface class OfflineDetails {
  Future<Album?> album(MediaId id);
  Future<Playlist?> playlist(MediaId id);
}

/// The on-disk detail cache (null where the build keeps no files: tests,
/// previews). Downloaded albums and playlists never age out of it; it ages
/// out the rest as it first opens (the hygiene pass).
final detailCacheStoreProvider = Provider<DetailCacheStore?>((ref) {
  final files = ref.watch(appFilesHostProvider);
  if (files is NoAppFilesHost) return null;
  final store = DetailCacheStore(
    files.filesDirectory,
    keeps: (profileId, kind, entityId) async {
      if (kind == DetailCacheStore.kindArtist) return false;
      final id = MediaId.tryParse(entityId);
      if (id == null) return false;
      try {
        final downloads = ref.read(offlineDownloadsProvider);
        await downloads.start();
        return kind == DetailCacheStore.kindAlbum
            ? downloads.offlineAlbum(id, profileId: profileId) != null
            : downloads.offlinePlaylist(id, profileId: profileId) != null;
      } catch (_) {
        return false;
      }
    },
  );
  return store;
});

final detailCacheProvider = Provider<DetailCache>((ref) {
  final cache = DetailCache(
    () => awaitActiveSource(ref),
    offline: _OfflineDetails(ref),
    disk: ref.watch(detailCacheStoreProvider),
    profileId: () async => (await ref.read(profileRepositoryProvider).activeProfile())?.id,
  );
  // One account's reads only: emptied when a different source takes over
  // (not when the first one finishes loading).
  ref.listen(activeSourceProvider, (previous, next) {
    final before = previous?.value;
    if (before != null && !identical(before, next.value)) cache.clear();
  });
  return cache;
});

/// The active account's downloads (read only when the server can't be reached).
final class _OfflineDetails implements OfflineDetails {
  const _OfflineDetails(this._ref);
  final Ref _ref;

  @override
  Future<Album?> album(MediaId id) async {
    final (downloads, profileId) = await _ready();
    return downloads.offlineAlbum(id, profileId: profileId);
  }

  @override
  Future<Playlist?> playlist(MediaId id) async {
    final (downloads, profileId) = await _ready();
    return downloads.offlinePlaylist(id, profileId: profileId);
  }

  /// The downloads with their index read, and the account in use: a page
  /// opened as the app starts offline may come before either.
  Future<(OfflineDownloads, String?)> _ready() async {
    final downloads = _ref.read(offlineDownloadsProvider);
    await downloads.start();
    String? profileId;
    try {
      profileId = (await _ref.read(profileRepositoryProvider).activeProfile())?.id;
    } catch (_) {
      profileId = null; // The followed account's, then.
    }
    return (downloads, profileId);
  }
}
