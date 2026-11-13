import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/playback/channel_playback_engine.dart';
import 'package:yoin/platform/playback/playback_engine.dart';

Map<String, Object?> _stateMap({
  String phase = 'ready',
  bool isPlaying = false,
  int index = 0,
  String? currentId = 'subsonic:1',
  int positionMs = 0,
}) =>
    {
      'phase': phase,
      'isPlaying': isPlaying,
      'index': index,
      'currentId': currentId,
      'positionMs': positionMs,
      'sampledAtMs': 1700000000000,
      'durationMs': 200000,
      'errorMessage': null,
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const methods = MethodChannel(ChannelPlaybackEngine.methodChannelName);
  const events = EventChannel(ChannelPlaybackEngine.eventChannelName);
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<MethodCall> calls;

  setUp(() {
    calls = [];
    messenger.setMockMethodCallHandler(methods, (call) async {
      calls.add(call);
      return null;
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(methods, null);
    messenger.setMockStreamHandler(events, null);
  });

  group('commands', () {
    test('should_sendItemsAndOptions_when_setQueue', () async {
      final engine = ChannelPlaybackEngine();
      await engine.setQueue(
        const [
          EngineItem(
            id: 'subsonic:1',
            uri: 'https://music.example/rest/stream?id=1',
            headers: {'Authorization': 'Basic abc'},
            title: 'Song',
            artist: 'Artist',
            durationMs: 200000,
          ),
          EngineItem(id: 'subsonic:2', uri: 'https://x/2', title: 'Two'),
        ],
        startIndex: 1,
        startPositionMs: 1500,
        playWhenReady: false,
      );

      final call = calls.single;
      expect(call.method, 'setQueue');
      final args = call.arguments as Map<Object?, Object?>;
      expect(args['startIndex'], 1);
      expect(args['startPositionMs'], 1500);
      expect(args['playWhenReady'], false);
      final items = args['items']! as List<Object?>;
      expect(items, hasLength(2));
      expect(items.first, {
        'id': 'subsonic:1',
        'uri': 'https://music.example/rest/stream?id=1',
        'headers': {'Authorization': 'Basic abc'},
        'title': 'Song',
        'artist': 'Artist',
        'album': null,
        'artworkUri': null,
        'durationMs': 200000,
      });
      expect((items.last! as Map)['headers'], isEmpty);
    });

    test('should_useDefaults_when_setQueueWithoutOptions', () async {
      await ChannelPlaybackEngine().setQueue(const []);

      expect(calls.single.arguments, {
        'items': <Object?>[],
        'startIndex': 0,
        'startPositionMs': 0,
        'playWhenReady': true,
      });
    });

    test('should_sendNamedArguments_when_seekAndSkip', () async {
      final engine = ChannelPlaybackEngine();
      await engine.seekTo(42000);
      await engine.skipTo(3);

      expect(calls[0].method, 'seekTo');
      expect(calls[0].arguments, {'positionMs': 42000});
      expect(calls[1].method, 'skipTo');
      expect(calls[1].arguments, {'index': 3});
    });

    test('should_sendBareMethods_when_transportControls', () async {
      final engine = ChannelPlaybackEngine();
      await engine.play();
      await engine.pause();
      await engine.next();
      await engine.previous();
      await engine.stop();

      expect(calls.map((c) => c.method),
          ['play', 'pause', 'next', 'previous', 'stop']);
      expect(calls.every((c) => c.arguments == null), isTrue);
    });
  });

  group('states', () {
    test('should_parseEveryField_when_eventArrives', () async {
      messenger.setMockStreamHandler(
        events,
        MockStreamHandler.inline(onListen: (args, sink) {
          sink.success({
            ..._stateMap(isPlaying: true, positionMs: 1234),
            'phase': 'error',
            'errorMessage': '5400106: unsupported',
          });
        }),
      );

      final state = await ChannelPlaybackEngine().states.first;

      expect(state.phase, PlaybackPhase.error);
      expect(state.isPlaying, isTrue);
      expect(state.index, 0);
      expect(state.currentId, 'subsonic:1');
      expect(state.positionMs, 1234);
      expect(state.sampledAtMs, 1700000000000);
      expect(state.durationMs, 200000);
      expect(state.errorMessage, '5400106: unsupported');
    });

    test('should_parseIdleState_when_queueEmpty', () async {
      messenger.setMockStreamHandler(
        events,
        MockStreamHandler.inline(onListen: (args, sink) {
          sink.success({
            'phase': 'idle',
            'isPlaying': false,
            'index': -1,
            'currentId': null,
            'positionMs': 0,
            'sampledAtMs': 0,
            'durationMs': null,
            'errorMessage': null,
          });
        }),
      );

      final state = await ChannelPlaybackEngine().states.first;

      expect(state.phase, PlaybackPhase.idle);
      expect(state.index, -1);
      expect(state.currentId, isNull);
      expect(state.durationMs, isNull);
    });

    test('should_replayLatestState_when_secondListenerSubscribes', () async {
      MockStreamHandlerEventSink? nativeSink;
      var listens = 0;
      messenger.setMockStreamHandler(
        events,
        MockStreamHandler.inline(onListen: (args, sink) {
          listens++;
          nativeSink = sink;
          sink.success(_stateMap(positionMs: 0));
        }),
      );
      final engine = ChannelPlaybackEngine();

      final first = <EngineState>[];
      final sub1 = engine.states.listen(first.add);
      await pumpEventQueue();
      nativeSink!.success(_stateMap(isPlaying: true, positionMs: 5000));
      await pumpEventQueue();

      final late = await engine.states.first;

      expect(listens, 1, reason: 'one platform subscription is shared');
      expect(late.isPlaying, isTrue);
      expect(late.positionMs, 5000);
      expect(first.map((s) => s.positionMs), [0, 5000]);
      expect(engine.latest?.positionMs, 5000);
      await sub1.cancel();
    });

    test('should_deliverLaterChanges_when_listening', () async {
      MockStreamHandlerEventSink? nativeSink;
      messenger.setMockStreamHandler(
        events,
        MockStreamHandler.inline(onListen: (args, sink) {
          nativeSink = sink;
          sink.success(_stateMap(phase: 'loading', index: 0));
        }),
      );
      final engine = ChannelPlaybackEngine();
      final received = <PlaybackPhase>[];
      final sub = engine.states.listen((s) => received.add(s.phase));
      await pumpEventQueue();

      nativeSink!.success(_stateMap(phase: 'ready', isPlaying: true));
      nativeSink!.success(_stateMap(phase: 'ended'));
      await pumpEventQueue();

      expect(received,
          [PlaybackPhase.loading, PlaybackPhase.ready, PlaybackPhase.ended]);
      await sub.cancel();
    });

    test('should_resubscribeToNative_when_allListenersLeftAndOneReturns',
        () async {
      var listens = 0;
      var cancels = 0;
      messenger.setMockStreamHandler(
        events,
        MockStreamHandler.inline(
          onListen: (args, sink) {
            listens++;
            sink.success(_stateMap(positionMs: listens * 1000));
          },
          onCancel: (args) => cancels++,
        ),
      );
      final engine = ChannelPlaybackEngine();

      final firstRun = engine.states.listen((_) {});
      await pumpEventQueue();
      await firstRun.cancel();
      await pumpEventQueue();

      final seen = <int>[];
      final secondRun = engine.states.listen((s) => seen.add(s.positionMs));
      await pumpEventQueue();

      expect(listens, 2);
      expect(cancels, 1);
      expect(seen, [1000, 2000], reason: 'cached state first, then the fresh one');
      await secondRun.cancel();
    });
  });
}
