/// How a search query meets a piece of text (docs/search.md, "What matches";
/// :shared search/SearchMatch.kt, ported against
/// shared/src/commonTest/resources/golden/memory-search.json): case-, width-
/// and accent-insensitive, the same fold on both sides, and a strength saying
/// how well it met. Local only: the services rank their own rows; this ranks
/// the sections around them and the ledger's hits. Offsets are UTF-16 code
/// units, as in Kotlin.
library;

import '../notes/note_markup.dart' as markup show isBlank;
import '../notes/note_markup.dart' show isLetterOrDigit, isWhitespace, trimKotlin;
import 'search_nfkd.dart';

/// How well a query met one field, weakest first, so a larger index is a better hit.
enum MatchStrength {
  none,

  /// Somewhere inside the text, mid-word (and every CJK hit that isn't at the start).
  contains,

  /// At the start of a word after the first: "dark" in "The Dark Side".
  wordPrefix,

  /// At the very start: "ヨル" in "ヨルシカ".
  prefix,

  /// The whole text, folded.
  exact,
}

/// What kind of field a hit was in. A title-like field outranks the rest
/// whatever the strength ("a title hit outranks a body hit"); the weights
/// live here and nowhere else.
enum MatchTier {
  /// What a result is only about: a song note's song name or artist.
  incidental,

  /// Writing: a note, a review, a diary entry.
  body,

  /// The thing's context: a song's artist or album, an album's artist, a playlist's owner.
  secondary,

  /// The thing's own name: a song or album title, an artist's name, a memory's title.
  primary,
}

/// One hit's rank. Compared tier first, then strength; [none] is below every real hit.
final class SearchScore implements Comparable<SearchScore> {
  const SearchScore(this.tier, this.strength);

  final MatchTier tier;
  final MatchStrength strength;

  static const none = SearchScore(MatchTier.incidental, MatchStrength.none);

  bool get isHit => strength != MatchStrength.none;

  /// Exact or prefix on the thing's own name: what earns the best-match card.
  bool get isStrongName => tier == MatchTier.primary && strength.index >= MatchStrength.prefix.index;

  @override
  int compareTo(SearchScore other) {
    if (isHit != other.isHit) return isHit ? 1 : -1;
    final t = tier.index.compareTo(other.tier.index);
    return t != 0 ? t : strength.index.compareTo(other.strength.index);
  }

  bool operator >(SearchScore other) => compareTo(other) > 0;

  /// The better of the two; the first when they tie (Kotlin maxOf).
  static SearchScore max(SearchScore a, SearchScore b) => a.compareTo(b) >= 0 ? a : b;

  @override
  bool operator ==(Object other) => other is SearchScore && other.tier == tier && other.strength == strength;

  @override
  int get hashCode => Object.hash(tier, strength);

  @override
  String toString() => 'SearchScore(${tier.name}, ${strength.name})';
}

/// A range of UTF-16 units, [start] until [end]; empty when `start >= end`.
final class SearchRange {
  const SearchRange(this.start, this.end);

  static const empty = SearchRange(-1, -1);

  final int start;
  final int end;

  bool get isEmpty => start < 0 || start >= end;

  @override
  bool operator ==(Object other) =>
      other is SearchRange && (other.isEmpty && isEmpty || other.start == start && other.end == end);

  @override
  int get hashCode => isEmpty ? 0 : Object.hash(start, end);

  @override
  String toString() => isEmpty ? 'SearchRange.empty' : 'SearchRange($start, $end)';
}

/// [original] folded for matching, with each folded unit's source index so a
/// hit can be shown in the original text. Folding: compatibility forms
/// (full-width Ｙ, half-width ｶ) to their plain forms, lowercase, Latin accents
/// dropped (Björk = Bjork), runs of whitespace to one space, ends trimmed.
/// Kana keep their voicing marks: が is not か.
final class FoldedText {
  FoldedText._(this.original, this.text, this._origins);

  final String original;
  final String text;
  final List<int> _origins;

  /// The original units behind folded [start] until [endExclusive].
  SearchRange originalRange(int start, int endExclusive) {
    if (start >= endExclusive || _origins.isEmpty) return SearchRange.empty;
    final from = _origins[start];
    final last = _origins[endExclusive - 1];
    return SearchRange(from, last + _codePointWidth(original, last));
  }

  factory FoldedText.of(String original) {
    final out = StringBuffer();
    var outLength = 0;
    final origins = <int>[];
    var pendingSpace = false;
    var i = 0;
    while (i < original.length) {
      final width = _codePointWidth(original, i);
      final folded = _foldCodePoint(original.substring(i, i + width));
      for (var k = 0; k < folded.length; k++) {
        final ch = folded.codeUnitAt(k);
        if (isWhitespace(ch)) {
          pendingSpace = outLength > 0;
          continue;
        }
        // One code point can fold to many units (ﷺ), and a space may go first.
        if (pendingSpace) {
          out.writeCharCode(0x20);
          outLength++;
          origins.add(i);
          pendingSpace = false;
        }
        out.writeCharCode(ch);
        outLength++;
        origins.add(i);
      }
      i += width;
    }
    return FoldedText._(original, out.toString(), origins);
  }

  // Decomposed, so a code point folds the same alone as inside a word: ｶﾞ and
  // ガ both become カ + ゛. Hangul syllables stay whole, or 가 would match inside 각.
  static String _foldCodePoint(String alone) {
    if (alone.length == 1 && alone.codeUnitAt(0) >= 0xAC00 && alone.codeUnitAt(0) <= 0xD7A3) return alone;
    final decomposed = nfkdCodePoint(alone).toLowerCase();
    for (var k = 0; k < decomposed.length; k++) {
      if (!_isLatinLike(decomposed.codeUnitAt(k))) return decomposed;
    }
    final kept = StringBuffer();
    for (var k = 0; k < decomposed.length; k++) {
      final c = decomposed.codeUnitAt(k);
      if (!isNonSpacingMark(c)) kept.writeCharCode(c);
    }
    return kept.toString();
  }

  // Latin, Greek, Cyrillic and their combining marks: where dropping an accent
  // is what people expect. Kana voicing marks (U+3099, U+309A) are outside it.
  static bool _isLatinLike(int c) => c < 0x0530 || (c >= 0x1E00 && c <= 0x1FFF);
}

/// UTF-16 units of the code point at [index]: 2 for a surrogate pair, else 1 (a lone surrogate is one).
int _codePointWidth(String text, int index) =>
    _isHigh(text.codeUnitAt(index)) && index + 1 < text.length && _isLow(text.codeUnitAt(index + 1)) ? 2 : 1;

bool _isHigh(int c) => c >= 0xD800 && c <= 0xDBFF;
bool _isLow(int c) => c >= 0xDC00 && c <= 0xDFFF;

Map<int, String>? _nfkd;

/// Unicode NFKD of one code point ([alone], one or two UTF-16 units), as
/// java.text.Normalizer gives it; anything else comes back as it is.
String nfkdCodePoint(String alone) {
  final table = _nfkd ??= _parseNfkd();
  final runes = alone.runes;
  if (runes.length != 1) return alone;
  return table[runes.first] ?? alone;
}

Map<int, String> _parseNfkd() {
  final table = <int, String>{};
  for (final entry in searchNfkdData.split(';')) {
    final colon = entry.indexOf(':');
    final from = int.parse(entry.substring(0, colon), radix: 16);
    table[from] = String.fromCharCodes([
      for (final hex in entry.substring(colon + 1).split('.')) int.parse(hex, radix: 16),
    ]);
  }
  return table;
}

final _nonSpacingMark = RegExp(r'^\p{Mn}$', unicode: true);
final _markCache = <int, bool>{};

/// Kotlin's `Char.category == NON_SPACING_MARK` on one UTF-16 unit (a lone surrogate is none).
bool isNonSpacingMark(int c) {
  if (c < 0x0300 || (c >= 0xD800 && c <= 0xDFFF)) return false;
  return _markCache[c] ??= _nonSpacingMark.hasMatch(String.fromCharCode(c));
}

/// Where and how well a query met one field ([source], folded).
final class FieldMatch {
  const FieldMatch(this.strength, this.source, {this.start = -1, this.length = 0});

  static const none = FieldMatch(MatchStrength.none, null);

  final MatchStrength strength;
  final FoldedText? source;
  final int start;
  final int length;

  /// The hit's units in the field's original text; empty without a hit.
  SearchRange get originalRange =>
      source == null || start < 0 ? SearchRange.empty : source!.originalRange(start, start + length);
}

/// A query, folded once, matched against many fields. Blank queries match nothing.
final class SearchQuery {
  SearchQuery(String raw) : folded = FoldedText.of(raw).text;

  final String folded;

  bool get isBlank => folded.isEmpty;

  FieldMatch match(String? text) {
    if (isBlank || text == null || markup.isBlank(text)) return FieldMatch.none;
    final field = FoldedText.of(text);
    final haystack = field.text;
    var first = -1;
    var from = 0;
    while (true) {
      final at = haystack.indexOf(folded, from);
      if (at < 0) break;
      from = at + 1;
      // A hit that stops inside a decomposed char (か before the ゛ of が) is no hit.
      final end = at + folded.length;
      if (end < haystack.length && isNonSpacingMark(haystack.codeUnitAt(end))) continue;
      if (at == 0 && end == haystack.length) {
        return FieldMatch(MatchStrength.exact, field, start: 0, length: folded.length);
      }
      if (at == 0) return FieldMatch(MatchStrength.prefix, field, start: 0, length: folded.length);
      if (!isLetterOrDigit(haystack.codeUnitAt(at - 1)) && isLetterOrDigit(haystack.codeUnitAt(at))) {
        return FieldMatch(MatchStrength.wordPrefix, field, start: at, length: folded.length);
      }
      if (first < 0) first = at;
    }
    return first >= 0
        ? FieldMatch(MatchStrength.contains, field, start: first, length: folded.length)
        : FieldMatch.none;
  }

  SearchScore score(String? text, MatchTier tier) => SearchScore(tier, match(text).strength);

  /// The part of [match]'s field worth showing: the hit with a little before
  /// it, up to [maxChars], line breaks as spaces, an ellipsis where text was
  /// cut. The whole text when it fits.
  ///
  /// A cut never falls inside a surrogate pair (an emoji is two UTF-16
  /// units): a cut that would is moved inward to the whole code point, so the
  /// text stays within [maxChars] and the highlight, which always covers
  /// whole code points, stays intact (docs/search.md: an intentional change
  /// from Android before parity p3).
  SearchFragment? fragment(FieldMatch match, {int maxChars = fragmentMaxChars}) {
    final original = match.source?.original;
    if (original == null) return null;
    final flat = String.fromCharCodes([
      for (final c in original.codeUnits) c == 0x0A || c == 0x0D || c == 0x09 ? 0x20 : c,
    ]);
    final hit = match.originalRange;
    if (flat.length <= maxChars || hit.isEmpty) {
      var keep = maxChars < flat.length ? maxChars : flat.length;
      if (keep >= 1 && keep < flat.length && _isHigh(flat.codeUnitAt(keep - 1)) && _isLow(flat.codeUnitAt(keep))) {
        keep--;
      }
      final text = _trimEnd(flat.substring(0, keep));
      final cut = flat.length > maxChars ? '$text…' : text;
      // Kotlin: the hit stands when its last unit is inside the kept text.
      return SearchFragment(cut, !hit.isEmpty && hit.end - 1 < text.length ? hit : SearchRange.empty);
    }
    var start = hit.start - _fragmentLeadChars < 0 ? 0 : hit.start - _fragmentLeadChars;
    if (start > 0 && _isLow(flat.codeUnitAt(start))) start--;
    var end = start + maxChars > flat.length ? flat.length : start + maxChars;
    if (end < flat.length && _isHigh(flat.codeUnitAt(end - 1))) end--;
    if (end - start < maxChars) start = end - maxChars < 0 ? 0 : end - maxChars;
    // Pulled back from the end, the start can land on a pair's second half:
    // step in past it. The hit starts on a whole code point after it.
    if (start > 0 && _isLow(flat.codeUnitAt(start)) && _isHigh(flat.codeUnitAt(start - 1))) start++;
    final lead = start > 0 ? '…' : '';
    final tail = end < flat.length ? '…' : '';
    final window = flat.substring(start, end);
    final body = trimKotlin(window);
    final trimmedLead = window.length - _trimStart(window).length;
    final offset = lead.length - start - trimmedLead;
    final from = hit.start + offset < lead.length ? lead.length : hit.start + offset;
    final lastIndex = lead.length + body.length - 1;
    final to = hit.end - 1 + offset > lastIndex ? lastIndex : hit.end - 1 + offset;
    return SearchFragment('$lead$body$tail', from <= to ? SearchRange(from, to + 1) : SearchRange.empty);
  }
}

String _trimEnd(String s) {
  var end = s.length;
  while (end > 0 && isWhitespace(s.codeUnitAt(end - 1))) {
    end--;
  }
  return s.substring(0, end);
}

String _trimStart(String s) {
  var start = 0;
  while (start < s.length && isWhitespace(s.codeUnitAt(start))) {
    start++;
  }
  return s.substring(start);
}

/// A piece of text to show for a hit: the text and, inside it, the units to highlight.
final class SearchFragment {
  const SearchFragment(this.text, this.highlight);

  final String text;
  final SearchRange highlight;

  SearchFragment withoutHighlight() => SearchFragment(text, SearchRange.empty);

  @override
  bool operator ==(Object other) => other is SearchFragment && other.text == text && other.highlight == highlight;

  @override
  int get hashCode => Object.hash(text, highlight);
}

/// A fragment's longest text, ellipses aside: about two lines in a result row.
const int fragmentMaxChars = 80;

/// How much text a fragment keeps before its hit.
const int _fragmentLeadChars = 24;
