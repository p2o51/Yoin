/// Which albums are Memories (Android data/memory AlbumMemoryCandidate +
/// AlbumMemoryCandidateBuilder): the gates and the ranking, over the rows of
/// one account.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../playback/local_ratings.dart';
import '../activity/activity.dart';
import '../model/model.dart';
import 'memory_store.dart';

/// The fewest tracks an album needs to become a Memory (owner, 2026-10-05):
/// singles and short EPs never do; an EP of four or more is fine.
const int memoryMinTrackCount = 4;

/// Rated tracks / all tracks at or above this make an album a Memory.
const double memoryRatingCoverageGate = 0.6;

/// Album and song notes at or above this make an album a Memory.
const int memoryNoteCountGate = 2;

/// Albums scanned for one build (Android MAX_CANDIDATE_SCAN_SIZE).
const int memoryMaxCandidateScan = 48;

/// Rediscover's track-signal albums past the scan (Android MAX_TRACK_SIGNAL_SEEDS).
const int memoryMaxTrackSignalSeeds = 16;

/// The track-count half of the Memory rule. [trackCount] is the album's own
/// count (the larger of the loaded track list and songCount); null when the
/// detail could not be loaded, which leaves the album to the other gates.
bool meetsMemoryTrackCount(int? trackCount) => trackCount == null || trackCount >= memoryMinTrackCount;

/// A stable id for one album Memory of one account (Android
/// stableAlbumMemorySessionId): 64-bit string hash of `profile|provider|album`.
int stableAlbumMemorySessionId(String profileId, String provider, String albumId) {
  var hash = 1125899906842597;
  for (final unit in '$profileId|$provider|$albumId'.codeUnits) {
    hash = (31 * hash + unit).toSigned(64);
  }
  return hash;
}

@immutable
final class AlbumMemoryCandidate {
  const AlbumMemoryCandidate({
    required this.profileId,
    required this.albumId,
    required this.albumName,
    this.artistName,
    required this.totalTracks,
    required this.ratedTrackCount,
    required this.ratingCoverage,
    this.averageSongRating,
    this.albumRating,
    required this.hasAlbumReview,
    required this.noteCount,
    this.lastPlayedAt,
    this.firstPlayedAt,
    this.lastWrittenAt,
    required this.isMemoryEligible,
    this.year,
    this.coverArt,
    this.album,
    this.plays,
  });

  final String profileId;
  final MediaId albumId;
  final String albumName;
  final String? artistName;
  final int totalTracks;
  final int ratedTrackCount;
  final double ratingCoverage;
  final double? averageSongRating;

  /// The user's album score when set (> 0).
  final double? albumRating;
  final bool hasAlbumReview;
  final int noteCount;

  /// Newest play or visit (activity included): the ranking's last key.
  final int? lastPlayedAt;
  final int? firstPlayedAt;

  /// Newest time the user wrote something on this album (score, review,
  /// track rating, note). Null when nothing was written.
  final int? lastWrittenAt;
  final bool isMemoryEligible;
  final int? year;
  final CoverRef? coverArt;

  /// The loaded album detail (tracks in album order); null when it failed.
  final Album? album;

  /// Plays in Yoin only (never visits); null when never played here.
  final AlbumPlays? plays;

  int get sessionId => stableAlbumMemorySessionId(profileId, albumId.provider, albumId.rawId);
}

/// Has an album review first, then coverage, notes, and the latest play.
int compareAlbumMemoryCandidates(AlbumMemoryCandidate a, AlbumMemoryCandidate b) {
  int desc<T extends Comparable<Object>>(T x, T y) => y.compareTo(x);
  var c = desc(a.hasAlbumReview ? 1 : 0, b.hasAlbumReview ? 1 : 0);
  if (c != 0) return c;
  c = desc(a.ratingCoverage, b.ratingCoverage);
  if (c != 0) return c;
  c = desc(a.noteCount, b.noteCount);
  if (c != 0) return c;
  return desc(a.lastPlayedAt ?? a.firstPlayedAt ?? 0, b.lastPlayedAt ?? b.firstPlayedAt ?? 0);
}

/// Everything one build reads, already loaded from the account's stores.
@immutable
final class AlbumMemoryInputs {
  const AlbumMemoryInputs({
    required this.profileId,
    required this.provider,
    this.albumRatings = const {},
    this.songNotes = const [],
    this.albumPlays = const [],
    this.activity = const [],
    this.trackRatings = const {},
    this.trackAlbums = const {},
  });

  final String profileId;
  final String provider;
  final Map<MediaId, AlbumRatingRow> albumRatings;
  final List<SongNote> songNotes;

  /// Most recently played first.
  final List<AlbumPlays> albumPlays;

  /// Newest first.
  final List<ActivityEvent> activity;
  final Map<MediaId, LocalRating> trackRatings;

  /// The album each known song belongs to (a lookup's link, a play in Yoin;
  /// track_album_links.dart): what places a note or a score that carries no
  /// album.
  final Map<MediaId, MediaId> trackAlbums;

  /// The album [note] is on: the one it was written on, else its song's.
  MediaId? albumOfNote(SongNote note) => note.albumId ?? trackAlbums[note.trackId];
}

typedef AlbumLoader = Future<Album?> Function(MediaId albumId);

/// Builds the Memory pool (Android AlbumMemoryCandidateBuilder.build):
/// seeds from album scores and reviews, played albums and the activity log,
/// loads each album through [loadAlbum] (the detail cache, at most
/// [maxConcurrent] at once), and keeps the eligible ones, ranked.
///
/// [alsoScan] adds albums to the scan whatever their rows say (the deck's
/// cards, refreshed in place after a write took their last row away).
///
/// HarmonyOS also seeds from albums with song notes: its play history only
/// starts with Memories, so an album noted before its first recorded play
/// would otherwise never be scanned. A note's album is the one it was written
/// on, else its song's ([AlbumMemoryInputs.trackAlbums]).
///
/// [withTrackSignals] (Rediscover, with [includeIneligible]) adds Android's
/// track-signal albums: albums whose songs carry a note or a score, placed
/// through [AlbumMemoryInputs.trackAlbums], that the scan above did not reach
/// — strongest first, at most [memoryMaxTrackSignalSeeds], and never
/// Memory-eligible.
Future<List<AlbumMemoryCandidate>> buildAlbumMemoryCandidates(
  AlbumMemoryInputs inputs, {
  required AlbumLoader loadAlbum,
  int limit = memoryMaxCandidateScan,
  bool includeIneligible = false,
  bool withTrackSignals = false,
  Iterable<MediaId> alsoScan = const [],
  int maxConcurrent = 4,
}) async {
  final provider = inputs.provider;
  final scanLimit = (limit * 4).clamp(limit, memoryMaxCandidateScan);
  final seeds = <String, _Seed>{};
  _Seed seed(String rawId) => seeds.putIfAbsent(rawId, () => _Seed(rawId));

  final ratingRows = inputs.albumRatings.values.where((r) => r.albumId.provider == provider).toList()
    ..sort((a, b) {
      var c = (b.hasReview ? 1 : 0).compareTo(a.hasReview ? 1 : 0);
      if (c != 0) return c;
      c = b.rating.compareTo(a.rating);
      if (c != 0) return c;
      return b.updatedAt.compareTo(a.updatedAt);
    });
  for (final row in ratingRows) {
    seed(row.albumId.rawId).ratingRow = row;
  }

  final notesByAlbum = <String, int>{};
  for (final note in inputs.songNotes) {
    final album = inputs.albumOfNote(note);
    if (note.isBlank || album == null || album.provider != provider) continue;
    notesByAlbum[album.rawId] = (notesByAlbum[album.rawId] ?? 0) + 1;
  }
  final notedAlbums = notesByAlbum.entries.toList()..sort((a, b) => b.value.compareTo(a.value));
  for (final e in notedAlbums) {
    seed(e.key);
  }

  for (final plays in inputs.albumPlays.take(scanLimit)) {
    if (plays.albumId.provider != provider) continue;
    final s = seed(plays.albumId.rawId)
      ..plays = plays
      ..albumName ??= _nonBlank(plays.albumName)
      ..artistName ??= _nonBlank(plays.artistName)
      ..coverKey ??= plays.coverArtKey;
    s
      ..firstPlayedAt = _min(s.firstPlayedAt, plays.firstPlayedAt)
      ..lastPlayedAt = _max(s.lastPlayedAt, plays.lastPlayedAt);
  }

  for (final event in inputs.activity.take(scanLimit)) {
    final isAlbum = event.entityType == ActivityEntityType.album;
    final rawId = isAlbum ? event.entityId : event.albumId;
    if (rawId == null || rawId.isEmpty) continue;
    final s = seed(rawId);
    if (isAlbum) {
      s
        ..albumName ??= _nonBlank(event.title)
        ..artistName ??= _nonBlank(event.subtitle)
        ..coverKey ??= event.coverArtKey;
    }
    s
      ..lastPlayedAt = _max(s.lastPlayedAt, event.timestampMs)
      ..firstPlayedAt = _min(s.firstPlayedAt, event.timestampMs);
  }

  final scanned = seeds.values.take(scanLimit).toList();
  // Albums the caller holds on screen are scanned even with no rows left.
  for (final id in alsoScan) {
    if (id.provider == provider && !scanned.any((s) => s.rawId == id.rawId)) scanned.add(seed(id.rawId));
  }
  final built = List<AlbumMemoryCandidate?>.filled(scanned.length, null);
  var next = 0;
  Future<void> worker() async {
    while (next < scanned.length) {
      final i = next++;
      built[i] = await _buildCandidate(scanned[i], inputs, loadAlbum);
    }
  }

  await Future.wait([for (var w = 0; w < maxConcurrent; w++) worker()]);
  final sorted = built.whereType<AlbumMemoryCandidate>().toList();
  _stableSort(sorted, compareAlbumMemoryCandidates);
  if (!includeIneligible) return sorted.where((c) => c.isMemoryEligible).take(limit).toList();
  if (!withTrackSignals) return sorted;
  final extras = trackSignalAlbums(inputs, exclude: {for (final s in scanned) s.rawId});
  if (extras.isEmpty) return sorted;
  final extraSeeds = [
    for (final id in extras)
      () {
        final s = _Seed(id.rawId, inMemoryScan: false);
        final plays = _playsFor(inputs, id);
        if (plays != null) {
          s
            ..plays = plays
            ..albumName = _nonBlank(plays.albumName)
            ..artistName = _nonBlank(plays.artistName)
            ..coverKey = plays.coverArtKey
            ..firstPlayedAt = plays.firstPlayedAt
            ..lastPlayedAt = plays.lastPlayedAt;
        }
        return s;
      }(),
  ];
  final builtExtras = List<AlbumMemoryCandidate?>.filled(extraSeeds.length, null);
  next = 0;
  Future<void> extraWorker() async {
    while (next < extraSeeds.length) {
      final i = next++;
      builtExtras[i] = await _buildCandidate(extraSeeds[i], inputs, loadAlbum);
    }
  }

  await Future.wait([for (var w = 0; w < maxConcurrent; w++) extraWorker()]);
  final extraSorted = builtExtras.whereType<AlbumMemoryCandidate>().toList();
  _stableSort(extraSorted, compareAlbumMemoryCandidates);
  return [...sorted, ...extraSorted];
}

/// Rediscover's extra albums ([buildAlbumMemoryCandidates] includeIneligible,
/// Android buildTrackSignalCandidates): the albums of [inputs]'s noted songs
/// (one signal per non-blank note) and scored songs (one per scored song),
/// placed through the note's own album or [AlbumMemoryInputs.trackAlbums],
/// not in [exclude] (raw ids). Most signals first, then the newest write; at
/// most [limit].
List<MediaId> trackSignalAlbums(
  AlbumMemoryInputs inputs, {
  Set<String> exclude = const {},
  int limit = memoryMaxTrackSignalSeeds,
}) {
  final provider = inputs.provider;
  final signals = <MediaId, ({int count, int last})>{};
  void add(MediaId? album, int at) {
    if (album == null || album.provider != provider || exclude.contains(album.rawId)) return;
    final was = signals[album];
    signals[album] = (count: (was?.count ?? 0) + 1, last: was == null || at > was.last ? at : was.last);
  }

  for (final note in inputs.songNotes) {
    if (!note.isBlank) add(inputs.albumOfNote(note), note.updatedAt);
  }
  for (final rating in inputs.trackRatings.values) {
    if (rating.rating != null) add(inputs.trackAlbums[rating.trackId], rating.updatedAt);
  }
  final ordered = signals.entries.toList();
  _stableSort(ordered, (a, b) {
    final c = b.value.count.compareTo(a.value.count);
    return c != 0 ? c : b.value.last.compareTo(a.value.last);
  });
  return [for (final e in ordered.take(limit)) e.key];
}

Future<AlbumMemoryCandidate?> _buildCandidate(_Seed seed, AlbumMemoryInputs inputs, AlbumLoader loadAlbum) async {
  if (seed.rawId.isEmpty) return null;
  final provider = inputs.provider;
  final albumId = MediaId(provider, seed.rawId);
  Album? album;
  try {
    album = await loadAlbum(albumId);
  } catch (_) {
    album = null;
  }
  final tracks = album?.tracks ?? const <Track>[];
  // 0.0 is a score; a cleared rating (null) is not.
  final ratedRows = [
    for (final t in tracks)
      if (inputs.trackRatings[t.id] case final LocalRating r when r.rating != null) r,
  ];
  final rated = [for (final r in ratedRows) r.rating!];
  final totalTracks = tracks.isNotEmpty ? tracks.length : (album?.songCount ?? 0);
  final coverage = totalTracks > 0 ? rated.length / totalTracks : 0.0;
  final average = rated.isEmpty ? null : rated.reduce((a, b) => a + b) / rated.length;

  final ratingRow = seed.ratingRow ?? inputs.albumRatings[albumId];
  final hasReview = ratingRow?.hasReview ?? false;

  final trackIds = {for (final t in tracks) t.id};
  final notes = [
    for (final n in inputs.songNotes)
      if (!n.isBlank && (trackIds.contains(n.trackId) || inputs.albumOfNote(n) == albumId)) n,
  ];
  final lastWrittenAt = [
    if (ratingRow != null && !ratingRow.isEmpty) ratingRow.updatedAt,
    for (final r in ratedRows) r.updatedAt,
    for (final n in notes) n.updatedAt,
  ].fold<int?>(null, _max);

  final albumTrackCount = album == null
      ? null
      : (album.tracks.length > (album.songCount ?? 0) ? album.tracks.length : (album.songCount ?? 0));
  final passesWritingGate = coverage >= memoryRatingCoverageGate || hasReview || notes.length >= memoryNoteCountGate;

  return AlbumMemoryCandidate(
    profileId: inputs.profileId,
    albumId: albumId,
    albumName: album?.name ?? seed.albumName ?? seed.rawId,
    artistName: _nonBlank(album?.artist) ?? seed.artistName,
    totalTracks: totalTracks,
    ratedTrackCount: rated.length,
    ratingCoverage: coverage,
    averageSongRating: average,
    albumRating: (ratingRow?.hasScore ?? false) ? ratingRow!.rating : null,
    hasAlbumReview: hasReview,
    noteCount: notes.length,
    firstPlayedAt: seed.firstPlayedAt,
    lastPlayedAt: seed.lastPlayedAt,
    lastWrittenAt: lastWrittenAt,
    isMemoryEligible: seed.inMemoryScan && passesWritingGate && meetsMemoryTrackCount(albumTrackCount),
    year: album?.year,
    coverArt: album?.coverArt ?? CoverRef.fromStorageKey(seed.coverKey),
    album: album,
    plays: seed.plays ?? _playsFor(inputs, albumId),
  );
}

AlbumPlays? _playsFor(AlbumMemoryInputs inputs, MediaId albumId) {
  for (final p in inputs.albumPlays) {
    if (p.albumId == albumId) return p;
  }
  return null;
}

final class _Seed {
  _Seed(this.rawId, {this.inMemoryScan = true});
  final String rawId;

  /// False for a track-signal album: built for Rediscover, never a Memory.
  final bool inMemoryScan;
  String? albumName;
  String? artistName;
  String? coverKey;
  int? firstPlayedAt;
  int? lastPlayedAt;
  AlbumRatingRow? ratingRow;
  AlbumPlays? plays;
}

/// Insertion sort keeps ties in input order (Kotlin's sortedWith is stable;
/// Dart's List.sort is not guaranteed to be).
void _stableSort<T>(List<T> list, int Function(T, T) compare) {
  for (var i = 1; i < list.length; i++) {
    final item = list[i];
    var j = i - 1;
    while (j >= 0 && compare(list[j], item) > 0) {
      list[j + 1] = list[j];
      j--;
    }
    list[j + 1] = item;
  }
}

int? _min(int? a, int? b) => a == null ? b : (b == null ? a : (a < b ? a : b));
int? _max(int? a, int? b) => a == null ? b : (b == null ? a : (a > b ? a : b));
String? _nonBlank(String? s) => s == null || s.trim().isEmpty ? null : s;
