import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../platform/storage/stores.dart';

/// Jump Back In's candidate pools for one account (Android
/// home_grid_pool_cache). Slim rows only — id, title, artist, cover storage
/// key, album id, duration, version — never resolved cover URLs (Subsonic
/// URLs carry credentials).
final class HomeGridPools {
  const HomeGridPools({this.albums = const [], this.tracks = const [], this.playlists = const [], required this.cachedAtMs});

  final List<Album> albums;
  final List<Track> tracks;
  final List<Playlist> playlists;
  final int cachedAtMs;

  bool get isEmpty => albums.isEmpty && tracks.isEmpty && playlists.isEmpty;

  Map<String, Object?> toJson() => {
        'v': 1,
        'at': cachedAtMs,
        'albums': [
          for (final a in albums)
            {'id': a.id.rawId, 'n': a.name, 'a': a.artist, 'c': a.coverArt?.storageKey, 'v': a.version},
        ],
        'tracks': [
          for (final t in tracks)
            {'id': t.id.rawId, 't': t.title, 'a': t.artist, 'c': t.coverArt?.storageKey, 'al': t.albumId?.rawId, 'd': t.durationSec},
        ],
        'playlists': [
          for (final p in playlists)
            {'id': p.id.rawId, 'n': p.name, 'o': p.owner, 'c': p.coverArt?.storageKey, 'sc': p.songCount},
        ],
      };

  static HomeGridPools fromJson(Map<String, Object?> json, {required String provider}) {
    List<Map<String, Object?>> rows(String key) =>
        [for (final r in (json[key] as List?) ?? const []) (r! as Map).cast<String, Object?>()];
    MediaId id(Object? raw) => MediaId(provider, raw! as String);
    return HomeGridPools(
      cachedAtMs: (json['at'] as int?) ?? 0,
      albums: [
        for (final r in rows('albums'))
          Album(
            id: id(r['id']),
            name: (r['n'] as String?) ?? '',
            artist: r['a'] as String?,
            coverArt: CoverRef.fromStorageKey(r['c'] as String?),
            version: r['v'] as String?,
          ),
      ],
      tracks: [
        for (final r in rows('tracks'))
          Track(
            id: id(r['id']),
            title: r['t'] as String?,
            artist: r['a'] as String?,
            coverArt: CoverRef.fromStorageKey(r['c'] as String?),
            albumId: r['al'] == null ? null : id(r['al']),
            durationSec: r['d'] as int?,
          ),
      ],
      playlists: [
        for (final r in rows('playlists'))
          Playlist(
            id: id(r['id']),
            name: (r['n'] as String?) ?? '',
            owner: r['o'] as String?,
            coverArt: CoverRef.fromStorageKey(r['c'] as String?),
            songCount: r['sc'] as int?,
          ),
      ],
    );
  }
}

/// Pools as one JSON value per account in the [KeyValueStore]; writes are
/// serialised so a rotation and an account switch never clobber each other.
class HomeGridPoolStore {
  HomeGridPoolStore(this._store);

  final KeyValueStore _store;
  Future<void> _tail = Future.value();

  static String keyFor(String profileId, String provider) => 'home.gridPools.v1.$provider.$profileId';

  /// null = nothing stored. Throws when the store can't be read.
  Future<HomeGridPools?> read(String profileId, String provider) async {
    await _tail;
    final raw = await _store.read(keyFor(profileId, provider));
    if (raw == null) return null;
    return HomeGridPools.fromJson((jsonDecode(raw) as Map).cast<String, Object?>(), provider: provider);
  }

  /// Takes playlist [rawId] out of the stored pools: the service deleted it,
  /// so Jump Back In's next read doesn't bring it back (Android
  /// HomeGridPoolDao.deletePlaylist). Best effort.
  Future<void> forgetPlaylist(String profileId, String provider, String rawId) {
    final done = _tail.then((_) async {
      try {
        final key = keyFor(profileId, provider);
        final raw = await _store.read(key);
        if (raw == null) return;
        final pools = HomeGridPools.fromJson((jsonDecode(raw) as Map).cast<String, Object?>(), provider: provider);
        if (!pools.playlists.any((p) => p.id.rawId == rawId)) return;
        final kept = HomeGridPools(
          albums: pools.albums,
          tracks: pools.tracks,
          playlists: [
            for (final p in pools.playlists)
              if (p.id.rawId != rawId) p,
          ],
          cachedAtMs: pools.cachedAtMs,
        );
        await _store.write(key, jsonEncode(kept.toJson()));
      } catch (_) {}
    });
    _tail = done;
    return done;
  }

  /// Playlist [rawId] in the stored pools takes [name]: renamed on its page,
  /// so Jump Back In's next read shows the new name (Android
  /// HomeGridPoolDao.renamePlaylist). Best effort.
  Future<void> renamePlaylist(String profileId, String provider, String rawId, String name) {
    final done = _tail.then((_) async {
      try {
        final key = keyFor(profileId, provider);
        final raw = await _store.read(key);
        if (raw == null) return;
        final pools = HomeGridPools.fromJson((jsonDecode(raw) as Map).cast<String, Object?>(), provider: provider);
        if (!pools.playlists.any((p) => p.id.rawId == rawId && p.name != name)) return;
        final renamed = HomeGridPools(
          albums: pools.albums,
          tracks: pools.tracks,
          playlists: [for (final p in pools.playlists) p.id.rawId == rawId ? p.withName(name) : p],
          cachedAtMs: pools.cachedAtMs,
        );
        await _store.write(key, jsonEncode(renamed.toJson()));
      } catch (_) {}
    });
    _tail = done;
    return done;
  }

  /// Best effort: a failed write never costs the shelf.
  Future<void> write(String profileId, String provider, HomeGridPools pools) {
    final done = _tail.then((_) async {
      try {
        await _store.write(keyFor(profileId, provider), jsonEncode(pools.toJson()));
      } catch (_) {}
    });
    _tail = done;
    return done;
  }
}

final homeGridPoolStoreProvider = Provider<HomeGridPoolStore>((ref) => HomeGridPoolStore(ref.watch(keyValueStoreProvider)));
