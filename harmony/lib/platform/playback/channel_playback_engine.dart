/// [PlaybackEngine] over the `yoin/playback` method channel and the
/// `yoin/playback/events` event channel served by the ArkTS
/// `YoinPlaybackPlugin` (ohos/entry/src/main/ets/yoin/playback/).
library;

import 'dart:async';

import 'package:flutter/services.dart';

import 'playback_engine.dart';

/// Talks to the native engine. One instance per app: the event channel has a
/// single native sink, so every Dart listener shares one platform
/// subscription here.
final class ChannelPlaybackEngine implements PlaybackEngine {
  ChannelPlaybackEngine({
    MethodChannel methods = const MethodChannel(methodChannelName),
    EventChannel events = const EventChannel(eventChannelName),
  })  : _methods = methods,
        _events = events;

  static const methodChannelName = 'yoin/playback';
  static const eventChannelName = 'yoin/playback/events';

  final MethodChannel _methods;
  final EventChannel _events;

  /// Fans the single platform subscription out to every [states] listener.
  late final StreamController<EngineState> _fanOut =
      StreamController<EngineState>.broadcast(
    onListen: _subscribe,
    onCancel: _unsubscribe,
  );
  StreamSubscription<Object?>? _platform;

  /// Last state from native; replayed to each new listener so a late
  /// subscriber (a page opened mid-song) does not wait for the next change.
  EngineState? _latest;

  /// The most recent state, or null before the first event arrived.
  EngineState? get latest => _latest;

  @override
  Stream<EngineState> get states => Stream<EngineState>.multi((controller) {
        final latest = _latest;
        if (latest != null) controller.add(latest);
        final sub = _fanOut.stream.listen(
          controller.add,
          onError: controller.addError,
          onDone: controller.close,
        );
        controller.onCancel = sub.cancel;
      });

  void _subscribe() {
    _platform = _events.receiveBroadcastStream().listen(
      (event) {
        if (event is! Map) return;
        final state = EngineState.fromMap(event);
        _latest = state;
        _fanOut.add(state);
      },
      onError: _fanOut.addError,
    );
  }

  Future<void> _unsubscribe() async {
    final sub = _platform;
    _platform = null;
    await sub?.cancel();
  }

  @override
  Future<void> setQueue(
    List<EngineItem> items, {
    int startIndex = 0,
    int startPositionMs = 0,
    bool playWhenReady = true,
  }) =>
      _methods.invokeMethod<void>('setQueue', {
        'items': [for (final item in items) item.toMap()],
        'startIndex': startIndex,
        'startPositionMs': startPositionMs,
        'playWhenReady': playWhenReady,
      });

  @override
  Future<void> play() => _methods.invokeMethod<void>('play');

  @override
  Future<void> pause() => _methods.invokeMethod<void>('pause');

  @override
  Future<void> seekTo(int positionMs) =>
      _methods.invokeMethod<void>('seekTo', {'positionMs': positionMs});

  @override
  Future<void> skipTo(int index) =>
      _methods.invokeMethod<void>('skipTo', {'index': index});

  @override
  Future<void> next() => _methods.invokeMethod<void>('next');

  @override
  Future<void> previous() => _methods.invokeMethod<void>('previous');

  @override
  Future<void> stop() => _methods.invokeMethod<void>('stop');
}
