/// The small grammars every Memories surface shares (Android
/// ui/memories/copy MemoryScores, MemoryDates, MemorySentences): one score
/// rounding, one date grammar, whole-sentence handling.
library;

/// A calendar day: a UTC-midnight [DateTime] (Android LocalDate). Built by
/// a [MemoryDayOf]; nothing here reads the system clock or zone.
typedef MemoryDay = DateTime;

/// The local calendar day of an instant. Tests pass a fixed zone.
typedef MemoryDayOf = MemoryDay Function(int epochMs);

/// The device's zone.
MemoryDay localMemoryDay(int epochMs) {
  final d = DateTime.fromMillisecondsSinceEpoch(epochMs);
  return DateTime.utc(d.year, d.month, d.day);
}

/// The one score rounding every Memories surface shows: one decimal, halves
/// up, read from the score's shortest decimal form, so 9.95 shows "10.0"
/// (a binary multiply would show 9.9).
abstract final class MemoryScores {
  /// The displayed score in tenths (9.95 → 100). Scores are 0–10.
  static int tenths(double score) {
    if (score <= 0) return 0;
    final text = score.toString();
    if (text.contains('e')) return (score * 10 + 0.5).floor();
    final dot = text.indexOf('.');
    final whole = int.parse(dot < 0 ? text : text.substring(0, dot));
    final frac = dot < 0 ? '' : text.substring(dot + 1);
    final first = frac.isNotEmpty ? frac.codeUnitAt(0) - 48 : 0;
    final second = frac.length > 1 ? frac.codeUnitAt(1) - 48 : 0;
    return whole * 10 + first + (second >= 5 ? 1 : 0);
  }

  /// One decimal: "9.0", "8.5", "10.0".
  static String text(double score) {
    final t = tenths(score);
    return '${t ~/ 10}.${t % 10}';
  }
}

/// One date grammar for the whole Memories page (the approved prototype's
/// `day`, `dayZh`, `stats`): this year's dates drop the year ("Jul 26"),
/// any other year keeps it ("Nov 8, 2025"). Month names are fixed tables.
abstract final class MemoryDates {
  static const _monthShort = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  static const _monthEn = [
    'January', 'February', 'March', 'April', 'May', 'June', //
    'July', 'August', 'September', 'October', 'November', 'December',
  ];

  /// Months never take 两: 二月, never 两月 (两 is for counting only).
  static const zhMonths = ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月'];

  /// Whole calendar days from [from] to [to]; negative when [to] is earlier.
  static int daysBetween(MemoryDay from, MemoryDay to) =>
      (DateTime.utc(to.year, to.month, to.day).difference(DateTime.utc(from.year, from.month, from.day)).inHours / 24)
          .round();

  /// "Jul 26" this year, "Nov 8, 2025" in any other year.
  static String day(MemoryDay date, MemoryDay today) {
    final base = '${_monthShort[date.month - 1]} ${date.day}';
    return date.year != today.year ? '$base, ${date.year}' : base;
  }

  /// "7月26日" this year, "2025年11月8日" in any other year.
  static String dayZh(MemoryDay date, MemoryDay today) {
    final year = date.year != today.year ? '${date.year}年' : '';
    return '$year${date.month}月${date.day}日';
  }

  /// Always with the year: "Nov 8, 2025". Diary entry headers use it.
  static String dayWithYear(MemoryDay date) => '${_monthShort[date.month - 1]} ${date.day}, ${date.year}';

  /// Full month name: "February" / "二月".
  static String monthName(int month, {required bool zh}) => zh ? zhMonths[month - 1] : _monthEn[month - 1];
}

/// Whole-sentence handling for every excerpt Memories shows (the
/// prototype's `sentences`, `joinS`, `wlen`). The card never cuts a sentence
/// and never shows an ellipsis.
abstract final class MemorySentences {
  static const _fullStops = '。！？!?';
  static const _closers = '。！？!?.”’」）)';

  /// Splits on 。！？.!? A "." directly before a digit (8.5) is not a full
  /// stop. Closing quotes and brackets after a stop stay with its sentence.
  static List<String> split(String text) {
    final out = <String>[];
    final current = StringBuffer();
    var i = 0;
    while (i < text.length) {
      final c = text[i];
      current.write(c);
      final next = i + 1 < text.length ? text[i + 1] : null;
      final isDigit = next != null && next.compareTo('0') >= 0 && next.compareTo('9') <= 0;
      if (_fullStops.contains(c) || (c == '.' && !isDigit)) {
        while (i + 1 < text.length && _closers.contains(text[i + 1])) {
          i++;
          current.write(text[i]);
        }
        final s = current.toString().trim();
        if (s.isNotEmpty) out.add(s);
        current.clear();
      }
      i++;
    }
    final s = current.toString().trim();
    if (s.isNotEmpty) out.add(s);
    return out;
  }

  /// Joins sentences back into running text: a space between two sentences,
  /// none after CJK text or full-width punctuation. A closing ” takes the
  /// space unless the character it closes on is CJK.
  static String join(List<String> sentences) => sentences.fold('', (acc, sentence) {
    if (acc.isEmpty || !_needsSpaceAfter(acc)) return acc + sentence;
    return '$acc $sentence';
  });

  /// Weighted length for the Medium teaser cap: a Han character (and CJK or
  /// full-width punctuation) counts 1, anything else 0.5, per code point.
  static double weightedLength(String text) {
    var total = 0.0;
    for (final cp in text.runes) {
      total += (cp >= 0x2E80 && cp <= 0x9FFF) || (cp >= 0xF900 && cp <= 0xFFEF) ? 1.0 : 0.5;
    }
    return total;
  }

  static bool _needsSpaceAfter(String s) {
    final last = s.codeUnitAt(s.length - 1);
    if (_isCjkOrFullWidth(last)) return false;
    if (last == 0x201D) {
      if (s.length < 2) return true;
      return !_isCjkOrFullWidth(s.codeUnitAt(s.length - 2));
    }
    return true;
  }

  static bool _isCjkOrFullWidth(int c) =>
      (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF) || (c >= 0x3400 && c <= 0x9FFF);
}
