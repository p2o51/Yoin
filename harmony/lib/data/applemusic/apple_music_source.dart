/// [MusicSource] for one Apple Music account through the Apple Music API
/// (Android data/source/applemusic/AppleMusicSource.kt).
///
/// What it does, as on Android: library albums / artists / playlists / songs,
/// catalogue album / artist / playlist detail, catalogue and library search,
/// Add to library, new library playlists and songs added to them, and names
/// from another storefront per the account's name region; library ids
/// stay `library:<id>`, songs fold onto their catalogue ids (see
/// apple_music_mappers.dart). What it doesn't: favourites and ratings (Apple
/// has neither through this API; Android refuses them too), lyrics (Apple
/// serves none to third parties), random songs, play reporting (MusicKit
/// reports its own plays), and playback: [MusicPlayback.handleFor] refuses
/// until a HarmonyOS player exists (docs/applemusic.md).
library;

import 'dart:collection';

import 'package:flutter/foundation.dart' show visibleForTesting;
import 'package:http/http.dart' as http;

import '../model/model.dart';
import '../profile/profile.dart';
import '../source/music_source.dart';
import 'apple_music_api.dart';
import 'apple_music_developer_token.dart';
import 'apple_music_mappers.dart';
import 'apple_music_name_region.dart';
import 'apple_music_name_resolver.dart';

export '../profile/profile.dart' show AppleMusicCredentials;
export 'apple_music_api.dart'
    show AppleMusicApi, AppleMusicApiException, AppleMusicFailure, AppleMusicStorefront, AppleMusicTrackRef;
export 'apple_music_developer_token.dart' show AppleMusicDeveloperTokenProvider, developerTokenExpiry;
export 'apple_music_mappers.dart' show AppleMusicSongFacts;

final class AppleMusicSource implements MusicSource {
  /// [client] carries both the token service and Apple's API; [dispose]
  /// closes it. [apiBaseUrl] is for tests (loopback allowed).
  ///
  /// [nameRegion] is the account's [AppleMusicNameRegion] (Android
  /// AppleMusicSource.nameRegion), read as each list is named; null keeps the
  /// names Apple shows.
  factory AppleMusicSource(
    AppleMusicCredentials credentials, {
    http.Client? client,
    Uri? apiBaseUrl,
    Future<AppleMusicNameRegion> Function()? nameRegion,
  }) {
    final transport = client ?? http.Client();
    final tokens = AppleMusicDeveloperTokenProvider(credentials.endpoint, client: transport);
    final api = AppleMusicApi(
      developerToken: tokens.token,
      musicUserToken: () => credentials.musicUserToken,
      client: transport,
      baseUrl: apiBaseUrl,
      onDeveloperTokenRejected: tokens.invalidate,
    );
    return AppleMusicSource._(api, nameRegion);
  }

  AppleMusicSource._(this._api, [this._nameRegion]) {
    _library = _AppleMusicLibrary(this);
    _playback = _AppleMusicPlayback(this);
    _metadata = _AppleMusicMetadata(this);
  }

  final AppleMusicApi _api;
  final Future<AppleMusicNameRegion> Function()? _nameRegion;
  late final _AppleMusicLibrary _library;
  late final _AppleMusicWriteActions _writeActions = _AppleMusicWriteActions(this);
  late final AppleMusicNameResolver _names = AppleMusicNameResolver(
    catalog: (segments, query) => _api.resourcePage(segments, personal: false, query: query),
  );
  late final _AppleMusicPlayback _playback;
  late final _AppleMusicMetadata _metadata;

  Future<AppleMusicStorefront>? _storefront;

  /// Song facts by track id, for the rows this source handed out (Android
  /// keeps them in Track.extras, which the neutral Track here doesn't have).
  final _facts = <MediaId, AppleMusicSongFacts>{}; // insertion-ordered: oldest first
  static const _factsMemory = 4096;

  /// A library playlist's catalogue playlist, by library raw id: what it opens
  /// as when Apple won't read it from the library.
  final _catalogPlaylists = <String, ({String catalogId, Playlist listed})>{};

  /// Apple refused recent/played's scoped `catalog` include: later reads skip it.
  bool _recentPlayedRefusesCatalog = false;

  /// Apple refused a catalogue album's `include[songs]`: later albums skip it.
  bool _albumSongsRefuseInclude = false;

  @override
  String get provider => MediaId.providerAppleMusic;

  /// Android ServiceFeatureCatalog.appleMusic: search of the catalogue and of
  /// the library (with playlists), Add to library, the library's songs, its
  /// playlists, and songs appended to its own (PLAYLIST_IMPORT).
  @override
  Set<Capability> get capabilities => const {
    Capability.search,
    Capability.catalogSearch,
    Capability.searchPlaylists,
    Capability.libraryAdd,
    Capability.librarySongs,
    Capability.playlistsRead,
    Capability.playlistAdd,
  };

  @override
  MusicLibrary get library => _library;
  @override
  MusicMetadata get metadata => _metadata;
  @override
  MusicWriteActions get writeActions => _writeActions;
  @override
  MusicPlayback get playback => _playback;

  /// Covers are full URLs already sized to 600 (Android); [size] isn't applied.
  @override
  String? resolveCoverUrl(CoverRef ref, {int? size}) => switch (ref) {
    CoverUrl(:final url) => url,
    CoverSourceRelative() => null,
  };

  @override
  void dispose() => _api.close();

  /// What this source knows about [trackId] beyond the [Track] (library id,
  /// catalogue id, availability); null for a row it never mapped.
  AppleMusicSongFacts? factsFor(MediaId trackId) => _facts[trackId];

  /// The catalogue id MusicKit would play [track] by (Android handleFor):
  /// throws [UnsupportedSourceOperation] for an import with no catalogue
  /// version, or a song the storefront can't stream (MusicKit would silently
  /// skip it and start further on).
  String catalogIdForPlayback(Track track) {
    _checkProvider(track.id);
    final facts = _facts[track.id];
    final catalogId = facts?.catalogId ?? (_digits.hasMatch(track.id.rawId) ? track.id.rawId : null);
    if (catalogId == null) {
      throw const UnsupportedSourceOperation('This imported song has no Apple Music catalog version');
    }
    if (facts?.catalogUnavailable ?? false) throw const UnsupportedSourceOperation('Unable to play this song');
    return catalogId;
  }

  static final _digits = RegExp(r'^[0-9]+$');

  /// How long Add to library waits before each look at the library (Android
  /// 0, 500, 1000, 2000, 4000 ms). Tests shorten it.
  @visibleForTesting
  static List<Duration> libraryAddConfirmWaits = const [
    Duration.zero,
    Duration(milliseconds: 500),
    Duration(seconds: 1),
    Duration(seconds: 2),
    Duration(seconds: 4),
  ];

  // ------------------------------------------------------------------ names

  /// The account's name region; the default when none is set or it can't be read.
  Future<AppleMusicNameRegion> _region() async {
    final read = _nameRegion;
    if (read == null) return AppleMusicNameRegion.defaultRegion;
    try {
      return await read();
    } catch (_) {
      return AppleMusicNameRegion.defaultRegion;
    }
  }

  /// The account's storefront for the names, or null while it can't be read (then every region is asked).
  Future<String?> _storefrontOrNull() async {
    try {
      return await _storefrontId();
    } catch (_) {
      return null;
    }
  }

  ({String? catalogId, String? isrc}) _songKeys(Track track) {
    final facts = _facts[track.id];
    return (
      catalogId: facts?.catalogId ?? (_digits.hasMatch(track.id.rawId) ? track.id.rawId : null),
      isrc: facts?.isrc,
    );
  }

  Future<List<Track>> _regionalTracks(List<Track> tracks) async {
    final region = await _region();
    if (region == AppleMusicNameRegion.off) return tracks;
    return _names.tracks(region, await _storefrontOrNull(), tracks, keys: _songKeys);
  }

  Future<List<Album>> _regionalAlbums(List<Album> albums) async {
    final region = await _region();
    if (region == AppleMusicNameRegion.off) return albums;
    return _names.albums(region, await _storefrontOrNull(), albums);
  }

  Future<Album?> _regionalAlbum(Album? album) async {
    if (album == null) return null;
    final region = await _region();
    if (region == AppleMusicNameRegion.off) return album;
    return _names.album(region, await _storefrontOrNull(), album, keys: _songKeys);
  }

  // ------------------------------------------------------------------ plumbing

  void _checkProvider(MediaId id) {
    if (id.provider != MediaId.providerAppleMusic) {
      throw ArgumentError.value(id, 'id', 'AppleMusicSource received a non-Apple Music MediaId');
    }
  }

  /// The account's storefront, read once; concurrent callers share the read
  /// (Android: 46 parallel storefront reads drew 429s). A failed read is
  /// forgotten so the next caller asks again.
  Future<AppleMusicStorefront> _storefrontDetails() {
    if (_storefront case final known?) return known;
    final read = _api.storefrontDetails();
    _storefront = read;
    read.then<void>(
      (_) {},
      onError: (Object _) {
        if (identical(_storefront, read)) _storefront = null;
      },
    );
    return read;
  }

  Future<String> _storefrontId() async => (await _storefrontDetails()).id;

  /// `/v1/me/library/<kind>[/id]` for a library id (or none), else
  /// `/v1/catalog/<storefront>/<kind>/<id>`.
  Future<List<String>> _path(String kind, [MediaId? id]) async {
    if (id != null) _checkProvider(id);
    if (id == null || isLibraryId(id)) return ['v1', 'me', 'library', kind, ?(id == null ? null : appleId(id))];
    return ['v1', 'catalog', await _storefrontId(), kind, appleId(id)];
  }

  Future<AppleMusicJson> _page(
    List<String> path, {
    Map<String, String> query = const {},
    String? next,
    Set<String> excluded = const {},
  }) => _api.resourcePage(
    path,
    personal: path.length > 1 && path[1] == 'me',
    query: query,
    next: next,
    excludedQueryParameters: excluded,
  );

  /// Every page of [path]. With a [fallback], a first page Apple refuses as it
  /// refuses an include is read again with it, and later pages leave out what
  /// [fallback] dropped even if their next links carry it.
  Future<List<AppleMusicJson>> _all(
    List<String> path, {
    Map<String, String> query = const {},
    Map<String, String>? fallback,
  }) async {
    final result = <AppleMusicJson>[];
    final visited = <String>{};
    var pageQuery = query;
    var excluded = const <String>{};
    String? next;
    do {
      AppleMusicJson response;
      try {
        response = await _page(path, query: pageQuery, next: next, excluded: excluded);
      } on AppleMusicApiException catch (e) {
        if (fallback == null || identical(pageQuery, fallback) || !e.mayRefuseInclude) rethrow;
        pageQuery = fallback;
        excluded = query.keys.toSet().difference(fallback.keys.toSet());
        response = await _page(path, query: pageQuery, next: next, excluded: excluded);
      }
      result.addAll(resources(response));
      next = text(response, 'next');
      if (next != null && !visited.add(next)) {
        throw const AppleMusicApiException(AppleMusicFailure.invalidResponse, detail: 'Repeated pagination link');
      }
    } while (next != null);
    return result;
  }

  List<Track> _remember(Iterable<AppleMusicSong> songs) => [for (final song in songs) _rememberOne(song)];

  Track _rememberOne(AppleMusicSong song) {
    _facts.remove(song.track.id);
    _facts[song.track.id] = song.facts;
    while (_facts.length > _factsMemory) {
      _facts.remove(_facts.keys.first);
    }
    return song.track;
  }

  Playlist? _listedPlaylist(AppleMusicJson resource) {
    final playlist = playlistFrom(resource);
    final catalogId = catalogPlaylistIdOf(resource);
    if (playlist != null && catalogId != null) {
      _catalogPlaylists[appleId(playlist.id)] = (catalogId: catalogId, listed: playlist);
    }
    return playlist;
  }

  /// Runs [body], turning Apple failures into the interface's [SourceException]s.
  Future<T> _guard<T>(Future<T> Function() body) async {
    try {
      return await body();
    } on AppleMusicApiException catch (e) {
      throw sourceExceptionFor(e);
    } on ArgumentError catch (e) {
      if (e.message == 'Invalid Apple Music pagination link') {
        throw const SourceServerException(
          'Apple Music returned an invalid pagination link',
          cause: AppleMusicApiException(AppleMusicFailure.invalidResponse, detail: 'Invalid pagination link'),
        );
      }
      rethrow;
    }
  }

  /// [body]'s result, or null when Apple answers 404 (the id doesn't exist).
  Future<T?> _nullIfNotFound<T>(Future<T?> Function() body) => _guard(() async {
    try {
      return await body();
    } on AppleMusicApiException catch (e) {
      if (e.failure == AppleMusicFailure.http && e.status == 404) return null;
      rethrow;
    }
  });
}

/// The interface's exception for an Apple failure. [SourceServerException.code]
/// is the HTTP status here (Subsonic's is its error code).
/// [SourceException.cause] keeps [e]: the UI's line and the diagnostics read it.
SourceException sourceExceptionFor(AppleMusicApiException e) => switch (e.failure) {
  AppleMusicFailure.network => SourceNetworkException(
    e.detail ?? 'Apple Music unreachable',
    kind: e.networkKind ?? NetworkFailureKind.io,
    cause: e,
  ),
  AppleMusicFailure.userAuthorizationRequired => SourceAuthException('Sign in to Apple Music again', cause: e),
  AppleMusicFailure.developerTokenRejected => SourceAuthException('Apple Music refused the developer token', cause: e),
  AppleMusicFailure.developerTokenUnavailable => SourceServerException(
    e.detail ?? 'Apple Music developer token unavailable',
    code: e.status,
    cause: e,
  ),
  // Not proof the account was revoked (Android): a server refusal, retryable later.
  AppleMusicFailure.accessDenied => SourceServerException('Apple Music refused this request', code: 403, cause: e),
  AppleMusicFailure.rateLimited => SourceServerException('Apple Music is busy, try again later', code: 429, cause: e),
  AppleMusicFailure.http => SourceServerException('Apple Music HTTP ${e.status}', code: e.status, cause: e),
  AppleMusicFailure.invalidResponse => SourceServerException(
    e.detail ?? 'Apple Music returned an invalid response',
    cause: e,
  ),
};

// ------------------------------------------------------------------ queries (Android AppleMusicSource.kt)

/// Apple's largest page for library collections and the artist albums relationship.
const _pageLimit = {'limit': '100'};
const _libraryArtistsQuery = {'limit': '100', 'include': 'catalog'};
const _libraryArtistQuery = {'include': 'catalog'};
const _libraryAlbumQuery = {'include': 'artists,catalog'};
const _libraryTracksQuery = {'include': 'catalog'};
const _librarySongQuery = {'include': 'catalog,albums,artists'};
const _catalogSongQuery = {'include': 'albums,artists'};
const _songRelationships = 'albums,artists';
const _catalogAlbumWithSongsQuery = {'include': 'artists', 'include[songs]': _songRelationships};
const _catalogAlbumQuery = {'include': 'artists'};

/// recently-added's undocumented page size; Apple's default (10) when refused.
const _recentlyAddedQuery = {'limit': '25'};

/// recent/played's largest page, with each library album's catalogue album.
const _recentPlayedQuery = {'limit': '10'};
const _recentPlayedCatalogQuery = {'limit': '10', 'include[library-albums]': 'catalog'};

/// recent/played pages read at most: Apple keeps a short history anyway.
const _recentPlayedMaxPages = 5;

/// Apple's search page size per type (its maximum).
const _searchLimit = 25;

const _albumTypes = {'albums', 'library-albums'};

Track _inAlbum(Track t, MediaId albumId, MediaId? artistId) => Track(
  id: t.id,
  title: t.title,
  artist: t.artist,
  artistId: t.artistId ?? artistId,
  album: t.album,
  albumId: t.albumId ?? albumId,
  coverArt: t.coverArt,
  durationSec: t.durationSec,
  trackNumber: t.trackNumber,
  discNumber: t.discNumber,
  year: t.year,
  genre: t.genre,
  composer: t.composer,
);

final class _AppleMusicLibrary implements MusicLibrary, MusicTrackCodes, MusicLibraryCollection, MusicTrackLookup {
  _AppleMusicLibrary(this._s);
  final AppleMusicSource _s;

  @override
  Future<bool> ping() => _s._guard(() async {
    await _s._storefrontDetails();
    return true;
  });

  /// newest → the library's recently added; recent → recently played;
  /// starred → none (Apple has no favourites here); every other order →
  /// the library's albums as Apple lists them (Android sends every type
  /// but newest there; Apple sorts nothing on request).
  @override
  Future<List<Album>> getAlbumList(AlbumListType type, {int size = 20, int offset = 0}) => _s._guard(() async {
    if (size <= 0) return const [];
    final skip = offset < 0 ? 0 : offset;
    switch (type) {
      case AlbumListType.starred:
        return const [];
      case AlbumListType.recent:
        return _s._regionalAlbums((await _recentlyPlayedAlbums(skip + size)).skip(skip).take(size).toList());
      case AlbumListType.newest:
        return _s._regionalAlbums(
          await _mixedAlbums(const ['v1', 'me', 'library', 'recently-added'], _recentlyAddedQuery, skip, size),
        );
      case AlbumListType.frequent ||
          AlbumListType.random ||
          AlbumListType.alphabeticalByName ||
          AlbumListType.alphabeticalByArtist:
        return _s._regionalAlbums(await _libraryAlbums(skip, size));
    }
  });

  /// Library albums with their catalogue albums (so a row opens under its catalogue identity).
  Future<List<Album>> _libraryAlbums(int offset, int size) async {
    final albums = <Album>[];
    final visited = <String>{};
    String? next;
    do {
      final response = await _s._page(
        const ['v1', 'me', 'library', 'albums'],
        query: {'include': 'catalog', 'limit': '100', 'offset': '$offset'},
        next: next,
      );
      albums.addAll([for (final r in resources(response)) ?albumFrom(r)]);
      next = text(response, 'next');
      if (next != null && !visited.add(next)) break;
    } while (next != null && albums.length < size);
    return albums.take(size).toList();
  }

  /// recently-added mixes albums and playlists, server-paginated: filter, then offset.
  Future<List<Album>> _mixedAlbums(List<String> path, Map<String, String> pageQuery, int skip, int size) async {
    final albums = <Album>[];
    final visited = <String>{};
    var query = pageQuery;
    String? next;
    do {
      AppleMusicJson response;
      try {
        response = await _s._page(path, query: query, next: next);
      } on AppleMusicApiException catch (e) {
        // Its page size is undocumented: refused, page at Apple's default.
        if (next != null || query.isEmpty || e.status != 400) rethrow;
        query = const {};
        response = await _s._page(path);
      }
      albums.addAll([
        for (final r in resources(response))
          if (_albumTypes.contains(text(r, 'type'))) ?albumFrom(r),
      ]);
      next = text(response, 'next');
      if (next != null && !visited.add(next)) break;
    } while (next != null && albums.length < skip + size);
    return albums.skip(skip).take(size).toList();
  }

  /// recent/played's albums (it mixes playlists and stations), each once.
  Future<List<Album>> _recentlyPlayedAlbums(int size) async {
    const path = ['v1', 'me', 'recent', 'played'];
    final albums = <Album>[];
    final seen = <MediaId>{};
    final visited = <String>{};
    var query = _s._recentPlayedRefusesCatalog ? _recentPlayedQuery : _recentPlayedCatalogQuery;
    String? next;
    do {
      AppleMusicJson response;
      try {
        response = await _s._page(path, query: query, next: next);
      } on AppleMusicApiException catch (e) {
        if (next != null || identical(query, _recentPlayedQuery) || !e.mayRefuseInclude) rethrow;
        if (e.status == 400) _s._recentPlayedRefusesCatalog = true;
        query = _recentPlayedQuery;
        response = await _s._page(path, query: query);
      }
      for (final r in resources(response)) {
        if (!_albumTypes.contains(text(r, 'type'))) continue;
        if (albumFrom(r) case final album? when seen.add(album.id)) albums.add(album);
      }
      next = text(response, 'next');
      if (next != null && !visited.add(next)) break;
    } while (next != null && albums.length < size && visited.length < _recentPlayedMaxPages);
    return albums.take(size).toList();
  }

  /// A library album opens as its full catalogue album when Apple matches one
  /// (Android's Spotify-parity rule); an import, or a match the storefront no
  /// longer serves, keeps the library tracklist under the library id.
  @override
  Future<Album?> getAlbum(MediaId id) async => _s._regionalAlbum(await _readAlbum(id));

  Future<Album?> _readAlbum(MediaId id) => _s._nullIfNotFound(() async {
    _s._checkProvider(id);
    if (!isLibraryId(id)) return _catalogAlbum(id);
    final path = await _s._path('albums', id);
    final resource = resources(await _s._page(path, query: _libraryAlbumQuery)).firstOrNull;
    if (resource == null) return null;
    final catalog = related(resource, 'catalog').where((r) => text(r, 'type') == 'albums').firstOrNull;
    final catalogId = catalog == null ? null : mediaIdOf(catalog);
    if (catalogId != null) {
      try {
        if (await _catalogAlbum(catalogId) case final album?) return album;
      } on AppleMusicApiException catch (e) {
        if (e.failsCatalogToo) rethrow;
      }
    }
    final mapped = albumFrom(resource);
    if (mapped == null) return null;
    final songs = songsFrom(await _s._all([...path, 'tracks'], query: _libraryTracksQuery), dedupe: true);
    final tracks = [for (final t in _s._remember(songs)) _inAlbum(t, id, mapped.artistId)];
    return albumWithTracks(
      Album(
        id: id,
        name: mapped.name,
        artist: mapped.artist,
        artistId: mapped.artistId,
        coverArt: mapped.coverArt,
        year: mapped.year,
        genre: mapped.genre,
        addedAt: mapped.addedAt,
        releaseType: mapped.releaseType,
      ),
      tracks,
    );
  });

  /// A catalogue album with its songs' albums and artists in one read when
  /// Apple embeds the whole tracklist, else a tracklist read of its own.
  Future<Album?> _catalogAlbum(MediaId id) async {
    final path = await _s._path('albums', id);
    AppleMusicJson response;
    var withSongs = !_s._albumSongsRefuseInclude;
    try {
      response = await _s._page(path, query: withSongs ? _catalogAlbumWithSongsQuery : _catalogAlbumQuery);
    } on AppleMusicApiException catch (e) {
      if (!withSongs || !e.mayRefuseInclude) rethrow;
      response = await _s._page(path, query: _catalogAlbumQuery);
      if (e.status == 400) _s._albumSongsRefuseInclude = true;
      withSongs = false;
    }
    final resource = resources(response).firstOrNull;
    if (resource == null) return null;
    final album = albumFrom(resource);
    if (album == null) return null;
    final entries = (withSongs ? embeddedTracks(resource) : null) ?? await _catalogTracks(path);
    final tracks = [for (final t in _s._remember(songsFrom(entries, dedupe: true))) _inAlbum(t, id, album.artistId)];
    return albumWithTracks(album, tracks);
  }

  /// The tracklist with its songs' albums and artists; Apple 500s some
  /// tracklists' includes while serving the bare tracks.
  Future<List<AppleMusicJson>> _catalogTracks(List<String> path) async {
    try {
      return await _s._all([...path, 'tracks'], query: const {'include': _songRelationships});
    } on AppleMusicApiException catch (e) {
      if (e.failure != AppleMusicFailure.http) rethrow;
      return _s._all([...path, 'tracks']);
    }
  }

  /// Library › Artists with their catalogue portraits; a refused include costs
  /// the portraits, never the list. Grouped by first letter like Android.
  @override
  Future<List<ArtistIndex>> getArtists() => _s._guard(() async {
    final listed = await _s._all(
      const ['v1', 'me', 'library', 'artists'],
      query: _libraryArtistsQuery,
      fallback: _pageLimit,
    );
    final groups = SplayTreeMap<String, List<Artist>>();
    for (final r in listed) {
      final artist = artistFrom(r);
      if (artist == null) continue;
      final letter = artist.name.isEmpty ? '#' : String.fromCharCode(artist.name.runes.first).toUpperCase();
      (groups[letter] ??= []).add(artist);
    }
    return [for (final e in groups.entries) ArtistIndex(name: e.key, artists: e.value)];
  });

  @override
  Future<ArtistDetail?> getArtist(MediaId id) => _s._nullIfNotFound(() async {
    final path = await _s._path('artists', id);
    // A library artist's portrait is its catalogue artist's.
    final query = isLibraryId(id) ? _libraryArtistQuery : const <String, String>{};
    AppleMusicJson response;
    try {
      response = await _s._page(path, query: query);
    } on AppleMusicApiException catch (e) {
      if (query.isEmpty || !e.mayRefuseInclude) rethrow;
      response = await _s._page(path);
    }
    final resource = resources(response).firstOrNull;
    final artist = resource == null ? null : artistFrom(resource);
    if (artist == null) return null;
    final albums = await _s._regionalAlbums([
      for (final r in await _s._all([...path, 'albums'], query: _pageLimit)) ?albumFrom(r),
    ]);
    return ArtistDetail(
      id: artist.id,
      name: artist.name,
      albumCount: albums.length,
      coverArt: artist.coverArt,
      albums: albums,
    );
  });

  @override
  Future<List<Playlist>> getPlaylists() => _s._guard(() async {
    final listed = await _s._all(const ['v1', 'me', 'library', 'playlists']);
    return [for (final r in listed) ?_s._listedPlaylist(r)];
  });

  /// A library playlist Apple won't read from the library (Android saw minutes
  /// of library 403s) opens as its catalogue playlist when it has one: the
  /// library's name and cover, the catalogue's tracks.
  @override
  Future<Playlist?> getPlaylist(MediaId id) => _s._nullIfNotFound(() async {
    _s._checkProvider(id);
    if (!isLibraryId(id)) return _readPlaylist(id);
    try {
      return await _readPlaylist(id);
    } on AppleMusicApiException catch (e) {
      if (e.failsCatalogToo) rethrow;
      final catalog = _s._catalogPlaylists[appleId(id)];
      if (catalog == null) rethrow;
      final Playlist? read;
      try {
        read = await _readPlaylist(MediaId(MediaId.providerAppleMusic, catalog.catalogId));
      } on AppleMusicApiException {
        throw e;
      }
      if (read == null) rethrow;
      final listed = catalog.listed;
      return playlistWithTracks(
        Playlist(
          id: listed.id,
          name: listed.name,
          owner: listed.owner,
          coverArt: listed.coverArt ?? read.coverArt,
          comment: listed.comment ?? read.comment,
          libraryAddedAt: listed.libraryAddedAt,
        ),
        read.tracks,
      );
    }
  });

  Future<Playlist?> _readPlaylist(MediaId id) async {
    final path = await _s._path('playlists', id);
    final resource = resources(await _s._page(path)).firstOrNull;
    final playlist = resource == null ? null : _s._listedPlaylist(resource);
    if (playlist == null) return null;
    final query = isLibraryId(id) ? _libraryTracksQuery : const {'include': _songRelationships};
    final tracks = _s._remember(songsFrom(await _s._all([...path, 'tracks'], query: query)));
    return playlistWithTracks(playlist, await _s._regionalTracks(tracks));
  }

  /// One song (Android getTrack): a library song with its catalogue song,
  /// albums and artists, a catalogue song with its albums and artists.
  @override
  Future<Track?> getTrack(MediaId id) => _s._nullIfNotFound(() async {
    _s._checkProvider(id);
    final path = await _s._path('songs', id);
    final query = isLibraryId(id) ? _librarySongQuery : _catalogSongQuery;
    final songs = songsFrom(resources(await _s._page(path, query: query)), dedupe: true);
    return _s._remember(songs).firstOrNull;
  });

  /// Apple has no favourites through this API.
  @override
  Future<Starred> getStarred() async => const Starred();

  @override
  Future<List<Track>> getRandomSongs({int size = 20}) async => const [];

  /// A song's ISRC as Apple gave it with the song (MusicTrackCodes).
  @override
  String? isrcOf(MediaId trackId) => _s._facts[trackId]?.isrc;

  /// The catalogue's songs by ISRC (Android AppleMusicApiClient.catalogSongsByIsrc): 25 codes a request,
  /// malformed codes left out, each song filed under the code Apple gives it.
  @override
  Future<Map<String, List<Track>>> tracksByIsrc(List<String> isrcs) => _s._guard(() async {
    final codes = {
      for (final raw in isrcs)
        if (_isrcPattern.hasMatch(raw.trim().toUpperCase())) raw.trim().toUpperCase(),
    }.toList();
    final found = <String, List<Track>>{};
    if (codes.isEmpty) return found;
    final storefront = await _s._storefrontId();
    for (var i = 0; i < codes.length; i += _isrcBatch) {
      final batch = codes.skip(i).take(_isrcBatch).toList();
      final response = await _s._page(['v1', 'catalog', storefront, 'songs'], query: {'filter[isrc]': batch.join(',')});
      for (final song in songsFrom(resources(response))) {
        final isrc = song.facts.isrc;
        if (isrc == null || !batch.contains(isrc)) continue;
        (found[isrc] ??= []).add(_s._rememberOne(song));
      }
    }
    return found;
  });

  static final _isrcPattern = RegExp(r'^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$');
  static const _isrcBatch = 25;

  /// Catalogue search: songs (with their albums and artists), albums, artists
  /// and playlists, at most [_searchLimit] of each.
  @override
  Future<SearchResults> search(String query, {int trackCount = 20, int albumCount = 20, int artistCount = 20}) =>
      _s._guard(() async {
        if (query.trim().isEmpty) return const SearchResults();
        final storefront = await _s._storefrontId();
        final response = await _s._page(
          ['v1', 'catalog', storefront, 'search'],
          query: {
            'term': query,
            'types': 'songs,albums,artists,playlists',
            'limit': '$_searchLimit',
            'include[songs]': _songRelationships,
          },
        );
        final results = obj(response['results']);
        List<AppleMusicJson> of(String type) => resources(obj(results?[type]));
        return SearchResults(
          tracks: await _s._regionalTracks(_s._remember(songsFrom(of('songs'))).take(trackCount).toList()),
          albums: await _s._regionalAlbums([for (final r in of('albums')) ?albumFrom(r)].take(albumCount).toList()),
          artists: [for (final r in of('artists')) ?artistFrom(r)].take(artistCount).toList(),
          playlists: [for (final r in of('playlists')) ?playlistFrom(r)],
        );
      });

  /// The library alone (Android searchLibrary): `/v1/me/library/search`, at
  /// most [_searchLimit] of each type. Library search takes no include, so
  /// its songs' catalogue identities are read in one more request (at most
  /// 25), which only adds what Apple answers. Two library songs (or albums)
  /// can share one catalogue identity: rows are kept once by id.
  @override
  Future<SearchResults> searchLibrary(String query) => _s._guard(() async {
    if (query.trim().isEmpty) return const SearchResults();
    final response = await _s._page(
      const ['v1', 'me', 'library', 'search'],
      query: {
        'term': query,
        'types': 'library-songs,library-albums,library-artists,library-playlists',
        'limit': '$_searchLimit',
      },
    );
    final results = obj(response['results']);
    List<AppleMusicJson> of(String type) => resources(obj(results?[type]));
    final songs = await _withCatalog(of('library-songs'));
    final tracks = <Track>[];
    final seenTracks = <MediaId>{};
    for (final t in _s._remember(songsFrom(songs))) {
      if (seenTracks.add(t.id)) tracks.add(t);
    }
    final albums = <Album>[];
    final seenAlbums = <MediaId>{};
    for (final r in of('library-albums')) {
      if (albumFrom(r) case final a? when seenAlbums.add(a.id)) albums.add(a);
    }
    return SearchResults(
      tracks: await _s._regionalTracks(tracks),
      albums: await _s._regionalAlbums(albums),
      artists: [for (final r in of('library-artists')) ?artistFrom(r)],
      playlists: [for (final r in of('library-playlists')) ?_s._listedPlaylist(r)],
    );
  });

  /// [songs] with each one's catalogue song where Apple answers it
  /// (`/v1/me/library/songs?ids=…&include=catalog`); a failed read keeps them as they are.
  Future<List<AppleMusicJson>> _withCatalog(List<AppleMusicJson> songs) async {
    bool answered(AppleMusicJson r) => obj(obj(r['relationships'])?['catalog'])?['data'] is List;
    final ids = {
      for (final r in songs)
        if (text(r, 'type') == 'library-songs' && !answered(r)) ?text(r, 'id'),
    };
    if (ids.isEmpty) return songs;
    final Map<String, Object?> checked;
    try {
      final response = await _s._page(
        const ['v1', 'me', 'library', 'songs'],
        query: {'ids': ids.join(','), 'include': 'catalog'},
      );
      checked = {
        for (final r in resources(response))
          if (text(r, 'type') == 'library-songs' && answered(r)) text(r, 'id')!: obj(r['relationships'])!['catalog'],
      };
    } on AppleMusicApiException {
      return songs;
    }
    return [
      for (final song in songs)
        if (checked[text(song, 'id')] case final catalog?)
          {
            ...song,
            'relationships': {...?obj(song['relationships']), 'catalog': catalog},
          }
        else
          song,
    ];
  }

  /// The library's songs, [size] from [offset] (Android getLibrarySongs), with
  /// their catalogue songs, albums and artists; each once.
  @override
  Future<List<Track>> librarySongs({int size = 100, int offset = 0}) => _s._guard(() async {
    if (size <= 0) return const [];
    final tracks = <Track>[];
    final seen = <MediaId>{};
    final visited = <String>{};
    String? next;
    do {
      final response = await _s._page(
        const ['v1', 'me', 'library', 'songs'],
        query: {
          'include': 'catalog,albums,artists',
          'limit': '${size < 100 ? size : 100}',
          'offset': '${offset < 0 ? 0 : offset}',
        },
        next: next,
      );
      for (final t in _s._remember(songsFrom(resources(response)))) {
        if (seen.add(t.id)) tracks.add(t);
      }
      next = text(response, 'next');
      if (next != null && !visited.add(next)) {
        throw const AppleMusicApiException(AppleMusicFailure.invalidResponse, detail: 'Repeated pagination link');
      }
    } while (next != null && tracks.length < size);
    return _s._regionalTracks(tracks.take(size).toList());
  });
}

final class _AppleMusicMetadata implements MusicMetadata, MusicLinks {
  _AppleMusicMetadata(this._s);
  final AppleMusicSource _s;

  /// Catalog id → its English names (null: the same as shown, or none); misses are kept too.
  final _names = <String, TrackNames?>{};
  static const _namesMemory = 256;
  static const _english = 'en-US';

  /// Apple serves no lyrics to third-party apps (Android returns none).
  @override
  Future<Lyrics?> getLyrics(Track track) async => null;

  /// A storefront whose own language isn't English names songs in it (on
  /// Japan's, Kim Petras is キム・ペトラス). Its catalog answers in English when
  /// asked with `l=en-US` (one of the languages it lists), which is what lyrics
  /// sources index for songs released in English (Android
  /// AppleMusicSource.alternateNames).
  /// Catalog songs only.
  @override
  Future<TrackNames?> alternateNames(Track track) async {
    if (track.id.provider != MediaId.providerAppleMusic) return null;
    final catalogId = track.id.rawId;
    if (catalogId.isEmpty || !RegExp(r'^[0-9]+$').hasMatch(catalogId)) return null;
    if (_names.containsKey(catalogId)) return _names[catalogId];
    final shop = await _s._storefrontDetails();
    if (!shop.languages.contains(_english)) return null;
    // An English storefront's names are English already, unless the account's name region replaced them.
    if ((shop.defaultLanguage?.startsWith('en') ?? false) && await _s._region() == AppleMusicNameRegion.off) {
      return null;
    }
    final page = await _s._api.resourcePage(
      ['v1', 'catalog', shop.id, 'songs', catalogId],
      personal: false,
      query: const {'l': _english},
    );
    final data = page['data'];
    final first = data is List && data.isNotEmpty ? data.first : null;
    final attrs = first is Map ? first['attributes'] : null;
    final title = attrs is Map && attrs['name'] is String ? (attrs['name'] as String).trim() : '';
    final artist = attrs is Map && attrs['artistName'] is String ? (attrs['artistName'] as String).trim() : '';
    final names = title.isNotEmpty && artist.isNotEmpty ? TrackNames(title: title, artist: artist) : null;
    _names[catalogId] = names;
    while (_names.length > _namesMemory) {
      _names.remove(_names.keys.first);
    }
    return names;
  }

  /// `https://music.apple.com/<storefront>/<kind>/<id>` for a catalogue item
  /// (Android AppleMusicSource.webUrl): digits, or a `pl.` catalogue
  /// playlist. Library-only items have no public page. Null when the
  /// storefront can't be read.
  @override
  Future<String?> webUrl(WebLinkKind kind, MediaId id) async {
    if (id.provider != MediaId.providerAppleMusic) return null;
    final rawId = id.rawId;
    final catalog = RegExp(r'^[0-9]+$').hasMatch(rawId) || (kind == WebLinkKind.playlist && rawId.startsWith('pl.'));
    if (rawId.trim().isEmpty || !catalog) return null;
    try {
      return 'https://music.apple.com/${await _s._storefrontId()}/${kind.path}/$rawId';
    } on Object {
      return null;
    }
  }

  /// Apple Music's own pages are the links: it makes no others.
  @override
  Future<String?> createShareLink(WebLinkKind kind, MediaId id, {String? description}) async => null;
}

final class _AppleMusicWriteActions implements MusicWriteActions, MusicPlaylistWrites, MusicLibraryAdds {
  _AppleMusicWriteActions(this._s);
  final AppleMusicSource _s;

  AppleMusicApi get _api => _s._api;

  /// Catalogue songs Apple took but hasn't shown in the library yet: a recheck never posts them again.
  final Set<String> _pendingAdds = {};

  /// One library write at a time (Android libraryMutationMutex).
  Future<void> _tail = Future.value();

  /// A library song is in the library; a catalogue song when Apple names its library song.
  @override
  Future<LibraryMembership> libraryMembership(MediaId trackId) => _s._guard(() async {
    _s._checkProvider(trackId);
    if (isLibraryId(trackId)) return LibraryMembership.added;
    return _membership(_catalogSongId(trackId));
  });

  /// Adds a catalogue song (`POST /v1/me/library?ids[songs]=…`, 202), then
  /// looks for it in the library a few times (0, 0.5, 1, 2, 4 s): added only
  /// once Apple shows it, pending when it took the song but hasn't yet, or a
  /// confirmation read fails after the write was taken.
  @override
  Future<LibraryMembership> addToLibrary(MediaId trackId) {
    final run = _tail.then((_) => _add(trackId));
    _tail = run.then<void>((_) {}, onError: (Object _) {});
    return run;
  }

  Future<LibraryMembership> _add(MediaId trackId) => _s._guard(() async {
    _s._checkProvider(trackId);
    if (isLibraryId(trackId)) return LibraryMembership.added;
    final catalogId = _catalogSongId(trackId);
    if (await _membership(catalogId) == LibraryMembership.added) return LibraryMembership.added;
    // Rechecking an accepted addition must not send a second mutation.
    if (!_pendingAdds.contains(catalogId)) {
      await _api.addSongToLibrary(catalogId);
      _pendingAdds.add(catalogId);
    }
    for (final wait in AppleMusicSource.libraryAddConfirmWaits) {
      await Future<void>.delayed(wait);
      final LibraryMembership now;
      try {
        now = await _membership(catalogId);
      } on AppleMusicApiException {
        // Apple accepted the write; a failed confirmation can't turn it into success.
        return LibraryMembership.pending;
      }
      if (now == LibraryMembership.added) return LibraryMembership.added;
    }
    return LibraryMembership.pending;
  });

  Future<LibraryMembership> _membership(String catalogId) async {
    if (await _api.librarySongId(await _s._storefrontId(), catalogId) != null) {
      _pendingAdds.remove(catalogId);
      return LibraryMembership.added;
    }
    return _pendingAdds.contains(catalogId) ? LibraryMembership.pending : LibraryMembership.notAdded;
  }

  static String _catalogSongId(MediaId trackId) {
    if (!RegExp(r'^[0-9]+$').hasMatch(trackId.rawId)) {
      throw ArgumentError.value(trackId, 'trackId', 'A catalog song ID is required');
    }
    return trackId.rawId;
  }

  /// Library membership isn't a favourite, and unsupported writes never fake success (Android).
  @override
  Future<void> setFavorite(MediaId id, bool favorite) async =>
      throw const UnsupportedSourceOperation('Manage this in Apple Music.');

  @override
  Future<void> setRating(MediaId trackId, int rating) async =>
      throw const UnsupportedSourceOperation('Manage this in Apple Music.');

  // A new library playlist, and songs appended to one (Capability.playlistAdd). Apple's API has no
  // rename, delete, remove or reorder for library playlists (Android AppleMusicSource).

  /// One create carrying the first [_batch] songs, then appends: a playlist Apple has only just made
  /// may not take an append yet, so a playlist of up to a batch never sends one.
  @override
  Future<Playlist> createPlaylist(String name, {List<MediaId> tracks = const [], String? description}) async {
    final refs = [for (final t in tracks) trackRef(t)];
    final resource = await _api.createLibraryPlaylist(name, refs.take(_batch).toList(), description: description);
    final created = playlistFrom(resource);
    if (created == null) {
      throw const AppleMusicApiException(AppleMusicFailure.invalidResponse, detail: 'Apple Music returned no playlist');
    }
    for (var i = _batch; i < refs.length; i += _batch) {
      try {
        await _api.addTracksToLibraryPlaylist(appleId(created.id), refs.skip(i).take(_batch).toList());
      } catch (e) {
        // The playlist is there with the songs so far: say so rather than lose it (a restore records it).
        throw PartialPlaylistCreate(created, i, e);
      }
    }
    return created;
  }

  /// A library playlist (the account's own or one it added); a catalogue playlist never.
  @override
  bool acceptsTracks(Playlist playlist) =>
      playlist.id.provider == MediaId.providerAppleMusic && isLibraryId(playlist.id);

  @override
  Future<void> addTracksToPlaylist(MediaId playlistId, List<MediaId> tracks) async {
    if (playlistId.provider != MediaId.providerAppleMusic || !isLibraryId(playlistId)) {
      throw ArgumentError.value(playlistId, 'playlistId', 'A library playlist is required');
    }
    final refs = [for (final t in tracks) trackRef(t)];
    for (var i = 0; i < refs.length; i += _batch) {
      await _api.addTracksToLibraryPlaylist(appleId(playlistId), refs.skip(i).take(_batch).toList());
    }
  }

  @override
  Future<void> renamePlaylist(MediaId playlistId, String name) async =>
      throw const UnsupportedSourceOperation('Manage this in Apple Music.');

  @override
  Future<void> deletePlaylist(MediaId playlistId) async =>
      throw const UnsupportedSourceOperation('Manage this in Apple Music.');

  @override
  Future<void> removeFromPlaylist(MediaId playlistId, List<int> positions) async =>
      throw const UnsupportedSourceOperation('Manage this in Apple Music.');

  @override
  Future<void> setPlaylistTracks(MediaId playlistId, List<MediaId> tracks) async =>
      throw const UnsupportedSourceOperation('Manage this in Apple Music.');

  /// Android PLAYLIST_ADD_BATCH.
  static const _batch = 100;

  /// A catalogue song by its digits id; a library song (`library:i.…`) as a library song.
  static AppleMusicTrackRef trackRef(MediaId track) {
    if (track.provider != MediaId.providerAppleMusic) {
      throw ArgumentError.value(track, 'track', 'An Apple Music song is required');
    }
    final raw = track.rawId;
    if (RegExp(r'^[0-9]+$').hasMatch(raw)) return AppleMusicTrackRef(raw, 'songs');
    if (isLibraryId(track)) return AppleMusicTrackRef(appleId(track), 'library-songs');
    throw ArgumentError.value(track, 'track', 'An Apple Music song is required');
  }
}

/// The personal-build switch for Apple Music playback (see [_AppleMusicPlayback.handleFor]).
const bool appleMusicNativePlayback = bool.fromEnvironment('YOIN_AM_PLAYBACK');

final class _AppleMusicPlayback implements MusicPlayback {
  _AppleMusicPlayback(this._s);
  final AppleMusicSource _s;

  /// Android hands MusicKit the catalogue id (`ExternalController(APPLE_MUSIC_KIT, id)`).
  /// HarmonyOS has no Apple Music player yet, and [ExternalControllerType]
  /// has no Apple member, so this checks the song the same way and then
  /// refuses: an import or an unavailable song with Android's reason, any
  /// other song as unsupported playback.
  @override
  Future<PlaybackHandle> handleFor(Track track) async {
    final catalogId = _s.catalogIdForPlayback(track);
    // Personal builds only (--dart-define=YOIN_AM_PLAYBACK=true): the native player behind the
    // `yoin-am:` scheme (docs/applemusic.md, Phase 3). Off by default, so no distributed build plays.
    if (appleMusicNativePlayback) return DirectStream('yoin-am:$catalogId');
    throw const UnsupportedSourceOperation('Apple Music playback is not available on HarmonyOS yet');
  }

  /// MusicKit reports its own plays; there is nothing to send through the API.
  @override
  Future<void> reportNowPlaying(MediaId trackId) async => throw const UnsupportedSourceOperation('reportNowPlaying');

  @override
  Future<void> submitPlay(MediaId trackId, DateTime playedAt) async =>
      throw const UnsupportedSourceOperation('submitPlay');
}
