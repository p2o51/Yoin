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
}

/// A third-party lyrics source (Android data/lyrics/LyricProvider.kt): search
/// by title + artist for the provider's own ids, then fetch one id's LRC.
/// Failures (network, non-2xx, empty) come back as an empty list / null.
///
/// Harmony ports only the manual search (the Search key's sheet): the
/// automatic fallback chain and its `LyricCandidateMatcher` are not here.
abstract class LyricProvider {
  /// Stored with applied lyrics; never renamed once shipped (Android keeps the same ids).
  String get name;

  /// The provider's own order, at most [limit].
  Future<List<SongMatch>> searchMultiple(String title, String artist, {int limit = 3});

  /// The raw LRC (or plain text) for [songId]; null when there is none.
  Future<String?> fetchLyric(String songId);

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
