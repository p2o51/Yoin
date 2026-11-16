import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'ai_client.dart';
import 'ai_settings.dart';

/// The About fields' keys and their order (Android SongAboutEntry.CANON_*).
abstract final class AboutFields {
  static const String creationTime = 'creation_time';
  static const String creationLocation = 'creation_location';
  static const String lyricist = 'lyricist';
  static const String composer = 'composer';
  static const String producer = 'producer';
  static const String review = 'review';

  static const List<String> order = [creationTime, creationLocation, lyricist, composer, producer, review];
}

/// One About field the model filled in.
@immutable
final class AboutValue {
  const AboutValue(this.key, this.answer);

  final String key;
  final String answer;

  @override
  bool operator ==(Object other) => other is AboutValue && other.key == key && other.answer == answer;

  @override
  int get hashCode => Object.hash(key, answer);

  @override
  String toString() => 'AboutValue($key, $answer)';
}

/// An About call with what it searched and the raw reply, for 「拿一首歌试试」 (GeminiService.AboutRun).
final class AboutRun {
  const AboutRun({
    required this.values,
    required this.rawText,
    required this.searchKind,
    required this.searchQueries,
    required this.pagesRead,
  });

  final List<AboutValue> values;
  final String rawText;
  final AiSearchKind searchKind;

  /// The queries Yoin ran itself (empty when the model searched, or nobody did).
  final List<String> searchQueries;
  final int pagesRead;
}

/// Title + answer of an Ask (GeminiService.AskAnswer).
@immutable
final class AskAnswer {
  const AskAnswer(this.title, this.answer);

  final String title;
  final String answer;

  @override
  bool operator ==(Object other) => other is AskAnswer && other.title == title && other.answer == answer;

  @override
  int get hashCode => Object.hash(title, answer);
}

/// Yoin's online AI calls (Android GeminiService: About, Ask, lyric
/// translation, Custom's model list). Every call goes to whichever service
/// [AiSettings] names, through [AiClient].
class AiService {
  AiService(this._client);

  final AiClient _client;

  /// The six About fields (Creation Time / Location / Lyricist / Composer /
  /// Producer / Review) in one searched call; only fields the model actually
  /// filled in come back, in [AboutFields.order].
  Future<List<AboutValue>> generateAbout(
    AiSettings ai, {
    required String title,
    required String artist,
    required String album,
    required String targetLanguage,
  }) async => (await runAbout(ai, title: title, artist: artist, album: album, targetLanguage: targetLanguage)).values;

  /// [generateAbout] with what it searched and the raw reply (runCanonicalAbout).
  Future<AboutRun> runAbout(
    AiSettings ai, {
    required String title,
    required String artist,
    required String album,
    required String targetLanguage,
  }) async {
    final queries = [
      '$title $artist ${HttpAiClient.creditsKeywords(title, artist)}',
      if (ai.searchQueries > 1) '$title $artist $album',
    ].take(ai.searchQueries).toList();
    final searched = await _searchFor(ai, queries);
    final prompt = aboutPrompt(title, artist, album, targetLanguage, _searchLead(ai, searched)) + searched.appendix;
    final rawText = await _client.generate(ai, prompt, temperature: 0.8, useSearch: true);
    return AboutRun(
      values: parseAboutResponse(rawText),
      rawText: rawText,
      searchKind: ai.searchKind,
      searchQueries: ai.searchKind == AiSearchKind.external ? queries : const [],
      pagesRead: searched.pages,
    );
  }

  /// Searched free-form Q&A about a song, with a short headline for the question (askAboutSong).
  Future<AskAnswer> askAboutSong(
    AiSettings ai, {
    required String title,
    required String artist,
    required String album,
    required String question,
    required String targetLanguage,
  }) async {
    final searched = await _searchFor(ai, ['$title $artist $question']);
    final prompt =
        askPrompt(title, artist, album, question, targetLanguage, _searchLead(ai, searched)) + searched.appendix;
    final rawText = await _client.generate(ai, prompt, temperature: 0.8, useSearch: true);
    return parseAskResponse(rawText, question);
  }

  /// A heading for an Ask while its answer is on the way (generateAskTitle).
  Future<String> generateAskTitle(
    AiSettings ai, {
    required String title,
    required String artist,
    required String album,
    required String question,
    required String targetLanguage,
  }) async {
    final rawText = await _client.generate(
      ai,
      askTitlePrompt(title, artist, album, question, targetLanguage),
      temperature: 0.2,
      useSearch: false,
    );
    return cleanAskTitle(rawText, question);
  }

  /// Our line index → its translation into [targetLanguage]. No search: the
  /// model must keep the line count and phrasing (translateLyricLines).
  Future<Map<int, String>> translateLyricLines(
    AiSettings ai, {
    required String title,
    required String artist,
    required List<String> lines,
    required String targetLanguage,
  }) async {
    final raw = await _client.generate(
      ai,
      lyricsTranslationPrompt(title, artist, lines, targetLanguage),
      temperature: 0.8,
      useSearch: false,
    );
    final parsed = parseLineTranslations(raw, lines.length);
    if (parsed.isEmpty) throw const AiException('No usable lyric translations in AI response');
    return parsed;
  }

  /// A title for a Memory from the words the user wrote on the album
  /// (generateAlbumMemoryTitle): no search, quick. The one place the user's
  /// own words go to the model (design.md's titling exemption).
  Future<String> generateAlbumMemoryTitle(
    AiSettings ai, {
    required String albumName,
    String? artist,
    int? year,
    required String writingKind,
    required String writingText,
  }) async {
    final raw = await _client.generate(
      ai,
      memoryTitlePrompt(
        albumName: albumName,
        artist: artist,
        year: year,
        writingKind: writingKind,
        writingText: writingText,
      ),
      temperature: 0.2,
      useSearch: false,
    );
    var title = raw.trim();
    if (title.length >= 2 && title.startsWith('"') && title.endsWith('"')) {
      title = title.substring(1, title.length - 1);
    }
    title = title.replaceAll('\n', ' ');
    if (title.isEmpty) throw const AiException('No content in AI response');
    return title;
  }

  /// Yoin's narration and its closing question for a Memory's diary page,
  /// from the listening facts only (generateAlbumMemoryNarration): no search;
  /// past [memoryNarrationTimeout] the local template stands in.
  Future<MemoryNarrationText> generateAlbumMemoryNarration(
    AiSettings ai, {
    required String languageName,
    required List<String> facts,
    String? alreadySaid,
  }) async {
    final raw = await _client.generate(
      ai,
      memoryNarrationPrompt(languageName: languageName, facts: facts, alreadySaid: alreadySaid),
      temperature: 0.4,
      useSearch: false,
      timeout: memoryNarrationTimeout,
    );
    return parseMemoryNarration(raw) ?? (throw const AiException('No usable narration in AI response'));
  }

  /// The model ids Custom's endpoint offers.
  Future<List<String>> listCustomModels(AiSettings ai) =>
      _client.listModels(ai.customFormat, ai.customBaseUrl, ai.customKey);

  /// Runs [queries] when Yoin searches for this service, and turns the pages
  /// into the block appended to the prompt. Built-in or no search: nothing.
  Future<({String appendix, int pages})> _searchFor(AiSettings ai, List<String> queries) async {
    if (ai.searchKind != AiSearchKind.external) return (appendix: '', pages: 0);
    final hits = (await Future.wait(queries.map((q) => _client.search(ai, q)))).expand((h) => h).toList();
    final block = HttpAiClient.contextBlock(hits);
    if (block.isEmpty) return (appendix: '', pages: 0);
    final pages = hits.map((h) => h.url).toSet().length;
    return (
      appendix: '\n\nSearch results:\n$block',
      pages: pages > HttpAiClient.maxPagesInPrompt ? HttpAiClient.maxPagesInPrompt : pages,
    );
  }

  static String _searchLead(AiSettings ai, ({String appendix, int pages}) searched) {
    if (ai.searchKind == AiSearchKind.builtIn) return 'Use the search tool to find accurate information.';
    if (searched.pages > 0) return 'Use the search results provided below to find accurate information.';
    return 'Answer from what you reliably know; write "N/A" for anything you are not sure of.';
  }
}

/// GeminiService.buildPrompt, word for word.
String aboutPrompt(String title, String artist, String album, String targetLanguage, String searchLead) =>
    '''
Search for and provide detailed information about the following song:

Song: $title
Artist: $artist
Album: $album

$searchLead Write all human-readable
answers in $targetLanguage. Respond strictly in the following tagged format.
If any information cannot be found, write "N/A" inside the tag.

[CREATION_TIME]
When the song was created/recorded
[/CREATION_TIME]

[CREATION_LOCATION]
Where the song was created/recorded
[/CREATION_LOCATION]

[LYRICIST]
Lyricist(s)
[/LYRICIST]

[COMPOSER]
Composer(s)
[/COMPOSER]

[PRODUCER]
Producer(s)
[/PRODUCER]

[REVIEW]
A 2-4 sentence analysis of the song including its background, musical characteristics, and cultural significance. Be insightful and professional but not overly academic.
[/REVIEW]

Respond strictly in the tagged format above. Every field must include its corresponding tags.''';

/// GeminiService.buildAskPrompt, word for word.
String askPrompt(
  String title,
  String artist,
  String album,
  String question,
  String targetLanguage,
  String searchLead,
) =>
    '''
Answer the following question about the song "$title" from the album "$album" by $artist.
$searchLead
Write the title and answer in $targetLanguage.

Respond strictly in the following tagged format:

[TITLE]
A concise headline that summarises the question — no more than 8 words, no trailing punctuation. This is shown as the visual heading above your answer, so it should feel like a title, not a full sentence.
[/TITLE]

[ANSWER]
The detailed answer. No more than 120 words. Plain prose only — no citation markers, headers, or bullet points.
[/ANSWER]

Question: $question''';

/// GeminiService.buildAskTitlePrompt, word for word.
String askTitlePrompt(String title, String artist, String album, String question, String targetLanguage) =>
    '''
Write a concise visual heading for an in-progress Gemini answer about this song.
Write the heading in $targetLanguage.

Song: $title
Artist: $artist
Album: $album
Question: $question

Return only the heading. No more than 8 words. No trailing punctuation. Do not
answer the question.''';

/// GeminiService.buildLyricsTranslationPrompt, word for word.
String lyricsTranslationPrompt(String title, String artist, List<String> lines, String targetLanguage) {
  final indexed = [for (var i = 0; i < lines.length; i++) '[$i] ${lines[i]}'].join('\n');
  return '''
Translate these lyric lines into $targetLanguage.

Song: $title
Artist: $artist

Preserve line count and meaning. Keep imagery natural, concise, and singable.
Return strictly one tagged block per source line:

[L0]translation for line 0[/L0]
[L1]translation for line 1[/L1]

Lines:
$indexed''';
}

/// Narration + closing question for a Memory (GeminiService.MemoryNarrationText).
@immutable
final class MemoryNarrationText {
  const MemoryNarrationText(this.narration, this.question);

  final String narration;
  final String question;

  @override
  bool operator ==(Object other) =>
      other is MemoryNarrationText && other.narration == narration && other.question == question;

  @override
  int get hashCode => Object.hash(narration, question);
}

/// The Memories narration prompt's version; part of the narration cache key
/// (MEMORY_NARRATION_PROMPT_VERSION). Bump it with every prompt change.
const int memoryNarrationPromptVersion = 2;

/// The Memories deck waits on the narration this long at most.
const Duration memoryNarrationTimeout = Duration(seconds: 8);

/// GeminiService.buildMemoryTitlePrompt, word for word.
String memoryTitlePrompt({
  required String albumName,
  String? artist,
  int? year,
  required String writingKind,
  required String writingText,
}) {
  final artistLine = (artist == null || artist.trim().isEmpty) ? 'Unknown artist' : artist;
  final yearLine = year?.toString() ?? 'Unknown year';
  return '''
You are titling a private music-journal entry. The user wrote the following
$writingKind about an album; give it a short title.

Album: $albumName
Artist: $artistLine
Year: $yearLine

Their $writingKind:
$writingText

Write ONE title that captures the heart of what they wrote, drawing on what
you know about this album's sound and background where it sharpens the title.
Same language as their text. At most 14 CJK characters or 34 latin characters.
No quotes, no emoji, no trailing period, no line break.
Output only the title itself.''';
}

/// GeminiService.buildMemoryNarrationPrompt (v2, the prototype's voice), word for word.
String memoryNarrationPrompt({required String languageName, required List<String> facts, String? alreadySaid}) {
  final factLines = facts.map((f) => '- $f').join('\n');
  final said = alreadySaid == null || alreadySaid.trim().isEmpty
      ? ''
      : '\nThe title already on the page reads: "$alreadySaid". Do not repeat any fact it states.\n';
  return '''
You are Yoin, a music app. You write a few words at the top of a listener's
private diary page for one album, speaking to the listener directly.

Listening facts recorded in the app (the only things you may mention):
$factLines
$said
Rules:
- Write in $languageName.
- NARRATION: one or two short sentences, second person, past tense, built only
  from the facts above. Pick the one or two facts that say most about how they
  listened; do not list them all.
- QUESTION: one short question that follows directly from the last fact you
  stated, so the listener wants to write their own words. It ends with "?" in
  English or "？" in Chinese.
- Never judge, praise or describe the music, the artist, the album or its
  sound. Never guess at feelings, moods or reasons.
- Never quote, paraphrase or allude to anything the listener wrote. You have
  not read it.
- Never name the mechanism: no words such as "memory", "memories",
  "remember", "记忆", "回忆".
- Keep track names, months and relative dates as given.
- No quotation marks around your text, no emoji, no hashtags, no line breaks.

The voice, from other albums (never reuse their facts):
[NARRATION]37 plays since March, the last one two days ago.[/NARRATION]
[QUESTION]You gave it a 9.5. What earned it?[/QUESTION]
[NARRATION]你跨了三个季节回来听，最高分是《Undertow》的 9.0。[/NARRATION]
[QUESTION]那整张专辑呢？[/QUESTION]

Output exactly this and nothing else:
[NARRATION]...[/NARRATION]
[QUESTION]...[/QUESTION]''';
}

/// `[NARRATION]…[/NARRATION]` + `[QUESTION]…[/QUESTION]`; null when either is
/// missing or blank, or the question isn't one (parseMemoryNarration).
MemoryNarrationText? parseMemoryNarration(String rawText) {
  String? clean(String? text) {
    if (text == null) return null;
    var t = text.replaceAll(RegExp(r'\s*\n\s*'), ' ').trim();
    const quotes = '"“”';
    while (t.isNotEmpty && quotes.contains(t[0])) {
      t = t.substring(1);
    }
    while (t.isNotEmpty && quotes.contains(t[t.length - 1])) {
      t = t.substring(0, t.length - 1);
    }
    t = t.trim();
    return t.isEmpty ? null : t;
  }

  final narration = clean(extractTagContent(rawText, 'NARRATION'));
  final question = clean(extractTagContent(rawText, 'QUESTION'));
  if (narration == null || question == null) return null;
  if (!question.endsWith('?') && !question.endsWith('？')) return null;
  return MemoryNarrationText(narration, question);
}

/// The cached form of a narration; [parseMemoryNarration] reads it back.
String encodeMemoryNarration(MemoryNarrationText text) =>
    '[NARRATION]${text.narration}[/NARRATION]\n[QUESTION]${text.question}[/QUESTION]';

final RegExp _tagMarker = RegExp(r'\[\s*/?\s*[A-Z][A-Z0-9_]*\s*\]', caseSensitive: false);
final RegExp _lineTranslation = RegExp(r'\[\s*L(\d+)\s*\]([\s\S]*?)\[\s*/\s*L\1\s*\]', caseSensitive: false);
final RegExp _leadingLineMarker = RegExp(r'^(?:[-*•]\s*)?(?:(?:[\[【(]\s*\d+\s*[\]】)])(?:[.)：:、])?|\d+[.)：:、])\s*');

/// Leftover `[TAG]` / `[/TAG]` markers gone from prose (stripTagMarkers).
String stripTagMarkers(String text) => text.replaceAll(_tagMarker, '').trim();

/// The text between `[TAG]` and `[/TAG]`, tolerant of case, spaces inside the
/// brackets and a missing closing tag (read to the end). Null when the open
/// tag is missing or the content blank (extractTagContent).
String? extractTagContent(String text, String tag) {
  final escaped = RegExp.escape(tag);
  final open = RegExp('\\[\\s*$escaped\\s*\\]', caseSensitive: false).firstMatch(text);
  if (open == null) return null;
  final close = RegExp('\\[\\s*/\\s*$escaped\\s*\\]', caseSensitive: false).firstMatch(text.substring(open.end));
  final end = close == null ? text.length : open.end + close.start;
  final content = stripTagMarkers(text.substring(open.end, end));
  return content.isEmpty ? null : content;
}

/// The About fields with real content, in [AboutFields.order]; "N/A",
/// "null" and 「无法获取」 count as empty (parseTaggedResponse).
List<AboutValue> parseAboutResponse(String rawText) {
  String? extract(String tag) {
    final content = extractTagContent(rawText, tag);
    if (content == null) return null;
    final lower = content.toLowerCase();
    if (lower == 'n/a' || lower == 'null' || content == '无法获取') return null;
    return content;
  }

  const tags = {
    AboutFields.creationTime: 'CREATION_TIME',
    AboutFields.creationLocation: 'CREATION_LOCATION',
    AboutFields.lyricist: 'LYRICIST',
    AboutFields.composer: 'COMPOSER',
    AboutFields.producer: 'PRODUCER',
    AboutFields.review: 'REVIEW',
  };
  return [
    for (final key in AboutFields.order)
      if (extract(tags[key]!) case final value?) AboutValue(key, value),
  ];
}

/// `[TITLE]` and `[ANSWER]`; plain prose falls back to the question as the
/// title and the reply without tag markers as the answer (parseAskResponse).
AskAnswer parseAskResponse(String rawText, String fallbackQuestion) {
  final title = extractTagContent(rawText, 'TITLE');
  final answer = extractTagContent(rawText, 'ANSWER');
  if (title != null && answer != null) return AskAnswer(title, answer);
  if (answer != null) return AskAnswer(fallbackQuestion.trim(), answer);
  if (title != null) return AskAnswer(title, stripTagMarkers(rawText));
  return AskAnswer(fallbackQuestion.trim(), stripTagMarkers(rawText));
}

/// The first line, unquoted, without its closing punctuation; else the question (cleanAskTitle).
String cleanAskTitle(String rawText, String fallbackQuestion) {
  final line = const LineSplitter().convert(stripTagMarkers(rawText)).where((l) => l.trim().isNotEmpty).firstOrNull;
  if (line == null) return fallbackQuestion.trim();
  const quotes = '"\'“”‘’';
  var cleaned = line.trim();
  while (cleaned.isNotEmpty && quotes.contains(cleaned[0])) {
    cleaned = cleaned.substring(1);
  }
  while (cleaned.isNotEmpty && quotes.contains(cleaned[cleaned.length - 1])) {
    cleaned = cleaned.substring(0, cleaned.length - 1);
  }
  for (final suffix in const ['.', '?', '!', '。', '？', '！']) {
    if (cleaned.endsWith(suffix)) cleaned = cleaned.substring(0, cleaned.length - suffix.length);
  }
  cleaned = cleaned.trim();
  return cleaned.isEmpty ? fallbackQuestion.trim() : cleaned;
}

/// The tagged `[Ln]…[/Ln]` blocks; failing those, plain lines when there are exactly as many (parseLineTranslations).
Map<int, String> parseLineTranslations(String rawText, int lineCount) {
  final tagged = <int, String>{};
  for (final m in _lineTranslation.allMatches(rawText)) {
    final index = int.tryParse(m.group(1)!);
    final text = cleanLineTranslation(m.group(2)!);
    if (index != null && index >= 0 && index < lineCount && text.trim().isNotEmpty) tagged[index] = text;
  }
  if (tagged.isNotEmpty) return tagged;
  final plain = const LineSplitter().convert(rawText).map(cleanLineTranslation).where((l) => l.isNotEmpty).toList();
  if (plain.length != lineCount) return const {};
  return {
    for (var i = 0; i < plain.length; i++)
      if (cleanLineTranslation(plain[i]).trim().isNotEmpty) i: cleanLineTranslation(plain[i]),
  };
}

/// Leftover tag markers and leading numbering ("1.", "[2]", "•") gone (cleanLineTranslation).
String cleanLineTranslation(String text) {
  var cleaned = stripTagMarkers(text);
  for (var i = 0; i < 3; i++) {
    cleaned = cleaned.replaceFirst(_leadingLineMarker, '').trim();
  }
  return cleaned;
}

final aiServiceProvider = Provider<AiService>((ref) => AiService(ref.watch(aiClientProvider)));
