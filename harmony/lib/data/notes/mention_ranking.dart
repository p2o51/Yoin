/// The @ popup's rows and how they are ordered for what is typed after the
/// `@` (Android ui/notes/editor/MentionCandidates.kt + MentionRanking.kt, and
/// NoteMarkupField.kt's live filtering). Pure; tested against the golden
/// vectors the Kotlin writes (shared/src/commonTest/resources/golden/mention-ranking.json).
library;

import 'package:flutter/foundation.dart';
import 'package:pinyin/pinyin.dart';

import '../transfer/track_matcher.dart' show foldForMatching;
import 'note_markup.dart';

/// Where a row came from: what is playing and recent plays are listed before
/// search results; [seen] = a row an earlier search offered, kept only while
/// it still matches. Declaration order is the listing order.
enum MentionSource { current, recent, search, seen }

/// One row of the @ popup: what it inserts, and how it's shown.
@immutable
final class MentionCandidate {
  const MentionCandidate({
    required this.mention,
    this.coverUrl,
    this.artist,
    this.album,
    this.source = MentionSource.search,
  });

  final NoteMention mention;
  final String? coverUrl;

  /// The second line's artist (a song's, an album's); null for an artist.
  final String? artist;

  /// The second line's album (a song's), muted after the artist.
  final String? album;
  final MentionSource source;

  MentionCandidate withSource(MentionSource source) =>
      MentionCandidate(mention: mention, coverUrl: coverUrl, artist: artist, album: album, source: source);
}

/// The @ popup's rows, by kind, in the order shown.
@immutable
final class MentionCandidates {
  const MentionCandidates({
    this.tracks = const [],
    this.albums = const [],
    this.artists = const [],
    this.order = defaultOrder,
  });

  static const defaultOrder = [MentionKind.track, MentionKind.album, MentionKind.artist];
  static const empty = MentionCandidates();

  final List<MentionCandidate> tracks;
  final List<MentionCandidate> albums;
  final List<MentionCandidate> artists;

  /// The kinds in the order they're listed (the best match's kind first once something is typed).
  final List<MentionKind> order;

  List<MentionCandidate> of(MentionKind kind) => switch (kind) {
    MentionKind.track => tracks,
    MentionKind.album => albums,
    MentionKind.artist => artists,
  };

  /// Every row in listing order: what ↑ ↓ walk, and the first is what Enter picks.
  List<MentionCandidate> get all => [for (final kind in order) ...of(kind)];

  bool get isEmpty => tracks.isEmpty && albums.isEmpty && artists.isEmpty;

  MentionCandidates copyWith({
    List<MentionCandidate>? tracks,
    List<MentionCandidate>? albums,
    List<MentionCandidate>? artists,
    List<MentionKind>? order,
  }) => MentionCandidates(
    tracks: tracks ?? this.tracks,
    albums: albums ?? this.albums,
    artists: artists ?? this.artists,
    order: order ?? this.order,
  );

  /// These rows and [other]'s, each item once (the first seen wins); the default order.
  MentionCandidates merge(MentionCandidates other) => MentionCandidates(
    tracks: _distinct([...tracks, ...other.tracks]),
    albums: _distinct([...albums, ...other.albums]),
    artists: _distinct([...artists, ...other.artists]),
  );

  static List<MentionCandidate> _distinct(List<MentionCandidate> rows) {
    final seen = <MentionRef>{};
    return [
      for (final row in rows)
        if (seen.add(row.mention.ref)) row,
    ];
  }
}

/// Latin letters for a name written in Chinese characters ("纽约" → "niu yue"),
/// or null when there is nothing to spell (no Han characters) or no way to spell it.
typedef MentionRomanizer = String? Function(String text);

String? noMentionRomanizer(String text) => null;

/// Pinyin from the `pinyin` package (BSD-2-Clause, pure Dart): Android uses
/// the platform's ICU (`Han-Latin; Latin-ASCII`), which Flutter on HarmonyOS
/// can't reach synchronously, and ranking runs on every key. The package
/// reads common words as words (多音字 in context) like ICU does. Its tables
/// are parsed on first use ([warmUp]); answers are cached by name.
abstract final class PinyinMentionRomanizer {
  static final Map<String, String?> _cache = {};
  static const _cacheSize = 512;

  static String? romanize(String text) {
    if (!_hasHan(text)) return null;
    if (_cache.containsKey(text)) return _cache[text];
    String? out;
    try {
      out = PinyinHelper.getPinyin(text, separator: ' ', format: PinyinFormat.WITHOUT_TONE);
    } on Object {
      out = null; // a character the tables don't know: match by its own letters
    }
    if (_cache.length >= _cacheSize) _cache.remove(_cache.keys.first);
    _cache[text] = out;
    return out;
  }

  /// Parses the tables (a one-off cost) before the first `@` needs them.
  static void warmUp() => romanize('纽约');

  static bool _hasHan(String text) {
    for (final rune in text.runes) {
      if (isHan(rune)) return true;
    }
    return false;
  }
}

/// A CJK unified ideograph (Unicode script Han, the blocks names use).
bool isHan(int rune) =>
    (rune >= 0x4E00 && rune <= 0x9FFF) ||
    (rune >= 0x3400 && rune <= 0x4DBF) ||
    (rune >= 0x20000 && rune <= 0x2EBEF) ||
    (rune >= 0xF900 && rune <= 0xFAFF) ||
    (rune >= 0x30000 && rune <= 0x3134F) ||
    rune == 0x3007 ||
    (rune >= 0x2E80 && rune <= 0x2FDF);

/// How well a row matches what is typed, best first.
enum MentionTier { titlePrefix, initials, titleSubstring, artist, none }

/// A name as the words it is matched by: its own (folded) and, for Chinese, its pinyin syllables.
@immutable
final class _Key {
  const _Key(this.folded, this.compact, this.spelled, this.initials);

  final String folded;
  final String compact;
  final String spelled;
  final String initials;
}

/// How the @ popup orders its rows (owner 2026-11-24, "@N puts New York
/// first"): a title that starts with it, then one whose initials do ("ny", or
/// pinyin initials for Chinese names), then one that holds it anywhere, then
/// one whose artist matches. Within one tier the song playing and recent plays
/// stay first, then the service's order.
abstract final class MentionRanking {
  /// Lower case, accents and full width folded ("Ｎｅｗ Yörk" → "new york").
  static String fold(String text) => foldForMatching(text).replaceAll(_marks, '').toLowerCase();

  /// Every combining mark (Kotlin's `\p{M}+`: accents, and variation selectors such as an emoji's U+FE0F).
  static final _marks = RegExp(r'\p{M}+', unicode: true);

  /// The typed query compared without the spaces and apostrophes a pinyin keyboard puts between syllables.
  static String compact(String text) => _strip(fold(text));

  static String _strip(String folded) {
    final b = StringBuffer();
    for (var i = 0; i < folded.length; i++) {
      final c = folded.codeUnitAt(i);
      if (isKotlinWhitespace(c) || _separators.contains(c)) continue;
      b.writeCharCode(c);
    }
    return b.toString();
  }

  static _Key _key(String name, MentionRomanizer romanizer) {
    final folded = fold(name);
    final words = fold(romanizer(name) ?? name).split(_wordBreak).where((w) => w.isNotEmpty).toList();
    return _Key(folded, _strip(folded), words.join(), words.map((w) => w.substring(0, 1)).join());
  }

  static MentionTier tier(String query, MentionCandidate row, MentionRomanizer romanizer) {
    final folded = kotlinTrim(fold(query));
    final compact = MentionRanking.compact(query);
    if (compact.isEmpty) return MentionTier.none;
    final title = _key(row.mention.title, romanizer);
    if (title.folded.startsWith(folded) || title.compact.startsWith(compact) || title.spelled.startsWith(compact)) {
      return MentionTier.titlePrefix;
    }
    if (title.initials.startsWith(compact)) return MentionTier.initials;
    if (title.folded.contains(folded) || title.compact.contains(compact) || title.spelled.contains(compact)) {
      return MentionTier.titleSubstring;
    }
    final artist = row.artist;
    if (artist != null && _matches(compact, folded, _key(artist, romanizer))) return MentionTier.artist;
    return MentionTier.none;
  }

  static bool _matches(String compact, String folded, _Key key) =>
      key.folded.contains(folded) ||
      key.compact.contains(compact) ||
      key.spelled.contains(compact) ||
      key.initials.startsWith(compact);

  /// [rows] ordered for [query]. A row the account already knew (the song
  /// playing, a recent play) that doesn't match is dropped; a search result
  /// stays, last. The kinds are listed best match first, so the top row (what
  /// Enter picks) is the best one whatever its kind.
  static MentionCandidates rank(String query, MentionCandidates rows, MentionRomanizer romanizer) {
    if (compact(query).isEmpty) return rows;
    List<_Scored> order(List<MentionCandidate> list) {
      final scored = [
        for (final (index, row) in list.indexed) _Scored(row, tier(query, row, romanizer), index),
      ].where((s) => s.tier != MentionTier.none || s.row.source == MentionSource.search).toList();
      scored.sort((a, b) {
        final t = a.tier.index - b.tier.index;
        if (t != 0) return t;
        final s = a.row.source.index - b.row.source.index;
        if (s != 0) return s;
        return a.index - b.index;
      });
      return scored;
    }

    final tracks = order(rows.tracks);
    final albums = order(rows.albums);
    final artists = order(rows.artists);
    final firsts = [
      (MentionKind.track, tracks),
      (MentionKind.album, albums),
      (MentionKind.artist, artists),
    ].where((e) => e.$2.isNotEmpty).toList();
    final best = [for (final (i, e) in firsts.indexed) (i, e.$1, e.$2.first)];
    best.sort((a, b) {
      final t = a.$3.tier.index - b.$3.tier.index;
      if (t != 0) return t;
      final s = a.$3.row.source.index - b.$3.row.source.index;
      if (s != 0) return s;
      return a.$1 - b.$1; // Kotlin's sortedWith is stable
    });
    final kinds = [for (final b in best) b.$2];
    return MentionCandidates(
      tracks: [for (final s in tracks) s.row],
      albums: [for (final s in albums) s.row],
      artists: [for (final s in artists) s.row],
      order: [...kinds, ...MentionCandidates.defaultOrder.where((k) => !kinds.contains(k))],
    );
  }

  static final _wordBreak = RegExp(r'[^\p{L}\p{N}]+', unicode: true);
  static final _separators = "'’·._-".codeUnits.toSet();
}

final class _Scored {
  const _Scored(this.row, this.tier, this.index);

  final MentionCandidate row;
  final MentionTier tier;
  final int index;
}

/// How many rows of each kind the popup shows.
const int mentionPopupRowsPerKind = 5;

/// The pool filtered for [query] at once (rows the account already knew drop out when they don't match).
MentionCandidates mentionRowsWhileSearching(String query, MentionCandidates pool, MentionRomanizer romanizer) =>
    _capped(MentionRanking.rank(query, _withoutSearchRows(pool), romanizer));

/// The search's rows for [query], with any row from [pool] that matches better ranked in among them.
MentionCandidates mentionRowsAfterSearch(
  String query,
  MentionCandidates found,
  MentionCandidates pool,
  MentionRomanizer romanizer,
) {
  if (query.isEmpty) return found;
  return _capped(MentionRanking.rank(query, found.merge(_withoutSearchRows(pool)), romanizer));
}

/// An earlier search's rows: listed after the current search's, and only while they still match.
MentionCandidates _withoutSearchRows(MentionCandidates rows) {
  List<MentionCandidate> known(List<MentionCandidate> list) => [
    for (final r in list) r.source == MentionSource.search ? r.withSource(MentionSource.seen) : r,
  ];
  return rows.copyWith(tracks: known(rows.tracks), albums: known(rows.albums), artists: known(rows.artists));
}

MentionCandidates _capped(MentionCandidates rows) => rows.copyWith(
  tracks: rows.tracks.take(mentionPopupRowsPerKind).toList(),
  albums: rows.albums.take(mentionPopupRowsPerKind).toList(),
  artists: rows.artists.take(mentionPopupRowsPerKind).toList(),
);

/// Kotlin's Char.isWhitespace (Java isWhitespace or isSpaceChar), on one UTF-16 unit.
bool isKotlinWhitespace(int c) =>
    (c >= 0x09 && c <= 0x0D) ||
    (c >= 0x1C && c <= 0x20) ||
    c == 0xA0 ||
    c == 0x1680 ||
    (c >= 0x2000 && c <= 0x200A) ||
    c == 0x2028 ||
    c == 0x2029 ||
    c == 0x202F ||
    c == 0x205F ||
    c == 0x3000;

/// Kotlin's String.trim(): drops [isKotlinWhitespace] from both ends.
String kotlinTrim(String s) {
  var a = 0;
  var b = s.length;
  while (a < b && isKotlinWhitespace(s.codeUnitAt(a))) {
    a++;
  }
  while (b > a && isKotlinWhitespace(s.codeUnitAt(b - 1))) {
    b--;
  }
  return s.substring(a, b);
}
