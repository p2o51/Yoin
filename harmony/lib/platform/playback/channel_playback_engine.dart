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
///
/// The event channel carries an envelope: `{type: 'state', …}` (or no type,
/// the pre-v1 shape) is an [EngineState]; `{type: 'command' | 'openNowPlaying'}`
/// is an [EngineEvent].
final class ChannelPlaybackEngine implements QueuePlaybackEngine {
  ChannelPlaybackEngine({
    MethodChannel methods = const MethodChannel(methodChannelName),
    EventChannel events = const EventChannel(eventChannelName),
  })  : _methods = methods,
        _events = events;

  static const methodChannelName = 'yoin/playback';
  static const eventChannelName = 'yoin/playback/events';

  final MethodChannel _methods;
  final EventChannel _events;

  /// Fans the single platform subscription out to every listener of
  /// [states] and [events] (EngineState or EngineEvent values).
  late final StreamController<Object> _fanOut = StreamController<Object>.broadcast(
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
          (value) {
            if (value is EngineState) controller.add(value);
          },
          onError: controller.addError,
          onDone: controller.close,
        );
        controller.onCancel = sub.cancel;
      });

  @override
  Stream<EngineEvent> get events => _fanOut.stream.where((v) => v is EngineEvent).cast<EngineEvent>();

  void _subscribe() {
    _platform = _events.receiveBroadcastStream().listen(
      (event) {
        if (event is! Map) return;
        final type = event['type'];
        if (type == null || type == 'state') {
          final state = EngineState.fromMap(event);
          _latest = state;
          _fanOut.add(state);
          return;
        }
        final parsed = EngineEvent.fromMap(event);
        if (parsed != null) _fanOut.add(parsed);
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
  Future<void> startQueue(
    List<EngineItem> items, {
    required int revision,
    int startIndex = 0,
    int startPositionMs = 0,
    bool playWhenReady = true,
    EngineRepeat repeat = EngineRepeat.all,
    bool shuffle = false,
  }) =>
      _methods.invokeMethod<void>('setQueue', {
        'items': [for (final item in items) item.toMap()],
        'startIndex': startIndex,
        'startPositionMs': startPositionMs,
        'playWhenReady': playWhenReady,
        'repeat': repeat.name,
        'shuffle': shuffle,
        'revision': revision,
      });

  @override
  Future<void> insert(int at, List<EngineItem> items, {required int revision}) =>
      _methods.invokeMethod<void>('insert', {
        'at': at,
        'items': [for (final item in items) item.toMap()],
        'revision': revision,
      });

  @override
  Future<void> removeAt(int index, {required int revision}) =>
      _methods.invokeMethod<void>('removeAt', {'index': index, 'revision': revision});

  @override
  Future<void> move(int from, int to, {required int revision}) =>
      _methods.invokeMethod<void>('move', {'from': from, 'to': to, 'revision': revision});

  @override
  Future<void> setRepeat(EngineRepeat repeat) => _methods.invokeMethod<void>('setRepeat', {'repeat': repeat.name});

  @override
  Future<void> setShuffle(bool shuffle) => _methods.invokeMethod<void>('setShuffle', {'shuffle': shuffle});

  @override
  Future<void> setFavorite(String id, bool? favorite) =>
      _methods.invokeMethod<void>('setFavorite', {'id': id, 'favorite': favorite});

  @override
  Future<void> setGains(Map<String, double> gainsByEntryId) =>
      _methods.invokeMethod<void>('setGains', {'gains': gainsByEntryId});

  @override
  Future<EngineSnapshot?> snapshot() async {
    final map = await _methods.invokeMethod<Map<Object?, Object?>>('getSnapshot');
    return map == null ? null : EngineSnapshot.fromMap(map);
  }

  @override
  Future<void> play() => _methods.invokeMethod<void>('play');

  @override
  Future<void> pause() => _methods.invokeMethod<void>('pause');

  @override
  Future<void> seekTo(int positionMs) => _methods.invokeMethod<void>('seekTo', {'positionMs': positionMs});

  @override
  Future<void> skipTo(int index) => _methods.invokeMethod<void>('skipTo', {'index': index});

  @override
  Future<void> next() => _methods.invokeMethod<void>('next');

  @override
  Future<void> previous() => _methods.invokeMethod<void>('previous');

  @override
  Future<void> stop() => _methods.invokeMethod<void>('stop');
}
