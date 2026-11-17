import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/detail/detail_play.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/playback_controller.dart';

import '../support/fake_playback_engine.dart';
import '../support/playback_fakes.dart';

/// A source whose stream lookup fails (the controller's _build throws).
class _BrokenStreamSource extends PlaybackFakeSource {
  @override
  Future<PlaybackHandle> handleFor(Track track) async => throw const SourceNetworkException('offline');
}

ProviderContainer _container({required PlaybackEngine engine, MusicSource? source}) {
  final c = ProviderContainer(
    overrides: [
      playbackEngineProvider.overrideWithValue(engine),
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      activityLogProvider.overrideWith((ref) async => null),
      activeSourceProvider.overrideWith((ref) async => source ?? PlaybackFakeSource()),
    ],
  );
  c.listen(playbackControllerProvider, (_, _) {});
  return c;
}

void main() {
  test('should_returnAddedCount_when_queueEmpty', () async {
    final c = _container(engine: PlaybackFakeQueueEngine());
    addTearDown(c.dispose);
    await c.read(activeSourceProvider.future);

    final n = await queueFromDetail(DetailPlayer.container(c), () async => playbackTracks(3), next: false);

    expect(n, 3);
  });

  test('should_returnInsertedCount_when_queueAlreadyPlaying', () async {
    final engine = PlaybackFakeQueueEngine();
    final c = _container(engine: engine);
    addTearDown(c.dispose);
    await c.read(activeSourceProvider.future);
    await c.read(playbackControllerProvider.notifier).playTracks(playbackTracks(2));
    engine.echo(index: 0);
    await pumpEventQueue();

    final n = await queueFromDetail(
      DetailPlayer.container(c),
      () async => playbackTracks(4, prefix: 'q'),
      next: true,
    );

    expect(n, 4);
    expect(engine.calls.last, 'insert:1:4');
  });

  test('should_returnZero_when_engineCannotEditQueue', () async {
    // The base engine has no queue edits: addToQueue returns quietly with a queue loaded.
    final c = _container(engine: FakePlaybackEngine());
    addTearDown(c.dispose);
    await c.read(activeSourceProvider.future);
    await c.read(playbackControllerProvider.notifier).playTracks(playbackTracks(2));

    final n = await queueFromDetail(DetailPlayer.container(c), () async => playbackTracks(3, prefix: 'q'), next: false);

    expect(n, 0);
    expect(c.read(playbackControllerProvider).queue, hasLength(2));
  });

  test('should_returnZeroWithoutThrowing_when_streamLookupFails', () async {
    final c = _container(engine: PlaybackFakeQueueEngine(), source: _BrokenStreamSource());
    addTearDown(c.dispose);
    await c.read(activeSourceProvider.future);

    expect(await queueFromDetail(DetailPlayer.container(c), () async => playbackTracks(2), next: false), 0);
  });

  test('should_returnZeroWithoutThrowing_when_loadFails', () async {
    final c = _container(engine: PlaybackFakeQueueEngine());
    addTearDown(c.dispose);

    final n = await queueFromDetail(
      DetailPlayer.container(c),
      () async => throw const SourceNetworkException('offline'),
      next: false,
    );

    expect(n, 0);
  });

  test('should_returnZero_when_noAccount', () async {
    final c = ProviderContainer(
      overrides: [
        playbackEngineProvider.overrideWithValue(PlaybackFakeQueueEngine()),
        keyValueStoreProvider.overrideWithValue(InMemoryStore()),
        secretStoreProvider.overrideWithValue(InMemoryStore()),
        activityLogProvider.overrideWith((ref) async => null),
        activeSourceProvider.overrideWith((ref) async => null),
      ],
    );
    addTearDown(c.dispose);
    c.listen(playbackControllerProvider, (_, _) {});

    expect(await queueFromDetail(DetailPlayer.container(c), () async => playbackTracks(2), next: true), 0);
  });
}
