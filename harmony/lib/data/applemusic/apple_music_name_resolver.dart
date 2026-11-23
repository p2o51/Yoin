/// Renames songs, albums, and artists after another storefront's names, as an
/// account's [AppleMusicNameRegion] asks (Android AppleMusicNameResolver.kt).
///
/// Items are grouped by the storefront they ask ([OriginalNameTarget.forRegion]);
/// each group is one request per batch of catalog ids (`GET
/// /v1/catalog/{storefront}/songs?ids=…&l=…`). Songs absent in the target
/// storefront fall back to ISRC lookup; regional-edition albums absent in the
/// target storefront fall back to their global catalog artist ID (and track
/// ISRC) read from the account's storefront. Speculative pure-Katakana lookups
/// in [AppleMusicNameRegion.original] verify track/top-song ISRCs so native
/// Japanese Katakana acts (ヨルシカ, サカナクション, ササノマリイ) are never
/// romanized.
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

final class _SourceAlbumFallback {
  const _SourceAlbumFallback({this.artistId, this.firstTrackIsrc, required this.hasJapaneseScript, this.genre});
  final String? artistId;
  final String? firstTrackIsrc;
  final bool hasJapaneseScript;
  final String? genre;
}

final class AppleMusicNameResolver {
  AppleMusicNameResolver({required AppleMusicCatalogRead catalog, this.batchTimeout = const Duration(seconds: 6)})
    : _catalog = catalog;

  final AppleMusicCatalogRead _catalog;

  /// How long a list waits on one batch of names before it opens under the names it has.
  final Duration batchTimeout;

  /// Items whose names are kept: a large playlist's worth.
  static const cacheSize = 4000;

  /// Bounded parallelism for multi-batch region lookups.
  static const maxConcurrentBatches = 6;

  final _cache = <String, AppleMusicNames?>{}; // insertion order = least recently used first
  final _sourceAlbumCache = <String, _SourceAlbumFallback?>{};

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
            artist: _pick(region, r.target, tracks[i].artist, names[r.id]!.artist, isArtist: true),
            album: _pick(region, r.target, tracks[i].album, names[r.id]!.album),
          )
        else
          tracks[i],
    ];
  }

  Future<List<Album>> albums(
    AppleMusicNameRegion region,
    String? accountStorefront,
    List<Album> albums, {
    Map<String, String> isrcByAlbumId = const {},
  }) async {
    if (region == AppleMusicNameRegion.off || albums.isEmpty) return albums;
    final requests = <_Request>[];
    final byAlbum = <int, _Request>{};
    for (var i = 0; i < albums.length; i++) {
      final a = albums[i];
      if (a.id.provider != MediaId.providerAppleMusic || !_isCatalogId(a.id.rawId)) continue;
      final isrc = isrcByAlbumId[a.id.rawId];
      // An album's genre says its language as a song's does; album pages also pass a unanimous track ISRC.
      final target = OriginalNameTarget.forRegion(
        region,
        title: a.name,
        artist: a.artist,
        genre: a.genre,
        isrc: isrc,
      );
      if (target == null || target.storefront == accountStorefront) continue;
      final speculative = region == AppleMusicNameRegion.original &&
          target == OriginalNameTarget.unitedStates &&
          OriginalNameTarget.isKatakanaSpeculative(title: a.name, artist: a.artist, genre: a.genre, isrc: isrc);
      final r = _Request(a.id.rawId, target, null, verifyNotJapanese: speculative);
      requests.add(r);
      byAlbum[i] = r;
    }
    if (requests.isEmpty) return albums;
    final names = Map<String, AppleMusicNames>.from(await _resolve(_Kind.albums, requests));
    final effectiveTarget = <String, OriginalNameTarget>{for (final r in requests) r.id: r.target};
    final missed = [for (final r in requests) if (!names.containsKey(r.id)) r];
    if (missed.isNotEmpty && accountStorefront != null && accountStorefront.isNotEmpty) {
      final albumsById = {for (final a in albums) a.id.rawId: a};
      final recovered = await _resolveMissedAlbumsViaSourceStorefront(
        region,
        accountStorefront,
        missed,
        albumsById,
      );
      for (final entry in recovered.entries) {
        names[entry.key] = entry.value.$1;
        effectiveTarget[entry.key] = entry.value.$2;
      }
    }
    return [
      for (var i = 0; i < albums.length; i++)
        if (byAlbum[i] case final r? when names[r.id] != null)
          renamedAlbum(
            albums[i],
            name: _pick(region, effectiveTarget[r.id] ?? r.target, albums[i].name, names[r.id]!.title) ?? albums[i].name,
            artist: _pick(
              region,
              effectiveTarget[r.id] ?? r.target,
              albums[i].artist,
              names[r.id]!.artist,
              isArtist: true,
            ),
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
      if (region == AppleMusicNameRegion.original && target == OriginalNameTarget.korea) continue;
      final speculative = region == AppleMusicNameRegion.original &&
          target == OriginalNameTarget.unitedStates &&
          OriginalNameTarget.isKatakanaSpeculative(artist: a.name, genre: k.genre);
      final r = _Request(id, target, null, verifyNotJapanese: speculative);
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
            name: _pick(region, r.target, artists[i].name, names[r.id]!.title, isArtist: true) ?? artists[i].name,
          )
        else
          artists[i],
    ];
  }

  /// An album page: the album and its tracklist, renamed together with consistent header/track artist names.
  Future<Album> album(
    AppleMusicNameRegion region,
    String? accountStorefront,
    Album album, {
    required AppleMusicSongKeys keys,
  }) async {
    if (region == AppleMusicNameRegion.off) return album;
    final unanimous = _unanimousTrackIsrc(album.tracks, keys);
    final isrcMap = unanimous != null ? {album.id.rawId: unanimous} : const <String, String>{};
    final results = await Future.wait([
      tracks(region, accountStorefront, album.tracks, keys: keys),
      albums(region, accountStorefront, [album], isrcByAlbumId: isrcMap),
    ]);
    final renamedTracks = results[0] as List<Track>;
    final renamed = (results[1] as List<Album>).first;
    var artist = renamed.artist;
    if (artist == album.artist && album.artist != null && album.artist!.isNotEmpty) {
      artist = _consensusTrackArtist(album.artist!, album.tracks, renamedTracks) ?? artist;
    }
    return renamedAlbum(renamed, name: renamed.name, artist: artist, tracks: renamedTracks);
  }

  Future<Map<String, (AppleMusicNames, OriginalNameTarget)>> _resolveMissedAlbumsViaSourceStorefront(
    AppleMusicNameRegion region,
    String accountStorefront,
    List<_Request> missed,
    Map<String, Album> albumsById,
  ) async {
    final sourceInfo = await _fetchSourceAlbumFallbacks(accountStorefront, [for (final r in missed) r.id]);
    if (sourceInfo.isEmpty) return const {};

    final plans = <({
      String albumId,
      OriginalNameTarget target,
      String? artistId,
      String? isrc,
      bool needsTitleByIsrc,
      bool verifyArtistNotJapanese,
    })>[];

    for (final req in missed) {
      final info = sourceInfo[req.id];
      if (info == null) continue;
      final album = albumsById[req.id];
      OriginalNameTarget effectiveTarget;
      if (region == AppleMusicNameRegion.original) {
        final byIsrc = OriginalNameTarget.forIsrc(info.firstTrackIsrc);
        final byGenre = OriginalNameTarget.forGenre(info.genre);
        if (byIsrc != null && byIsrc.storefront == accountStorefront) continue;
        if (byIsrc == OriginalNameTarget.unitedStates) {
          effectiveTarget = OriginalNameTarget.unitedStates;
        } else if (byIsrc != null) {
          effectiveTarget = byIsrc;
        } else if (byGenre != null && byGenre.storefront == accountStorefront) {
          continue;
        } else if (req.verifyNotJapanese && info.hasJapaneseScript) {
          continue;
        } else {
          effectiveTarget = req.target;
        }
      } else {
        effectiveTarget = req.target;
      }
      if (effectiveTarget.storefront == accountStorefront) continue;
      if (region == AppleMusicNameRegion.original && effectiveTarget == OriginalNameTarget.korea) continue;

      final title = album?.name ?? '';
      final needsTitle = region != AppleMusicNameRegion.original ||
          (effectiveTarget == OriginalNameTarget.unitedStates && OriginalNameTarget.hasNonLatin(title)) ||
          (OriginalNameTarget.isChinese(effectiveTarget) && !OriginalNameTarget.hasHanText(title));
      final confirmedNonJapanese =
          info.firstTrackIsrc != null && OriginalNameTarget.forIsrc(info.firstTrackIsrc) != null;
      final artistId = (info.artistId != null && _isCatalogId(info.artistId!)) ? info.artistId : null;
      plans.add((
        albumId: req.id,
        target: effectiveTarget,
        artistId: artistId,
        isrc: info.firstTrackIsrc,
        needsTitleByIsrc: needsTitle && info.firstTrackIsrc != null && info.firstTrackIsrc!.isNotEmpty,
        verifyArtistNotJapanese: req.verifyNotJapanese && !confirmedNonJapanese,
      ));
    }
    if (plans.isEmpty) return const {};

    final artistRequests = [
      for (final p in plans)
        if (p.artistId != null) _Request(p.artistId!, p.target, null, verifyNotJapanese: p.verifyArtistNotJapanese),
    ];
    final isrcGroups = <OriginalNameTarget, List<String>>{};
    for (final p in plans) {
      if (p.needsTitleByIsrc && p.isrc != null) {
        (isrcGroups[p.target] ??= []).add(p.isrc!);
      }
    }

    final results = await Future.wait([
      artistRequests.isEmpty ? Future.value(const <String, AppleMusicNames>{}) : _resolve(_Kind.artists, artistRequests),
      Future.wait([
        for (final entry in isrcGroups.entries)
          () async {
            try {
              final songs = await _songsByIsrc(entry.key, entry.value).timeout(batchTimeout);
              return MapEntry(entry.key, songs);
            } catch (_) {
              return MapEntry(entry.key, const <String, AppleMusicNames>{});
            }
          }(),
      ]),
    ]);

    final resolvedArtists = results[0] as Map<String, AppleMusicNames>;
    final resolvedIsrcs = Map<OriginalNameTarget, Map<String, AppleMusicNames>>.fromEntries(
      results[1] as List<MapEntry<OriginalNameTarget, Map<String, AppleMusicNames>>>,
    );

    final out = <String, (AppleMusicNames, OriginalNameTarget)>{};
    for (final p in plans) {
      final artistName = p.artistId != null ? resolvedArtists[p.artistId!]?.title : null;
      final targetIsrcs = resolvedIsrcs[p.target];
      final songMatch = (p.isrc != null && targetIsrcs != null) ? targetIsrcs[p.isrc!.toUpperCase()] : null;
      final title = songMatch?.album;
      final artist = artistName ?? songMatch?.artist;
      if (title != null || artist != null) {
        final resolved = AppleMusicNames(title: title, artist: artist);
        out[p.albumId] = (resolved, p.target);
        _put(_key(_Kind.albums, p.target, p.albumId), resolved);
      }
    }
    return out;
  }

  Future<Map<String, _SourceAlbumFallback>> _fetchSourceAlbumFallbacks(
    String accountStorefront,
    List<String> albumIds,
  ) async {
    final known = <String, _SourceAlbumFallback>{};
    final unread = <String>[];
    for (final id in albumIds.toSet()) {
      final k = '$accountStorefront|$id';
      if (_sourceAlbumCache.containsKey(k)) {
        final hit = _sourceAlbumCache[k];
        if (hit != null) known[id] = hit;
      } else {
        unread.add(id);
      }
    }
    if (unread.isEmpty) return known;
    final batches = <List<String>>[
      for (var i = 0; i < unread.length; i += _Kind.albums.batch)
        unread.sublist(i, (i + _Kind.albums.batch).clamp(0, unread.length)),
    ];
    var cursor = 0;
    final workers = batches.length < maxConcurrentBatches ? batches.length : maxConcurrentBatches;
    await Future.wait([
      for (var w = 0; w < workers; w++)
        () async {
          while (cursor < batches.length) {
            final batch = batches[cursor++];
            Map<String, Object?> response;
            try {
              response = await _catalog(
                ['v1', 'catalog', accountStorefront, _Kind.albums.path],
                {'ids': batch.join(','), 'include': 'artists,tracks'},
              ).timeout(batchTimeout);
            } catch (_) {
              continue;
            }
            final parsed = <String, _SourceAlbumFallback>{};
            for (final item in _data(response)) {
              final id = item['id'];
              if (id is! String) continue;
              final attrs = item['attributes'] is Map ? (item['attributes'] as Map).cast<String, Object?>() : null;
              final rels = item['relationships'] is Map ? (item['relationships'] as Map).cast<String, Object?>() : null;
              final artistsData = _nestedData(rels?['artists']);
              final artistId = artistsData.isNotEmpty ? _text(artistsData.first['id']) : null;
              final tracksData = _nestedData(rels?['tracks']);
              String? firstIsrc;
              var hasJpScript = false;
              for (final track in tracksData) {
                final tAttrs = track['attributes'] is Map ? (track['attributes'] as Map).cast<String, Object?>() : null;
                if (tAttrs == null) continue;
                firstIsrc ??= _text(tAttrs['isrc']);
                final tName = _text(tAttrs['name']) ?? '';
                if (OriginalNameTarget.hasJapaneseScript(tName)) hasJpScript = true;
              }
              parsed[id] = _SourceAlbumFallback(
                artistId: artistId,
                firstTrackIsrc: firstIsrc,
                hasJapaneseScript: hasJpScript,
                genre: _genreString(attrs),
              );
            }
            for (final id in batch) {
              _sourceAlbumCache['$accountStorefront|$id'] = parsed[id];
              while (_sourceAlbumCache.length > cacheSize) {
                _sourceAlbumCache.remove(_sourceAlbumCache.keys.first);
              }
            }
            known.addAll(parsed);
          }
        }(),
    ]);
    return known;
  }

  static String? _unanimousTrackIsrc(List<Track> tracks, AppleMusicSongKeys keys) {
    final isrcs = <String>[];
    for (final t in tracks) {
      final code = keys(t).isrc;
      if (code != null && code.isNotEmpty) isrcs.add(code);
    }
    if (isrcs.isEmpty) return null;
    final targets = {for (final code in isrcs) ?OriginalNameTarget.forIsrc(code)};
    return targets.length == 1 ? isrcs.first : null;
  }

  static String? _consensusTrackArtist(String originalArtist, List<Track> before, List<Track> after) {
    final count = before.length < after.length ? before.length : after.length;
    final renamed = <String>{};
    for (var i = 0; i < count; i++) {
      final next = after[i].artist;
      if (before[i].artist == originalArtist && next != null && next.isNotEmpty && next != originalArtist) {
        renamed.add(next);
      }
    }
    return renamed.length == 1 ? renamed.first : null;
  }

  static String? _pick(
    AppleMusicNameRegion region,
    OriginalNameTarget target,
    String? shown,
    String? found, {
    bool isArtist = false,
  }) => OriginalNameTarget.accepts(region, target, shown, found, isArtist: isArtist) ? found!.trim() : shown;

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
          if (unread.isEmpty) return;
          final batches = <List<_Request>>[
            for (var i = 0; i < unread.length; i += kind.batch)
              unread.sublist(i, (i + kind.batch).clamp(0, unread.length)),
          ];
          var cursor = 0;
          final workers = batches.length < maxConcurrentBatches ? batches.length : maxConcurrentBatches;
          await Future.wait([
            for (var w = 0; w < workers; w++)
              () async {
                while (cursor < batches.length) {
                  final batch = batches[cursor++];
                  final read = await _readBatch(kind, target, batch);
                  if (read == null) continue;
                  for (final r in batch) {
                    _put(_key(kind, target, r.id), read[r.id]);
                  }
                  found.addAll(read);
                }
              }(),
          ]);
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

  /// One batch's names: by id, then the songs missing by ISRC (timed out separately so id answers stand).
  Future<Map<String, AppleMusicNames>?> _readBatch(
    _Kind kind,
    OriginalNameTarget target,
    List<_Request> requests,
  ) async {
    final verifyIds = {for (final r in requests) if (r.verifyNotJapanese) r.id};
    final query = <String, String>{
      'ids': requests.map((r) => r.id).join(','),
      'l': target.language,
      if (verifyIds.isNotEmpty && kind == _Kind.artists) 'views': 'top-songs',
      if (verifyIds.isNotEmpty && kind == _Kind.albums) 'include': 'tracks',
    };
    final Map<String, Object?> response;
    try {
      response = await _catalog(['v1', 'catalog', target.storefront, kind.path], query).timeout(batchTimeout);
    } catch (_) {
      return null;
    }
    final found = <String, AppleMusicNames>{};
    for (final item in _data(response)) {
      final id = item['id'];
      final attributes = item['attributes'];
      if (id is! String || attributes is! Map) continue;
      final attrs = attributes.cast<String, Object?>();
      if (verifyIds.contains(id) && _isConfirmedNonWesternOrigin(kind, item, attrs)) {
        continue;
      }
      found[id] = AppleMusicNames(
        title: _text(attrs['name']),
        artist: _text(attrs['artistName']),
        album: _text(attrs['albumName']),
      );
    }
    // A storefront may carry a song under an id of its own; the recording's ISRC still finds it.
    if (kind == _Kind.songs) {
      final byIsrc = [
        for (final r in requests)
          if (!found.containsKey(r.id) && r.isrc != null) r,
      ];
      if (byIsrc.isNotEmpty) {
        // The ids' answer stands even when this second read fails or times out.
        try {
          final songs = await _songsByIsrc(target, [for (final r in byIsrc) r.isrc!]).timeout(batchTimeout);
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

  static bool _isConfirmedNonWesternOrigin(_Kind kind, Map<String, Object?> item, Map<String, Object?> attrs) {
    final itemGenreTarget = OriginalNameTarget.forGenre(_genreString(attrs));
    if (itemGenreTarget != null && itemGenreTarget != OriginalNameTarget.unitedStates) return true;
    final List<Map<String, Object?>> nestedTracks;
    if (kind == _Kind.artists) {
      final views = item['views'] is Map ? (item['views'] as Map).cast<String, Object?>() : null;
      nestedTracks = _nestedData(views?['top-songs']);
    } else if (kind == _Kind.albums) {
      final rels = item['relationships'] is Map ? (item['relationships'] as Map).cast<String, Object?>() : null;
      nestedTracks = _nestedData(rels?['tracks']);
    } else {
      return false;
    }
    for (final track in nestedTracks) {
      final tAttrs = track['attributes'] is Map ? (track['attributes'] as Map).cast<String, Object?>() : null;
      if (tAttrs == null) continue;
      final isrcTarget = OriginalNameTarget.forIsrc(_text(tAttrs['isrc']));
      if (isrcTarget != null && isrcTarget != OriginalNameTarget.unitedStates) return true;
      final genreTarget = OriginalNameTarget.forGenre(_genreString(tAttrs));
      if (genreTarget != null && genreTarget != OriginalNameTarget.unitedStates) return true;
      final name = _text(tAttrs['name']) ?? '';
      if (OriginalNameTarget.hasJapaneseScript(name)) return true;
    }
    return false;
  }

  static List<Map<String, Object?>> _nestedData(Object? container) {
    if (container is! Map) return const [];
    final data = container['data'];
    if (data is! List) return const [];
    return [for (final el in data) if (el is Map) el.cast<String, Object?>()];
  }

  static String? _genreString(Map<String, Object?>? attrs) {
    final genres = attrs?['genreNames'];
    if (genres is! List) return null;
    final joined = genres.whereType<String>().join(', ');
    return joined.isEmpty ? null : joined;
  }

  /// The first catalog song for each ISRC (`songs?filter[isrc]=…`), at most 25 codes a request.
  Future<Map<String, AppleMusicNames>> _songsByIsrc(OriginalNameTarget target, List<String> isrcs) async {
    final codes = {for (final c in isrcs) c.trim().toUpperCase()}.where(_isrc.hasMatch).toList();
    if (codes.isEmpty) return const {};
    final batches = <List<String>>[
      for (var i = 0; i < codes.length; i += _isrcBatch) codes.sublist(i, (i + _isrcBatch).clamp(0, codes.length)),
    ];
    final out = <String, AppleMusicNames>{};
    var cursor = 0;
    final workers = batches.length < maxConcurrentBatches ? batches.length : maxConcurrentBatches;
    await Future.wait([
      for (var w = 0; w < workers; w++)
        () async {
          while (cursor < batches.length) {
            final batch = batches[cursor++];
            final batchSet = batch.toSet();
            final response = await _catalog(
              ['v1', 'catalog', target.storefront, 'songs'],
              {'filter[isrc]': batch.join(','), 'l': target.language},
            );
            for (final item in _data(response)) {
              final attributes = item['attributes'];
              if (attributes is! Map) continue;
              final code = _text(attributes['isrc'])?.toUpperCase();
              if (code == null || !batchSet.contains(code) || out.containsKey(code)) continue;
              out[code] = AppleMusicNames(
                title: _text(attributes['name']),
                artist: _text(attributes['artistName']),
                album: _text(attributes['albumName']),
              );
            }
          }
        }(),
    ]);
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
  const _Request(this.id, this.target, this.isrc, {this.verifyNotJapanese = false});
  final String id;
  final OriginalNameTarget target;
  final String? isrc;
  final bool verifyNotJapanese;
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
