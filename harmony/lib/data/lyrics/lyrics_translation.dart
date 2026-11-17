import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../model/model.dart';
import 'lrc_parser.dart';
import 'lyric_provider.dart';
import 'netease_provider.dart';

/// One of our lyric lines as translation sees it: its start (null = untimed) and text.
typedef LyricLineRef = ({int? startMs, String text});

/// A provider's translation LRC put onto its own lyric lines by timestamp
/// (Android YoinRepository.buildProviderTranslations): several lines on one
/// timestamp pair by position when both sides have as many, else the whole
/// group takes that timestamp's last translation; `//` placeholders count as
/// untranslated. With no timestamp in common it pairs by order.
Map<int, String> buildProviderTranslations(Lyrics lyrics, String translatedLrc) {
  final translated = LrcParser.parse(translatedLrc);
  if (lyrics is SyncedLyrics && translated is SyncedLyrics) {
    final offeredByStart = <int, List<LyricLine>>{};
    for (final line in translated.lines) {
      offeredByStart.putIfAbsent(line.startMs, () => []).add(line);
    }
    final originalsByStart = <int, List<int>>{};
    for (var i = 0; i < lyrics.lines.length; i++) {
      originalsByStart.putIfAbsent(lyrics.lines[i].startMs, () => []).add(i);
    }
    final exact = <int, String>{};
    originalsByStart.forEach((start, originals) {
      final offered = offeredByStart[start];
      if (offered == null) return;
      if (offered.length == originals.length) {
        for (var k = 0; k < originals.length; k++) {
          final t = _lineTranslation(offered[k].text);
          if (t != null) exact[originals[k]] = t;
        }
      } else {
        final last = offered.lastWhere((l) => l.text.trim().isNotEmpty, orElse: () => offered.last);
        final shared = _lineTranslation(last.text);
        if (shared == null) return;
        for (final i in originals) {
          exact[i] = shared;
        }
      }
    });
    if (exact.isNotEmpty) return exact;
  }
  final a = _lineTexts(lyrics);
  final b = _lineTexts(translated);
  final byOrder = <int, String>{};
  for (var i = 0; i < math.min(a.length, b.length); i++) {
    final t = _lineTranslation(b[i]);
    if (t != null) byOrder[i] = t;
  }
  return byOrder;
}

String? _lineTranslation(String text) {
  final t = text.trim();
  if (t.isEmpty || t == LyricPayload.untranslatedLine) return null;
  return t;
}

List<String> _lineTexts(Lyrics lyrics) => switch (lyrics) {
  SyncedLyrics(:final lines) => [for (final l in lines) l.text],
  UnsyncedLyrics(:final text) => [
    for (final l in text.split('\n'))
      if (l.trim().isNotEmpty) l.trim(),
  ],
};

/// How far a provider's line may sit from ours and still be the same line.
const int translationAlignToleranceMs = 600;

/// Lines whose stamps are this close are one line even if their texts differ (spelling, punctuation).
const int translationSameStampMs = 50;

/// Lowercase, no punctuation or spaces — two lines "say the same".
String _textKey(String text) => text.toLowerCase().replaceAll(RegExp(r'[\s\p{P}\p{S}]', unicode: true), '');

/// Puts the provider's translations ([providerTranslations], keyed by index
/// into [provider]) onto OUR lines by timestamp: a line takes the translation
/// of the provider line that says the same within
/// [translationAlignToleranceMs], or of the one on (nearly) the same stamp.
/// Untimed lyrics pair by order only when both sides say the same lines.
Map<int, String> alignTranslations(
  List<LyricLineRef> ours,
  List<LyricLineRef> provider,
  Map<int, String> providerTranslations,
) {
  final out = <int, String>{};
  final timed = ours.any((l) => l.startMs != null) && provider.any((l) => l.startMs != null);
  if (!timed) {
    final a = [for (final l in ours) _textKey(l.text)];
    final b = [for (final l in provider) _textKey(l.text)];
    if (!listEquals(a, b)) return out;
    for (var i = 0; i < ours.length; i++) {
      final t = providerTranslations[i];
      if (t != null) out[i] = t;
    }
    return out;
  }
  final used = <int>{};
  for (var i = 0; i < ours.length; i++) {
    final start = ours[i].startMs;
    if (start == null) continue;
    final key = _textKey(ours[i].text);
    int? best;
    var bestScore = 1 << 30;
    for (var j = 0; j < provider.length; j++) {
      final other = provider[j].startMs;
      if (other == null || used.contains(j) || !providerTranslations.containsKey(j)) continue;
      final gap = (other - start).abs();
      if (gap > translationAlignToleranceMs) continue;
      final same = _textKey(provider[j].text) == key;
      if (!same && gap > translationSameStampMs) continue;
      // The same words win over a closer stamp.
      final score = (same ? 0 : translationAlignToleranceMs + 1) + gap;
      if (score < bestScore) {
        bestScore = score;
        best = j;
      }
    }
    if (best != null) {
      used.add(best);
      out[i] = providerTranslations[best]!;
    }
  }
  return out;
}

/// Mostly Chinese already (Android appearsMostlyChinese): NetEase's
/// translation would add nothing.
bool lyricsAppearMostlyChinese(Iterable<String> lines) {
  var han = 0, kana = 0, hangul = 0, latin = 0;
  for (final line in lines) {
    for (final r in line.runes) {
      if ((r >= 0x4E00 && r <= 0x9FFF) || (r >= 0x3400 && r <= 0x4DBF) || (r >= 0xF900 && r <= 0xFAFF)) {
        han++;
      } else if ((r >= 0x3040 && r <= 0x30FF) || (r >= 0x31F0 && r <= 0x31FF)) {
        kana++;
      } else if ((r >= 0xAC00 && r <= 0xD7AF) || (r >= 0x1100 && r <= 0x11FF) || (r >= 0x3130 && r <= 0x318F)) {
        hangul++;
      } else if ((r >= 0x41 && r <= 0x5A) || (r >= 0x61 && r <= 0x7A) || (r >= 0xC0 && r <= 0x24F)) {
        latin++;
      }
    }
  }
  final meaningful = han + kana + hangul + latin;
  if (han < 8 || meaningful < 12) return false;
  if (kana > 0 || hangul > 0) return false;
  final ratio = han / meaningful;
  return ratio >= 0.72 || (han >= 20 && ratio >= 0.60);
}

/// NetEase's own translation put onto a song's lines by timestamp (owner
/// 2026-11-15: "网易云的先用着"): HarmonyOS's stopgap when no Gemini key is set
/// and the lyrics' own source has no translation. [providerSongId] is used
/// directly when the lyrics shown came from NetEase; otherwise NetEase's
/// automatic match ([LyricCandidateMatcher]) finds the song.
class NetEaseTranslator {
  NetEaseTranslator({NetEaseLyricsProvider? provider}) : _provider = provider ?? NetEaseLyricsProvider();

  final NetEaseLyricsProvider _provider;

  /// Our line index → translation; empty when NetEase has none that lines up.
  Future<Map<int, String>> translate({
    required List<LyricLineRef> lines,
    required String title,
    required String artist,
    int? durationMs,
    String? providerSongId,
  }) async {
    var songId = providerSongId;
    if (songId == null) {
      final match = await _provider.search(title, artist, durationMs: durationMs);
      if (match == null) return const {};
      songId = match.songId;
    }
    final payload = await _provider.fetchLyricWithTranslation(songId);
    final translated = payload?.translatedLyric;
    if (payload == null || translated == null || translated.trim().isEmpty) return const {};
    final theirs = LrcParser.parse(payload.lyric);
    final byTheirIndex = buildProviderTranslations(theirs, translated);
    if (byTheirIndex.isEmpty) return const {};
    final theirLines = switch (theirs) {
      SyncedLyrics(:final lines) => [for (final l in lines) (startMs: l.startMs as int?, text: l.text)],
      UnsyncedLyrics() => [for (final t in _lineTexts(theirs)) (startMs: null as int?, text: t)],
    };
    return alignTranslations(lines, theirLines, byTheirIndex);
  }
}

final netEaseTranslatorProvider = Provider<NetEaseTranslator>((ref) => NetEaseTranslator());
