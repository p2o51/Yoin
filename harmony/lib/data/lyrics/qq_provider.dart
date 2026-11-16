import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'lyric_candidate_matcher.dart';
import 'lyric_provider.dart';

/// QQ Music, no sign-in (Android QQLyricsProvider.kt). Two host families,
/// three kinds of call; no request is signed:
///
/// - **Web hosts** (`c.y.qq.com` …): `client_search_cp` searches;
///   `fcg_play_single_song` turns a numeric songid into its songmid;
///   `fcg_query_lyric_new` fetches a songmid's LRC (needs
///   `Referer: https://y.qq.com/`; original only, no translation).
/// - **Gateway hosts** (`u.y.qq.com` …): `musicu.fcg` with
///   `DoSearchForQQMusicDesktop` as the search fallback, and
///   `GetPlayLyricInfo` by numeric songID for base64 LRC plus translation —
///   the main way lyrics are fetched.
///
/// Song ids are always the numeric songid. Lyrics come from the gateway
/// first; when every gateway host fails, refuses or sends only ciphertext,
/// the web call runs with the mid remembered from the search (or looked up).
/// Each host family asks the host that last answered first. No Content-Type
/// is trusted: bodies are read as text (the web calls may wrap JSONP).
///
/// The automatic match ([search]) goes through [LyricCandidateMatcher] —
/// null rather than someone else's same-titled song; the manual search
/// ([searchMultiple]) keeps QQ's own order.
class QQLyricsProvider extends LyricProvider {
  QQLyricsProvider({
    http.Client? client,
    List<String> webBaseUrls = defaultWebBaseUrls,
    List<String> gatewayBaseUrls = defaultGatewayBaseUrls,
    LyricCandidateMatcher? matcher,
  }) : _client = client ?? http.Client(),
       _webHosts = _HostRotation(webBaseUrls),
       _gatewayHosts = _HostRotation(gatewayBaseUrls),
       _matcher = matcher ?? LyricCandidateMatcher();

  final http.Client _client;
  final _HostRotation _webHosts;
  final _HostRotation _gatewayHosts;
  final LyricCandidateMatcher _matcher;
  final _SongMidMemory _midsById = _SongMidMemory(_midMemoryCapacity);

  static const List<String> defaultWebBaseUrls = ['https://c.y.qq.com', 'https://shc.y.qq.com'];
  static const List<String> defaultGatewayBaseUrls = ['https://u.y.qq.com', 'https://u6.y.qq.com'];

  static const String _webSearchPath = '/soso/fcgi-bin/client_search_cp';
  static const String _songDetailPath = '/v8/fcg-bin/fcg_play_single_song.fcg';
  static const String _legacyLyricPath = '/lyric/fcgi-bin/fcg_query_lyric_new.fcg';
  static const String _gatewayPath = '/cgi-bin/musicu.fcg';
  static const String _webReferer = 'https://y.qq.com/';

  static const String _gatewayRequestKey = 'req_1';
  static const String _gatewayClientType = '19';
  static const String _gatewayClientVersion = '1859';
  static const String _searchModule = 'music.search.SearchCgiService';
  static const String _searchMethod = 'DoSearchForQQMusicDesktop';
  static const String _lyricModule = 'music.musichallSong.PlayLyricInfo';
  static const String _lyricMethod = 'GetPlayLyricInfo';

  static const int _searchPageSize = 10;
  static const int _midMemoryCapacity = 512;
  static const int _noLyricRetcode = -1901;
  static const String _artistSeparator = ' / ';
  static const String _translationPlaceholder = '//';
  static const String _instrumentalPlaceholder = '此歌曲为没有填词的纯音乐，请您欣赏';
  static const Duration _timeout = Duration(seconds: 15);

  static final RegExp _hexText = RegExp(r'^[0-9A-Fa-f]+$');
  static final RegExp _leadingTimestamps = RegExp(r'^(?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\])+');
  static final RegExp _idTag = RegExp(r'^\[[A-Za-z]+:[^\]]*\]$');
  static final RegExp _kanaTag = RegExp(r'^\[kana:.*\]$');
  static final RegExp _jsonpCallback = RegExp(r'^[A-Za-z_$][\w$.]*$');

  @override
  String get name => 'qq';

  @override
  NativeTranslation get nativeTranslation => NativeTranslation.simplifiedChinese;

  @override
  Future<SongMatch?> search(String title, String artist, {int? durationMs}) async {
    final songs = await _findSongs(title, artist, _searchPageSize);
    if (songs == null) return null;
    final picked = _matcher.pick(
      [
        for (final s in songs)
          LyricCandidate(title: s.title, artists: s.artists, album: s.album, durationMs: s.durationMs),
      ],
      title,
      artist,
      durationMs: durationMs,
    );
    return picked == null ? null : songs[picked].toMatch();
  }

  @override
  Future<List<SongMatch>> searchMultiple(String title, String artist, {int limit = 3}) async {
    if (limit <= 0) return const [];
    final songs = await _findSongs(title, artist, limit);
    if (songs == null) throw const QQUnavailableException();
    return [for (final s in songs.take(limit)) s.toMatch()];
  }

  /// Only a positive numeric songid; an old cached songmid (letters and digits) is refused.
  @override
  bool canFetch(String songId) =>
      songId.isNotEmpty && RegExp(r'^[0-9]+$').hasMatch(songId) && (int.tryParse(songId) ?? 0) > 0;

  @override
  Future<String?> fetchLyric(String songId) async => (await _fetchPayload(songId))?.lyric;

  @override
  Future<LyricPayload?> fetchLyricWithTranslation(String songId) => _fetchPayload(songId);

  // Search ------------------------------------------------------------------

  /// Null = no host answered; an empty list is a real "nothing found".
  Future<List<_QQSong>?> _findSongs(String title, String artist, int pageSize) async {
    final query = [title, artist].map((s) => s.trim()).where((s) => s.isNotEmpty).join(' ');
    if (query.isEmpty) return const [];
    final songs =
        await _webHosts.firstAnswer((base) => _searchOnWeb(base, query, pageSize)) ??
        await _gatewayHosts.firstAnswer((base) => _searchOnGateway(base, query, pageSize));
    if (songs == null) return null;
    for (final s in songs.value) {
      _midsById.remember(s.id, s.mid);
    }
    return songs.value;
  }

  Future<_Answer<List<_QQSong>>?> _searchOnWeb(String base, String query, int pageSize) async {
    final uri = _endpoint(base, _webSearchPath)?.replace(
      queryParameters: {'w': query, 'format': 'json', 'n': '$pageSize', 'p': '1', 't': '0', 'cr': '1', 'new_json': '1'},
    );
    if (uri == null) return null;
    final root = await _fetchJson(() => _client.get(uri, headers: const {'Referer': _webReferer}), 'search');
    if (root == null || !_codeIsZero(root)) return null;
    final list = _obj(_obj(root['data'])?['song'])?['list'];
    if (list is! List) return null;
    return _Answer(_parseSongs(list));
  }

  Future<_Answer<List<_QQSong>>?> _searchOnGateway(String base, String query, int pageSize) async {
    final body = _gatewayBody(_searchModule, _searchMethod, {
      'query': query,
      'search_type': 0,
      'num_per_page': pageSize,
      'page_num': 1,
    });
    final data = await _callGateway(base, body, 'gateway search');
    final list = _obj(_obj(data?['body'])?['song'])?['list'];
    if (list is! List) return null;
    return _Answer(_parseSongs(list));
  }

  /// Entries without a numeric id can't be fetched: skipped.
  List<_QQSong> _parseSongs(List<Object?> list) {
    final out = <_QQSong>[];
    for (final element in list) {
      final item = _obj(element);
      if (item == null) continue;
      final id = int.tryParse(_string(item['id']) ?? '');
      if (id == null || id <= 0) continue;
      final title = _nonBlank(_string(item['title'])) ?? _nonBlank(_string(item['name']));
      if (title == null) continue;
      final singers = item['singer'];
      final artists = <String>[
        if (singers is List)
          for (final s in singers) ?_nonBlank(_string(_obj(s)?['name'])?.trim()),
      ];
      final album = [
        _string(_obj(item['album'])?['name']),
        _string(_obj(item['album'])?['title']),
        _string(item['albumname']),
      ].whereType<String>().where((s) => s.trim().isNotEmpty).firstOrNull;
      // `interval`: seconds.
      final seconds = int.tryParse(_string(item['interval']) ?? '');
      out.add(
        _QQSong(
          id: id,
          mid: _string(item['mid'])?.trim(),
          title: title,
          artists: artists,
          album: album?.trim(),
          durationMs: seconds != null && seconds > 0 ? seconds * 1000 : null,
        ),
      );
    }
    return out;
  }

  // Lyrics ------------------------------------------------------------------

  Future<LyricPayload?> _fetchPayload(String songId) async {
    if (!canFetch(songId)) return null;
    final id = int.parse(songId);
    final reply = (await _gatewayHosts.firstAnswer((base) => _lyricFromGateway(base, id)))?.value;
    // The gateway saying there are none means the web has none either.
    if (reply is _GatewayMissing) return null;
    final payload = reply is _GatewayFound ? reply.payload : await _lyricFromWeb(id);
    if (payload == null || _instrumental(payload.lyric)) return null;
    return payload;
  }

  Future<_Answer<_GatewayLyric>?> _lyricFromGateway(String base, int songId) async {
    final body = _gatewayBody(_lyricModule, _lyricMethod, {
      'songID': songId,
      'format': 'json',
      'crypt': 0,
      'qrc': 0,
      'roma': 0,
      'trans': 1,
    });
    final data = await _callGateway(base, body, 'lyric');
    if (data == null || !data.containsKey('lyric') || data['lyric'] is Map || data['lyric'] is List) return null;
    final field = _decodeLyricField(_string(data['lyric']));
    return _Answer(switch (field) {
      _FieldEmpty() => const _GatewayMissing(),
      // Word-timed QRC songs send ciphertext despite crypt:0; the other gateway is the same backend.
      _FieldUnreadable() => const _GatewayEncrypted(),
      _FieldText(:final value) => _GatewayFound(
        LyricPayload(lyric: value, translatedLyric: _cleanTranslation(_string(data['trans']))),
      ),
    });
  }

  Future<LyricPayload?> _lyricFromWeb(int songId) async {
    final mid = _midsById.recall(songId) ?? (await _webHosts.firstAnswer((base) => _resolveMid(base, songId)))?.value;
    if (mid == null) return null;
    final lyric = (await _webHosts.firstAnswer((base) => _legacyLyric(base, mid)))?.value;
    return lyric == null ? null : LyricPayload(lyric: lyric);
  }

  /// songid → songmid; none (or the wrong song) is an authoritative null.
  Future<_Answer<String?>?> _resolveMid(String base, int songId) async {
    final uri = _endpoint(base, _songDetailPath)?.replace(queryParameters: {'songid': '$songId', 'format': 'json'});
    if (uri == null) return null;
    final root = await _fetchJson(() => _client.get(uri, headers: const {'Referer': _webReferer}), 'song detail');
    if (root == null || !_codeIsZero(root)) return null;
    final songs = root['data'];
    if (songs is! List) return null;
    // Some ids (1, say) answer with an unrelated song: only an id that matches counts.
    final first = songs.isEmpty ? null : _obj(songs.first);
    final mid = first != null && int.tryParse(_string(first['id']) ?? '') == songId
        ? _nonBlank(_string(first['mid'])?.trim())
        : null;
    _midsById.remember(songId, mid);
    return _Answer(mid);
  }

  /// A songmid's original. `-1901` = no lyrics (authoritative); any other non-zero tries the next host.
  Future<_Answer<String?>?> _legacyLyric(String base, String mid) async {
    final uri = _endpoint(
      base,
      _legacyLyricPath,
    )?.replace(queryParameters: {'songmid': mid, 'format': 'json', 'nobase64': '1'});
    if (uri == null) return null;
    final root = await _fetchJson(() => _client.get(uri, headers: const {'Referer': _webReferer}), 'legacy lyric');
    if (root == null) return null;
    final retcode = _int(root['retcode']) ?? _int(root['code']);
    if (retcode == 0) {
      return switch (_decodeLyricField(_string(root['lyric']))) {
        _FieldEmpty() => const _Answer(null),
        _FieldUnreadable() => null,
        _FieldText(:final value) => _Answer(value),
      };
    }
    if (retcode == _noLyricRetcode) return const _Answer(null);
    debugPrint('QQ legacy lyric refused: $retcode');
    return null;
  }

  // Payload decoding ----------------------------------------------------------

  /// `lyric` / `trans` come as plain LRC (starting `[`), base64 LRC or hex
  /// ciphertext. Hex is valid base64 too, so ciphertext is ruled out first.
  static _LyricField _decodeLyricField(String? raw) {
    final trimmed = raw?.trim() ?? '';
    if (trimmed.isEmpty) return const _FieldEmpty();
    if (trimmed.startsWith('[')) return _FieldText(trimmed);
    final compact = trimmed.replaceAll(RegExp(r'\s'), '');
    if (compact.length.isEven && _hexText.hasMatch(compact)) return const _FieldUnreadable();
    final decoded = _decodeBase64Utf8(compact);
    if (decoded == null) return const _FieldUnreadable();
    return decoded.trim().isEmpty ? const _FieldEmpty() : _FieldText(decoded);
  }

  static String? _decodeBase64Utf8(String value) {
    try {
      return utf8.decode(base64.decode(base64.normalize(value)));
    } on FormatException {
      return null;
    }
  }

  /// The gateway's translation is LRC on the original's timeline. Japanese
  /// songs' `[kana:…]` lines and `//` placeholders ("this line isn't
  /// translated") go; none with a translated line left = no translation.
  static String? _cleanTranslation(String? raw) {
    final field = _decodeLyricField(raw);
    if (field is! _FieldText) return null;
    final kept = const LineSplitter().convert(field.value).where((line) {
      final trimmed = line.trim();
      return !_kanaTag.hasMatch(trimmed) && _lineText(trimmed) != _translationPlaceholder;
    }).toList();
    final hasTranslatedLine = kept.any(
      (line) => _leadingTimestamps.hasMatch(line.trim()) && _lineText(line).isNotEmpty,
    );
    return hasTranslatedLine ? kept.join('\n') : null;
  }

  /// Instrumentals come back on both paths as one line
  /// `[00:00:00]此歌曲为没有填词的纯音乐，请您欣赏` (with a broken stamp): nothing
  /// to follow, so no lyrics.
  static bool _instrumental(String lyric) {
    final texts = const LineSplitter()
        .convert(lyric)
        .map(_lineText)
        .where((t) => t.isNotEmpty && !_idTag.hasMatch(t))
        .toList();
    return texts.isNotEmpty && texts.every((t) => t == _instrumentalPlaceholder);
  }

  /// The text after every leading timestamp.
  static String _lineText(String line) => line.trim().replaceFirst(_leadingTimestamps, '').trim();

  // HTTP / JSON -------------------------------------------------------------

  /// The gateway reply's `req_1.data`; a non-zero code at any level is this host refusing.
  Future<Map<String, Object?>?> _callGateway(String base, Map<String, Object?> body, String what) async {
    final uri = _endpoint(base, _gatewayPath);
    if (uri == null) return null;
    final root = await _fetchJson(
      () => _client.post(
        uri,
        headers: const {'content-type': 'application/json; charset=utf-8'},
        body: utf8.encode(jsonEncode(body)),
      ),
      what,
    );
    if (root == null || !_codeIsZero(root)) return null;
    final reply = _obj(root[_gatewayRequestKey]);
    if (reply == null) return null;
    final code = _int(reply['code']);
    if (code != 0) {
      debugPrint('QQ $what refused: $code');
      return null;
    }
    return _obj(reply['data']);
  }

  static Map<String, Object?> _gatewayBody(String module, String method, Map<String, Object?> param) => {
    'comm': {'ct': _gatewayClientType, 'cv': _gatewayClientVersion, 'uin': '0'},
    _gatewayRequestKey: {'module': module, 'method': method, 'param': param},
  };

  /// A 2xx body that parses as a JSON object; anything else is null.
  Future<Map<String, Object?>?> _fetchJson(Future<http.Response> Function() send, String what) async {
    final String text;
    try {
      final response = await send().timeout(_timeout);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        debugPrint('QQ $what failed: ${response.statusCode}');
        return null;
      }
      text = utf8.decode(response.bodyBytes, allowMalformed: true);
    } catch (e) {
      debugPrint('QQ $what error: ${e.runtimeType}');
      return null;
    }
    if (text.trim().isEmpty) return null;
    try {
      final parsed = jsonDecode(_unwrapJsonp(text));
      return parsed is Map ? parsed.cast<String, Object?>() : null;
    } catch (e) {
      debugPrint('QQ $what parse error: ${e.runtimeType}');
      return null;
    }
  }

  /// `MusicJsonCallback({...})` / `callback({...});` → `{...}`; bare JSON as it is.
  @visibleForTesting
  static String unwrapJsonp(String body) => _unwrapJsonp(body);

  static String _unwrapJsonp(String body) {
    final text = body.trim();
    if (text.startsWith('{') || text.startsWith('[')) return text;
    final open = text.indexOf('(');
    final close = text.lastIndexOf(')');
    if (open <= 0 || close <= open) return text;
    if (!_jsonpCallback.hasMatch(text.substring(0, open).trim())) return text;
    return text.substring(open + 1, close);
  }

  /// Joined, not resolved: a base may carry a path prefix (the tests' `/web1`).
  static Uri? _endpoint(String base, String path) => Uri.tryParse('$base$path');

  static bool _codeIsZero(Map<String, Object?> root) => !root.containsKey('code') || _int(root['code']) == 0;

  static Map<String, Object?>? _obj(Object? value) => value is Map ? value.cast<String, Object?>() : null;

  /// A JSON primitive's text (numbers too), as kotlinx's contentOrNull.
  static String? _string(Object? value) => switch (value) {
    final String s => s,
    final int n => '$n',
    final double d => d == d.roundToDouble() ? '${d.toInt()}' : '$d',
    final bool b => '$b',
    _ => null,
  };

  static int? _int(Object? value) => switch (value) {
    final int n => n,
    final double d when d == d.roundToDouble() => d.toInt(),
    final String s => int.tryParse(s),
    _ => null,
  };

  static String? _nonBlank(String? s) => s == null || s.trim().isEmpty ? null : s;
}

/// The manual search's error when no QQ host answered (UNAVAILABLE_MESSAGE).
final class QQUnavailableException implements Exception {
  const QQUnavailableException();

  @override
  String toString() => 'QQ Music is unavailable right now';
}

/// [album] only tells the matcher a live / interview album; [durationMs] only picks a same-titled recording.
final class _QQSong {
  _QQSong({
    required this.id,
    required this.mid,
    required this.title,
    required this.artists,
    required this.album,
    this.durationMs,
  });

  final int id;
  final String? mid;
  final String title;
  final List<String> artists;
  final String? album;
  final int? durationMs;

  SongMatch toMatch() =>
      SongMatch(songId: '$id', title: title, artist: artists.join(QQLyricsProvider._artistSeparator));
}

/// One host's authoritative answer; [value] itself may be "none". A null answer means: try the next host.
final class _Answer<T> {
  const _Answer(this.value);
  final T value;
}

sealed class _GatewayLyric {
  const _GatewayLyric();
}

final class _GatewayFound extends _GatewayLyric {
  const _GatewayFound(this.payload);
  final LyricPayload payload;
}

final class _GatewayMissing extends _GatewayLyric {
  const _GatewayMissing();
}

final class _GatewayEncrypted extends _GatewayLyric {
  const _GatewayEncrypted();
}

sealed class _LyricField {
  const _LyricField();
}

final class _FieldEmpty extends _LyricField {
  const _FieldEmpty();
}

final class _FieldUnreadable extends _LyricField {
  const _FieldUnreadable();
}

final class _FieldText extends _LyricField {
  const _FieldText(this.value);
  final String value;
}

/// A host family asked in turn, the one that last answered first. State belongs to this provider.
final class _HostRotation {
  _HostRotation(List<String> baseUrls) : _bases = [for (final b in baseUrls) b.replaceFirst(RegExp(r'/+$'), '')];

  final List<String> _bases;
  int _preferred = 0;

  Future<_Answer<T>?> firstAnswer<T>(Future<_Answer<T>?> Function(String base) attempt) async {
    final start = _preferred;
    for (var offset = 0; offset < _bases.length; offset++) {
      final index = (start + offset) % _bases.length;
      final answer = await attempt(_bases[index]);
      if (answer == null) continue;
      _preferred = index;
      return answer;
    }
    return null;
  }
}

/// songid → songmid seen in search results, for the web call when the gateway is down. A bounded LRU.
final class _SongMidMemory {
  _SongMidMemory(this._capacity);

  final int _capacity;
  final Map<int, String> _mids = {};

  void remember(int songId, String? mid) {
    if (mid == null || mid.trim().isEmpty) return;
    _mids.remove(songId);
    _mids[songId] = mid;
    while (_mids.length > _capacity) {
      _mids.remove(_mids.keys.first);
    }
  }

  String? recall(int songId) {
    final mid = _mids.remove(songId);
    if (mid != null) _mids[songId] = mid;
    return mid;
  }
}
