/// Renames songs, albums, and artists after another storefront's names, as an
/// account's [AppleMusicNameRegion] asks (Android AppleMusicNameResolver.kt).
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
  const _SourceAlbumFallback({
    this.artistIds = const [],
    this.firstTrackIsrc,
    this.preferredChineseTarget,
    required this.hasJapaneseScript,
    this.genre,
    required this.tracksInspected,
  });
  final List<String> artistIds;
  final String? firstTrackIsrc;
  final OriginalNameTarget? preferredChineseTarget;
  final bool hasJapaneseScript;
  final String? genre;
  final bool tracksInspected;
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
  static const maxConcurrentBatches = 4;

  static const _inspectAlbumsBatch = 15;
  static const _inspectArtistsBatch = 10;
  static const _artistsOnlyAlbumsBatch = 50;
  static const _isrcTimeout = Duration(seconds: 10);

  final _cache = <String, AppleMusicNames?>{};
  final _sourceAlbumCache = <String, _SourceAlbumFallback?>{};
  final _artistNameCache = <String, String>{};

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
    final resolved = await _resolve(_Kind.songs, requests);
    final names = resolved.names;
    return [
      for (var i = 0; i < tracks.length; i++)
        if (byTrack[i] case final r? when names[r.id] != null)
          () {
            final pickedArtist = _pick(region, r.target, tracks[i].artist, names[r.id]!.artist, isArtist: true);
            _rememberArtistName(region, tracks[i].artist, pickedArtist);
            return renamedTrack(
              tracks[i],
              title: _pick(region, r.target, tracks[i].title, names[r.id]!.title),
              artist: pickedArtist,
              album: _pick(region, r.target, tracks[i].album, names[r.id]!.album),
            );
          }()
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
      final checkChinese = region == AppleMusicNameRegion.original &&
          target == OriginalNameTarget.taiwan &&
          OriginalNameTarget.isMandopopGeneric(genre: a.genre, isrc: isrc);
      final r = _Request(
        a.id.rawId,
        target,
        null,
        verifyNotJapanese: speculative,
        checkChineseRegion: checkChinese,
      );
      requests.add(r);
      byAlbum[i] = r;
    }
    if (requests.isEmpty) return albums;
    final resolved = await _resolve(_Kind.albums, requests);
    final names = Map<String, AppleMusicNames>.from(resolved.names);
    final effectiveTarget = Map<String, OriginalNameTarget>.from(resolved.targets);
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
    for (var i = 0; i < albums.length; i++) {
      final r = byAlbum[i];
      if (r != null && names[r.id] != null) {
        final t = effectiveTarget[r.id] ?? r.target;
        final pickedArtist = _pick(region, t, albums[i].artist, names[r.id]!.artist, isArtist: true);
        _rememberArtistName(region, albums[i].artist, pickedArtist);
      }
    }
    return [
      for (var i = 0; i < albums.length; i++)
        if (byAlbum[i] case final r? when names[r.id] != null)
          () {
            final t = effectiveTarget[r.id] ?? r.target;
            final pickedArtist = _pick(region, t, albums[i].artist, names[r.id]!.artist, isArtist: true) ??
                _cachedArtistName(region, albums[i].artist) ??
                albums[i].artist;
            return renamedAlbum(
              albums[i],
              name: _pick(region, t, albums[i].name, names[r.id]!.title) ?? albums[i].name,
              artist: pickedArtist,
            );
          }()
        else if (_cachedArtistName(region, albums[i].artist) case final cached?)
          renamedAlbum(albums[i], name: albums[i].name, artist: cached)
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
      final checkChinese = region == AppleMusicNameRegion.original &&
          target == OriginalNameTarget.taiwan &&
          OriginalNameTarget.isMandopopGeneric(genre: k.genre);
      final r = _Request(
        id,
        target,
        null,
        verifyNotJapanese: speculative,
        checkChineseRegion: checkChinese,
      );
      requests.add(r);
      byArtist[i] = r;
    }
    if (requests.isEmpty) return artists;
    final resolved = await _resolve(_Kind.artists, requests);
    return [
      for (var i = 0; i < artists.length; i++)
        if (byArtist[i] case final r? when resolved.names[r.id] != null)
          () {
            final t = resolved.targets[r.id] ?? r.target;
            final picked = _pick(region, t, artists[i].name, resolved.names[r.id]!.title, isArtist: true) ??
                artists[i].name;
            _rememberArtistName(region, artists[i].name, picked);
            return renamedArtist(artists[i], name: picked);
          }()
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
    final withTracksIds = <String>[];
    final artistsOnlyIds = <String>[];
    if (region == AppleMusicNameRegion.original) {
      for (final req in missed) {
        final title = albumsById[req.id]?.name ?? '';
        final titleNeedsRecovery =
            (req.target == OriginalNameTarget.unitedStates && OriginalNameTarget.hasNonLatin(title)) ||
                (OriginalNameTarget.isChinese(req.target) && !OriginalNameTarget.hasHanText(title));
        if (req.verifyNotJapanese || req.checkChineseRegion || titleNeedsRecovery) {
          withTracksIds.add(req.id);
        } else {
          artistsOnlyIds.add(req.id);
        }
      }
    } else {
      for (final req in missed) {
        artistsOnlyIds.add(req.id);
      }
    }

    final fetched = await Future.wait([
      withTracksIds.isEmpty
          ? Future.value(const <String, _SourceAlbumFallback>{})
          : _fetchSourceAlbumFallbacks(accountStorefront, withTracksIds, includeTracks: true),
      artistsOnlyIds.isEmpty
          ? Future.value(const <String, _SourceAlbumFallback>{})
          : _fetchSourceAlbumFallbacks(accountStorefront, artistsOnlyIds, includeTracks: false),
    ]);
    final sourceInfo = <String, _SourceAlbumFallback>{...fetched[0], ...fetched[1]};
    if (sourceInfo.isEmpty) return const {};

    final plans = <({
      String albumId,
      OriginalNameTarget target,
      List<String> artistIds,
      String? isrc,
      bool needsTitleByIsrc,
      bool verifyArtistNotJapanese,
      bool checkChineseRegion,
    })>[];

    for (final req in missed) {
      final info = sourceInfo[req.id];
      if (info == null) continue;
      final album = albumsById[req.id];
      OriginalNameTarget effectiveTarget;
      if (region == AppleMusicNameRegion.original) {
        final byIsrc = OriginalNameTarget.forIsrc(info.firstTrackIsrc);
        final byGenre = OriginalNameTarget.forGenre(info.genre);
        if (info.preferredChineseTarget != null) {
          effectiveTarget = info.preferredChineseTarget!;
        } else if (byIsrc != null && byIsrc.storefront == accountStorefront) {
          continue;
        } else if (byIsrc == OriginalNameTarget.unitedStates) {
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
      final needsTitle = region == AppleMusicNameRegion.original &&
          ((effectiveTarget == OriginalNameTarget.unitedStates && OriginalNameTarget.hasNonLatin(title)) ||
              (OriginalNameTarget.isChinese(effectiveTarget) && !OriginalNameTarget.hasHanText(title)));
      final confirmedNonJapanese =
          info.firstTrackIsrc != null && OriginalNameTarget.forIsrc(info.firstTrackIsrc) != null;
      final artistIds = [for (final id in info.artistIds) if (_isCatalogId(id)) id];
      plans.add((
        albumId: req.id,
        target: effectiveTarget,
        artistIds: artistIds,
        isrc: info.firstTrackIsrc,
        needsTitleByIsrc: needsTitle && info.firstTrackIsrc != null && info.firstTrackIsrc!.isNotEmpty,
        verifyArtistNotJapanese: req.verifyNotJapanese && !confirmedNonJapanese,
        checkChineseRegion: req.checkChineseRegion && info.preferredChineseTarget == null,
      ));
    }
    if (plans.isEmpty) return const {};

    final artistRequests = [
      for (final p in plans)
        for (final artistId in p.artistIds)
          _Request(
            artistId,
            p.target,
            null,
            verifyNotJapanese: p.verifyArtistNotJapanese,
            checkChineseRegion: p.checkChineseRegion,
          ),
    ];
    final isrcGroups = <OriginalNameTarget, List<String>>{};
    for (final p in plans) {
      if (p.needsTitleByIsrc && p.isrc != null) {
        (isrcGroups[p.target] ??= []).add(p.isrc!);
      }
    }

    final results = await Future.wait([
      artistRequests.isEmpty
          ? Future.value((names: const <String, AppleMusicNames>{}, targets: const <String, OriginalNameTarget>{}))
          : _resolve(_Kind.artists, artistRequests),
      Future.wait([
        for (final entry in isrcGroups.entries)
          () async {
            try {
              final songs = await _songsByIsrc(entry.key, entry.value).timeout(_isrcTimeout);
              return MapEntry(entry.key, songs);
            } catch (_) {
              return MapEntry(entry.key, const <String, AppleMusicNames>{});
            }
          }(),
      ]),
    ]);

    final resolvedArtists =
        results[0] as ({Map<String, AppleMusicNames> names, Map<String, OriginalNameTarget> targets});
    final resolvedIsrcs = Map<OriginalNameTarget, Map<String, AppleMusicNames>>.fromEntries(
      results[1] as List<MapEntry<OriginalNameTarget, Map<String, AppleMusicNames>>>,
    );

    final out = <String, (AppleMusicNames, OriginalNameTarget)>{};
    for (final p in plans) {
      final artistParts = [
        for (final artistId in p.artistIds)
          if (resolvedArtists.names[artistId]?.title case final t? when t.isNotEmpty) t,
      ];
      final artistName = artistParts.isEmpty ? null : artistParts.join(' & ');
      OriginalNameTarget? resolvedTarget;
      for (final artistId in p.artistIds) {
        resolvedTarget = resolvedArtists.targets[artistId];
        if (resolvedTarget != null) break;
      }
      final effectiveTarget = resolvedTarget ?? p.target;
      final targetIsrcs = resolvedIsrcs[p.target];
      final songMatch = (p.isrc != null && targetIsrcs != null) ? targetIsrcs[p.isrc!.toUpperCase()] : null;
      final title = songMatch?.album;
      final artist = artistName ?? songMatch?.artist;
      if (title != null || artist != null) {
        final resolved = AppleMusicNames(title: title, artist: artist);
        out[p.albumId] = (resolved, effectiveTarget);
        _put(_key(_Kind.albums, effectiveTarget, p.albumId), resolved);
      }
    }
    return out;
  }

  Future<Map<String, _SourceAlbumFallback>> _fetchSourceAlbumFallbacks(
    String accountStorefront,
    List<String> albumIds, {
    required bool includeTracks,
  }) async {
    final known = <String, _SourceAlbumFallback>{};
    final unread = <String>[];
    for (final id in albumIds.toSet()) {
      final k = '$accountStorefront|$id';
      if (_sourceAlbumCache.containsKey(k)) {
        final hit = _sourceAlbumCache[k];
        if (hit == null || hit.tracksInspected || !includeTracks) {
          if (hit != null) known[id] = hit;
        } else {
          unread.add(id);
        }
      } else {
        unread.add(id);
      }
    }
    if (unread.isEmpty) return known;
    final batchSize = includeTracks ? _inspectAlbumsBatch : _artistsOnlyAlbumsBatch;
    final includeValue = includeTracks ? 'artists,tracks' : 'artists';
    final batches = <List<String>>[
      for (var i = 0; i < unread.length; i += batchSize)
        unread.sublist(i, (i + batchSize).clamp(0, unread.length)),
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
                {'ids': batch.join(','), 'include': includeValue},
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
              final artistIds = [for (final a in artistsData) ?_text(a['id'])];
              final tracksData = _nestedData(rels?['tracks']);
              String? firstIsrc;
              var hasJpScript = false;
              final chineseTarget = includeTracks ? _preferredChineseTargetFromTracks(tracksData) : null;
              for (final track in tracksData) {
                final tAttrs = track['attributes'] is Map ? (track['attributes'] as Map).cast<String, Object?>() : null;
                if (tAttrs == null) continue;
                firstIsrc ??= _text(tAttrs['isrc']);
                final tName = _text(tAttrs['name']) ?? '';
                if (OriginalNameTarget.hasJapaneseScript(tName)) hasJpScript = true;
              }
              parsed[id] = _SourceAlbumFallback(
                artistIds: artistIds,
                firstTrackIsrc: firstIsrc,
                preferredChineseTarget: chineseTarget,
                hasJapaneseScript: hasJpScript,
                genre: _genreString(attrs),
                tracksInspected: includeTracks,
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

  void _rememberArtistName(AppleMusicNameRegion region, String? shown, String? resolved) {
    final s = shown?.trim() ?? '';
    final r = resolved?.trim() ?? '';
    if (s.isEmpty || r.isEmpty || s == r) return;
    if (OriginalNameTarget.isCompilationArtist(s) || OriginalNameTarget.isCompilationArtist(r)) return;
    final k = '${region.key}|$s';
    _artistNameCache.remove(k);
    _artistNameCache[k] = r;
    while (_artistNameCache.length > cacheSize) {
      _artistNameCache.remove(_artistNameCache.keys.first);
    }
  }

  String? _cachedArtistName(AppleMusicNameRegion region, String? shown) {
    final s = shown?.trim() ?? '';
    if (s.isEmpty || OriginalNameTarget.isCompilationArtist(s)) return null;
    return _artistNameCache['${region.key}|$s'];
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

  Future<({Map<String, AppleMusicNames> names, Map<String, OriginalNameTarget> targets})> _resolve(
    _Kind kind,
    List<_Request> requests,
  ) async {
    final distinct = <String, _Request>{};
    for (final r in requests) {
      distinct.putIfAbsent(r.id, () => r);
    }
    final groups = <OriginalNameTarget, List<_Request>>{};
    final finalTargets = <String, OriginalNameTarget>{};
    for (final r in distinct.values) {
      (groups[r.target] ??= []).add(r);
      finalTargets[r.id] = r.target;
    }
    final found = <String, AppleMusicNames>{};
    final reclassifiedRequests = <_Request>[];

    await Future.wait([
      for (final MapEntry(key: target, value: group) in groups.entries)
        () async {
          final unreadPlain = <_Request>[];
          final unreadInspected = <_Request>[];
          for (final r in group) {
            final key = _key(kind, target, r.id);
            if (!_cache.containsKey(key)) {
              if (r.needsInspection && kind != _Kind.songs) {
                unreadInspected.add(r);
              } else {
                unreadPlain.add(r);
              }
            } else {
              final hit = _cache.remove(key);
              _cache[key] = hit;
              if (hit != null) found[r.id] = hit;
            }
          }
          final batches = <(List<_Request>, bool)>[
            for (var i = 0; i < unreadPlain.length; i += kind.batch)
              (unreadPlain.sublist(i, (i + kind.batch).clamp(0, unreadPlain.length)), false),
            for (var i = 0; i < unreadInspected.length; i += kind.inspectBatch)
              (unreadInspected.sublist(i, (i + kind.inspectBatch).clamp(0, unreadInspected.length)), true),
          ];
          if (batches.isNotEmpty) {
            var cursor = 0;
            final workers = batches.length < maxConcurrentBatches ? batches.length : maxConcurrentBatches;
            await Future.wait([
              for (var w = 0; w < workers; w++)
                () async {
                  while (cursor < batches.length) {
                    final (batch, inspect) = batches[cursor++];
                    final outcome = await _readBatch(kind, target, batch, inspect: inspect);
                    if (outcome == null) continue;
                    for (final r in batch) {
                      if (!outcome.reclassified.containsKey(r.id)) {
                        _put(_key(kind, target, r.id), outcome.found[r.id]);
                      }
                    }
                    found.addAll(outcome.found);
                    for (final entry in outcome.reclassified.entries) {
                      reclassifiedRequests.add(_Request(entry.key, entry.value, null));
                    }
                  }
                }(),
            ]);
          }
          if (kind == _Kind.songs) {
            final missedWithIsrc = [
              for (final r in unreadPlain)
                if (!found.containsKey(r.id) && r.isrc != null && r.isrc!.isNotEmpty) r,
            ];
            if (missedWithIsrc.isNotEmpty) {
              try {
                final songs = await _songsByIsrc(target, [for (final r in missedWithIsrc) r.isrc!]).timeout(_isrcTimeout);
                for (final r in missedWithIsrc) {
                  if (songs[r.isrc!.toUpperCase()] case final names?) {
                    found[r.id] = names;
                    _put(_key(kind, target, r.id), names);
                  }
                }
              } catch (_) {}
            }
          }
        }(),
    ]);

    if (reclassifiedRequests.isNotEmpty) {
      final reclassGroups = <OriginalNameTarget, List<_Request>>{};
      for (final r in reclassifiedRequests) {
        (reclassGroups[r.target] ??= []).add(r);
      }
      await Future.wait([
        for (final MapEntry(key: target, value: group) in reclassGroups.entries)
          () async {
            final unread = <_Request>[];
            for (final r in group) {
              final key = _key(kind, target, r.id);
              if (_cache.containsKey(key)) {
                final hit = _cache[key];
                if (hit != null) {
                  found[r.id] = hit;
                  finalTargets[r.id] = target;
                }
              } else {
                unread.add(r);
              }
            }
            for (var i = 0; i < unread.length; i += kind.batch) {
              final batch = unread.sublist(i, (i + kind.batch).clamp(0, unread.length));
              final outcome = await _readBatch(kind, target, batch, inspect: false);
              if (outcome == null) continue;
              for (final r in batch) {
                final names = outcome.found[r.id];
                _put(_key(kind, target, r.id), names);
                if (names != null) {
                  found[r.id] = names;
                  finalTargets[r.id] = target;
                }
              }
            }
          }(),
      ]);
    }

    return (names: found, targets: finalTargets);
  }

  void _put(String key, AppleMusicNames? names) {
    _cache.remove(key);
    _cache[key] = names;
    while (_cache.length > cacheSize) {
      _cache.remove(_cache.keys.first);
    }
  }

  Future<({Map<String, AppleMusicNames> found, Map<String, OriginalNameTarget> reclassified})?> _readBatch(
    _Kind kind,
    OriginalNameTarget target,
    List<_Request> requests, {
    required bool inspect,
  }) async {
    final verifyIds = inspect ? {for (final r in requests) if (r.verifyNotJapanese) r.id} : const <String>{};
    final checkChineseIds = inspect ? {for (final r in requests) if (r.checkChineseRegion) r.id} : const <String>{};
    final needsNested = verifyIds.isNotEmpty || checkChineseIds.isNotEmpty;
    final query = <String, String>{
      'ids': requests.map((r) => r.id).join(','),
      'l': target.language,
      if (needsNested && kind == _Kind.artists) 'views': 'top-songs',
      if (needsNested && kind == _Kind.albums) 'include': 'tracks',
    };
    final Map<String, Object?> response;
    try {
      response = await _catalog(['v1', 'catalog', target.storefront, kind.path], query).timeout(batchTimeout);
    } catch (_) {
      return null;
    }
    final found = <String, AppleMusicNames>{};
    final reclassified = <String, OriginalNameTarget>{};
    for (final item in _data(response)) {
      final id = item['id'];
      final attributes = item['attributes'];
      if (id is! String || attributes is! Map) continue;
      final attrs = attributes.cast<String, Object?>();
      if (verifyIds.contains(id) && _isConfirmedNonWesternOrigin(kind, item, attrs)) {
        continue;
      }
      if (checkChineseIds.contains(id)) {
        final preferred = _preferredChineseTargetFromTracks(_nestedTracksFor(kind, item));
        if (preferred != null && preferred != target) {
          reclassified[id] = preferred;
        }
      }
      found[id] = AppleMusicNames(
        title: _text(attrs['name']),
        artist: _text(attrs['artistName']),
        album: _text(attrs['albumName']),
      );
    }
    return (found: found, reclassified: reclassified);
  }

  static List<Map<String, Object?>> _nestedTracksFor(_Kind kind, Map<String, Object?> item) {
    if (kind == _Kind.artists) {
      final views = item['views'] is Map ? (item['views'] as Map).cast<String, Object?>() : null;
      return _nestedData(views?['top-songs']);
    } else if (kind == _Kind.albums) {
      final rels = item['relationships'] is Map ? (item['relationships'] as Map).cast<String, Object?>() : null;
      return _nestedData(rels?['tracks']);
    }
    return const [];
  }

  static OriginalNameTarget? _preferredChineseTargetFromTracks(List<Map<String, Object?>> nestedTracks) {
    if (nestedTracks.isEmpty) return null;
    var cn = 0;
    var tw = 0;
    var hk = 0;
    for (final track in nestedTracks) {
      final tAttrs = track['attributes'] is Map ? (track['attributes'] as Map).cast<String, Object?>() : null;
      if (tAttrs == null) continue;
      final t = OriginalNameTarget.forIsrc(_text(tAttrs['isrc']));
      if (t == OriginalNameTarget.mainlandChina) {
        cn++;
      } else if (t == OriginalNameTarget.taiwan) {
        tw++;
      } else if (t == OriginalNameTarget.hongKong) {
        hk++;
      }
    }
    if (cn > 0 && cn >= tw + hk) return OriginalNameTarget.mainlandChina;
    if (hk > 0 && hk > tw && cn == 0) return OriginalNameTarget.hongKong;
    if (tw > 0) return OriginalNameTarget.taiwan;
    return null;
  }

  static bool _isConfirmedNonWesternOrigin(_Kind kind, Map<String, Object?> item, Map<String, Object?> attrs) {
    final itemGenreTarget = OriginalNameTarget.forGenre(_genreString(attrs));
    if (itemGenreTarget != null && itemGenreTarget != OriginalNameTarget.unitedStates) return true;
    final nestedTracks = _nestedTracksFor(kind, item);
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

enum _Kind {
  songs('songs', 300, 300),
  albums('albums', 100, AppleMusicNameResolver._inspectAlbumsBatch),
  artists('artists', 25, AppleMusicNameResolver._inspectArtistsBatch);

  const _Kind(this.path, this.batch, this.inspectBatch);
  final String path;
  final int batch;
  final int inspectBatch;
}

final class _Request {
  const _Request(
    this.id,
    this.target,
    this.isrc, {
    this.verifyNotJapanese = false,
    this.checkChineseRegion = false,
  });
  final String id;
  final OriginalNameTarget target;
  final String? isrc;
  final bool verifyNotJapanese;
  final bool checkChineseRegion;

  bool get needsInspection => verifyNotJapanese || checkChineseRegion;
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
  contentRating: t.contentRating,
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
  releaseDate: a.releaseDate,
  recordLabels: a.recordLabels,
  originalReleaseDate: a.originalReleaseDate,
  contentRating: a.contentRating,
);

/// [a] under another name; everything else kept.
Artist renamedArtist(Artist a, {required String name}) => Artist(
  id: a.id,
  name: name,
  albumCount: a.albumCount,
  coverArt: a.coverArt,
  isStarred: a.isStarred,
);
