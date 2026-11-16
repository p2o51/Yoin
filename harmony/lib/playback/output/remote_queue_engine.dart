/// The queue played on a remote renderer (Android
/// player/dlna/DlnaMedia3Player.kt): it owns the queue, its play order and
/// repeat, hands the renderer one song at a time and follows it by polling
/// once a second. The renderer pulls the original HTTP stream itself.
///
/// It reports [EngineState]s exactly like the native engine, so the
/// PlaybackController reads a speaker the way it reads the phone.
library;

import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';

import '../../platform/playback/playback_engine.dart';
import 'dlna_protocol.dart';
import 'renderer_transport.dart';

@immutable
final class _Entry {
  const _Entry(this.uid, this.item);
  final int uid;
  final EngineItem item;
}

/// Whether a renderer can play [item]: an http(s) stream that needs no
/// request headers (a Subsonic stream; never Apple Music or a local file).
bool isRemotePlayable(EngineItem item) => DlnaProtocol.httpUri(item.uri) != null && item.headers.isEmpty;

/// Conservative completion: an arbitrary external Stop must never silently
/// advance Yoin's queue (Android DlnaQueueEnd).
bool remoteQueueEndReached({
  required String previous,
  required String current,
  required int oldPositionMs,
  required int positionMs,
  required int durationMs,
  required bool wantsPlay,
}) =>
    wantsPlay &&
    previous == RendererState.playing &&
    current == RendererState.stopped &&
    durationMs > 0 &&
    max(oldPositionMs, positionMs) >= max(0, durationMs - 2000);

/// What the engine says when it can't drive the renderer (Android reportFailure).
const remoteFailureMessage = 'Cannot control the speaker. Check its connection and stream access.';

class RemoteQueueEngine {
  RemoteQueueEngine({
    this.onFailure,
    int Function()? nowMs,
    this.pollInterval = const Duration(seconds: 1),
    this.loadTimeout = const Duration(seconds: 20),
    Random? random,
    Future<void> Function(Duration)? sleep,
  }) : _now = nowMs ?? (() => DateTime.now().millisecondsSinceEpoch),
       _random = random ?? Random(),
       _sleep = sleep;

  /// The renderer failed (message) or recovered (null).
  final void Function(String? message)? onFailure;

  /// How often the renderer is read while connected; null = only on demand (tests).
  final Duration? pollInterval;

  /// A song that hasn't started after this long is a failure.
  final Duration loadTimeout;

  final int Function() _now;
  final Random _random;
  final Future<void> Function(Duration)? _sleep;

  final _states = StreamController<EngineState>.broadcast();
  EngineState? _latest;
  String? _lastKey;

  RendererTransport? _transport;
  List<_Entry> _entries = const [];
  int _nextUid = 0;
  int _index = 0;
  int _positionMs = 0;
  int _sampledAtMs = 0;
  int _durationMs = 0;
  String _observed = RendererState.stopped;
  bool _desiredPlay = false;
  bool _prepared = false;
  bool _loading = false;
  int _loadStartedMs = 0;
  String? _failure;
  int _pollFailures = 0;
  EngineRepeat _repeat = EngineRepeat.all;
  bool _shuffle = false;
  List<int> _shuffledUids = const [];
  double _volume = 1;
  bool _seekSupported = true;
  bool _released = false;
  int _revision = 0;
  int _repeatPlays = 0;
  Timer? _poll;
  Future<void> _lock = Future.value();

  // ---- reading ----

  /// Current state first, then every change and a sample after each poll.
  Stream<EngineState> get states => Stream<EngineState>.multi((controller) {
    final latest = _latest;
    if (latest != null) controller.add(latest);
    final sub = _states.stream.listen(controller.add, onError: controller.addError, onDone: controller.close);
    controller.onCancel = sub.cancel;
  });

  EngineState? get latest => _latest;
  bool get hasDevice => _transport != null;
  List<EngineItem> get items => [for (final e in _entries) e.item];
  int get index => _index;
  bool get playWhenReady => _desiredPlay;
  EngineRepeat get repeat => _repeat;
  bool get shuffle => _shuffle;
  int get revision => _revision;
  bool get seekSupported => _seekSupported;

  /// 0–1, as the renderer last reported it.
  double get volume => _volume;

  /// Where the renderer is now: the last reading, moved on by the clock while it plays.
  int get positionMs {
    if (_observed != RendererState.playing || _loading || _failure != null) return _positionMs;
    final elapsed = _now() - _sampledAtMs;
    final moved = _positionMs + (elapsed > 0 ? elapsed : 0);
    return _durationMs > 0 && moved > _durationMs ? _durationMs : moved;
  }

  /// The play order as queue indices.
  List<int> get order => _orderIndices();

  // ---- connecting ----

  /// Hands [items] to [transport] and starts [startIndex] at [startPositionMs].
  /// Throws when the renderer refuses; the transport stays attached so the
  /// caller can still Stop it ([disconnect]).
  Future<void> connect(
    RendererTransport transport,
    List<EngineItem> items, {
    required int startIndex,
    required int startPositionMs,
    required bool playWhenReady,
    required EngineRepeat repeat,
    required bool shuffle,
    required int revision,
    List<int>? order,
  }) async {
    await _locked(() async {
      if (_released) throw StateError('released');
      if (items.isNotEmpty && (startIndex < 0 || startIndex >= items.length)) throw RangeError.index(startIndex, items);
      for (final item in items) {
        _requirePlayable(item);
      }
      if (_transport != null && !identical(_transport, transport)) {
        throw StateError('Disconnect the current renderer first');
      }
      _transport = transport;
      _entries = [for (final item in items) _Entry(_nextUid++, item)];
      _index = items.isEmpty ? 0 : startIndex;
      _repeat = repeat;
      _shuffle = shuffle;
      _revision = revision;
      _repeatPlays = 0;
      _adoptOrder(order);
      _setPosition(max(0, startPositionMs));
      _desiredPlay = playWhenReady;
      _seekSupported = true;
      if (_entries.isNotEmpty) await _loadCurrent();
      if (transport.hasVolume) {
        try {
          _volume = await transport.volume() / 100;
        } catch (_) {
          // Volume is optional; playback goes on.
        }
      }
    });
    _startPolling();
    _emit();
  }

  /// Lets go of the renderer. With [stopRemote], Stop must be confirmed first
  /// (throws [DlnaCommandException] otherwise and keeps the renderer).
  Future<void> disconnect({required bool stopRemote}) => _locked(() async {
    final transport = _transport;
    if (stopRemote && transport != null) {
      await stopAndConfirm(transport, sleep: _sleep, nowMs: _now);
    }
    _poll?.cancel();
    _poll = null;
    _transport = null;
    _desiredPlay = false;
    _prepared = false;
    _loading = false;
    _observed = RendererState.stopped;
    _clearFailure();
    _emit();
  });

  /// The renderer could not be stopped after a failed hand-over: keep reading it, show the failure.
  void connectionFailed() {
    _reportFailure();
    _startPolling();
    _emit();
  }

  // ---- queue ----

  Future<void> startQueue(
    List<EngineItem> items, {
    required int revision,
    int startIndex = 0,
    int startPositionMs = 0,
    bool playWhenReady = true,
    EngineRepeat repeat = EngineRepeat.all,
    bool shuffle = false,
  }) {
    for (final item in items) {
      _requirePlayable(item);
    }
    return _command(() async {
      final transport = _requireTransport();
      if (_prepared) await transport.stop();
      _entries = [for (final item in items) _Entry(_nextUid++, item)];
      _index = startIndex >= 0 && startIndex < items.length ? startIndex : 0;
      _repeat = repeat;
      _shuffle = shuffle;
      _revision = revision;
      _repeatPlays = 0;
      _rebuildShuffleOrder();
      _setPosition(max(0, startPositionMs));
      _durationMs = 0;
      _prepared = false;
      _loading = false;
      _observed = RendererState.stopped;
      _clearFailure();
      _desiredPlay = playWhenReady;
      if (_entries.isNotEmpty) await _loadCurrent();
    });
  }

  /// Play next lands right after the current song; anything else, shuffled,
  /// at a random point after it (the native engine's rule).
  Future<void> insert(int at, List<EngineItem> items, {required int revision}) {
    for (final item in items) {
      _requirePlayable(item);
    }
    return _command(() async {
      final position = at.clamp(0, _entries.length);
      final added = [for (final item in items) _Entry(_nextUid++, item)];
      final currentUid = _currentUid;
      final playNext = position == _index + 1;
      _entries = [..._entries]..insertAll(position, added);
      if (position <= _index && _entries.length > items.length) _index += items.length;
      final order = [..._shuffledUids];
      final afterCurrent = order.indexOf(currentUid ?? -1) + 1;
      if (playNext && afterCurrent > 0) {
        order.insertAll(afterCurrent, [for (final e in added) e.uid]);
      } else {
        for (final e in added) {
          final from = max(afterCurrent, 0);
          order.insert(from + _random.nextInt(order.length - from + 1), e.uid);
        }
      }
      _shuffledUids = order;
      _revision = revision;
    });
  }

  Future<void> removeAt(int index, {required int revision}) => _command(() async {
    _revision = revision;
    if (index < 0 || index >= _entries.length) return;
    final currentUid = _currentUid;
    final oldOrder = _shuffle ? _shuffledUids : [for (final e in _entries) e.uid];
    final orderPosition = oldOrder.indexOf(currentUid ?? -1);
    _entries = [..._entries]..removeAt(index);
    final remaining = {for (final e in _entries) e.uid};
    _shuffledUids = [
      for (final uid in _shuffledUids)
        if (remaining.contains(uid)) uid,
    ];
    final preserved = _entries.indexWhere((e) => e.uid == currentUid);
    final successor = oldOrder.skip(orderPosition + 1).where(remaining.contains).firstOrNull;
    final bySuccessor = _entries.indexWhere((e) => e.uid == successor);
    _index = preserved >= 0
        ? preserved
        : bySuccessor >= 0
        ? bySuccessor
        : min(index, max(0, _entries.length - 1));
    if (preserved < 0) {
      _setPosition(0);
      if (_entries.isEmpty) {
        await _transport?.stop();
        _prepared = false;
        _desiredPlay = false;
        _loading = false;
        _observed = RendererState.stopped;
      } else if (_prepared) {
        await _loadCurrent();
      }
    }
  });

  Future<void> move(int from, int to, {required int revision}) => _command(() async {
    _revision = revision;
    if (from < 0 || from >= _entries.length) return;
    final currentUid = _currentUid;
    final entries = [..._entries];
    final moved = entries.removeAt(from);
    entries.insert(to.clamp(0, entries.length), moved);
    _entries = entries;
    _index = max(0, _entries.indexWhere((e) => e.uid == currentUid));
  });

  Future<void> setRepeat(EngineRepeat repeat) => _locked(() async {
    _repeat = repeat;
    _emit();
  });

  Future<void> setShuffle(bool shuffle) => _locked(() async {
    if (shuffle && !_shuffle) _rebuildShuffleOrder();
    _shuffle = shuffle;
    _emit();
  });

  // ---- transport ----

  Future<void> play() => _setPlayWhenReady(true);
  Future<void> pause() => _setPlayWhenReady(false);

  Future<void> seekTo(int positionMs) => _command(() => _seekTo(_index, positionMs));

  /// Queue index [index] from its start (the current one restarts).
  Future<void> skipTo(int index) => _command(() async {
    if (index >= 0 && index < _entries.length) await _seekTo(index, 0);
  });

  /// Next in play order; repeat one and all wrap, repeat off stops at the end.
  Future<void> next() => _command(() async {
    if (_entries.isEmpty) return;
    final target = _neighbour(1);
    if (target >= 0) await _seekTo(target, 0);
  });

  /// Back to the start when more than 3 s in (or nothing before it), else the previous song.
  Future<void> previous() => _command(() async {
    if (_entries.isEmpty) return;
    final target = _neighbour(-1);
    if (target < 0 || target == _index || positionMs > 3000) {
      await _seekTo(_index, 0);
    } else {
      await _seekTo(target, 0);
    }
  });

  /// Stop (confirmed); the queue stays.
  Future<void> stop() => _command(() async {
    final transport = _transport;
    if (transport != null) await stopAndConfirm(transport, sleep: _sleep, nowMs: _now);
    _desiredPlay = false;
    _prepared = false;
    _loading = false;
    _observed = RendererState.stopped;
    _clearFailure();
  });

  /// The renderer's volume, 0–1 (RenderingControl).
  Future<void> setVolume(double volume) => _command(() async {
    final transport = _requireTransport();
    await transport.setVolume((volume.clamp(0.0, 1.0) * 100).round());
    _volume = await transport.volume() / 100;
  });

  /// Reads the renderer now (the poll's step; tests call it directly).
  @visibleForTesting
  Future<void> pollOnce() => _locked(() async {
    if (_transport == null) {
      _poll?.cancel();
      _poll = null;
      return;
    }
    if (!_prepared) return;
    try {
      await _observe();
    } catch (_) {
      _pollFailures++;
      if (_pollFailures >= 3) _reportFailure();
    }
    _emit();
  });

  /// Releasing never issues an unconfirmed remote stop or starts local audio.
  void release() {
    _released = true;
    _poll?.cancel();
    _poll = null;
    _states.close();
  }

  // ---- internals ----

  Future<void> _setPlayWhenReady(bool play) => _command(() async {
    _desiredPlay = play;
    if (_entries.isEmpty) return;
    final transport = _requireTransport();
    if (!_prepared || _failure != null) {
      await _loadCurrent();
    } else {
      play ? await transport.play() : await transport.pause();
      await _observe();
    }
  });

  Future<void> _seekTo(int mediaIndex, int positionMs) async {
    if (mediaIndex < 0 || mediaIndex >= _entries.length) throw RangeError.index(mediaIndex, _entries);
    final target = max(0, positionMs);
    if (mediaIndex != _index || !_prepared) {
      _index = mediaIndex;
      _repeatPlays = 0;
      _setPosition(target);
      await _loadCurrent();
    } else {
      await _seek(target);
      _setPosition(target);
      await _observe();
    }
  }

  Future<void> _loadCurrent() async {
    final transport = _requireTransport();
    if (_index < 0 || _index >= _entries.length) return;
    final item = _entries[_index].item;
    _loading = true;
    _prepared = true;
    _loadStartedMs = _now();
    _observed = RendererState.stopped;
    _durationMs = item.durationMs ?? 0;
    _clearFailure();
    _emit();
    await transport.load(
      RendererMedia(
        id: item.id,
        title: item.title,
        artist: item.artist,
        album: item.album,
        artwork: item.artworkUri,
        uri: item.uri,
        durationMs: (item.durationMs ?? 0) > 0 ? item.durationMs : null,
      ),
    );
    if (_positionMs > 0) await _seek(_positionMs);
    if (_desiredPlay) await transport.play();
    await _observe();
  }

  Future<void> _seek(int target) async {
    try {
      await _requireTransport().seek(target);
    } on DlnaCommandException catch (e) {
      if (e.code == 710 || e.code == 711) _seekSupported = false;
      rethrow;
    }
  }

  Future<void> _observe() async {
    final transport = _transport;
    if (transport == null) return;
    final info = await transport.transportInfo();
    if (info.status == RendererState.errorStatus) throw const DlnaCommandException('Playback');
    final position = await transport.position();
    final expected = _entries.elementAtOrNull(_index)?.item.uri;
    final playing = position.trackUri;
    if (playing != null && playing.isNotEmpty && expected != null && playing != expected) {
      // Someone else took the renderer over.
      _prepared = false;
      _desiredPlay = false;
      _loading = false;
      throw const DlnaCommandException('TakenOver');
    }
    final priorPosition = _positionMs;
    final priorState = _observed;
    final duration = position.durationMs;
    if (duration != null && duration > 0) _durationMs = duration;
    final at = position.positionMs;
    if (at != null) _setPosition(at);
    _observed = info.state;
    if (info.state == RendererState.playing || info.state == RendererState.paused || !_desiredPlay) _loading = false;
    // Play / Pause pressed on the speaker itself: follow it, so a song resumed
    // there still advances at its natural end. Only a seen transition counts.
    if (!_loading && priorState != info.state) {
      if (priorState == RendererState.paused && info.state == RendererState.playing) _desiredPlay = true;
      if (priorState == RendererState.playing && info.state == RendererState.paused) _desiredPlay = false;
    }
    if (_loading && _now() - _loadStartedMs > loadTimeout.inMilliseconds) {
      throw const DlnaCommandException('Load');
    }
    if (remoteQueueEndReached(
      previous: priorState,
      current: info.state,
      oldPositionMs: priorPosition,
      positionMs: _positionMs,
      durationMs: _durationMs,
      wantsPlay: _desiredPlay,
    )) {
      final next = _repeat == EngineRepeat.one ? _index : _neighbour(1);
      if (next >= 0) {
        _repeatPlays = next == _index ? _repeatPlays + 1 : 0;
        _index = next;
        _setPosition(0);
        await _loadCurrent();
      } else {
        _desiredPlay = false;
        _loading = false;
        _setPosition(_durationMs);
      }
    } else if (info.state == RendererState.stopped && !_loading) {
      // An external Stop in the middle of a song is not a request for the next one.
      _desiredPlay = false;
    }
    _pollFailures = 0;
    _clearFailure();
  }

  void _startPolling() {
    final interval = pollInterval;
    if (_poll != null || interval == null || _released) return;
    _poll = Timer.periodic(interval, (_) => pollOnce());
  }

  /// Serialises renderer commands and polls (Android commandsMutex).
  Future<T> _locked<T>(Future<T> Function() op) {
    final result = _lock.then((_) => op());
    _lock = result.then((_) {}, onError: (Object _) {});
    return result;
  }

  /// One user command: a failure shows as the engine's error, never as a throw.
  Future<void> _command(Future<void> Function() op) => _locked(() async {
    try {
      if (_released) throw StateError('released');
      await op();
    } catch (_) {
      _reportFailure();
    } finally {
      _emit();
    }
  });

  void _reportFailure() {
    _loading = false;
    _failure = remoteFailureMessage;
    onFailure?.call(_failure);
  }

  void _clearFailure() {
    _failure = null;
    onFailure?.call(null);
  }

  RendererTransport _requireTransport() {
    final transport = _transport;
    if (transport == null) throw StateError('No renderer');
    return transport;
  }

  void _requirePlayable(EngineItem item) {
    if (!isRemotePlayable(item)) throw ArgumentError('A renderer needs an HTTP stream');
  }

  void _setPosition(int positionMs) {
    _positionMs = positionMs;
    _sampledAtMs = _now();
  }

  int? get _currentUid => _entries.elementAtOrNull(_index)?.uid;

  void _rebuildShuffleOrder() {
    final current = _currentUid;
    final rest = [
      for (final e in _entries)
        if (e.uid != current) e.uid,
    ]..shuffle(_random);
    _shuffledUids = [?current, ...rest];
  }

  /// Keeps the order the phone was playing in (a valid permutation), else shuffles afresh.
  void _adoptOrder(List<int>? order) {
    final n = _entries.length;
    if (order != null && order.length == n && order.toSet().length == n && order.every((i) => i >= 0 && i < n)) {
      _shuffledUids = [for (final i in order) _entries[i].uid];
    } else {
      _rebuildShuffleOrder();
    }
  }

  List<int> _orderIndices() {
    if (!_shuffle) return List<int>.generate(_entries.length, (i) => i);
    final byUid = {for (var i = 0; i < _entries.length; i++) _entries[i].uid: i};
    return [for (final uid in _shuffledUids) ?byUid[uid]];
  }

  /// The queue index [step] away in play order for a skip, or -1 (repeat off at an end).
  int _neighbour(int step) {
    final order = _orderIndices();
    final n = order.length;
    final pos = order.indexOf(_index);
    if (pos < 0 || n == 0) return -1;
    final k = pos + step;
    if (k >= 0 && k < n) return order[k];
    if (_repeat == EngineRepeat.off) return -1;
    return order[(k + n) % n];
  }

  PlaybackPhase get _phase {
    if (_failure != null) return PlaybackPhase.error;
    if (!_prepared || _entries.isEmpty) return PlaybackPhase.idle;
    if (_loading || _observed == RendererState.transitioning) return PlaybackPhase.loading;
    if (_observed == RendererState.noMedia) return PlaybackPhase.ended;
    if (_observed == RendererState.stopped && _durationMs > 0 && _positionMs >= _durationMs - 1000) {
      return PlaybackPhase.ended;
    }
    return PlaybackPhase.ready;
  }

  void _emit() {
    if (_states.isClosed) return;
    final phase = _phase;
    final empty = _entries.isEmpty;
    final entry = empty ? null : _entries[_index.clamp(0, _entries.length - 1)];
    // PLAYING comes only from a renderer observation, never a successful Play alone.
    final playWhenReady = _failure == null && (_observed == RendererState.playing || (_loading && _desiredPlay));
    final isPlaying = phase == PlaybackPhase.ready && _observed == RendererState.playing;
    final order = _orderIndices();
    final key =
        '$phase|$isPlaying|$playWhenReady|$_index|${entry?.uid}|$_repeat|$_shuffle|$_revision|'
        '$_repeatPlays|$order|$_durationMs|$_failure|${_transport != null}';
    final tick = key == _lastKey;
    _lastKey = key;
    final state = EngineState(
      phase: phase,
      isPlaying: isPlaying,
      playWhenReady: playWhenReady,
      index: empty ? -1 : _index,
      currentId: entry?.item.id,
      currentEntryId: entry?.item.entryId,
      positionMs: _positionMs,
      sampledAtMs: _sampledAtMs,
      durationMs: _durationMs > 0 ? _durationMs : null,
      errorMessage: _failure,
      repeat: _repeat,
      shuffle: _shuffle,
      revision: _revision,
      repeatPlays: _repeatPlays,
      order: tick ? null : order,
      tick: tick,
    );
    _latest = state;
    _states.add(state);
  }
}
