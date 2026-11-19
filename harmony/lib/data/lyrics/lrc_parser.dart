import '../model/model.dart';

/// LRC text → [Lyrics] (Android data/lyrics/LrcParser.kt).
///
/// Understands `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss]` stamps, several stamps
/// on one line (one line each), and drops metadata tags (`[ar:]`, `[ti:]`,
/// `[offset:]` …). One timed line or more → [SyncedLyrics] sorted by start;
/// otherwise the text comes back as [UnsyncedLyrics]. Credits are taken out
/// later, where every lyric enters Now Playing (`lyricSheetOf`).
abstract final class LrcParser {
  static final RegExp _timestamp = RegExp(r'\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]');
  static final RegExp _leadingTimestamp = RegExp(r'^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]');
  static final RegExp _wordTimestamp = RegExp(r'<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>');
  static final RegExp _metadataTag = RegExp(r'^\[[a-zA-Z]+:[^\]]*\]$');
  static final RegExp _offsetTag = RegExp(r'^\[offset:\s*([+-]?\d+)\s*\]$', caseSensitive: false);

  static Lyrics parse(String raw) {
    final synced = <LyricLine>[];
    var offsetMs = 0;
    for (final rawLine in raw.split('\n')) {
      final line = rawLine.trim();
      if (line.isEmpty) continue;
      final offsetMatch = _offsetTag.firstMatch(line);
      if (offsetMatch != null) {
        offsetMs = int.tryParse(offsetMatch[1]!) ?? 0;
        continue;
      }
      if (_metadataTag.hasMatch(line) && !_timestamp.hasMatch(line)) continue;

      // Consume only consecutive leading timestamps as line prefixes; inline timestamps
      // (and enhanced word-by-word `<mm:ss.xx>` tags) are stripped without truncating earlier words.
      final starts = <int>[];
      var cursor = 0;
      while (cursor < line.length) {
        final m = _leadingTimestamp.firstMatch(line.substring(cursor));
        if (m == null) break;
        final ms = _ms(m);
        if (ms != null) starts.add(ms);
        cursor += m.end;
      }
      if (starts.isEmpty) continue;
      final text = line
          .substring(cursor)
          .replaceAll(_timestamp, '')
          .replaceAll(_wordTimestamp, '')
          .trim();
      if (text.isEmpty) continue;
      for (final start in starts) {
        synced.add(LyricLine(start, text));
      }
    }
    if (synced.isEmpty) return UnsyncedLyrics(raw.trim());
    final adjusted = offsetMs == 0
        ? synced
        : [
            for (final line in synced)
              LyricLine(line.startMs > offsetMs ? line.startMs - offsetMs : 0, line.text),
          ];
    adjusted.sort((a, b) => a.startMs.compareTo(b.startMs));
    return SyncedLyrics(adjusted);
  }

  static int? _ms(RegExpMatch m) {
    final minutes = int.tryParse(m[1]!);
    final seconds = int.tryParse(m[2]!);
    if (minutes == null || seconds == null) return null;
    final frac = m[3] ?? '';
    final fraction = switch (frac.length) {
      0 => 0,
      1 => int.parse(frac) * 100,
      2 => int.parse(frac) * 10,
      _ => int.parse(frac.substring(0, 3)),
    };
    return minutes * 60000 + seconds * 1000 + fraction;
  }

  /// `[mm:ss.xx]` for [ms] (LyricsSheets.kt toLrcTimestamp).
  static String timestamp(int ms) {
    final clamped = ms < 0 ? 0 : ms;
    final total = clamped ~/ 1000;
    String two(int v) => v.toString().padLeft(2, '0');
    return '[${two(total ~/ 60)}:${two(total % 60)}.${two((clamped % 1000) ~/ 10)}]';
  }
}
