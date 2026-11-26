/// Album / artist / playlist ⇄ JSON for the on-disk detail cache (Android
/// data/cache/DetailCacheDtos.kt). Short keys, absent = default, so a row stays
/// small; unknown keys are ignored and a row that doesn't decode is a miss.
library;

import '../model/model.dart';

/// The detail rows' formats (Android DetailCacheFormat). Album and artist rows say which format wrote
/// them ('fv'; a track's 'f' is its audio format); a row from before the key was written is format 1.
abstract final class DetailCacheFormat {
  /// Every row from before PR #343.
  static const legacy = 1;

  /// Tracks and albums keep the service's explicit / clean mark ('cr'). Older Apple Music album and
  /// artist rows are misses (read again from the service rather than served without the "E" for the
  /// cache's month). A Subsonic row stays: it may back an album downloaded for offline play, whose page
  /// must open with no server, and OpenSubsonic's `explicitStatus` fills in on its next background refresh.
  static const rated = 2;

  static const current = rated;

  /// Whether a row in [format] still serves [provider]'s page.
  static bool serves(int format, String provider) => format >= rated || !_ratedProviders.contains(provider);

  static const _ratedProviders = {MediaId.providerAppleMusic, MediaId.providerSpotify};

  /// The format an album / artist row was written in.
  static int of(Map<Object?, Object?> json) => switch (json['fv']) {
    final int f => f,
    _ => legacy,
  };
}

abstract final class DetailCacheJson {
  // ---------------------------------------------------------------- album

  static Map<String, Object?> album(Album a) => {
    'id': a.id.toString(),
    'n': a.name,
    'ar': ?a.artist,
    'arId': ?a.artistId?.toString(),
    'c': ?a.coverArt?.storageKey,
    'sc': ?a.songCount,
    'd': ?a.durationSec,
    'y': ?a.year,
    'g': ?a.genre,
    if (a.isStarred) 's': true,
    if (a.tracks.isNotEmpty) 't': [for (final t in a.tracks) track(t)],
    'sa': ?a.starredAt,
    'aa': ?a.addedAt,
    'rt': ?a.releaseType?.name,
    'v': ?a.version,
    'rd': ?a.releaseDate,
    if (a.recordLabels.isNotEmpty) 'lb': a.recordLabels,
    'ord': ?a.originalReleaseDate,
    'cr': ?a.contentRating?.storageName,
    'fv': DetailCacheFormat.current,
  };

  static Album? albumFrom(Object? json) {
    if (json is! Map) return null;
    final id = MediaId.tryParse(json['id'] as String?);
    final name = json['n'];
    if (id == null || name is! String) return null;
    return Album(
      id: id,
      name: name,
      artist: json['ar'] as String?,
      artistId: MediaId.tryParse(json['arId'] as String?),
      coverArt: CoverRef.fromStorageKey(json['c'] as String?),
      songCount: _int(json['sc']),
      durationSec: _int(json['d']),
      year: _int(json['y']),
      genre: json['g'] as String?,
      isStarred: json['s'] == true,
      tracks: _list(json['t'], trackFrom),
      starredAt: json['sa'] as String?,
      addedAt: json['aa'] as String?,
      releaseType: ReleaseType.values.where((r) => r.name == json['rt']).firstOrNull,
      version: json['v'] as String?,
      releaseDate: json['rd'] as String?,
      recordLabels: [
        if (json['lb'] case final List<Object?> labels)
          for (final l in labels)
            if (l is String) l,
      ],
      originalReleaseDate: json['ord'] as String?,
      contentRating: _rating(json['cr']),
    );
  }

  /// [albumFrom] for the detail cache's own album row: a miss (null) when the row predates what its
  /// provider's page needs ([DetailCacheFormat.serves]).
  static Album? cachedAlbumFrom(Object? json) {
    if (json is! Map) return null;
    final id = MediaId.tryParse(json['id'] as String?);
    if (id == null || !DetailCacheFormat.serves(DetailCacheFormat.of(json), id.provider)) return null;
    return albumFrom(json);
  }

  // ---------------------------------------------------------------- artist

  static Map<String, Object?> artist(ArtistDetail a) => {
    'id': a.id.toString(),
    'n': a.name,
    'ac': ?a.albumCount,
    'c': ?a.coverArt?.storageKey,
    if (a.isStarred) 's': true,
    if (a.albums.isNotEmpty) 'al': [for (final al in a.albums) album(al)],
    'fv': DetailCacheFormat.current,
  };

  static ArtistDetail? artistFrom(Object? json) {
    if (json is! Map) return null;
    final id = MediaId.tryParse(json['id'] as String?);
    final name = json['n'];
    if (id == null || name is! String) return null;
    return ArtistDetail(
      id: id,
      name: name,
      albumCount: _int(json['ac']),
      coverArt: CoverRef.fromStorageKey(json['c'] as String?),
      isStarred: json['s'] == true,
      albums: _list(json['al'], albumFrom),
    );
  }

  /// [artistFrom] for the detail cache's own artist row: a miss (null) when the row predates its
  /// releases' explicit marks on a provider that has them ([DetailCacheFormat.serves]).
  static ArtistDetail? cachedArtistFrom(Object? json) {
    if (json is! Map) return null;
    final id = MediaId.tryParse(json['id'] as String?);
    if (id == null || !DetailCacheFormat.serves(DetailCacheFormat.of(json), id.provider)) return null;
    return artistFrom(json);
  }

  // ---------------------------------------------------------------- playlist

  static Map<String, Object?> playlist(Playlist p) => {
    'id': p.id.toString(),
    'n': p.name,
    'o': ?p.owner,
    'c': ?p.coverArt?.storageKey,
    'sc': ?p.songCount,
    'd': ?p.durationSec,
    if (p.tracks.isNotEmpty) 't': [for (final t in p.tracks) track(t)],
    if (p.canWrite) 'w': true,
    'cm': ?p.comment,
  };

  static Playlist? playlistFrom(Object? json) {
    if (json is! Map) return null;
    final id = MediaId.tryParse(json['id'] as String?);
    final name = json['n'];
    if (id == null || name is! String) return null;
    return Playlist(
      id: id,
      name: name,
      owner: json['o'] as String?,
      coverArt: CoverRef.fromStorageKey(json['c'] as String?),
      songCount: _int(json['sc']),
      durationSec: _int(json['d']),
      tracks: _list(json['t'], trackFrom),
      canWrite: json['w'] == true,
      comment: json['cm'] as String?,
    );
  }

  // ---------------------------------------------------------------- track

  static Map<String, Object?> track(Track t) => {
    'id': t.id.toString(),
    'ti': ?t.title,
    'ar': ?t.artist,
    'arId': ?t.artistId?.toString(),
    'al': ?t.album,
    'alId': ?t.albumId?.toString(),
    'c': ?t.coverArt?.storageKey,
    'd': ?t.durationSec,
    'no': ?t.trackNumber,
    'dn': ?t.discNumber,
    'y': ?t.year,
    'g': ?t.genre,
    'r': ?t.userRating,
    if (t.isStarred) 's': true,
    'sa': ?t.starredAt,
    'aa': ?t.addedAt,
    'f': ?_format(t.audioFormat),
    'rg': ?t.replayGain?.toJson(),
    'sz': ?t.sizeBytes,
    'pc': ?t.playCount,
    'bpm': ?t.bpm,
    'cmp': ?t.composer,
    'cmt': ?t.comment,
    'p': ?t.path,
    'cr': ?t.contentRating?.storageName,
  };

  static Track? trackFrom(Object? json) {
    if (json is! Map) return null;
    final id = MediaId.tryParse(json['id'] as String?);
    if (id == null) return null;
    return Track(
      id: id,
      title: json['ti'] as String?,
      artist: json['ar'] as String?,
      artistId: MediaId.tryParse(json['arId'] as String?),
      album: json['al'] as String?,
      albumId: MediaId.tryParse(json['alId'] as String?),
      coverArt: CoverRef.fromStorageKey(json['c'] as String?),
      durationSec: _int(json['d']),
      trackNumber: _int(json['no']),
      discNumber: _int(json['dn']),
      year: _int(json['y']),
      genre: json['g'] as String?,
      userRating: _int(json['r']),
      isStarred: json['s'] == true,
      starredAt: json['sa'] as String?,
      addedAt: json['aa'] as String?,
      audioFormat: _formatFrom(json['f']),
      replayGain: ReplayGain.fromJson(json['rg']),
      sizeBytes: _int(json['sz']),
      playCount: _int(json['pc']),
      bpm: _int(json['bpm']),
      composer: json['cmp'] as String?,
      comment: json['cmt'] as String?,
      path: json['p'] as String?,
      contentRating: _rating(json['cr']),
    );
  }

  static Map<String, Object>? _format(AudioFormat? f) => f == null
      ? null
      : {
          'sfx': ?f.suffix,
          'ct': ?f.contentType,
          'kbps': ?f.bitRateKbps,
          'hz': ?f.sampleRateHz,
          'depth': ?f.bitDepth,
          'ch': ?f.channels,
        };

  static AudioFormat? _formatFrom(Object? json) {
    if (json is! Map) return null;
    return AudioFormat(
      suffix: json['sfx'] as String?,
      contentType: json['ct'] as String?,
      bitRateKbps: _int(json['kbps']),
      sampleRateHz: _int(json['hz']),
      bitDepth: _int(json['depth']),
      channels: _int(json['ch']),
    );
  }

  static int? _int(Object? v) => v is num ? v.toInt() : null;

  /// A stored [ContentRating]; unknown for anything else (a row from before it, a name this build doesn't know).
  static ContentRating? _rating(Object? v) => v is String ? ContentRating.fromStorage(v) : null;

  static List<T> _list<T>(Object? json, T? Function(Object?) decode) => [
    if (json is List)
      for (final e in json) ?decode(e),
  ];
}
