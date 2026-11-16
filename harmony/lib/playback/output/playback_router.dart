/// Where the music plays: this device, a DLNA speaker or the system's cast
/// device. The router is the [QueuePlaybackEngine] the PlaybackController
/// talks to; it passes every command to the renderer in use and that
/// renderer's states back, the way Android's PlaybackService swaps the
/// MediaSession's player between the phone and DlnaMedia3Player
/// (PlaybackManager.transferToDlna / transferFromDlna, CastManager's
/// transferToCast / transferFromCast).
///
/// While a remote renderer plays, the phone's own player stays paused and
/// the system session (播控中心, lock screen) mirrors the renderer; its keys
/// come back as [SessionTransportCommand]s and go to the renderer. Losing a
/// speaker never makes the phone start on its own.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../platform/cast/cast_host.dart';
import '../../platform/playback/playback_engine.dart';
import 'dlna_manager.dart';
import 'dlna_protocol.dart';
import 'remote_queue_engine.dart';

/// The renderer the queue plays on.
@immutable
sealed class OutputRoute {
  const OutputRoute();
}

final class LocalOutput extends OutputRoute {
  const LocalOutput();

  @override
  bool operator ==(Object other) => other is LocalOutput;

  @override
  int get hashCode => 0;
}

final class DlnaOutput extends OutputRoute {
  const DlnaOutput(this.device);
  final DlnaDevice device;

  @override
  bool operator ==(Object other) => other is DlnaOutput && other.device.id == device.id;

  @override
  int get hashCode => device.id.hashCode;
}

/// The system session went to a remote cast device (picked in the system panel).
final class SystemCastOutput extends OutputRoute {
  const SystemCastOutput(this.name);
  final String name;

  @override
  bool operator ==(Object other) => other is SystemCastOutput && other.name == name;

  @override
  int get hashCode => name.hashCode;
}

/// Why a hand-over did not happen; the Devices sheet words it.
enum OutputTransferFailure {
  /// Nothing (from Subsonic) is loaded on this device.
  playFirst,

  /// The queue is not a stream a renderer can pull (Apple Music, another provider).
  notRemotePlayable,

  /// The system's cast device holds the session.
  systemCastActive,

  /// The renderer refused; [DlnaManager.error] says how.
  renderer,
}

final class OutputTransferException implements Exception {
  const OutputTransferException(this.reason);
  final OutputTransferFailure reason;

  @override
  String toString() => 'OutputTransferException(${reason.name})';
}

class PlaybackRouter implements QueuePlaybackEngine {
  PlaybackRouter(
    this.native, {
    DlnaManager? dlna,
    this.castHost = const NoCastHost(),
    RemoteQueueEngine? castPlayer,
    int Function()? nowMs,
    this.handoffTimeout = const Duration(seconds: 5),
  }) : dlna = dlna ?? DlnaManager(),
       _now = nowMs ?? (() => DateTime.now().millisecondsSinceEpoch) {
    this.castPlayer = castPlayer ?? RemoteQueueEngine(onFailure: (message) => castFailure.value = message);
  }

  /// The phone's own engine (ArkTS AVPlayer + AVSession).
  final QueuePlaybackEngine native;
  final DlnaManager dlna;
  final CastHost castHost;
  late final RemoteQueueEngine castPlayer;
  final Duration handoffTimeout;
  final int Function() _now;

  /// Where the queue plays now.
  final ValueNotifier<OutputRoute> route = ValueNotifier(const LocalOutput());

  /// The system session's output device (null until the system says).
  final ValueNotifier<CastOutput?> systemOutput = ValueNotifier(null);

  /// The system cast device stopped answering (null once it recovers).
  final ValueNotifier<String?> castFailure = ValueNotifier(null);

  // The queue as the controller last set it.
  List<EngineItem> _items = const [];
  int _revision = 0;
  EngineRepeat _repeat = EngineRepeat.all;
  bool _shuffle = false;
  EngineState? _nativeLatest;

  final _out = StreamController<EngineState>.broadcast();
  final _events = StreamController<EngineEvent>.broadcast();
  EngineState? _latest;
  final List<StreamSubscription<Object?>> _subs = [];
  bool _listening = false;
  Future<void> _handoff = Future.value();

  RemoteQueueEngine? get _remote => switch (route.value) {
    DlnaOutput() => dlna.player,
    SystemCastOutput() => castPlayer,
    LocalOutput() => null,
  };

  /// Whether a remote renderer plays the queue.
  bool get isRemote => _remote != null;

  // ---- wiring ----

  void _ensureListening() {
    if (_listening) return;
    _listening = true;
    _subs
      ..add(native.states.listen(_onNativeState, onError: (Object _) {}))
      ..add(native.events.listen(_onNativeEvent, onError: (Object _) {}))
      ..add(dlna.player.states.listen((s) => _onRemoteState(dlna.player, s)))
      ..add(castPlayer.states.listen((s) => _onRemoteState(castPlayer, s)))
      ..add(castHost.outputs.listen(_onSystemOutput, onError: (Object _) {}));
    dlna.connectedDevice.addListener(_onDlnaDevice);
  }

  @override
  Stream<EngineState> get states {
    _ensureListening();
    return Stream<EngineState>.multi((controller) {
      final latest = _latest;
      if (latest != null) controller.add(latest);
      final sub = _out.stream.listen(controller.add, onError: controller.addError);
      controller.onCancel = sub.cancel;
    });
  }

  @override
  Stream<EngineEvent> get events {
    _ensureListening();
    return _events.stream;
  }

  void _publish(EngineState state) {
    _latest = state;
    _out.add(state);
  }

  void _onNativeState(EngineState state) {
    _nativeLatest = state;
    if (_quiet(state)) {
      for (final waiter in _quietWaiters) {
        if (!waiter.isCompleted) waiter.complete();
      }
      _quietWaiters.clear();
    }
    if (route.value is LocalOutput) _publish(state);
  }

  static bool _quiet(EngineState? s) => s == null || (!s.isPlaying && s.playWhenReady != true);
  final List<Completer<void>> _quietWaiters = [];

  void _onRemoteState(RemoteQueueEngine engine, EngineState state) {
    if (!identical(_remote, engine)) return;
    _publish(state);
    _mirror(state);
  }

  void _onNativeEvent(EngineEvent event) {
    if (event is SessionTransportCommand) {
      _onSessionCommand(event);
      return;
    }
    _events.add(event);
  }

  /// The system's keys while the session mirrors a renderer.
  void _onSessionCommand(SessionTransportCommand command) {
    final remote = _remote;
    if (remote == null) return;
    switch (command.name) {
      case 'play':
        remote.play();
      case 'pause':
        remote.pause();
      case 'stop':
        remote.stop();
      case 'next':
        remote.next();
      case 'previous':
        remote.previous();
      case 'seek':
        final target = int.tryParse(command.value ?? '');
        if (target != null) remote.seekTo(target);
      case 'playMode':
        final parts = (command.value ?? '').split('|');
        final repeat = EngineRepeat.values.asNameMap()[parts.first];
        if (repeat != null) setRepeat(repeat);
        if (parts.length > 1) setShuffle(parts[1] == 'true');
    }
  }

  /// The system session shows the renderer's song and progress.
  void _mirror(EngineState state) {
    final mirror = native;
    if (mirror is! RemoteSessionMirror) return;
    final items = _remote?.items ?? _items;
    final index = state.index;
    if (index < 0 || index >= items.length) return;
    (mirror as RemoteSessionMirror)
        .setRemoteSession(
          RemoteSessionState(
            item: items[index],
            phase: state.phase,
            isPlaying: state.isPlaying,
            playWhenReady: state.playWhenReady ?? state.isPlaying,
            positionMs: state.positionMs,
            sampledAtMs: state.sampledAtMs,
            durationMs: state.durationMs,
            repeat: state.repeat ?? _repeat,
            shuffle: state.shuffle ?? _shuffle,
          ),
        )
        .catchError((Object _) {});
  }

  void _clearMirror() {
    final mirror = native;
    if (mirror is RemoteSessionMirror) (mirror as RemoteSessionMirror).setRemoteSession(null).catchError((Object _) {});
  }

  /// PlaybackService: every speaker change pauses the phone's player and swaps the session's player.
  void _onDlnaDevice() {
    final device = dlna.connectedDevice.value;
    if (device != null) {
      _setRoute(DlnaOutput(device));
    } else if (route.value is DlnaOutput) {
      _setRoute(const LocalOutput());
    }
  }

  void _setRoute(OutputRoute next) {
    if (route.value == next) return;
    route.value = next;
    native.pause().catchError((Object _) {});
    final remote = _remote;
    if (remote == null) {
      _clearMirror();
      final local = _nativeLatest;
      if (local != null) _publish(local);
    } else {
      final latest = remote.latest;
      if (latest != null) {
        _publish(latest);
        _mirror(latest);
      }
    }
  }

  // ---- hand-overs ----

  Future<void> _serial(Future<void> Function() op) {
    final result = _handoff.then((_) => op());
    _handoff = result.then((_) {}, onError: (Object _) {});
    return result;
  }

  /// Hands the queue to [device] once the phone has paused (Android
  /// PlaybackManager.transferToDlna). From another speaker, that one is
  /// stopped first. Throws [OutputTransferException].
  Future<void> transferToDlna(DlnaDevice device) => _serial(() async {
    if (route.value is SystemCastOutput) throw const OutputTransferException(OutputTransferFailure.systemCastActive);
    final fromSpeaker = route.value is DlnaOutput;
    final List<EngineItem> items;
    final int index;
    final int position;
    final bool playWhenReady;
    final List<int>? order;
    if (fromSpeaker) {
      final player = dlna.player;
      items = player.items;
      index = player.index;
      position = player.positionMs;
      playWhenReady = player.playWhenReady;
      order = player.order;
    } else {
      final state = _nativeLatest;
      if (_items.isEmpty || state == null || state.index < 0 || state.index >= _items.length) {
        throw const OutputTransferException(OutputTransferFailure.playFirst);
      }
      items = _items;
      index = state.index;
      position = _positionOf(state);
      playWhenReady = state.playWhenReady ?? state.isPlaying;
      order = state.order;
    }
    if (items.isEmpty) throw const OutputTransferException(OutputTransferFailure.playFirst);
    if (!items.every(isRemotePlayable)) throw const OutputTransferException(OutputTransferFailure.notRemotePlayable);
    if (!fromSpeaker) await _pauseNative();
    try {
      await dlna.connect(
        device,
        items,
        index: index,
        positionMs: position,
        playWhenReady: playWhenReady,
        repeat: _repeat,
        shuffle: _shuffle,
        revision: _revision,
        order: order,
      );
    } catch (_) {
      // The speaker let go only after a confirmed Stop: the phone may pick up again.
      if (!fromSpeaker && dlna.connectedDevice.value == null && playWhenReady) {
        await native.play().catchError((Object _) {});
      }
      throw const OutputTransferException(OutputTransferFailure.renderer);
    }
  });

  /// Stops the speaker, then restores the same queue and position on this
  /// device (Android transferFromDlna). A refused Stop keeps the speaker.
  Future<void> transferToLocal() => _serial(() async {
    if (route.value is! DlnaOutput) return;
    final player = dlna.player;
    final items = player.items.length == _items.length ? _items : player.items;
    final index = player.index;
    final position = player.positionMs;
    final playWhenReady = player.playWhenReady;
    try {
      await dlna.disconnect();
    } catch (_) {
      throw const OutputTransferException(OutputTransferFailure.renderer);
    }
    if (items.isEmpty || index < 0 || index >= items.length) return;
    _items = items;
    await native.startQueue(
      items,
      revision: _revision,
      startIndex: index,
      startPositionMs: position,
      playWhenReady: playWhenReady,
      repeat: _repeat,
      shuffle: _shuffle,
    );
  });

  /// Opens the system's output / cast panel.
  Future<bool> openSystemPicker() => castHost.openPicker();

  void _onSystemOutput(CastOutput output) {
    systemOutput.value = output;
    if (output.isRemote) {
      if (route.value is LocalOutput) _serial(() => _enterSystemCast(output.name)).catchError((Object _) {});
      return;
    }
    // Back on a local output, or the cast device let go (a new one still connecting changes nothing).
    if (output.connection != CastConnection.connecting && route.value is SystemCastOutput) {
      _serial(_leaveSystemCast).catchError((Object _) {});
    }
  }

  /// The session went to a cast device: its queue follows (CastManager.transferToCast).
  Future<void> _enterSystemCast(String name) async {
    if (route.value is! LocalOutput) return;
    final state = _nativeLatest;
    if (_items.isEmpty || state == null || state.index < 0 || state.index >= _items.length) return;
    if (!_items.every(isRemotePlayable)) return;
    final playWhenReady = state.playWhenReady ?? state.isPlaying;
    final position = _positionOf(state);
    await _pauseNative();
    try {
      await castPlayer.connect(
        castHost.castTransport(),
        _items,
        startIndex: state.index,
        startPositionMs: position,
        playWhenReady: playWhenReady,
        repeat: _repeat,
        shuffle: _shuffle,
        revision: _revision,
        order: state.order,
      );
      _setRoute(SystemCastOutput(name));
    } catch (_) {
      await castPlayer.disconnect(stopRemote: false).catchError((Object _) {});
      if (playWhenReady) await native.play().catchError((Object _) {});
    }
  }

  /// The cast device let go: the queue comes back here where it was (CastManager.transferFromCast).
  Future<void> _leaveSystemCast() async {
    if (route.value is! SystemCastOutput) return;
    final items = castPlayer.items.length == _items.length ? _items : castPlayer.items;
    final index = castPlayer.index;
    final position = castPlayer.positionMs;
    final playWhenReady = castPlayer.playWhenReady;
    await castPlayer.disconnect(stopRemote: false).catchError((Object _) {});
    castFailure.value = null;
    _setRoute(const LocalOutput());
    if (items.isEmpty || index < 0 || index >= items.length) return;
    _items = items;
    await native.startQueue(
      items,
      revision: _revision,
      startIndex: index,
      startPositionMs: position,
      playWhenReady: playWhenReady,
      repeat: _repeat,
      shuffle: _shuffle,
    );
  }

  /// Pauses the phone and waits until it is quiet (Android waits on playWhenReady / isPlaying).
  Future<void> _pauseNative() async {
    final quiet = Completer<void>();
    _quietWaiters.add(quiet);
    await native.pause();
    if (_quiet(_nativeLatest)) {
      _quietWaiters.remove(quiet);
      return;
    }
    try {
      await quiet.future.timeout(handoffTimeout);
    } finally {
      _quietWaiters.remove(quiet);
    }
  }

  int _positionOf(EngineState s) {
    var position = s.positionMs;
    if (s.isPlaying) position += _now() - s.sampledAtMs;
    final duration = s.durationMs;
    if (duration != null && duration > 0 && position > duration) position = duration;
    return position < 0 ? 0 : position;
  }

  // ---- the engine contract ----

  @override
  Future<void> setQueue(
    List<EngineItem> items, {
    int startIndex = 0,
    int startPositionMs = 0,
    bool playWhenReady = true,
  }) => startQueue(
    items,
    revision: _revision + 1,
    startIndex: startIndex,
    startPositionMs: startPositionMs,
    playWhenReady: playWhenReady,
  );

  @override
  Future<void> startQueue(
    List<EngineItem> items, {
    required int revision,
    int startIndex = 0,
    int startPositionMs = 0,
    bool playWhenReady = true,
    EngineRepeat repeat = EngineRepeat.all,
    bool shuffle = false,
  }) async {
    _items = [...items];
    _revision = revision;
    _repeat = repeat;
    _shuffle = shuffle;
    final remote = _remote;
    if (remote != null) {
      if (items.isNotEmpty && items.every(isRemotePlayable)) {
        return remote.startQueue(
          items,
          revision: revision,
          startIndex: startIndex,
          startPositionMs: startPositionMs,
          playWhenReady: playWhenReady,
          repeat: repeat,
          shuffle: shuffle,
        );
      }
      // Another provider's queue: a renderer can't pull it. Stop the speaker
      // first; a refused Stop must not start a second output.
      await _leaveRemote();
    }
    return native.startQueue(
      items,
      revision: revision,
      startIndex: startIndex,
      startPositionMs: startPositionMs,
      playWhenReady: playWhenReady,
      repeat: repeat,
      shuffle: shuffle,
    );
  }

  Future<void> _leaveRemote() async {
    switch (route.value) {
      case DlnaOutput():
        await dlna.disconnect();
      case SystemCastOutput():
        await castPlayer.disconnect(stopRemote: false).catchError((Object _) {});
        _setRoute(const LocalOutput());
      case LocalOutput():
        break;
    }
  }

  @override
  Future<void> insert(int at, List<EngineItem> items, {required int revision}) {
    _items = [..._items]..insertAll(at.clamp(0, _items.length), items);
    _revision = revision;
    return _remote?.insert(at, items, revision: revision) ?? native.insert(at, items, revision: revision);
  }

  @override
  Future<void> removeAt(int index, {required int revision}) {
    if (index >= 0 && index < _items.length) _items = [..._items]..removeAt(index);
    _revision = revision;
    return _remote?.removeAt(index, revision: revision) ?? native.removeAt(index, revision: revision);
  }

  @override
  Future<void> move(int from, int to, {required int revision}) {
    if (from >= 0 && from < _items.length) {
      final items = [..._items];
      items.insert(to.clamp(0, items.length - 1), items.removeAt(from));
      _items = items;
    }
    _revision = revision;
    return _remote?.move(from, to, revision: revision) ?? native.move(from, to, revision: revision);
  }

  @override
  Future<void> setRepeat(EngineRepeat repeat) {
    _repeat = repeat;
    return _remote?.setRepeat(repeat) ?? native.setRepeat(repeat);
  }

  @override
  Future<void> setShuffle(bool shuffle) {
    _shuffle = shuffle;
    return _remote?.setShuffle(shuffle) ?? native.setShuffle(shuffle);
  }

  /// The session heart stays on the phone's session, whoever plays.
  @override
  Future<void> setFavorite(String id, bool? favorite) => native.setFavorite(id, favorite);

  /// A renderer plays the file as it is; the gains wait for the phone.
  @override
  Future<void> setGains(Map<String, double> gainsByEntryId) {
    _items = [
      for (final item in _items)
        switch (gainsByEntryId[item.entryId]) {
          final double gain => _withGain(item, gain),
          _ => item,
        },
    ];
    return _remote == null ? native.setGains(gainsByEntryId) : Future.value();
  }

  @override
  Future<EngineSnapshot?> snapshot() => native.snapshot();

  @override
  Future<void> play() => _remote?.play() ?? native.play();

  @override
  Future<void> pause() => _remote?.pause() ?? native.pause();

  @override
  Future<void> seekTo(int positionMs) => _remote?.seekTo(positionMs) ?? native.seekTo(positionMs);

  @override
  Future<void> skipTo(int index) => _remote?.skipTo(index) ?? native.skipTo(index);

  @override
  Future<void> next() => _remote?.next() ?? native.next();

  @override
  Future<void> previous() => _remote?.previous() ?? native.previous();

  /// Clearing the queue (account switch, sign-out) lets go of a renderer too;
  /// a refused speaker Stop keeps it listed so the sheet can retry.
  @override
  Future<void> stop() async {
    _items = const [];
    if (_remote != null) {
      try {
        await _leaveRemote();
      } catch (_) {
        // DlnaManager.error says the Stop was not confirmed.
      }
    }
    await native.stop();
  }

  @visibleForTesting
  void dispose() {
    for (final sub in _subs) {
      sub.cancel();
    }
    dlna.connectedDevice.removeListener(_onDlnaDevice);
  }
}

EngineItem _withGain(EngineItem item, double gain) => EngineItem(
  id: item.id,
  uri: item.uri,
  headers: item.headers,
  localUri: item.localUri,
  title: item.title,
  artist: item.artist,
  album: item.album,
  artworkUri: item.artworkUri,
  durationMs: item.durationMs,
  entryId: item.entryId,
  extra: item.extra,
  gain: gain,
);
