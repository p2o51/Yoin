import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'lyric_candidate_matcher.dart';
import 'lyric_provider.dart';

/// NetEase Cloud Music through Spotoolfy's proxy (Android
/// NetEaseLyricsProvider.kt): `cloudsearch`, then `lyric/new`. Word-timed
/// JSON lyrics are turned into plain LRC.
///
/// The automatic match ([search]) asks for more hits and lets
/// [LyricCandidateMatcher] check them — NetEase's first hit is often a cover,
/// someone else's song of the same name, or not even the same title; none it
/// can trust → null and the next provider tries. The manual search
/// ([searchMultiple]) keeps NetEase's own order.
class NetEaseLyricsProvider extends LyricProvider {
  NetEaseLyricsProvider({
    http.Client? client,
    this.baseUrl = 'https://api.spotoolfy.gojyuplus.com',
    LyricCandidateMatcher? matcher,
  }) : _client = client ?? http.Client(),
       _matcher = matcher ?? LyricCandidateMatcher();

  final http.Client _client;
  final String baseUrl;
  final LyricCandidateMatcher _matcher;

  static const Duration _timeout = Duration(seconds: 15);

  /// Hits the automatic match looks through (SEARCH_PAGE_SIZE, as QQ).
  static const int _searchPageSize = 10;

  @override
  String get name => 'netease';

  @override
  NativeTranslation get nativeTranslation => NativeTranslation.simplifiedChinese;

  @override
  Future<SongMatch?> search(String title, String artist, {int? durationMs}) async {
    final songs = await searchCandidates(title, artist, limit: _searchPageSize);
    final picked = _matcher.pick(
      [
        for (final s in songs)
          // A hit with no title is never compared as the requested one.
          LyricCandidate(title: s.title ?? '', artists: s.artists, album: s.album, durationMs: s.durationMs),
      ],
      title,
      artist,
      durationMs: durationMs,
    );
    return picked == null ? null : songs[picked].match;
  }

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
        final name = song['name'] is String ? song['name'] as String : null;
        final dt = song['dt'];
        out.add(
          NetEaseCandidate(
            match: SongMatch(
              songId: '${song['id']}',
              title: name ?? title,
              artist: artists.isEmpty ? artist : artists.first,
            ),
            title: name,
            artists: artists,
            album: song['al'] is Map && (song['al'] as Map)['name'] is String
                ? ((song['al'] as Map)['name'] as String).trim()
                : null,
            // `dt`: milliseconds.
            durationMs: dt is num && dt > 0 ? dt.toInt() : null,
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
  @override
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
