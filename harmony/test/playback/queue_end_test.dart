import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/playback_controller.dart';

import '../support/playback_fakes.dart';

/// Models the native engine's end-of-queue rules (PlaybackEngine.ets):
/// - reaching the end under repeat off keeps the intent but reports
///   phase `ended` with playWhenReady false (the snapshot only reports intent
///   while loading / ready);
/// - a seek out of `ended` (and so `previous`, which restarts the song there)
///   resumes playback when the intent is still set (Media3 parity);
/// - play() at `ended` restarts the queue from the top of the play order.
class _EndingEngine extends PlaybackFakeQueueEngine {
  bool intent = false;
  PlaybackPhase phase = PlaybackPhase.idle;
  bool sounding = false;
  int index = -1;
  int position = 0;

  void report() => emit(EngineState(
        phase: phase,
        isPlaying: sounding,
        playWhenReady: intent && (phase == PlaybackPhase.loading || phase == PlaybackPhase.ready),
        index: index,
        currentId: queue[index].id,
        currentEntryId: queue[index].entryId,
        positionMs: position,
        sampledAtMs: 1000,
        durationMs: 200000,
        repeat: EngineRepeat.off,
        shuffle: false,
        revision: revision,
        order: List<int>.generate(queue.length, (i) => i),
      ));

  /// The last item completes under repeat off.
  void finishQueue() {
    index = queue.length - 1;
    phase = PlaybackPhase.ended;
    sounding = false;
    position = 200000;
    report();
  }

  @override
  Future<void> play() async {
    await super.play();
    intent = true;
    if (phase == PlaybackPhase.ended) index = 0;
    phase = PlaybackPhase.ready;
    sounding = true;
    position = 0;
    report();
  }

  @override
  Future<void> pause() async {
    await super.pause();
    intent = false;
    sounding = false;
    report();
  }

  @override
  Future<void> seekTo(int positionMs) async {
    await super.seekTo(positionMs);
    final wasEnded = phase == PlaybackPhase.ended;
    if (wasEnded) phase = PlaybackPhase.ready;
    position = positionMs;
    if (wasEnded && intent) sounding = true;
    report();
  }

  @override
  Future<void> previous() async {
    await super.previous();
    // At the end the position is the duration (> 3 s): restart the song.
    await seekTo(0);
  }
}

void main() {
  late _EndingEngine engine;
  late ProviderContainer c;

  PlaybackController ctl() => c.read(playbackControllerProvider.notifier);

  setUp(() async {
    engine = _EndingEngine();
    c = ProviderContainer(overrides: [
      playbackEngineProvider.overrideWithValue(engine),
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      activeSourceProvider.overrideWith((ref) async => PlaybackFakeSource()),
    ]);
    c.listen(playbackControllerProvider, (_, _) {});
    await c.read(activeSourceProvider.future);
    await ctl().playTracks(playbackTracks(3), startIndex: 2);
    engine
      ..intent = true
      ..index = 2
      ..phase = PlaybackPhase.ready
      ..sounding = true
      ..report();
    await pumpEventQueue();
  });
  tearDown(() => c.dispose());

  test('should_showPlay_when_queueEnded', () async {
    engine.finishQueue();
    await pumpEventQueue();
    final s = c.read(playbackControllerProvider);
    expect(s.phase, PlaybackPhase.ended);
    expect(s.isPlaying, isFalse);
    expect(s.playWhenReady, isFalse, reason: 'the button must offer PLAY, one tap to hear music');
  });

  test('should_replayLastSongAudibly_when_previousAfterQueueEnded', () async {
    engine.finishQueue();
    await pumpEventQueue();
    engine.calls.clear();

    await ctl().previous();
    await pumpEventQueue();

    final s = c.read(playbackControllerProvider);
    expect(engine.calls, ['previous', 'seekTo:0']);
    expect(s.index, 2, reason: 'previous at the end restarts the last song');
    expect(s.phase, PlaybackPhase.ready);
    expect(s.isPlaying, isTrue);
    expect(s.playWhenReady, isTrue);
  });

  test('should_resumeAtNewPosition_when_seekAfterQueueEnded', () async {
    engine.finishQueue();
    await pumpEventQueue();

    await ctl().seekTo(60000);
    await pumpEventQueue();

    final s = c.read(playbackControllerProvider);
    expect(s.isPlaying, isTrue);
    expect(s.playWhenReady, isTrue);
    expect(s.positionAt(1000), 60000);
  });

  test('should_stayPaused_when_seekAfterQueueEndedWithoutIntent', () async {
    engine.finishQueue();
    await pumpEventQueue();
    await ctl().pause();
    await pumpEventQueue();

    await ctl().seekTo(60000);
    await pumpEventQueue();

    final s = c.read(playbackControllerProvider);
    expect(s.isPlaying, isFalse);
    expect(s.playWhenReady, isFalse, reason: 'never PAUSE shown while nothing plays');
  });

  test('should_needOneTap_when_playAfterQueueEnded', () async {
    engine.finishQueue();
    await pumpEventQueue();
    engine.calls.clear();

    await ctl().togglePlayPause();
    await pumpEventQueue();

    expect(engine.calls.first, 'play');
    expect(engine.calls.where((x) => x == 'play'), hasLength(1));
    final s = c.read(playbackControllerProvider);
    expect(s.isPlaying, isTrue);
    expect(s.index, 0, reason: 'play at the end restarts the queue from the top');
  });
}
