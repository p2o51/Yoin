import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/lyrics/lrc_parser.dart';
import 'package:yoin/data/lyrics/lyrics_translation.dart';
import 'package:yoin/data/lyrics/netease_provider.dart';

http.Response _json(Object body) =>
    http.Response.bytes(utf8.encode(jsonEncode(body)), 200, headers: {'content-type': 'application/json'});

void main() {
  group('buildProviderTranslations', () {
    test('should_pairByTimestamp_when_stampsShared', () {
      final lyrics = LrcParser.parse('[00:01.00]one\n[00:02.00]two\n[00:03.00]three');
      final map = buildProviderTranslations(lyrics, '[00:01.00]一\n[00:03.00]三');
      expect(map, {0: '一', 2: '三'});
    });

    test('should_pairGroupByPosition_when_countsMatch', () {
      final lyrics = LrcParser.parse('[00:00.00]Title\n[00:00.00]Words: X\n[00:05.00]line');
      final map = buildProviderTranslations(lyrics, '[00:00.00]标题\n[00:00.00]词：X\n[00:05.00]行');
      expect(map, {0: '标题', 1: '词：X', 2: '行'});
    });

    test('should_shareLastTranslation_when_groupCountsDiffer', () {
      final lyrics = LrcParser.parse('[00:00.00]a\n[00:00.00]b');
      expect(buildProviderTranslations(lyrics, '[00:00.00]甲'), {0: '甲', 1: '甲'});
    });

    test('should_skipPlaceholder_when_untranslatedLine', () {
      final lyrics = LrcParser.parse('[00:01.00]one\n[00:02.00]two');
      expect(buildProviderTranslations(lyrics, '[00:01.00]//\n[00:02.00]二'), {1: '二'});
    });

    test('should_pairByOrder_when_noStampInCommon', () {
      final lyrics = LrcParser.parse('[00:01.00]one\n[00:02.00]two');
      expect(buildProviderTranslations(lyrics, '[00:09.00]一\n[00:10.00]二'), {0: '一', 1: '二'});
    });
  });

  group('alignTranslations', () {
    const provider = [
      (startMs: 1000, text: 'Hello, world'),
      (startMs: 4000, text: 'Second line'),
      (startMs: 8000, text: 'Third'),
    ];
    const translations = {0: '你好世界', 1: '第二行', 2: '第三'};

    test('should_takeSameWordsWithinTolerance_when_stampsDrift', () {
      final ours = [(startMs: 1300, text: 'hello world'), (startMs: 3600, text: 'Second line!')];
      expect(alignTranslations(ours, provider, translations), {0: '你好世界', 1: '第二行'});
    });

    test('should_takeSameStamp_when_wordsDiffer', () {
      final ours = [(startMs: 8020, text: 'Thirdd')];
      expect(alignTranslations(ours, provider, translations), {0: '第三'});
    });

    test('should_skip_when_differentWordsOffStamp', () {
      final ours = [(startMs: 4300, text: 'something else'), (startMs: 20000, text: 'Third')];
      expect(alignTranslations(ours, provider, translations), isEmpty);
    });

    test('should_useEachProviderLineOnce_when_linesRepeat', () {
      final ours = [(startMs: 1000, text: 'Hello, world'), (startMs: 1100, text: 'Hello, world')];
      expect(alignTranslations(ours, provider, translations), {0: '你好世界'});
    });

    test('should_pairByOrder_when_bothUntimedAndSameLines', () {
      final ours = [(startMs: null, text: 'a'), (startMs: null, text: 'b')];
      final theirs = [(startMs: null, text: 'A'), (startMs: null, text: 'b')];
      expect(alignTranslations(ours, theirs, {0: '甲', 1: '乙'}), {0: '甲', 1: '乙'});
      expect(alignTranslations(ours, [(startMs: null, text: 'x'), (startMs: null, text: 'b')], {0: '甲'}), isEmpty);
    });
  });

  test('should_detectChineseLyrics_when_mostlyHan', () {
    expect(lyricsAppearMostlyChinese(['大千世界曾由我主宰', '巨浪也曾因我之命澎湃']), isTrue);
    expect(lyricsAppearMostlyChinese(['I used to rule the world', 'Seas would rise']), isFalse);
    expect(lyricsAppearMostlyChinese(['夜に駆ける', '沈むように溶けてゆくように二人だけの空']), isFalse);
  });

  group('NetEaseTranslator', () {
    MockClient netEase({required List<String> asked}) => MockClient((request) async {
      asked.add(request.url.path);
      if (request.url.path == '/cloudsearch') {
        return _json({
          'result': {
            'songs': [
              {
                'id': 42,
                'name': 'Viva La Vida',
                'ar': [
                  {'name': 'Coldplay'},
                ],
                'dt': 242373,
              },
            ],
          },
        });
      }
      expect(request.url.queryParameters['id'], '42');
      return _json({
        'lrc': {'lyric': '[00:00.00]作词: Chris Martin\n[00:13.34]I used to rule the world\n[00:17.08]Seas would rise'},
        'tlyric': {'lyric': '[00:11.34]\n[00:13.34]大千世界曾由我主宰\n[00:17.08]巨浪也曾因我之命澎湃'},
      });
    });

    test('should_searchAndAlignToOurLines_when_lyricsFromElsewhere', () async {
      final asked = <String>[];
      final translator = NetEaseTranslator(
        provider: NetEaseLyricsProvider(client: netEase(asked: asked)),
      );
      final map = await translator.translate(
        lines: [(startMs: 13500, text: 'I used to rule the world'), (startMs: 17000, text: 'Seas would rise')],
        title: 'Viva La Vida',
        artist: 'Coldplay',
        durationMs: 242000,
      );
      expect(map, {0: '大千世界曾由我主宰', 1: '巨浪也曾因我之命澎湃'});
      expect(asked, ['/cloudsearch', '/lyric/new']);
    });

    test('should_fetchDirectly_when_lyricsCameFromNetEase', () async {
      final asked = <String>[];
      final translator = NetEaseTranslator(
        provider: NetEaseLyricsProvider(client: netEase(asked: asked)),
      );
      final map = await translator.translate(
        lines: [(startMs: 13340, text: 'I used to rule the world')],
        title: 'x',
        artist: 'y',
        providerSongId: '42',
      );
      expect(map, {0: '大千世界曾由我主宰'});
      expect(asked, ['/lyric/new']);
    });
  });
}
