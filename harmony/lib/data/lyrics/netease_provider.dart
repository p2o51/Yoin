import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'lyric_provider.dart';

/// NetEase Cloud Music through Spotoolfy's proxy (Android
/// NetEaseLyricsProvider.kt): `cloudsearch` in NetEase's own order, then
/// `lyric/new`. Word-timed JSON lyrics are turned into plain LRC.
class NetEaseLyricsProvider extends LyricProvider {
  NetEaseLyricsProvider({http.Client? client, this.baseUrl = 'https://api.spotoolfy.gojyuplus.com'})
    : _client = client ?? http.Client();

  final http.Client _client;
  final String baseUrl;

  static const Duration _timeout = Duration(seconds: 15);

  @override
  String get name => 'netease';

  @override
  Future<List<SongMatch>> searchMultiple(String title, String artist, {int limit = 3}) async => [
    for (final c in await searchCandidates(title, artist, limit: limit)) c.match,
  ];

  /// `cloudsearch` in NetEase's own order, with what the translation lookup
  /// matches on (artists, album, length). Entries without an id are skipped.
  Future<List<NetEaseCandidate>> searchCandidates(String title, String artist, {int limit = 10}) async {
    if (limit <= 0) return const [];
    final uri = Uri.parse(
      '$baseUrl/cloudsearch',
    ).replace(queryParameters: {'keywords': '$title $artist', 'limit': '$limit'});
    try {
      final response = await _client.get(uri).timeout(_timeout);
      if (response.statusCode < 200 || response.statusCode >= 300) return const [];
      final root = jsonDecode(utf8.decode(response.bodyBytes));
      final songs = root is Map ? (root['result'] is Map ? root['result']['songs'] : null) : null;
      if (songs is! List) return const [];
      final out = <NetEaseCandidate>[];
      for (final song in songs) {
        if (out.length >= limit) break;
        if (song is! Map || song['id'] == null) continue;
        final artists = [
          if (song['ar'] is List)
            for (final a in song['ar'] as List)
              if (a is Map && a['name'] is String && (a['name'] as String).trim().isNotEmpty)
                (a['name'] as String).trim(),
        ];
        final name = song['name'] as String?;
        out.add(
          NetEaseCandidate(
            match: SongMatch(
              songId: '${song['id']}',
              title: name ?? title,
              artist: artists.isEmpty ? artist : artists.first,
            ),
            title: name,
            artists: artists,
            album: song['al'] is Map ? (song['al'] as Map)['name'] as String? : null,
            durationMs: (song['dt'] as num?)?.toInt(),
          ),
        );
      }
      return out;
    } catch (e) {
      debugPrint('NetEase search failed: ${e.runtimeType}');
      return const [];
    }
  }

  @override
  Future<String?> fetchLyric(String songId) async => (await fetchLyricWithTranslation(songId))?.lyric;

  /// The song's LRC and NetEase's own (Simplified Chinese) translation LRC,
  /// `tlyric` — null when it has none.
  Future<LyricPayload?> fetchLyricWithTranslation(String songId) async {
    final uri = Uri.parse('$baseUrl/lyric/new').replace(queryParameters: {'id': songId});
    try {
      final response = await _client.get(uri).timeout(_timeout);
      if (response.statusCode < 200 || response.statusCode >= 300) return null;
      final root = jsonDecode(utf8.decode(response.bodyBytes));
      if (root is! Map || root['lrc'] is! Map) return null;
      String? read(Object? block) {
        final raw = block is Map ? block['lyric'] : null;
        if (raw is! String || raw.isEmpty) return null;
        return raw.trimLeft().startsWith('{') ? netEaseJsonLyricToLrc(raw) : raw;
      }

      final lrc = read(root['lrc']);
      if (lrc == null) return null;
      return LyricPayload(lyric: lrc, translatedLyric: read(root['tlyric']));
    } catch (e) {
      debugPrint('NetEase lyric failed: ${e.runtimeType}');
      return null;
    }
  }
}

/// One NetEase search hit with what matching needs; [title] is null when NetEase sent none.
@immutable
final class NetEaseCandidate {
  const NetEaseCandidate({
    required this.match,
    required this.title,
    required this.artists,
    this.album,
    this.durationMs,
  });

  final SongMatch match;
  final String? title;
  final List<String> artists;
  final String? album;
  final int? durationMs;
}

/// A provider's lyric LRC and its own translation LRC (LyricPayload).
@immutable
final class LyricPayload {
  const LyricPayload({required this.lyric, this.translatedLyric});

  /// A translation line that says "no translation here" (QQ's convention; UNTRANSLATED_LINE).
  static const untranslatedLine = '//';

  final String lyric;
  final String? translatedLyric;
}

/// NetEase's word-timed JSON lines (`{"t": 12345, "c": [{"tx": "…"}, …]}`)
/// → standard LRC; plain lines pass through (parseJsonLyric).
@visibleForTesting
String? netEaseJsonLyricToLrc(String raw) {
  final out = <String>[];
  for (final line in raw.split('\n')) {
    final trimmed = line.trim();
    if (trimmed.isEmpty) continue;
    if (!trimmed.startsWith('{')) {
      out.add(line);
      continue;
    }
    try {
      final obj = jsonDecode(trimmed);
      if (obj is! Map || obj['c'] is! List) continue;
      final time = (obj['t'] as num?)?.toInt() ?? 0;
      final text = (obj['c'] as List).map((p) => p is Map ? (p['tx'] as String? ?? '') : '').join();
      String two(int v) => v.toString().padLeft(2, '0');
      out.add('[${two(time ~/ 60000)}:${two((time % 60000) ~/ 1000)}.${two((time % 1000) ~/ 10)}]$text');
    } catch (_) {
      continue;
    }
  }
  return out.isEmpty ? null : out.join('\n');
}
