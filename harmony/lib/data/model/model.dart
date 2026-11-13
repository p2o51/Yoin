/// Provider-neutral domain models (data/model/*.kt on Android). Every
/// provider maps into these; never let provider DTOs past the source layer.
library;

import 'media_id.dart';

export 'media_id.dart';

/// Where artwork comes from: a full URL, or an id the active source resolves
/// (MusicSource.resolveCoverUrl).
sealed class CoverRef {
  const CoverRef();

  /// Storage form: the URL, or the bare cover-art id.
  String get storageKey;

  static CoverRef? fromStorageKey(String? key) {
    if (key == null || key.trim().isEmpty) return null;
    final lower = key.toLowerCase();
    return lower.startsWith('http://') || lower.startsWith('https://') ? CoverUrl(key) : CoverSourceRelative(key);
  }
}

final class CoverUrl extends CoverRef {
  const CoverUrl(this.url);
  final String url;
  @override
  String get storageKey => url;
  @override
  bool operator ==(Object other) => other is CoverUrl && other.url == url;
  @override
  int get hashCode => url.hashCode;
}

final class CoverSourceRelative extends CoverRef {
  const CoverSourceRelative(this.coverArtId);
  final String coverArtId;
  @override
  String get storageKey => coverArtId;
  @override
  bool operator ==(Object other) => other is CoverSourceRelative && other.coverArtId == coverArtId;
  @override
  int get hashCode => coverArtId.hashCode;
}

/// Audio facts a source can report (data/model/AudioFormat.kt, trimmed).
final class AudioFormat {
  const AudioFormat({this.suffix, this.contentType, this.bitRateKbps, this.sampleRateHz, this.bitDepth, this.channels});
  final String? suffix;
  final String? contentType;
  final int? bitRateKbps;
  final int? sampleRateHz;
  final int? bitDepth;
  final int? channels;
}

final class Track {
  const Track({
    required this.id,
    this.title,
    this.artist,
    this.artistId,
    this.album,
    this.albumId,
    this.coverArt,
    this.durationSec,
    this.trackNumber,
    this.discNumber,
    this.year,
    this.genre,
    this.userRating,
    this.isStarred = false,
    this.addedAt,
    this.audioFormat,
  });

  final MediaId id;
  final String? title;
  final String? artist;
  final MediaId? artistId;
  final String? album;
  final MediaId? albumId;
  final CoverRef? coverArt;
  final int? durationSec;
  final int? trackNumber;
  final int? discNumber;
  final int? year;
  final String? genre;

  /// 1–5 stars where the source supports rating, else null.
  final int? userRating;
  final bool isStarred;
  final String? addedAt;
  final AudioFormat? audioFormat;

  Track copyWith({bool? isStarred, int? userRating}) => Track(
        id: id,
        title: title,
        artist: artist,
        artistId: artistId,
        album: album,
        albumId: albumId,
        coverArt: coverArt,
        durationSec: durationSec,
        trackNumber: trackNumber,
        discNumber: discNumber,
        year: year,
        genre: genre,
        userRating: userRating ?? this.userRating,
        isStarred: isStarred ?? this.isStarred,
        addedAt: addedAt,
        audioFormat: audioFormat,
      );
}

enum ReleaseType { album, ep, single, compilation }

final class Album {
  const Album({
    required this.id,
    required this.name,
    this.artist,
    this.artistId,
    this.coverArt,
    this.songCount,
    this.durationSec,
    this.year,
    this.genre,
    this.isStarred = false,
    this.tracks = const [],
    this.addedAt,
    this.releaseType,
    this.version,
  });

  final MediaId id;
  final String name;
  final String? artist;
  final MediaId? artistId;
  final CoverRef? coverArt;
  final int? songCount;
  final int? durationSec;
  final int? year;
  final String? genre;
  final bool isStarred;

  /// Filled by getAlbum; list calls leave it empty.
  final List<Track> tracks;
  final String? addedAt;
  final ReleaseType? releaseType;
  final String? version;
}

final class Artist {
  const Artist({required this.id, required this.name, this.albumCount, this.coverArt, this.isStarred = false});
  final MediaId id;
  final String name;
  final int? albumCount;
  final CoverRef? coverArt;
  final bool isStarred;
}

final class ArtistDetail {
  const ArtistDetail({
    required this.id,
    required this.name,
    this.albumCount,
    this.coverArt,
    this.isStarred = false,
    this.albums = const [],
  });
  final MediaId id;
  final String name;
  final int? albumCount;
  final CoverRef? coverArt;
  final bool isStarred;
  final List<Album> albums;
}

/// One letter group of the artist index.
final class ArtistIndex {
  const ArtistIndex({required this.name, this.artists = const []});
  final String name;
  final List<Artist> artists;
}

final class Playlist {
  const Playlist({
    required this.id,
    required this.name,
    this.owner,
    this.coverArt,
    this.songCount,
    this.durationSec,
    this.tracks = const [],
    this.canWrite = false,
    this.comment,
  });
  final MediaId id;
  final String name;
  final String? owner;
  final CoverRef? coverArt;
  final int? songCount;
  final int? durationSec;
  final List<Track> tracks;
  final bool canWrite;
  final String? comment;
}

enum SearchResultType { tracks, albums, artists, playlists }

final class SearchResults {
  const SearchResults({
    this.tracks = const [],
    this.albums = const [],
    this.artists = const [],
    this.playlists = const [],
  });
  final List<Track> tracks;
  final List<Album> albums;
  final List<Artist> artists;
  final List<Playlist> playlists;
  bool get isEmpty => tracks.isEmpty && albums.isEmpty && artists.isEmpty && playlists.isEmpty;
}

final class Starred {
  const Starred({this.tracks = const [], this.albums = const [], this.artists = const []});
  final List<Track> tracks;
  final List<Album> albums;
  final List<Artist> artists;
}

/// Lyrics: plain text, or timed lines (data/model/Lyrics.kt, credits dropped for v1).
sealed class Lyrics {
  const Lyrics({this.language});
  final String? language;
}

final class UnsyncedLyrics extends Lyrics {
  const UnsyncedLyrics(this.text, {super.language});
  final String text;
}

final class SyncedLyrics extends Lyrics {
  const SyncedLyrics(this.lines, {super.language});
  final List<LyricLine> lines;
}

final class LyricLine {
  const LyricLine(this.startMs, this.text);
  final int startMs;
  final String text;
}

/// How a track plays (data/model/PlaybackHandle.kt).
sealed class PlaybackHandle {
  const PlaybackHandle();
}

/// A URL the HarmonyOS AVPlayer can stream directly (Subsonic).
final class DirectStream extends PlaybackHandle {
  const DirectStream(this.uri, {this.headers = const {}});
  final String uri;
  final Map<String, String> headers;
}

/// Playback delegated to another controller (Spotify Connect later).
final class ExternalController extends PlaybackHandle {
  const ExternalController(this.type);
  final ExternalControllerType type;
}

enum ExternalControllerType { spotifyConnect }
