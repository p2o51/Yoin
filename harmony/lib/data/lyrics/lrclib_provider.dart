import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'lyric_provider.dart';

/// LRCLIB (https://lrclib.net; Android LrclibLyricsProvider.kt): free, no key.
/// Search `/api/search?track_name=&artist_name=` (fuzzy, an array), fetch
/// `/api/get/{id}` and take `syncedLyrics`, else `plainLyrics`.
class LrclibLyricsProvider extends LyricProvider {
  LrclibLyricsProvider({http.Client? client, this.baseUrl = 'https://lrclib.net'}) : _client = client ?? http.Client();

  final http.Client _client;
  final String baseUrl;

  static const Duration _timeout = Duration(seconds: 15);

  /// LRCLIB asks for an identifiable User-Agent (Android sends the same).
  static const Map<String, String> _headers = {
    'user-agent': 'Yoin/0.1.0 (https://github.com/p2o51/Yoin)',
    'accept': 'application/json',
  };

  @override
  String get name => 'lrclib';

  @override
  Future<List<SongMatch>> searchMultiple(String title, String artist, {int limit = 3}) async {
    final uri = Uri.parse('$baseUrl/api/search').replace(queryParameters: {'track_name': title, 'artist_name': artist});
    try {
      final response = await _client.get(uri, headers: _headers).timeout(_timeout);
      if (response.statusCode < 200 || response.statusCode >= 300) return const [];
      final list = jsonDecode(utf8.decode(response.bodyBytes));
      if (list is! List) return const [];
      final out = <SongMatch>[];
      for (final item in list) {
        if (out.length >= limit) break;
        if (item is! Map) continue;
        final id = item['id'];
        if (id == null) continue;
        out.add(
          SongMatch(
            songId: '$id',
            title: (item['trackName'] as String?) ?? title,
            artist: (item['artistName'] as String?) ?? artist,
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
