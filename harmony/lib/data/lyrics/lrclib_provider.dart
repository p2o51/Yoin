import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'lyric_candidate_matcher.dart';
import 'lyric_provider.dart';

/// LRCLIB (https://lrclib.net; Android LrclibLyricsProvider.kt): free, no key.
/// Search `/api/search?track_name=&artist_name=` (fuzzy, an array), fetch
/// `/api/get/{id}` and take `syncedLyrics`, else `plainLyrics`.
///
/// The automatic match drops `instrumental` hits, then lets
/// [LyricCandidateMatcher] check title, artist and album and pick the length
/// that fits — LRCLIB often has the single, the album and a compilation of
/// one song. When the matcher trusts none, the first hit is taken as before.
class LrclibLyricsProvider extends LyricProvider {
  LrclibLyricsProvider({http.Client? client, this.baseUrl = 'https://lrclib.net', LyricCandidateMatcher? matcher})
    : _client = client ?? http.Client(),
      _matcher = matcher ?? LyricCandidateMatcher();

  final http.Client _client;
  final String baseUrl;
  final LyricCandidateMatcher _matcher;

  static const Duration _timeout = Duration(seconds: 15);

  /// Hits the automatic match looks through for the length that fits.
  static const int _searchPageSize = 10;

  /// Several performers in one LRCLIB credit (`Sam Smith, Kim Petras`).
  static final RegExp _artistCreditSeparator = RegExp(
    r'\s*(?:[,&/;]|\s(?:feat\.?|ft\.|featuring)\s)\s*',
    caseSensitive: false,
  );

  /// LRCLIB asks for an identifiable User-Agent (Android sends the same).
  static const Map<String, String> _headers = {
    'user-agent': 'Yoin/0.1.0 (https://github.com/p2o51/Yoin)',
    'accept': 'application/json',
  };

  @override
  String get name => 'lrclib';

  @override
  Future<SongMatch?> search(String title, String artist, {int? durationMs}) async {
    final tracks = (await _findTracks(title, artist, _searchPageSize)).where((t) => !t.instrumental).toList();
    final picked = _matcher.pick(
      [
        for (final t in tracks)
          LyricCandidate(
            title: t.match.title,
            artists: _artistsOf(t.match.artist),
            album: t.album,
            durationMs: t.durationMs,
          ),
      ],
      title,
      artist,
      durationMs: durationMs,
    );
    return (picked == null ? tracks.firstOrNull : tracks[picked])?.match;
  }

  /// The whole credit and each performer in it go to the matcher.
  static List<String> _artistsOf(String credit) => {
    for (final a in [credit, ...credit.split(_artistCreditSeparator)]) a.trim(),
  }.where((a) => a.isNotEmpty).toList();

  @override
  Future<List<SongMatch>> searchMultiple(String title, String artist, {int limit = 3}) async => [
    for (final t in await _findTracks(title, artist, limit)) t.match,
  ];

  Future<List<_LrclibTrack>> _findTracks(String title, String artist, int limit) async {
    final uri = Uri.parse('$baseUrl/api/search').replace(queryParameters: {'track_name': title, 'artist_name': artist});
    try {
      final response = await _client.get(uri, headers: _headers).timeout(_timeout);
      if (response.statusCode < 200 || response.statusCode >= 300) return const [];
      final list = jsonDecode(utf8.decode(response.bodyBytes));
      if (list is! List) return const [];
      final out = <_LrclibTrack>[];
      for (final item in list.take(limit)) {
        if (item is! Map) continue;
        final id = item['id'];
        if (id == null) continue;
        // `duration`: seconds, maybe with a fraction.
        final seconds = item['duration'];
        final album = item['albumName'];
        out.add(
          _LrclibTrack(
            match: SongMatch(
              songId: '$id',
              title: (item['trackName'] as String?) ?? title,
              artist: (item['artistName'] as String?) ?? artist,
            ),
            album: album is String && album.trim().isNotEmpty ? album : null,
            durationMs: seconds is num && seconds > 0 ? (seconds * 1000).round() : null,
            instrumental: item['instrumental'] == true,
          ),
        );
      }
      return out;
    } catch (e) {
      debugPrint('LRCLIB search failed: ${e.runtimeType}');
      return const [];
    }
  }

  @override
  Future<String?> fetchLyric(String songId) async {
    try {
      final response = await _client.get(Uri.parse('$baseUrl/api/get/$songId'), headers: _headers).timeout(_timeout);
      if (response.statusCode < 200 || response.statusCode >= 300) return null;
      final obj = jsonDecode(utf8.decode(response.bodyBytes));
      if (obj is! Map) return null;
      final synced = obj['syncedLyrics'] as String?;
      final plain = obj['plainLyrics'] as String?;
      if (synced != null && synced.trim().isNotEmpty) return synced;
      if (plain != null && plain.trim().isNotEmpty) return plain;
      return null;
    } catch (e) {
      debugPrint('LRCLIB fetch failed: ${e.runtimeType}');
      return null;
    }
  }
}

final class _LrclibTrack {
  const _LrclibTrack({required this.match, required this.album, required this.durationMs, required this.instrumental});

  final SongMatch match;
  final String? album;
  final int? durationMs;
  final bool instrumental;
}
