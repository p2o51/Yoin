/// The provider interface (data/source/MusicSource.kt), sliced by concern and
/// gated by [Capability] so the UI hides what a provider can't do. v1 carries
/// only what the Subsonic MVP needs; add methods here (and a Capability) when
/// a feature needs them, implementing them in every source.
library;

import '../model/model.dart';

enum Capability {
  favorites,
  search,
  randomSongs,
  playlistsRead,
  lyrics,
  rating,
  playReporting,
}

/// Thrown by a source when a call isn't supported by this provider.
final class UnsupportedSourceOperation implements Exception {
  const UnsupportedSourceOperation(this.operation);
  final String operation;
  @override
  String toString() => 'UnsupportedSourceOperation($operation)';
}

/// A source call failed in a way the UI can describe (server error, auth, network).
sealed class SourceException implements Exception {
  const SourceException(this.message);
  final String message;
  @override
  String toString() => '$runtimeType: $message';
}

/// Wrong user / password / token.
final class SourceAuthException extends SourceException {
  const SourceAuthException(super.message);
}

/// The server answered with an error that isn't auth.
final class SourceServerException extends SourceException {
  const SourceServerException(super.message, {this.code});
  final int? code;
}

/// Couldn't reach the server (DNS, TLS, timeout, connection refused).
final class SourceNetworkException extends SourceException {
  const SourceNetworkException(super.message);
}

abstract interface class MusicSource {
  /// Provider token, matches [MediaId.provider].
  String get provider;
  Set<Capability> get capabilities;

  MusicLibrary get library;
  MusicMetadata get metadata;
  MusicWriteActions get writeActions;
  MusicPlayback get playback;

  /// A loadable image URL for [ref], or null. [size] is the longest edge in px.
  String? resolveCoverUrl(CoverRef ref, {int? size});

  /// Release clients, sockets, timers. The source is unusable afterwards.
  void dispose();
}

/// Album list orderings (Subsonic getAlbumList2 `type`).
enum AlbumListType { newest, recent, frequent, random, alphabeticalByName, alphabeticalByArtist, starred }

abstract interface class MusicLibrary {
  /// True when the server answers and the credentials work.
  Future<bool> ping();
  Future<List<Album>> getAlbumList(AlbumListType type, {int size = 20, int offset = 0});

  /// With tracks; null when the id doesn't exist.
  Future<Album?> getAlbum(MediaId id);
  Future<List<ArtistIndex>> getArtists();
  Future<ArtistDetail?> getArtist(MediaId id);
  Future<List<Playlist>> getPlaylists();

  /// With tracks; null when the id doesn't exist.
  Future<Playlist?> getPlaylist(MediaId id);
  Future<Starred> getStarred();
  Future<List<Track>> getRandomSongs({int size = 20});
  Future<SearchResults> search(String query, {int trackCount = 20, int albumCount = 20, int artistCount = 20});
}

abstract interface class MusicMetadata {
  /// Lyrics for a track, or null when the server has none.
  Future<Lyrics?> getLyrics(Track track);
}

abstract interface class MusicWriteActions {
  /// Star / unstar a track, album or artist by id.
  Future<void> setFavorite(MediaId id, bool favorite);

  /// 0 clears, 1–5 sets.
  Future<void> setRating(MediaId trackId, int rating);
}

abstract interface class MusicPlayback {
  Future<PlaybackHandle> handleFor(Track track);

  /// Tell the server what started playing (Subsonic scrobble submission=false).
  Future<void> reportNowPlaying(MediaId trackId);

  /// Record a finished play (Subsonic scrobble submission=true).
  Future<void> submitPlay(MediaId trackId, DateTime playedAt);
}
