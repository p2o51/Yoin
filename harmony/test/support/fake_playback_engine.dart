import 'dart:async';

import 'package:yoin/platform/playback/playback_engine.dart';

/// Records commands; tests push states with [emit].
class FakePlaybackEngine implements PlaybackEngine {
  final _states = StreamController<EngineState>.broadcast();
  final List<String> calls = [];
  List<EngineItem> queue = const [];
  int startIndex = -1;

  void emit(EngineState state) => _states.add(state);

  @override
  Stream<EngineState> get states => _states.stream;

  @override
  Future<void> setQueue(List<EngineItem> items, {int startIndex = 0, int startPositionMs = 0, bool playWhenReady = true}) async {
    queue = items;
    this.startIndex = startIndex;
    calls.add('setQueue');
  }

  @override
  Future<void> play() async => calls.add('play');
  @override
  Future<void> pause() async => calls.add('pause');
  @override
  Future<void> seekTo(int positionMs) async => calls.add('seekTo:$positionMs');
  @override
  Future<void> skipTo(int index) async => calls.add('skipTo:$index');
  @override
  Future<void> next() async => calls.add('next');
  @override
  Future<void> previous() async => calls.add('previous');
  @override
  Future<void> stop() async => calls.add('stop');
}
