import 'dart:convert';
import 'dart:math' as math;

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../platform/storage/stores.dart';
import '../model/model.dart';
import 'lrc_parser.dart';
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

/// The NetEase search hit that is this song (a small cut of Android's
/// LyricCandidateMatcher): the title must match (case, punctuation and a
/// trailing bracket note aside), the artist must when it can be compared, the
/// original beats a noted version (Live, Remix …), and a length within 3 s
/// beats one that isn't. Null when nothing can be trusted.
int? pickNetEaseCandidate(List<NetEaseCandidate> candidates, String title, String artist, int? durationMs) {
  final wanted = _textKey(title);
  final wantedCore = _textKey(_stripNotes(title));
  if (wantedCore.isEmpty) return null;
  final wantedArtists = artist
      .split(RegExp(r'\s*(?:[,&/;、]|\sfeat\.?\s|\sft\.\s)\s*', caseSensitive: false))
      .map(_textKey)
      .where((a) => a.isNotEmpty)
      .toSet();
  int? best;
  var bestScore = -1;
  for (var i = 0; i < candidates.length; i++) {
    final c = candidates[i];
    final offered = c.title;
    if (offered == null) continue;
    final full = _textKey(offered);
    final core = _textKey(_stripNotes(offered));
    final titleFit = full == wanted ? 2 : (core == wantedCore ? 1 : 0);
    if (titleFit == 0) continue;
    final performers = c.artists.map(_textKey).where((a) => a.isNotEmpty).toSet();
    final artistKnown = wantedArtists.isNotEmpty && performers.isNotEmpty;
    final artistFit = artistKnown && performers.any(wantedArtists.contains);
    if (artistKnown && !artistFit) continue;
    final lengthFit = durationMs != null && c.durationMs != null && (c.durationMs! - durationMs).abs() <= 3000;
    final score = titleFit * 4 + (artistFit ? 2 : 0) + (lengthFit ? 1 : 0);
    if (score > bestScore) {
      bestScore = score;
      best = i;
    }
  }
  return best;
}

String _stripNotes(String title) {
  var t = title.trim();
  while (true) {
    final next = t
        .replaceFirst(RegExp(r'\s*[\(\[（【][^\)\]）】]*[\)\]）】]\s*$'), '')
        .replaceFirst(RegExp(r'\s+-\s+[^-]+$'), '')
        .trim();
    if (next == t || next.isEmpty) return t;
    t = next;
  }
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

/// Translations kept per song (Android lyrics_translation_cache, model
/// `provider:netease`): keyed by the song and a hash of its lines, so new
/// lyrics never pick up an old translation.
class LyricsTranslationStore {
  LyricsTranslationStore(this._store);

  final KeyValueStore _store;

  static String keyFor(String songKey) => 'yoin.lyrics.translation.netease.$songKey';

  static String sourceHash(List<String> lines) => sha1.convert(utf8.encode(lines.join('\n'))).toString();

  Future<Map<int, String>?> read(String songKey, List<String> lines) async {
    final raw = await _store.read(keyFor(songKey));
    if (raw == null) return null;
    try {
      final json = jsonDecode(raw);
      if (json is! Map || json['hash'] != sourceHash(lines) || json['lines'] is! List) return null;
      final list = json['lines'] as List;
      return {
        for (var i = 0; i < list.length; i++)
          if (list[i] is String && (list[i] as String).isNotEmpty) i: list[i] as String,
      };
    } catch (_) {
      return null;
    }
  }

  Future<void> write(String songKey, List<String> lines, Map<int, String> translations) => _store.write(
    keyFor(songKey),
    jsonEncode({
      'hash': sourceHash(lines),
      'lines': [for (var i = 0; i < lines.length; i++) translations[i] ?? ''],
    }),
  );
}

/// NetEase's own translation for a song's lines (owner 2026-11-15: "网易云的先用着").
/// [providerSongId] is used directly when the lyrics shown came from NetEase;
/// otherwise NetEase is searched for title + artist.
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
      final candidates = await _provider.searchCandidates(title, artist);
      final picked = pickNetEaseCandidate(candidates, title, artist, durationMs);
      if (picked == null) return const {};
      songId = candidates[picked].match.songId;
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

/// The kept translations; tests without a key-value store get none.
final lyricsTranslationStoreProvider = Provider<LyricsTranslationStore?>((ref) {
  try {
    return LyricsTranslationStore(ref.watch(keyValueStoreProvider));
  } catch (_) {
    return null;
  }
});
