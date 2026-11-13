/// [MusicSource] for one Subsonic / OpenSubsonic server account
/// (Android data/source/subsonic/SubsonicMusicSource.kt).
library;

import 'package:http/http.dart' as http;

import '../model/model.dart';
import '../profile/profile.dart';
import '../source/music_source.dart';
import 'subsonic_api.dart';
import 'subsonic_mappers.dart';

export 'subsonic_api.dart' show SubsonicApi, SubsonicAuth, SubsonicErrorCode;

/// A Subsonic server behind the provider-neutral interface.
///
/// Every request carries token auth (`u`, `t = md5(password + salt)`, `s`,
/// `v = 1.16.1`, `c = Yoin`, `f = json`); the salt is drawn once per source so
/// cover and stream URLs stay stable. [dispose] closes [client].
final class SubsonicMusicSource implements MusicSource {
  SubsonicMusicSource(
    SubsonicCredentials credentials, {
    http.Client? client,
    String? salt,
    Duration timeout = SubsonicApi.defaultTimeout,
  }) : _api = SubsonicApi(credentials: credentials, client: client ?? http.Client(), salt: salt, timeout: timeout) {
    _library = _SubsonicLibrary(_api, _kinds);
    _metadata = _SubsonicMetadata(_api);
    _writeActions = _SubsonicWriteActions(_api, _kinds);
    _playback = _SubsonicPlayback(_api);
  }

  final SubsonicApi _api;
  final _IdKinds _kinds = _IdKinds();
  late final _SubsonicLibrary _library;
  late final _SubsonicMetadata _metadata;
  late final _SubsonicWriteActions _writeActions;
  late final _SubsonicPlayback _playback;

  /// The auth pair this source signs with (tests, diagnostics).
  SubsonicApi get api => _api;

  @override
  String get provider => MediaId.providerSubsonic;

  @override
  Set<Capability> get capabilities => const {
    Capability.favorites,
    Capability.search,
    Capability.randomSongs,
    Capability.playlistsRead,
    Capability.lyrics,
    Capability.rating,
    Capability.playReporting,
  };

  @override
  MusicLibrary get library => _library;
  @override
  MusicMetadata get metadata => _metadata;
  @override
  MusicWriteActions get writeActions => _writeActions;
  @override
  MusicPlayback get playback => _playback;

  /// [CoverUrl] passes through; [CoverSourceRelative] becomes a signed
  /// `getCoverArt.view` URL ([size] = longest edge in px, null = original).
  /// Null when the base URL is unusable. No network.
  @override
  String? resolveCoverUrl(CoverRef ref, {int? size}) => switch (ref) {
    CoverUrl(:final url) => url,
    CoverSourceRelative(:final coverArtId) => _api.restUri('getCoverArt', [
      ('id', coverArtId),
      if (size != null) ('size', '$size'),
    ])?.toString(),
  };

  @override
  void dispose() => _api.close();
}

/// The raw id of a Subsonic [MediaId]; anything else is a programming error.
String _raw(MediaId id) {
  if (id.provider != MediaId.providerSubsonic) {
    throw ArgumentError.value(id, 'id', 'SubsonicMusicSource received a non-Subsonic MediaId');
  }
  return id.rawId;
}

/// Returns null for Subsonic error 70 ("data not found"): the interface's "doesn't exist".
Future<T?> _nullIfNotFound<T>(Future<T?> Function() body) async {
  try {
    return await body();
  } on SourceServerException catch (e) {
    if (e.code == SubsonicErrorCode.notFound) return null;
    rethrow;
  }
}

/// Which kind of thing each id this source has handed out names.
///
/// `star` / `unstar` take a song as `id`, an album as `albumId`, an artist as
/// `artistId` (spec-strict servers only star an artist through `artistId`),
/// but [MusicWriteActions.setFavorite] gets a bare [MediaId] with no kind.
/// Android splits the call instead: `setFavorite` always sends `id` (fine on
/// Navidrome, which resolves any id) and artists go through
/// `setArtistFollowed` with `artistId`. Here every id the library mapped is
/// remembered with its kind, so the right parameter goes out; an id never
/// seen falls back to Android's `id`.
final class _IdKinds {
  final _tracks = <String>{};
  final _albums = <String>{};
  final _artists = <String>{};

  void track(Track t) {
    _tracks.add(t.id.rawId);
    if (t.albumId case final a?) _albums.add(a.rawId);
    if (t.artistId case final a?) _artists.add(a.rawId);
  }

  void album(Album a) {
    _albums.add(a.id.rawId);
    if (a.artistId case final ar?) _artists.add(ar.rawId);
    a.tracks.forEach(track);
  }

  void artist(MediaId id) => _artists.add(id.rawId);

  /// The `star` parameter for [rawId]: `id`, `albumId` or `artistId`.
  String paramFor(String rawId) {
    if (_tracks.contains(rawId)) return 'id';
    if (_albums.contains(rawId)) return 'albumId';
    if (_artists.contains(rawId)) return 'artistId';
    return 'id';
  }
}

final class _SubsonicLibrary implements MusicLibrary {
  _SubsonicLibrary(this._api, this._kinds);
  final SubsonicApi _api;
  final _IdKinds _kinds;

  @override
  Future<bool> ping() async {
    await _api.call('ping');
    return true;
  }

  @override
  Future<List<Album>> getAlbumList(AlbumListType type, {int size = 20, int offset = 0}) async {
    final body = await _api.call('getAlbumList2', [
      ('type', albumListTypeParam(type)),
      ('size', '$size'),
      ('offset', '$offset'),
    ]);
    final albums = albumsFromJson((body['albumList2'] as Map?)?['album']);
    albums.forEach(_kinds.album);
    return albums;
  }

  @override
  Future<Album?> getAlbum(MediaId id) => _nullIfNotFound(() async {
    final body = await _api.call('getAlbum', [('id', _raw(id))]);
    final album = switch (body['album']) {
      final Map<Object?, Object?> m => albumFromJson(m.cast()),
      _ => null,
    };
    if (album != null) _kinds.album(album);
    return album;
  });

  @override
  Future<List<ArtistIndex>> getArtists() async {
    final body = await _api.call('getArtists');
    final groups = artistIndexesFromJson((body['artists'] as Map?)?.cast());
    for (final g in groups) {
      for (final a in g.artists) {
        _kinds.artist(a.id);
      }
    }
    return groups;
  }

  @override
  Future<ArtistDetail?> getArtist(MediaId id) => _nullIfNotFound(() async {
    final body = await _api.call('getArtist', [('id', _raw(id))]);
    final artist = switch (body['artist']) {
      final Map<Object?, Object?> m => artistDetailFromJson(m.cast()),
      _ => null,
    };
    if (artist != null) {
      _kinds.artist(artist.id);
      artist.albums.forEach(_kinds.album);
    }
    return artist;
  });

  @override
  Future<List<Playlist>> getPlaylists() async {
    final body = await _api.call('getPlaylists');
    final raw = (body['playlists'] as Map?)?['playlist'];
    return [
      for (final p
          in raw is List
              ? raw
              : raw is Map
              ? [raw]
              : const [])
        if (p is Map) ?playlistFromJson(p.cast(), currentUsername: _api.username),
    ];
  }

  @override
  Future<Playlist?> getPlaylist(MediaId id) => _nullIfNotFound(() async {
    final body = await _api.call('getPlaylist', [('id', _raw(id))]);
    final playlist = switch (body['playlist']) {
      final Map<Object?, Object?> m => playlistFromJson(m.cast(), currentUsername: _api.username),
      _ => null,
    };
    playlist?.tracks.forEach(_kinds.track);
    return playlist;
  });

  @override
  Future<Starred> getStarred() async {
    final body = await _api.call('getStarred2');
    final starred = starredFromJson((body['starred2'] as Map?)?.cast());
    starred.tracks.forEach(_kinds.track);
    starred.albums.forEach(_kinds.album);
    for (final a in starred.artists) {
      _kinds.artist(a.id);
    }
    return starred;
  }

  @override
  Future<List<Track>> getRandomSongs({int size = 20}) async {
    final body = await _api.call('getRandomSongs', [('size', '$size')]);
    final tracks = tracksFromJson((body['randomSongs'] as Map?)?['song']);
    tracks.forEach(_kinds.track);
    return tracks;
  }

  @override
  Future<SearchResults> search(String query, {int trackCount = 20, int albumCount = 20, int artistCount = 20}) async {
    final body = await _api.call('search3', [
      ('query', query),
      ('artistCount', '$artistCount'),
      ('albumCount', '$albumCount'),
      ('songCount', '$trackCount'),
    ]);
    final results = searchResultsFromJson((body['searchResult3'] as Map?)?.cast());
    results.tracks.forEach(_kinds.track);
    results.albums.forEach(_kinds.album);
    for (final a in results.artists) {
      _kinds.artist(a.id);
    }
    return results;
  }
}

/// [AlbumListType] → getAlbumList2 `type`.
String albumListTypeParam(AlbumListType type) => switch (type) {
  AlbumListType.newest => 'newest',
  AlbumListType.recent => 'recent',
  AlbumListType.frequent => 'frequent',
  AlbumListType.random => 'random',
  AlbumListType.alphabeticalByName => 'alphabeticalByName',
  AlbumListType.alphabeticalByArtist => 'alphabeticalByArtist',
  AlbumListType.starred => 'starred',
};

final class _SubsonicMetadata implements MusicMetadata {
  _SubsonicMetadata(this._api);
  final SubsonicApi _api;

  /// The server's OpenSubsonic extension names, asked once. A server that
  /// answers without them (plain Subsonic: an error or a 404) is remembered
  /// as having none; no answer at all (offline, auth) is asked again next time.
  Set<String>? _extensions;

  Future<Set<String>> _serverExtensions() async {
    if (_extensions case final known?) return known;
    Set<String> names;
    try {
      final body = await _api.call('getOpenSubsonicExtensions');
      final list = body['openSubsonicExtensions'];
      names = {
        if (list is List)
          for (final e in list)
            if (e is Map && e['name'] is String) e['name'] as String,
      };
    } on SourceServerException {
      names = const {};
    }
    return _extensions = names;
  }

  /// OpenSubsonic `getLyricsBySongId` when the server lists `songLyrics`
  /// (Android asks it, usually synced); when it doesn't, or has nothing for
  /// the song, the classic `getLyrics` by artist + title (iOS `lyricsFor`),
  /// whose text is read as LRC when it carries timestamps. Null when neither
  /// has lyrics. Auth and network failures are not swallowed.
  @override
  Future<Lyrics?> getLyrics(Track track) async {
    final rawId = _raw(track.id);
    if ((await _serverExtensions()).contains('songLyrics')) {
      final structured = await _nullIfNotFound(() async {
        final body = await _api.call('getLyricsBySongId', [('id', rawId)]);
        return lyricsFromStructured((body['lyricsList'] as Map?)?.cast());
      });
      if (structured != null) return structured;
    }
    final artist = track.artist?.trim() ?? '';
    final title = track.title?.trim() ?? '';
    if (artist.isEmpty && title.isEmpty) return null;
    return _nullIfNotFound(() async {
      final body = await _api.call('getLyrics', [
        if (artist.isNotEmpty) ('artist', artist),
        if (title.isNotEmpty) ('title', title),
      ]);
      return lyricsFromPlain((body['lyrics'] as Map?)?.cast());
    });
  }
}

final class _SubsonicWriteActions implements MusicWriteActions {
  _SubsonicWriteActions(this._api, this._kinds);
  final SubsonicApi _api;
  final _IdKinds _kinds;

  /// `star` / `unstar`, with the parameter [_IdKinds] picks for [id].
  @override
  Future<void> setFavorite(MediaId id, bool favorite) async {
    final raw = _raw(id);
    await _api.call(favorite ? 'star' : 'unstar', [(_kinds.paramFor(raw), raw)]);
  }

  /// `setRating`: 1–5, 0 clears; out-of-range values are clamped like Android.
  @override
  Future<void> setRating(MediaId trackId, int rating) async {
    await _api.call('setRating', [('id', _raw(trackId)), ('rating', '${rating.clamp(0, 5)}')]);
  }
}

final class _SubsonicPlayback implements MusicPlayback {
  _SubsonicPlayback(this._api);
  final SubsonicApi _api;

  /// A signed `stream.view` URL of the original file (no transcoding in v1).
  @override
  Future<PlaybackHandle> handleFor(Track track) async {
    final uri = _api.restUri('stream', [('id', _raw(track.id))]);
    if (uri == null) throw const SourceNetworkException('Invalid server URL');
    return DirectStream(uri.toString());
  }

  @override
  Future<void> reportNowPlaying(MediaId trackId) async {
    await _api.call('scrobble', [('id', _raw(trackId)), ('submission', 'false')]);
  }

  @override
  Future<void> submitPlay(MediaId trackId, DateTime playedAt) async {
    await _api.call('scrobble', [
      ('id', _raw(trackId)),
      ('time', '${playedAt.millisecondsSinceEpoch}'),
      ('submission', 'true'),
    ]);
  }
}
