/// Now Playing's About, the pure part (Android ui/nowplaying AboutUiState,
/// AskBarState, AboutReceipt's rows / paragraphs / carousel pages,
/// GeminiKeyHint's cooldown, and ui/component GeminiPrompts' quick prompts).
/// The widgets live in about_widgets.dart and ask_bar.dart.
library;

import 'package:flutter/foundation.dart';

import '../data/about/song_about_store.dart';
import '../data/ai/ai_service.dart' show AboutFields;
import '../data/model/model.dart';
import '../platform/storage/stores.dart';
import '../ui/component/audio_format_tags.dart';

/// The current song's About rows as both About surfaces read them.
sealed class AboutUiState {
  const AboutUiState();
}

/// No song yet, or nothing cached and nothing asked for.
final class AboutIdle extends AboutUiState {
  const AboutIdle();
  @override
  bool operator ==(Object other) => other is AboutIdle;
  @override
  int get hashCode => 0;
}

/// The first fetch is in flight.
final class AboutLoading extends AboutUiState {
  const AboutLoading();
  @override
  bool operator ==(Object other) => other is AboutLoading;
  @override
  int get hashCode => 1;
}

/// No AI service is set up.
final class AboutKeyMissing extends AboutUiState {
  const AboutKeyMissing();
  @override
  bool operator ==(Object other) => other is AboutKeyMissing;
  @override
  int get hashCode => 2;
}

/// The cached rows (canonical first, then the asks and own entries, newest first).
final class AboutReady extends AboutUiState {
  const AboutReady(this.entries);
  final List<SongAboutEntry> entries;
  @override
  bool operator ==(Object other) => other is AboutReady && listEquals(other.entries, entries);
  @override
  int get hashCode => Object.hashAll(entries);
}

final class AboutError extends AboutUiState {
  const AboutError(this.message);
  final String message;
  @override
  bool operator ==(Object other) => other is AboutError && other.message == message;
  @override
  int get hashCode => message.hashCode;
}

/// The Ask bar's transient state; never stored (AskBarState).
sealed class AskBarState {
  const AskBarState();
}

final class AskIdle extends AskBarState {
  const AskIdle();
  @override
  bool operator ==(Object other) => other is AskIdle;
  @override
  int get hashCode => 0;
}

final class AskFocused extends AskBarState {
  const AskFocused();
  @override
  bool operator ==(Object other) => other is AskFocused;
  @override
  int get hashCode => 1;
}

/// The question is on its way; [title] is the AI's short heading once it lands (the question until then).
final class AskLoading extends AskBarState {
  const AskLoading(this.title, {this.requestId = 0});
  final String title;
  final int requestId;
  @override
  bool operator ==(Object other) => other is AskLoading && other.title == title && other.requestId == requestId;
  @override
  int get hashCode => Object.hash(title, requestId);
}

/// [keyMissing]: no service; else [message] is the service's error.
final class AskFailed extends AskBarState {
  const AskFailed({this.message, this.keyMissing = false});
  final String? message;
  final bool keyMissing;
  @override
  bool operator ==(Object other) => other is AskFailed && other.message == message && other.keyMissing == keyMissing;
  @override
  int get hashCode => Object.hash(message, keyMissing);
}

/// What the service says about the song itself (SpineSourceFacts): on
/// Subsonic everything the server sends; on Apple Music only the catalogue's
/// composer; null for every other provider, or when there is nothing.
@immutable
final class AboutSourceFacts {
  const AboutSourceFacts({
    this.format,
    this.bitRateKbps,
    this.samplingRateHz,
    this.bitDepth,
    this.serverRating,
    this.genre,
    this.year,
    this.trackNumber,
    this.discNumber,
    this.durationSec,
    this.channels,
    this.sizeBytes,
    this.playCount,
    this.bpm,
    this.composer,
    this.comment,
    this.added,
    this.path,
  });

  final String? format;
  final int? bitRateKbps;
  final int? samplingRateHz;
  final int? bitDepth;

  /// The server's own 1–5 rating, not Yoin's.
  final int? serverRating;
  final String? genre;
  final int? year;
  final int? trackNumber;
  final int? discNumber;
  final int? durationSec;
  final int? channels;
  final int? sizeBytes;
  final int? playCount;
  final int? bpm;

  /// The composer the service names; About's composer when the lyrics name none.
  final String? composer;
  final String? comment;

  /// When the file came onto the server, ISO-8601 as the server sent it.
  final String? added;

  /// The file's path on the server.
  final String? path;

  /// [track]'s facts (Track.spineSourceFacts): a Subsonic song's, or an Apple
  /// Music song's composer; null otherwise.
  static AboutSourceFacts? of(Track track) {
    if (track.id.provider == MediaId.providerAppleMusic) {
      final composer = _nonBlank(track.composer);
      return composer == null ? null : AboutSourceFacts(composer: composer);
    }
    if (track.id.provider != MediaId.providerSubsonic) return null;
    final f = track.audioFormat;
    final codec = f?.codec;
    final rating = track.userRating;
    return AboutSourceFacts(
      format: codec == null ? null : codecLabel(codec),
      bitRateKbps: f?.bitRateKbps,
      samplingRateHz: f?.sampleRateHz,
      bitDepth: f?.bitDepth,
      serverRating: rating != null && rating >= 1 && rating <= 5 ? rating : null,
      genre: _nonBlank(track.genre),
      year: (track.year ?? 0) > 0 ? track.year : null,
      trackNumber: (track.trackNumber ?? 0) > 0 ? track.trackNumber : null,
      discNumber: track.discNumber,
      durationSec: (track.durationSec ?? 0) > 0 ? track.durationSec : null,
      channels: f?.channels,
      sizeBytes: (track.sizeBytes ?? 0) > 0 ? track.sizeBytes : null,
      playCount: track.playCount,
      bpm: track.bpm,
      composer: _nonBlank(track.composer),
      comment: _nonBlank(track.comment),
      added: track.addedAt,
      path: _nonBlank(track.path),
    );
  }

  static String? _nonBlank(String? s) {
    final t = s?.trim();
    return t == null || t.isEmpty ? null : t;
  }

  @override
  bool operator ==(Object other) =>
      other is AboutSourceFacts &&
      other.format == format &&
      other.bitRateKbps == bitRateKbps &&
      other.samplingRateHz == samplingRateHz &&
      other.bitDepth == bitDepth &&
      other.serverRating == serverRating &&
      other.genre == genre &&
      other.year == year &&
      other.trackNumber == trackNumber &&
      other.discNumber == discNumber &&
      other.durationSec == durationSec &&
      other.channels == channels &&
      other.sizeBytes == sizeBytes &&
      other.playCount == playCount &&
      other.bpm == bpm &&
      other.composer == composer &&
      other.comment == comment &&
      other.added == added &&
      other.path == path;

  @override
  int get hashCode => Object.hash(
    format,
    bitRateKbps,
    samplingRateHz,
    bitDepth,
    serverRating,
    genre,
    year,
    trackNumber,
    discNumber,
    durationSec,
    channels,
    sizeBytes,
    playCount,
    bpm,
    composer,
    comment,
    added,
    path,
  );
}

/// The receipt's words (SpineLabels + AboutLabels), kept out of the rows so they stay pure.
abstract interface class AboutLabels {
  String about(String entryKey);
  String get quality;
  String get serverRating;
  String get genre;
  String get year;
  String get track;
  String discTrack(int disc, int track);
  String kbps(int value);
  String khz(String value);
  String bit(int value);
  String get arranger;
  String get duration;
  String get channels;
  String channelCount(int count);
  String get size;
  String megabytes(String value);
  String get bpm;
  String get playCount;
  String get added;
  String get comment;
  String get file;
}

/// One line of the receipt; [entry] is the AI's row behind it (a long press), null otherwise.
@immutable
final class ReceiptRow {
  const ReceiptRow(this.label, this.value, {this.entry});
  final String label;
  final String value;
  final SongAboutEntry? entry;

  @override
  bool operator ==(Object other) =>
      other is ReceiptRow && other.label == label && other.value == value && other.entry == entry;

  @override
  int get hashCode => Object.hash(label, value, entry);

  @override
  String toString() => 'ReceiptRow($label: $value)';
}

/// One paragraph under the receipt: the review, an answer, or the listener's own entry.
@immutable
final class AboutParagraph {
  const AboutParagraph(this.label, this.text, this.entry);
  final String label;
  final String text;
  final SongAboutEntry entry;

  @override
  bool operator ==(Object other) =>
      other is AboutParagraph && other.label == label && other.text == text && other.entry == entry;

  @override
  int get hashCode => Object.hash(label, text, entry);
}

String? _oneLine(String text) {
  final t = text.replaceAll(RegExp(r'\s+'), ' ').trim();
  return t.isEmpty ? null : t;
}

/// 245 → "4:05" (formatDuration).
String formatAboutDuration(int seconds) {
  final h = seconds ~/ 3600;
  final m = seconds % 3600 ~/ 60;
  final s = seconds % 60;
  String two(int v) => v.toString().padLeft(2, '0');
  return h > 0 ? '$h:${two(m)}:${two(s)}' : '$m:${two(s)}';
}

/// 9_437_184 → "9.0" (formatMegabytes).
String formatAboutMegabytes(int bytes) => (bytes / 1048576).toStringAsFixed(1);

/// The receipt, in order (aboutReceiptRows): who wrote it (the lyrics'
/// [credits] first, the server's composer next, then the AI), when and where
/// (the AI), then everything the server says.
List<ReceiptRow> aboutReceiptRows(
  List<SongAboutEntry> entries,
  LyricCredits? credits,
  AboutSourceFacts? facts,
  AboutLabels labels,
) {
  final canonical = {
    for (final e in entries)
      if (e.isCanonical) e.entryKey: e,
  };
  final rows = <ReceiptRow>[];
  void ai(String key) {
    final row = canonical[key];
    if (row == null) return;
    final value = _oneLine(row.answerText);
    if (value != null) rows.add(ReceiptRow(labels.about(key), value, entry: row));
  }

  void add(String label, String? value) {
    final v = value == null ? null : _oneLine(value);
    if (v != null) rows.add(ReceiptRow(label, v));
  }

  void credited(String key, LyricCreditRole role, [String? fallback]) {
    final names = credits?.namesFor(role) ?? fallback;
    if (names != null) {
      add(labels.about(key), names);
    } else {
      ai(key);
    }
  }

  credited(AboutFields.lyricist, LyricCreditRole.lyricist);
  credited(AboutFields.composer, LyricCreditRole.composer, facts?.composer);
  add(labels.arranger, credits?.namesFor(LyricCreditRole.arranger));
  credited(AboutFields.producer, LyricCreditRole.producer);
  ai(AboutFields.creationTime);
  ai(AboutFields.creationLocation);
  if (facts != null) {
    add(labels.genre, facts.genre);
    add(labels.year, facts.year?.toString());
    final track = facts.trackNumber;
    if (track != null) {
      final disc = facts.discNumber;
      add(labels.track, disc != null && disc > 1 ? labels.discTrack(disc, track) : '$track');
    }
    add(labels.duration, facts.durationSec == null ? null : formatAboutDuration(facts.durationSec!));
    final quality = [
      ?facts.format,
      if (facts.bitDepth case final d?) labels.bit(d),
      if (facts.samplingRateHz case final hz?) labels.khz(khzLabel(hz)),
      if (facts.bitRateKbps case final k?) labels.kbps(k),
    ];
    // Two spaces between parts, as on the spine (oneLine would fold them).
    if (quality.isNotEmpty) rows.add(ReceiptRow(labels.quality, quality.join('  ')));
    add(labels.channels, facts.channels == null ? null : labels.channelCount(facts.channels!));
    add(labels.size, facts.sizeBytes == null ? null : labels.megabytes(formatAboutMegabytes(facts.sizeBytes!)));
    add(labels.bpm, facts.bpm?.toString());
    add(labels.playCount, facts.playCount?.toString());
    final stars = facts.serverRating;
    if (stars != null) add(labels.serverRating, '${'★' * stars}${'☆' * (5 - stars)}');
    final added = facts.added;
    add(labels.added, added == null ? null : (added.length > 10 ? added.substring(0, 10) : added));
    add(labels.comment, facts.comment);
    add(labels.file, facts.path);
  }
  return rows;
}

/// Whether About already has something to show before the AI answers: the
/// lyrics' credits or the server's facts (rememberAboutHasSourceRows). While
/// the AI loads over such a page, the wait shows as a small indicator in the
/// tab row (between About and Note) instead of replacing or trailing the
/// content (Android 11-16).
bool aboutHasSourceRows(LyricCredits? credits, AboutSourceFacts? facts) =>
    aboutReceiptRows(const [], credits, facts, const _BlankAboutLabels()).isNotEmpty;

/// Labels for counting rows only: whether a row shows never depends on its label
/// (values built from a label, like a disc/track pair, stay non-empty).
final class _BlankAboutLabels implements AboutLabels {
  const _BlankAboutLabels();

  @override
  String about(String entryKey) => '-';
  @override
  String get quality => '-';
  @override
  String get serverRating => '-';
  @override
  String get genre => '-';
  @override
  String get year => '-';
  @override
  String get track => '-';
  @override
  String discTrack(int disc, int track) => '-';
  @override
  String kbps(int value) => '-';
  @override
  String khz(String value) => '-';
  @override
  String bit(int value) => '-';
  @override
  String get arranger => '-';
  @override
  String get duration => '-';
  @override
  String get channels => '-';
  @override
  String channelCount(int count) => '-';
  @override
  String get size => '-';
  @override
  String megabytes(String value) => '-';
  @override
  String get bpm => '-';
  @override
  String get playCount => '-';
  @override
  String get added => '-';
  @override
  String get comment => '-';
  @override
  String get file => '-';
}

/// The "N more · …" line under the receipt (cmp_lyric_credits_more's second
/// part): the first three of [others]' roles, a free-form line called
/// [noteLabel], joined with "、" and trailed by "…".
String lyricCreditsMorePreview(List<LyricCredit> others, String noteLabel) =>
    '${others.take(3).map((c) => c.role.isEmpty ? noteLabel : c.role).join('、')}…';

/// The review first, then the answers and the listener's own entries, newest first (aboutParagraphs).
List<AboutParagraph> aboutParagraphs(List<SongAboutEntry> entries, String reviewLabel) {
  final review = entries
      .where((e) => e.isCanonical && e.entryKey == AboutFields.review && e.answerText.trim().isNotEmpty)
      .firstOrNull;
  return [
    if (review != null) AboutParagraph(reviewLabel, review.answerText.trim(), review),
    for (final row in entries)
      if (!row.isCanonical && row.answerText.trim().isNotEmpty)
        // Rows written before titles existed fall back to the question so history stays readable.
        AboutParagraph(
          (row.titleText?.trim().isNotEmpty ?? false) ? row.titleText! : (row.promptText ?? ''),
          row.answerText.trim(),
          row,
        ),
  ];
}

/// One page of the collapsed About: a few receipt lines, or one paragraph.
sealed class AboutCarouselPage {
  const AboutCarouselPage();
}

final class AboutCarouselLines extends AboutCarouselPage {
  const AboutCarouselLines(this.rows);
  final List<ReceiptRow> rows;
  @override
  bool operator ==(Object other) => other is AboutCarouselLines && listEquals(other.rows, rows);
  @override
  int get hashCode => Object.hashAll(rows);
}

final class AboutCarouselParagraph extends AboutCarouselPage {
  const AboutCarouselParagraph(this.paragraph);
  final AboutParagraph paragraph;
  @override
  bool operator ==(Object other) => other is AboutCarouselParagraph && other.paragraph == paragraph;
  @override
  int get hashCode => paragraph.hashCode;
}

/// Receipt lines per carousel page.
const int aboutCarouselLinesPerPage = 3;

/// The receipt in pages of [aboutCarouselLinesPerPage] lines, then a page per paragraph (aboutCarouselPages).
List<AboutCarouselPage> aboutCarouselPages(List<ReceiptRow> rows, List<AboutParagraph> paragraphs) => [
  for (var i = 0; i < rows.length; i += aboutCarouselLinesPerPage)
    AboutCarouselLines(
      rows.sublist(i, i + aboutCarouselLinesPerPage > rows.length ? rows.length : i + aboutCarouselLinesPerPage),
    ),
  for (final p in paragraphs) AboutCarouselParagraph(p),
];

/// What was already asked about the song on show, for the quick prompts to
/// leave out (askedQuestions): the asks' questions and the own entries' headings.
List<String> askedQuestions(AboutUiState state) => switch (state) {
  AboutReady(:final entries) => [
    for (final row in entries)
      if (!row.isCanonical && ((row.promptText ?? row.titleText)?.trim().isNotEmpty ?? false))
        (row.promptText ?? row.titleText)!,
  ],
  _ => const [],
};

/// The quick prompts left once [asked] are taken out, compared the way About rows are keyed (remainingQuickPrompts).
List<String> remainingQuickPrompts(List<String> prompts, Iterable<String> asked) {
  final done = {for (final a in asked) normalizeAboutKey(a)};
  return [
    for (final p in prompts)
      if (!done.contains(normalizeAboutKey(p))) p,
  ];
}

/// The soft pink glow on Now Playing when About needs an AI key that isn't
/// set up (GeminiKeyHint): it comes back at most every [cooldownMs], kept on
/// this device only.
abstract final class AiKeyHint {
  static const String storeKey = 'yoin.ai.key_hint.last_shown_ms';

  /// Three days between glows.
  static const int cooldownMs = 3 * 24 * 60 * 60 * 1000;

  /// The glow holds this long, then fades.
  static const Duration hold = Duration(milliseconds: 3200);

  /// Never shown, or shown at least the cooldown ago (a clock set back counts as due).
  static bool isDue(int? lastShownMs, int nowMs) =>
      lastShownMs == null || nowMs < lastShownMs || nowMs - lastShownMs >= cooldownMs;

  /// True when the glow may show now; records it as shown if so.
  static Future<bool> claim(KeyValueStore store, int nowMs) async {
    final last = int.tryParse(await store.read(storeKey) ?? '');
    if (!isDue(last, nowMs)) return false;
    await store.write(storeKey, '$nowMs');
    return true;
  }
}
