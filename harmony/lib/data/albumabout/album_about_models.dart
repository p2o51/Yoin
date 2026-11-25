// An album's About (owner + Carbon 2026-11-25; docs/design.md, album page):
// one compact credits line under the album's title on the scrapbook and an
// editorial background paragraph on a tertiary card. Dart port of :shared
// `albumabout/AlbumAboutModels.kt`, field for field; pinned by
// shared/src/commonTest/resources/golden/album-about.json.
import 'dart:convert';

import 'package:flutter/foundation.dart';

import 'kt_text.dart';

/// The album-level credits the AI could vouch for (high or medium
/// confidence), already cleaned: no placeholders, no blanks. Every field is
/// optional; an empty value is "not known" and never shown.
///
/// Stored ([encode]) as the `answerText` of the `canonical` / `credits` row,
/// and synced in that form: every platform reads and writes the same JSON.
@immutable
class AlbumAiCredits {
  const AlbumAiCredits({
    this.label = const [],
    this.producers = const [],
    this.mastering = const [],
    this.mixing = const [],
    this.recordedTime,
    this.recordedStudios = const [],
    this.source,
    this.firstReleased,
  });

  static const none = AlbumAiCredits();

  final List<String> label;
  final List<String> producers;
  final List<String> mastering;
  final List<String> mixing;

  /// When it was recorded, in the AI language ("1975年1月", "Jan–Mar 1975").
  final String? recordedTime;

  /// Where: studio names with their city.
  final List<String> recordedStudios;

  /// The film, game, anime, show or artist a soundtrack or tribute album belongs to.
  final String? source;

  /// A reissue's first release, in the AI language.
  final String? firstReleased;

  bool get isEmpty =>
      label.isEmpty &&
      producers.isEmpty &&
      mastering.isEmpty &&
      mixing.isEmpty &&
      recordedTime == null &&
      recordedStudios.isEmpty &&
      source == null &&
      firstReleased == null;

  /// The stored form: compact JSON without defaults (`{}` for [none]), as
  /// kotlinx writes it (property order as declared, non-ASCII as is).
  String encode() {
    final fields = <String>[];
    void list(String key, List<String> values) {
      if (values.isNotEmpty) fields.add('${_quote(key)}:[${values.map(_quote).join(',')}]');
    }

    void text(String key, String? value) {
      if (value != null) fields.add('${_quote(key)}:${_quote(value)}');
    }

    list('label', label);
    list('producers', producers);
    list('mastering', mastering);
    list('mixing', mixing);
    text('recordedTime', recordedTime);
    list('recordedStudios', recordedStudios);
    text('source', source);
    text('firstReleased', firstReleased);
    return '{${fields.join(',')}}';
  }

  /// The stored form ([encode]); [none] for anything unreadable (an older or
  /// damaged row). Strict JSON like kotlinx's default reader: unknown keys
  /// ignored, a value of the wrong type makes the whole row unreadable.
  static AlbumAiCredits decode(String? text) {
    if (text == null || ktIsBlank(text)) return none;
    try {
      final root = jsonDecode(text);
      if (root is! Map<String, dynamic>) return none;
      List<String> list(String key) {
        if (!root.containsKey(key)) return const [];
        final value = root[key];
        if (value is! List) throw const FormatException('not a list');
        return [
          for (final v in value)
            if (v is String) v else throw const FormatException('not a string'),
        ];
      }

      String? str(String key) {
        final value = root[key];
        if (value == null) return null;
        if (value is! String) throw const FormatException('not a string');
        return value;
      }

      return AlbumAiCredits(
        label: list('label'),
        producers: list('producers'),
        mastering: list('mastering'),
        mixing: list('mixing'),
        recordedTime: str('recordedTime'),
        recordedStudios: list('recordedStudios'),
        source: str('source'),
        firstReleased: str('firstReleased'),
      );
    } on FormatException {
      return none;
    }
  }

  AlbumAiCredits copyWith({
    List<String>? label,
    List<String>? producers,
    List<String>? mastering,
    List<String>? mixing,
    List<String>? recordedStudios,
  }) => AlbumAiCredits(
    label: label ?? this.label,
    producers: producers ?? this.producers,
    mastering: mastering ?? this.mastering,
    mixing: mixing ?? this.mixing,
    recordedTime: recordedTime,
    recordedStudios: recordedStudios ?? this.recordedStudios,
    source: source,
    firstReleased: firstReleased,
  );

  @override
  bool operator ==(Object other) =>
      other is AlbumAiCredits &&
      listEquals(other.label, label) &&
      listEquals(other.producers, producers) &&
      listEquals(other.mastering, mastering) &&
      listEquals(other.mixing, mixing) &&
      other.recordedTime == recordedTime &&
      listEquals(other.recordedStudios, recordedStudios) &&
      other.source == source &&
      other.firstReleased == firstReleased;

  @override
  int get hashCode => Object.hash(
    Object.hashAll(label),
    Object.hashAll(producers),
    Object.hashAll(mastering),
    Object.hashAll(mixing),
    recordedTime,
    Object.hashAll(recordedStudios),
    source,
    firstReleased,
  );

  @override
  String toString() => 'AlbumAiCredits(${encode()})';
}

/// kotlinx's string quoting: `"` and `\` escaped, control characters as
/// short escapes or `\u00xx`, everything else as is.
String _quote(String value) {
  final out = StringBuffer('"');
  for (final c in value.codeUnits) {
    switch (c) {
      case 0x22:
        out.write(r'\"');
      case 0x5C:
        out.write(r'\\');
      case 0x08:
        out.write(r'\b');
      case 0x09:
        out.write(r'\t');
      case 0x0A:
        out.write(r'\n');
      case 0x0C:
        out.write(r'\f');
      case 0x0D:
        out.write(r'\r');
      default:
        if (c < 0x20) {
          out.write('\\u${c.toRadixString(16).padLeft(4, '0')}');
        } else {
          out.writeCharCode(c);
        }
    }
  }
  out.write('"');
  return out.toString();
}

/// What the service says about the release, sent with the prompt as ground
/// truth. Every field the service doesn't give is null or empty and is then
/// left out of the prompt.
@immutable
class AlbumAboutGrounding {
  const AlbumAboutGrounding({
    required this.album,
    required this.artist,
    this.version,
    this.releaseDate,
    this.originalReleaseDate,
    this.labels = const [],
    this.releaseType,
    this.genre,
    this.tracks = const [],
    this.totalDurationSec,
    this.serviceNotes,
  });

  final String album;
  final String artist;
  final String? version;

  /// This release's date, ISO at the precision the service gave ("2023-10-27", "2023-10", "2023").
  final String? releaseDate;

  /// The first release's date when the service gives it apart from [releaseDate], ISO like it.
  final String? originalReleaseDate;
  final List<String> labels;

  /// "Album", "EP", "Single", "Compilation"; null when the service doesn't say.
  final String? releaseType;
  final String? genre;

  /// Track titles in album order.
  final List<String> tracks;
  final int? totalDurationSec;

  /// The service's own notes on the album (Apple Music's editorial notes, as
  /// plain text): the AI is then asked for the credits only.
  final String? serviceNotes;
}

/// One About reply as read. [answered] tells "the model answered in the
/// format and knew nothing" (stored, so the page doesn't ask again) from a
/// reply that isn't an answer at all (a failure: nothing stored).
@immutable
class AlbumAboutReply {
  const AlbumAboutReply({required this.credits, required this.blurb, required this.answered});

  final AlbumAiCredits credits;

  /// The editorial paragraph; null when the AI wrote N/A, nothing, or when the service's notes are the paragraph.
  final String? blurb;
  final bool answered;

  /// Answered, but with nothing in it: a regenerate that comes back like this keeps what was there.
  bool get isEmpty => blurb == null && credits.isEmpty;

  AlbumAboutReply withoutBlurb() => AlbumAboutReply(credits: credits, blurb: null, answered: answered);

  @override
  bool operator ==(Object other) =>
      other is AlbumAboutReply && other.credits == credits && other.blurb == blurb && other.answered == answered;

  @override
  int get hashCode => Object.hash(credits, blurb, answered);
}

/// One stored About row of an album (Android's `AlbumAboutEntry`), as much as reading it needs.
@immutable
class AlbumAboutRow {
  const AlbumAboutRow({
    required this.kind,
    required this.entryKey,
    this.promptText,
    this.titleText,
    required this.answerText,
    required this.createdAt,
  });

  /// `canonical` or `ask`.
  final String kind;

  /// Canonical: `credits` / `blurb`; ask: the question's key.
  final String entryKey;
  final String? promptText;
  final String? titleText;
  final String answerText;
  final int createdAt;
}

/// One question about the album and the AI's answer.
@immutable
class AlbumAboutAsk {
  const AlbumAboutAsk({required this.question, this.title, required this.answer});

  final String question;
  final String? title;
  final String answer;

  @override
  bool operator ==(Object other) =>
      other is AlbumAboutAsk && other.question == question && other.title == title && other.answer == answer;

  @override
  int get hashCode => Object.hash(question, title, answer);
}

/// What the album page reads of an album's About: the AI's credits, its
/// paragraph and the questions asked.
@immutable
class AlbumAboutContent {
  const AlbumAboutContent({
    this.credits = AlbumAiCredits.none,
    this.blurb,
    this.asks = const [],
    this.generated = false,
  });

  static const none = AlbumAboutContent();

  final AlbumAiCredits credits;

  /// The editorial paragraph; null until written (or when the AI knew too little to write one).
  final String? blurb;

  /// Questions about the album, newest first; blank (removed) answers left out.
  final List<AlbumAboutAsk> asks;

  /// Canonical rows exist: the AI has been asked (an empty result is still an answer).
  final bool generated;

  @override
  bool operator ==(Object other) =>
      other is AlbumAboutContent &&
      other.credits == credits &&
      other.blurb == blurb &&
      listEquals(other.asks, asks) &&
      other.generated == generated;

  @override
  int get hashCode => Object.hash(credits, blurb, Object.hashAll(asks), generated);
}
