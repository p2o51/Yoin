import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'huawei_lrc.dart';
import 'lyric_candidate_matcher.dart';
import 'lyric_provider.dart';

/// Huawei Music (China region, drcn) lyrics through **anonymous public calls
/// only** (Android HuaweiLyricsProvider.kt): no Authorization, cookie,
/// device id or token. Two steps:
///
/// 1. **Search**: `POST …/music-search-service/v10/service/fuzzysearch`, a
///    JSON body of strings only (`contentType "1"` = songs). Only
///    `result.resultCode == "000000"` is an answer; nothing found is
///    `000000` too, just without `songSimpleInfos`. Each song's
///    `contentExInfo` is a JSON string whose `lyrics` is another JSON string
///    with the line-by-line lyric's address and `lineByLineLyric_subType`.
/// 2. **Lyric**: a plain GET of that address (a static, unsigned file on
///    dbankcdn or tingmall). A `subType` holding `translate` means the
///    Simplified Chinese translation is inline; [huaweiLrcSplit] splits it.
///
/// **Ids.** A lyric's address changes when the lyric is updated, so fetching
/// by id (days later, in another process, on another device) must be able to
/// find the address again: the song id is a self-resolving [HuaweiSongRef],
/// `hw1|<contentID>|<query hint>`. An LRU remembers contentID → address in
/// this process; with none, or a remembered one now gone, the hint is
/// searched again and the exact contentID looked for. None → no lyrics,
/// never another recording of the same name.
///
/// Manual search only ([automatic] false): the API is undocumented and in
/// the China region, not worth a call for every song in the automatic chain.
class HuaweiLyricsProvider extends LyricProvider {
  HuaweiLyricsProvider({http.Client? client, LyricCandidateMatcher? matcher})
    : _client = client ?? http.Client(),
      _matcher = matcher ?? LyricCandidateMatcher();

  final http.Client _client;
  final LyricCandidateMatcher _matcher;
  final _LyricFileMemory _lyricFiles = _LyricFileMemory(_lyricMemoryCapacity);

  static const String providerName = 'huawei';

  static final Uri searchUrl = Uri.parse(
    'https://api-drcn.music.dbankcloud.cn/music-search-service/v10/service/fuzzysearch',
  );
  static const String userAgent = 'Yoin/0.6.5 (https://github.com/p2o51/Yoin)';
  static const String _contentTypeSong = '1';
  static const String _successCode = '000000';
  static const String _noSuchKey = '<Code>NoSuchKey</Code>';
  static const String _subtypeInstrumental = 'abs';

  /// One hit is ~10 KB (`contentExInfo` is large): the automatic match and a
  /// re-resolve read one page of 10.
  static const int _searchPageSize = 10;
  static const int _maxPageSize = 20;
  static const int _lyricMemoryCapacity = 256;
  static const int _maxLyricBytes = 512 * 1024;
  static const Set<int> _goneStatusCodes = {403, 404, 410};
  static const Duration _timeout = Duration(seconds: 15);

  static final RegExp _contentId = RegExp(r'^[A-Za-z0-9_-]{1,64}$');
  static final RegExp _artistAlias = RegExp(r'^(.*?)\s*[(（]([^()（）]+)[)）]\s*$');
  static final RegExp _translatedTitle = RegExp(r'\s*[(（]([^()（）]+)[)）]\s*$');
  static final RegExp _han = RegExp(r'\p{Script=Han}', unicode: true);
  static final RegExp _letter = RegExp(r'\p{L}', unicode: true);
  static final RegExp _space = RegExp(r'\s');

  /// Words that make a bracketed part a version note (karaoke, live, cover …), not a translated title.
  static const List<String> _versionNoteWords = [
    '伴奏', '纯音乐', '版', '现场', '混音', '翻', '原唱', '改编', '铃声', '片段', //
    '演唱会', '剪辑', '合唱', '钢琴', '吉他', '八音盒', '音乐盒', '女声', '男声', //
    '加速', '降调', '升调', '慢速',
  ];

  @override
  String get name => providerName;

  @override
  bool get automatic => false;

  @override
  NativeTranslation get nativeTranslation => NativeTranslation.simplifiedChinese;

  @override
  Future<SongMatch?> search(String title, String artist, {int? durationMs}) async {
    final songs = (await _findSongs(_queryOf(title, artist), _searchPageSize))?.where((s) => s.hasUsableLyric).toList();
    if (songs == null) return null;
    final picked = _matcher.pick(
      [for (final s in songs) LyricCandidate(title: s.matcherTitle, artists: s.matcherArtists, album: s.album)],
      title,
      artist,
    );
    return picked == null ? null : songs[picked].toMatch();
  }

  /// Huawei's own order, minus the hits that surely have no lyric (no
  /// address, the instrumental placeholder): its catalogue is full of music
  /// box and karaoke cuts that would otherwise fill the three places, so
  /// more are asked for and the list cut after.
  @override
  Future<List<SongMatch>> searchMultiple(String title, String artist, {int limit = 3}) async {
    if (limit <= 0) return const [];
    final pageSize = math.min(limit * 2, math.max(limit, _maxPageSize));
    final songs = await _findSongs(_queryOf(title, artist), pageSize);
    if (songs == null) throw const HuaweiUnavailableException();
    return [for (final s in songs.where((s) => s.hasUsableLyric).take(limit)) s.toMatch()];
  }

  @override
  bool canFetch(String songId) => HuaweiSongRef.parse(songId) != null;

  @override
  Future<String?> fetchLyric(String songId) async => (await _fetchPayload(songId))?.lyric;

  @override
  Future<LyricPayload?> fetchLyricWithTranslation(String songId) => _fetchPayload(songId);

  // Lyric ---------------------------------------------------------------------

  Future<LyricPayload?> _fetchPayload(String songId) async {
    final ref = HuaweiSongRef.parse(songId);
    if (ref == null) return null;
    final remembered = _lyricFiles.recall(ref.contentId);
    final file = remembered ?? await _resolve(ref);
    if (file == null) return null;
    final download = await _download(file);
    switch (download) {
      case _Text(:final text):
        return huaweiLrcSplit(text, file.subTypes);
      // A network error or a 5xx: no re-resolve and no retry; the caller asks again later.
      case _Unavailable():
        return null;
      case _Gone():
        // An address found just now that is already gone: this song has no lyric now.
        if (remembered == null) return null;
        _lyricFiles.forget(ref.contentId);
        final fresh = await _resolve(ref);
        if (fresh == null || fresh.address == file.address) return null;
        final again = await _download(fresh);
        return again is _Text ? huaweiLrcSplit(again.text, fresh.subTypes) : null;
    }
  }

  /// Searches the id's hint again and takes only the entry with the very same
  /// contentID (another version of the song included).
  Future<_LyricFile?> _resolve(HuaweiSongRef ref) async {
    final songs = await _findSongs(ref.hint, _searchPageSize);
    if (songs == null) return null;
    final lyric = songs
        .expand((s) => [s, ...s.otherVersions])
        .where((s) => s.contentId == ref.contentId)
        .firstOrNull
        ?.lyric;
    return lyric != null && lyric.usable ? lyric : null;
  }

  Future<_Download> _download(_LyricFile file) async {
    final url = Uri.tryParse(file.address);
    if (url == null || !url.hasAuthority) return const _Gone();
    try {
      final request = http.Request('GET', url)..headers['User-Agent'] = userAgent;
      final response = await _client.send(request).timeout(_timeout);
      if (_goneStatusCodes.contains(response.statusCode)) {
        unawaited(response.stream.drain<void>().catchError((Object _) {}));
        return const _Gone();
      }
      if (response.statusCode < 200 || response.statusCode >= 300) {
        unawaited(response.stream.drain<void>().catchError((Object _) {}));
        debugPrint('Huawei lyric failed: ${response.statusCode}');
        return const _Unavailable();
      }
      final bytes = await _readCapped(response.stream).timeout(_timeout);
      if (bytes == null) {
        debugPrint('Huawei lyric larger than $_maxLyricBytes bytes');
        return const _Unavailable();
      }
      final text = utf8.decode(bytes, allowMalformed: true);
      if (text.trim().isEmpty || _isMissingObject(text)) return const _Gone();
      return _Text(text);
    } catch (e) {
      debugPrint('Huawei lyric error: ${e.runtimeType}');
      return const _Unavailable();
    }
  }

  /// The body, or null once it passes [_maxLyricBytes].
  static Future<List<int>?> _readCapped(Stream<List<int>> stream) async {
    final out = BytesBuilder(copy: false);
    await for (final chunk in stream) {
      out.add(chunk);
      if (out.length > _maxLyricBytes) return null;
    }
    return out.takeBytes();
  }

  /// A file deleted from tingmall answers with S3's `<Code>NoSuchKey</Code>` XML.
  static bool _isMissingObject(String text) => text.trimLeft().startsWith('<') && text.contains(_noSuchKey);

  // Search ----------------------------------------------------------------------

  /// Null = no answer (network, non-2xx, unparsable, wrong resultCode); an
  /// empty list is a real "nothing found".
  Future<List<_HuaweiSong>?> _findSongs(String query, int pageSize) async {
    if (query.isEmpty) return const [];
    final body = jsonEncode({'contentType': _contentTypeSong, 'queryWord': query, 'start': '0', 'limit': '$pageSize'});
    final Map<String, Object?>? root;
    try {
      final response = await _client
          .post(
            searchUrl,
            headers: const {'Content-Type': 'application/json; charset=utf-8', 'User-Agent': userAgent},
            body: utf8.encode(body),
          )
          .timeout(_timeout);
      if (response.statusCode < 200 || response.statusCode >= 300) {
        debugPrint('Huawei search failed: ${response.statusCode}');
        return null;
      }
      final parsed = jsonDecode(utf8.decode(response.bodyBytes, allowMalformed: true));
      root = parsed is Map ? parsed.cast<String, Object?>() : null;
    } catch (e) {
      debugPrint('Huawei search error: ${e.runtimeType}');
      return null;
    }
    if (root == null) return null;
    final resultCode = _string(_obj(root['result'])?['resultCode']);
    if (resultCode != _successCode) {
      debugPrint('Huawei search refused: $resultCode');
      return null;
    }
    final list = root['songSimpleInfos'];
    final songs = [
      if (list is List)
        for (final item in list) ?_parseSong(item),
    ];
    for (final song in songs.expand((s) => [s, ...s.otherVersions])) {
      final lyric = song.lyric;
      if (lyric != null && lyric.usable) _lyricFiles.remember(song.contentId, lyric);
    }
    return songs;
  }

  _HuaweiSong? _parseSong(Object? element) {
    final item = _obj(element);
    if (item == null) return null;
    final contentId = _string(item['contentID'])?.trim();
    if (contentId == null || !_contentId.hasMatch(contentId)) return null;
    final title = _string(item['contentName'])?.trim();
    if (title == null || title.isEmpty) return null;
    final extra = _nested(item, 'contentExInfo');
    final artistName = _string(item['artistName'])?.trim();
    final artistLine = artistName != null && artistName.isNotEmpty
        ? artistName
        : (_string(extra?['artistNames'])?.trim() ?? '');
    final album = _string(item['albumName'])?.trim();
    final children = item['childContents'];
    return _HuaweiSong(
      contentId: contentId,
      title: title,
      artistLine: artistLine,
      album: album == null || album.isEmpty ? null : album,
      lyric: _lyricFileOf(item, extra),
      otherVersions: [
        if (children is List)
          for (final c in children) ?_parseSong(c),
      ],
    );
  }

  /// The address from `lyrics.lineByLineLyric` → `lyricAddress` →
  /// `contentExInfo.lyricAddres` (one s short in the original), https only.
  _LyricFile? _lyricFileOf(Map<String, Object?> item, Map<String, Object?>? extra) {
    final lyrics = extra == null ? null : _nested(extra, 'lyrics');
    final address = [
      _string(lyrics?['lineByLineLyric']),
      _string(item['lyricAddress']),
      _string(extra?['lyricAddres']),
    ].whereType<String>().map((s) => s.trim()).where(_isHttps).firstOrNull;
    if (address == null) return null;
    final subTypes = {
      for (final part in (_string(lyrics?['lineByLineLyric_subType']) ?? '').split('|'))
        if (part.trim().isNotEmpty) part.trim(),
    };
    return _LyricFile(address, subTypes);
  }

  static bool _isHttps(String address) {
    final url = Uri.tryParse(address);
    return url != null && url.scheme.toLowerCase() == 'https' && url.host.isNotEmpty;
  }

  /// `contentExInfo` and `lyrics` are "JSON inside a JSON string"; a plain object is taken too.
  static Map<String, Object?>? _nested(Map<String, Object?> json, String key) {
    final value = json[key];
    if (value is Map) return value.cast<String, Object?>();
    final text = _string(value);
    if (text == null || text.trim().isEmpty) return null;
    try {
      final parsed = jsonDecode(text);
      return parsed is Map ? parsed.cast<String, Object?>() : null;
    } catch (_) {
      return null;
    }
  }

  static Map<String, Object?>? _obj(Object? value) => value is Map ? value.cast<String, Object?>() : null;

  /// A JSON primitive's text (numbers too), as kotlinx's contentOrNull.
  static String? _string(Object? value) => switch (value) {
    final String s => s,
    final int n => '$n',
    final double d => d == d.roundToDouble() ? '${d.toInt()}' : '$d',
    final bool b => '$b',
    _ => null,
  };

  static String _queryOf(String title, String artist) =>
      [title, artist].map((s) => s.trim()).where((s) => s.isNotEmpty).join(' ');

  static String _withoutAlias(String artist) {
    final alias = _artistAlias.firstMatch(artist);
    if (alias == null) return artist;
    final name = alias.group(1)!.trim();
    return name.isEmpty ? artist : name;
  }

  /// `アイドル(偶像)`, `Pretender(假装者)` → `アイドル`, `Pretender`. Only a final
  /// bracket that is **all Han**, after a title with non-Han letters (a
  /// foreign title), and not a version note. Kept, the translated original
  /// (which also carries the translation) would lose as a "noted" version to
  /// a later recording with neither.
  @visibleForTesting
  static String withoutTranslatedTitle(String title) {
    final match = _translatedTitle.firstMatch(title);
    if (match == null) return title;
    final core = title.substring(0, match.start);
    final note = match.group(1)!.trim();
    final noteIsChineseName =
        note.isNotEmpty &&
        note.runes.every((r) {
          final c = String.fromCharCode(r);
          return _han.hasMatch(c) || c == '·' || c == '・' || _space.hasMatch(c);
        }) &&
        !_versionNoteWords.any(note.contains);
    final coreIsForeign = core.runes.any((r) {
      final c = String.fromCharCode(r);
      return _letter.hasMatch(c) && !_han.hasMatch(c);
    });
    return noteIsChineseName && coreIsForeign ? core.trimRight() : title;
  }
}

/// The manual search's error when Huawei Music couldn't answer (UNAVAILABLE_MESSAGE).
final class HuaweiUnavailableException implements Exception {
  const HuaweiUnavailableException();

  @override
  String toString() => 'Huawei Music is unavailable right now';
}

/// A Huawei Music song's id outside the provider: `hw1|<contentID>|<query hint>`
/// (Android HuaweiSongRef). The hint is Huawei's own title plus the artists
/// without their aliases, URL-encoded (so it holds no `|`), cut to
/// [maxHintCodePoints] code points first. Searching it finds the same
/// contentID on page one (24/24 from Japan, 2026-10-05). The lyric's address
/// is not in the id: it names one version of the lyric file, and changes.
@immutable
final class HuaweiSongRef {
  const HuaweiSongRef(this.contentId, this.hint);

  final String contentId;
  final String hint;

  static const String _prefix = 'hw1';
  static const int maxHintCodePoints = 100;

  /// 100 four-byte code points URL-encode to at most 1200 characters.
  static const int _maxEncodedHint = 1200;
  static final RegExp _contentId = RegExp(r'^[A-Za-z0-9_-]{1,64}$');

  String encode() {
    final runes = hint.runes;
    final cut = runes.length <= maxHintCodePoints ? hint : String.fromCharCodes(runes.take(maxHintCodePoints));
    return '$_prefix|$contentId|${Uri.encodeQueryComponent(cut)}';
  }

  static HuaweiSongRef? parse(String songId) {
    final parts = songId.split('|');
    if (parts.length != 3 || parts[0] != _prefix) return null;
    final contentId = parts[1];
    final encodedHint = parts[2];
    if (!_contentId.hasMatch(contentId) || encodedHint.isEmpty || encodedHint.length > _maxEncodedHint) return null;
    final String hint;
    try {
      hint = Uri.decodeQueryComponent(encodedHint).trim();
    } catch (_) {
      return null;
    }
    return hint.isEmpty ? null : HuaweiSongRef(contentId, hint);
  }

  @override
  bool operator ==(Object other) => other is HuaweiSongRef && other.contentId == contentId && other.hint == hint;

  @override
  int get hashCode => Object.hash(contentId, hint);

  @override
  String toString() => 'HuaweiSongRef($contentId, $hint)';
}

final class _HuaweiSong {
  _HuaweiSong({
    required this.contentId,
    required this.title,
    required this.artistLine,
    required this.album,
    required this.lyric,
    required this.otherVersions,
  });

  final String contentId;
  final String title;

  /// The artists as Huawei writes them: `/` between people, an alias in
  /// brackets, e.g. `米津玄師(よねづ けんし)/Daoko(ダヲコ)`.
  final String artistLine;

  /// Only for the matcher to tell a live / interview album.
  final String? album;
  final _LyricFile? lyric;

  /// The same song on other albums (`childContents`), only for finding a contentID again.
  final List<_HuaweiSong> otherVersions;

  bool get hasUsableLyric => lyric?.usable ?? false;

  SongMatch toMatch() => SongMatch(
    songId: HuaweiSongRef(
      contentId,
      [title, ..._artistParts.map(HuaweiLyricsProvider._withoutAlias)].join(' '),
    ).encode(),
    title: title,
    artist: artistLine,
  );

  /// A Chinese name after a foreign title is no version note: dropped before comparing.
  String get matcherTitle => HuaweiLyricsProvider.withoutTranslatedTitle(title);

  /// `Name(Alias)` as two names, so `NewJeans` and `뉴진스` both match `NewJeans(뉴진스)`.
  List<String> get matcherArtists => [
    for (final part in _artistParts)
      ...switch (HuaweiLyricsProvider._artistAlias.firstMatch(part)) {
        final alias? when alias.group(1)!.trim().isNotEmpty => [alias.group(1)!.trim(), alias.group(2)!.trim()],
        _ => [part],
      },
  ];

  List<String> get _artistParts => [
    for (final part in artistLine.split(RegExp('[/／]')))
      if (part.trim().isNotEmpty) part.trim(),
  ];
}

final class _LyricFile {
  const _LyricFile(this.address, this.subTypes);

  final String address;
  final Set<String> subTypes;

  /// `abs` = one "instrumental" placeholder line, not worth a download.
  bool get usable => !subTypes.contains(HuaweiLyricsProvider._subtypeInstrumental);
}

sealed class _Download {
  const _Download();
}

final class _Text extends _Download {
  const _Text(this.text);
  final String text;
}

/// 403 / 404 / 410 / NoSuchKey / an empty file: nothing will ever be at this address again.
final class _Gone extends _Download {
  const _Gone();
}

final class _Unavailable extends _Download {
  const _Unavailable();
}

/// contentID → lyric file seen in a search. A bounded LRU, this provider's own.
final class _LyricFileMemory {
  _LyricFileMemory(this._capacity);

  final int _capacity;
  final Map<String, _LyricFile> _files = {};

  void remember(String contentId, _LyricFile file) {
    _files.remove(contentId);
    _files[contentId] = file;
    while (_files.length > _capacity) {
      _files.remove(_files.keys.first);
    }
  }

  _LyricFile? recall(String contentId) {
    final file = _files.remove(contentId);
    if (file != null) _files[contentId] = file;
    return file;
  }

  void forget(String contentId) => _files.remove(contentId);
}
