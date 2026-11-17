import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/play_trace.dart';
import 'package:yoin/playback/playback_controller.dart';

import '../support/playback_fakes.dart';

const _creds = SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p');

/// What the playback chain writes to the diagnostics log (Android
/// PlaybackManager's PlayTrace lines): starts, transport commands, failures,
/// the decoder's format and the native engine's own lines.
void main() {
  late PlaybackFakeQueueEngine engine;
  late _Source source;
  Object? failure;
  late PlayTrace trace;
  late ProviderContainer c;

  PlaybackController ctl() => c.read(playbackControllerProvider.notifier);

  /// The lines without their time stamps.
  List<String> lines() => [for (final l in trace.lines) l.substring(19)];

  setUp(() async {
    engine = PlaybackFakeQueueEngine();
    failure = null;
    source = _Source(() => failure);
    trace = PlayTrace.memory();
    final values = InMemoryStore();
    final repo = ProfileRepository(values, InMemoryStore());
    await repo.add(provider: MediaId.providerSubsonic, displayName: 'Home', credentials: _creds);
    c = ProviderContainer(
      overrides: [
        playbackEngineProvider.overrideWithValue(engine),
        keyValueStoreProvider.overrideWithValue(values),
        profileRepositoryProvider.overrideWithValue(repo),
        activeSourceProvider.overrideWith((ref) async => source),
        playTraceProvider.overrideWithValue(trace),
      ],
    );
    c.listen(playbackControllerProvider, (_, _) {});
    await c.read(activeSourceProvider.future);
    await pumpEventQueue();
  });
  tearDown(() => c.dispose());

  test('should_logTheStartWithoutTheServer_when_aQueueStarts', () async {
    await ctl().playTracks(playbackTracks(3), startIndex: 1, shuffled: true);
    expect(lines(), ['play subsonic t1 at=1/3 paused=false shuffled=true stream https://…/t1']);
  });

  test('should_logEachTransportCommandWithItsBackend_when_pressed', () async {
    await ctl().pause();
    await ctl().playTracks(playbackTracks(2));
    await ctl().pause();
    await ctl().play();
    await ctl().next();
    await ctl().previous();
    await ctl().skipTo(1);
    await ctl().seekTo(5000);
    expect(lines().where((l) => !l.startsWith('play ')), [
      'pause NONE',
      'pause LOCAL',
      'resume LOCAL',
      'next LOCAL',
      'previous LOCAL',
      'skip to 1 LOCAL',
      'seek 5000 LOCAL',
    ]);
  });

  test('should_logTheFailureWithItsHostMasked_when_aStartFails', () async {
    failure = const SourceNetworkException('failed to connect to music.example.com/203.0.113.4');
    await ctl().playTracks(playbackTracks(1));
    expect(lines(), ['play failed t0 SourceNetworkException: failed to connect to …/…']);
  });

  test('should_logThePlayerError_when_theEngineReportsOne', () async {
    await ctl().playTracks(playbackTracks(1));
    engine.echo(
      index: 0,
      phase: PlaybackPhase.error,
      isPlaying: false,
      errorMessage: '5400103: http://10.0.0.2:4533 refused',
    );
    await pumpEventQueue();
    expect(lines().last, startsWith('player error '));
    expect(lines().last, endsWith('LOCAL: 5400103: http://… refused'));
  });

  test('should_logTheEnginesLinesWithTheirTimeAndMaskedDetail_when_theyArrive', () async {
    final at = DateTime(2026, 11, 17, 7, 30).millisecondsSinceEpoch;
    engine.event(EngineTraceEvent('open cache at=0/1', atMs: at));
    engine.event(const EngineTraceEvent('player error 5400106', detail: 'connect to nas.local:4533 failed'));
    await pumpEventQueue();
    expect(trace.lines.first, '11-17 07:30:00.000 engine open cache at=0/1');
    expect(lines().last, 'engine player error 5400106: connect to …:4533 failed');
  });

  test('should_logTheDecodedFormat_when_theEngineReportsIt', () async {
    engine.event(
      const PlayingFormatEvent(
        id: 'subsonic:t0',
        fromFile: true,
        decoded: AudioFormat(contentType: 'audio/flac', channels: 2, sampleRateHz: 96000, bitDepth: 24),
      ),
    );
    await pumpEventQueue();
    expect(lines(), ['audio input mime=audio/flac channels=2 rate=96000 bitDepth=24 bitrate=null file=true']);
  });

  test('should_readTheEnginesTraceEvent_when_theChannelSendsOne', () {
    final event = EngineEvent.fromMap({'type': 'trace', 'line': 'focus duck shared', 'detail': null, 'atMs': 12});
    expect(event, isA<EngineTraceEvent>());
    final trace = event! as EngineTraceEvent;
    expect(trace.line, 'focus duck shared');
    expect(trace.atMs, 12);
    expect(trace.detail, isNull);
    expect(EngineEvent.fromMap({'type': 'trace', 'line': ''}), isNull);
  });
}

/// Fails every stream address with what [failure] holds.
final class _Source extends PlaybackFakeSource {
  _Source(this.failure);
  final Object? Function() failure;

  @override
  Future<PlaybackHandle> handleFor(Track track) async {
    final error = failure();
    if (error != null) throw error;
    return super.handleFor(track);
  }
}
