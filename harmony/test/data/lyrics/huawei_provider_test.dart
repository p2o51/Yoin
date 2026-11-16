import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/lyrics/huawei_provider.dart';
import 'package:yoin/data/lyrics/lrc_parser.dart';
import 'package:yoin/data/lyrics/lyric_provider.dart';
import 'package:yoin/data/lyrics/lyrics_registry.dart';
import 'package:yoin/data/model/model.dart';

/// Android HuaweiLyricsProviderTest over the same fixtures: anonymous Huawei
/// Music responses recorded 2026-10-04/05 from a JP IP, search lists cut to
/// the first few songs and the fields the provider reads, lyric files cut to
/// their header and first lines. Every request is routed by `/<host><path>`,
/// so the provider calls the same absolute https URLs it calls in production.
void main() {
  const searchPath = '/api-drcn.music.dbankcloud.cn/music-search-service/v10/service/fuzzysearch';
  const lemonLrcFile = '72a33102-7b76-4d58-a46c-fa533fd11a99.lrc';
  const movedLrcFile = '0b1e0000-0000-4000-8000-000000000000.lrc';
  const lemonLrcDir = '/music-pics-drcn.dbankcdn.cn/4c23bb0d6931ddf3fd18fed3a0a8372e/fccc85b2037b9d71f82f28a3a433384d/';
  const lemonLrcPath = lemonLrcDir + lemonLrcFile;
  const movedLrcPath = lemonLrcDir + movedLrcFile;

  late Map<String, http.Response Function(http.Request)> routes;
  late List<http.Request> seen;
  late HuaweiLyricsProvider provider;

  String fixture(String name) => File('test/data/lyrics/fixtures/huawei/$name').readAsStringSync();
  http.Response ok(String body, {Map<String, String> headers = const {}}) =>
      http.Response.bytes(utf8.encode(body), 200, headers: headers);
  String route(http.BaseRequest r) => '/${r.url.host}${r.url.path}';
  void on(String path, http.Response Function(http.Request) respond) => routes[path] = respond;
  void onSearch(http.Response Function(http.Request) respond) => on(searchPath, respond);

  HuaweiLyricsProvider newProvider() => HuaweiLyricsProvider(
    client: MockClient((request) async {
      seen.add(request);
      final handler = routes[route(request)];
      return handler?.call(request) ?? http.Response('', 404);
    }),
  );

  void reset() {
    routes = {};
    seen = [];
    provider = newProvider();
  }

  setUp(reset);

  String contentIdOf(String songId) => HuaweiSongRef.parse(songId)!.contentId;
  Map<String, Object?> bodyOf(http.Request r) => (jsonDecode(r.body) as Map).cast();
  String lrcUrl(String key) => 'https://music-pics-drcn.dbankcdn.cn/$key.lrc';
  String lemonSearchWithMovedLyric() => fixture('search_lemon.json').replaceAll(lemonLrcFile, movedLrcFile);

  Map<String, Object?> song({
    required String? id,
    required String? name,
    String? artist = 'Artist',
    String? lineByLine,
    String? subType = 'translate|scrolling',
    String? lyricAddress,
    String? lyricAddres,
    String? artistNames,
    String? album,
  }) => {
    'contentID': ?id,
    'contentName': ?name,
    'artistName': ?artist,
    'albumName': ?album,
    'lyricAddress': ?lyricAddress,
    'contentExInfo': jsonEncode({
      'lyrics': jsonEncode({'lineByLineLyric': ?lineByLine, 'lineByLineLyric_subType': ?subType}),
      'lyricAddres': ?lyricAddres,
      'artistNames': ?artistNames,
    }),
  };

  String searchBody(List<Map<String, Object?>> songs) => jsonEncode({
    'result': {'resultCode': '000000', 'resultMessage': 'Success!'},
    'songSimpleInfos': songs,
  });

  List<int> starts(String lrc) => [for (final l in (LrcParser.parse(lrc) as SyncedLyrics).lines) l.startMs];

  group('search', () {
    test('should_postSongFuzzySearch_when_searchingManually', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      await provider.searchMultiple('Lemon 米津玄師', '', limit: 3);
      final request = seen.single;
      expect(request.method, 'POST');
      expect(request.url.host, 'api-drcn.music.dbankcloud.cn');
      expect(request.headers['Content-Type'], startsWith('application/json'));
      final body = bodyOf(request);
      expect(body.values.every((v) => v is String), isTrue, reason: 'every field is sent as a string');
      expect(body, {'contentType': '1', 'queryWord': 'Lemon 米津玄師', 'start': '0', 'limit': '6'});
    });

    test('should_sendNoCredentials_when_searchingAndFetching', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      on(lemonLrcPath, (_) => ok(fixture('lemon.lrc')));
      final match = (await provider.searchMultiple('Lemon', '米津玄師')).first;
      await provider.fetchLyricWithTranslation(match.songId);
      expect(seen, hasLength(2));
      for (final request in seen) {
        final names = request.headers.keys.map((k) => k.toLowerCase());
        expect(names.any((n) => n == 'authorization' || n == 'cookie' || n.contains('token')), isFalse);
        expect(request.headers['User-Agent'], startsWith('Yoin/'));
      }
    });

    test('should_keepPlatformOrderAndSkipLyriclessEntries_when_searchMultiple', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      final matches = await provider.searchMultiple('Lemon', '米津玄師', limit: 3);
      // The music-box version (subType "abs") in second place has no lyrics to offer.
      expect(matches.map((m) => contentIdOf(m.songId)), ['14584432', 'MFAkyyYXn9TxOlrAp']);
      expect(matches[0], SongMatch(songId: matches[0].songId, title: 'Lemon', artist: '米津玄師(よねづ けんし)'));
      expect(matches[1].title, 'Lemon(翻自：米津玄師)');
      expect(await provider.searchMultiple('Lemon', '米津玄師', limit: 1), hasLength(1));
    });

    test('should_buildHintFromHuaweiNames_when_listingCandidates', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      final songId = (await provider.searchMultiple('lemon', '', limit: 1)).single.songId;
      expect(songId, startsWith('hw1|14584432|'));
      expect(provider.canFetch(songId), isTrue);
      // Huawei's own title + artist without the "(よねづ けんし)" alias: searching it finds the same contentID.
      expect(HuaweiSongRef.parse(songId), const HuaweiSongRef('14584432', 'Lemon 米津玄師'));
    });

    test('should_throwUnavailable_when_searchFails', () async {
      final failures = <http.Response Function(http.Request)>[
        (_) => http.Response('', 500),
        (_) => http.Response('', 400, headers: {'x-error-code': '400'}),
        (_) => ok('<html>gateway</html>'),
        (_) => ok('{"result":{"resultCode":"100001","resultMessage":"Failed"}}'),
        (_) => throw http.ClientException('connection reset'),
      ];
      for (final failure in failures) {
        onSearch(failure);
        await expectLater(
          provider.searchMultiple('Lemon', '米津玄師'),
          throwsA(
            isA<HuaweiUnavailableException>().having((e) => '$e', 'message', 'Huawei Music is unavailable right now'),
          ),
        );
        expect(await provider.search('Lemon', '米津玄師'), isNull);
      }
    });

    test('should_returnEmptyList_when_searchSucceedsWithoutSongs', () async {
      onSearch((_) => ok(fixture('search_empty.json')));
      expect(await provider.searchMultiple('zzzz', ''), isEmpty);
      expect(await provider.search('zzzz', 'nobody'), isNull);
    });

    test('should_skipEntries_when_contentIdOrNameMissing', () async {
      onSearch(
        (_) => ok(
          searchBody([
            song(id: null, name: 'No Id', lineByLine: lrcUrl('a')),
            song(id: '12345', name: null, lineByLine: lrcUrl('b')),
            song(id: 'bad|id', name: 'Pipe', lineByLine: lrcUrl('c')),
            song(id: '67890', name: 'Kept', lineByLine: lrcUrl('d')),
          ]),
        ),
      );
      expect((await provider.searchMultiple('x', '', limit: 5)).map((m) => contentIdOf(m.songId)), ['67890']);
    });

    test('should_readArtistFromContentExInfo_when_artistNameMissing', () async {
      onSearch(
        (_) =>
            ok(searchBody([song(id: '1', name: 'Usseewa', artist: null, artistNames: 'Ado', lineByLine: lrcUrl('a'))])),
      );
      expect((await provider.searchMultiple('Usseewa', '', limit: 1)).single.artist, 'Ado');
    });

    test('should_pickVerifiedCandidate_when_artistHasParentheticalAlias', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      final match = await provider.search('Lemon', '米津玄師');
      expect(contentIdOf(match!.songId), '14584432');
      expect(bodyOf(seen.single)['limit'], '10');
    });

    test('should_returnNull_when_artistCannotBeVerified', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      // Romanised artist vs. kanji on Huawei's side: undecidable, and only one cut.
      expect(await provider.search('Lemon', 'Kenshi Yonezu'), isNull);
    });

    test('should_pickTranslatedOriginal_when_titleCarriesChineseName', () async {
      // Huawei lists "Pretender(假装者)" (with translation) first and a plain
      // "Pretender" (no translation) third; the Chinese name is not a version note.
      onSearch((_) => ok(fixture('search_pretender.json')));
      final match = await provider.search('Pretender', 'Official髭男dism');
      expect(contentIdOf(match!.songId), '51044549');
    });

    test('should_preferStudioCut_when_plainTitleComesFromTourAlbum', () async {
      onSearch(
        (_) => ok(
          searchBody([
            song(
              id: '1',
              name: 'Pretender',
              artist: 'Official髭男dism',
              album: 'one-man tour 2021-2022 -Editorial-',
              lineByLine: lrcUrl('a'),
            ),
            song(id: '2', name: 'Pretender', artist: 'Official髭男dism', album: 'Traveler', lineByLine: lrcUrl('b')),
          ]),
        ),
      );
      expect(contentIdOf((await provider.search('Pretender', 'Official髭男dism'))!.songId), '2');
    });

    test('should_stripOnlyChineseTitleNames_when_preparingMatcherTitle', () {
      String strip(String t) => HuaweiLyricsProvider.withoutTranslatedTitle(t);
      expect(strip('Pretender(假装者)'), 'Pretender');
      expect(strip('アイドル(偶像)'), 'アイドル');
      expect(strip('夜に駆ける(向夜晚奔去)'), '夜に駆ける');
      expect(strip('Espresso(On Vacation Version)(浓缩咖啡)'), 'Espresso(On Vacation Version)');
      // Version notes and Chinese titles stay as they are.
      expect(strip('Lemon(伴奏)'), 'Lemon(伴奏)');
      expect(strip('Lemon(钢琴版)'), 'Lemon(钢琴版)');
      expect(strip('晴天(现场)'), '晴天(现场)');
      expect(strip('孤勇者(电视剧)'), '孤勇者(电视剧)');
      expect(strip('Blinding Lights(Live)'), 'Blinding Lights(Live)');
    });
  });

  group('lyric', () {
    test('should_fetchAndSplitTranslation_when_songSeenInSearch', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      on(lemonLrcPath, (_) => ok(fixture('lemon.lrc'), headers: {'content-type': 'application/octet-stream'}));
      final match = (await provider.searchMultiple('Lemon', '米津玄師')).first;
      final payload = (await provider.fetchLyricWithTranslation(match.songId))!;
      expect(seen.map((r) => r.method), ['POST', 'GET']);
      expect(route(seen.last), lemonLrcPath);
      expect(payload.lyric, isNot(contains('^')));
      expect(payload.lyric, contains('[00:01.60]夢ならば\n'));
      final translation = payload.translatedLyric!;
      expect(translation, contains('[00:01.60]如果只是一场梦'));
      // Title and credit lines share 00:00.00 and get placeholders, one per line.
      expect(translation.split('\n').where((l) => l == '[00:00.00]//'), hasLength(3));
      expect(starts(translation), starts(payload.lyric));
      expect(await provider.fetchLyric(match.songId), payload.lyric);
    });

    test('should_reResolveBySearch_when_memoryIsEmpty', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      on(lemonLrcPath, (_) => ok(fixture('lemon.lrc')));
      final songId = const HuaweiSongRef('14584432', 'Lemon 米津玄師').encode();
      expect(await newProvider().fetchLyric(songId), isNotNull);
      final body = bodyOf(seen.first);
      expect(body['queryWord'], 'Lemon 米津玄師');
      expect(body['limit'], '10');
      expect(route(seen.last), lemonLrcPath);
    });

    test('should_findOtherVersion_when_contentIdIsAChildContent', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      on('/lyric.tingmall.com/lyric/34/893/34893867-LRC-LRC.lrc', (_) => ok(fixture('lemon.lrc')));
      final lyric = await provider.fetchLyric(const HuaweiSongRef('34893867', 'Lemon 米津玄師').encode());
      expect(lyric, isNotNull);
      // The version parameter in the address is passed through untouched.
      expect(seen.last.url.queryParameters['t'], '1596882207000');
    });

    test('should_returnNull_when_reResolvedResultsLackContentId', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      // Same title, other recordings in the results: never substitute one of them.
      expect(await provider.fetchLyric(const HuaweiSongRef('99999999', 'Lemon 米津玄師').encode()), isNull);
      expect(seen, hasLength(1));
    });

    test('should_reResolveOnce_when_rememberedAddressIsGone', () async {
      final goneResponses = <http.Response Function(http.Request)>[
        (_) => http.Response('', 404),
        (_) => http.Response('', 403),
        (_) => http.Response('', 410),
        (_) => ok(fixture('nosuchkey.xml')),
        (_) => ok(''),
      ];
      for (final gone in goneResponses) {
        reset();
        final searches = [fixture('search_lemon.json'), lemonSearchWithMovedLyric()];
        onSearch((_) => ok(searches.removeAt(0)));
        on(lemonLrcPath, gone);
        on(movedLrcPath, (_) => ok(fixture('lemon.lrc')));
        final match = (await provider.searchMultiple('Lemon', '米津玄師', limit: 1)).single;
        final payload = await provider.fetchLyricWithTranslation(match.songId);
        expect(payload?.translatedLyric, isNotNull);
        expect(seen.map((r) => r.method == 'POST' ? 'search' : route(r)), [
          'search',
          lemonLrcPath,
          'search',
          movedLrcPath,
        ]);
      }
    });

    test('should_stopAfterOneRetry_when_reResolvedAddressIsGoneToo', () async {
      final searches = [fixture('search_lemon.json'), lemonSearchWithMovedLyric()];
      onSearch((_) => ok(searches.removeAt(0)));
      on(lemonLrcPath, (_) => http.Response('', 404));
      on(movedLrcPath, (_) => http.Response('', 404));
      final match = (await provider.searchMultiple('Lemon', '米津玄師', limit: 1)).single;
      expect(await provider.fetchLyric(match.songId), isNull);
      expect(seen, hasLength(4));
    });

    test('should_returnNull_when_reResolvedAddressIsUnchanged', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      on(lemonLrcPath, (_) => http.Response('', 404));
      final match = (await provider.searchMultiple('Lemon', '米津玄師', limit: 1)).single;
      expect(await provider.fetchLyric(match.songId), isNull);
      expect(seen.map((r) => r.method), ['POST', 'GET', 'POST']);
    });

    test('should_returnNull_when_freshlyResolvedAddressIsGone', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      on(lemonLrcPath, (_) => http.Response('', 404));
      expect(await provider.fetchLyric(const HuaweiSongRef('14584432', 'Lemon 米津玄師').encode()), isNull);
      expect(seen.map((r) => r.method), ['POST', 'GET']);
    });

    test('should_notReResolve_when_lyricFetchFailsTransiently', () async {
      final transientFailures = <http.Response Function(http.Request)>[
        (_) => http.Response('', 503),
        (_) => throw http.ClientException('connection reset'),
      ];
      for (final failure in transientFailures) {
        reset();
        onSearch((_) => ok(fixture('search_lemon.json')));
        on(lemonLrcPath, failure);
        final match = (await provider.searchMultiple('Lemon', '米津玄師', limit: 1)).single;
        expect(await provider.fetchLyricWithTranslation(match.songId), isNull);
        expect(seen.where((r) => r.method == 'POST'), hasLength(1));
        expect(seen.where((r) => r.method == 'GET').every((r) => route(r) == lemonLrcPath), isTrue);
      }
    });

    test('should_preferLineByLineLyric_then_lyricAddress_then_lyricAddres', () async {
      onSearch(
        (_) => ok(
          searchBody([
            song(
              id: '1',
              name: 'A',
              lineByLine: lrcUrl('line'),
              lyricAddress: lrcUrl('top'),
              lyricAddres: lrcUrl('ex'),
            ),
            song(id: '2', name: 'B', lyricAddress: lrcUrl('top'), lyricAddres: lrcUrl('ex')),
            song(id: '3', name: 'C', lyricAddres: lrcUrl('ex')),
          ]),
        ),
      );
      for (final key in ['line', 'top', 'ex']) {
        on('/music-pics-drcn.dbankcdn.cn/$key.lrc', (_) => ok('[00:01.00]$key'));
      }
      final ids = [for (final m in await provider.searchMultiple('x', '')) m.songId];
      expect(
        [for (final id in ids) await provider.fetchLyric(id)],
        ['[00:01.00]line', '[00:01.00]top', '[00:01.00]ex'],
      );
    });

    test('should_skipEntry_when_lyricAddressIsNotHttps', () async {
      onSearch(
        (_) => ok(searchBody([song(id: '1', name: 'Plain', lineByLine: 'http://music-pics-drcn.dbankcdn.cn/a.lrc')])),
      );
      expect(await provider.searchMultiple('Plain', ''), isEmpty);
      expect(await provider.fetchLyric(const HuaweiSongRef('1', 'Plain').encode()), isNull);
      expect(seen.every((r) => r.method == 'POST'), isTrue);
    });

    test('should_returnNull_when_lyricIsInstrumentalPlaceholder', () async {
      onSearch(
        (_) => ok(searchBody([song(id: '1', name: 'Theme', lineByLine: lrcUrl('theme'), subType: 'scrolling')])),
      );
      on('/music-pics-drcn.dbankcdn.cn/theme.lrc', (_) => ok(fixture('instrumental.lrc')));
      final match = (await provider.searchMultiple('Theme', '', limit: 1)).single;
      expect(await provider.fetchLyricWithTranslation(match.songId), isNull);
    });

    test('should_stripBomAndCrlf_when_lyricHostedOnTingmall', () async {
      const address = 'https://lyric.tingmall.com/lyric/09/285/9285974-LRC-LRC.lrc?t=1585123456000';
      onSearch((_) => ok(searchBody([song(id: '9285974', name: '打上花火', lineByLine: address)])));
      on('/lyric.tingmall.com/lyric/09/285/9285974-LRC-LRC.lrc', (_) => ok(fixture('uchiage_hanabi_bom_crlf.lrc')));
      final match = (await provider.searchMultiple('打上花火', '', limit: 1)).single;
      final payload = (await provider.fetchLyricWithTranslation(match.songId))!;
      expect(payload.lyric, startsWith('[ti:打上花火'));
      expect(payload.lyric.contains('\r') || payload.lyric.contains('﻿'), isFalse);
      expect(payload.lyric, contains('[00:20.30]あの日見わたした渚\n'));
      expect(payload.translatedLyric, contains('[00:20.30]直到现在我也能想起'));
      expect(payload.translatedLyric, contains('[00:06.69]//'));
    });

    test('should_keepCaretsInOriginal_when_subTypeHasNoTranslate', () async {
      onSearch((_) => ok(searchBody([song(id: '1', name: 'Up', lineByLine: lrcUrl('up'), subType: 'scrolling')])));
      on('/music-pics-drcn.dbankcdn.cn/up.lrc', (_) => ok('[00:01.00]Up ^ and down^ again'));
      final match = (await provider.searchMultiple('Up', '', limit: 1)).single;
      final payload = (await provider.fetchLyricWithTranslation(match.songId))!;
      expect(payload.lyric, '[00:01.00]Up ^ and down^ again');
      expect(payload.translatedLyric, isNull);
    });

    test('should_giveUp_when_lyricFileIsTooLarge', () async {
      onSearch((_) => ok(searchBody([song(id: '1', name: 'Big', lineByLine: lrcUrl('big'))])));
      on('/music-pics-drcn.dbankcdn.cn/big.lrc', (_) => ok('[00:01.00]${'x' * (512 * 1024)}'));
      final match = (await provider.searchMultiple('Big', '', limit: 1)).single;
      expect(await provider.fetchLyric(match.songId), isNull);
    });
  });

  group('id', () {
    test('should_rejectMalformedIds_when_canFetch', () async {
      const malformed = [
        '97773',
        '',
        'hw1|',
        'hw1||Lemon',
        'hw1|14584432|',
        'hw1|14584432|Lemon|extra',
        'hw2|14584432|Lemon',
        'hw1|1458 4432|Lemon',
        'hw1|14584432|%E3%8',
      ];
      for (final songId in malformed) {
        expect(provider.canFetch(songId), isFalse, reason: songId);
        expect(await provider.fetchLyric(songId), isNull);
      }
      expect(seen, isEmpty);
    });

    test('should_acceptDashAndUnderscore_when_canFetch', () {
      expect(provider.canFetch('hw1|NGZib8cFkavPHYYv_|Anti-Hero+Taylor+Swift'), isTrue);
      expect(provider.canFetch('hw1|NQ1PJ-GUJrtGMN4hk|Blinding+Lights+The+Weeknd'), isTrue);
    });

    test('should_roundTripSongRef_when_namesAreUnicode', () {
      const ref = HuaweiSongRef('66957894', 'アイドル(偶像) YOASOBI | live');
      final encoded = ref.encode();
      expect('|'.allMatches(encoded), hasLength(2));
      expect(HuaweiSongRef.parse(encoded), ref);

      final long = HuaweiSongRef('1', '🎵' * 150);
      final parsed = HuaweiSongRef.parse(long.encode())!;
      expect(parsed.hint.runes, hasLength(100));
      expect(parsed.hint, '🎵' * 100);
    });
  });

  group('registry', () {
    test('should_listHuaweiForManualSearchOnly_when_registryIsDefault', () {
      final registry = LyricsProviderRegistry();
      expect(registry.providerNames, ['qq', 'netease', 'huawei', 'lrclib']);
      expect(registry.providers.firstWhere((p) => p.name == 'huawei').automatic, isFalse);
      // Its own translation serves Chinese, but it is never offered as a switch (the user never chose it).
      expect(registry.servesNativeTranslation('huawei', 'Simplified Chinese'), isTrue);
      expect(registry.translationSwitchCandidates('netease', 'Simplified Chinese'), isNot(contains('huawei')));
    });

    test('should_neverAskHuawei_when_theAutomaticChainRuns', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      final registry = LyricsProviderRegistry(providers: [provider]);
      expect(await registry.fetchLyric('Lemon', '米津玄師'), isNull);
      expect(seen, isEmpty);
    });

    test('should_fetchTheTranslation_when_aHuaweiResultIsPicked', () async {
      onSearch((_) => ok(fixture('search_lemon.json')));
      on(lemonLrcPath, (_) => ok(fixture('lemon.lrc')));
      final registry = LyricsProviderRegistry(providers: [provider]);
      final sections = await registry.searchByProvider('Lemon', '米津玄師');
      final picked = sections.single.matches.first;
      final hit = await registry.fetchSelectedLyricWithTranslation('huawei', picked.songId);
      expect(hit?.providerName, 'huawei');
      expect(hit?.translatedLrc, contains('如果只是一场梦'));
    });
  });
}
