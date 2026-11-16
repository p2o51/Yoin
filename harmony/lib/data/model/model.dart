/// Provider-neutral domain models (data/model/*.kt on Android). Every
/// provider maps into these; never let provider DTOs past the source layer.
library;

import 'media_id.dart';
import 'replay_gain.dart';

export 'media_id.dart';
export 'replay_gain.dart';

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

/// Audio facts a source can report (data/model/AudioFormat.kt). A plain
/// Subsonic server sends only the suffix and the bit rate, OpenSubsonic adds
/// depth, rate and channels.
final class AudioFormat {
  const AudioFormat({this.suffix, this.contentType, this.bitRateKbps, this.sampleRateHz, this.bitDepth, this.channels});
  final String? suffix;
  final String? contentType;
  final int? bitRateKbps;
  final int? sampleRateHz;
  final int? bitDepth;
  final int? channels;

  /// E-AC-3 with joint object coding: Dolby Atmos.
  static const codecEac3Joc = 'eac3-joc';

  /// The lower-case codec ("flac", "mp3", "opus", "aac", "alac",
  /// "eac3-joc"): the suffix's, else the content type's; null when neither
  /// names one.
  String? get codec => codecForSuffix(suffix, bitDepth: bitDepth) ?? codecForMime(contentType);

  bool get isLossless => _losslessCodecs.contains(codec);

  /// Dolby Atmos in E-AC-3. Its channel count is the base layer's, not the
  /// content's layout, so it gets no channel tag.
  bool get isDolbyAtmos => codec == codecEac3Joc;

  static const _losslessCodecs = {'flac', 'alac', 'wav', 'aiff', 'ape', 'wavpack', 'dsd'};

  /// The codec a file's [suffix] holds; [bitDepth] tells an ALAC `.m4a` from
  /// an AAC one (only lossless files have a depth). Null for no suffix
  /// (AudioFormat.codecForSuffix).
  static String? codecForSuffix(String? suffix, {int? bitDepth}) {
    final s = suffix?.trim().toLowerCase();
    return switch (s) {
      null || '' => null,
      'flac' || 'fla' => 'flac',
      'mp3' => 'mp3',
      'opus' => 'opus',
      'ogg' || 'oga' => 'vorbis',
      'aac' || 'adts' => 'aac',
      'm4a' || 'mp4' || 'm4b' => (bitDepth ?? 0) > 0 ? 'alac' : 'aac',
      'alac' => 'alac',
      'wav' || 'wave' => 'wav',
      'aif' || 'aiff' || 'aifc' => 'aiff',
      'ape' => 'ape',
      'wv' => 'wavpack',
      'dsf' || 'dff' => 'dsd',
      'wma' => 'wma',
      final other => other,
    };
  }

  /// The codec a MIME type names, e.g. "audio/flac" → "flac"
  /// (AudioFormat.codecForMime).
  static String? codecForMime(String? mime) => switch (mime?.trim().toLowerCase()) {
    'audio/flac' || 'audio/x-flac' => 'flac',
    'audio/mpeg' || 'audio/mpeg-l2' || 'audio/mpeg-l1' => 'mp3',
    'audio/opus' => 'opus',
    'audio/vorbis' || 'audio/ogg' => 'vorbis',
    'audio/mp4a-latm' || 'audio/aac' => 'aac',
    'audio/alac' => 'alac',
    'audio/raw' || 'audio/wav' || 'audio/x-wav' => 'wav',
    'audio/ac3' => 'ac3',
    'audio/eac3' => 'eac3',
    'audio/eac3-joc' => codecEac3Joc,
    // AC-4 may carry Atmos too, but the MIME type doesn't say: plain AC-4.
    'audio/ac4' => 'ac4',
    _ => null,
  };
}

/// One credited artist of a [Track] and, when the service names it, that
/// artist's page (Android ArtistCredit).
final class ArtistCredit {
  const ArtistCredit(this.name, this.id);

  final String name;
  final MediaId? id;

  @override
  bool operator ==(Object other) => other is ArtistCredit && other.name == name && other.id == id;

  @override
  int get hashCode => Object.hash(name, id);

  @override
  String toString() => 'ArtistCredit($name, $id)';
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
    this.starredAt,
    this.addedAt,
    this.audioFormat,
    this.replayGain,
    this.artists = const [],
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

  /// When the user starred it (Subsonic `starred`, ISO-8601); null when not
  /// starred or unknown. Recently Added sorts by it (Android addedAt).
  final String? starredAt;

  /// When it came onto the server (Subsonic `created`).
  final String? addedAt;
  final AudioFormat? audioFormat;

  /// The song's ReplayGain (Subsonic only); null when the server says nothing.
  final ReplayGain? replayGain;

  /// Every credited artist one by one, where the service names more than the
  /// one in [artist] (OpenSubsonic `artists`; Android EXTRA_ARTISTS /
  /// EXTRA_ARTIST_IDS); empty otherwise. Read [artistCredits].
  final List<ArtistCredit> artists;

  /// Every credited artist with the page each one opens: [artists] where the
  /// service named several, else the one [artist] with [artistId] (Android
  /// Track.artistCredits). An artist the service gives no id has a null id.
  List<ArtistCredit> get artistCredits {
    if (artists.length > 1) return artists;
    final name = artist?.trim();
    return name == null || name.isEmpty ? const [] : [ArtistCredit(name, artistId)];
  }

  /// [clearUserRating] drops the rating (a null [userRating] keeps it).
  Track copyWith({bool? isStarred, int? userRating, bool clearUserRating = false}) => Track(
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
        userRating: clearUserRating ? null : (userRating ?? this.userRating),
        isStarred: isStarred ?? this.isStarred,
        starredAt: starredAt,
        addedAt: addedAt,
        audioFormat: audioFormat,
        replayGain: replayGain,
        artists: artists,
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
    this.starredAt,
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

  /// When the user starred it (Subsonic `starred`); null when not starred.
  final String? starredAt;

  /// When it came onto the server (Subsonic `created`).
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
    this.ownedByMe,
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

  /// Whether the account's user made this playlist, for Library's By You
  /// (Android Playlist.ownedByMe); null when the service doesn't say. Kept
  /// apart from [canWrite], which gates edits. Subsonic: `owner` against the
  /// login name, ignoring case; no owner is null. Apple Music: a library
  /// playlist's `canEdit`; catalogue playlists stay null.
  final bool? ownedByMe;
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
