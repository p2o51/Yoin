import 'dart:async';

import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';

/// A configurable MusicSource for Library / detail tests: every list in
/// memory, failures injectable per call, calls recorded.
class LibraryFakeSource implements MusicSource, MusicLibrary, MusicPlayback, MusicWriteActions {
  LibraryFakeSource({
    this.artists = const [],
    this.albums = const [],
    this.playlists = const [],
    this.starred = const Starred(),
    Map<MediaId, Album>? albumDetails,
    Map<MediaId, ArtistDetail>? artistDetails,
    Map<MediaId, Playlist>? playlistDetails,
    this.searchResults = const SearchResults(),
  }) : albumDetails = albumDetails ?? {},
       artistDetails = artistDetails ?? {},
       playlistDetails = playlistDetails ?? {};

  List<Artist> artists;
  List<Album> albums;
  List<Playlist> playlists;
  Starred starred;
  final Map<MediaId, Album> albumDetails;
  final Map<MediaId, ArtistDetail> artistDetails;
  final Map<MediaId, Playlist> playlistDetails;
  SearchResults searchResults;

  /// Calls throw this while set (by name: 'getArtists', 'getAlbumList', 'getPlaylists', 'getStarred', 'getAlbum', 'search', 'setFavorite').
  final Map<String, Object> failures = {};
  final List<String> calls = [];
  final List<({AlbumListType type, int size, int offset})> albumListCalls = [];
  final List<(MediaId, bool)> favoriteWrites = [];
  final List<String> searches = [];

  /// When set, getAlbum waits for it (in-flight counting).
  Completer<void>? albumGate;
  int albumReadsInFlight = 0;
  int maxAlbumReadsInFlight = 0;

  void _maybeFail(String name) {
    calls.add(name);
    final f = failures[name];
    if (f != null) throw f;
  }

  @override
  String get provider => MediaId.providerSubsonic;
  @override
  Set<Capability> get capabilities => const {Capability.favorites, Capability.search, Capability.playlistsRead};
  @override
  MusicLibrary get library => this;
  @override
  MusicMetadata get metadata => throw const UnsupportedSourceOperation('metadata');
  @override
  MusicWriteActions get writeActions => this;
  @override
  MusicPlayback get playback => this;
  @override
  String? resolveCoverUrl(CoverRef ref, {int? size}) => 'https://covers.test/${ref.storageKey}?size=$size';
  @override
  void dispose() {}

  @override
  Future<bool> ping() async => true;

  @override
  Future<List<Album>> getAlbumList(AlbumListType type, {int size = 20, int offset = 0}) async {
    albumListCalls.add((type: type, size: size, offset: offset));
    _maybeFail('getAlbumList');
    return albums.skip(offset).take(size).toList();
  }

  @override
  Future<Album?> getAlbum(MediaId id) async {
    _maybeFail('getAlbum');
    albumReadsInFlight++;
    if (albumReadsInFlight > maxAlbumReadsInFlight) maxAlbumReadsInFlight = albumReadsInFlight;
    try {
      if (albumGate != null) await albumGate!.future;
      final f = failures['getAlbum:${id.rawId}'];
      if (f != null) throw f;
      return albumDetails[id];
    } finally {
      albumReadsInFlight--;
    }
  }

  @override
  Future<List<ArtistIndex>> getArtists() async {
    _maybeFail('getArtists');
    return [ArtistIndex(name: 'A', artists: artists)];
  }

  @override
  Future<ArtistDetail?> getArtist(MediaId id) async {
    _maybeFail('getArtist');
    return artistDetails[id];
  }

  @override
  Future<List<Playlist>> getPlaylists() async {
    _maybeFail('getPlaylists');
    return playlists;
  }

  @override
  Future<Playlist?> getPlaylist(MediaId id) async {
    _maybeFail('getPlaylist');
    return playlistDetails[id];
  }

  @override
  Future<Starred> getStarred() async {
    _maybeFail('getStarred');
    return starred;
  }

  @override
  Future<List<Track>> getRandomSongs({int size = 20}) async => const [];

  @override
  Future<SearchResults> search(String query, {int trackCount = 20, int albumCount = 20, int artistCount = 20}) async {
    searches.add(query);
    _maybeFail('search');
    return searchResults;
  }

  @override
  Future<void> setFavorite(MediaId id, bool favorite) async {
    favoriteWrites.add((id, favorite));
    _maybeFail('setFavorite');
  }

  @override
  Future<void> setRating(MediaId trackId, int rating) async {}

  @override
  Future<PlaybackHandle> handleFor(Track track) async => DirectStream('https://stream.test/${track.id.rawId}');
  @override
  Future<void> reportNowPlaying(MediaId trackId) async {}
  @override
  Future<void> submitPlay(MediaId trackId, DateTime playedAt) async {}
}

/// Test data helpers.
abstract final class LibraryFixtures {
  static Track track(
    String id, {
    String? album,
    String? albumId,
    String? artist,
    int? disc,
    int? number,
    int duration = 180,
  }) => Track(
    id: MediaId.subsonic(id),
    title: 'Song $id',
    artist: artist,
    album: album,
    albumId: albumId == null ? null : MediaId.subsonic(albumId),
    discNumber: disc,
    trackNumber: number,
    durationSec: duration,
  );

  static Album album(
    String id, {
    String? name,
    String? artist,
    String? addedAt,
    int? year,
    List<Track> tracks = const [],
  }) => Album(
    id: MediaId.subsonic(id),
    name: name ?? 'Album $id',
    artist: artist,
    coverArt: CoverSourceRelative(id),
    addedAt: addedAt,
    year: year,
    tracks: tracks,
    songCount: tracks.length,
  );

  static Artist artist(String id, {String? name, int? albumCount}) =>
      Artist(id: MediaId.subsonic(id), name: name ?? 'Artist $id', albumCount: albumCount);
}
