/// The share-card rules (port of :shared `YoinShareCards.kt`, Android PR #335,
/// owner 2026-11-25): which tabs a song has, which layouts each tab offers
/// and which it opens on, what each image says (the lyric lines, About's
/// credits and paragraph, the note), the tones' colour roles, the Story zoom,
/// the export sizes and the file name. Answers exactly as
/// shared/src/commonTest/resources/golden/share-cards.json says
/// (test/data/share/share_cards_golden_test.dart).
///
/// Kotlin's semantics are kept where Dart's differ: strings are UTF-16 units
/// (lengths, take, chunked), `trim` / `isBlank` are Kotlin's
/// ([ktTrim] / [ktIsBlank]), `\s` is Java's ASCII class, and every fraction is
/// a 32-bit float ([f32]). The CJK test reads the copied JVM tables
/// ([ShareScripts]), never Dart's script classes.
library;

import 'dart:math' as math;

import 'package:material_color_utilities/material_color_utilities.dart';

import '../albumabout/kt_text.dart';
import '../notes/note_markup.dart';
import 'share_card_models.dart';
import 'share_scripts.dart';
import 'share_zoom_search.dart';

export 'share_card_models.dart';

/// What a note line is ([YoinShareCards.noteLines]).
enum ShareNoteLineKind { paragraph, heading, bullet, checklist }

/// A stretch of one note line's words that share their marks; a mention's run is its title.
final class ShareNoteRun {
  const ShareNoteRun(this.text, {required this.bold, required this.italic, this.mention});

  final String text;
  final bool bold;
  final bool italic;
  final NoteMention? mention;
}

/// One note line: [level] 1–3 for a heading (else 0); [checked] for a done to-do.
final class ShareNoteLine {
  const ShareNoteLine(this.kind, this.level, this.checked, this.runs);

  final ShareNoteLineKind kind;
  final int level;
  final bool checked;
  final List<ShareNoteRun> runs;
}

abstract final class YoinShareCards {
  // ── stored About rows (Android `song_about_entries`, synced as `song_about`) ──

  static const kindCanonical = 'canonical';
  static const kindAsk = 'ask';
  static const kindOwn = 'own';
  static const canonLyricist = 'lyricist';
  static const canonComposer = 'composer';
  static const canonProducer = 'producer';
  static const canonCreationTime = 'creation_time';
  static const canonCreationLocation = 'creation_location';
  static const canonReview = 'review';

  // ── sizes ──

  /// Every image lays out at its logical size (dp, font scale 1): a Card is this wide, as tall as it needs.
  static const int cardDesignWidthDp = 360;

  /// A Story is 360 × 640.
  static const int storyDesignWidthDp = 360;
  static const int storyDesignHeightDp = 640;

  /// A Card exports this wide (720 logical px at 2×), its height following the content ([exportSize]).
  static const int cardExportWidthPx = 1440;

  /// A Story exports at the common 9:16 canvas.
  static const int storyExportWidthPx = 1080;
  static const int storyExportHeightPx = 1920;

  /// A Story's content keeps clear of the chrome stories draw over the image: the frame it zooms into.
  static const int storyMarginHorizontalDp = 28;
  static const int storyMarginVerticalDp = 72;

  static final double storyMinZoom = ShareZoomSearch.minZoom;
  static final double storyMaxZoomLimit = ShareZoomSearch.maxZoomLimit;

  // ── tabs and layouts ──

  /// Whether the song has something for [tab].
  static bool has(ShareCardContent content, ShareTab tab) => switch (tab) {
    ShareTab.lyrics => content.lyrics != null && content.lyrics!.lines.isNotEmpty,
    // Credits or AI words; the year alone is not an About.
    ShareTab.about =>
      content.about != null && (content.about!.paragraph != null || content.about!.credits.any((c) => !c.fact)),
    ShareTab.note => content.note != null && !ktIsBlank(content.note!.content),
  };

  /// The tabs this song has something for, in order (none: the sheet doesn't open).
  static List<ShareTab> tabs(ShareCardContent content) => [
    for (final tab in ShareTab.values)
      if (has(content, tab)) tab,
  ];

  /// Every layout of [tab], in 版式 order.
  static List<ShareLayout> allLayouts(ShareTab tab) => [
    for (final layout in ShareLayout.values)
      if (layout.tab == tab) layout,
  ];

  /// The layouts [tab] offers for this content, in 版式 order (empty when the tab has nothing).
  static List<ShareLayout> layouts(ShareCardContent content, ShareTab tab) {
    if (!has(content, tab)) return const [];
    return [
      for (final layout in allLayouts(tab))
        if (switch (layout) {
          // 竖排 only when every line is CJK: Latin set on its side reads as a mistake.
          ShareLayout.lyricsVertical => content.lyrics!.lines.every((l) => isCjk(l.text)),
          // The quote needs a paragraph to quote.
          ShareLayout.aboutQuote => content.about!.paragraph != null,
          // A timeline needs the note's moment and the song's length.
          ShareLayout.noteTimeline => content.note!.positionMs != null && content.song.durationMs > 0,
          _ => true,
        })
          layout,
    ];
  }

  /// The layout [tab] opens on: [remembered] when this content offers it, else the tab's first; null when none.
  static ShareLayout? resolveLayout(ShareCardContent content, ShareTab tab, ShareLayout? remembered) {
    final offered = layouts(content, tab);
    if (remembered != null && offered.contains(remembered)) return remembered;
    return offered.isEmpty ? null : offered.first;
  }

  /// The layout remembered for [tab] as stored ([ShareLayout.storeValue] under
  /// [ShareTab.storeKey]): a layout a later build dropped, or one filed under
  /// another tab, reads as none chosen.
  static ShareLayout? layoutForStoreValue(ShareTab tab, String? value) {
    if (value == null) return null;
    for (final layout in ShareLayout.values) {
      if (layout.storeValue == value && layout.tab == tab) return layout;
    }
    return null;
  }

  /// The tone in swatch [slot] (0–3) for [role]; the sheet keeps the slot across tabs.
  static ShareTone tone(ShareRole role, int slot) => role.tones()[slot];

  // ── lyrics ──

  /// Whether [text] is CJK all through: at least one Han / kana / Hangul /
  /// Bopomofo letter and no letter of any other script (digits, punctuation,
  /// symbols and spaces don't count either way). By Unicode Script, not
  /// Script_Extensions: ー (U+30FC) is a Common letter, so "ラーメン" is not CJK.
  static bool isCjk(String text) => ShareScripts.isCjk(text);

  /// The line the playhead is in, for a share opened without a pick: the last
  /// timed line started by [positionMs], else the first line. Blank lines
  /// never; null when none.
  static ShareLyrics? currentLine(List<ShareLyricLine> lyrics, int positionMs, bool showTranslation) {
    final lines = [
      for (final l in lyrics)
        if (!ktIsBlank(l.text)) l,
    ];
    if (lines.isEmpty) return null;
    ShareLyricLine? current;
    for (final l in lines) {
      if (l.startMs != null && l.startMs! <= positionMs) current = l;
    }
    return ShareLyrics(lines: [current ?? lines.first], showTranslation: showTranslation);
  }

  /// Where a pick of the lyrics' [selected] indices (any order; outside 0
  /// until [lineCount] ignored) skips part of the song: positions in the
  /// picked lines, in order, that start a new passage.
  static List<int> passageStarts(List<int> selected, int lineCount) {
    final picked = {
      for (final i in selected)
        if (i >= 0 && i < lineCount) i,
    }.toList()..sort();
    return [
      for (var i = 1; i < picked.length; i++)
        if (picked[i] != picked[i - 1] + 1) i,
    ];
  }

  /// The share text sent with the image: the title, then the artist on a line of its own; null without a title.
  static String? caption(String? title, String? artist) {
    final t = ktTrim(title ?? '');
    if (t.isEmpty) return null;
    final by = ktTrim(artist ?? '');
    return by.isEmpty ? t : '$t\n$by';
  }

  /// Classic's line size on the 360 card (sp): short picks 22, medium 19, long 17.
  static double cardLineSizeSp(List<String> lines) {
    final chars = lines.fold<int>(0, (n, l) => n + l.length);
    if (chars <= 90 && lines.length <= 4) return 22;
    if (chars <= 200) return 19;
    return 17;
  }

  /// The most lines a Poster shows: the first big, up to three more small.
  static const int posterMaxLines = 4;

  /// A Story poster draws the lead this much larger than [SharePoster.leadSizeSp].
  static const double posterStoryLeadBoostSp = 6;

  /// Between the translations on a Poster's one translation line.
  static const String posterTranslationSeparator = '  /  ';

  /// What a Poster draws: the first non-blank lines (at most [posterMaxLines]); null when there are none.
  static SharePoster? poster(ShareLyrics pick) {
    final shown = [
      for (final l in pick.lines)
        if (!ktIsBlank(l.text)) l,
    ].take(posterMaxLines).toList();
    if (shown.isEmpty) return null;
    final lead = shown.first;
    String? translation;
    if (pick.showTranslation) {
      final joined = [
        for (final l in shown)
          if (l.translation != null && ktTrim(l.translation!).isNotEmpty) ktTrim(l.translation!),
      ].join(posterTranslationSeparator);
      translation = joined.isEmpty ? null : joined;
    }
    return SharePoster(
      lead: lead,
      rest: shown.sublist(1),
      translation: translation,
      leadSizeSp: posterLeadSizeSp(lead.text),
      leadTime: lead.startMs == null ? null : time(lead.startMs!),
    );
  }

  /// The lead line's size on a Poster Card (sp), by its UTF-16 length: the shorter, the larger.
  static double posterLeadSizeSp(String text) {
    if (text.length <= 12) return 40;
    if (text.length <= 24) return 34;
    if (text.length <= 40) return 28;
    return 24;
  }

  /// The excerpt is drawn at least this much of the Player's bar, so a short pick still shows.
  static final double playerExcerptMinFraction = f32(0.06);

  /// A line's assumed length after its start: where the excerpt ends after the last line's start.
  static const int playerExcerptLineMs = 4000;

  /// The span of [pick] on a [durationMs]-long song: its first line's start to
  /// its last line's start plus a line's length, drawn at least
  /// [playerExcerptMinFraction] of the bar. Null when the song's length or
  /// every line's time is unknown (the Player then draws the lines without a bar).
  static ShareExcerpt? playerExcerpt(ShareLyrics pick, int durationMs) {
    if (durationMs <= 0) return null;
    final times = [
      for (final l in pick.lines)
        if (l.startMs != null) l.startMs!,
    ];
    if (times.isEmpty) return null;
    final startMs = times.reduce(math.min).clamp(0, durationMs);
    final endMs = (times.reduce(math.max) + playerExcerptLineMs).clamp(startMs, durationMs);
    final duration = f32(durationMs);
    var start = f32(f32(startMs) / duration);
    var end = f32(f32(endMs) / duration);
    if (f32(end - start) < playerExcerptMinFraction) {
      end = math.min(f32(start + playerExcerptMinFraction), 1.0);
      start = math.max(f32(end - playerExcerptMinFraction), 0.0);
    }
    return ShareExcerpt(start, end, startMs, endMs);
  }

  /// Candidate 竖排 sizes (sp), largest first.
  static const List<double> verticalSizesSp = [28, 26, 24, 22, 20, 17, 15, 13];

  /// One character's height in a column, × the type size.
  static final double verticalCharAdvance = f32(1.14);

  /// One column's width with the gap after it, × the type size (the gap is the 0.5).
  static const double verticalColumnAdvance = 1.5;

  /// The Vertical layout's column height and the width its lyric columns share (dp).
  static const double verticalColumnHeightDp = 380;
  static const double verticalAvailableWidthDp = 250;

  /// The width the 「歌词大意」 footnote column takes from the lyric columns when there is one (dp).
  static const double verticalFootnoteWidthDp = 22;

  /// Between the translations in the footnote ("歌词大意：A；B").
  static const String verticalGistSeparator = '；';

  /// Sets [lines] in columns [columnHeightDp] tall within [widthDp]: one line
  /// per column, a line longer than a column carries on in the next (spaces
  /// set as ideographic spaces). The largest size from [verticalSizesSp] at
  /// which every line fits one column and the columns fit the width; else the
  /// largest at which the columns fit; else the smallest (the Story zoom takes
  /// it from there). Columns are in reading order: the first is the rightmost.
  static ShareVerticalPlan verticalPlan(List<String> lines, double columnHeightDp, double widthDp) {
    final height = f32(columnHeightDp);
    final width = f32(widthDp);
    final clean = [
      for (final l in lines)
        if (ktTrim(l).isNotEmpty) ktTrim(l),
    ];
    ShareVerticalPlan plan(double size) {
      final perColumn = math.max(1, (f32(height / f32(size * verticalCharAdvance))).floor());
      final columns = [for (final line in clean) ..._chunked(line.replaceAll(' ', '　'), perColumn)];
      return ShareVerticalPlan(columns, size, perColumn);
    }

    final fitting = [
      for (final size in verticalSizesSp)
        if (f32(f32(plan(size).columns.length * size) * verticalColumnAdvance) <= width) plan(size),
    ];
    // A line to a column reads as a poem; a line broken over two only when nothing larger fits whole.
    for (final p in fitting) {
      if (p.columns.length == clean.length) return p;
    }
    return fitting.isNotEmpty ? fitting.first : plan(verticalSizesSp.last);
  }

  /// The 「歌词大意」 footnote's translations, when the pick shows them: each
  /// line's translation trimmed, empty ones dropped. The platform writes them
  /// as its "歌词大意：%1$s" string, joined by [verticalGistSeparator].
  static List<String> verticalGist(ShareLyrics pick) => pick.showTranslation
      ? [
          for (final l in pick.lines)
            if (l.translation != null && ktTrim(l.translation!).isNotEmpty) ktTrim(l.translation!),
        ]
      : const [];

  /// The Vertical layout's plan for [pick]: its columns in the layout's frame, less the footnote's width.
  static ShareVerticalPlan verticalLyricsPlan(ShareLyrics pick) {
    final footnote = verticalGist(pick).isEmpty ? 0.0 : verticalFootnoteWidthDp;
    return verticalPlan(
      [for (final l in pick.lines) l.text],
      verticalColumnHeightDp,
      f32(verticalAvailableWidthDp - footnote),
    );
  }

  /// Short text beside the Vertical lines (the title, the artist, the
  /// footnote) at [sizeSp] in columns [columnHeightDp] tall: CJK (or
  /// letterless) text stacked character by character, in columns in reading
  /// order; null when it is to be turned on its side instead.
  static List<String>? verticalTextColumns(String text, double sizeSp, double columnHeightDp) {
    if (!isCjk(text) && ShareScripts.hasLetterUnit(text)) return null;
    final perColumn = math.max(1, f32(f32(columnHeightDp) / f32(f32(sizeSp) * verticalCharAdvance)).truncate());
    return _chunked(text, perColumn);
  }

  /// [text] as vertical text sets it, character by character ([verticalForm]).
  static String verticalText(String text) =>
      String.fromCharCodes([for (var i = 0; i < text.length; i++) verticalForm(text.codeUnitAt(i))]);

  /// A UTF-16 unit as vertical text sets it: CJK punctuation turns to its
  /// vertical presentation form (。， become ︒︐, 「」 become ﹁﹂, dashes and
  /// ellipses stand up); everything else as it is.
  static int verticalForm(int c) => switch (c) {
    0xFF0C => 0xFE10, // ， → ︐
    0x3001 => 0xFE11, // 、 → ︑
    0x3002 => 0xFE12, // 。 → ︒
    0xFF1A => 0xFE13, // ： → ︓
    0xFF1B => 0xFE14, // ； → ︔
    0xFF01 => 0xFE15, // ！ → ︕
    0xFF1F => 0xFE16, // ？ → ︖
    0x300C => 0xFE41, // 「 → ﹁
    0x300D => 0xFE42, // 」 → ﹂
    0x300E => 0xFE43, // 『 → ﹃
    0x300F => 0xFE44, // 』 → ﹄
    0xFF08 || 0x28 => 0xFE35, // （ ( → ︵
    0xFF09 || 0x29 => 0xFE36, // ） ) → ︶
    0x300A => 0xFE3D, // 《 → ︽
    0x300B => 0xFE3E, // 》 → ︾
    0x3008 => 0xFE3F, // 〈 → ︿
    0x3009 => 0xFE40, // 〉 → ﹀
    0x2026 => 0xFE19, // … → ︙
    0x2014 || 0xFF0D || 0x2D || 0x30FC => 0xFE31, // — － - ー → ︱
    0xFF5E || 0x7E => 0x2240, // ～ ~ → ≀
    _ => c,
  };

  // ── About ──

  /// Credits a Quote shows as chips, and a Liner in its 2 × 2 grid: the first of [ShareAbout.credits].
  static const int quoteCredits = 3;
  static const int linerCredits = 4;

  /// About's share content: the credits — the lyrics' credits first, then the
  /// server's composer, then the AI's lyricist / composer / producer, creation
  /// time and place, then the year (a [ShareCredit.fact]) — and one paragraph:
  /// the AI answer or review asked for ([focusKind] + [focusKey], the row
  /// long-pressed), else the review. Never the server's file facts, never the
  /// listener's own entries. Null when there is nothing to share (the year
  /// alone is nothing). Values fold runs of whitespace to one space.
  static ShareAbout? aboutPick(
    List<ShareAboutEntry> entries,
    ShareAboutSources sources,
    ShareAboutLabels labels,
    String? focusKind,
    String? focusKey,
  ) {
    final canonical = <String, ShareAboutEntry>{
      for (final e in entries)
        if (e.kind == kindCanonical) e.entryKey: e,
    };
    final rows = <ShareCredit>[];
    void ai(String key, String label) {
      final row = canonical[key];
      if (row == null) return;
      final value = _oneLine(row.answerText);
      if (value == null) return;
      rows.add(ShareCredit(label, value, ai: true));
    }

    void add(String label, String? value) {
      final v = value == null ? null : _oneLine(value);
      if (v != null) rows.add(ShareCredit(label, v));
    }

    void credited(String key, String label, String? names) {
      if (names != null) {
        add(label, names);
      } else {
        ai(key, label);
      }
    }

    credited(canonLyricist, labels.lyricist, sources.lyricist);
    credited(canonComposer, labels.composer, sources.composer ?? sources.serverComposer);
    add(labels.arranger, sources.arranger);
    credited(canonProducer, labels.producer, sources.producer);
    ai(canonCreationTime, labels.creationTime);
    ai(canonCreationLocation, labels.creationLocation);
    if (sources.year != null) rows.add(ShareCredit(labels.year, '${sources.year}', fact: true));

    ShareAboutEntry? review;
    for (final e in entries) {
      if (e.kind == kindCanonical && e.entryKey == canonReview && !ktIsBlank(e.answerText)) {
        review = e;
        break;
      }
    }
    // The AI's paragraphs: the review, then its answers; the listener's own entries are theirs, not shared.
    final paragraphs = <(ShareAboutEntry, String, String)>[
      if (review != null) (review, labels.review, ktTrim(review.answerText)),
      for (final row in entries)
        if (row.kind == kindAsk && !ktIsBlank(row.answerText))
          (
            row,
            row.titleText != null && !ktIsBlank(row.titleText!) ? row.titleText! : (row.promptText ?? ''),
            ktTrim(row.answerText),
          ),
    ];
    (ShareAboutEntry, String, String)? chosen;
    for (final p in paragraphs) {
      if (focusKind != null && p.$1.kind == focusKind && p.$1.entryKey == focusKey) {
        chosen = p;
        break;
      }
    }
    if (chosen == null) {
      for (final p in paragraphs) {
        if (p.$1.kind == kindCanonical) {
          chosen = p;
          break;
        }
      }
    }
    if (!rows.any((r) => !r.fact) && chosen == null) return null;
    return ShareAbout(credits: rows, heading: chosen?.$2 ?? labels.review, paragraph: chosen?.$3);
  }

  static final RegExp _whitespace = RegExp('$javaSpace+');

  static String? _oneLine(String text) {
    final folded = ktTrim(text.replaceAll(_whitespace, ' '));
    return folded.isEmpty ? null : folded;
  }

  // ── notes ──

  /// The note an image shares: [noteId] when given (and not blank), else the
  /// note the playhead is in (the latest anchored at or before [positionMs]),
  /// else the newest. Blank notes never; null when none.
  static ShareNote? notePick(List<ShareNote> notes, String? noteId, int positionMs) {
    final live = [
      for (final n in notes)
        if (!ktIsBlank(n.content)) n,
    ];
    if (noteId != null) {
      for (final n in live) {
        if (n.noteId == noteId) return n;
      }
    }
    final anchored = _anchoredAt(live, positionMs);
    if (anchored != null) {
      for (final n in live) {
        if (n.noteId == anchored) return n;
      }
    }
    ShareNote? newest;
    for (final n in live) {
      if (newest == null || newest.createdAt < n.createdAt) newest = n;
    }
    return newest;
  }

  /// The latest anchored note at or before [positionMs] (a later one in [notes] wins a tie); null when none.
  static String? _anchoredAt(List<ShareNote> notes, int positionMs) {
    ShareNote? best;
    for (final note in notes) {
      final anchor = note.positionMs;
      if (anchor == null) continue;
      if (anchor <= positionMs && (best?.positionMs ?? -1) <= anchor) best = note;
    }
    return best?.noteId;
  }

  /// Where a Timeline's marker sits: the note's moment as a fraction of the song (0–1); null without either.
  static double? timelineFraction(int? positionMs, int durationMs) {
    if (positionMs == null || durationMs <= 0) return null;
    return f32(f32(positionMs) / f32(durationMs)).clamp(0.0, 1.0);
  }

  /// The note's text as drawn: its lines and runs, mentions as chips (title, cover), no touch.
  static List<ShareNoteLine> noteLines(ShareNote note) => [
    for (final block in NoteMarkup.parse(note.content).blocks)
      ShareNoteLine(
        switch (block) {
          NoteParagraph() => ShareNoteLineKind.paragraph,
          NoteHeading() => ShareNoteLineKind.heading,
          NoteBullet() => ShareNoteLineKind.bullet,
          NoteChecklist() => ShareNoteLineKind.checklist,
        },
        block is NoteHeading ? block.level : 0,
        block is NoteChecklist && block.checked,
        _flatten(block.inlines, bold: false, italic: false),
      ),
  ];

  static List<ShareNoteRun> _flatten(List<NoteInline> nodes, {required bool bold, required bool italic}) => [
    for (final node in nodes)
      ...switch (node) {
        NoteText(:final text) => [ShareNoteRun(text, bold: bold, italic: italic)],
        NoteBold(:final children) => _flatten(children, bold: true, italic: italic),
        NoteItalic(:final children) => _flatten(children, bold: bold, italic: true),
        NoteMentionInline(:final mention) => [
          ShareNoteRun(mention.title, bold: bold, italic: italic, mention: mention),
        ],
      },
  ];

  // ── time ──

  /// "1:05" for a moment in the song: whole minutes (no hours, "65:00"), seconds in two digits; negative = 0.
  static String time(int ms) {
    final total = math.max(ms ~/ 1000, 0);
    return '${total ~/ 60}:${(total % 60).toString().padLeft(2, '0')}';
  }

  // ── colours ──

  /// Secondary text (the artist, the signs) is the tone's content colour at this alpha.
  static final double secondaryAlpha = f32(0.72);

  /// Paper's ink: the role colour at its light-theme tone (T40), in both themes.
  static const int paperInkTone = 40;

  /// Paper's Story backdrop: the role at its light container tone (T90).
  static const int paperBackdropTone = 90;

  /// [tone]'s colours for [role], as roles of the playing palette's scheme
  /// (Now Playing's theme is the cover's). The lyrics keep their pairings
  /// (Vivid and Mist tertiary); About draws from tertiary, a note from primary,
  /// with Paper in the fourth slot: a white card, the role re-toned as ink. The
  /// fourth slot follows the role: a lyrics image asked for Paper draws Mist
  /// and the others the other way round, so a slot kept across tabs stays valid.
  static ShareToneColors toneColors(ShareTone tone, ShareRole role) {
    if (role == ShareRole.lyrics) {
      return switch (tone) {
        ShareTone.soft => const ShareToneColors(
          ShareInk(ShareInkSource.primaryContainer),
          ShareInk(ShareInkSource.onPrimaryContainer),
          ShareInk(ShareInkSource.primary),
        ),
        ShareTone.deep => const ShareToneColors(
          ShareInk(ShareInkSource.onPrimaryContainer),
          ShareInk(ShareInkSource.primaryContainer),
          ShareInk(ShareInkSource.primaryContainer),
        ),
        ShareTone.vivid => const ShareToneColors(
          ShareInk(ShareInkSource.tertiary),
          ShareInk(ShareInkSource.onTertiary),
          ShareInk(ShareInkSource.tertiaryContainer),
        ),
        ShareTone.mist || ShareTone.paper => const ShareToneColors(
          ShareInk(ShareInkSource.tertiaryContainer),
          ShareInk(ShareInkSource.onTertiaryContainer),
          ShareInk(ShareInkSource.tertiary),
        ),
      };
    }
    final aiRole = role == ShareRole.ai;
    final main = aiRole ? ShareInkSource.tertiary : ShareInkSource.primary;
    final onMain = aiRole ? ShareInkSource.onTertiary : ShareInkSource.onPrimary;
    final container = aiRole ? ShareInkSource.tertiaryContainer : ShareInkSource.primaryContainer;
    final onContainer = aiRole ? ShareInkSource.onTertiaryContainer : ShareInkSource.onPrimaryContainer;
    return switch (tone) {
      ShareTone.soft => ShareToneColors(ShareInk(container), ShareInk(onContainer), ShareInk(main)),
      ShareTone.deep => ShareToneColors(ShareInk(onContainer), ShareInk(container), ShareInk(container)),
      ShareTone.vivid => ShareToneColors(ShareInk(main), ShareInk(onMain), ShareInk(container)),
      ShareTone.mist || ShareTone.paper => ShareToneColors(
        const ShareInk(ShareInkSource.white),
        ShareInk(main, paperInkTone),
        ShareInk(main, paperBackdropTone),
      ),
    };
  }

  /// [argb] at HCT tone [tone], its hue and chroma kept (as far as the gamut allows): Paper's ink and backdrop.
  static int retone(int argb, int tone) {
    final hct = Hct.fromInt(argb);
    return Hct.from(hct.hue, hct.chroma, tone.toDouble()).toInt();
  }

  // ── Story zoom ──

  /// How far [layout]'s Story may zoom in: a poster never grows (it fills the frame's height), a card to 1.4.
  static double storyMaxZoom(ShareLayout layout) => layout.storyIsPoster ? 1.0 : storyMaxZoomLimit;

  /// Whether content [heightPx] tall laid out at [zoom] fits a [frameHeightPx] frame (half a pixel's slack).
  static bool storyFits(int heightPx, double zoom, int frameHeightPx) =>
      f32(f32(heightPx) * f32(zoom)) <= f32(f32(frameHeightPx) + ShareZoomSearch.fitSlackPx);

  /// The zoom [ShareZoomSearch] settles on when [fits] answers at once:
  /// [maxZoom] when even that fits; [storyMinZoom] when nothing does. [fits]
  /// lays the content out at the frame's width / zoom and says whether its
  /// height × zoom fits the frame ([storyFits]).
  static double storyZoom(double maxZoom, bool Function(double zoom) fits) {
    final search = ShareZoomSearch(maxZoom);
    while (!search.settled) {
      search.report(fits(search.zoom));
    }
    return search.zoom;
  }

  // ── export ──

  /// The PNG's pixel size for an image laid out [laidOutWidth] ×
  /// [laidOutHeight] (any unit): a fixed width per format, so the image
  /// doesn't depend on the screen; a Card's height follows its content.
  static ShareExportSize exportSize(ShareFormat format, double laidOutWidth, double laidOutHeight) => switch (format) {
    ShareFormat.story => const ShareExportSize(storyExportWidthPx, storyExportHeightPx),
    ShareFormat.card => ShareExportSize(
      cardExportWidthPx,
      math.max(1, f32(f32(f32(laidOutHeight) * cardExportWidthPx) / f32(laidOutWidth)).round()),
    ),
  };

  /// The uniform scale that fits a [childWidth] × [childHeight] image into
  /// [maxWidth] × [maxHeight] (a preview), never above [maxScale] (1 = never
  /// enlarge; a wide sheet's preview may go to 1.5). 1 for an empty child.
  static double fitScale(int childWidth, int childHeight, int maxWidth, int maxHeight, double maxScale) {
    if (childWidth <= 0 || childHeight <= 0) return 1;
    return math.min(
      f32(maxScale),
      math.min(f32(f32(maxWidth) / f32(childWidth)), f32(f32(maxHeight) / f32(childHeight))),
    );
  }

  /// The song title as the file name carries it, at most this many UTF-16 units.
  static const int fileTitleMaxChars = 48;

  /// Saved images go to Pictures/Yoin (Android) — the album name where one is used.
  static const String galleryFolder = 'Yoin';

  static const String _forbiddenInFileNames = '\\/:*?"<>|';

  /// The title for the file name: the characters file systems refuse (\ / : *
  /// ? " < > | and control characters) as spaces, runs of spaces folded,
  /// trimmed, capped at [fileTitleMaxChars]; empty when nothing is left.
  static String fileTitle(String? songTitle) {
    final replaced = StringBuffer();
    final s = songTitle ?? '';
    for (var i = 0; i < s.length; i++) {
      final c = s.codeUnitAt(i);
      final forbidden = _forbiddenInFileNames.codeUnits.contains(c) || c < 0x20 || c == 0x7F;
      replaced.writeCharCode(forbidden ? 0x20 : c);
    }
    final folded = StringBuffer();
    final r = replaced.toString();
    var last = -1;
    for (var i = 0; i < r.length; i++) {
      final c = r.codeUnitAt(i);
      if (c != 0x20 || last != 0x20) folded.writeCharCode(c);
      last = c;
    }
    return ktTrim(ktTake(ktTrim(folded.toString()), fileTitleMaxChars));
  }

  /// The saved image's name, the same for every layout:
  /// `Yoin - <title> - <ms>.png`, or `Yoin <ms>.png` without a title (the
  /// Android strings `np_share_file_named` / `np_share_file_untitled`, the
  /// same in every language).
  static String fileName(String? songTitle, int nowMs) {
    final title = fileTitle(songTitle);
    return title.isEmpty ? 'Yoin $nowMs.png' : 'Yoin - $title - $nowMs.png';
  }
}

/// Kotlin `CharSequence.chunked(size)`: UTF-16 units; empty text has no chunks.
List<String> _chunked(String text, int size) => [
  for (var i = 0; i < text.length; i += size) text.substring(i, math.min(text.length, i + size)),
];
