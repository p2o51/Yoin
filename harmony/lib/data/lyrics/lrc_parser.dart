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
  static final RegExp _metadataTag = RegExp(r'^\[[a-zA-Z]+:[^\]]*\]$');

  static Lyrics parse(String raw) {
    final synced = <LyricLine>[];
    for (final rawLine in raw.split('\n')) {
      final line = rawLine.trim();
      if (line.isEmpty) continue;
      if (_metadataTag.hasMatch(line) && !_timestamp.hasMatch(line)) continue;
      final matches = _timestamp.allMatches(line).toList();
      if (matches.isEmpty) continue;
      final text = line.substring(matches.last.end).trim();
      if (text.isEmpty) continue;
      for (final m in matches) {
        final start = _ms(m);
        if (start != null) synced.add(LyricLine(start, text));
      }
    }
    if (synced.isEmpty) return UnsyncedLyrics(raw.trim());
    synced.sort((a, b) => a.startMs.compareTo(b.startMs));
    return SyncedLyrics(synced);
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
