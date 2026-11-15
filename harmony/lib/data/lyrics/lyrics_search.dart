import 'package:flutter/foundation.dart';

import 'lyric_provider.dart';
import 'lrclib_provider.dart';
import 'netease_provider.dart';

/// One provider's part of the manual search (Android LyricsSearchProviderSection).
@immutable
final class LyricsSearchSection {
  const LyricsSearchSection({required this.providerName, required this.matches});

  final String providerName;
  final List<SongMatch> matches;
}

/// The lyrics a picked search result fetched, ready to apply.
@immutable
final class LyricsSearchHit {
  const LyricsSearchHit({required this.providerName, required this.providerSongId, required this.lrc});

  final String providerName;
  final String providerSongId;
  final String lrc;
}

/// The Search key's providers, in Android's manual-search order (QQ Music →
/// NetEase → Huawei Music → LRCLIB) less the two Harmony doesn't port yet:
/// QQ Music (signed `musicu.fcg` requests) and Huawei Music (manual-only on
/// Android, anonymous API with its own id scheme).
class LyricsSearch {
  LyricsSearch({List<LyricProvider>? providers})
    : providers = providers ?? [NetEaseLyricsProvider(), LrclibLyricsProvider()];

  final List<LyricProvider> providers;

  /// Results shown per provider (YoinRepository.searchLyricsProviderSections).
  static const int perProvider = 3;

  List<String> get providerNames => [for (final p in providers) p.name];

  /// The whole query as the title, no artist — as Android searches; every
  /// provider at once, each keeping its own order.
  Future<List<LyricsSearchSection>> search(String query) async {
    final q = query.trim();
    if (q.isEmpty) return const [];
    final results = await Future.wait([
      for (final p in providers) p.searchMultiple(q, '', limit: perProvider).catchError((Object _) => <SongMatch>[]),
    ]);
    return [
      for (var i = 0; i < providers.length; i++)
        LyricsSearchSection(providerName: providers[i].name, matches: results[i]),
    ];
  }

  /// The picked result's lyrics, normalized; null when the provider has none.
  Future<LyricsSearchHit?> fetch(String providerName, String songId) async {
    final provider = providers.where((p) => p.name == providerName).firstOrNull;
    if (provider == null) return null;
    final lrc = await provider.fetchNormalizedLyric(songId);
    if (lrc == null) return null;
    return LyricsSearchHit(providerName: providerName, providerSongId: songId, lrc: lrc);
  }
}
