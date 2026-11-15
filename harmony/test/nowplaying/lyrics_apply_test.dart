import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/lyrics/lyrics_translation.dart';
import 'package:yoin/data/lyrics/netease_provider.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/lyrics/user_lyrics_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/now_playing_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/nowplaying_fakes.dart';

void main() {
  late ProviderContainer c;
  late InMemoryStore store;

  Future<void> settle() async {
    for (var i = 0; i < 8; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  var netEaseCalls = 0;

  /// NetEase answering with a translation for "server line" at 0:00 (or none).
  NetEaseTranslator translator({bool hasTranslation = true}) => NetEaseTranslator(
    provider: NetEaseLyricsProvider(
      client: MockClient((request) async {
        netEaseCalls++;
        final body = request.url.path == '/cloudsearch'
            ? {
                'result': {
                  'songs': [
                    {
                      'id': 5,
                      'name': 'Song 0',
                      'ar': [
                        {'name': 'Artist 0'},
                      ],
                    },
                  ],
                },
              }
            : {
                'lrc': {'lyric': '[00:00.00]server line'},
                if (hasTranslation) 'tlyric': {'lyric': '[00:00.00]服务器那一行'},
              };
        return http.Response.bytes(utf8.encode(jsonEncode(body)), 200);
      }),
    ),
  );

  ProviderContainer make({NetEaseTranslator? withTranslator, String serverLine = 'server line'}) {
    store = InMemoryStore();
    netEaseCalls = 0;
    final tracks = nowPlayingTracks(2);
    final container = ProviderContainer(
      overrides: [
        ...nowPlayingOverrides(
          controller: NowPlayingFakeController(playingState(tracks)),
          source: NowPlayingFakeSource(
            lyrics: {
              't0': SyncedLyrics([LyricLine(0, serverLine)]),
            },
          ),
        ),
        keyValueStoreProvider.overrideWithValue(store),
        netEaseTranslatorProvider.overrideWithValue(withTranslator ?? translator()),
      ],
    );
    container.listen(nowPlayingViewModelProvider, (_, _) {});
    return container;
  }

  tearDown(() => c.dispose());

  List<String> shownLines() {
    final s = c.read(nowPlayingViewModelProvider) as NowPlayingContent;
    return (s.lyrics as LyricsReady).sheet.rows.map((r) => r.text).toList();
  }

  test('should_showAndStoreLyrics_when_userApplies', () async {
    c = make();
    await c.read(activeSourceProvider.future);
    await settle();
    expect(shownLines(), ['server line']);

    final applied = await c
        .read(nowPlayingViewModelProvider.notifier)
        .applyLyrics('[00:01.00]mine\n[00:02.00]too', provider: 'lrclib', providerSongId: '7');
    expect(applied, isTrue);
    expect(shownLines(), ['mine', 'too']);
    final key = (c.read(nowPlayingViewModelProvider) as NowPlayingContent).songKey;
    final stored = await UserLyricsStore(store).read(key);
    expect(stored?.provider, 'lrclib');
    expect(stored?.providerSongId, '7');
  });

  test('should_preferUserLyrics_when_songLoads', () async {
    c = make();
    final key = nowPlayingTracks(2).first.id.toString();
    await UserLyricsStore(store).write(key, const UserLyrics(lrc: '[00:01.00]chosen', provider: 'manual'));
    await c.read(activeSourceProvider.future);
    await settle();
    expect(shownLines(), ['chosen'], reason: "the user's pick wins over the server's lyrics");
  });

  test('should_refuse_when_blank', () async {
    c = make();
    await c.read(activeSourceProvider.future);
    await settle();
    expect(await c.read(nowPlayingViewModelProvider.notifier).applyLyrics('   '), isFalse);
    expect(shownLines(), ['server line']);
  });

  group('translateLyrics', () {
    NowPlayingContent shown() => c.read(nowPlayingViewModelProvider) as NowPlayingContent;

    test('should_showNetEaseTranslation_when_firstTapped', () async {
      c = make();
      await c.read(activeSourceProvider.future);
      await settle();
      final vm = c.read(nowPlayingViewModelProvider.notifier);
      expect(await vm.translateLyrics(), LyricsTranslateOutcome.translated);
      expect(shown().showTranslation, isTrue);
      expect((shown().lyrics as LyricsReady).sheet.rows.single.translation, '服务器那一行');
      final kept = await LyricsTranslationStore(store).read(shown().songKey, ['server line']);
      expect(kept, {0: '服务器那一行'});
    });

    test('should_toggleWithoutAsking_when_translationThere', () async {
      c = make();
      await c.read(activeSourceProvider.future);
      await settle();
      final vm = c.read(nowPlayingViewModelProvider.notifier);
      await vm.translateLyrics();
      final calls = netEaseCalls;
      expect(await vm.translateLyrics(), LyricsTranslateOutcome.toggled);
      expect(shown().showTranslation, isFalse);
      expect(await vm.translateLyrics(), LyricsTranslateOutcome.toggled);
      expect(shown().showTranslation, isTrue);
      expect(netEaseCalls, calls);
    });

    test('should_fail_when_netEaseHasNoTranslation', () async {
      c = make(withTranslator: translator(hasTranslation: false));
      await c.read(activeSourceProvider.future);
      await settle();
      expect(await c.read(nowPlayingViewModelProvider.notifier).translateLyrics(), LyricsTranslateOutcome.failed);
      expect(shown().showTranslation, isFalse);
      expect(shown().translating, isFalse);
    });

    test('should_sayAlreadyChinese_when_lyricsAreChinese', () async {
      c = make(serverLine: '大千世界曾由我主宰巨浪也曾因我之命澎湃');
      await c.read(activeSourceProvider.future);
      await settle();
      expect(
        await c.read(nowPlayingViewModelProvider.notifier).translateLyrics(),
        LyricsTranslateOutcome.alreadyChinese,
      );
      expect(netEaseCalls, 0);
    });
  });
}
