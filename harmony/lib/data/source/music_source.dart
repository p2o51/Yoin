/// The provider interface (data/source/MusicSource.kt), sliced by concern and
/// gated by [Capability] so the UI hides what a provider can't do. v1 carries
/// only what the Subsonic MVP needs; add methods here (and a Capability) when
/// a feature needs them, implementing them in every source.
library;

import 'package:flutter/foundation.dart';

import '../model/model.dart';

enum Capability {
  favorites,
  search,
  randomSongs,
  playlistsRead,
  lyrics,
  rating,
  playReporting,

  /// A new playlist, and songs appended to one ([MusicPlaylistWrites.createPlaylist],
  /// [MusicPlaylistWrites.addTracksToPlaylist]): Add to playlist and New playlist.
  /// Apple Music has this but not [playlistsWrite] (its API has no rename,
  /// delete or remove for library playlists; Android PLAYLIST_IMPORT).
  playlistAdd,

  /// Rename, delete, remove songs and reorder ([MusicPlaylistWrites]); implies [playlistAdd].
  playlistsWrite,
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

  /// [track]'s title and artist in English where the service shows them in
  /// another language (Android MusicMetadata.alternateNames): Apple Music's
  /// Japan storefront writes Kim Petras as キム・ペトラス. Lyrics sources index
  /// the names a song came out under, so a lookup that found nothing under the
  /// shown names tries these. Null when there are none.
  Future<TrackNames?> alternateNames(Track track);
}

/// A song's title and artist under one set of names; see [MusicMetadata.alternateNames].
@immutable
final class TrackNames {
  const TrackNames({required this.title, required this.artist});

  final String title;
  final String artist;

  @override
  bool operator ==(Object other) => other is TrackNames && other.title == title && other.artist == artist;

  @override
  int get hashCode => Object.hash(title, artist);
}

abstract interface class MusicWriteActions {
  /// Star / unstar a track, album or artist by id.
  Future<void> setFavorite(MediaId id, bool favorite);

  /// 0 clears, 1–5 sets.
  Future<void> setRating(MediaId trackId, int rating);
}

/// Playlist writes (Android MusicWriteActions' playlist half), gated by
/// [Capability.playlistAdd] / [Capability.playlistsWrite]. A call the
/// provider can't do throws [UnsupportedSourceOperation]; callers also check
/// [Playlist.canWrite] (a followed playlist can't be changed).
abstract interface class MusicPlaylistWrites {
  /// A new playlist owned by the account, holding [tracks] in order.
  Future<Playlist> createPlaylist(String name, {List<MediaId> tracks = const []});

  /// Appends [tracks] to [playlistId], in order.
  Future<void> addTracksToPlaylist(MediaId playlistId, List<MediaId> tracks);
  Future<void> renamePlaylist(MediaId playlistId, String name);
  Future<void> deletePlaylist(MediaId playlistId);

  /// Removes the songs at [positions] (0-based, in the playlist's current order).
  Future<void> removeFromPlaylist(MediaId playlistId, List<int> positions);

  /// Replaces the playlist's songs with [tracks] in this order (a reorder).
  Future<void> setPlaylistTracks(MediaId playlistId, List<MediaId> tracks);

  /// Whether [playlist] takes new songs (Add to playlist lists it). Can be
  /// true where [Playlist.canWrite] is false: an Apple Music library
  /// playlist takes songs but can't be renamed or deleted.
  bool acceptsTracks(Playlist playlist);
}

/// A source's playlist writes: null when it has none (test fakes, providers
/// without [Capability.playlistAdd]). Gate the UI on the capability too.
extension MusicSourcePlaylists on MusicSource {
  MusicPlaylistWrites? get playlistWrites {
    if (!capabilities.contains(Capability.playlistAdd)) return null;
    return switch (writeActions) {
      final MusicPlaylistWrites w => w,
      _ => null,
    };
  }
}

abstract interface class MusicPlayback {
  Future<PlaybackHandle> handleFor(Track track);

  /// Tell the server what started playing (Subsonic scrobble submission=false).
  Future<void> reportNowPlaying(MediaId trackId);

  /// Record a finished play (Subsonic scrobble submission=true).
  Future<void> submitPlay(MediaId trackId, DateTime playedAt);
}
