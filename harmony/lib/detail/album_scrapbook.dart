/// The album page's second page: a scrapbook of what the listener left on
/// this album, pasted in track order on dotted paper (Android
/// AlbumScrapbook.kt, D3, owner-approved 2026-10-05). Irregular but stable:
/// every tilt comes from a hash of the album id and the piece's key, never
/// from a random draw, so a page reopens exactly the same.
///
/// HarmonyOS keeps what is the listener's own — the review, the track scores,
/// the notes, the plays and the user's Memory title. The Ask / About cards,
/// their fact tags and the AI Memory title are Gemini's and are not built;
/// nor is the album-note sticky (nothing writes album notes any more).
///
/// This file is the model, its pure builder and its live loader;
/// album_scrapbook_page.dart draws it.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../memories/memories_view_model.dart';
import '../playback/local_ratings.dart';
import 'detail_cache.dart';

/// The page's fixed rules (D3 §5).
abstract final class ScrapbookRules {
  /// A track score at or above this can be the album's best.
  static const double bestFrom = 9.0;

  /// Notes shown on a clipping before "+N notes".
  static const int notesShown = 3;

  /// Rating-only tracks per small-ticket row.
  static const int contactSheetSize = 2;

  /// Weighted characters (CJK counts 2) up to which the review sits beside the phone cover.
  static const int openingBesideBudget = 160;

  // Tilt sets in degrees; no 0 (a 0° piece reads as an alignment slip).
  static const paperTilts = [-1.5, -1.0, -0.5, 0.5, 1.0, 1.5];
  static const cardTilts = [-1.0, -0.6, 0.6, 1.0];
  static const ticketTilts = [-2.0, -1.25, 1.25, 2.0];
  static const stickerTilts = [-12.0, -8.0, -5.0, 5.0, 8.0, 12.0];
  static const coverTilts = [-3.0, 3.0];
}

/// One track as the page shows it.
@immutable
final class ScrapTrack {
  const ScrapTrack({
    required this.track,
    required this.key,
    required this.index,
    required this.number,
    required this.plays,
    required this.score,
    required this.starred,
    required this.heartTilt,
  });

  final Track track;

  /// Unique within the page (a provider can list one track twice).
  final String key;

  /// Album order, for playing the album from here.
  final int index;
  final int number;
  final int plays;

  /// 0.0–10.0 (0.0 is a score); null = not rated.
  final double? score;
  final bool starred;
  final double heartTilt;

  String get title => track.title ?? '';

  @override
  bool operator ==(Object other) =>
      other is ScrapTrack &&
      other.track.id == track.id &&
      other.key == key &&
      other.number == number &&
      other.plays == plays &&
      other.score == score &&
      other.starred == starred &&
      other.heartTilt == heartTilt;

  @override
  int get hashCode => Object.hash(track.id, key, number, plays, score, starred, heartTilt);
}

/// One masonry item. [fullLine] items span every lane; the rest go to the shortest lane.
@immutable
sealed class ScrapPiece {
  const ScrapPiece();

  String get key;
  bool get fullLine;
}

/// Cover (with the score emblem hanging off its corner) beside the review,
/// or a blank pen to write one; the user's Memory title and Last Play beside the cover.
final class ScrapOpening extends ScrapPiece {
  const ScrapOpening({
    required this.review,
    required this.coverTilt,
    required this.reviewTilt,
    this.title,
    this.reviewAt,
  });

  final String? review;
  final double coverTilt;
  final double reviewTilt;

  /// The user's own Memory title; null when they gave none.
  final String? title;

  /// When the review was written (epoch ms); null without a review.
  final int? reviewAt;

  @override
  String get key => 'opening';
  @override
  bool get fullLine => true;
}

/// A track the listener left notes on, or the album's best score: its
/// ticket and the notes clipping.
final class ScrapTrackCluster extends ScrapPiece {
  const ScrapTrackCluster({
    required this.track,
    required this.mirror,
    required this.best,
    required this.ticketTilt,
    required this.notes,
    required this.hiddenNotes,
    required this.notesTilt,
  });

  final ScrapTrack track;

  /// Which side the ticket sits on; the next piece takes the other side.
  final bool mirror;

  /// The album's best score (≥ [ScrapbookRules.bestFrom]), at most one per album.
  final bool best;
  final double ticketTilt;

  /// Every note, timeline order; the clipping shows the first [ScrapbookRules.notesShown].
  final List<SongNote> notes;
  final int hiddenNotes;
  final double notesTilt;

  @override
  String get key => 'track:${track.key}';
  @override
  bool get fullLine => false;
}

/// One or two tracks that only carry a score, side by side as small tickets.
final class ScrapContactSheet extends ScrapPiece {
  const ScrapContactSheet({required this.tracks, required this.tilts, required this.mirror});

  final List<ScrapTrack> tracks;
  final List<double> tilts;
  final bool mirror;

  @override
  String get key => 'sheet:${tracks.first.key}';
  @override
  bool get fullLine => false;
}

/// Nothing on any track yet: one quiet line.
final class ScrapNotYet extends ScrapPiece {
  const ScrapNotYet();

  @override
  String get key => 'notyet';
  @override
  bool get fullLine => true;
}

/// Plays in Yoin, only for an album played here at least once. The receipt
/// is English on every locale (Android keeps its glyphs untranslatable).
final class ScrapReceipt extends ScrapPiece {
  const ScrapReceipt({required this.header, required this.lines, required this.tilt});

  final String header;
  final List<(String, String)> lines;
  final double tilt;

  @override
  String get key => 'receipt';
  @override
  bool get fullLine => true;
}

/// Everything the page is built from, for one album and one account.
@immutable
final class AlbumScrapbookData {
  const AlbumScrapbookData({
    this.review,
    this.reviewAt,
    this.ratings = const {},
    this.notes = const {},
    this.trackPlays = const {},
    this.plays,
    this.title,
    this.starred = const {},
  });

  final String? review;
  final int? reviewAt;

  /// Track scores (0.0 is a score).
  final Map<MediaId, double> ratings;

  /// Notes by track, any order (the builder sorts them).
  final Map<MediaId, List<SongNote>> notes;
  final Map<MediaId, int> trackPlays;
  final AlbumPlays? plays;

  /// The user's own Memory title.
  final String? title;
  final Set<MediaId> starred;
}

/// FNV-1a over UTF-16 units, as an unsigned 32-bit value (Android scrapHash).
int scrapHash(String text) {
  var h = 0x811C9DC5;
  for (final c in text.codeUnits) {
    h ^= c;
    h = (h * 16777619) & 0xFFFFFFFF;
  }
  return h;
}

/// Neighbours lean opposite ways: [angle] flipped when it leans the same way as [previous].
double oppositeTilt(double? previous, double angle) => previous != null && previous.sign == angle.sign ? -angle : angle;

/// One-decimal score text with the Memories rounding (9.95 → "10.0").
String scrapScoreText(double score) {
  final tenths = (score * 10 + 0.5).floor();
  return '${tenths ~/ 10}.${tenths % 10}';
}

/// Characters with CJK / full-width counting 2 and markdown bold markers counting 0.
int weightedLength(String text) => text
    .replaceAll('**', '')
    .codeUnits
    .fold(0, (sum, c) => sum + ((c >= 0x3000 && c <= 0x9FFF) || (c >= 0xFF00 && c <= 0xFFEF) ? 2 : 1));

/// Whole calendar days from [fromMs] to [nowMs], local time (0 on the same day).
int scrapDaysBetween(int fromMs, int nowMs) {
  final a = DateTime.fromMillisecondsSinceEpoch(fromMs);
  final b = DateTime.fromMillisecondsSinceEpoch(nowMs);
  final days = DateTime.utc(b.year, b.month, b.day).difference(DateTime.utc(a.year, a.month, a.day)).inDays;
  return math.max(0, days);
}

const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/// "Today" / "Yesterday" / "3 days ago" / "Mar 14" / "Mar 14, 2025" — the receipt's English.
String scrapRelativeDay(int ms, int nowMs) {
  final days = scrapDaysBetween(ms, nowMs);
  final date = DateTime.fromMillisecondsSinceEpoch(ms);
  final today = DateTime.fromMillisecondsSinceEpoch(nowMs);
  if (days <= 0) return 'Today';
  if (days == 1) return 'Yesterday';
  if (days < 7) return '$days days ago';
  final md = '${_months[date.month - 1]} ${date.day}';
  return date.year != today.year ? '$md, ${date.year}' : md;
}

/// Builds the scrapbook of [album] from [data] (pure: the same inputs always
/// give the same page). [nowMs] only feeds the receipt's "days since" and "last played".
List<ScrapPiece> buildAlbumScrapbook(Album album, AlbumScrapbookData data, {required int nowMs}) {
  final seed = album.id.rawId;
  double tilt(String key, List<double> set) => set[scrapHash('$seed/$key') % set.length];

  final tracks = [
    for (final (i, t) in album.tracks.indexed)
      ScrapTrack(
        track: t,
        key: '${t.id}#$i',
        index: i,
        number: t.trackNumber ?? i + 1,
        plays: data.trackPlays[t.id] ?? 0,
        score: data.ratings[t.id],
        starred: data.starred.contains(t.id),
        heartTilt: tilt('${t.trackNumber ?? i + 1}/heart', ScrapbookRules.stickerTilts),
      ),
  ];
  List<SongNote> notesOf(ScrapTrack t) {
    final list = [...?data.notes[t.track.id]]
      ..removeWhere((n) => n.isBlank)
      ..sort((a, b) {
        final pa = a.positionMs, pb = b.positionMs;
        if (pa != null && pb != null && pa != pb) return pa.compareTo(pb);
        if ((pa == null) != (pb == null)) return pa == null ? 1 : -1;
        return a.createdAt.compareTo(b.createdAt);
      });
    return list;
  }

  bool touched(ScrapTrack t) => t.score != null || notesOf(t).isNotEmpty;

  final pieces = <ScrapPiece>[];
  final review = data.review?.trim();
  final hasReview = review != null && review.isNotEmpty;
  pieces.add(
    ScrapOpening(
      review: hasReview ? review : null,
      coverTilt: tilt('cover', ScrapbookRules.coverTilts),
      reviewTilt: tilt(hasReview ? 'review' : 'ghost', ScrapbookRules.cardTilts),
      title: data.title?.trim().isEmpty ?? true ? null : data.title!.trim(),
      reviewAt: hasReview ? data.reviewAt : null,
    ),
  );

  String? best;
  double bestScore = -1;
  for (final t in tracks) {
    final s = t.score;
    if (s != null && s >= ScrapbookRules.bestFrom && s > bestScore) {
      best = t.key;
      bestScore = s;
    }
  }
  final startMirror = (scrapHash(seed) & 1) == 1;
  var clusterIndex = 0;
  final run = <ScrapTrack>[];
  bool mirrorNow() => (clusterIndex % 2 == 1) != startMirror;
  void flushRun() {
    if (run.isEmpty) return;
    for (var i = 0; i < run.length; i += ScrapbookRules.contactSheetSize) {
      final chunk = run.sublist(i, math.min(run.length, i + ScrapbookRules.contactSheetSize));
      double? previous;
      final tilts = [
        for (final t in chunk) previous = oppositeTilt(previous, tilt('${t.number}/tk', ScrapbookRules.ticketTilts)),
      ];
      pieces.add(ScrapContactSheet(tracks: chunk, tilts: tilts, mirror: mirrorNow()));
    }
    clusterIndex++;
    run.clear();
  }

  for (final t in tracks) {
    if (!touched(t)) continue;
    final notes = notesOf(t);
    final isBest = t.key == best;
    if (notes.isEmpty && !isBest) {
      run.add(t);
      continue;
    }
    flushRun();
    final ticketTilt = tilt('${t.number}/tk', ScrapbookRules.ticketTilts);
    pieces.add(
      ScrapTrackCluster(
        track: t,
        mirror: mirrorNow(),
        best: isBest,
        ticketTilt: ticketTilt,
        notes: notes,
        hiddenNotes: math.max(0, notes.length - ScrapbookRules.notesShown),
        notesTilt: notes.isEmpty ? 0 : oppositeTilt(ticketTilt, tilt('${t.number}/nt', ScrapbookRules.paperTilts)),
      ),
    );
    clusterIndex++;
  }
  flushRun();

  // Only an album with nothing on any track gets the "Nothing yet" line.
  if (tracks.isNotEmpty && !tracks.any(touched)) pieces.add(const ScrapNotYet());

  final plays = data.plays;
  if (plays != null && plays.playCount > 0) {
    final ids = {for (final t in tracks) t.track.id};
    final rated = data.ratings.keys.where(ids.contains).length;
    final notes = [for (final id in ids) ...?data.notes[id]].where((n) => !n.isBlank).length;
    pieces.add(
      ScrapReceipt(
        header: '${album.name} — ${album.artist ?? ''}'.toUpperCase(),
        lines: [
          ('Plays in Yoin', '${plays.playCount}'),
          ('Days since first play', '${scrapDaysBetween(plays.firstPlayedAt, nowMs)}'),
          ('Last played', scrapRelativeDay(plays.lastPlayedAt, nowMs)),
          ('Rated', '$rated / ${tracks.length}'),
          ('Notes', '$notes'),
        ],
        tilt: tilt('receipt', ScrapbookRules.paperTilts),
      ),
    );
  }
  return pieces;
}

/// The scrapbook of one album, live: a score, a note, a review or a play
/// written anywhere rebuilds it. Empty until the album and the store are in.
class AlbumScrapbookNotifier extends Notifier<List<ScrapPiece>?> {
  AlbumScrapbookNotifier(this.albumId);

  final MediaId albumId;
  StreamSubscription<MemoryStoreChange>? _changes;
  int _generation = 0;

  @override
  List<ScrapPiece>? build() {
    final store = ref.watch(memoryStoreProvider).value;
    final ratings = ref.watch(localRatingsProvider);
    final starred = ref.watch(favoriteOverridesProvider);
    _changes?.cancel();
    _changes = store?.changes.listen(
      (_) => _load(store, ref.read(localRatingsProvider), ref.read(favoriteOverridesProvider)),
    );
    ref.onDispose(() => _changes?.cancel());
    final previous = stateOrNull;
    scheduleMicrotask(() => _load(store, ratings, starred));
    return previous;
  }

  /// Album detail reloaded (a new track list): rebuild against it.
  void refresh() =>
      _load(ref.read(memoryStoreProvider).value, ref.read(localRatingsProvider), ref.read(favoriteOverridesProvider));

  Future<void> _load(MemoryStore? store, Map<MediaId, LocalRating> ratings, Map<MediaId, bool> starred) async {
    final generation = ++_generation;
    final album = ref.read(detailCacheProvider).cachedAlbum(albumId);
    if (album == null) return;
    try {
      final ids = {for (final t in album.tracks) t.id};
      final row = await store?.albumRating(albumId);
      final notes = await store?.notesForTracks(ids) ?? const <SongNote>[];
      final trackPlays = await store?.trackPlays() ?? const <TrackPlays>[];
      final plays = await store?.playsOf(albumId);
      final title = await store?.titleOf(albumId);
      if (!ref.mounted || generation != _generation) return;
      final data = AlbumScrapbookData(
        review: row?.review,
        reviewAt: row?.reviewUpdatedAt,
        ratings: {for (final id in ids) id: ?ratings[id]?.rating},
        notes: {
          for (final n in notes) n.trackId: [...?_group(notes, n.trackId)],
        },
        trackPlays: {
          for (final p in trackPlays)
            if (ids.contains(p.trackId)) p.trackId: p.playCount,
        },
        plays: plays,
        title: title?.title,
        starred: {
          for (final t in album.tracks)
            if (starred[t.id] ?? t.isStarred) t.id,
        },
      );
      state = buildAlbumScrapbook(album, data, nowMs: ref.read(memoriesClockProvider)());
    } catch (_) {
      // Best-effort: the page keeps what it showed.
    }
  }

  static List<SongNote>? _group(List<SongNote> notes, MediaId id) => [
    for (final n in notes)
      if (n.trackId == id) n,
  ];
}

final albumScrapbookProvider = NotifierProvider.autoDispose.family<AlbumScrapbookNotifier, List<ScrapPiece>?, MediaId>(
  AlbumScrapbookNotifier.new,
);
