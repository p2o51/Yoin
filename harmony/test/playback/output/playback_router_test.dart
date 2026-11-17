import 'dart:async';
import 'dart:io' show SocketException;

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/cast/cast_host.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/playback/output/dlna_manager.dart';
import 'package:yoin/playback/output/dlna_protocol.dart';
import 'package:yoin/playback/output/playback_router.dart';
import 'package:yoin/playback/output/renderer_transport.dart';

import 'output_fakes.dart';

void main() {
  late FakeClock clock;
  late MirrorNativeEngine native;
  late Map<String, FakeRenderer> speakers;
  late FakeCastHost cast;
  late PlaybackRouter router;
  late List<EngineState> states;
  late List<EngineEvent> events;
  late FakeVolumeKeys keys;

  setUp(() {
    clock = FakeClock();
    native = MirrorNativeEngine();
    speakers = {};
    cast = FakeCastHost();
    keys = FakeVolumeKeys();
    router = testRouter(clock: clock, native: native, speakers: speakers, cast: cast, volumeKeys: keys);
    states = [];
    events = [];
    router.states.listen(states.add);
    router.events.listen(events.add);
  });

  tearDown(() => router.dispose());

  /// The controller started a queue of [n] and the phone plays [index] at 30 s.
  Future<void> playLocally({int n = 3, int index = 1, List<EngineItem>? items}) async {
    await router.startQueue(items ?? [for (var i = 0; i < n; i++) streamItem(i)], revision: 4, startIndex: index);
    native.index = index;
    native.echo(index: index, isPlaying: true, positionMs: 30000, sampledAtMs: clock.now);
    await pumpEventQueue();
  }

  test('should_passCommandsAndStatesThrough_when_playingOnThisDevice', () async {
    await playLocally();
    expect(native.calls, ['startQueue']);
    expect(states.last.index, 1);
    await router.next();
    await router.seekTo(5000);
    expect(native.calls, ['startQueue', 'next', 'seekTo:5000']);
    expect(router.route.value, const LocalOutput());
  });

  test('should_pauseThePhoneThenPlayOnTheSpeaker_when_transferringToDlna', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    await pumpEventQueue();
    expect(native.calls, contains('pause'));
    final speaker = speakers['uuid:a']!;
    expect(speaker.calls, ['load:subsonic:t1', 'seek:30000', 'play']);
    expect(router.route.value, DlnaOutput(fakeSpeaker('uuid:a')));
    expect(states.last.currentId, 'subsonic:t1');
    expect(states.last.isPlaying, isTrue);
    expect(states.last.revision, 4);
    // The system session shows the speaker's song.
    expect(native.mirrored.last?.item.id, 'subsonic:t1');
    expect(native.mirrored.last?.isPlaying, isTrue);
  });

  test('should_moveTheSpeakersVolume_when_volumeKeysPressedWhileItPlays', () async {
    await playLocally();
    expect(keys.intercepting, isFalse);
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    await pumpEventQueue();
    expect(keys.intercepting, isTrue);
    final speaker = speakers['uuid:a']!;
    expect(speaker.volumeValue, 30);

    keys.press(1);
    await pumpEventQueue();
    expect(speaker.volumeValue, 31);
    expect(speaker.calls, contains('setVolume:31'));

    // Quick presses add up from the last one asked for, not the last reading.
    keys.press(-1);
    keys.press(-1);
    keys.press(-1);
    await pumpEventQueue();
    expect(speaker.volumeValue, 28);
  });

  test('should_stopAtTheEnds_when_volumeKeysHeld', () async {
    await playLocally();
    speakers['uuid:a'] = FakeRenderer()..volumeValue = 100;
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    await pumpEventQueue();
    keys.press(1);
    keys.press(1);
    await pumpEventQueue();
    expect(speakers['uuid:a']!.volumeValue, 100);
  });

  test('should_giveTheKeysBack_when_switchingBackToThisDevice', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    await pumpEventQueue();
    await router.transferToLocal();
    await pumpEventQueue();
    expect(keys.requests, [true, false]);
    speakers['uuid:a']!.calls.clear();
    keys.press(1);
    await pumpEventQueue();
    expect(speakers['uuid:a']!.calls, isEmpty);
  });

  test('should_leaveTheKeysToTheSystem_when_theSpeakerHasNoVolume', () async {
    await playLocally();
    speakers['uuid:a'] = FakeRenderer()..withVolume = false;
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    await pumpEventQueue();
    expect(keys.requests, isEmpty);
    keys.press(1);
    await pumpEventQueue();
    expect(speakers['uuid:a']!.calls.where((c) => c.startsWith('setVolume')), isEmpty);
  });

  test('should_sendCommandsToTheSpeaker_when_itPlays', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    native.calls.clear();
    await router.pause();
    expect(speakers['uuid:a']!.calls.last, 'pause');
    expect(native.calls, isEmpty);
    await router.next();
    expect(speakers['uuid:a']!.calls, contains('load:subsonic:t2'));
  });

  test('should_routeTheSystemKeysToTheSpeaker_when_theSessionMirrorsIt', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    native.event(const SessionTransportCommand('pause', null));
    await pumpEventQueue();
    expect(speakers['uuid:a']!.calls.last, 'pause');
    native.event(const SessionTransportCommand('seek', '12000'));
    await pumpEventQueue();
    expect(speakers['uuid:a']!.calls.last, 'seek:12000');
    native.event(const SessionTransportCommand('playMode', 'one|false'));
    await pumpEventQueue();
    expect(router.dlna.player.repeat, EngineRepeat.one);
    // The controller never sees these.
    expect(events.whereType<SessionTransportCommand>(), isEmpty);
  });

  test('should_forwardOtherSystemEvents_when_routing', () async {
    native.event(const OpenNowPlayingEvent());
    await pumpEventQueue();
    expect(events.single, isA<OpenNowPlayingEvent>());
  });

  test('should_playANewSubsonicQueueOnTheSpeaker_when_oneIsConnected', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    native.calls.clear();
    await router.startQueue([streamItem(7)], revision: 5);
    expect(speakers['uuid:a']!.calls, contains('load:subsonic:t7'));
    expect(native.calls, isEmpty);
    expect(states.last.revision, 5);
  });

  test('should_stopTheSpeakerFirst_when_aQueueItCannotPullStarts', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    native.calls.clear();
    await router.startQueue([const EngineItem(id: 'applemusic:1', uri: 'yoin-am:1', title: 'x')], revision: 5);
    expect(speakers['uuid:a']!.calls.last, 'stop');
    expect(router.route.value, const LocalOutput());
    expect(native.calls, contains('startQueue'));
    expect(native.mirrored.last, isNull);
  });

  test('should_restoreTheQueueWhereItWas_when_switchingBackToThisDevice', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    speakers['uuid:a']!.at = 64000;
    await router.dlna.player.pollOnce();
    native.calls.clear();
    await router.transferToLocal();
    expect(speakers['uuid:a']!.calls.last, 'stop');
    expect(router.route.value, const LocalOutput());
    expect(native.calls, ['pause', 'startQueue']);
    expect(native.startIndex, 1);
    expect(native.startPositionMs, greaterThanOrEqualTo(64000));
    expect(native.playWhenReady, isTrue);
    expect(native.revision, 4);
    expect(native.mirrored.last, isNull);
  });

  test('should_keepTheSpeaker_when_itWontConfirmTheStop', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    speakers['uuid:a']!.ignoreStop = true;
    await expectLater(router.transferToLocal(), throwsA(isA<OutputTransferException>()));
    expect(router.route.value, isA<DlnaOutput>());
    expect(router.dlna.error.value, DlnaError.stopUnconfirmed);
  });

  test('should_throwAndKeepTheSpeakerAndQueue_when_itWontConfirmTheStopForASwitch', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    speakers['uuid:a']!.ignoreStop = true;
    await expectLater(router.leaveRendererForSwitch(), throwsA(isA<SpeakerStopRefused>()));
    // Nothing let go: the switch fails and the old account keeps playing there.
    expect(router.route.value, isA<DlnaOutput>());
    expect(router.dlna.error.value, DlnaError.stopUnconfirmed);
    expect(router.dlna.player.items, isNotEmpty);
  });

  test('should_handBackToThisDevice_when_theSpeakerConfirmsTheStopForASwitch', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    await router.leaveRendererForSwitch();
    expect(speakers['uuid:a']!.calls.last, 'stop');
    expect(router.route.value, const LocalOutput());
    expect(router.dlna.connectedDevice.value, isNull);
  });

  test('should_doNothing_when_switchingWhilePlayingOnThisDevice', () async {
    await playLocally();
    native.calls.clear();
    await router.leaveRendererForSwitch();
    expect(native.calls, isEmpty);
    expect(router.route.value, const LocalOutput());
  });

  test('should_askToPlayFirst_when_nothingIsLoaded', () async {
    await expectLater(
      router.transferToDlna(fakeSpeaker('uuid:a')),
      throwsA(isA<OutputTransferException>().having((e) => e.reason, 'reason', OutputTransferFailure.playFirst)),
    );
  });

  test('should_refuse_when_theQueueIsNotASubsonicStream', () async {
    await playLocally(
      items: [const EngineItem(id: 'applemusic:1', uri: 'yoin-am:1', title: 'x')],
      index: 0,
    );
    await expectLater(
      router.transferToDlna(fakeSpeaker('uuid:a')),
      throwsA(
        isA<OutputTransferException>().having((e) => e.reason, 'reason', OutputTransferFailure.notRemotePlayable),
      ),
    );
    expect(native.calls, isNot(contains('pause')));
  });

  test('should_resumeThePhone_when_theSpeakerRefusesAndConfirmsTheStop', () async {
    await playLocally();
    speakers['uuid:a'] = FakeRenderer()..failing.add('play');
    await expectLater(router.transferToDlna(fakeSpeaker('uuid:a')), throwsA(isA<OutputTransferException>()));
    expect(router.route.value, const LocalOutput());
    expect(native.calls.last, 'play');
  });

  test('should_handTheQueueToTheCastDevice_when_theSystemSessionGoesRemote', () async {
    await playLocally();
    cast.emit(const CastOutput(connection: CastConnection.connected, category: CastCategory.remote, name: 'Vision'));
    await pumpEventQueue();
    expect(router.route.value, const SystemCastOutput('Vision'));
    expect(cast.renderer.calls, ['load:subsonic:t1', 'seek:30000', 'play']);
    expect(router.systemOutput.value?.name, 'Vision');
    await router.pause();
    expect(cast.renderer.calls.last, 'pause');
  });

  test('should_bringTheQueueBack_when_theCastDeviceLetsGo', () async {
    await playLocally();
    cast.emit(const CastOutput(connection: CastConnection.connected, category: CastCategory.remote, name: 'Vision'));
    await pumpEventQueue();
    native.calls.clear();
    cast.emit(const CastOutput(connection: CastConnection.connected, category: CastCategory.local, name: ''));
    await pumpEventQueue();
    expect(router.route.value, const LocalOutput());
    expect(native.calls, contains('startQueue'));
    expect(native.startIndex, 1);
  });

  test('should_ignoreTheCastDevice_when_aSpeakerAlreadyPlays', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    cast.emit(const CastOutput(connection: CastConnection.connected, category: CastCategory.remote, name: 'Vision'));
    await pumpEventQueue();
    expect(router.route.value, isA<DlnaOutput>());
    await expectLater(router.transferToDlna(fakeSpeaker('uuid:b')), completes);
  });

  test('should_refuseASpeaker_when_theCastDeviceHoldsTheSession', () async {
    await playLocally();
    cast.emit(const CastOutput(connection: CastConnection.connected, category: CastCategory.remote, name: 'Vision'));
    await pumpEventQueue();
    await expectLater(
      router.transferToDlna(fakeSpeaker('uuid:a')),
      throwsA(isA<OutputTransferException>().having((e) => e.reason, 'reason', OutputTransferFailure.systemCastActive)),
    );
  });

  test('should_letGoOfTheSpeakerAndStopThePhone_when_theQueueIsCleared', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    await router.stop();
    expect(speakers['uuid:a']!.calls.last, 'stop');
    expect(router.route.value, const LocalOutput());
    expect(native.calls.last, 'stop');
  });

  test('should_keepGainsForThePhone_when_aSpeakerPlays', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    native.calls.clear();
    await router.setGains({'e1': 0.5});
    expect(native.calls, isEmpty);
    await router.transferToLocal();
    expect(native.queue[1].gain, 0.5);
  });

  test('should_openTheSystemPanel_when_asked', () async {
    expect(await router.openSystemPicker(), isTrue);
    expect(cast.pickerOpens, 1);
  });

  test('should_keepTheSessionHeartOnThePhone_when_aSpeakerPlays', () async {
    await playLocally();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    await router.setFavorite('subsonic:t1', true);
    expect(native.hearts['subsonic:t1'], isTrue);
  });

  test('should_handTheSessionBackToThePhone_when_aNewRouterStarts', () async {
    // An earlier isolate may have left the system session mirroring a speaker.
    expect(native.mirrored, [null]);
  });

  test('should_stopTheSpeaker_when_theQueueIsClearedDuringTheHandOver', () async {
    await playLocally();
    final gate = Completer<void>();
    speakers['uuid:a'] = _GatedRenderer(gate.future);
    final transfer = router.transferToDlna(fakeSpeaker('uuid:a'));
    await pumpEventQueue();
    await router.stop();
    gate.complete();
    await transfer;
    expect(speakers['uuid:a']!.calls.last, 'stop');
    expect(router.route.value, const LocalOutput());
    expect(router.dlna.connectedDevice.value, isNull);
  });

  test('should_stopTheCastDevice_when_aNewQueueStartsDuringTheHandOver', () async {
    await playLocally();
    final gate = Completer<void>();
    final gated = _GatedRenderer(gate.future);
    final host = _GatedCastHost(gated);
    final other = testRouter(clock: clock, native: native, speakers: speakers, cast: host);
    addTearDown(other.dispose);
    other.states.listen((_) {});
    await other.startQueue([for (var i = 0; i < 3; i++) streamItem(i)], revision: 4, startIndex: 1);
    native.echo(index: 1, isPlaying: true, positionMs: 30000, sampledAtMs: clock.now);
    await pumpEventQueue();
    host.emit(const CastOutput(connection: CastConnection.connected, category: CastCategory.remote, name: 'Vision'));
    await pumpEventQueue();
    await other.startQueue([streamItem(7)], revision: 5);
    gate.complete();
    await pumpEventQueue();
    expect(other.route.value, const LocalOutput());
    expect(gated.calls.last, 'stop');
    expect(native.queue.single.id, 'subsonic:t7');
  });

  test('should_adoptThePhonesQueue_when_theControllerRebuildsFromASnapshot', () async {
    final items = [for (var i = 0; i < 3; i++) streamItem(i)];
    native
      ..queue = items
      ..snapshotToReturn = EngineSnapshot(
        items: items,
        state: const EngineState(phase: PlaybackPhase.ready, isPlaying: true, index: 2, revision: 9),
      );
    await router.snapshot();
    native.index = 2;
    native.echo(index: 2, isPlaying: true, positionMs: 1000, sampledAtMs: clock.now);
    await pumpEventQueue();
    await router.transferToDlna(fakeSpeaker('uuid:a'));
    expect(speakers['uuid:a']!.calls.first, 'load:subsonic:t2');
    expect(router.dlna.player.revision, 9);
  });

  test('should_sayTheSearchFailed_when_theLanCantBeSearched', () async {
    final offline = testRouter(
      clock: clock,
      native: native,
      speakers: speakers,
      search: () async => throw const SocketException('no multicast'),
    );
    addTearDown(offline.dispose);
    await offline.dlna.discover();
    expect(offline.dlna.devices.value, isEmpty);
    expect(offline.dlna.error.value, DlnaError.discovery);
    expect(offline.dlna.isDiscovering.value, isFalse);
  });
}

/// A renderer whose first load waits for [gate] (a hand-over still in flight).
class _GatedRenderer extends FakeRenderer {
  _GatedRenderer(this.gate);
  final Future<void> gate;

  @override
  Future<void> load(RendererMedia media) async {
    await gate;
    await super.load(media);
  }
}

class _GatedCastHost extends FakeCastHost {
  _GatedCastHost(this.gated);
  final FakeRenderer gated;

  @override
  RendererTransport castTransport() => gated;
}
