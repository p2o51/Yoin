import 'package:flutter/foundation.dart';

/// One hit from a lyrics provider's search: its own id, title and artist.
@immutable
final class SongMatch {
  const SongMatch({required this.songId, required this.title, required this.artist});

  final String songId;
  final String title;
  final String artist;

  @override
  bool operator ==(Object other) =>
      other is SongMatch && other.songId == songId && other.title == title && other.artist == artist;

  @override
  int get hashCode => Object.hash(songId, title, artist);

  @override
  String toString() => 'SongMatch($songId, $title, $artist)';
}

/// A provider's lyric LRC and its own translation LRC (Android LyricPayload).
/// The translation is LRC on the same timeline; a line it doesn't translate
/// may read [untranslatedLine], so several lines on one timestamp still pair
/// by position.
@immutable
final class LyricPayload {
  const LyricPayload({required this.lyric, this.translatedLyric});

  /// A translation line that says "no translation here" (QQ's convention; UNTRANSLATED_LINE).
  static const untranslatedLine = '//';

  final String lyric;
  final String? translatedLyric;

  @override
  bool operator ==(Object other) =>
      other is LyricPayload && other.lyric == lyric && other.translatedLyric == translatedLyric;

  @override
  int get hashCode => Object.hash(lyric, translatedLyric);
}

/// What language a provider's own translation is in (Android NativeTranslation).
enum NativeTranslation {
  /// Simplified Chinese (QQ Music, NetEase, Huawei Music).
  simplifiedChinese;

  /// Whether this translation counts as one into [targetLanguage]: Simplified
  /// serves every Chinese target, Traditional included (Android's rule from
  /// before the providers multiplied — Traditional readers get Simplified).
  bool serves(String targetLanguage) => switch (this) {
    NativeTranslation.simplifiedChinese => isChineseTargetLanguage(targetLanguage),
  };
}

/// The translation setting's target is Chinese, Simplified or Traditional (isChineseTargetLanguage).
bool isChineseTargetLanguage(String language) {
  final normalized = language.trim().toLowerCase();
  return normalized == 'chinese' ||
      normalized.contains('中文') ||
      normalized.contains('汉语') ||
      normalized.contains('simplified chinese') ||
      normalized.contains('traditional chinese') ||
      normalized.contains('chinese (');
}

/// A third-party lyrics source (Android data/lyrics/LyricProvider.kt): search
/// by title + artist for the provider's own id, then fetch that id's LRC.
/// Failures (network, non-2xx, empty) come back as null / an empty list so the
/// registry can fall through to the next provider.
abstract class LyricProvider {
  /// Stored with the lyrics it gave and used to find it again for a
  /// translation: never renamed once shipped (Android keeps the same ids).
  String get name;

  /// Takes part in the automatic fallback chain and may be offered as the
  /// source to switch to for its translation. False: manual search only.
  bool get automatic => true;

  /// The language [fetchLyricWithTranslation]'s own translation is in; null = never translates.
  NativeTranslation? get nativeTranslation => null;

  /// The automatic match: the search hit that is this song, or null. [durationMs]
  /// (the playing song's length) picks among same-title recordings by length;
  /// see `LyricCandidateMatcher`.
  Future<SongMatch?> search(String title, String artist, {int? durationMs});

  /// The provider's own order, at most [limit] (the manual search). Throws
  /// when the provider couldn't answer at all, so the section can say so.
  Future<List<SongMatch>> searchMultiple(String title, String artist, {int limit = 3}) async {
    final match = await search(title, artist);
    return [?match].take(limit).toList();
  }

  /// Whether [songId] can be fetched as it is (an old QQ songmid can't).
  bool canFetch(String songId) => true;

  /// The raw LRC (or plain text) for [songId]; null when there is none.
  Future<String?> fetchLyric(String songId);

  /// The LRC and the provider's own translation; by default only the LRC.
  Future<LyricPayload?> fetchLyricWithTranslation(String songId) async {
    final raw = await fetchLyric(songId);
    if (raw == null) return null;
    final lyric = normalizeLyric(raw);
    return lyric.isEmpty ? null : LyricPayload(lyric: lyric);
  }

  /// [fetchLyric] made tidy: the few HTML entities providers leak, blank runs
  /// collapsed, trimmed; null when nothing is left.
  Future<String?> fetchNormalizedLyric(String songId) async {
    try {
      final raw = await fetchLyric(songId);
      if (raw == null) return null;
      final tidy = normalizeLyric(raw);
      return tidy.isEmpty ? null : tidy;
    } catch (_) {
      return null;
    }
  }

  /// [fetchLyricWithTranslation] made tidy, both halves.
  Future<LyricPayload?> fetchNormalizedLyricWithTranslation(String songId) async {
    try {
      final payload = await fetchLyricWithTranslation(songId);
      if (payload == null) return null;
      final lyric = normalizeLyric(payload.lyric);
      if (lyric.isEmpty) return null;
      final raw = payload.translatedLyric;
      final translated = raw == null ? null : normalizeLyric(raw);
      return LyricPayload(lyric: lyric, translatedLyric: translated == null || translated.isEmpty ? null : translated);
    } catch (_) {
      return null;
    }
  }
}

/// LyricProvider.normalizeLyric.
String normalizeLyric(String raw) => raw
    .replaceAll('&amp;', '&')
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&#39;', "'")
    .replaceAll('&apos;', "'")
    .replaceAll(RegExp(r'(\r?\n)+'), '\n')
    .trim();
