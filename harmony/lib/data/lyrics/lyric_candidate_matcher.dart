import 'package:flutter/foundation.dart';

import 'traditional_simplified_table.dart';

/// Within this the lengths "match" (providers mostly give whole seconds; LYRIC_DURATION_TOLERANCE_MS).
const int lyricDurationToleranceMs = 3000;

/// One search hit as the matcher sees it: title, every performer, its album
/// (null when the provider sent none) and length; list order is the provider's rank.
@immutable
final class LyricCandidate {
  const LyricCandidate({required this.title, required this.artists, this.album, this.durationMs});

  final String title;
  final List<String> artists;
  final String? album;
  final int? durationMs;
}

/// Picks, from a lyrics provider's search results, the one that IS this song
/// (Android LyricCandidateMatcher.kt, ported rule for rule). It returns null
/// when nothing can be trusted, so the next provider gets a go instead of a
/// same-titled song by someone else.
///
/// **Title.** Both sides are normalised first: NFKC (full-width → half-width),
/// [fold] (Traditional → Simplified by default), case, punctuation and spaces
/// ignored, Latin diacritics dropped. Trailing notes — a bracketed part
/// (`(Live)`, `(Explicit)`, `(DJ 版)`), ` - suffix`, a `feat.` clause — are
/// peeled from the outside in. Equal once same-master notes are gone on both
/// sides = "original"; equal only once every note is gone = "decorated".
/// Instrumental / karaoke notes never match unless the request is one.
///
/// **Same-master notes** (remaster, mono / stereo, album / LP / original
/// version, explicit, their Japanese forms) rank with the plain title. Single
/// version / edit, radio edit, clean, a bare year mix, a lone "original",
/// "Taylor's Version", live, remix, acoustic … are ordinary notes.
///
/// **Album.** For a request with no notes beyond same-master ones, a live /
/// unplugged / concert / remix / demo / covers / sped-up album makes the hit
/// at best "decorated"; an interview / karaoke / instrumental album drops it
/// to "doubtful" (last, but not out).
///
/// **Artist.** The requested credit as a whole and each part split on `&`,
/// `,`, `、`, `/`, `;`, `feat.` is compared with every performer: equal once
/// normalised → match; the same writing systems but different → mismatch (no
/// prefixes or fragments); different systems but both with Latin words → the
/// words of one must all be in the other; otherwise unknown.
///
/// **Picking.** Artist matches first: best tier (original → decorated →
/// doubtful), then length within [lyricDurationToleranceMs], then rank. With
/// none, among the "unknown" hits only the first-ranked performer counts, and
/// only with at least two same-titled recordings and more than any rival;
/// once settled on, a cut whose known length is off by more than the tolerance
/// is dropped — with the artist unchecked, a same-titled song by someone else
/// (Japanese Spotify's `摩天輪` / `ヒビ・ティエン` vs QQ's 罗百吉《摩天轮》) can
/// only be stopped by its length.
class LyricCandidateMatcher {
  LyricCandidateMatcher({String Function(String)? fold}) : _fold = fold ?? traditionalToSimplified;

  final String Function(String) _fold;

  /// The index in [candidates] picked, or null when none can be trusted.
  int? pick(List<LyricCandidate> candidates, String title, String artist, {int? durationMs}) {
    if (candidates.isEmpty) return null;
    final wantedTitle = _titleOf(title);
    if (wantedTitle == null) return null;
    final wantedArtists = _artistNamesOf(artist);

    final cuts = <_Cut>[];
    for (var index = 0; index < candidates.length; index++) {
      final candidate = candidates[index];
      final fit = _fitOf(wantedTitle, candidate);
      if (fit == null) continue;
      final performers = [for (final a in candidate.artists) _nameOf(a)].where((n) => n.key.isNotEmpty).toList();
      final d = candidate.durationMs;
      final offLength = durationMs != null && d != null && (d - durationMs).abs() > lyricDurationToleranceMs;
      cuts.add(_Cut(index, fit, performers, _verdictOf(wantedArtists, performers), offLength));
    }
    if (cuts.isEmpty) return null;

    final matched = _preferred(cuts.where((c) => c.verdict == _Verdict.match).toList());
    if (matched != null) return matched.index;
    return _dominantPerformerCut(cuts.where((c) => c.verdict == _Verdict.unknown).toList())?.index;
  }

  // Title ---------------------------------------------------------------------

  _Fit? _fitOf(_TitleShape wanted, LyricCandidate candidate) {
    final offered = _titleOf(candidate.title);
    if (offered == null) return null;
    final _Fit byTitle;
    if (offered.key == wanted.key || offered.masterKey == wanted.masterKey) {
      byTitle = _Fit.original;
    } else if (offered.coreKey != wanted.coreKey) {
      return null;
    } else if (offered.instrumental && !wanted.instrumental) {
      return null;
    } else {
      byTitle = _Fit.decorated;
    }
    if (!wanted.isOriginal) return byTitle;
    final byAlbum = _albumFitOf(candidate.album);
    return byAlbum.index > byTitle.index ? byAlbum : byTitle;
  }

  _TitleShape? _titleOf(String raw) {
    final text = _prepare(raw);
    final key = seamKeyOf(text);
    if (key.isEmpty) return null;
    var core = text;
    final notes = <_Note>[];
    while (true) {
      _NotePattern? pattern;
      RegExpMatch? found;
      for (final candidate in _trailingNotes) {
        final m = candidate.regex.firstMatch(core);
        if (m != null) {
          pattern = candidate;
          found = m;
          break;
        }
      }
      if (pattern == null || found == null) break;
      final rest = core.substring(0, found.start);
      if (seamKeyOf(rest).isEmpty) break;
      final noteKey = seamKeyOf(found.group(1) ?? '');
      notes.add(
        _Note(
          key: noteKey,
          segmentKey: seamKeyOf(found.group(0)!),
          sameMaster: pattern.versionNote && _sameMasterNote.hasMatch(noteKey),
        ),
      );
      core = rest;
    }
    final coreKey = seamKeyOf(core);
    // Peeled from the outside in; put back from the inside out.
    final otherNotes = notes.reversed.where((n) => !n.sameMaster).map((n) => n.segmentKey).join();
    return _TitleShape(
      key: key,
      coreKey: coreKey,
      masterKey: coreKey + otherNotes,
      instrumental: notes.any((n) => _isInstrumentalNote(n.key)),
    );
  }

  static bool _isInstrumentalNote(String noteKey) =>
      _instrumentalNotes.contains(noteKey) || _instrumentalWords.any(noteKey.contains);

  // Album ---------------------------------------------------------------------

  _Fit _albumFitOf(String? raw) {
    if (raw == null || raw.trim().isEmpty) return _Fit.original;
    final text = _prepare(raw);
    final key = seamKeyOf(text);
    final words = _latinWordListOf(text);
    if (words.any(_doubtfulAlbumWords.contains) || _doubtfulAlbumMarks.any(key.contains)) return _Fit.doubtful;
    if (_isLiveAlbum(words) || words.any(_alternateAlbumWords.contains) || _alternateAlbumMarks.any(key.contains)) {
      return _Fit.decorated;
    }
    return _Fit.original;
  }

  /// `live` last (`Unplugged 1994 (live)`), or followed by a year or one of
  /// [_liveFollowers] (`Live in Tokyo 1966`) — not `Live Through This`;
  /// `tour` followed by a year — not `Magical Mystery Tour`.
  static bool _isLiveAlbum(List<String> words) {
    for (var i = 0; i < words.length; i++) {
      final next = i + 1 < words.length ? words[i + 1] : null;
      switch (words[i]) {
        case 'live':
          if (next == null || _liveFollowers.contains(next) || _isYear(next)) return true;
        case 'tour':
          if (next != null && _isYear(next)) return true;
      }
    }
    return false;
  }

  // Artist --------------------------------------------------------------------

  List<_Name> _artistNamesOf(String raw) {
    final whole = raw.trim();
    if (whole.isEmpty) return const [];
    final seen = <String>{};
    return [
      for (final part in [whole, ...whole.split(_artistSeparator)]) _nameOf(part),
    ].where((n) => n.key.isNotEmpty && seen.add(n.key)).toList();
  }

  _Name _nameOf(String raw) {
    final text = _prepare(raw);
    return _Name(key: seamKeyOf(text), scripts: scriptsOf(text), latinWords: _latinWordsOf(text));
  }

  static _Verdict _verdictOf(List<_Name> wanted, List<_Name> performers) {
    if (wanted.isEmpty || performers.isEmpty) return _Verdict.unknown;
    var undecided = false;
    for (final name in wanted) {
      for (final performer in performers) {
        switch (_compare(name, performer)) {
          case _Verdict.match:
            return _Verdict.match;
          case _Verdict.unknown:
            undecided = true;
          case _Verdict.mismatch:
            break;
        }
      }
    }
    return undecided ? _Verdict.unknown : _Verdict.mismatch;
  }

  static _Verdict _compare(_Name wanted, _Name offered) {
    if (wanted.key == offered.key) return _Verdict.match;
    if (setEquals(wanted.scripts, offered.scripts)) return _Verdict.mismatch;
    if (wanted.latinWords.isEmpty || offered.latinWords.isEmpty) return _Verdict.unknown;
    if (offered.latinWords.containsAll(wanted.latinWords) || wanted.latinWords.containsAll(offered.latinWords)) {
      return _Verdict.match;
    }
    return _Verdict.mismatch;
  }

  // Picking -------------------------------------------------------------------

  static _Cut? _dominantPerformerCut(List<_Cut> unverifiable) {
    if (unverifiable.isEmpty) return null;
    final lead = unverifiable.first;
    final cutsByPerformer = <String, List<_Cut>>{};
    for (final cut in unverifiable) {
      for (final key in cut.performerKeys) {
        cutsByPerformer.putIfAbsent(key, () => []).add(cut);
      }
    }
    String? performer;
    var most = -1;
    for (final key in lead.performerKeys) {
      final n = cutsByPerformer[key]?.length ?? 0;
      if (n > most) {
        most = n;
        performer = key;
      }
    }
    if (performer == null) return null;
    final own = cutsByPerformer[performer]!;
    if (own.length < _minDominantCuts) return null;
    final rivalCounts = <String, int>{};
    for (final cut in unverifiable) {
      if (cut.performerKeys.contains(performer)) continue;
      for (final key in cut.performerKeys) {
        rivalCounts[key] = (rivalCounts[key] ?? 0) + 1;
      }
    }
    final strongestRival = rivalCounts.values.fold(0, (a, b) => a > b ? a : b);
    if (own.length <= strongestRival) return null;
    // Nothing vouches for the artist here, so a known length that's off rules a cut out.
    return _preferred(own.where((c) => !c.offLength).toList());
  }

  static _Cut? _preferred(List<_Cut> cuts) {
    _Cut? best;
    for (final cut in cuts) {
      if (best == null || _better(cut, best)) best = cut;
    }
    return best;
  }

  static bool _better(_Cut a, _Cut b) {
    if (a.fit.index != b.fit.index) return a.fit.index < b.fit.index;
    if (a.offLength != b.offLength) return !a.offLength;
    return a.index < b.index;
  }

  // Normalising ---------------------------------------------------------------

  /// NFKC → [_fold] → lower case; punctuation stays for the note peeling.
  String _prepare(String raw) => _fold(nfkcLite(raw)).toLowerCase();

  static const int _minDominantCuts = 2;

  /// Trailing notes, peeled from the outside in; group 1 is the note.
  static final List<_NotePattern> _trailingNotes = [
    _NotePattern(RegExp(r'\s*[(\[【〔]([^()\[\]【】〔〕]*)[)\]】〕]\s*$'), versionNote: true),
    _NotePattern(RegExp(r'\s+[-–—]\s+(.+)$'), versionNote: true),
    _NotePattern(RegExp(r'\s+(?:feat\.?|ft\.|featuring)\s+(.+)$'), versionNote: false),
  ];

  /// A same-master note: the whole normalised note made of these words with
  /// at least one marker. The digit run is atomic (`(?=(\d+))\1`): JS-style
  /// regexes have no possessive `\d++`, and `(\d+)*` would backtrack
  /// exponentially on a long digit run that doesn't fit.
  static final RegExp _sameMasterNote = () {
    const marker =
        'remaster(?:ed|ing)?|mono|stereo|explicit|(?:album|lp|original)(?:version|ver)|'
        'originalmix|リマスター|リマスタリング|モノラル|ステレオ|アルバム(?:バージョン|ヴァージョン)';
    // Each copy of the filler has its own capture group for its atomic digit run.
    String filler(int group) =>
        '(?=(\\d+))\\$group(?:st|nd|rd|th)?|anniversary|digital(?:ly)?|hd|version|ver|edition|mix|'
        '版|バージョン|ヴァージョン';
    return RegExp('^(?:${filler(1)})*(?:$marker)(?:${filler(2)}|$marker)*\$');
  }();

  /// Several performers in the requested credit. The whole credit is compared too.
  static final RegExp _artistSeparator = RegExp(
    r'\s*(?:[&＆,，、/／;；]|\s(?:feat\.?|ft\.|featuring)\s)\s*',
    caseSensitive: false,
  );

  static const Set<String> _instrumentalNotes = {'inst', 'instver', 'instversion'};
  static const List<String> _instrumentalWords = ['instrumental', 'karaoke', 'offvocal', '伴奏', '纯音乐', 'カラオケ', 'オフボーカル'];
  static const Set<String> _doubtfulAlbumWords = {
    'interview',
    'interviews',
    'karaoke',
    'instrumental',
    'instrumentals',
    'inst',
  };
  static const List<String> _doubtfulAlbumMarks = [
    'offvocal',
    '访谈',
    '专访',
    '采访',
    '伴奏',
    '纯音乐',
    'カラオケ',
    'オフボーカル',
    'インタビュー',
    '인터뷰',
  ];
  static const Set<String> _alternateAlbumWords = {
    'unplugged',
    'concert',
    'concerts',
    'acoustic',
    'remix',
    'remixes',
    'remixed',
    'demo',
    'demos',
    'outtakes',
    'rehearsal',
    'rehearsals',
    'bootleg',
    'covers',
    'tribute',
    'sped',
    'slowed',
    'nightcore',
  };
  static const List<String> _alternateAlbumMarks = [
    '演唱会',
    '现场',
    '音乐会',
    '翻唱',
    '翻自',
    '致敬',
    '混音',
    '加速版',
    '慢速版',
    '降速版',
    'ライブ',
    'ライヴ',
    'コンサート',
    'リミックス',
    '라이브',
    '콘서트',
  ];
  static const Set<String> _liveFollowers = {
    'at',
    'in',
    'from',
    'on',
    'cd',
    'dvd',
    'album',
    'version',
    'ver',
    'edition',
    'recording',
    'recordings',
    'tour',
    'concert',
    'session',
    'sessions',
    'set',
    'show',
  };
}

enum _Fit { original, decorated, doubtful }

enum _Verdict { match, mismatch, unknown }

/// [key] the whole title; [coreKey] without any trailing note; [masterKey]
/// without the same-master notes only.
final class _TitleShape {
  _TitleShape({required this.key, required this.coreKey, required this.masterKey, required this.instrumental});

  final String key;
  final String coreKey;
  final String masterKey;
  final bool instrumental;

  bool get isOriginal => masterKey == coreKey;
}

final class _Note {
  _Note({required this.key, required this.segmentKey, required this.sameMaster});

  final String key;
  final String segmentKey;
  final bool sameMaster;
}

final class _NotePattern {
  _NotePattern(this.regex, {required this.versionNote});

  final RegExp regex;

  /// A version note can be same-master; a `feat.` clause names people and can't.
  final bool versionNote;
}

final class _Name {
  _Name({required this.key, required this.scripts, required this.latinWords});

  final String key;
  final Set<Script> scripts;
  final Set<String> latinWords;
}

final class _Cut {
  _Cut(this.index, this.fit, List<_Name> performers, this.verdict, this.offLength)
    : performerKeys = {for (final p in performers) p.key}.toList();

  final int index;
  final _Fit fit;
  final _Verdict verdict;
  final bool offLength;
  final List<String> performerKeys;
}

// Unicode helpers (no ICU in Dart) -------------------------------------------

final RegExp _letter = RegExp(r'^\p{L}$', unicode: true);
final RegExp _decimalDigit = RegExp(r'^\p{Nd}$', unicode: true);

bool _isLetter(int cp) => _letter.hasMatch(String.fromCharCode(cp));
bool _isLetterOrDigit(int cp) => _isLetter(cp) || _decimalDigit.hasMatch(String.fromCharCode(cp));

/// The writing systems the matcher tells apart (Character.UnicodeScript's,
/// by block range). Hiragana counts as Katakana; [other] is any other script,
/// bucketed by its 128-code-point block in [scriptsOf].
enum Script { latin, greek, cyrillic, armenian, hebrew, arabic, devanagari, thai, han, katakana, hangul, other }

/// The script of a letter, or null for the common / inherited ones (`ー`,
/// digits are not letters). Ranges after Unicode's Scripts.txt for the scripts
/// song credits use.
Script? scriptOf(int cp) {
  bool inAny(List<int> ranges) {
    for (var i = 0; i < ranges.length; i += 2) {
      if (cp >= ranges[i] && cp <= ranges[i + 1]) return true;
    }
    return false;
  }

  if (cp == 0x30FC || cp == 0xFF70 || cp == 0x30FB || cp == 0xFF65) return null;
  if (inAny(const [
    0x41, 0x5A, 0x61, 0x7A, 0xAA, 0xAA, 0xBA, 0xBA, 0xC0, 0xD6, 0xD8, 0xF6, 0xF8, 0x24F, //
    0x250, 0x2AF, 0x1D00, 0x1D7F, 0x1E00, 0x1EFF, 0x2C60, 0x2C7F, 0xA720, 0xA7FF, 0xAB30, 0xAB6F, //
    0xFB00, 0xFB06, 0xFF21, 0xFF3A, 0xFF41, 0xFF5A,
  ])) {
    return Script.latin;
  }
  if (inAny(const [0x370, 0x3FF, 0x1F00, 0x1FFF])) return Script.greek;
  if (inAny(const [0x400, 0x52F, 0x1C80, 0x1C8F, 0x2DE0, 0x2DFF, 0xA640, 0xA69F])) return Script.cyrillic;
  if (inAny(const [0x531, 0x58F, 0xFB13, 0xFB17])) return Script.armenian;
  if (inAny(const [0x590, 0x5FF, 0xFB1D, 0xFB4F])) return Script.hebrew;
  if (inAny(const [0x600, 0x6FF, 0x750, 0x77F, 0x8A0, 0x8FF, 0xFB50, 0xFDFF, 0xFE70, 0xFEFF])) return Script.arabic;
  if (inAny(const [0x900, 0x97F, 0xA8E0, 0xA8FF])) return Script.devanagari;
  if (inAny(const [0xE00, 0xE7F])) return Script.thai;
  if (inAny(const [
    0x2E80, 0x2FDF, 0x3005, 0x3005, 0x3007, 0x3007, 0x3021, 0x3029, 0x3038, 0x303B, //
    0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xF900, 0xFAFF, 0x20000, 0x3FFFF,
  ])) {
    return Script.han;
  }
  if (inAny(const [0x3040, 0x30FF, 0x31F0, 0x31FF, 0x32D0, 0x32FE, 0x3300, 0x3357, 0xFF66, 0xFF9F, 0x1B000, 0x1B16F])) {
    return Script.katakana;
  }
  if (inAny(const [0x1100, 0x11FF, 0x3130, 0x318F, 0xA960, 0xA97F, 0xAC00, 0xD7FF, 0xFFA0, 0xFFDC])) {
    return Script.hangul;
  }
  return Script.other;
}

/// The scripts a name is written in; common / inherited letters don't count.
Set<Script> scriptsOf(String text) {
  final scripts = <Script>{};
  for (final cp in text.runes) {
    if (!_isLetter(cp)) continue;
    final s = scriptOf(cp);
    if (s != null) scripts.add(s);
  }
  return scripts;
}

/// The comparison key: letters and digits only, Latin letters without their diacritics. Input is prepared.
String seamKeyOf(String text) {
  final out = StringBuffer();
  for (final cp in text.runes) {
    if (!_isLetterOrDigit(cp)) continue;
    if (scriptOf(cp) == Script.latin) {
      out.write(baseLatin(cp));
    } else {
      out.writeCharCode(cp);
    }
  }
  return out.toString();
}

/// Runs of Latin letters / ASCII digits, at least two long with a letter.
Set<String> _latinWordsOf(String text) =>
    _latinWordListOf(text).where((w) => w.length >= 2 && w.runes.any((c) => !(c >= 0x30 && c <= 0x39))).toSet();

/// The same words in order, all of them (one-letter and numbers too), for reading album titles.
List<String> _latinWordListOf(String text) {
  final words = <String>[];
  final word = StringBuffer();
  void flush() {
    if (word.isNotEmpty) words.add(word.toString());
    word.clear();
  }

  for (final cp in text.runes) {
    if (_isLetter(cp) && scriptOf(cp) == Script.latin) {
      word.write(baseLatin(cp));
    } else if (cp >= 0x30 && cp <= 0x39) {
      word.writeCharCode(cp);
    } else {
      flush();
    }
  }
  flush();
  return words;
}

bool _isYear(String word) =>
    word.length == 4 && RegExp(r'^\d{4}$').hasMatch(word) && (word.startsWith('19') || word.startsWith('20'));

/// A Latin letter without its diacritic (NFD less the marks), for the letters song titles carry.
String baseLatin(int cp) {
  if (cp < 0x80) return String.fromCharCode(cp);
  return _latinBase[cp] ?? String.fromCharCode(cp);
}

final Map<int, String> _latinBase = () {
  const pairs = <String, String>{
    'ÀÁÂÃÄÅĀĂĄǍǞǠǺȀȂȦȺḀẠẢẤẦẨẪẬẮẰẲẴẶ': 'A',
    'àáâãäåāăąǎǟǡǻȁȃȧḁạảấầẩẫậắằẳẵặ': 'a',
    'ÇĆĈĊČ': 'C',
    'çćĉċč': 'c',
    'ĎḊḌḎḐḒ': 'D',
    'ďḋḍḏḑḓ': 'd',
    'ÈÉÊËĒĔĖĘĚȄȆȨḔḖḘḚḜẸẺẼẾỀỂỄỆ': 'E',
    'èéêëēĕėęěȅȇȩḕḗḙḛḝẹẻẽếềểễệ': 'e',
    'ĜĞĠĢǦǴḠ': 'G',
    'ĝğġģǧǵḡ': 'g',
    'ĤȞḢḤḦḨḪ': 'H',
    'ĥȟḣḥḧḩḫẖ': 'h',
    'ÌÍÎÏĨĪĬĮİǏȈȊḬḮỈỊ': 'I',
    'ìíîïĩīĭįǐȉȋḭḯỉị': 'i',
    'Ĵ': 'J',
    'ĵǰ': 'j',
    'ĶǨḰḲḴ': 'K',
    'ķǩḱḳḵ': 'k',
    'ĹĻĽḶḸḺḼ': 'L',
    'ĺļľḷḹḻḽ': 'l',
    'ḾṀṂ': 'M',
    'ḿṁṃ': 'm',
    'ÑŃŅŇǸṄṆṈṊ': 'N',
    'ñńņňǹṅṇṉṋ': 'n',
    'ÒÓÔÕÖŌŎŐƠǑǪǬȌȎȪȬȮȰṌṎṐṒỌỎỐỒỔỖỘỚỜỞỠỢ': 'O',
    'òóôõöōŏőơǒǫǭȍȏȫȭȯȱṍṏṑṓọỏốồổỗộớờởỡợ': 'o',
    'ṔṖ': 'P',
    'ṕṗ': 'p',
    'ŔŖŘȐȒṘṚṜṞ': 'R',
    'ŕŗřȑȓṙṛṝṟ': 'r',
    'ŚŜŞŠȘṠṢṤṦṨ': 'S',
    'śŝşšșṡṣṥṧṩ': 's',
    'ŢŤȚṪṬṮṰ': 'T',
    'ţťțṫṭṯṱẗ': 't',
    'ÙÚÛÜŨŪŬŮŰŲƯǓǕǗǙǛȔȖṲṴṶṸṺỤỦỨỪỬỮỰ': 'U',
    'ùúûüũūŭůűųưǔǖǘǚǜȕȗṳṵṷṹṻụủứừửữự': 'u',
    'ṼṾ': 'V',
    'ṽṿ': 'v',
    'ŴẀẂẄẆẈ': 'W',
    'ŵẁẃẅẇẉẘ': 'w',
    'ẊẌ': 'X',
    'ẋẍ': 'x',
    'ÝŶŸȲẎỲỴỶỸ': 'Y',
    'ýÿŷȳẏẙỳỵỷỹ': 'y',
    'ŹŻŽẐẒẔ': 'Z',
    'źżžẑẓẕ': 'z',
  };
  final out = <int, String>{};
  pairs.forEach((from, to) {
    for (final cp in from.runes) {
      out[cp] = to;
    }
  });
  return out;
}();

/// The NFKC folds song titles meet (Dart has no Unicode normaliser):
/// full-width ASCII → ASCII, the ideographic and no-break spaces → space,
/// half-width katakana → full-width (with their voicing marks), circled
/// numbers, ligatures, ™ and the ellipsis.
String nfkcLite(String raw) {
  final out = StringBuffer();
  final runes = raw.runes.toList();
  for (var i = 0; i < runes.length; i++) {
    final cp = runes[i];
    if (cp >= 0xFF01 && cp <= 0xFF5E) {
      out.writeCharCode(cp - 0xFEE0);
    } else if (cp == 0x3000 || cp == 0xA0 || cp == 0x2007 || cp == 0x202F) {
      out.write(' ');
    } else if (cp >= 0xFF61 && cp <= 0xFF9F) {
      final next = i + 1 < runes.length ? runes[i + 1] : 0;
      final full = _halfwidthKana[cp - 0xFF61];
      if (next == 0xFF9E && _voicable.contains(full)) {
        out.writeCharCode(full.runes.first + 1);
        i++;
      } else if (next == 0xFF9F && _semiVoicable.contains(full)) {
        out.writeCharCode(full.runes.first + 2);
        i++;
      } else {
        out.write(full);
      }
    } else if (cp >= 0x2460 && cp <= 0x2473) {
      out.write('${cp - 0x2460 + 1}');
    } else if (_compat.containsKey(cp)) {
      out.write(_compat[cp]);
    } else {
      out.writeCharCode(cp);
    }
  }
  return out.toString();
}

/// U+FF61..U+FF9F as NFKC writes them.
const List<String> _halfwidthKana = [
  '。', '「', '」', '、', '・', 'ヲ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ャ', 'ュ', 'ョ', 'ッ', //
  'ー', 'ア', 'イ', 'ウ', 'エ', 'オ', 'カ', 'キ', 'ク', 'ケ', 'コ', 'サ', 'シ', 'ス', 'セ', 'ソ', //
  'タ', 'チ', 'ツ', 'テ', 'ト', 'ナ', 'ニ', 'ヌ', 'ネ', 'ノ', 'ハ', 'ヒ', 'フ', 'ヘ', 'ホ', 'マ', //
  'ミ', 'ム', 'メ', 'モ', 'ヤ', 'ユ', 'ヨ', 'ラ', 'リ', 'ル', 'レ', 'ロ', 'ワ', 'ン', '゙', '゚',
];

const Set<String> _voicable = {
  'カ', 'キ', 'ク', 'ケ', 'コ', 'サ', 'シ', 'ス', 'セ', 'ソ', 'タ', 'チ', 'ツ', 'テ', 'ト', 'ハ', 'ヒ', 'フ', 'ヘ', 'ホ', //
};
const Set<String> _semiVoicable = {'ハ', 'ヒ', 'フ', 'ヘ', 'ホ'};

const Map<int, String> _compat = {
  0xFB00: 'ff',
  0xFB01: 'fi',
  0xFB02: 'fl',
  0xFB03: 'ffi',
  0xFB04: 'ffl',
  0x2122: 'TM',
  0x2026: '...',
  0x2025: '..',
  0x00B2: '2',
  0x00B3: '3',
  0x00B9: '1',
  0x2070: '0',
  0x2074: '4',
  0x2075: '5',
  0x2076: '6',
  0x2077: '7',
  0x2078: '8',
  0x2079: '9',
};

/// The default fold (TraditionalToSimplified): ICU's Traditional → Simplified
/// as a table; text without Han passes straight through.
String traditionalToSimplified(String value) {
  if (!value.runes.any((cp) => scriptOf(cp) == Script.han)) return value;
  final map = _t2s;
  final out = StringBuffer();
  for (final cp in value.runes) {
    final simplified = map[cp];
    if (simplified != null) {
      out.writeCharCode(simplified);
    } else {
      out.writeCharCode(cp);
    }
  }
  return out.toString();
}

final Map<int, int> _t2s = () {
  final from = traditionalToSimplifiedFrom.runes.toList();
  final to = traditionalToSimplifiedTo.runes.toList();
  assert(from.length == to.length);
  return {for (var i = 0; i < from.length; i++) from[i]: to[i]};
}();
