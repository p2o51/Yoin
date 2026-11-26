// The scrapbook's credits line (owner + Carbon 2026-11-25): under the album's
// title beside the cover, in place of Last Play. Items apart by " · ", names
// within an item by "、" (zh) / ", " (en), in a fixed order: label, producer,
// mastering (or mix), recorded; then, only when they apply, source and first
// release. Dart port of :shared `albumabout/AlbumCredits.kt`; pinned by
// shared/src/commonTest/resources/golden/album-about.json.
import 'package:flutter/foundation.dart';

import 'album_about_models.dart';
import 'kt_text.dart';

enum AlbumCreditKind {
  label('Label'),
  producer('Producer'),
  mastering('Mastering'),
  mixing('Mixing'),
  recorded('Recorded'),
  source('Source'),
  firstReleased('FirstReleased');

  const AlbumCreditKind(this.kotlinName);

  /// The Kotlin enum's name (the golden file's and logs' spelling).
  final String kotlinName;
}

/// One item of the line. [names] for the people / companies / studios;
/// [text] for the AI's free text (the recording time, the source work, the
/// first release); [date] for a first release the service dated (ISO at the
/// service's precision; each platform writes it in its own date style).
@immutable
class AlbumCreditItem {
  const AlbumCreditItem(this.kind, {this.names = const [], this.text, this.date});

  final AlbumCreditKind kind;
  final List<String> names;
  final String? text;
  final String? date;

  @override
  bool operator ==(Object other) =>
      other is AlbumCreditItem &&
      other.kind == kind &&
      listEquals(other.names, names) &&
      other.text == text &&
      other.date == date;

  @override
  int get hashCode => Object.hash(kind, Object.hashAll(names), text, date);

  @override
  String toString() => 'AlbumCreditItem(${kind.kotlinName}, $names, $text, $date)';
}

/// The words the line is built with, from the platform's strings ("厂牌",
/// "Label"…). [nameSeparator] goes between names within one item;
/// [timeAndPlace] is a format with `%1$s` (time) and `%2$s` (place).
@immutable
class AlbumCreditLabels {
  const AlbumCreditLabels({
    required this.label,
    required this.producer,
    required this.mastering,
    required this.mixing,
    required this.recorded,
    required this.source,
    required this.firstReleased,
    required this.nameSeparator,
    required this.timeAndPlace,
  });

  final String label;
  final String producer;
  final String mastering;
  final String mixing;
  final String recorded;
  final String source;
  final String firstReleased;
  final String nameSeparator;
  final String timeAndPlace;

  String labelOf(AlbumCreditKind kind) => switch (kind) {
    AlbumCreditKind.label => label,
    AlbumCreditKind.producer => producer,
    AlbumCreditKind.mastering => mastering,
    AlbumCreditKind.mixing => mixing,
    AlbumCreditKind.recorded => recorded,
    AlbumCreditKind.source => source,
    AlbumCreditKind.firstReleased => firstReleased,
  };

  String formatTimeAndPlace(String time, String place) =>
      timeAndPlace.replaceAllMapped(RegExp(r'%([12])\$s'), (m) => m.group(1) == '1' ? time : place);

  @override
  bool operator ==(Object other) =>
      other is AlbumCreditLabels &&
      other.label == label &&
      other.producer == producer &&
      other.mastering == mastering &&
      other.mixing == mixing &&
      other.recorded == recorded &&
      other.source == source &&
      other.firstReleased == firstReleased &&
      other.nameSeparator == nameSeparator &&
      other.timeAndPlace == timeAndPlace;

  @override
  int get hashCode =>
      Object.hash(label, producer, mastering, mixing, recorded, source, firstReleased, nameSeparator, timeAndPlace);
}

/// One credit as the About sheet lists it (label left, value right).
@immutable
class AlbumCreditRow {
  const AlbumCreditRow(this.kind, this.label, this.value);

  final AlbumCreditKind kind;
  final String label;
  final String value;

  @override
  bool operator ==(Object other) =>
      other is AlbumCreditRow && other.kind == kind && other.label == label && other.value == value;

  @override
  int get hashCode => Object.hash(kind, label, value);
}

/// The credits line, ready to lay out.
///
/// [text] is the line Android renders: items apart by the item separator (a
/// no-break space before the dot), the label glued to its value by a
/// no-break space, word joiners (U+2060) wherever the line must not break.
/// [segments] are the same line as runs that never break inside; they
/// concatenate to [plain], the line as read. [rows] are the items one by one.
@immutable
class AlbumCreditsLine {
  const AlbumCreditsLine({required this.text, required this.plain, required this.segments, required this.rows});

  final String text;
  final String plain;
  final List<String> segments;
  final List<AlbumCreditRow> rows;
}

/// The line's rules (Kotlin `internal object AlbumCredits`); reached through `YoinAlbumAbout`.
abstract final class AlbumCredits {
  /// Between items. The no-break space keeps the dot off the start of a line.
  static const itemSeparator = '\u00A0· ';

  /// Between an item's label and its value: never split.
  static const labelGap = '\u00A0';

  static const wordJoiner = '\u2060';

  static List<AlbumCreditItem> items({
    required List<String> sourceLabels,
    String? releaseDate,
    String? originalReleaseDate,
    required AlbumAiCredits ai,
  }) {
    final out = <AlbumCreditItem>[];
    var labels = ktDistinct(sourceLabels.map(ktTrim).where((l) => l.isNotEmpty));
    if (labels.isEmpty) labels = ai.label;
    if (labels.isNotEmpty) out.add(AlbumCreditItem(AlbumCreditKind.label, names: labels));
    if (ai.producers.isNotEmpty) out.add(AlbumCreditItem(AlbumCreditKind.producer, names: ai.producers));
    if (ai.mastering.isNotEmpty) {
      out.add(AlbumCreditItem(AlbumCreditKind.mastering, names: ai.mastering));
    } else if (ai.mixing.isNotEmpty) {
      out.add(AlbumCreditItem(AlbumCreditKind.mixing, names: ai.mixing));
    }
    if (ai.recordedTime != null || ai.recordedStudios.isNotEmpty) {
      out.add(AlbumCreditItem(AlbumCreditKind.recorded, names: ai.recordedStudios, text: ai.recordedTime));
    }
    if (ai.source != null) out.add(AlbumCreditItem(AlbumCreditKind.source, text: ai.source));
    final releaseYear = yearOf(releaseDate);
    final originalYear = yearOf(originalReleaseDate);
    final serviceFirst = (originalYear != null && releaseYear != null && originalYear < releaseYear)
        ? originalReleaseDate
        : null;
    if (serviceFirst != null) {
      out.add(AlbumCreditItem(AlbumCreditKind.firstReleased, date: serviceFirst));
    } else if (originalYear != null) {
      // The service dates the first release no earlier than this one: not a reissue, whatever the AI says.
    } else if (ai.firstReleased != null && _isEarlierThan(ai.firstReleased!, releaseYear)) {
      out.add(AlbumCreditItem(AlbumCreditKind.firstReleased, text: ai.firstReleased));
    }
    return out;
  }

  static final _isoYear = RegExp(r'^(\d{1,4})(?:-|$)');
  static final _yearRegex = RegExp(r'(?<!\d)(1[89]\d\d|20\d\d)(?!\d)');

  /// The year of an ISO date ("2014-05-20" → 2014); null when it doesn't start with one.
  static int? yearOf(String? iso) {
    if (iso == null) return null;
    final match = _isoYear.firstMatch(ktTrim(iso));
    final year = match == null ? null : int.tryParse(match.group(1)!);
    return year != null && year > 0 ? year : null;
  }

  static bool _isEarlierThan(String text, int? releaseYear) {
    if (releaseYear == null) return true;
    final years = [for (final m in _yearRegex.allMatches(text)) int.parse(m.group(0)!)];
    return years.isEmpty || years.reduce((a, b) => a < b ? a : b) < releaseYear;
  }

  /// One item's value as shown (no label); null when it holds nothing to show.
  static String? value(AlbumCreditItem item, AlbumCreditLabels labels, String? dateText) {
    final joined = item.names.join(labels.nameSeparator);
    final names = joined.isEmpty ? null : joined;
    switch (item.kind) {
      case AlbumCreditKind.recorded:
        if (item.text != null && names != null) return labels.formatTimeAndPlace(item.text!, names);
        return item.text ?? names;
      case AlbumCreditKind.source:
        return item.text;
      case AlbumCreditKind.firstReleased:
        return item.date != null ? (dateText ?? item.date) : item.text;
      default:
        return names;
    }
  }

  /// The line; null when no item is known.
  static AlbumCreditsLine? line(List<AlbumCreditItem> items, AlbumCreditLabels labels, String? dateText) {
    final rows = <AlbumCreditRow>[];
    for (final item in items) {
      final v = value(item, labels, dateText);
      if (v != null) rows.add(AlbumCreditRow(item.kind, labels.labelOf(item.kind), v));
    }
    if (rows.isEmpty) return null;
    final text = rows.map((r) => itemText(r.label, r.value)).join(itemSeparator);
    return AlbumCreditsLine(text: text, plain: plain(text), segments: segments(text), rows: rows);
  }

  /// One item as the line shows it: the label never leaves its value and
  /// never splits; inside the value, CJK runs hold together except after
  /// "，、；", dashes hold their neighbours, Latin text breaks between words.
  static String itemText(String label, String value) =>
      _joinCjk(label.replaceAll(' ', '\u00A0'), breakAfterComma: false) + labelGap + _joinCjk(value, breakAfterComma: true);

  /// [label] glued together: no-break spaces, word joiners between CJK characters.
  static String unbreakable(String label) => _joinCjk(label.replaceAll(' ', '\u00A0'), breakAfterComma: false);

  /// The line as read: word joiners gone, no-break spaces as spaces.
  static String plain(String text) => text.replaceAll(wordJoiner, '').replaceAll('\u00A0', ' ');

  static const int _wj = 0x2060;
  static const int _nbsp = 0xA0;
  static const int _space = 0x20;
  static const String _breakAfter = '-‐–—/';
  static const String _cjkCommas = '，、；';

  /// UAX #14 classes CL, CP, EX, IS and SY in Latin text: no break before them, even after spaces.
  static const String _noBreakAfterSpace = ')]}!?,.:;/';

  /// [text] cut where it may break, returned as read ([plain]).
  static List<String> segments(String text) {
    final out = <String>[];
    final current = StringBuffer();
    for (var i = 0; i < text.length; i++) {
      final c = text.codeUnitAt(i);
      final previous = i > 0 ? text.codeUnitAt(i - 1) : null;
      final breaks =
          previous != null &&
          (previous == _space || _cjkCommas.contains(String.fromCharCode(previous))) &&
          c != _wj &&
          c != _space &&
          c != _nbsp &&
          !(previous == _space && _noBreakAfterSpace.contains(String.fromCharCode(c)));
      if (breaks && current.isNotEmpty) {
        out.add(current.toString());
        current.clear();
      }
      if (c == _wj) continue;
      current.writeCharCode(c == _nbsp ? _space : c);
    }
    if (current.isNotEmpty) out.add(current.toString());
    return out;
  }

  static String _joinCjk(String text, {required bool breakAfterComma}) {
    final out = StringBuffer();
    for (var i = 0; i < text.length; i++) {
      final c = text.codeUnitAt(i);
      if (i > 0) {
        final previous = text.codeUnitAt(i - 1);
        final p = String.fromCharCode(previous);
        final joined =
            (_isCjk(c) || _isCjk(previous) || _breakAfter.contains(p)) && !(breakAfterComma && _cjkCommas.contains(p));
        if (joined) out.write(wordJoiner);
      }
      out.writeCharCode(c);
    }
    return out.toString();
  }

  /// The credits line's CJK (wider than the paragraph's: radicals and compatibility ideographs too).
  static bool _isCjk(int c) =>
      (c >= 0x2E80 && c <= 0x9FFF) || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFFEF);
}
