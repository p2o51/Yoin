import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/lyrics/lrc_parser.dart';
import 'package:yoin/data/lyrics/lrclib_provider.dart';
import 'package:yoin/data/lyrics/lyric_provider.dart';
import 'package:yoin/data/lyrics/lyrics_search.dart';
import 'package:yoin/data/lyrics/netease_provider.dart';
import 'package:yoin/data/lyrics/user_lyrics_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/storage/stores.dart';

http.Response _json(Object body, {int status = 200}) =>
    http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

void main() {
  group('LrcParser', () {
    test('should_parseStampsAndSort_when_lrc', () {
      final lyrics = LrcParser.parse('[ti:Song]\n[00:05.50]second\n[00:01.2]first\n[01:02.345]third') as SyncedLyrics;
      expect(lyrics.lines.map((l) => (l.startMs, l.text)).toList(), [
        (1200, 'first'),
        (5500, 'second'),
        (62345, 'third'),
      ]);
    });

    test('should_expandEachStamp_when_lineHasSeveral', () {
      final lyrics = LrcParser.parse('[00:01.00][00:10.00]chorus') as SyncedLyrics;
      expect(lyrics.lines.map((l) => l.startMs), [1000, 10000]);
    });

    test('should_keepPlainText_when_noStamps', () {
      final lyrics = LrcParser.parse('  just words\nmore words  ');
      expect(lyrics, isA<UnsyncedLyrics>());
      expect((lyrics as UnsyncedLyrics).text, 'just words\nmore words');
    });

    test('should_writeAndroidTimestamp_when_formatting', () {
      expect(LrcParser.timestamp(62345), '[01:02.34]');
      expect(LrcParser.timestamp(-5), '[00:00.00]');
    });
  });

  test('should_unescapeAndCollapse_when_normalizing', () {
    expect(normalizeLyric(' a &amp; b\r\n\r\n\nc &#39;d&#39; '), "a & b\nc 'd'");
  });

  group('LrclibLyricsProvider', () {
    test('should_listMatchesInOrder_when_searching', () async {
      late Uri asked;
      final provider = LrclibLyricsProvider(
        client: MockClient((request) async {
          asked = request.url;
          return _json([
            {'id': 7, 'trackName': 'Viva La Vida', 'artistName': 'Coldplay'},
            {'id': 8, 'trackName': 'Viva La Vida (Live)', 'artistName': 'Coldplay'},
            {'id': 9, 'trackName': 'Other', 'artistName': 'X'},
            {'id': 10, 'trackName': 'Extra', 'artistName': 'Y'},
          ]);
        }),
      );
      final matches = await provider.searchMultiple('Viva La Vida Coldplay', '', limit: 3);
      expect(asked.path, '/api/search');
      expect(asked.queryParameters['track_name'], 'Viva La Vida Coldplay');
      expect(matches.map((m) => m.songId), ['7', '8', '9']);
      expect(matches.first, const SongMatch(songId: '7', title: 'Viva La Vida', artist: 'Coldplay'));
    });

    test('should_preferSynced_when_fetching', () async {
      final provider = LrclibLyricsProvider(
        client: MockClient((_) async => _json({'syncedLyrics': '[00:01.00]hi', 'plainLyrics': 'hi'})),
      );
      expect(await provider.fetchLyric('7'), '[00:01.00]hi');
    });

    test('should_fallBackToPlain_when_noSynced', () async {
      final provider = LrclibLyricsProvider(
        client: MockClient((_) async => _json({'syncedLyrics': null, 'plainLyrics': 'plain words'})),
      );
      expect(await provider.fetchLyric('7'), 'plain words');
    });

    test('should_returnNothing_when_serverFails', () async {
      final provider = LrclibLyricsProvider(client: MockClient((_) async => http.Response('nope', 500)));
      expect(await provider.searchMultiple('a', ''), isEmpty);
      expect(await provider.fetchLyric('1'), isNull);
    });
  });

  group('NetEaseLyricsProvider', () {
    test('should_readCloudsearch_when_searching', () async {
      late Uri asked;
      final provider = NetEaseLyricsProvider(
        client: MockClient((request) async {
          asked = request.url;
          return _json({
            'result': {
              'songs': [
                {
                  'id': 3986017,
                  'name': 'Viva La Vida',
                  'ar': [
                    {'name': 'Coldplay'},
                  ],
                },
                {'name': 'no id'},
              ],
            },
          });
        }),
      );
      final matches = await provider.searchMultiple('Viva La Vida Coldplay', '');
      expect(asked.path, '/cloudsearch');
      expect(asked.queryParameters['keywords'], 'Viva La Vida Coldplay ');
      expect(matches, [const SongMatch(songId: '3986017', title: 'Viva La Vida', artist: 'Coldplay')]);
    });

    test('should_returnLrc_when_fetching', () async {
      final provider = NetEaseLyricsProvider(
        client: MockClient(
          (_) async => _json({
            'lrc': {'lyric': '[00:01.00]line'},
            'tlyric': {'lyric': '[00:01.00]行'},
          }),
        ),
      );
      expect(await provider.fetchLyric('1'), '[00:01.00]line');
    });

    test('should_convertWordTimedJson_when_providerSendsIt', () {
      const raw = '{"t":61234,"c":[{"tx":"歌"},{"tx":"词"}]}\n[00:02.00]plain';
      expect(netEaseJsonLyricToLrc(raw), '[01:01.23]歌词\n[00:02.00]plain');
    });
  });

  group('LyricsSearch', () {
    test('should_searchEveryProviderWithQueryAsTitle_when_searching', () async {
      final a = _FakeProvider('netease', [const SongMatch(songId: '1', title: 'A', artist: 'x')]);
      final b = _FakeProvider('lrclib', const []);
      final search = LyricsSearch(providers: [a, b]);
      final sections = await search.search('  Song Artist ');
      expect(sections.map((s) => s.providerName), ['netease', 'lrclib']);
      expect(sections.first.matches.single.songId, '1');
      expect(a.lastTitle, 'Song Artist');
      expect(a.lastArtist, '');
      expect(a.lastLimit, LyricsSearch.perProvider);
    });

    test('should_fetchNormalized_when_resultPicked', () async {
      final search = LyricsSearch(providers: [_FakeProvider('netease', const [], lyric: ' [00:01.00]a &amp; b ')]);
      final hit = await search.fetch('netease', '1');
      expect(hit?.lrc, '[00:01.00]a & b');
      expect(hit?.providerName, 'netease');
      expect(await search.fetch('qq', '1'), isNull);
    });
  });

  test('should_roundTripUserLyrics_when_stored', () async {
    final store = UserLyricsStore(InMemoryStore());
    expect(await store.read('subsonic:1'), isNull);
    await store.write('subsonic:1', const UserLyrics(lrc: '[00:01.00]x', provider: 'lrclib', providerSongId: '9'));
    final back = await store.read('subsonic:1');
    expect(back?.lrc, '[00:01.00]x');
    expect(back?.provider, 'lrclib');
    expect(back?.providerSongId, '9');
  });
}

class _FakeProvider extends LyricProvider {
  _FakeProvider(this.name, this.matches, {this.lyric});

  @override
  final String name;
  final List<SongMatch> matches;
  final String? lyric;
  String? lastTitle;
  String? lastArtist;
  int? lastLimit;

  @override
  Future<List<SongMatch>> searchMultiple(String title, String artist, {int limit = 3}) async {
    lastTitle = title;
    lastArtist = artist;
    lastLimit = limit;
    return matches;
  }

  @override
  Future<String?> fetchLyric(String songId) async => lyric;
}
