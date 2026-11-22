/// Renames songs and albums after another storefront's names, as an
/// account's [AppleMusicNameRegion] asks (Android AppleMusicNameResolver.kt).
///
/// Items are grouped by the storefront they ask ([OriginalNameTarget.forRegion]);
/// each group is one request per batch of catalog ids (`GET
/// /v1/catalog/{storefront}/songs?ids=…&l=…`), and songs that storefront
/// doesn't know by their id are asked for once more by ISRC. Answers, misses
/// included, are kept in memory, so a list read again costs nothing. Names
/// are a nicety: a batch that fails or takes longer than [batchTimeout] keeps
/// the names it had, and a storefront the account is in already is never asked.
library;

import 'dart:async';

import '../model/model.dart';
import 'apple_music_name_region.dart';

/// A catalog read: the path segments and query, answered with Apple's document.
typedef AppleMusicCatalogRead = Future<Map<String, Object?>> Function(List<String> segments, Map<String, String> query);

/// What the resolver needs to know about a song beyond the [Track]: its
/// catalog id (digits) and ISRC, when known.
typedef AppleMusicSongKeys = ({String? catalogId, String? isrc}) Function(Track track);

/// What the resolver needs to know about an artist beyond [Artist]: its
/// catalog id (digits) and primary genre, when known.
typedef AppleMusicArtistKeys = ({String? catalogId, String? genre}) Function(Artist artist);

/// One item's names in one storefront. A null field: the storefront named it nothing there.
final class AppleMusicNames {
  const AppleMusicNames({this.title, this.artist, this.album});
  final String? title;
  final String? artist;
  final String? album;
}

final class AppleMusicNameResolver {
  AppleMusicNameResolver({required AppleMusicCatalogRead catalog, this.batchTimeout = const Duration(seconds: 4)})
    : _catalog = catalog;

  final AppleMusicCatalogRead _catalog;

  /// How long a list waits on one batch of names before it opens under the names it has.
  final Duration batchTimeout;

  /// Items whose names are kept: a large playlist's worth.
  static const cacheSize = 4000;

  final _cache = <String, AppleMusicNames?>{}; // insertion order = least recently used first

  Future<List<Track>> tracks(
    AppleMusicNameRegion region,
    String? accountStorefront,
    List<Track> tracks, {
    required AppleMusicSongKeys keys,
  }) async {
    if (region == AppleMusicNameRegion.off || tracks.isEmpty) return tracks;
    final requests = <_Request>[];
    final byTrack = <int, _Request>{};
    for (var i = 0; i < tracks.length; i++) {
      final t = tracks[i];
      if (t.id.provider != MediaId.providerAppleMusic) continue;
      final k = keys(t);
      final id = k.catalogId;
      if (id == null || !_isCatalogId(id)) continue;
      final target = OriginalNameTarget.forRegion(
        region,
        title: t.title,
        artist: t.artist,
        genre: t.genre,
        isrc: k.isrc,
      );
      if (target == null || target.storefront == accountStorefront) continue;
      final r = _Request(id, target, k.isrc);
      requests.add(r);
      byTrack[i] = r;
    }
    if (requests.isEmpty) return tracks;
    final names = await _resolve(_Kind.songs, requests);
    return [
      for (var i = 0; i < tracks.length; i++)
        if (byTrack[i] case final r? when names[r.id] != null)
          renamedTrack(
            tracks[i],
            title: _pick(region, r.target, tracks[i].title, names[r.id]!.title),
            artist: _pick(region, r.target, tracks[i].artist, names[r.id]!.artist),
            album: _pick(region, r.target, tracks[i].album, names[r.id]!.album),
          )
        else
          tracks[i],
    ];
  }

  Future<List<Album>> albums(AppleMusicNameRegion region, String? accountStorefront, List<Album> albums) async {
    if (region == AppleMusicNameRegion.off || albums.isEmpty) return albums;
    final requests = <_Request>[];
    final byAlbum = <int, _Request>{};
    for (var i = 0; i < albums.length; i++) {
      final a = albums[i];
      if (a.id.provider != MediaId.providerAppleMusic || !_isCatalogId(a.id.rawId)) continue;
      // An album's genre says its language as a song's does; its tracks' ISRCs aren't in a list's answer.
      final target = OriginalNameTarget.forRegion(region, title: a.name, artist: a.artist, genre: a.genre);
      if (target == null || target.storefront == accountStorefront) continue;
      final r = _Request(a.id.rawId, target, null);
      requests.add(r);
      byAlbum[i] = r;
    }
    if (requests.isEmpty) return albums;
    final names = await _resolve(_Kind.albums, requests);
    return [
      for (var i = 0; i < albums.length; i++)
        if (byAlbum[i] case final r? when names[r.id] != null)
          renamedAlbum(
            albums[i],
            name: _pick(region, r.target, albums[i].name, names[r.id]!.title) ?? albums[i].name,
            artist: _pick(region, r.target, albums[i].artist, names[r.id]!.artist),
          )
        else
          albums[i],
    ];
  }

  Future<List<Artist>> artists(
    AppleMusicNameRegion region,
    String? accountStorefront,
    List<Artist> artists, {
    required AppleMusicArtistKeys keys,
  }) async {
    if (region == AppleMusicNameRegion.off || artists.isEmpty) return artists;
    final requests = <_Request>[];
    final byArtist = <int, _Request>{};
    for (var i = 0; i < artists.length; i++) {
      final a = artists[i];
      if (a.id.provider != MediaId.providerAppleMusic) continue;
      final k = keys(a);
      final id = k.catalogId;
      if (id == null || !_isCatalogId(id)) continue;
      final target = OriginalNameTarget.forRegion(region, artist: a.name, genre: k.genre);
      if (target == null || target.storefront == accountStorefront) continue;
      final r = _Request(id, target, null);
      requests.add(r);
      byArtist[i] = r;
    }
    if (requests.isEmpty) return artists;
    final names = await _resolve(_Kind.artists, requests);
    return [
      for (var i = 0; i < artists.length; i++)
        if (byArtist[i] case final r? when names[r.id] != null)
          renamedArtist(
            artists[i],
            name: _pick(region, r.target, artists[i].name, names[r.id]!.title) ?? artists[i].name,
          )
        else
          artists[i],
    ];
  }

  /// An album page: the album and its tracklist, renamed together.
  Future<Album> album(
    AppleMusicNameRegion region,
    String? accountStorefront,
    Album album, {
    required AppleMusicSongKeys keys,
  }) async {
    if (region == AppleMusicNameRegion.off) return album;
    final results = await Future.wait([
      tracks(region, accountStorefront, album.tracks, keys: keys),
      albums(region, accountStorefront, [album]),
    ]);
    final renamed = (results[1] as List<Album>).first;
    return renamedAlbum(renamed, name: renamed.name, artist: renamed.artist, tracks: results[0] as List<Track>);
  }

  static String? _pick(AppleMusicNameRegion region, OriginalNameTarget target, String? shown, String? found) =>
      OriginalNameTarget.accepts(region, target, shown, found) ? found!.trim() : shown;

  /// Catalog id → its names, for every request that has an answer (cached or read now).
  Future<Map<String, AppleMusicNames>> _resolve(_Kind kind, List<_Request> requests) async {
    final distinct = <String, _Request>{};
    for (final r in requests) {
      distinct.putIfAbsent(r.id, () => r);
    }
    final groups = <OriginalNameTarget, List<_Request>>{};
    for (final r in distinct.values) {
      (groups[r.target] ??= []).add(r);
    }
    final found = <String, AppleMusicNames>{};
    await Future.wait([
      for (final MapEntry(key: target, value: group) in groups.entries)
        () async {
          final unread = <_Request>[];
          for (final r in group) {
            final key = _key(kind, target, r.id);
            if (!_cache.containsKey(key)) {
              unread.add(r);
            } else {
              final hit = _cache.remove(key);
              _cache[key] = hit; // most recent last
              if (hit != null) found[r.id] = hit;
            }
          }
          // Batch by batch, each kept as it lands: a long list that runs out of time keeps what it read.
          for (var i = 0; i < unread.length; i += kind.batch) {
            final batch = unread.sublist(i, (i + kind.batch).clamp(0, unread.length));
            Map<String, AppleMusicNames> read;
            try {
              read = await _read(kind, target, batch).timeout(batchTimeout);
            } catch (_) {
              break;
            }
            for (final r in batch) {
              _put(_key(kind, target, r.id), read[r.id]);
            }
            found.addAll(read);
          }
        }(),
    ]);
    return found;
  }

  void _put(String key, AppleMusicNames? names) {
    _cache.remove(key);
    _cache[key] = names;
    while (_cache.length > cacheSize) {
      _cache.remove(_cache.keys.first);
    }
  }

  /// One batch's names: by id, then the songs missing by ISRC.
  Future<Map<String, AppleMusicNames>> _read(_Kind kind, OriginalNameTarget target, List<_Request> requests) async {
    final found = <String, AppleMusicNames>{};
    final response = await _catalog(
      ['v1', 'catalog', target.storefront, kind.path],
      {'ids': requests.map((r) => r.id).join(','), 'l': target.language},
    );
    for (final item in _data(response)) {
      final id = item['id'];
      final attributes = item['attributes'];
      if (id is! String || attributes is! Map) continue;
      found[id] = AppleMusicNames(
        title: _text(attributes['name']),
        artist: _text(attributes['artistName']),
        album: _text(attributes['albumName']),
      );
    }
    // A storefront may carry a song under an id of its own; the recording's ISRC still finds it.
    if (kind == _Kind.songs) {
      final byIsrc = [
        for (final r in requests)
          if (!found.containsKey(r.id) && r.isrc != null) r,
      ];
      if (byIsrc.isNotEmpty) {
        // The ids' answer stands even when this second read fails.
        try {
          final songs = await _songsByIsrc(target, [for (final r in byIsrc) r.isrc!]);
          for (final r in byIsrc) {
            if (songs[r.isrc] case final names?) found[r.id] = names;
          }
        } catch (_) {
          // Names by id only.
        }
      }
    }
    return found;
  }

  /// The first catalog song for each ISRC (`songs?filter[isrc]=…`), at most 25 codes a request.
  Future<Map<String, AppleMusicNames>> _songsByIsrc(OriginalNameTarget target, List<String> isrcs) async {
    final codes = {for (final c in isrcs) c.trim().toUpperCase()}.where(_isrc.hasMatch).toList();
    final out = <String, AppleMusicNames>{};
    for (var i = 0; i < codes.length; i += _isrcBatch) {
      final batch = codes.sublist(i, (i + _isrcBatch).clamp(0, codes.length));
      final response = await _catalog(
        ['v1', 'catalog', target.storefront, 'songs'],
        {'filter[isrc]': batch.join(','), 'l': target.language},
      );
      for (final item in _data(response)) {
        final attributes = item['attributes'];
        if (attributes is! Map) continue;
        final code = _text(attributes['isrc'])?.toUpperCase();
        if (code == null || !batch.contains(code) || out.containsKey(code)) continue;
        out[code] = AppleMusicNames(
          title: _text(attributes['name']),
          artist: _text(attributes['artistName']),
          album: _text(attributes['albumName']),
        );
      }
    }
    return out;
  }

  static final _isrc = RegExp(r'^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$');
  static const _isrcBatch = 25;

  static Iterable<Map<String, Object?>> _data(Map<String, Object?> response) {
    final data = response['data'];
    if (data is! List) return const [];
    return data.whereType<Map>().map((m) => m.cast<String, Object?>());
  }

  static String? _text(Object? v) => v is String ? v : null;

  static String _key(_Kind kind, OriginalNameTarget target, String id) =>
      '${kind.path}|${target.storefront}|${target.language}|$id';

  static bool _isCatalogId(String id) => id.isNotEmpty && RegExp(r'^[0-9]+$').hasMatch(id);
}

/// [kind.batch]: Apple's most ids per request for the type (songs 300, albums 100, artists 25).
enum _Kind {
  songs('songs', 300),
  albums('albums', 100),
  artists('artists', 25);

  const _Kind(this.path, this.batch);
  final String path;
  final int batch;
}

final class _Request {
  const _Request(this.id, this.target, this.isrc);
  final String id;
  final OriginalNameTarget target;
  final String? isrc;
}

/// [t] under other names; everything else kept.
Track renamedTrack(Track t, {String? title, String? artist, String? album}) => Track(
  id: t.id,
  title: title,
  artist: artist,
  artistId: t.artistId,
  album: album,
  albumId: t.albumId,
  coverArt: t.coverArt,
  durationSec: t.durationSec,
  trackNumber: t.trackNumber,
  discNumber: t.discNumber,
  year: t.year,
  genre: t.genre,
  userRating: t.userRating,
  isStarred: t.isStarred,
  starredAt: t.starredAt,
  addedAt: t.addedAt,
  audioFormat: t.audioFormat,
  replayGain: t.replayGain,
  sizeBytes: t.sizeBytes,
  playCount: t.playCount,
  bpm: t.bpm,
  composer: t.composer,
  comment: t.comment,
  path: t.path,
);

/// [a] under other names (and, with [tracks], another tracklist); everything else kept.
Album renamedAlbum(Album a, {required String name, String? artist, List<Track>? tracks}) => Album(
  id: a.id,
  name: name,
  artist: artist,
  artistId: a.artistId,
  coverArt: a.coverArt,
  songCount: a.songCount,
  durationSec: a.durationSec,
  year: a.year,
  genre: a.genre,
  isStarred: a.isStarred,
  tracks: tracks ?? a.tracks,
  starredAt: a.starredAt,
  addedAt: a.addedAt,
  releaseType: a.releaseType,
  version: a.version,
);

/// [a] under another name; everything else kept.
Artist renamedArtist(Artist a, {required String name}) => Artist(
  id: a.id,
  name: name,
  albumCount: a.albumCount,
  coverArt: a.coverArt,
  isStarred: a.isStarred,
);
