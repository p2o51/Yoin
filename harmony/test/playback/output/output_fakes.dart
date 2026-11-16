import 'dart:async';
import 'dart:math';

import 'package:yoin/platform/cast/cast_host.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/playback/output/dlna_client.dart';
import 'package:yoin/playback/output/dlna_discovery.dart';
import 'package:yoin/playback/output/dlna_manager.dart';
import 'package:yoin/playback/output/dlna_protocol.dart';
import 'package:yoin/playback/output/playback_router.dart';
import 'package:yoin/playback/output/remote_queue_engine.dart';
import 'package:yoin/playback/output/renderer_transport.dart';

import '../../support/playback_fakes.dart';

/// A test clock the engine reads and its waits move.
class FakeClock {
  int now = 1000;
  int call() => now;
  Future<void> sleep(Duration d) async => now += d.inMilliseconds;
}

/// A renderer in memory: it plays what it was given and answers like a UPnP speaker.
class FakeRenderer implements RendererTransport {
  final List<String> calls = [];
  String state = RendererState.stopped;
  String? status;
  int at = 0;
  int? duration;
  String? trackUri;
  int volumeValue = 30;
  bool withVolume = true;

  /// Commands that throw (by name: load, play, pause, stop, seek, position, transportInfo).
  final Set<String> failing = {};

  /// Play is acknowledged; false leaves the renderer transitioning forever.
  bool playStarts = true;

  /// A Stop the renderer acknowledges but never carries out.
  bool ignoreStop = false;
  int? seekErrorCode;

  void _maybeFail(String name) {
    if (failing.contains(name)) throw DlnaCommandException(name);
  }

  @override
  Future<void> load(RendererMedia media) async {
    calls.add('load:${media.id}');
    _maybeFail('load');
    trackUri = media.uri;
    state = RendererState.stopped;
    at = 0;
    duration = media.durationMs;
  }

  @override
  Future<void> play() async {
    calls.add('play');
    _maybeFail('play');
    state = playStarts ? RendererState.playing : RendererState.transitioning;
  }

  @override
  Future<void> pause() async {
    calls.add('pause');
    _maybeFail('pause');
    state = RendererState.paused;
  }

  @override
  Future<void> stop() async {
    calls.add('stop');
    _maybeFail('stop');
    if (!ignoreStop) state = RendererState.stopped;
  }

  @override
  Future<void> seek(int positionMs) async {
    calls.add('seek:$positionMs');
    final code = seekErrorCode;
    if (code != null) throw DlnaCommandException('Seek', code);
    _maybeFail('seek');
    at = positionMs;
  }

  @override
  Future<RendererPosition> position() async {
    _maybeFail('position');
    return RendererPosition(positionMs: at, durationMs: duration, trackUri: trackUri);
  }

  @override
  Future<RendererTransportInfo> transportInfo() async {
    _maybeFail('transportInfo');
    return RendererTransportInfo(state, status);
  }

  @override
  bool get hasVolume => withVolume;

  @override
  Future<int> volume() async => volumeValue;

  @override
  Future<void> setVolume(int volume) async {
    calls.add('setVolume:$volume');
    volumeValue = volume;
  }
}

/// A Subsonic-like stream item.
EngineItem streamItem(int i, {int durationMs = 200000}) => EngineItem(
  id: 'subsonic:t$i',
  uri: 'http://music.local/rest/stream?id=t$i&t=token',
  title: 'Song $i',
  artist: 'Artist',
  album: 'Album',
  durationMs: durationMs,
  entryId: 'e$i',
);

/// A system cast panel in memory.
class FakeCastHost implements CastHost {
  final _outputs = StreamController<CastOutput>.broadcast();
  final FakeRenderer renderer = FakeRenderer();
  int pickerOpens = 0;
  bool pickerAvailable = true;

  void emit(CastOutput output) => _outputs.add(output);

  @override
  bool get hasPicker => true;

  @override
  Future<bool> openPicker() async {
    pickerOpens++;
    return pickerAvailable;
  }

  @override
  Stream<CastOutput> get outputs => _outputs.stream;

  @override
  RendererTransport castTransport() => renderer;
}

/// The phone's engine: pausing reports a paused state; the session mirror is recorded.
class MirrorNativeEngine extends PlaybackFakeQueueEngine implements RemoteSessionMirror {
  final List<RemoteSessionState?> mirrored = [];
  int index = 0;

  @override
  Future<void> pause() async {
    await super.pause();
    echo(index: index, isPlaying: false, positionMs: 30000, sampledAtMs: 1000);
  }

  @override
  Future<void> setRemoteSession(RemoteSessionState? state) async => mirrored.add(state);
}

DlnaDevice fakeSpeaker(String id, {String? name}) => DlnaDevice(
  id: id,
  name: name ?? 'Speaker $id',
  descriptionUrl: Uri.parse('http://10.0.0.9/$id.xml'),
  avTransport: DlnaService('urn:schemas-upnp-org:service:AVTransport:1', Uri.parse('http://10.0.0.9/$id/av')),
);

/// A router over in-memory renderers: [speakers] answer by device id, [found] is what discovery finds.
PlaybackRouter testRouter({
  required FakeClock clock,
  required QueuePlaybackEngine native,
  required Map<String, FakeRenderer> speakers,
  CastHost cast = const NoCastHost(),
  Future<Set<Uri>> Function()? search,
  DlnaClient? describer,
}) {
  RemoteQueueEngine engine(void Function(String?)? onFailure) => RemoteQueueEngine(
    onFailure: onFailure,
    nowMs: clock.call,
    pollInterval: null,
    random: Random(1),
    sleep: clock.sleep,
  );
  return PlaybackRouter(
    native,
    dlna: DlnaManager(
      client: DlnaClient(http: (method, url, {headers = const {}, body}) async => throw Exception('offline')),
      discovery: DlnaDiscovery(
        describer ?? DlnaClient(http: (method, url, {headers = const {}, body}) async => throw Exception('offline')),
        search: search ?? () async => const {},
      ),
      engine: engine,
      transportFor: (client, device) => speakers.putIfAbsent(device.id, FakeRenderer.new),
    ),
    castHost: cast,
    castPlayer: engine(null),
    nowMs: clock.call,
    handoffTimeout: const Duration(milliseconds: 200),
  );
}
