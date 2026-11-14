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
        'entryId': null,
        'extra': null,
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

  group('v1 contract', () {
    test('should_sendModeAndRevision_when_startQueue', () async {
      await ChannelPlaybackEngine().startQueue(
        const [EngineItem(id: 'subsonic:1', uri: 'u', title: 'One', entryId: 'e1', extra: '{}')],
        revision: 4,
        startIndex: 0,
        repeat: EngineRepeat.one,
        shuffle: true,
      );
      final args = calls.single.arguments as Map<Object?, Object?>;
      expect(calls.single.method, 'setQueue');
      expect(args['repeat'], 'one');
      expect(args['shuffle'], true);
      expect(args['revision'], 4);
      final item = (args['items']! as List<Object?>).single! as Map;
      expect(item['entryId'], 'e1');
      expect(item['extra'], '{}');
    });

    test('should_sendNamedArguments_when_queueEditsAndModes', () async {
      final engine = ChannelPlaybackEngine();
      await engine.insert(2, const [EngineItem(id: 'subsonic:9', uri: 'u', title: 'x')], revision: 5);
      await engine.removeAt(1, revision: 6);
      await engine.move(0, 3, revision: 7);
      await engine.setRepeat(EngineRepeat.off);
      await engine.setShuffle(true);
      await engine.setFavorite('subsonic:9', null);

      expect(calls.map((c) => c.method), ['insert', 'removeAt', 'move', 'setRepeat', 'setShuffle', 'setFavorite']);
      expect((calls[0].arguments as Map)['at'], 2);
      expect(calls[1].arguments, {'index': 1, 'revision': 6});
      expect(calls[2].arguments, {'from': 0, 'to': 3, 'revision': 7});
      expect(calls[3].arguments, {'repeat': 'off'});
      expect(calls[4].arguments, {'shuffle': true});
      expect(calls[5].arguments, {'id': 'subsonic:9', 'favorite': null});
    });

    test('should_parseHostQueue_when_snapshot', () async {
      messenger.setMockMethodCallHandler(methods, (call) async => {
            'items': [
              {'id': 'subsonic:1', 'title': 'One', 'entryId': 'e1', 'extra': '{"id":"subsonic:1"}', 'durationMs': 1000},
            ],
            'state': {..._stateMap(index: 0), 'type': 'state', 'revision': 3, 'order': [0], 'repeat': 'all', 'shuffle': false},
          });
      final snapshot = await ChannelPlaybackEngine().snapshot();
      expect(snapshot!.items.single.entryId, 'e1');
      expect(snapshot.items.single.durationMs, 1000);
      expect(snapshot.state.revision, 3);
      expect(snapshot.state.order, [0]);
      expect(snapshot.state.repeat, EngineRepeat.all);
    });

    test('should_splitStatesAndEvents_when_envelopeTyped', () async {
      messenger.setMockStreamHandler(
        events,
        MockStreamHandler.inline(onListen: (args, sink) {
          sink.success({..._stateMap(), 'type': 'state', 'playWhenReady': true, 'repeatPlays': 2, 'currentEntryId': 'e1', 'tick': true});
          sink.success({'type': 'openNowPlaying'});
          sink.success({'type': 'command', 'name': 'toggleFavorite', 'id': 'subsonic:1'});
          sink.success({'type': 'networkAvailable'});
          sink.success({'type': 'somethingNew'});
        }),
      );
      final engine = ChannelPlaybackEngine();
      final states = <EngineState>[];
      final received = <EngineEvent>[];
      final s1 = engine.states.listen(states.add);
      final s2 = engine.events.listen(received.add);
      await pumpEventQueue();

      expect(states.single.playWhenReady, isTrue);
      expect(states.single.repeatPlays, 2);
      expect(states.single.currentEntryId, 'e1');
      expect(states.single.tick, isTrue);
      expect(states.single.order, isNull);
      expect(received, hasLength(3));
      expect(received[0], isA<OpenNowPlayingEvent>());
      expect((received[1] as ToggleFavoriteCommand).id, 'subsonic:1');
      expect(received[2], isA<NetworkAvailableEvent>());
      await s1.cancel();
      await s2.cancel();
    });
  });
}
