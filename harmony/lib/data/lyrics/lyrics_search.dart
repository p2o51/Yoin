import 'package:flutter/foundation.dart';

import 'lyric_provider.dart';
import 'lyrics_registry.dart';

/// One provider's part of the manual search (Android LyricsSearchProviderSection).
@immutable
final class LyricsSearchSection {
  const LyricsSearchSection({required this.providerName, required this.matches, this.errorMessage});

  final String providerName;
  final List<SongMatch> matches;

  /// The provider couldn't answer at all (QQ with every host down).
  final String? errorMessage;
}

/// The lyrics a picked search result fetched, ready to apply.
@immutable
final class LyricsSearchHit {
  const LyricsSearchHit({required this.providerName, required this.providerSongId, required this.lrc});

  final String providerName;
  final String providerSongId;
  final String lrc;
}

/// The Search key's search (YoinRepository.searchLyricsProviderSections /
/// applyLyricsSearchResult) over the registry's providers, in its order:
/// QQ Music → NetEase → LRCLIB.
class LyricsSearch {
  LyricsSearch({LyricsProviderRegistry? registry, List<LyricProvider>? providers})
    : registry = registry ?? LyricsProviderRegistry(providers: providers);

  final LyricsProviderRegistry registry;

  /// Results shown per provider (YoinRepository.searchLyricsProviderSections).
  static const int perProvider = 3;

  List<String> get providerNames => registry.providerNames;

  /// The whole query as the title, no artist — as Android searches; every
  /// provider at once, each keeping its own order.
  Future<List<LyricsSearchSection>> search(String query) async {
    final q = query.trim();
    if (q.isEmpty) return const [];
    final results = await registry.searchByProvider(q, '', limitPerProvider: perProvider);
    return [
      for (final r in results)
        LyricsSearchSection(providerName: r.providerName, matches: r.matches, errorMessage: r.errorMessage),
    ];
  }

  /// The picked result's lyrics, normalized; null when the provider has none.
  Future<LyricsSearchHit?> fetch(String providerName, String songId) async {
    final hit = await registry.fetchSelectedLyric(providerName, songId);
    if (hit == null) return null;
    return LyricsSearchHit(providerName: hit.providerName, providerSongId: songId, lrc: hit.lrc);
  }
}
