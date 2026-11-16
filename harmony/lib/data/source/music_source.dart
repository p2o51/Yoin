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

  /// Songs kept on the device for listening offline ([MusicDownloads]; Android
  /// OFFLINE_DOWNLOAD). Subsonic only: Apple Music doesn't let an app store its songs.
  offlineDownload,

  /// The player decodes the service's files itself and the server reports
  /// their ReplayGain (OpenSubsonic `replayGain`), so the account's page
  /// offers Volume leveling. Apple Music levels the volume itself.
  replayGain,

  /// Share makes a public link on request ([MusicLinks.createShareLink]):
  /// a Subsonic server's `createShare`, which anyone can open without an
  /// account. Services with public pages of their own (Apple Music) share
  /// those ([MusicLinks.webUrl]) and don't have this.
  shareLinks,
  /// The service's own catalogue can be searched besides the user's library
  /// (Android CATALOG_SEARCH): Library's search offers the two scopes, and
  /// [MusicLibraryCollection.searchLibrary] searches the library alone.
  catalogSearch,

  /// Search finds playlists too (Android SEARCH_PLAYLISTS; Subsonic's
  /// search3 has none): the search offers a Playlists filter.
  searchPlaylists,

  /// A catalogue song can be added to the user's library (Android
  /// LIBRARY_ADD; [MusicLibraryAdds]).
  libraryAdd,

  /// The library lists songs of its own (Android LIBRARY_SONGS;
  /// [MusicLibraryCollection.librarySongs]): Library has a Songs tab of them.
  librarySongs,
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

/// What a [MusicLinks.webUrl] points at; [path] is the URL segment Apple
/// Music uses (Android WebLinkKind).
enum WebLinkKind {
  album('album'),
  artist('artist'),
  playlist('playlist'),
  track('song');

  const WebLinkKind(this.path);
  final String path;
}

/// Public links to a page or a song, for Share and "Open in …" (Android
/// MusicMetadata.webUrl / createShareLink). A source's metadata implements it
/// when it has any; [MusicSourceLinks.links] finds it.
abstract interface class MusicLinks {
  /// A public web link to [id] (an album, artist, playlist or song); the
  /// provider's own app opens it where one is installed. Null where there is
  /// none: a self-hosted server, or an item only in the user's library.
  Future<String?> webUrl(WebLinkKind kind, MediaId id);

  /// A share link made on request, for Share where [webUrl] has none: a
  /// Subsonic server's `createShare` ([Capability.shareLinks]). Called only
  /// when the user taps Share, since each call makes a new link on the
  /// server. [description] shows on the share's page. Null where the service
  /// makes none; throws when the server refused.
  Future<String?> createShareLink(WebLinkKind kind, MediaId id, {String? description});
}

/// A source's links: null when its metadata has none (test fakes, a source
/// without metadata).
extension MusicSourceLinks on MusicSource {
  MusicLinks? get links {
    try {
      return switch (metadata) {
        final MusicLinks l => l,
        _ => null,
      };
    } on UnsupportedSourceOperation {
      return null;
    }
  }
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
  /// A new playlist owned by the account, holding [tracks] in order, with
  /// [description] where given (a restored backup's; Android createPlaylist's
  /// description). A failure after the playlist was made throws
  /// [PartialPlaylistCreate].
  Future<Playlist> createPlaylist(String name, {List<MediaId> tracks = const [], String? description});

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

/// A new playlist made, but not every song went in (Android
/// PartialPlaylistImportException): [playlist] exists on the service and
/// holds the first [added] songs; [cause] stopped the rest.
final class PartialPlaylistCreate implements Exception {
  const PartialPlaylistCreate(this.playlist, this.added, this.cause);
  final Playlist playlist;
  final int added;
  final Object cause;

  @override
  String toString() => 'PartialPlaylistCreate(${playlist.id}, $added added: $cause)';
}

/// A song's ISRC, and the catalogue's songs for ISRCs (Android
/// Track.EXTRA_ISRC and MusicLibrary.tracksByIsrc): what a playlist backup
/// keeps and what a restore matches first. Only where the service knows ISRCs
/// (Apple Music); a source without it is matched by title search alone.
abstract interface class MusicTrackCodes {
  /// [trackId]'s ISRC (upper case), for a row this source handed out; null when unknown.
  String? isrcOf(MediaId trackId);

  /// The catalogue's songs for each of [isrcs] that it has, by ISRC.
  Future<Map<String, List<Track>>> tracksByIsrc(List<String> isrcs);
}

/// A source's ISRCs: null when it has none.
extension MusicSourceTrackCodes on MusicSource {
  MusicTrackCodes? get trackCodes => switch (library) {
    final MusicTrackCodes c => c,
    _ => null,
  };
}

/// One song by id (Android MusicLibrary.getTrack): what Continue On reads a
/// handed-off queue back with when it didn't come from an album, a playlist
/// or Liked songs. Not a UI affordance, so no [Capability]: a source without
/// it just can't rebuild such a queue.
abstract interface class MusicTrackLookup {
  /// The song, or null when the id doesn't exist.
  Future<Track?> getTrack(MediaId id);
}

/// A source's single-song lookup: null when it has none (test fakes).
extension MusicSourceTrackLookup on MusicSource {
  MusicTrackLookup? get trackLookup => switch (library) {
    final MusicTrackLookup l => l,
    _ => null,
  };
}

/// The user's library apart from the service's catalogue (Android
/// MusicLibrary.searchLibrary / getLibrarySongs), for a service that has both:
/// gated by [Capability.catalogSearch] and [Capability.librarySongs].
abstract interface class MusicLibraryCollection {
  /// Search the saved library only; [MusicLibrary.search] searches the catalogue.
  Future<SearchResults> searchLibrary(String query);

  /// The library's songs, [size] of them from [offset], in the service's order.
  Future<List<Track>> librarySongs({int size = 100, int offset = 0});
}

/// A catalogue song's place in the user's library (Android LibraryMembership).
enum LibraryMembership {
  unknown,
  notAdded,

  /// The service took the addition but hasn't shown it in the library yet.
  pending,
  added,
}

/// Adding a song to the library (Android MusicWriteActions.addToLibrary),
/// gated by [Capability.libraryAdd]. Not a favourite: a service whose
/// favourites are its library (Spotify) uses those.
abstract interface class MusicLibraryAdds {
  /// Whether [trackId] is in the library now.
  Future<LibraryMembership> libraryMembership(MediaId trackId);

  /// Adds [trackId]. [LibraryMembership.added] only once membership has been
  /// confirmed; [LibraryMembership.pending] when the service took it but
  /// hasn't shown it yet.
  Future<LibraryMembership> addToLibrary(MediaId trackId);
}

/// A source's library collection: null when it has none. Gate the UI on the capability too.
extension MusicSourceLibraryCollection on MusicSource {
  MusicLibraryCollection? get libraryCollection => switch (library) {
    final MusicLibraryCollection c => c,
    _ => null,
  };

  MusicLibraryAdds? get libraryAdds {
    if (!capabilities.contains(Capability.libraryAdd)) return null;
    return switch (writeActions) {
      final MusicLibraryAdds a => a,
      _ => null,
    };
  }
}

/// Where a song is fetched from to keep it on the device (Android
/// OfflineDownloads.resolveDownload), gated by [Capability.offlineDownload].
/// The address is signed for the account now, so it is made as the download
/// opens and never stored.
abstract interface class MusicDownloads {
  /// The song's file ([maxBitRateKbps] null), or a [format] transcode capped
  /// at [maxBitRateKbps]; null when the address can't be made.
  Uri? downloadUri(MediaId trackId, {int? maxBitRateKbps, String? format});
}

/// A source's downloads: null when it has none. Gate the UI on the capability too.
extension MusicSourceDownloads on MusicSource {
  MusicDownloads? get downloads {
    if (!capabilities.contains(Capability.offlineDownload)) return null;
    return switch (playback) {
      final MusicDownloads d => d,
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
