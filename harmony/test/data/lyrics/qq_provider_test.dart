import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/lyrics/lyric_provider.dart';
import 'package:yoin/data/lyrics/qq_provider.dart';

/// Android QQLyricsProviderTest, over the same fixtures (live QQ responses
/// recorded 2026-10-05, trimmed): two web hosts (`/web1`, `/web2`) and two
/// gateway hosts (`/gw1`, `/gw2`) on one fake server.
void main() {
  late Map<String, http.Response Function(http.Request)> routes;
  late List<http.Request> seen;
  late QQLyricsProvider provider;

  String fixture(String name) => File('test/data/lyrics/fixtures/qq/$name').readAsStringSync();
  http.Response ok(String body) => http.Response.bytes(utf8.encode(body), 200);
  void on(String prefix, http.Response Function(http.Request) respond) => routes[prefix] = respond;

  setUp(() {
    routes = {};
    seen = [];
    provider = QQLyricsProvider(
      client: MockClient((request) async {
        seen.add(request);
        final path = request.url.path;
        final handler = routes.entries.where((e) => path.startsWith(e.key)).firstOrNull?.value;
        return handler?.call(request) ?? http.Response('', 404);
      }),
      webBaseUrls: const ['https://qq.test/web1', 'https://qq.test/web2'],
      gatewayBaseUrls: const ['https://qq.test/gw1', 'https://qq.test/gw2'],
    );
  });

  group('search', () {
    test('should_returnArtistMatchedSong_when_webSearchAnswers', () async {
      on('/web1/soso/', (_) => ok(fixture('search_cp_qingtian.json')));
      expect(await provider.search('晴天', '周杰伦'), const SongMatch(songId: '97773', title: '晴天', artist: '周杰伦'));
      final request = seen.single;
      expect(request.method, 'GET');
      expect(request.url.queryParameters['w'], '晴天 周杰伦');
      expect(request.url.queryParameters['new_json'], '1');
      expect(request.url.queryParameters['n'], '10');
      expect(request.headers['Referer'], 'https://y.qq.com/');
    });

    test('should_tryNextWebHost_when_firstSearchHostReturns500', () async {
      on('/web1/soso/', (_) => http.Response('', 500));
      on('/web2/soso/', (_) => ok(fixture('search_cp_qingtian.json')));
      expect((await provider.search('晴天', '周杰伦'))?.songId, '97773');
      expect((await provider.search('晴天', '周杰伦'))?.songId, '97773');
      // The host that answered is asked first next time.
      expect(seen.where((r) => r.url.path.startsWith('/web1/')).length, 1);
      expect(seen.where((r) => r.url.path.startsWith('/web2/')).length, 2);
    });

    test('should_tryNextWebHost_when_searchBodyIsBlank', () async {
      on('/web1/soso/', (_) => ok(''));
      on('/web2/soso/', (_) => ok(fixture('search_cp_qingtian.json')));
      expect((await provider.search('晴天', '周杰伦'))?.songId, '97773');
    });

    test('should_fallBackToGatewaySearch_when_everyWebSearchHostFails', () async {
      on('/web1/soso/', (_) => http.Response('', 500));
      on('/web2/soso/', (_) => http.Response('', 502));
      on('/gw1/', (_) => ok(fixture('gateway_refused_2001.json')));
      on('/gw2/', (_) => ok(fixture('gateway_search_qingtian.json')));
      expect((await provider.search('晴天', '周杰伦'))?.songId, '97773');
      final body = seen.lastWhere((r) => r.url.path.startsWith('/gw2/')).body;
      expect(body, contains('"method":"DoSearchForQQMusicDesktop"'));
      expect(body, contains('"query":"晴天 周杰伦"'));
      expect(body, contains('"ct":"19"'));
    });

    test('should_throwUnavailable_when_everySearchRouteFails', () async {
      on('/', (_) => http.Response('', 500));
      await expectLater(
        provider.searchMultiple('晴天', '周杰伦'),
        throwsA(isA<QQUnavailableException>().having((e) => '$e', 'message', 'QQ Music is unavailable right now')),
      );
      expect(await provider.search('晴天', '周杰伦'), isNull);
    });

    test('should_returnNull_when_katakanaArtistLeavesSameTitleAmbiguous', () async {
      on('/web1/soso/', (_) => ok(fixture('search_cp_stupid_love_katakana.json')));
      expect(await provider.search('Stupid Love', 'レディー・ガガ'), isNull);
    });

    test('should_skipWrongArtistAtTop_when_artistIsComparable', () async {
      on('/web1/soso/', (_) => ok(fixture('search_cp_stupid_love_katakana.json')));
      expect(
        await provider.search('Stupid Love', 'Lady Gaga'),
        const SongMatch(songId: '267472054', title: 'Stupid Love', artist: 'Lady Gaga'),
      );
    });

    test('should_returnNull_when_unverifiableArtistMatchesSeveralRecordings', () async {
      on('/web1/soso/', (_) => ok(fixture('search_cp_what_was_that_katakana.json')));
      expect(await provider.search('What Was That', 'ロード'), isNull);
      expect((await provider.search('What Was That', 'Lorde'))?.songId, '586058642');
    });

    test('should_keepProviderOrder_when_searchingManually', () async {
      on('/web1/soso/', (_) => ok(fixture('search_cp_stupid_love_katakana.json')));
      final matches = await provider.searchMultiple('Stupid Love', '', limit: 3);
      expect(matches.map((m) => m.songId), ['5017814', '449497875', '267472054']);
      expect(seen.single.url.queryParameters['w'], 'Stupid Love');
      expect(seen.single.url.queryParameters['n'], '3');
    });

    test('should_preferStudioAlbum_when_plainTitleComesFromConcertAlbum', () async {
      const list = '''[
        {"id":"9001","title":"晴天","singer":[{"name":"周杰伦"}],"album":{"name":"周杰伦 2004 无与伦比 演唱会 Live CD"}},
        {"id":"9002","title":"晴天","singer":[{"name":"周杰伦"}],"albumname":"Live in Tokyo 1966"},
        {"id":"97773","title":"晴天","singer":[{"name":"周杰伦"}],"album":{"name":"叶惠美"}}
      ]''';
      on('/web1/soso/', (_) => ok('{"code":0,"data":{"song":{"list":$list}}}'));
      expect((await provider.search('晴天', '周杰伦'))?.songId, '97773');
    });

    test('should_skipSongsWithoutNumericId_when_parsingSearch', () async {
      const list = '[{"id":"abc123","title":"Lost Stars","singer":[{"name":"Adam Levine"}]}]';
      on('/web1/soso/', (_) => ok('{"code":0,"data":{"song":{"list":$list}}}'));
      expect(await provider.search('Lost Stars', 'Adam Levine'), isNull);
    });
  });

  group('lyric', () {
    test('should_decodeLyricAndCleanTranslation_when_gatewayAnswers', () async {
      on('/gw1/', (_) => ok(fixture('play_lyric_lemon_213086592.json')));
      final payload = await provider.fetchLyricWithTranslation('213086592');
      expect(payload, isNotNull);
      expect(payload!.lyric, startsWith('[ti:Lemon'));
      expect(payload.lyric, contains('[00:01.54]夢ならば'));
      final translation = payload.translatedLyric!;
      expect(translation, contains('[00:01.54]如果只是一场梦'));
      expect(const LineSplitter().convert(translation).any((l) => l.endsWith(']//')), isFalse);
      final body = seen.single.body;
      expect(body, contains('"songID":213086592'));
      expect(body, contains('"crypt":0'));
      expect(body, contains('"trans":1'));
      expect(body, isNot(contains('songMID')));
    });

    test('should_tryNextGatewayHost_when_lyricCodeIsNonZero', () async {
      on('/gw1/', (_) => ok('{"code":0,"req_1":{"code":24001}}'));
      on('/gw2/', (_) => ok(fixture('play_lyric_lemon_213086592.json')));
      expect(await provider.fetchLyric('213086592'), startsWith('[ti:Lemon'));
    });

    test('should_decodePlainBase64Lyric_when_gatewayAnswers', () async {
      const plain = '[00:01.00]hello world';
      final b64 = base64.encode(utf8.encode(plain));
      on('/gw1/', (_) => ok('{"req_1": {"code": 0, "data": {"lyric": "$b64"}}}'));
      expect(await provider.fetchLyric('97773'), plain);
    });

    test('should_useRememberedMid_when_gatewayIsUnreachable', () async {
      on('/web1/soso/', (_) => ok(fixture('search_cp_qingtian.json')));
      on('/gw', (_) => http.Response('', 500));
      on('/web1/lyric/', (_) => ok(fixture('legacy_lyric_qingtian_0039MnYb0qxYhV.json')));
      final match = (await provider.search('晴天', '周杰伦'))!;
      final payload = await provider.fetchLyricWithTranslation(match.songId);
      expect(payload!.lyric, startsWith('[ti:晴天]'));
      expect(payload.lyric, contains('故事的小黄花'));
      final lyricRequest = seen.singleWhere((r) => r.url.path.startsWith('/web1/lyric/'));
      expect(lyricRequest.url.queryParameters['songmid'], '0039MnYb0qxYhV');
      expect(lyricRequest.url.queryParameters['nobase64'], '1');
      expect(seen.any((r) => r.url.path.contains('fcg_play_single_song')), isFalse);
    });

    test('should_resolveMidBySongId_when_midIsUnknown', () async {
      on('/gw', (_) => http.Response('', 500));
      on('/web1/v8/', (_) => ok(fixture('song_detail_97773.json')));
      on('/web1/lyric/', (_) => ok(fixture('legacy_lyric_qingtian_jsonp_base64.txt')));
      expect(await provider.fetchLyric('97773'), startsWith('[ti:晴天]'));
      final detail = seen.singleWhere((r) => r.url.path.startsWith('/web1/v8/'));
      expect(detail.url.queryParameters['songid'], '97773');
    });

    test('should_useWebLyric_when_gatewaySendsCipherText', () async {
      on('/web1/soso/', (_) => ok(fixture('search_cp_qingtian.json')));
      on('/gw1/', (_) => ok('{"req_1":{"code":0,"data":{"lyric":"${'E5D94A70C91F7022' * 4}","trans":""}}}'));
      on('/web1/lyric/', (_) => ok(fixture('legacy_lyric_qingtian_0039MnYb0qxYhV.json')));
      await provider.search('晴天', '周杰伦');
      expect(await provider.fetchLyric('97773'), startsWith('[ti:晴天]'));
    });

    test('should_returnNull_when_gatewayAnswersWithoutLyric', () async {
      on('/gw1/', (_) => ok('{"req_1":{"code":0,"data":{"lyric":"","trans":""}}}'));
      expect(await provider.fetchLyric('97773'), isNull);
      expect(seen, hasLength(1));
    });

    test('should_tryNextWebHost_when_webLyricIsRefused', () async {
      on('/web1/soso/', (_) => ok(fixture('search_cp_qingtian.json')));
      on('/gw', (_) => http.Response('', 500));
      on('/web1/lyric/', (_) => ok(fixture('legacy_lyric_refused_1101.json')));
      on('/web2/lyric/', (_) => ok(fixture('legacy_lyric_qingtian_0039MnYb0qxYhV.json')));
      await provider.search('晴天', '周杰伦');
      expect(await provider.fetchLyric('97773'), startsWith('[ti:晴天]'));
    });

    test('should_stopAtFirstWebHost_when_songHasNoLyric', () async {
      on('/web1/soso/', (_) => ok(fixture('search_cp_qingtian.json')));
      on('/gw', (_) => http.Response('', 500));
      on('/web1/lyric/', (_) => ok('{"retcode":-1901,"code":-1901,"subcode":-1901}'));
      await provider.search('晴天', '周杰伦');
      expect(await provider.fetchLyric('97773'), isNull);
      expect(seen.any((r) => r.url.path.startsWith('/web2/lyric/')), isFalse);
    });

    test('should_rejectNonNumericSongId_when_fetching', () async {
      expect(await provider.fetchLyric('abc123'), isNull);
      expect(seen, isEmpty);
    });

    test('should_returnNull_when_songIsInstrumental', () async {
      final b64 = base64.encode(utf8.encode('[ti:x]\n[00:00:00]此歌曲为没有填词的纯音乐，请您欣赏'));
      on('/gw1/', (_) => ok('{"req_1":{"code":0,"data":{"lyric":"$b64"}}}'));
      expect(await provider.fetchLyric('97773'), isNull);
    });

    test('should_unwrapJsonp_when_callbackWrapsBody', () {
      expect(QQLyricsProvider.unwrapJsonp('MusicJsonCallback({"a":1})'), '{"a":1}');
      expect(QQLyricsProvider.unwrapJsonp('callback({"a":1});'), '{"a":1}');
      expect(QQLyricsProvider.unwrapJsonp(' {"a":1} '), '{"a":1}');
    });
  });

  test('should_serveChineseTargets_when_translationIsSimplified', () {
    expect(provider.nativeTranslation.serves('Simplified Chinese'), isTrue);
    expect(provider.nativeTranslation.serves('Traditional Chinese'), isTrue);
    expect(provider.nativeTranslation.serves('English'), isFalse);
  });
}
