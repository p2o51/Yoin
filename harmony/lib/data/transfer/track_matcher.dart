/// Decides which of another service's tracks is a playlist's track
/// (Android data/transfer/TrackMatcher.kt; docs/playlist-transfer.md §4).
/// Pure: no service calls, so the rules are unit-tested on their own.
///
/// A candidate is scored on title, artist and duration. Title and artist are
/// compared after [TrackMatcher.normalizeTitle] / [TrackMatcher.normalizeArtist]
/// (case, width, accents, "feat." credits and "Remastered" tags fall away; CJK
/// stays). Duration within [TrackMatcher.durationToleranceSec] counts in full;
/// beyond [TrackMatcher.durationRejectSec] the candidate is a different recording.
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../model/model.dart';

/// How a track found its counterpart.
enum MatchMethod {
  isrc,
  search,

  /// The same service's own id (a Spotify backup into a Spotify account on Android).
  sourceId,
}

sealed class MatchDecision {
  const MatchDecision();
}

@immutable
final class MatchMatched extends MatchDecision {
  const MatchMatched(this.track, this.method);
  final Track track;
  final MatchMethod method;
}

/// Close, but not close enough to add without asking: the best few, best first.
@immutable
final class MatchAmbiguous extends MatchDecision {
  const MatchAmbiguous(this.candidates);
  final List<Track> candidates;
}

final class MatchNotFound extends MatchDecision {
  const MatchNotFound();
}

abstract final class TrackMatcher {
  static const int durationToleranceSec = 3;
  static const int durationRejectSec = 10;

  /// A score at or above this, inside the duration tolerance, is a match.
  static const double matchScore = 0.85;

  /// Below [matchScore] but at or above this: shown as unsure, not added.
  static const double ambiguousScore = 0.65;

  static const double _titleWeight = 0.5;
  static const double _artistWeight = 0.35;
  static const double _durationWeight = 0.15;
  static const double _titleFloor = 0.6;
  static const double _artistFloor = 0.5;

  /// [source]'s best counterpart among [candidates] from a title search.
  static MatchDecision choose(Track source, List<Track> candidates) {
    final scored = [
      for (final (i, c) in candidates.indexed) (index: i, track: c, score: score(source, c)),
    ].where((e) => e.score > 0).toList();
    // List.sort isn't stable in Dart: the original order breaks the last tie, as Kotlin's stable sort keeps it.
    scored.sort((a, b) {
      final byScore = b.score.compareTo(a.score);
      if (byScore != 0) return byScore;
      final byGap = (_durationGap(source, a.track) ?? 1 << 31).compareTo(_durationGap(source, b.track) ?? 1 << 31);
      if (byGap != 0) return byGap;
      return a.index.compareTo(b.index);
    });
    if (scored.isEmpty) return const MatchNotFound();
    final best = scored.first;
    final gap = _durationGap(source, best.track);
    final withinDuration = gap == null || gap <= durationToleranceSec;
    // A live take, a remix or an acoustic cut of the song is another recording:
    // never added unasked, only offered for the user to check.
    final sameVersion = setEquals(versionTags(source.title), versionTags(best.track.title));
    if (best.score >= matchScore && withinDuration && sameVersion) return MatchMatched(best.track, MatchMethod.search);
    if (best.score >= ambiguousScore) {
      return MatchAmbiguous([for (final e in scored.takeWhile((e) => e.score >= ambiguousScore).take(3)) e.track]);
    }
    return const MatchNotFound();
  }

  /// [source]'s counterpart among tracks that share its ISRC: the same
  /// recording, so only a duration far off (a different edit filed under one
  /// code) rules one out. Ties go to the closest title and album.
  static MatchDecision chooseByIsrc(Track source, List<Track> candidates) {
    final usable = candidates.where((c) {
      final gap = _durationGap(source, c);
      return gap == null || gap <= durationRejectSec;
    }).toList();
    if (usable.isEmpty) return const MatchNotFound();
    var best = usable.first;
    for (final c in usable.skip(1)) {
      if (_isrcOrder(source, c, best) > 0) best = c;
    }
    return MatchMatched(best, MatchMethod.isrc);
  }

  /// Kotlin maxWithOrNull: the first of equals wins, so only a strictly greater one replaces it.
  static int _isrcOrder(Track source, Track a, Track b) {
    final byTitle = titleSimilarity(source.title, a.title).compareTo(titleSimilarity(source.title, b.title));
    if (byTitle != 0) return byTitle;
    final byAlbum = (_sameAlbum(source, a) ? 1 : 0).compareTo(_sameAlbum(source, b) ? 1 : 0);
    if (byAlbum != 0) return byAlbum;
    return (-(_durationGap(source, a) ?? 0)).compareTo(-(_durationGap(source, b) ?? 0));
  }

  /// 0 when a floor rules [candidate] out, else the weighted similarity in (0, 1].
  static double score(Track source, Track candidate) {
    final title = titleSimilarity(source.title, candidate.title);
    if (title < _titleFloor) return 0;
    final artist = artistSimilarity(source.artist, candidate.artist);
    if (artist < _artistFloor) return 0;
    final gap = _durationGap(source, candidate);
    final double duration;
    if (gap == null) {
      duration = 0.8;
    } else if (gap <= durationToleranceSec) {
      duration = 1.0;
    } else if (gap <= durationRejectSec) {
      duration = 0.4;
    } else {
      return 0;
    }
    return _titleWeight * title + _artistWeight * artist + _durationWeight * duration;
  }

  /// What a title search on the target asks for: the bare title and the first artist.
  static String? searchQuery(Track track) {
    final title = stripDecorations(track.title ?? '').trim();
    if (title.isEmpty) return null;
    final artist = splitArtists(track.artist ?? '').firstOrNull ?? '';
    return artist.isEmpty ? title : '$title $artist';
  }

  /// The bare title alone, the search after [searchQuery] found nothing.
  static String? titleQuery(Track track) {
    final title = stripDecorations(track.title ?? '').trim();
    return title.isEmpty ? null : title;
  }

  static double titleSimilarity(String? a, String? b) {
    final left = normalizeTitle(a ?? '');
    final right = normalizeTitle(b ?? '');
    if (left.isEmpty || right.isEmpty) return 0;
    if (left == right) return 1;
    return math.max(similarity(left, right), _tokenOverlap(left, right) * 0.95);
  }

  /// The best pairing of the two credits' artists: "A, B" against "A & B"
  /// is 1, and so is "A feat. C" against "A" — the lead artist decides.
  static double artistSimilarity(String? a, String? b) {
    final left = [for (final s in splitArtists(a ?? '')) normalizeArtist(s)].where((s) => s.isNotEmpty).toList();
    final right = [for (final s in splitArtists(b ?? '')) normalizeArtist(s)].where((s) => s.isNotEmpty).toList();
    if (left.isEmpty || right.isEmpty) return left.isEmpty && right.isEmpty ? 0.6 : 0.5;
    if (left.any(right.contains)) return 1;
    final whole = similarity(left.join(' '), right.join(' '));
    var pairs = 0.0;
    for (final l in left) {
      for (final r in right) {
        pairs = math.max(pairs, similarity(l, r));
      }
    }
    return math.max(whole, pairs);
  }

  static String normalizeTitle(String title) => _normalize(stripDecorations(title));

  static String normalizeArtist(String artist) {
    final n = _normalize(artist);
    return (n.startsWith('the ') ? n.substring(4) : n).trim();
  }

  /// "Song (feat. X) - Remastered 2011" → "Song": credits and edition tags, not words that change the recording.
  static String stripDecorations(String title) {
    var result = title.replaceAllMapped(_bracketed, (m) => _decoration.hasMatch(m[0]!) ? '' : m[0]!);
    final dash = _dashSuffix.firstMatch(result);
    if (dash != null && _decoration.hasMatch(dash[1]!)) result = result.substring(0, dash.start);
    result = result.replaceAll(_featTail, '');
    return result.trim();
  }

  /// The words in a title that name another recording of the song ("Live", "Remix"…).
  static Set<String> versionTags(String? title) => {
    for (final m in _versionTag.allMatches(title ?? '')) m[0]!.toLowerCase().replaceAll(_spaces, ' '),
  };

  static List<String> splitArtists(String artist) =>
      artist.split(_artistSeparator).map((s) => s.trim()).where((s) => s.isNotEmpty).toList();

  static String _normalize(String text) {
    final folded = foldForMatching(text).toLowerCase().replaceAll('&', ' ');
    final b = StringBuffer();
    for (final rune in folded.runes) {
      final char = String.fromCharCode(rune);
      b.write(_letterOrDigit.hasMatch(char) ? char : ' ');
    }
    return b.toString().split(' ').where((s) => s.isNotEmpty).join(' ');
  }

  static double _tokenOverlap(String a, String b) {
    final left = a.split(' ').toSet();
    final right = b.split(' ').toSet();
    final shared = left.intersection(right).length;
    return shared / math.max(left.length, right.length);
  }

  /// 1 − Levenshtein distance / longer length (over UTF-16 units, as Kotlin's chars).
  static double similarity(String a, String b) {
    if (a == b) return 1;
    final longer = math.max(a.length, b.length);
    if (longer == 0) return 1;
    var previous = List<int>.generate(b.length + 1, (i) => i);
    var current = List<int>.filled(b.length + 1, 0);
    for (var i = 1; i <= a.length; i++) {
      current[0] = i;
      for (var j = 1; j <= b.length; j++) {
        final cost = a.codeUnitAt(i - 1) == b.codeUnitAt(j - 1) ? 0 : 1;
        current[j] = math.min(math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      final swap = previous;
      previous = current;
      current = swap;
    }
    return 1 - previous[b.length] / longer;
  }

  static int? _durationGap(Track a, Track b) {
    final left = a.durationSec;
    final right = b.durationSec;
    if (left == null || left <= 0 || right == null || right <= 0) return null;
    return (left - right).abs();
  }

  static bool _sameAlbum(Track a, Track b) {
    final left = normalizeTitle(a.album ?? '');
    return left.isNotEmpty && left == normalizeTitle(b.album ?? '');
  }

  static final _letterOrDigit = RegExp(r'[\p{L}\p{N}]', unicode: true);
  static final _spaces = RegExp(r'\s+');
  static final _bracketed = RegExp(r'[(\[（【][^)\]）】]*[)\]）】]');
  static final _dashSuffix = RegExp(r'\s[-–—]\s(.+)$');
  static final _decoration = RegExp(
    r'\b(feat\.?|ft\.?|featuring|with|remaster(ed)?|\d{4}\s+remaster|deluxe|bonus track|explicit|clean|'
    r'single version|album version|original mix|from\s.+)',
    caseSensitive: false,
  );
  static final _versionTag = RegExp(
    r'\b(live|remix|acoustic|instrumental|demo|karaoke|unplugged|orchestral|piano version|'
    r'radio edit|extended|sped up|slowed|a cappella|acapella)\b',
    caseSensitive: false,
  );
  static final _featTail = RegExp(r'\s(feat\.?|ft\.?|featuring)\s.*$', caseSensitive: false);
  static final _artistSeparator = RegExp(
    r'\s*(,|&|、|/|;|\sx\s|\sand\s|\sfeat\.?\s|\sft\.?\s|\sfeaturing\s|\swith\s)\s*',
    caseSensitive: false,
  );
}

/// What Android's NFKD + "drop combining marks" does to the text a match
/// compares, without a Unicode normaliser in Dart: full-width forms to ASCII,
/// the ideographic space to a space, Latin letters with accents to their base
/// letter, and combining marks dropped. CJK is left as it is.
String foldForMatching(String text) {
  final b = StringBuffer();
  for (final rune in text.runes) {
    if (rune >= 0x0300 && rune <= 0x036F) continue; // combining diacritical marks
    if (rune >= 0xFF01 && rune <= 0xFF5E) {
      b.writeCharCode(rune - 0xFEE0);
      continue;
    }
    if (rune == 0x3000) {
      b.write(' ');
      continue;
    }
    final base = _latinBase[rune];
    if (base != null) {
      b.write(base);
    } else {
      b.writeCharCode(rune);
    }
  }
  return b.toString();
}

/// Latin-1 Supplement and Latin Extended-A letters NFKD splits into a base
/// letter and a mark (plus the compatibility ligatures it spells out).
final Map<int, String> _latinBase = () {
  const groups = {
    'A': 'ÀÁÂÃÄÅĀĂĄ',
    'a': 'àáâãäåāăą',
    'C': 'ÇĆĈĊČ',
    'c': 'çćĉċč',
    'D': 'Ď',
    'd': 'ď',
    'E': 'ÈÉÊËĒĔĖĘĚ',
    'e': 'èéêëēĕėęě',
    'G': 'ĜĞĠĢ',
    'g': 'ĝğġģ',
    'H': 'Ĥ',
    'h': 'ĥ',
    'I': 'ÌÍÎÏĨĪĬĮİ',
    'i': 'ìíîïĩīĭį',
    'J': 'Ĵ',
    'j': 'ĵ',
    'K': 'Ķ',
    'k': 'ķ',
    'L': 'ĹĻĽ',
    'l': 'ĺļľ',
    'N': 'ÑŃŅŇ',
    'n': 'ñńņň',
    'O': 'ÒÓÔÕÖŌŎŐ',
    'o': 'òóôõöōŏő',
    'R': 'ŔŖŘ',
    'r': 'ŕŗř',
    'S': 'ŚŜŞŠ',
    's': 'śŝşš',
    'T': 'ŢŤ',
    't': 'ţť',
    'U': 'ÙÚÛÜŨŪŬŮŰŲ',
    'u': 'ùúûüũūŭůűų',
    'W': 'Ŵ',
    'w': 'ŵ',
    'Y': 'ÝŶŸ',
    'y': 'ýÿŷ',
    'Z': 'ŹŻŽ',
    'z': 'źżž',
  };
  final map = <int, String>{};
  groups.forEach((base, letters) {
    for (final rune in letters.runes) {
      map[rune] = base;
    }
  });
  map[0xFB00] = 'ff';
  map[0xFB01] = 'fi';
  map[0xFB02] = 'fl';
  return map;
}();
