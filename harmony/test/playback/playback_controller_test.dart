import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/playback/playback_controller.dart';

import '../support/fake_music_source.dart';
import '../support/fake_playback_engine.dart';

List<Track> _tracks(int n) => [
      for (var i = 0; i < n; i++)
        Track(id: MediaId.subsonic('t$i'), title: 'Song $i', artist: 'A', durationSec: 200, coverArt: CoverSourceRelative('c$i')),
    ];

void main() {
  late FakePlaybackEngine engine;
  late FakeMusicSource source;
  late ProviderContainer c;

  setUp(() {
    engine = FakePlaybackEngine();
    source = FakeMusicSource();
    c = ProviderContainer(overrides: [
      playbackEngineProvider.overrideWithValue(engine),
      activeSourceProvider.overrideWith((ref) async => source),
    ]);
    c.listen(playbackControllerProvider, (_, _) {});
  });
  tearDown(() => c.dispose());

  test('should_sendStreamItemsWithArtwork_when_playTracks', () async {
    final tracks = _tracks(3);
    await c.read(playbackControllerProvider.notifier).playTracks(tracks, startIndex: 1);

    expect(engine.startIndex, 1);
    expect(engine.queue.map((i) => i.uri), ['https://stream.test/t0', 'https://stream.test/t1', 'https://stream.test/t2']);
    expect(engine.queue[1].artworkUri, 'https://covers.test/c1?size=600');
    expect(engine.queue[1].durationMs, 200000);
    final state = c.read(playbackControllerProvider);
    expect(state.current!.id, tracks[1].id);
    expect(state.phase, PlaybackPhase.loading);
  });

  test('should_followNativeTrack_when_engineAdvances', () async {
    final tracks = _tracks(3);
    await c.read(playbackControllerProvider.notifier).playTracks(tracks);

    engine.emit(EngineState(phase: PlaybackPhase.ready, isPlaying: true, index: 2, currentId: tracks[2].id.toString(), positionMs: 1000, sampledAtMs: 5000, durationMs: 199000));
    await Future<void>.delayed(Duration.zero);

    final state = c.read(playbackControllerProvider);
    expect(state.current!.id, tracks[2].id);
    expect(state.isPlaying, isTrue);
    expect(state.positionAt(6500), 2500); // extrapolated from the sample
  });

  test('should_notExtrapolate_when_paused', () async {
    await c.read(playbackControllerProvider.notifier).playTracks(_tracks(1));
    engine.emit(const EngineState(phase: PlaybackPhase.ready, isPlaying: false, index: 0, positionMs: 1000, sampledAtMs: 5000));
    await Future<void>.delayed(Duration.zero);
    expect(c.read(playbackControllerProvider).positionAt(9000), 1000);
  });
}
