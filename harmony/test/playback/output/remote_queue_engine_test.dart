import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/playback/output/dlna_protocol.dart';
import 'package:yoin/playback/output/remote_queue_engine.dart';
import 'package:yoin/playback/output/renderer_transport.dart';

import 'output_fakes.dart';

void main() {
  late FakeClock clock;
  late FakeRenderer renderer;
  late RemoteQueueEngine engine;
  late List<EngineState> states;
  late List<String?> failures;

  setUp(() {
    clock = FakeClock();
    renderer = FakeRenderer();
    failures = [];
    engine = RemoteQueueEngine(
      onFailure: failures.add,
      nowMs: clock.call,
      pollInterval: null,
      random: Random(1),
      sleep: clock.sleep,
    );
    states = [];
    engine.states.listen(states.add);
  });

  tearDown(() => engine.release());

  Future<void> connect({
    int count = 3,
    int index = 0,
    int position = 0,
    bool play = true,
    EngineRepeat repeat = EngineRepeat.all,
    bool shuffle = false,
    List<int>? order,
  }) => engine.connect(
    renderer,
    [for (var i = 0; i < count; i++) streamItem(i)],
    startIndex: index,
    startPositionMs: position,
    playWhenReady: play,
    repeat: repeat,
    shuffle: shuffle,
    revision: 7,
    order: order,
  );

  test('should_loadSeekAndPlayTheStartSong_when_connecting', () async {
    await connect(index: 1, position: 42000);
    expect(renderer.calls, ['load:subsonic:t1', 'seek:42000', 'play']);
    final s = engine.latest!;
    expect(s.index, 1);
    expect(s.currentEntryId, 'e1');
    expect(s.isPlaying, isTrue);
    expect(s.phase, PlaybackPhase.ready);
    expect(s.revision, 7);
    expect(s.order, [0, 1, 2]);
    expect(engine.volume, 0.3);
  });

  test('should_refuseTheQueue_when_anItemIsNotAnHttpStream', () async {
    const local = EngineItem(id: 'applemusic:1', uri: 'yoin-am:1', title: 'x');
    expect(
      () => engine.connect(
        renderer,
        [local],
        startIndex: 0,
        startPositionMs: 0,
        playWhenReady: true,
        repeat: EngineRepeat.all,
        shuffle: false,
        revision: 1,
      ),
      throwsArgumentError,
    );
  });

  test('should_keepThePhonesShuffleOrder_when_connectingShuffled', () async {
    await connect(shuffle: true, order: [0, 2, 1]);
    expect(engine.order, [0, 2, 1]);
    expect(engine.latest!.shuffle, isTrue);
  });

  test('should_throwAndShowNotPlaying_when_theRendererRefusesPlay', () async {
    renderer.failing.add('play');
    await expectLater(connect(), throwsA(isA<DlnaCommandException>()));
    expect(engine.latest?.isPlaying ?? false, isFalse);
    // Still attached: the caller can Stop it.
    expect(engine.hasDevice, isTrue);
  });

  test('should_advanceToTheNextSong_when_theRendererStopsAtTheEnd', () async {
    await connect();
    renderer
      ..at = 199500
      ..state = RendererState.stopped;
    await engine.pollOnce();
    expect(renderer.calls.where((c) => c.startsWith('load')), ['load:subsonic:t0', 'load:subsonic:t1']);
    expect(engine.latest!.index, 1);
    expect(engine.latest!.isPlaying, isTrue);
  });

  test('should_replayTheSameSong_when_repeatOneReachesTheEnd', () async {
    await connect(repeat: EngineRepeat.one);
    renderer
      ..at = 199500
      ..state = RendererState.stopped;
    await engine.pollOnce();
    expect(engine.latest!.index, 0);
    expect(engine.latest!.repeatPlays, 1);
  });

  test('should_stopAtTheEnd_when_repeatOffFinishesTheLastSong', () async {
    await connect(count: 1, repeat: EngineRepeat.off);
    renderer
      ..at = 199800
      ..state = RendererState.stopped;
    await engine.pollOnce();
    expect(engine.playWhenReady, isFalse);
    expect(engine.latest!.phase, PlaybackPhase.ended);
  });

  test('should_notAdvance_when_someoneStopsTheSpeakerMidSong', () async {
    await connect();
    renderer
      ..at = 30000
      ..state = RendererState.stopped;
    await engine.pollOnce();
    expect(engine.latest!.index, 0);
    expect(engine.playWhenReady, isFalse);
  });

  test('should_followPauseAndPlay_when_pressedOnTheSpeakerItself', () async {
    await connect();
    renderer.state = RendererState.paused;
    await engine.pollOnce();
    expect(engine.playWhenReady, isFalse);
    renderer.state = RendererState.playing;
    await engine.pollOnce();
    expect(engine.playWhenReady, isTrue);
  });

  test('should_showAFailure_when_threePollsInARowFail', () async {
    await connect();
    renderer.failing.add('transportInfo');
    await engine.pollOnce();
    await engine.pollOnce();
    expect(engine.latest!.phase, isNot(PlaybackPhase.error));
    await engine.pollOnce();
    expect(engine.latest!.phase, PlaybackPhase.error);
    expect(engine.latest!.errorMessage, remoteFailureMessage);
    expect(failures.last, remoteFailureMessage);
  });

  test('should_fail_when_anotherAppTakesTheRendererOver', () async {
    await connect();
    renderer.trackUri = 'http://other/app';
    await engine.seekTo(1000);
    expect(engine.latest!.phase, PlaybackPhase.error);
  });

  test('should_stopOfferingSeek_when_theRendererRejectsSeekWith710', () async {
    await connect();
    renderer.seekErrorCode = 710;
    await engine.seekTo(5000);
    expect(engine.seekSupported, isFalse);
  });

  test('should_pauseAndResumeOnTheRenderer_when_toggled', () async {
    await connect();
    await engine.pause();
    expect(renderer.state, RendererState.paused);
    expect(engine.latest!.isPlaying, isFalse);
    await engine.play();
    expect(renderer.state, RendererState.playing);
    expect(engine.latest!.isPlaying, isTrue);
  });

  test('should_restartTheSongOrGoBack_when_previousIsPressed', () async {
    await connect(index: 1);
    renderer.at = 10000;
    await engine.pollOnce();
    await engine.previous();
    expect(engine.latest!.index, 1);
    expect(renderer.calls, contains('seek:0'));
    renderer.at = 1000;
    await engine.pollOnce();
    await engine.previous();
    expect(engine.latest!.index, 0);
  });

  test('should_wrapToTheFirstSong_when_nextIsPressedOnTheLastWithRepeatAll', () async {
    await connect(index: 2);
    await engine.next();
    expect(engine.latest!.index, 0);
  });

  test('should_putPlayNextRightAfterTheCurrentSong_when_insertingShuffled', () async {
    await connect(shuffle: true, order: [0, 2, 1]);
    await engine.insert(1, [streamItem(9)], revision: 8);
    expect(engine.items.map((i) => i.id), ['subsonic:t0', 'subsonic:t9', 'subsonic:t1', 'subsonic:t2']);
    expect(engine.order.take(2), [0, 1]);
    expect(engine.latest!.revision, 8);
  });

  test('should_playTheSuccessor_when_theCurrentSongIsRemoved', () async {
    await connect(index: 1);
    await engine.removeAt(1, revision: 8);
    expect(engine.latest!.index, 1);
    expect(engine.latest!.currentId, 'subsonic:t2');
    expect(renderer.calls.last, 'play');
  });

  test('should_keepTheCurrentSong_when_anotherRowMoves', () async {
    await connect(index: 1);
    await engine.move(2, 0, revision: 8);
    expect(engine.items.map((i) => i.id), ['subsonic:t2', 'subsonic:t0', 'subsonic:t1']);
    expect(engine.latest!.index, 2);
    expect(engine.latest!.currentId, 'subsonic:t1');
  });

  test('should_stopThenLoadTheNewQueue_when_aNewQueueStarts', () async {
    await connect();
    renderer.calls.clear();
    await engine.startQueue([streamItem(5), streamItem(6)], revision: 9, startIndex: 1);
    expect(renderer.calls, ['stop', 'load:subsonic:t6', 'play']);
    expect(engine.latest!.revision, 9);
    expect(engine.latest!.currentId, 'subsonic:t6');
  });

  test('should_confirmTheStop_when_disconnectingWithStop', () async {
    await connect();
    await engine.disconnect(stopRemote: true);
    expect(renderer.calls, contains('stop'));
    expect(engine.hasDevice, isFalse);
  });

  test('should_keepTheRenderer_when_itNeverConfirmsTheStop', () async {
    await connect();
    renderer.ignoreStop = true;
    await expectLater(engine.disconnect(stopRemote: true), throwsA(isA<DlnaCommandException>()));
    expect(engine.hasDevice, isTrue);
  });

  test('should_setTheRenderersVolume_when_volumeChanges', () async {
    await connect();
    await engine.setVolume(0.55);
    expect(renderer.calls, contains('setVolume:55'));
    expect(engine.volume, 0.55);
  });

  test('should_markPurePositionSamplesAsTicks_when_nothingElseChanged', () async {
    await connect();
    renderer.at = 5000;
    await engine.pollOnce();
    expect(engine.latest!.tick, isTrue);
    expect(engine.latest!.order, isNull);
    expect(engine.latest!.positionMs, 5000);
  });

  test('should_failTheLoad_when_theRendererNeverStartsWithin20s', () async {
    renderer.playStarts = false;
    await connect();
    expect(engine.latest!.phase, PlaybackPhase.loading);
    expect(engine.latest!.playWhenReady, isTrue);
    clock.now += 21000;
    for (var i = 0; i < 3; i++) {
      await engine.pollOnce();
    }
    expect(engine.latest!.phase, PlaybackPhase.error);
  });

  test('should_judgeTheQueueEndConservatively_when_comparingStates', () {
    expect(
      remoteQueueEndReached(
        previous: 'PLAYING',
        current: 'STOPPED',
        oldPositionMs: 0,
        positionMs: 198500,
        durationMs: 200000,
        wantsPlay: true,
      ),
      isTrue,
    );
    expect(
      remoteQueueEndReached(
        previous: 'PAUSED_PLAYBACK',
        current: 'STOPPED',
        oldPositionMs: 0,
        positionMs: 199000,
        durationMs: 200000,
        wantsPlay: true,
      ),
      isFalse,
    );
    expect(
      remoteQueueEndReached(
        previous: 'PLAYING',
        current: 'STOPPED',
        oldPositionMs: 0,
        positionMs: 100000,
        durationMs: 200000,
        wantsPlay: true,
      ),
      isFalse,
    );
  });
}
