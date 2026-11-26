// The album About prompts and reply parser (owner + Carbon 2026-11-25). Dart
// port of :shared `albumabout/AlbumAboutPrompts.kt`; every string is pinned
// by shared/src/commonTest/resources/golden/album-about.json. Reached through
// `YoinAlbumAbout`.
import 'album_about_models.dart';
import 'kt_text.dart';
import 'lenient_json.dart';

abstract final class AlbumAboutPrompts {
  /// Tracks listed in the prompt.
  static const maxPromptTracks = 40;

  /// The service's album notes quoted in the prompt, at most this long.
  static const maxNotesChars = 1500;

  /// Names kept per credit item.
  static const maxNames = 4;

  static String searchLead(bool builtInSearch, int searchedPages) {
    if (builtInSearch) return 'Use the search tool to find accurate information.';
    if (searchedPages > 0) return 'Use the search results provided below to find accurate information.';
    return 'Answer from what you reliably know; write "N/A" for anything you are not sure of.';
  }

  static List<String> searchQueries(AlbumAboutGrounding grounding, int count) {
    final queries = <String>[
      '${grounding.album} ${grounding.artist} ${_creditsKeywords(grounding.album + grounding.artist)}',
      if (count > 1)
        '${grounding.album} ${grounding.artist} ${_backgroundKeywords(grounding.album + grounding.artist)}',
    ];
    final take = count < 1 ? 1 : count;
    return queries.length <= take ? queries : queries.sublist(0, take);
  }

  static String buildAboutPrompt(AlbumAboutGrounding grounding, String targetLanguage, String searchLead) {
    if (grounding.serviceNotes != null) return _buildCreditsOnlyPrompt(grounding, targetLanguage, searchLead);
    return ktTrimIndent('''

Write the About section of one album for a music app: its album-level credits and an editorial
background paragraph, the way Apple Music's album notes read.

${groundingLines(grounding)}

$searchLead The facts above come from the music service and are correct for this release:
build on them and never contradict them.

Respond strictly in the tagged format below, with nothing outside the tags.

${_creditsBlock(targetLanguage)}

[ABOUT]
One objective paragraph in $targetLanguage, ${blurbLength(targetLanguage)}: the album's place in the
artist's career, its sound or concept, and the one behind-the-scenes fact most worth knowing.
Editorial and factual, like Apple Music's album notes. No hype or superlatives, no track-by-track
listing, no rating, no second person, no headings, lists or citation markers. Plain prose.
If you know too little about this album to write it without guessing, write N/A.
[/ABOUT]
            ''');
  }

  static String _buildCreditsOnlyPrompt(AlbumAboutGrounding grounding, String targetLanguage, String searchLead) =>
      ktTrimIndent('''

Find the album-level credits of one album for a music app.

${groundingLines(grounding)}

$searchLead The facts above come from the music service and are correct for this release:
build on them and never contradict them.

Respond strictly with the tagged block below, with nothing outside the tags.

${_creditsBlock(targetLanguage)}
    ''');

  static String _creditsBlock(String targetLanguage) => ktTrimIndent('''

[CREDITS]
One JSON object with exactly these keys. Each value is null or an object whose "confidence" is
"high" or "medium":
{
  "label": {"names": ["record label"], "confidence": "high"},
  "producers": {"names": ["producer"], "confidence": "high"},
  "mastering": {"names": ["mastering engineer"], "confidence": "high"},
  "mixing": {"names": ["mixing engineer"], "confidence": "high"},
  "recorded": {"time": "when it was recorded", "studios": ["studio, city"], "confidence": "high"},
  "source": {"work": "the film, game, anime, show or artist it belongs to", "confidence": "high"},
  "first_released": {"date": "when the original album first came out", "confidence": "high"}
}
Rules for CREDITS:
- Album-level credits of this release only: not one track's, not another edition's.
- "high" = stated in the search results or the album's liner notes; "medium" = well documented
  elsewhere. Anything less certain is null. Never guess, and never infer from the artist's other albums.
- Unknown = null. Never write "N/A", "unknown", "various" or any placeholder inside a value.
- "label": null when the service already gave the record label above.
- "recorded": "time" as a month and year or a range; "studios" as studio names with their city.
  Either may be null inside the object; the whole item is null when neither is known.
- "source": only for a soundtrack, music from a game, anime, film or show, or a tribute album;
  otherwise null.
- "first_released": only when this release is a reissue, remaster, or deluxe or anniversary edition
  of an earlier album; otherwise null.
- Names exactly as credited, in their original script. Dates in $targetLanguage.
[/CREDITS]
    ''');

  static String buildAskPrompt(
    AlbumAboutGrounding grounding,
    String question,
    String targetLanguage,
    String searchLead,
  ) => ktTrimIndent('''

Answer the following question about the album "${grounding.album}" by ${grounding.artist}.

${groundingLines(grounding)}

$searchLead
Write the title and answer in $targetLanguage.

Respond strictly in the following tagged format:

[TITLE]
A concise headline that summarises the question — no more than 8 words, no trailing punctuation. This is shown as the visual heading above your answer, so it should feel like a title, not a full sentence.
[/TITLE]

[ANSWER]
The detailed answer. No more than 120 words. Plain prose only — no citation markers, headers, or bullet points.
[/ANSWER]

Question: $question
    ''');

  static final _whitespaceRun = RegExp('$javaSpace+');

  /// The service's facts, one per line; what it doesn't say is left out.
  static String groundingLines(AlbumAboutGrounding grounding) {
    final lines = <String>['Album: ${grounding.album}', 'Artist: ${grounding.artist}'];
    final version = grounding.version;
    if (version != null && !ktIsBlank(version)) lines.add('Edition: $version');
    if (grounding.releaseType != null) lines.add('Release type: ${grounding.releaseType}');
    if (grounding.releaseDate != null) lines.add("This release's date: ${grounding.releaseDate}");
    final original = grounding.originalReleaseDate;
    if (original != null && original != grounding.releaseDate) lines.add('Original release date: $original');
    if (grounding.labels.isNotEmpty) lines.add('Record label (from the service): ${grounding.labels.join(', ')}');
    final genre = grounding.genre;
    if (genre != null && !ktIsBlank(genre)) lines.add('Genre: $genre');
    final duration = grounding.totalDurationSec;
    if (duration != null && duration > 0) lines.add('Length: ${duration ~/ 60} min');
    final notes = grounding.serviceNotes;
    if (notes != null) {
      lines.add('Album notes (from the service): ${ktTake(notes.replaceAll(_whitespaceRun, ' '), maxNotesChars)}');
    }
    if (grounding.tracks.isNotEmpty) {
      lines.add('Tracks (${grounding.tracks.length}):');
      final shown = grounding.tracks.length <= maxPromptTracks
          ? grounding.tracks
          : grounding.tracks.sublist(0, maxPromptTracks);
      for (var i = 0; i < shown.length; i++) {
        lines.add('${i + 1}. ${shown[i]}');
      }
      if (grounding.tracks.length > maxPromptTracks) lines.add('…');
    }
    return lines.join('\n');
  }

  static String blurbLength(String targetLanguage) {
    if (isChineseTargetLanguage(targetLanguage)) return '180 to 240 Chinese characters';
    if (targetLanguage == 'Japanese' || targetLanguage == 'Korean') return 'about as long as 120 to 160 English words';
    return '120 to 160 words';
  }

  /// :shared `String.isChineseTargetLanguage()` (data/lyrics/NativeTranslation.kt).
  static bool isChineseTargetLanguage(String targetLanguage) {
    final normalized = ktTrim(targetLanguage).toLowerCase();
    return normalized == 'chinese' ||
        normalized.contains('中文') ||
        normalized.contains('汉语') ||
        normalized.contains('simplified chinese') ||
        normalized.contains('traditional chinese') ||
        normalized.contains('chinese (');
  }

  static bool _anyIn(String text, int from, int to) => text.codeUnits.any((c) => c >= from && c <= to);

  static String _creditsKeywords(String text) {
    if (_anyIn(text, 0xAC00, 0xD7A3)) return '앨범 크레딧 프로듀서 레이블';
    if (_anyIn(text, 0x3040, 0x30FF)) return 'アルバム クレジット プロデューサー レーベル';
    if (_anyIn(text, 0x4E00, 0x9FFF)) return '專輯 製作人 唱片公司 錄音';
    return 'album credits producer label recorded';
  }

  static String _backgroundKeywords(String text) {
    if (_anyIn(text, 0xAC00, 0xD7A3)) return '앨범 리뷰 배경';
    if (_anyIn(text, 0x3040, 0x30FF)) return 'アルバム 解説 制作背景';
    if (_anyIn(text, 0x4E00, 0x9FFF)) return '專輯 背景 樂評';
    return 'album review background';
  }

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  static AlbumAboutReply parseAboutResponse(String rawText) => AlbumAboutReply(
    credits: parseCredits(_extractTag(rawText, 'CREDITS')),
    blurb: cleanBlurb(_extractTag(rawText, 'ABOUT')),
    answered: _hasTag(rawText, 'CREDITS') || _hasTag(rawText, 'ABOUT'),
  );

  static RegExp _open(String tag) => RegExp('\\[$javaSpace*$tag$javaSpace*]', caseSensitive: false);

  static RegExp _close(String tag) => RegExp('\\[$javaSpace*/$javaSpace*$tag$javaSpace*]', caseSensitive: false);

  static bool _hasTag(String text, String tag) => _open(tag).hasMatch(text);

  /// The [CREDITS] JSON as [AlbumAiCredits]: items below "medium"
  /// confidence, without a confidence, or holding only placeholders are
  /// dropped. Tolerates a ```json fence and prose around the object;
  /// unreadable JSON is no credits at all.
  static AlbumAiCredits parseCredits(String? text) {
    if (text == null) return AlbumAiCredits.none;
    final start = text.indexOf('{');
    final end = text.lastIndexOf('}');
    if (start < 0 || end <= start) return AlbumAiCredits.none;
    Object? parsed;
    try {
      parsed = parseLenientJson(text.substring(start, end + 1));
    } on LenientJsonException {
      return AlbumAiCredits.none;
    }
    if (parsed is! Map<String, Object?>) return AlbumAiCredits.none;
    final root = parsed;
    Map<String, Object?>? item(String key) {
      final value = root[key];
      return value is Map<String, Object?> && _confident(value) ? value : null;
    }

    List<String> namesOf(String key) {
      final it = item(key);
      return it == null ? const [] : _names(it['names']);
    }

    final recorded = item('recorded');
    final source = item('source');
    final first = item('first_released');
    return AlbumAiCredits(
      label: namesOf('label'),
      producers: namesOf('producers'),
      mastering: namesOf('mastering'),
      mixing: namesOf('mixing'),
      recordedTime: recorded == null ? null : _text(recorded['time']),
      recordedStudios: recorded == null ? const [] : _names(recorded['studios']),
      source: source == null ? null : _text(source['work']),
      firstReleased: first == null ? null : _text(first['date']),
    );
  }

  static bool _confident(Map<String, Object?> item) {
    final value = item['confidence'];
    if (value is! JsonLiteral) return false;
    final confidence = ktTrim(value.content).toLowerCase();
    return confidence == 'high' || confidence == 'medium';
  }

  static final _nameSeparators = RegExp('$javaSpace*[,、;；]$javaSpace*');

  static List<String> _names(Object? element) {
    final List<String> values;
    if (element is List) {
      values = [for (final e in element) ?_text(e)];
    } else if (element is JsonLiteral) {
      // A model that writes "A, B" instead of ["A", "B"] still gets its names in.
      values = _text(element)?.split(_nameSeparators) ?? const [];
    } else {
      values = const [];
    }
    final cleaned = ktDistinct([for (final v in values) ?cleanValue(v)]);
    return cleaned.length <= maxNames ? cleaned : cleaned.sublist(0, maxNames);
  }

  static String? _text(Object? element) =>
      element is JsonLiteral && element.isString ? cleanValue(element.content) : null;

  static const _placeholders = {
    'n/a', 'na', 'null', 'none', 'unknown', 'not known', 'various', 'various artists', 'tbd', '-', '—', '?', //
    '未知', '不详', '不明', '无', '暂无', '無', '不詳', '未知的',
  };

  /// A value as shown, or null when it is blank or a placeholder ("N/A", "unknown", "未知"…).
  static String? cleanValue(String value) {
    final text = ktTrim(ktTrimChars(ktTrim(value.replaceAll(_whitespaceRun, ' ')), '"“”'));
    if (text.isEmpty) return null;
    return _placeholders.contains(ktTrimChars(text.toLowerCase(), '.。', start: false)) ? null : text;
  }

  /// One paragraph: line breaks folded (no space between CJK sentences), markdown bold and "N/A" dropped.
  static String? cleanBlurb(String? text) {
    if (text == null) return null;
    final raw = ktTrim(text);
    if (raw.isEmpty) return null;
    if (cleanValue(raw) == null) return null;
    final lines = ktLines(raw.replaceAll('**', '')).map(ktTrim).where((l) => l.isNotEmpty);
    final joined = StringBuffer();
    var last = -1;
    for (final line in lines) {
      if (joined.isNotEmpty && !(_isCjk(last) && _isCjk(line.codeUnitAt(0)))) joined.write(' ');
      joined.write(line);
      last = line.codeUnitAt(line.length - 1);
    }
    final out = joined.toString();
    return out.isEmpty ? null : out;
  }

  /// The paragraph's CJK (narrower than the credits line's).
  static bool _isCjk(int c) => (c >= 0x3000 && c <= 0x9FFF) || (c >= 0xFF00 && c <= 0xFFEF);

  static String? _extractTag(String text, String tag) {
    final open = _open(tag).firstMatch(text);
    if (open == null) return null;
    final close = _close(tag).firstMatch(text.substring(open.end));
    final body = text.substring(open.end, close == null ? text.length : open.end + close.start);
    final trimmed = ktTrim(body);
    return trimmed.isEmpty ? null : trimmed;
  }
}
