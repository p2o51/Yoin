/// Rediscover's picks (Android data/memory/RediscoverSelection.kt): albums
/// you kept something on — a rating, a review, a note, a rated track — that
/// haven't played in Yoin for 90 days, plus albums you wrote about but never
/// played here (note cards). Pure; unit-tested.
///
/// HarmonyOS keeps plays per album only (MemoryStore.albumPlays), so the
/// shelf has no song cards: the album picks and the note cards make it.
library;

import 'package:flutter/foundation.dart';

import '../data/memory/album_memory_candidate.dart';
import '../data/model/model.dart';

/// How long an album must go unplayed in Yoin before it comes back: 90 days.
const int rediscoverAwayMs = 90 * 24 * 60 * 60 * 1000;

/// Track-average coverage gate; same value as the builder's Memory gate.
const double rediscoverCoverageGate = 0.6;

/// Compact shelf maximum; Medium / Wide show fewer.
const int rediscoverLimit = 6;

/// About a third of the shelf goes to note cards (2 of 6, at least 1 when there is one).
const double rediscoverNoteShare = 1 / 3;
const int _noteEvery = 3;

/// The badge's grammar: your own album score is the solid sticker, a covered
/// track average the outline.
enum RediscoverScoreKind { none, album, average }

/// One Rediscover card (Android HomeRediscoverItem, album cards only).
@immutable
final class HomeRediscoverItem {
  const HomeRediscoverItem({
    required this.albumId,
    required this.albumName,
    this.artistName,
    this.cover,
    this.score,
    this.scoreKind = RediscoverScoreKind.none,
    required this.lastPlayedAt,
    this.firstPlayedAt,
    this.playCount = 0,
    this.hasReview = false,
    this.noteCount = 0,
    this.ratedTrackCount = 0,
    this.fromNotes = false,
    this.album,
  });

  final MediaId albumId;
  final String albumName;
  final String? artistName;
  final CoverRef? cover;

  /// Album rating, else a covered track average; null = no badge.
  final double? score;
  final RediscoverScoreKind scoreKind;

  /// Newest play in Yoin; 0 on a note card (it never played here).
  final int lastPlayedAt;
  final int? firstPlayedAt;
  final int playCount;
  final bool hasReview;
  final int noteCount;
  final int ratedTrackCount;

  /// A note card: back for what was kept, not for time away.
  final bool fromNotes;

  /// The loaded album (the detail page's preview), when the build had it.
  final Album? album;

  @override
  bool operator ==(Object other) =>
      other is HomeRediscoverItem &&
      other.albumId == albumId &&
      other.albumName == albumName &&
      other.artistName == artistName &&
      other.cover == cover &&
      other.score == score &&
      other.scoreKind == scoreKind &&
      other.lastPlayedAt == lastPlayedAt &&
      other.firstPlayedAt == firstPlayedAt &&
      other.playCount == playCount &&
      other.hasReview == hasReview &&
      other.noteCount == noteCount &&
      other.ratedTrackCount == ratedTrackCount &&
      other.fromNotes == fromNotes;

  @override
  int get hashCode => Object.hash(albumId, albumName, artistName, cover, score, scoreKind, lastPlayedAt, firstPlayedAt,
      playCount, hasReview, noteCount, ratedTrackCount, fromNotes);
}

/// The album's Rediscover score: its album rating when set, otherwise the
/// track average once at least [rediscoverCoverageGate] of tracks are rated.
double? rediscoverScore(AlbumMemoryCandidate c) {
  final album = c.albumRating;
  if (album != null && album > 0) return album;
  final average = c.averageSongRating;
  return average != null && c.ratingCoverage >= rediscoverCoverageGate ? average : null;
}

/// Whether the user ever kept something on this album. Plays and visits never count.
bool hasRediscoverMemory(AlbumMemoryCandidate c) =>
    (c.albumRating ?? 0) > 0 || c.hasAlbumReview || c.noteCount > 0 || c.ratedTrackCount > 0;

/// Singles and short EPs never come back as albums; an unloaded album (0 tracks) stays open.
bool meetsRediscoverTrackCount(AlbumMemoryCandidate c) =>
    c.isMemoryEligible || meetsMemoryTrackCount(c.totalTracks > 0 ? c.totalTracks : null);

/// One pick before it becomes a card.
@immutable
final class RediscoverPick {
  const RediscoverPick({required this.candidate, this.score, required this.lastPlayedAt, this.fromNotes = false});
  final AlbumMemoryCandidate candidate;
  final double? score;
  final int lastPlayedAt;
  final bool fromNotes;

  String get rawAlbumId => candidate.albumId.rawId;
}

/// Scored first, best score first, then the unscored; longest away first within a score.
int _rediscoverOrder(RediscoverPick a, RediscoverPick b) {
  final unscored = (a.score == null ? 1 : 0).compareTo(b.score == null ? 1 : 0);
  if (unscored != 0) return unscored;
  final score = (b.score ?? 0).compareTo(a.score ?? 0);
  if (score != 0) return score;
  return a.lastPlayedAt.compareTo(b.lastPlayedAt);
}

List<T> _stableSorted<T>(List<T> items, int Function(T, T) compare) {
  final indexed = [for (var i = 0; i < items.length; i++) (i, items[i])];
  indexed.sort((a, b) {
    final c = compare(a.$2, b.$2);
    return c != 0 ? c : a.$1.compareTo(b.$1);
  });
  return [for (final e in indexed) e.$2];
}

List<RediscoverPick> _distinctExcluding(List<RediscoverPick> picks, Set<String> exclude) {
  final seen = <String>{};
  return [
    for (final p in picks)
      if (seen.add(p.rawAlbumId) && !exclude.contains(p.rawAlbumId)) p,
  ];
}

/// Every album with a memory and enough tracks that hasn't played in Yoin
/// for [rediscoverAwayMs], best first. Visited-only albums never qualify.
List<RediscoverPick> selectRediscover(
  List<AlbumMemoryCandidate> candidates, {
  required int nowMs,
  Set<String> excludeRawAlbumIds = const {},
  int limit = rediscoverLimit,
}) {
  final picks = <RediscoverPick>[];
  for (final c in candidates) {
    final last = c.plays?.lastPlayedAt;
    if (last == null || !hasRediscoverMemory(c) || !meetsRediscoverTrackCount(c) || nowMs - last < rediscoverAwayMs) continue;
    picks.add(RediscoverPick(candidate: c, score: rediscoverScore(c), lastPlayedAt: last));
  }
  return _distinctExcluding(_stableSorted(picks, _rediscoverOrder), excludeRawAlbumIds).take(limit).toList();
}

/// Note cards: albums with a memory and enough tracks that never played in
/// Yoin, last written more than [rediscoverAwayMs] ago. Scored first, best
/// first, then the most noted.
List<RediscoverPick> selectRediscoverNotes(
  List<AlbumMemoryCandidate> candidates, {
  required int nowMs,
  Set<String> excludeRawAlbumIds = const {},
}) {
  final picks = [
    for (final c in candidates)
      if (c.plays == null &&
          hasRediscoverMemory(c) &&
          meetsRediscoverTrackCount(c) &&
          nowMs - (c.lastWrittenAt ?? 0) >= rediscoverAwayMs)
        RediscoverPick(candidate: c, score: rediscoverScore(c), lastPlayedAt: 0, fromNotes: true),
  ];
  final sorted = _stableSorted(picks, (a, b) {
    final unscored = (a.score == null ? 1 : 0).compareTo(b.score == null ? 1 : 0);
    if (unscored != 0) return unscored;
    final score = (b.score ?? 0).compareTo(a.score ?? 0);
    if (score != 0) return score;
    return b.candidate.noteCount.compareTo(a.candidate.noteCount);
  });
  return _distinctExcluding(sorted, excludeRawAlbumIds);
}

/// [listening] and [notes] in the ratio [rediscoverNoteShare], every third
/// card a note card; either kind fills the other's places when it runs short.
List<RediscoverPick> mixRediscoverShelf(List<RediscoverPick> listening, List<RediscoverPick> notes, {int limit = rediscoverLimit}) {
  if (notes.isEmpty || limit <= 0) return listening.take(limit).toList();
  final noteShare = (limit * rediscoverNoteShare).round().clamp(1, limit);
  final listeningCount = listening.length < limit - (noteShare < notes.length ? noteShare : notes.length)
      ? listening.length
      : limit - (noteShare < notes.length ? noteShare : notes.length);
  final noteCount = notes.length < limit - listeningCount ? notes.length : limit - listeningCount;
  final shelf = <RediscoverPick>[];
  var l = 0;
  var n = 0;
  while (l < listeningCount || n < noteCount) {
    final noteTurn = n < noteCount && (l >= listeningCount || shelf.length % _noteEvery == _noteEvery - 1);
    shelf.add(noteTurn ? notes[n++] : listening[l++]);
  }
  return shelf;
}

/// A pick as a card.
HomeRediscoverItem rediscoverItemOf(RediscoverPick pick) {
  final c = pick.candidate;
  final score = pick.score;
  return HomeRediscoverItem(
    albumId: c.albumId,
    albumName: c.albumName,
    artistName: c.artistName,
    cover: c.coverArt,
    score: score,
    scoreKind: score == null
        ? RediscoverScoreKind.none
        : ((c.albumRating ?? 0) > 0 ? RediscoverScoreKind.album : RediscoverScoreKind.average),
    lastPlayedAt: pick.lastPlayedAt,
    firstPlayedAt: c.plays?.firstPlayedAt,
    playCount: c.plays?.playCount ?? 0,
    hasReview: c.hasAlbumReview,
    noteCount: c.noteCount,
    ratedTrackCount: c.ratedTrackCount,
    fromNotes: pick.fromNotes,
    album: c.album,
  );
}

/// The whole shelf from a candidate pool (Android rediscoverFor, albums only).
List<HomeRediscoverItem> buildRediscover(List<AlbumMemoryCandidate> pool, {required int nowMs, Set<String> excludeRawAlbumIds = const {}}) {
  final listening = selectRediscover(pool, nowMs: nowMs, excludeRawAlbumIds: excludeRawAlbumIds, limit: 1 << 30);
  final notes = selectRediscoverNotes(pool, nowMs: nowMs, excludeRawAlbumIds: excludeRawAlbumIds);
  return [for (final p in mixRediscoverShelf(listening, notes)) rediscoverItemOf(p)];
}

// ---- copy helpers (pure) ----

/// "7 months" / "1 year": whole months (days / 30, at least 1) under a year, whole years from 365 days.
({int count, bool years}) rediscoverAway(int lastPlayedAt, int nowMs) {
  final days = ((nowMs - lastPlayedAt) ~/ (24 * 60 * 60 * 1000)).clamp(0, 1 << 30);
  if (days >= 365) return (count: days ~/ 365, years: true);
  final months = days ~/ 30;
  return (count: months < 1 ? 1 : months, years: false);
}

/// What was kept on a card with no badge to say it: a review, then notes,
/// then rated tracks. A note card always says it.
enum RediscoverReason { reviewed, notes, tracksRated }

RediscoverReason? rediscoverReason(HomeRediscoverItem item) {
  if (item.score != null && !item.fromNotes) return null;
  if (item.hasReview) return RediscoverReason.reviewed;
  if (item.noteCount > 0) return RediscoverReason.notes;
  if (item.ratedTrackCount > 0) return RediscoverReason.tracksRated;
  return null;
}

/// Rediscover's visible count (rediscoverVisibleCount): the landscape
/// handset two, the phone a peeking shelf of six, 3–4 units two, wider three.
int rediscoverVisibleCount(int units, {required bool landscape}) {
  if (landscape) return 2;
  if (units <= 2) return rediscoverLimit;
  if (units <= 4) return 2;
  return rediscoverMaxSideBySide;
}

const int rediscoverMaxSideBySide = 3;

/// The phone's peeking shelf: more cards than ever sit side by side.
bool isRediscoverShelf(int visibleCount) => visibleCount > rediscoverMaxSideBySide;
