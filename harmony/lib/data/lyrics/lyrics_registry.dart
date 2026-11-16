import 'package:flutter/foundation.dart';

import 'lrclib_provider.dart';
import 'lyric_provider.dart';
import 'netease_provider.dart';
import 'qq_provider.dart';

/// Lyrics a provider gave, and which one (Android LyricsProviderRegistry.Hit).
@immutable
final class LyricsHit {
  const LyricsHit({required this.lrc, required this.providerName, this.providerSongId});

  final String lrc;
  final String providerName;
  final String? providerSongId;
}

/// Lyrics and the provider's own translation of them (TranslationHit).
@immutable
final class LyricsTranslationHit {
  const LyricsTranslationHit({
    required this.lrc,
    required this.translatedLrc,
    required this.providerName,
    required this.providerSongId,
  });

  final String lrc;
  final String? translatedLrc;
  final String providerName;
  final String providerSongId;
}

/// One provider's part of the manual search (ProviderSearchResult); [errorMessage]
/// when the provider couldn't answer at all.
@immutable
final class LyricsProviderSearchResult {
  const LyricsProviderSearchResult({required this.providerName, required this.matches, this.errorMessage});

  final String providerName;
  final List<SongMatch> matches;
  final String? errorMessage;
}

/// The lyrics providers in priority order (Android LyricsProviderRegistry.kt):
/// - the automatic fallback walks the [LyricProvider.automatic] ones and the
///   first with non-empty LRC wins;
/// - the manual search asks every provider at once and keeps this order;
/// - a switch offered for a provider's own translation looks further down
///   this order ([translationSwitchCandidates]).
/// No caching, no translating, no extra timeouts (each provider has its own).
///
/// Order: QQ Music → NetEase → LRCLIB. The first two cover Chinese / J- and
/// K-pop and carry Simplified translations; LRCLIB is the FOSS fallback with
/// the widest Western catalogue. Android also lists Huawei Music (manual
/// search only) between NetEase and LRCLIB; it is not on HarmonyOS yet.
class LyricsProviderRegistry {
  LyricsProviderRegistry({List<LyricProvider>? providers})
    : providers = providers ?? [QQLyricsProvider(), NetEaseLyricsProvider(), LrclibLyricsProvider()];

  final List<LyricProvider> providers;

  /// The manual search's sections, in priority order, manual-only providers included.
  List<String> get providerNames => [for (final p in providers) p.name];

  /// Whether [providerName]'s own translation serves [targetLanguage]; an
  /// unknown name (manual, subsonic) never does.
  bool servesNativeTranslation(String providerName, String targetLanguage) =>
      _named(providerName)?.nativeTranslation?.serves(targetLanguage) ?? false;

  /// When the current source ([currentProviderName]) has no translation for
  /// this song: the sources whose own translation serves [targetLanguage]
  /// that a switch may be offered to — automatic, and after the current one.
  /// Only later ones: an earlier automatic source already failed this title
  /// and artist in the fallback, or the user skipped it by hand. A
  /// manual-only provider is never offered (the user never chose it).
  List<String> translationSwitchCandidates(String currentProviderName, String targetLanguage) {
    final current = providers.indexWhere((p) => p.name == currentProviderName);
    if (current < 0) return const [];
    return [
      for (final p in providers.skip(current + 1))
        if (p.automatic && (p.nativeTranslation?.serves(targetLanguage) ?? false)) p.name,
    ];
  }

  /// The automatic fallback: the first automatic provider that matches and has lyrics.
  Future<LyricsHit?> fetchLyric(String title, String artist, {int? durationMs}) async {
    for (final p in providers) {
      if (!p.automatic) continue;
      final SongMatch? match;
      try {
        match = await p.search(title, artist, durationMs: durationMs);
      } catch (_) {
        continue;
      }
      if (match == null) continue;
      final lrc = await p.fetchNormalizedLyric(match.songId);
      if (lrc == null) continue;
      return LyricsHit(lrc: lrc, providerName: p.name, providerSongId: match.songId);
    }
    return null;
  }

  /// Every provider at once, each in its own order and section.
  Future<List<LyricsProviderSearchResult>> searchByProvider(String title, String artist, {int limitPerProvider = 3}) =>
      Future.wait([
        for (final provider in providers)
          () async {
            try {
              final matches = await provider.searchMultiple(title, artist, limit: limitPerProvider);
              return LyricsProviderSearchResult(providerName: provider.name, matches: matches);
            } catch (e) {
              return LyricsProviderSearchResult(providerName: provider.name, matches: const [], errorMessage: '$e');
            }
          }(),
      ]);

  bool canFetch(String providerName, String songId) => _named(providerName)?.canFetch(songId) ?? false;

  Future<LyricsHit?> fetchSelectedLyric(String providerName, String songId) async {
    final provider = _named(providerName);
    if (provider == null || !provider.canFetch(songId)) return null;
    final lrc = await provider.fetchNormalizedLyric(songId);
    if (lrc == null) return null;
    return LyricsHit(lrc: lrc, providerName: provider.name, providerSongId: songId);
  }

  Future<LyricsTranslationHit?> fetchSelectedLyricWithTranslation(String providerName, String songId) async {
    final provider = _named(providerName);
    if (provider == null || !provider.canFetch(songId)) return null;
    final payload = await provider.fetchNormalizedLyricWithTranslation(songId);
    if (payload == null) return null;
    return LyricsTranslationHit(
      lrc: payload.lyric,
      translatedLrc: payload.translatedLyric,
      providerName: provider.name,
      providerSongId: songId,
    );
  }

  Future<LyricsTranslationHit?> searchAndFetchLyricWithTranslation(
    String providerName,
    String title,
    String artist, {
    int? durationMs,
  }) async {
    final provider = _named(providerName);
    if (provider == null) return null;
    final SongMatch? match;
    try {
      match = await provider.search(title, artist, durationMs: durationMs);
    } catch (_) {
      return null;
    }
    if (match == null) return null;
    final payload = await provider.fetchNormalizedLyricWithTranslation(match.songId);
    if (payload == null) return null;
    return LyricsTranslationHit(
      lrc: payload.lyric,
      translatedLrc: payload.translatedLyric,
      providerName: provider.name,
      providerSongId: match.songId,
    );
  }

  LyricProvider? _named(String name) => providers.where((p) => p.name == name).firstOrNull;
}
