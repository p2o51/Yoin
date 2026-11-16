import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/lyrics/lyrics_translation.dart';
import 'package:yoin/data/lyrics/netease_provider.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/lyrics/lyric_provider.dart';
import 'package:yoin/data/lyrics/lyrics_cache.dart';
import 'package:yoin/data/lyrics/lyrics_registry.dart';
import 'package:yoin/data/lyrics/lyrics_repository.dart';
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

  ProviderContainer make({
    NetEaseTranslator? withTranslator,
    String serverLine = 'server line',
    List<LyricLine>? serverLines,
    List<LyricProvider> providers = const [],
  }) {
    store = InMemoryStore();
    netEaseCalls = 0;
    final tracks = nowPlayingTracks(2);
    final container = ProviderContainer(
      overrides: [
        ...nowPlayingOverrides(
          controller: NowPlayingFakeController(playingState(tracks)),
          source: NowPlayingFakeSource(
            lyrics: {
              't0': SyncedLyrics(serverLines ?? [LyricLine(0, serverLine)]),
            },
          ),
        ),
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(store),
        netEaseTranslatorProvider.overrideWithValue(withTranslator ?? translator()),
        lyricsRegistryProvider.overrideWithValue(LyricsProviderRegistry(providers: providers)),
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
    final stored = await LyricsCacheStore(store).get(key);
    expect(stored?.lyricsProvider, 'lrclib');
    expect(stored?.providerSongId, '7');
    expect(stored?.userChosen, isTrue, reason: 'a pick never expires');
  });

  test('should_preferUserLyrics_when_songLoads', () async {
    c = make();
    final key = nowPlayingTracks(2).first.id.toString();
    await LyricsCacheStore(store).upsert(
      LyricsCachePolicy.entry(
        songKey: key,
        lyricsProvider: LyricsCachePolicy.manualProvider,
        lyricsProviderSongId: null,
        lrc: '[00:01.00]chosen',
        cachedAt: 1,
        userChosen: true,
      ),
    );
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
      expect(await vm.translateLyrics(), isA<LyricsTranslateDone>());
      expect(shown().showTranslation, isTrue);
      expect((shown().lyrics as LyricsReady).sheet.rows.single.translation, '服务器那一行');
      expect(store.values.keys.any((k) => k.contains('netease-aligned')), isTrue, reason: 'kept for the song');
    });

    test('should_keepTheLyricsCredits_when_theTranslationLands', () async {
      c = make(
        serverLines: [
          LyricLine(0, 'server line'),
          LyricLine(1000, 'second line'),
          LyricLine(5000, '作词：A'),
          LyricLine(6000, '作曲：B'),
        ],
      );
      await c.read(activeSourceProvider.future);
      await settle();
      expect(shown().lyricCredits?.namesFor(LyricCreditRole.lyricist), 'A');
      expect(await c.read(nowPlayingViewModelProvider.notifier).translateLyrics(), isA<LyricsTranslateDone>());
      expect((shown().lyrics as LyricsReady).sheet.rows.first.translation, '服务器那一行');
      // About and the spine still read the credits off the translated sheet.
      expect(shown().lyricCredits?.namesFor(LyricCreditRole.lyricist), 'A');
      expect(shown().lyricCredits?.namesFor(LyricCreditRole.composer), 'B');
    });

    test('should_toggleWithoutAsking_when_translationThere', () async {
      c = make();
      await c.read(activeSourceProvider.future);
      await settle();
      final vm = c.read(nowPlayingViewModelProvider.notifier);
      await vm.translateLyrics();
      final calls = netEaseCalls;
      expect(await vm.translateLyrics(), isA<LyricsTranslateToggled>());
      expect(shown().showTranslation, isFalse);
      expect(await vm.translateLyrics(), isA<LyricsTranslateToggled>());
      expect(shown().showTranslation, isTrue);
      expect(netEaseCalls, calls);
    });

    test('should_sayKeyMissing_when_noKeyAndNetEaseHasNoTranslation', () async {
      c = make(withTranslator: translator(hasTranslation: false));
      await c.read(activeSourceProvider.future);
      await settle();
      expect(await c.read(nowPlayingViewModelProvider.notifier).translateLyrics(), isA<LyricsTranslateKeyMissing>());
      expect(shown().showTranslation, isFalse);
      expect(shown().translating, isFalse);
    });

    test('should_offerSwitchThenApplyIt_when_laterSourceTranslates', () async {
      c = make(
        providers: [
          _Fake('qq', lrc: '[00:00.00]server line'),
          _Fake('netease', lrc: '[00:00.00]server line', translation: '[00:00.00]网易的翻译'),
        ],
      );
      await c.read(activeSourceProvider.future);
      await settle();
      final vm = c.read(nowPlayingViewModelProvider.notifier);
      // The shown lyrics came from QQ, which has no translation for them.
      await vm.applyLyrics('[00:00.00]server line', provider: 'qq', providerSongId: '1');
      // Answer in Simplified Chinese: QQ's and NetEase's own translations serve it.
      await store.write(AiSettingsStore.prefsKey, jsonEncode({'tier': 'good', 'targetLanguage': 'Simplified Chinese'}));
      final outcome = await vm.translateLyrics();
      expect(outcome, isA<LyricsTranslateOffer>().having((o) => o.providerName, 'provider', 'netease'));
      expect(shown().showTranslation, isFalse, reason: 'offered, not applied');
      expect(shown().translating, isFalse);

      expect(await vm.applyTranslationSwitch(), 'netease');
      expect(shown().showTranslation, isTrue);
      expect((shown().lyrics as LyricsReady).sheet.rows.single.translation, '网易的翻译');
      final row = await LyricsCacheStore(store).get(shown().songKey);
      expect(row?.lyricsProvider, 'netease');
      expect(row?.userChosen, isTrue);
      expect(await vm.applyTranslationSwitch(), isNull, reason: 'taken once');
    });
  });
}

/// A lyrics source whose own translation is Simplified Chinese.
class _Fake extends LyricProvider {
  _Fake(this.name, {required this.lrc, this.translation});

  @override
  final String name;
  final String lrc;
  final String? translation;

  @override
  NativeTranslation get nativeTranslation => NativeTranslation.simplifiedChinese;

  @override
  Future<SongMatch?> search(String title, String artist, {int? durationMs}) async =>
      SongMatch(songId: '2', title: title, artist: artist);

  @override
  Future<String?> fetchLyric(String songId) async => lrc;

  @override
  Future<LyricPayload?> fetchLyricWithTranslation(String songId) async =>
      LyricPayload(lyric: lrc, translatedLyric: translation);
}
