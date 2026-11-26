/// Share as image (Android PR #335, owner 2026-11-25): one sheet, three tabs
/// — the lyrics, the song's About (AI / objective text, tertiary) and one of
/// the listener's notes (their own words, primary) — each with its own
/// layouts, Card / Story, and four tones. The plain models the rules
/// ([YoinShareCards]) read and answer: a port of :shared
/// `com.gpo.yoin.shared.share.ShareCardModels.kt` (golden vectors
/// shared/src/commonTest/resources/golden/share-cards.json). Every platform
/// draws the images itself.
library;

import 'package:flutter/foundation.dart';

/// The sheet's tabs, in order: 歌词 / 关于 / 笔记.
enum ShareTab {
  lyrics('Lyrics'),
  about('About'),
  note('Note');

  const ShareTab(this.storeKey);

  /// The key the tab's last layout is remembered under (per device, outside sync): the tab's Kotlin name.
  final String storeKey;

  /// Whose words the tab's images carry, which decides their colours.
  ShareRole get role => switch (this) {
    ShareTab.lyrics => ShareRole.lyrics,
    ShareTab.about => ShareRole.ai,
    ShareTab.note => ShareRole.own,
  };
}

/// Every layout of every tab, in each tab's 版式 order; each tab's first is its default.
enum ShareLayout {
  /// Cover + title, the lines, the mark.
  lyricsClassic(ShareTab.lyrics, 'LyricsClassic'),

  /// The first line at display size, up to three more small under it.
  lyricsPoster(ShareTab.lyrics, 'LyricsPoster'),

  /// The lines over a progress bar marking where they sit in the song.
  lyricsPlayer(ShareTab.lyrics, 'LyricsPlayer'),

  /// CJK lines set top to bottom, right to left (CJK picks only).
  lyricsVertical(ShareTab.lyrics, 'LyricsVertical'),

  /// The credits as a receipt (dashed rules), then the paragraph.
  aboutReceipt(ShareTab.about, 'AboutReceipt'),

  /// The paragraph as the hero, the credits as chips.
  aboutQuote(ShareTab.about, 'AboutQuote'),

  /// Liner notes: a large title, the credits in a grid, the paragraph.
  aboutLiner(ShareTab.about, 'AboutLiner'),

  /// The note in large type, like the lyrics card.
  noteBig(ShareTab.note, 'NoteBig'),

  /// The note as a scrapbook clipping on dot paper, with a ticket stub.
  noteClipping(ShareTab.note, 'NoteClipping'),

  /// The note hanging from its moment on a wavy progress line.
  noteTimeline(ShareTab.note, 'NoteTimeline');

  const ShareLayout(this.tab, this.storeValue);

  final ShareTab tab;

  /// The value remembered for its tab ([ShareTab.storeKey]): the layout's Kotlin name.
  final String storeValue;

  /// In Story this layout is the whole poster, edge to edge; the others sit as a card on the tone's backdrop.
  bool get storyIsPoster =>
      this == ShareLayout.lyricsPoster ||
      this == ShareLayout.lyricsVertical ||
      this == ShareLayout.aboutLiner ||
      this == ShareLayout.noteClipping;
}

/// Whose words an image carries: the lyrics keep their own pairings; About is
/// AI / objective text and reads tertiary; a note is the listener's own and
/// reads primary.
enum ShareRole {
  lyrics('Lyrics'),
  ai('Ai'),
  own('Own');

  const ShareRole(this.kotlinName);

  final String kotlinName;

  /// The four tones the role offers, in swatch order (the sheet keeps the slot, 0–3, across tabs).
  List<ShareTone> tones() => switch (this) {
    ShareRole.lyrics => const [ShareTone.soft, ShareTone.deep, ShareTone.vivid, ShareTone.mist],
    ShareRole.ai || ShareRole.own => const [ShareTone.soft, ShareTone.deep, ShareTone.vivid, ShareTone.paper],
  };
}

/// An image's colour pairing ([YoinShareCards.toneColors]).
enum ShareTone {
  /// Container + on-container: the default.
  soft('Soft'),

  /// The soft pair inverted.
  deep('Deep'),

  /// The role colour + its on-colour (tertiary for the lyrics).
  vivid('Vivid'),

  /// Tertiary container + its on-colour — the lyrics' fourth slot.
  mist('Mist'),

  /// A white paper card with the role's colour as ink, in dark mode too — About and Note's fourth slot.
  paper('Paper');

  const ShareTone(this.kotlinName);

  final String kotlinName;
}

/// Card: the image alone, 360 wide, its height following the content. Story: 9:16, 360 × 640.
enum ShareFormat {
  card('Card'),
  story('Story');

  const ShareFormat(this.kotlinName);

  final String kotlinName;
}

/// The song an image is about. [durationMs] 0 = unknown.
@immutable
final class ShareSong {
  const ShareSong({
    required this.songId,
    required this.title,
    required this.artist,
    required this.coverArtUrl,
    this.albumName = '',
    this.durationMs = 0,
  });

  final String songId;
  final String title;
  final String artist;
  final String? coverArtUrl;
  final String albumName;
  final int durationMs;
}

/// One lyric line as shared: its start (null = untimed), its text, its translation.
@immutable
final class ShareLyricLine {
  const ShareLyricLine({required this.startMs, required this.text, this.translation});

  final int? startMs;
  final String text;
  final String? translation;

  @override
  bool operator ==(Object other) =>
      other is ShareLyricLine && other.startMs == startMs && other.text == text && other.translation == translation;

  @override
  int get hashCode => Object.hash(startMs, text, translation);

  @override
  String toString() => 'ShareLyricLine($startMs, $text, $translation)';
}

/// The lyric lines to share: the select-mode pick, or the current line
/// ([YoinShareCards.currentLine]). [passageStarts]: positions in [lines] that
/// start a new passage (the pick skipped part of the song there).
@immutable
final class ShareLyrics {
  const ShareLyrics({required this.lines, this.passageStarts = const [], this.showTranslation = false});

  final List<ShareLyricLine> lines;
  final List<int> passageStarts;
  final bool showTranslation;

  @override
  bool operator ==(Object other) =>
      other is ShareLyrics &&
      listEquals(other.lines, lines) &&
      listEquals(other.passageStarts, passageStarts) &&
      other.showTranslation == showTranslation;

  @override
  int get hashCode => Object.hash(Object.hashAll(lines), Object.hashAll(passageStarts), showTranslation);
}

/// One line on an About image ("作词" / "吴青峰"). [ai]: the AI wrote it, not
/// the lyrics' credits or the server. [fact]: a plain fact about the release
/// (the year), not a credit — on its own it isn't worth an About image.
@immutable
final class ShareCredit {
  const ShareCredit(this.label, this.value, {this.ai = false, this.fact = false});

  final String label;
  final String value;
  final bool ai;
  final bool fact;

  @override
  bool operator ==(Object other) =>
      other is ShareCredit && other.label == label && other.value == value && other.ai == ai && other.fact == fact;

  @override
  int get hashCode => Object.hash(label, value, ai, fact);
}

/// About as shared: the credits and ONE paragraph, nothing else — none of the
/// server's file facts and none of the listener's own About entries.
/// [heading]: "关于" for the review, the question's title for an answer.
@immutable
final class ShareAbout {
  const ShareAbout({required this.credits, required this.heading, required this.paragraph});

  final List<ShareCredit> credits;
  final String heading;
  final String? paragraph;

  /// Whether anything on the image came from the AI: only then does it say "由 X 整理".
  bool get aiContent => paragraph != null || credits.any((c) => c.ai);
}

/// One of the listener's notes (one note per image). [content] is its stored Markdown (mentions as links).
@immutable
final class ShareNote {
  const ShareNote({required this.noteId, required this.content, required this.positionMs, required this.createdAt});

  final String noteId;
  final String content;
  final int? positionMs;
  final int createdAt;
}

/// Everything the sheet can share for one song, frozen when it opens.
@immutable
final class ShareCardContent {
  const ShareCardContent({required this.song, this.lyrics, this.about, this.note});

  final ShareSong song;
  final ShareLyrics? lyrics;
  final ShareAbout? about;
  final ShareNote? note;
}

/// One stored About row (Android `song_about_entries`), as the About rules read it.
@immutable
final class ShareAboutEntry {
  const ShareAboutEntry({
    required this.kind,
    required this.entryKey,
    this.promptText,
    this.titleText,
    required this.answerText,
  });

  /// "canonical", "ask" or "own" ([YoinShareCards.kindCanonical] …).
  final String kind;

  /// A canonical row's key ("lyricist", "review" …); an ask's question key.
  final String entryKey;
  final String? promptText;
  final String? titleText;
  final String answerText;
}

/// What the song's sources say for About's credits: the lyrics' credits for
/// the four headline roles (names joined as the platform shows them, null when
/// absent) and the server's composer and year. Nothing else from the server
/// crosses over.
@immutable
final class ShareAboutSources {
  const ShareAboutSources({this.lyricist, this.composer, this.arranger, this.producer, this.serverComposer, this.year});

  final String? lyricist;
  final String? composer;
  final String? arranger;
  final String? producer;
  final String? serverComposer;
  final int? year;
}

/// About's labels in the platform's language ("作词" / "Lyricist" …); [review] heads the AI's review.
@immutable
final class ShareAboutLabels {
  const ShareAboutLabels({
    required this.lyricist,
    required this.composer,
    required this.arranger,
    required this.producer,
    required this.creationTime,
    required this.creationLocation,
    required this.year,
    required this.review,
  });

  final String lyricist;
  final String composer;
  final String arranger;
  final String producer;
  final String creationTime;
  final String creationLocation;
  final String year;
  final String review;
}

/// What a Poster draws: [lead] at display size, [rest] small, [translation] as one quiet line at the very bottom.
@immutable
final class SharePoster {
  const SharePoster({
    required this.lead,
    required this.rest,
    required this.translation,
    required this.leadSizeSp,
    required this.leadTime,
  });

  final ShareLyricLine lead;
  final List<ShareLyricLine> rest;
  final String? translation;

  /// The lead's size on a Card (sp); a Story poster draws it [YoinShareCards.posterStoryLeadBoostSp] larger.
  final double leadSizeSp;

  /// The lead line's moment ("1:05"), shown after the artist; null when untimed.
  final String? leadTime;
}

/// Where the excerpt sits on the Player's progress bar: fractions of the song (32-bit floats), and its times.
@immutable
final class ShareExcerpt {
  const ShareExcerpt(this.start, this.end, this.startMs, this.endMs);

  final double start;
  final double end;
  final int startMs;
  final int endMs;

  /// Where its time chip centres (kept inside the bar by the platform's layout).
  double get centre => f32((start + end) / 2);
}

/// How a 竖排 image sets its lines: each column's characters, top to bottom, and the type size.
@immutable
final class ShareVerticalPlan {
  const ShareVerticalPlan(this.columns, this.fontSizeSp, this.charsPerColumn);

  final List<String> columns;
  final double fontSizeSp;
  final int charsPerColumn;
}

/// Where an image colour comes from: one of the scheme's roles, or plain white (Paper's card).
enum ShareInkSource {
  primary('Primary'),
  onPrimary('OnPrimary'),
  primaryContainer('PrimaryContainer'),
  onPrimaryContainer('OnPrimaryContainer'),
  tertiary('Tertiary'),
  onTertiary('OnTertiary'),
  tertiaryContainer('TertiaryContainer'),
  onTertiaryContainer('OnTertiaryContainer'),
  white('White');

  const ShareInkSource(this.kotlinName);

  final String kotlinName;
}

/// One image colour: [source] as the platform's current colour scheme has it,
/// or — when [tone] is set — that colour re-toned to HCT tone [tone], hue and
/// chroma kept ([YoinShareCards.retone]; Paper's ink and backdrop).
@immutable
final class ShareInk {
  const ShareInk(this.source, [this.tone]);

  final ShareInkSource source;
  final int? tone;

  @override
  bool operator ==(Object other) => other is ShareInk && other.source == source && other.tone == tone;

  @override
  int get hashCode => Object.hash(source, tone);
}

/// A tone's colours for one role: the card, its text, and the Story's
/// backdrop. Secondary text is [content] at [YoinShareCards.secondaryAlpha].
@immutable
final class ShareToneColors {
  const ShareToneColors(this.card, this.content, this.backdrop);

  final ShareInk card;
  final ShareInk content;
  final ShareInk backdrop;
}

/// A stretch of an About value or paragraph that is (or isn't) set bold
/// ([YoinShareCards.boldRuns]).
@immutable
final class ShareTextRun {
  const ShareTextRun(this.text, this.bold);

  final String text;
  final bool bold;
}

/// An exported PNG's size in pixels.
@immutable
final class ShareExportSize {
  const ShareExportSize(this.width, this.height);

  final int width;
  final int height;
}

final Float32List _f32 = Float32List(1);

/// [x] as a Kotlin `Float` holds it: the rules' fractions are 32-bit floats,
/// and every step of their arithmetic rounds to one.
double f32(num x) {
  _f32[0] = x.toDouble();
  return _f32[0];
}
