import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
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

  ProviderContainer make() {
    store = InMemoryStore();
    final tracks = nowPlayingTracks(2);
    final container = ProviderContainer(
      overrides: [
        ...nowPlayingOverrides(
          controller: NowPlayingFakeController(playingState(tracks)),
          source: NowPlayingFakeSource(
            lyrics: {
              't0': const SyncedLyrics([LyricLine(0, 'server line')]),
            },
          ),
        ),
        keyValueStoreProvider.overrideWithValue(store),
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
}
