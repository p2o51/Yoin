// The facade over the album About core (docs/design.md, album page › 专辑「关于」;
// Android 0.6.3, PR #326): the prompts, the reply parser, the stored credits
// format, the credits line and its line-break model, Apple Music's editorial
// notes, and the AI service's name in copy. Dart port of :shared
// `albumabout/YoinAlbumAbout.kt`, member for member; answers exactly as
// shared/src/commonTest/resources/golden/album-about.json says
// (test/data/albumabout/album_about_golden_test.dart).
//
// The platform keeps the AI call itself, storage and sync (`album_about`),
// the UI and the strings.
import 'album_about_models.dart';
import 'album_about_prompts.dart';
import 'album_credits.dart';
import 'editorial_notes.dart';
import 'kt_text.dart';

abstract final class YoinAlbumAbout {
  // ── stored rows ──────────────────────────────────────────────────

  /// Row kinds and canonical keys of the album About rows (synced as is).
  static const kindCanonical = 'canonical';
  static const kindAsk = 'ask';
  static const canonCredits = 'credits';
  static const canonBlurb = 'blurb';

  /// Tracks listed in the prompt.
  static const maxPromptTracks = AlbumAboutPrompts.maxPromptTracks;

  /// The service's album notes quoted in the prompt, at most this many UTF-16 units.
  static const maxNotesChars = AlbumAboutPrompts.maxNotesChars;

  /// Names kept per credit item.
  static const maxNames = AlbumAboutPrompts.maxNames;

  // ── prompts ──────────────────────────────────────────────────────

  /// The search queries for one album (the second only when the service allows two).
  static List<String> searchQueries(AlbumAboutGrounding grounding, int count) =>
      AlbumAboutPrompts.searchQueries(grounding, count);

  /// The search lead every searched prompt carries.
  static String searchLead(bool builtInSearch, int searchedPages) =>
      AlbumAboutPrompts.searchLead(builtInSearch, searchedPages);

  /// The About prompt, before the platform appends its search results; the
  /// credits-only prompt when the service wrote the notes.
  static String aboutPrompt(AlbumAboutGrounding grounding, String targetLanguage, String searchLead) =>
      AlbumAboutPrompts.buildAboutPrompt(grounding, targetLanguage, searchLead);

  /// True when [aboutPrompt] asks for the credits only (the service wrote the paragraph).
  static bool isCreditsOnly(AlbumAboutGrounding grounding) => grounding.serviceNotes != null;

  /// The question prompt; the reply is the song Ask's `[TITLE]` / `[ANSWER]` format.
  static String askPrompt(AlbumAboutGrounding grounding, String question, String targetLanguage, String searchLead) =>
      AlbumAboutPrompts.buildAskPrompt(grounding, question, targetLanguage, searchLead);

  /// The service's facts as the prompts list them, one per line.
  static String groundingLines(AlbumAboutGrounding grounding) => AlbumAboutPrompts.groundingLines(grounding);

  /// How long the paragraph should be in [targetLanguage].
  static String blurbLength(String targetLanguage) => AlbumAboutPrompts.blurbLength(targetLanguage);

  // ── replies ──────────────────────────────────────────────────────

  /// One About reply as read: confident credits, the paragraph, and whether it was an answer at all.
  static AlbumAboutReply parseReply(String rawText) => AlbumAboutPrompts.parseAboutResponse(rawText);

  /// What to store from one About call: [parseReply], without the AI's
  /// paragraph when the service's own notes are the paragraph. Not
  /// [AlbumAboutReply.answered] = a failure: store nothing.
  static AlbumAboutReply storedReply(String rawText, {required bool hasServiceNotes}) {
    final reply = parseReply(rawText);
    return hasServiceNotes ? reply.withoutBlurb() : reply;
  }

  /// The `[CREDITS]` JSON alone (fences and prose around it tolerated).
  static AlbumAiCredits parseCredits(String? text) => AlbumAboutPrompts.parseCredits(text);

  /// A value as shown, or null when it is blank or a placeholder.
  static String? cleanValue(String value) => AlbumAboutPrompts.cleanValue(value);

  /// One paragraph: line breaks folded (no space between CJK sentences), markdown bold and "N/A" dropped.
  static String? cleanBlurb(String? text) => AlbumAboutPrompts.cleanBlurb(text);

  /// The credits as stored in the `canonical` / `credits` row.
  static String encodeCredits(AlbumAiCredits credits) => credits.encode();

  static AlbumAiCredits decodeCredits(String? text) => AlbumAiCredits.decode(text);

  static final _featSuffix = RegExp('$javaSpace*\\(feat\\.[^)]*\\)', caseSensitive: false);
  static final _whitespace = RegExp('$javaSpace+');

  /// The key of an ask row: the question trimmed, lower-cased, "(feat. …)"
  /// dropped, spaces folded (the song About's key, so asking again replaces the row).
  static String questionKey(String question) =>
      ktTrim(ktTrim(question).toLowerCase().replaceAll(_featSuffix, '').replaceAll(_whitespace, ' '));

  /// One album's stored rows as the page reads them.
  static AlbumAboutContent content(List<AlbumAboutRow> rows) {
    final canonical = <String, AlbumAboutRow>{
      for (final row in rows)
        if (row.kind == kindCanonical) row.entryKey: row,
    };
    final asks = [
      for (final row in rows)
        if (row.kind == kindAsk && !ktIsBlank(row.answerText)) row,
    ];
    // Kotlin's sortedByDescending is stable: equal times keep their order.
    final order = List<int>.generate(asks.length, (i) => i)
      ..sort((a, b) {
        final byTime = asks[b].createdAt.compareTo(asks[a].createdAt);
        return byTime != 0 ? byTime : a.compareTo(b);
      });
    final blurb = canonical[canonBlurb]?.answerText;
    final trimmedBlurb = blurb == null ? null : ktTrim(blurb);
    return AlbumAboutContent(
      credits: AlbumAiCredits.decode(canonical[canonCredits]?.answerText),
      blurb: trimmedBlurb == null || trimmedBlurb.isEmpty ? null : trimmedBlurb,
      asks: [
        for (final i in order)
          AlbumAboutAsk(
            question: _notBlank(asks[i].promptText) ?? asks[i].entryKey,
            title: _notBlank(asks[i].titleText),
            answer: ktTrim(asks[i].answerText),
          ),
      ],
      generated: canonical.isNotEmpty,
    );
  }

  static String? _notBlank(String? text) => text == null || ktIsBlank(text) ? null : text;

  // ── the credits line ─────────────────────────────────────────────

  /// Between items: a no-break space, the dot, a breakable space.
  static const creditItemSeparator = AlbumCredits.itemSeparator;

  /// The line's items for one album, in order: the service's labels before
  /// the AI's; the service's first-release date before the AI's, either only
  /// for a reissue. Dates are ISO ("2014-05-20", "2014-05", "2014").
  static List<AlbumCreditItem> creditItems({
    required List<String> sourceLabels,
    String? releaseDate,
    String? originalReleaseDate,
    required AlbumAiCredits ai,
  }) => AlbumCredits.items(
    sourceLabels: sourceLabels,
    releaseDate: releaseDate,
    originalReleaseDate: originalReleaseDate,
    ai: ai,
  );

  /// The line, or null when nothing is known. [dateText] is the
  /// service-dated first release in the platform's date style; null shows the ISO date.
  static AlbumCreditsLine? creditsLine(List<AlbumCreditItem> items, AlbumCreditLabels labels, String? dateText) =>
      AlbumCredits.line(items, labels, dateText);

  /// One item's value (no label); null when it holds nothing to show.
  static String? creditValue(AlbumCreditItem item, AlbumCreditLabels labels, String? dateText) =>
      AlbumCredits.value(item, labels, dateText);

  /// One item as the line writes it: the label glued to its value, the value's own break rules.
  static String creditItemText(String label, String value) => AlbumCredits.itemText(label, value);

  /// A label that never breaks: no-break spaces, word joiners between CJK characters.
  static String unbreakable(String label) => AlbumCredits.unbreakable(label);

  /// A line's text cut where it may break.
  static List<String> creditSegments(String text) => AlbumCredits.segments(text);

  // ── Apple Music's notes ──────────────────────────────────────────

  /// Apple Music's `editorialNotes`: `standard`, else `short`, as plain text; null when neither has words.
  static String? editorialNotes(String? standard, String? short) => EditorialNotes.pick(standard, short);

  /// Light HTML as plain text (breaks and paragraphs kept, tags dropped, entities decoded); null when empty.
  static String? plainTextFromHtml(String html) => EditorialNotes.plainTextFromHtml(html);

  // ── the AI service's name in copy ────────────────────────────────

  /// Copy with the AI service's name filled in (`%1$s`), the space before or
  /// after a Chinese name dropped: "问 %1$s" + 千问 → "问千问", + Gemini → "问 Gemini".
  static String aiCopy(String template, String name) => tightenCjkSpaces(template.replaceAll(r'%1$s', name));

  static final _cjkSpace = RegExp('([\u3000-\u9FFF\uFF00-\uFFEF]) ([\u3000-\u9FFF\uFF00-\uFFEF])');

  /// A single space between two CJK characters dropped (where a filled-in name is itself Chinese).
  static String tightenCjkSpaces(String text) => text.replaceAllMapped(_cjkSpace, (m) => '${m.group(1)}${m.group(2)}');
}
