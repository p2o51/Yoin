import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/playback/replay_gain.dart';
import 'package:yoin/playback/track_codec.dart';

import '../support/playback_fakes.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p');

/// Volume leveling through the playback chain: every queue entry carries the
/// volume its ReplayGain asks for, and a new choice reaches the engine at once.
void main() {
  late PlaybackFakeQueueEngine engine;
  late InMemoryStore values;
  late ProviderContainer c;

  // −9.6 dB with a 0.9886 peak, a quiet one (+1 dB, 0.335 peak), an untagged one.
  final tracks = [
    Track(
      id: MediaId.subsonic('loud'),
      title: 'Loud',
      replayGain: const ReplayGain(trackGainDb: -9.6, albumGainDb: -8, trackPeak: 0.9886, albumPeak: 0.99),
    ),
    Track(
      id: MediaId.subsonic('quiet'),
      title: 'Quiet',
      replayGain: const ReplayGain(trackGainDb: 1, trackPeak: 0.335),
    ),
    Track(id: MediaId.subsonic('plain'), title: 'Plain'),
  ];

  PlaybackController ctl() => c.read(playbackControllerProvider.notifier);

  Future<void> start({String? storedMode, PlaybackFakeSource? using, PlaybackFakeQueueEngine? host}) async {
    engine = host ?? PlaybackFakeQueueEngine();
    values = InMemoryStore();
    if (storedMode != null) await values.write(ReplayGainStore.key, storedMode);
    final repo = ProfileRepository(values, InMemoryStore());
    await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
    final source = using ?? PlaybackFakeSource();
    c = ProviderContainer(
      overrides: [
        playbackEngineProvider.overrideWithValue(engine),
        keyValueStoreProvider.overrideWithValue(values),
        profileRepositoryProvider.overrideWithValue(repo),
        activeSourceProvider.overrideWith((ref) async => source),
      ],
    );
    c.listen(playbackControllerProvider, (_, _) {});
    await c.read(activeSourceProvider.future);
    await pumpEventQueue();
  }

  tearDown(() => c.dispose());

  test('should_sendEachEntrysVolume_when_queueStartsByAlbum', () async {
    await start();
    await ctl().playTracks(tracks);
    final gains = engine.queue.map((i) => i.gain).toList();
    // By album (the default): −8 + 4 dB on the first; the second's +5 dB stops at 1; the third is untouched.
    expect(gains[0], closeTo(dbToLinear(-4), 1e-9));
    expect(gains[1], 1.0);
    expect(gains[2], 1.0);
  });

  test('should_useTheStoredChoice_when_queueStarts', () async {
    await start(storedMode: 'track');
    await ctl().playTracks(tracks);
    expect(engine.queue.first.gain, closeTo(dbToLinear(-5.6), 1e-9));
  });

  test('should_playEverythingAsItIs_when_off', () async {
    await start(storedMode: 'off');
    await ctl().playTracks(tracks);
    expect(engine.queue.map((i) => i.gain), [1.0, 1.0, 1.0]);
  });

  test('should_sendNewVolumesAtOnce_when_choiceChangesWhilePlaying', () async {
    await start();
    await ctl().playTracks(tracks);
    engine.calls.clear();

    await c.read(replayGainProvider.notifier).setMode(ReplayGainMode.off);
    await pumpEventQueue();

    expect(engine.calls, ['setGains:3']);
    expect(engine.gains, {for (final item in engine.queue) item.entryId!: 1.0});

    await c.read(replayGainProvider.notifier).setMode(ReplayGainMode.track);
    await pumpEventQueue();
    expect(engine.gains[engine.queue.first.entryId], closeTo(dbToLinear(-5.6), 1e-9));
  });

  test('should_carryTheVolume_when_songsAreQueued', () async {
    await start(storedMode: 'track');
    await ctl().playTracks([tracks[2]]);
    await ctl().addToQueue([tracks[0]]);
    expect(engine.queue.last.gain, closeTo(dbToLinear(-5.6), 1e-9));
  });

  test('should_leaveTheEngineAlone_when_nothingIsQueued', () async {
    await start();
    await c.read(replayGainProvider.notifier).ready();
    await c.read(replayGainProvider.notifier).setMode(ReplayGainMode.track);
    await pumpEventQueue();
    expect(engine.calls.where((call) => call.startsWith('setGains')), isEmpty);
  });

  test('should_notResend_when_theSameChoiceIsPicked', () async {
    await start();
    await ctl().playTracks(tracks);
    await c.read(replayGainProvider.notifier).ready();
    engine.calls.clear();
    await c.read(replayGainProvider.notifier).setMode(ReplayGainMode.album);
    await pumpEventQueue();
    expect(engine.calls, isEmpty);
  });

  test('should_relevelTheNewQueue_when_choiceChangesWhileItBuilds', () async {
    final gate = Completer<void>();
    await start(using: _GatedSource(gate));
    await c.read(replayGainProvider.notifier).ready();
    final playing = ctl().playTracks(tracks);
    await pumpEventQueue();
    // The queue's items are being built with "by album" when the choice changes.
    await c.read(replayGainProvider.notifier).setMode(ReplayGainMode.off);
    gate.complete();
    await playing;
    await pumpEventQueue();

    expect(engine.queue.first.gain, closeTo(dbToLinear(-4), 1e-9), reason: 'built before the change');
    expect(engine.calls.last, 'setGains:3', reason: 'the new queue is leveled again');
    expect(engine.gains.values, everyElement(1.0));
  });

  EngineItem held(Track t, String entryId, double gain) => EngineItem(
    id: t.id.toString(),
    uri: '',
    title: t.title ?? '',
    entryId: entryId,
    extra: TrackCodec.encode(t),
    gain: gain,
  );

  Future<void> rehydrate({required String storedMode, required double heldGain}) async {
    final host = PlaybackFakeQueueEngine()..queue = [held(tracks[0], 'e0', heldGain), held(tracks[2], 'e1', 1)];
    host.snapshotToReturn = EngineSnapshot(
      items: host.queue,
      state: const EngineState(
        phase: PlaybackPhase.ready,
        isPlaying: true,
        index: 0,
        currentEntryId: 'e0',
        order: [0, 1],
      ),
    );
    await start(storedMode: storedMode, host: host);
    await pumpEventQueue();
  }

  test('should_relevelTheRehydratedQueue_when_itsGainsAreStale', () async {
    // The host still holds "by album" gains, but the stored choice is off.
    await rehydrate(storedMode: 'off', heldGain: dbToLinear(-4));
    expect(c.read(playbackControllerProvider).entryIds, ['e0', 'e1']);
    expect(engine.calls, contains('setGains:2'));
    expect(engine.gains, {'e0': 1.0, 'e1': 1.0});
  });

  test('should_leaveTheRehydratedQueueAlone_when_itsGainsMatch', () async {
    await rehydrate(storedMode: 'album', heldGain: dbToLinear(-4));
    expect(c.read(playbackControllerProvider).entryIds, ['e0', 'e1']);
    expect(engine.calls.where((call) => call.startsWith('setGains')), isEmpty);
  });
}

class _GatedSource extends PlaybackFakeSource {
  _GatedSource(this.gate);
  final Completer<void> gate;
  @override
  Future<PlaybackHandle> handleFor(Track track) async {
    await gate.future;
    return super.handleFor(track);
  }
}
