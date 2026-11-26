/// The album page's second page: a scrapbook of what the listener left on
/// this album, pasted in track order on dotted paper (Android
/// AlbumScrapbook.kt, D3, owner-approved 2026-10-05). Irregular but stable:
/// every tilt comes from a hash of the album id and the piece's key, never
/// from a random draw, so a page reopens exactly the same.
///
/// Besides what is the listener's own — the review, the track scores, the
/// notes, the plays and the Memory title (theirs, else Yoin's AI title) — a
/// track's Ask / About rows from Now Playing show as a question card (or the
/// About paragraph's first sentence) with its facts as tags. An album holds
/// any number of album notes (owner 2026-11-22): one sticky each under the
/// cover and review, oldest first, then a dashed one to write the next.
/// The album's own About (owner + Carbon 2026-11-25): its credits line beside
/// the cover, where Last Play was, and the AI's paragraph on a tertiary card
/// after the opening (a dashed ✦ circle when the AI knew nothing, the newest
/// question's card when only questions were asked).
///
/// This file is the model, its pure builder and its live loader;
/// album_scrapbook_page.dart draws it.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/about/album_about_store.dart';
import '../data/about/song_about_store.dart';
import '../data/ai/ai_service.dart' show AboutFields;
import '../data/albumabout/album_about_models.dart';
import '../data/albumabout/album_credits.dart';
import '../data/albumabout/yoin_album_about.dart';
import '../data/memory/memory_copy_cache.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../memories/memories_view_model.dart';
import '../playback/local_ratings.dart';
import 'album_scrapbook_editor.dart';
import 'detail_cache.dart';

/// The page's fixed rules (D3 §5).
abstract final class ScrapbookRules {
  /// A track score at or above this can be the album's best.
  static const double bestFrom = 9.0;

  /// Notes shown on a clipping before "+N notes".
  static const int notesShown = 3;

  /// About facts shown as tags under a card before the rest go to the sheet.
  static const int tagsShown = 3;

  /// Rating-only tracks per small-ticket row.
  static const int contactSheetSize = 2;

  /// Weighted characters (CJK counts 2) a question card shows on a phone / a wider lane.
  static const int answerBudget = 130;
  static const int answerBudgetWide = 160;

  /// Answers show at most this many whole sentences on the card, never an ellipsis.
  static const int answerSentences = 2;

  /// Weighted characters (CJK counts 2) up to which the review sits beside the phone cover.
  static const int openingBesideBudget = 160;

  // Tilt sets in degrees; no 0 (a 0° piece reads as an alignment slip).
  static const paperTilts = [-1.5, -1.0, -0.5, 0.5, 1.0, 1.5];
  static const cardTilts = [-1.0, -0.6, 0.6, 1.0];
  static const ticketTilts = [-2.0, -1.25, 1.25, 2.0];
  static const stickerTilts = [-12.0, -8.0, -5.0, 5.0, 8.0, 12.0];
  static const coverTilts = [-3.0, 3.0];
  static const tagTilts = [-3.0, -2.0, 2.0, 3.0];
}

/// A question asked about a track (in Now Playing or on its sheet), or the
/// listener's own entry ([own]) (Android AlbumScrapbookAsk).
@immutable
final class ScrapAsk {
  const ScrapAsk({required this.question, this.title, required this.answer, this.ref, this.own = false});

  /// The listener's words: the question asked, or the heading of their own entry.
  final String question;

  /// The AI's short heading for the answer; null on the listener's own entries.
  final String? title;
  final String answer;
  final AboutRef? ref;
  final bool own;

  @override
  bool operator ==(Object other) =>
      other is ScrapAsk &&
      other.question == question &&
      other.title == title &&
      other.answer == answer &&
      other.ref == ref &&
      other.own == own;

  @override
  int get hashCode => Object.hash(question, title, answer, ref, own);
}

/// One About field of a track (Android AlbumScrapbookFact): [key] is one of [AboutFields].
@immutable
final class ScrapFact {
  const ScrapFact({required this.key, required this.value, this.ref});

  final String key;
  final String value;
  final AboutRef? ref;

  @override
  bool operator ==(Object other) => other is ScrapFact && other.key == key && other.value == value && other.ref == ref;

  @override
  int get hashCode => Object.hash(key, value, ref);
}

/// A track's Ask / About: questions newest first, facts in [AboutFields.order].
@immutable
final class ScrapAbout {
  const ScrapAbout({this.asks = const [], this.facts = const []});

  final List<ScrapAsk> asks;
  final List<ScrapFact> facts;

  bool get isEmpty => asks.isEmpty && facts.isEmpty;

  @override
  bool operator ==(Object other) =>
      other is ScrapAbout && listEquals(other.asks, asks) && listEquals(other.facts, facts);

  @override
  int get hashCode => Object.hash(Object.hashAll(asks), Object.hashAll(facts));
}

/// A fact as a tag (its field key, value and tilt).
@immutable
final class ScrapTag {
  const ScrapTag({required this.key, required this.value, required this.tilt, this.ref});

  final String key;
  final String value;
  final double tilt;
  final AboutRef? ref;

  /// The long About paragraph (the "review" field).
  bool get isParagraph => key == AboutFields.review;

  @override
  bool operator ==(Object other) =>
      other is ScrapTag && other.key == key && other.value == value && other.tilt == tilt && other.ref == ref;

  @override
  int get hashCode => Object.hash(key, value, tilt, ref);
}

/// Everything Ask / About holds for one track (the track sheet's About tab).
@immutable
final class ScrapAboutSheet {
  const ScrapAboutSheet({required this.asks, required this.facts});

  final List<ScrapAsk> asks;
  final List<ScrapTag> facts;

  @override
  bool operator ==(Object other) =>
      other is ScrapAboutSheet && listEquals(other.asks, asks) && listEquals(other.facts, facts);

  @override
  int get hashCode => Object.hash(Object.hashAll(asks), Object.hashAll(facts));
}

/// About rows (every row under the album's key) matched to [tracks] by
/// normalised title + artist; a row whose artist matches no track falls back
/// to the one track with that title. Rows of same-named albums by other
/// artists match nothing and are dropped (matchAboutToTracks).
Map<MediaId, ScrapAbout> matchAboutToTracks(List<SongAboutEntry> rows, List<Track> tracks) {
  if (rows.isEmpty || tracks.isEmpty) return const {};
  final byTitleArtist = <(String, String), MediaId>{};
  final byTitle = <String, Set<MediaId>>{};
  for (final t in tracks) {
    final title = normalizeAboutKey(t.title ?? '');
    byTitleArtist.putIfAbsent((title, normalizeAboutKey(t.artist ?? '')), () => t.id);
    (byTitle[title] ??= <MediaId>{}).add(t.id);
  }
  final grouped = <MediaId, List<SongAboutEntry>>{};
  for (final row in rows) {
    final candidates = byTitle[row.titleKey];
    final id = byTitleArtist[(row.titleKey, row.artistKey)] ?? (candidates?.length == 1 ? candidates!.first : null);
    if (id != null) (grouped[id] ??= []).add(row);
  }
  return {for (final e in grouped.entries) e.key: _toAbout(e.value)};
}

ScrapAbout _toAbout(List<SongAboutEntry> rows) {
  final asks =
      [
        for (final r in rows)
          if (!r.isCanonical && r.answerText.trim().isNotEmpty) r,
      ]..sort((a, b) {
        // By when it was first asked: an answer edited (or restored by Undo) keeps its place.
        final c = b.createdAt.compareTo(a.createdAt);
        return c != 0 ? c : b.updatedAt.compareTo(a.updatedAt);
      });
  final canonical = {
    for (final r in rows)
      if (r.isCanonical && r.answerText.trim().isNotEmpty) r.entryKey: r,
  };
  return ScrapAbout(
    asks: [
      for (final r in asks)
        r.isOwn
            ? ScrapAsk(
                question: (r.titleText?.trim().isNotEmpty ?? false) ? r.titleText! : r.entryKey,
                answer: r.answerText.trim(),
                ref: r.ref,
                own: true,
              )
            : ScrapAsk(
                question: (r.promptText?.trim().isNotEmpty ?? false) ? r.promptText! : r.entryKey,
                title: (r.titleText?.trim().isNotEmpty ?? false) ? r.titleText : null,
                answer: r.answerText.trim(),
                ref: r.ref,
              ),
    ],
    facts: [
      for (final key in AboutFields.order)
        if (canonical[key] case final row?) ScrapFact(key: key, value: row.answerText.trim(), ref: row.ref),
    ],
  );
}

/// The excerpt a card shows and whether anything was left for the sheet.
@immutable
final class AnswerExcerpt {
  const AnswerExcerpt(this.text, {required this.truncated});
  final String text;
  final bool truncated;
}

final RegExp _sentence = RegExp(r'[^。！？.!?]+[。！？.!?]+["”’）)]*|[^。！？.!?]+$');

/// The sentences of [text], their closing punctuation kept (splitSentences).
List<String> splitSentences(String text) => [
  for (final m in _sentence.allMatches(text))
    if (m.group(0)!.trim().isNotEmpty) m.group(0)!,
];

/// Whole sentences of [answer], never an ellipsis: the first always, then
/// more while they fit [budget] weighted characters, up to [maxSentences] (answerExcerpt).
AnswerExcerpt answerExcerpt(String answer, int budget, {int maxSentences = ScrapbookRules.answerSentences}) {
  final sentences = splitSentences(answer.trim());
  if (sentences.isEmpty) return const AnswerExcerpt('', truncated: false);
  final shown = StringBuffer();
  var weight = 0;
  var count = 0;
  for (final sentence in sentences) {
    final w = weightedLength(sentence);
    if (count < maxSentences && (count == 0 || weight + w <= budget)) {
      shown.write(sentence);
      weight += w;
      count++;
    } else {
      break;
    }
  }
  return AnswerExcerpt(shown.toString().trim(), truncated: count < sentences.length);
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

/// One album note on the page: its id, its words (stored note markup) and its lean.
@immutable
final class ScrapSticky {
  const ScrapSticky({required this.noteId, required this.text, required this.tilt});

  final String noteId;
  final String text;
  final double tilt;

  @override
  bool operator ==(Object other) =>
      other is ScrapSticky && other.noteId == noteId && other.text == text && other.tilt == tilt;

  @override
  int get hashCode => Object.hash(noteId, text, tilt);
}

/// Cover (with the score emblem hanging off its corner) beside the review,
/// or a blank pen to write one; the user's Memory title and the album's
/// credits line beside the cover; the album's notes as stickies pressed under them.
final class ScrapOpening extends ScrapPiece {
  const ScrapOpening({
    required this.review,
    required this.coverTilt,
    required this.reviewTilt,
    this.title,
    this.reviewAt,
    this.stickies = const [],
    this.credits = const [],
  });

  final String? review;
  final double coverTilt;
  final double reviewTilt;

  /// The user's own Memory title; null when they gave none.
  final String? title;

  /// When the review was written (epoch ms); null without a review.
  final int? reviewAt;

  /// Every album note, oldest first: the first leans against the review, the rest alternate.
  final List<ScrapSticky> stickies;

  /// The credits line under the title beside the cover (owner + Carbon 2026-11-25; it took Last Play's
  /// place there): the known items only, in order ([YoinAlbumAbout.creditItems]); empty = no line.
  final List<AlbumCreditItem> credits;

  @override
  String get key => 'opening';
  @override
  bool get fullLine => true;
}

/// The album's About (owner + Carbon 2026-11-25): the AI's editorial paragraph on a tertiary card like
/// the Ask cards, right after the opening and before the tracks, a few lines on the paper; a tap opens the
/// whole text, Regenerate and Ask. [text] is null while the AI writes it for the first time ([pending]),
/// and when it was asked and knew nothing (not [pending]): then the paper shows the newest question about
/// the album ([asks]) as its Ask card, or with none only a dashed ✦ circle.
final class ScrapAlbumAbout extends ScrapPiece {
  const ScrapAlbumAbout({required this.text, required this.pending, required this.tilt, this.asks = const []});

  final String? text;
  final bool pending;
  final double tilt;

  /// Questions asked about the album, newest first (the sheet lists them).
  final List<AlbumAboutAsk> asks;

  @override
  String get key => 'albumabout';
  @override
  bool get fullLine => true;

  @override
  bool operator ==(Object other) =>
      other is ScrapAlbumAbout &&
      other.text == text &&
      other.pending == pending &&
      other.tilt == tilt &&
      listEquals(other.asks, asks);

  @override
  int get hashCode => Object.hash(text, pending, tilt, Object.hashAll(asks));
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
    this.ask,
    this.moreAsks = 0,
    this.aboutLine,
    this.cardTilt = 0,
    this.tags = const [],
    this.hiddenTags = 0,
    this.sheet,
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

  /// The newest question (or own entry); [moreAsks] past it.
  final ScrapAsk? ask;
  final int moreAsks;

  /// The About paragraph's first sentence, only when the track has no question.
  final String? aboutLine;
  final double cardTilt;

  /// The short facts as tags (the first [ScrapbookRules.tagsShown]); [hiddenTags] past them.
  final List<ScrapTag> tags;
  final int hiddenTags;

  /// Everything Ask / About holds for the track (its sheet); null with none.
  final ScrapAboutSheet? sheet;

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
    this.aiTitle,
    this.starred = const {},
    this.about = const {},
    this.albumNotes = const [],
    this.albumAbout = AlbumAboutContent.none,
    this.albumAboutPending = false,
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

  /// Yoin's AI Memory title, only when Memories already wrote it (never generated from here).
  final String? aiTitle;
  final Set<MediaId> starred;

  /// Ask / About rows matched to the tracks.
  final Map<MediaId, ScrapAbout> about;

  /// The album's own notes, any order (the builder sorts them oldest first).
  final List<AlbumNote> albumNotes;

  /// The album's own About as stored: the AI's credits and paragraph, the questions asked about it.
  final AlbumAboutContent albumAbout;

  /// The AI is writing the album's About right now (first time, or Regenerate).
  final bool albumAboutPending;
}

String? _written(String? text) {
  final t = text?.trim();
  return t == null || t.isEmpty ? null : t;
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

  bool touched(ScrapTrack t) => t.score != null || notesOf(t).isNotEmpty || !(data.about[t.track.id]?.isEmpty ?? true);

  final pieces = <ScrapPiece>[];
  final review = data.review?.trim();
  final hasReview = review != null && review.isNotEmpty;
  final reviewTilt = tilt(hasReview ? 'review' : 'ghost', ScrapbookRules.cardTilts);
  // The first sticky leans against the review clipping, each next one against the one above.
  final albumNotes = [...data.albumNotes.where((n) => !n.isBlank)]..sort((a, b) => a.createdAt.compareTo(b.createdAt));
  double? previousStickyTilt;
  final stickies = [
    for (final (i, note) in albumNotes.indexed)
      ScrapSticky(
        noteId: note.id,
        text: note.content.trim(),
        tilt: previousStickyTilt = i == 0
            ? oppositeTilt(reviewTilt, tilt('albumnote', ScrapbookRules.paperTilts))
            : oppositeTilt(previousStickyTilt, tilt('albumnote/${note.id}', ScrapbookRules.paperTilts)),
      ),
  ];
  pieces.add(
    ScrapOpening(
      review: hasReview ? review : null,
      coverTilt: tilt('cover', ScrapbookRules.coverTilts),
      reviewTilt: reviewTilt,
      // The user's own name, else Yoin's AI title (scrapTitle).
      title: _written(data.title) ?? _written(data.aiTitle),
      reviewAt: hasReview ? data.reviewAt : null,
      stickies: stickies,
      credits: YoinAlbumAbout.creditItems(
        sourceLabels: album.recordLabels,
        releaseDate: album.releaseDate,
        originalReleaseDate: album.originalReleaseDate,
        ai: data.albumAbout.credits,
      ),
    ),
  );
  // Asked before and nothing came back (the AI knew nothing): the piece stays as a quiet way back in (a
  // dashed circle, like the blank review), to generate again or ask. Questions about the album keep the
  // piece too: with no paragraph, the newest is its Ask card.
  final albumAbout = data.albumAbout;
  if (albumAbout.blurb != null || data.albumAboutPending || albumAbout.generated || albumAbout.asks.isNotEmpty) {
    pieces.add(
      ScrapAlbumAbout(
        text: albumAbout.blurb,
        pending: data.albumAboutPending && albumAbout.blurb == null,
        tilt: tilt('albumabout', ScrapbookRules.cardTilts),
        asks: albumAbout.asks,
      ),
    );
  }

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
    final about = data.about[t.track.id];
    final asks = about?.asks ?? const <ScrapAsk>[];
    final facts = about?.facts ?? const <ScrapFact>[];
    final isBest = t.key == best;
    if (notes.isEmpty && asks.isEmpty && facts.isEmpty && !isBest) {
      run.add(t);
      continue;
    }
    flushRun();
    final ticketTilt = tilt('${t.number}/tk', ScrapbookRules.ticketTilts);
    var previous = ticketTilt;
    final notesTilt = notes.isEmpty
        ? 0.0
        : previous = oppositeTilt(previous, tilt('${t.number}/nt', ScrapbookRules.paperTilts));
    final paragraph = facts.where((f) => f.key == AboutFields.review).firstOrNull?.value;
    final aboutLine = asks.isEmpty && paragraph != null
        ? answerExcerpt(paragraph, ScrapbookRules.answerBudget, maxSentences: 1).text
        : null;
    final cardTilt = asks.isNotEmpty || aboutLine != null
        ? oppositeTilt(previous, tilt('${t.number}/qa', ScrapbookRules.cardTilts))
        : 0.0;
    ScrapTag tagOf(int i, ScrapFact f) =>
        ScrapTag(key: f.key, value: f.value, tilt: tilt('${t.number}/fact$i', ScrapbookRules.tagTilts), ref: f.ref);
    final allTags = [for (final (i, f) in facts.where((f) => f.key != AboutFields.review).indexed) tagOf(i, f)];
    pieces.add(
      ScrapTrackCluster(
        track: t,
        mirror: mirrorNow(),
        best: isBest,
        ticketTilt: ticketTilt,
        notes: notes,
        hiddenNotes: math.max(0, notes.length - ScrapbookRules.notesShown),
        notesTilt: notesTilt,
        ask: asks.firstOrNull,
        moreAsks: math.max(0, asks.length - 1),
        aboutLine: aboutLine,
        cardTilt: cardTilt,
        tags: allTags.take(ScrapbookRules.tagsShown).toList(),
        hiddenTags: math.max(0, allTags.length - ScrapbookRules.tagsShown),
        sheet: asks.isEmpty && facts.isEmpty
            ? null
            : ScrapAboutSheet(asks: asks, facts: [for (final (i, f) in facts.indexed) tagOf(i, f)]),
      ),
    );
    clusterIndex++;
  }
  flushRun();

  // Only an album with nothing on it at all gets the "Nothing yet" line: once anything is left (a review,
  // a title, a score, a note, a play), page 2 shows just what was left (owner 2026-10-05: "Not yet" read as
  // unexplained; the receipt already says "Rated 0 / N"). No heading, no sticker: one muted line.
  final played = (data.plays?.playCount ?? 0) > 0;
  if (tracks.isNotEmpty &&
      !tracks.any(touched) &&
      !hasReview &&
      stickies.isEmpty &&
      _written(data.title) == null &&
      _written(data.aiTitle) == null &&
      !played) {
    pieces.add(const ScrapNotYet());
  }

  final plays = data.plays;
  if (plays != null && plays.playCount > 0) {
    final ids = {for (final t in tracks) t.track.id};
    final rated = data.ratings.keys.where(ids.contains).length;
    // Every album note counts with the track notes (D3's receipt).
    final notes = [for (final id in ids) ...?data.notes[id]].where((n) => !n.isBlank).length + stickies.length;
    pieces.add(
      ScrapReceipt(
        header: '${album.name} — ${album.artist ?? ''}'.toUpperCase(),
        lines: [
          ('Plays in Yoin', '${plays.playCount}'),
          ('Days since first play', '${scrapDaysBetween(plays.firstPlayedAt, nowMs)}'),
          ('Last played', scrapRelativeDay(plays.lastPlayedAt, nowMs)),
          ('Rated', '$rated / ${tracks.length}'),
          ('Notes', '$notes'),
          // Questions about the songs and about the album itself (its About sheet's Ask) alike.
          (
            'Asked',
            '${[for (final id in ids) ...?data.about[id]?.asks].where((a) => !a.own).length + albumAbout.asks.length}',
          ),
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
  StreamSubscription<void>? _aboutChanges;
  StreamSubscription<void>? _albumAboutChanges;
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
    // A question, an answer or an edit from Now Playing or the track sheet.
    _aboutChanges?.cancel();
    _aboutChanges = _aboutStore()?.changes.listen(
      (_) => _load(
        ref.read(memoryStoreProvider).value,
        ref.read(localRatingsProvider),
        ref.read(favoriteOverridesProvider),
      ),
    );
    // The album's own About: written here, regenerated, asked about, or synced in.
    _albumAboutChanges?.cancel();
    _albumAboutChanges = _albumAboutStore()?.changes.listen(
      (_) => _load(
        ref.read(memoryStoreProvider).value,
        ref.read(localRatingsProvider),
        ref.read(favoriteOverridesProvider),
      ),
    );
    // The AI starts or stops writing the album's About (the card's dots).
    ref.listen(
      albumScrapbookEditorProvider(albumId).select((g) => g.albumAbout),
      (_, _) => _load(
        ref.read(memoryStoreProvider).value,
        ref.read(localRatingsProvider),
        ref.read(favoriteOverridesProvider),
      ),
    );
    // Cloud sync brought Yoin's AI title from another device.
    ref.listen(
      memoryCopySyncRevisionProvider,
      (_, _) => _load(
        ref.read(memoryStoreProvider).value,
        ref.read(localRatingsProvider),
        ref.read(favoriteOverridesProvider),
      ),
    );
    ref.onDispose(() {
      _changes?.cancel();
      _aboutChanges?.cancel();
      _albumAboutChanges?.cancel();
    });
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
      final albumNotes = await store?.albumNotes(albumId) ?? const <AlbumNote>[];
      final aboutRows = await _aboutStore()?.byAlbum(normalizeAboutKey(album.name)) ?? const <SongAboutEntry>[];
      final albumAboutRows =
          await _albumAboutStore()?.forAlbum(albumId.provider, albumId.rawId) ?? const <AlbumAboutEntry>[];
      final aiTitle = store == null ? null : await _aiTitle(store.profileId);
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
        aiTitle: aiTitle,
        about: matchAboutToTracks(aboutRows, album.tracks),
        albumNotes: albumNotes,
        albumAbout: YoinAlbumAbout.content([for (final r in albumAboutRows) r.row]),
        albumAboutPending: ref.read(albumScrapbookEditorProvider(albumId)).albumAbout,
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

  /// The About table; null where the app's storage isn't there (previews).
  SongAboutStore? _aboutStore() {
    try {
      return SongAboutStore.shared(ref.read(keyValueStoreProvider));
    } catch (_) {
      return null;
    }
  }

  /// The album About table; null where the app's storage isn't there (previews).
  AlbumAboutStore? _albumAboutStore() {
    try {
      return AlbumAboutStore.shared(ref.read(keyValueStoreProvider));
    } catch (_) {
      return null;
    }
  }

  /// Yoin's AI Memory title as Memories last wrote it (never generated here).
  Future<String?> _aiTitle(String profileId) async {
    try {
      return await MemoryCopyCache.shared(ref.read(keyValueStoreProvider), profileId).title(albumId);
    } catch (_) {
      return null;
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
