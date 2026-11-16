import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart' show AppLifecycleListener, AppLifecycleState, WidgetsBinding;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/activity/activity.dart';
import '../data/model/model.dart';
import '../data/source/music_source.dart';
import '../offline/offline_providers.dart';
import '../platform/playback/playback_engine.dart';
import '../platform/storage/stores.dart';
import '../platform/window/window_controls.dart';
import 'now_playing_state.dart';
import 'play_history.dart';
import 'play_mode.dart';
import 'playback_context.dart';
import 'playback_strings.dart';
import 'playhead.dart';
import 'queue_order.dart';
import 'replay_gain.dart';
import 'scrobble.dart';
import 'system_requests.dart';
import 'track_codec.dart';

/// Something the shell's snackbar tells the user (AppChrome): a heart or
/// rating write that did not land, or playback that failed.
sealed class PlaybackNotice {
  const PlaybackNotice();
}

final class FavoriteWriteFailed extends PlaybackNotice {
  const FavoriteWriteFailed(this.trackId, {required this.favorite});
  final MediaId trackId;

  /// The value the write tried to set.
  final bool favorite;
}

final class RatingWriteFailed extends PlaybackNotice {
  const RatingWriteFailed(this.trackId);
  final MediaId trackId;
}

/// Playback stopped on an error ([NowPlayingState.errorKind] went from none
/// to [kind]); raised once per failure, not per state update.
final class PlaybackFailed extends PlaybackNotice {
  const PlaybackFailed(this.kind);
  final PlaybackErrorKind kind;
}

/// Copy already written by the caller (the song sheet's confirmations and
/// failures shown over Now Playing), routed to the shell's snackbar.
final class TextNotice extends PlaybackNotice {
  const TextNotice(this.text);
  final String text;
}

/// One queue entry on the Dart side, parallel to the native queue.
@immutable
final class _Entry {
  const _Entry(this.entryId, this.track, {this.userQueued = false});
  final String entryId;
  final Track track;
  final bool userQueued;
  _Entry withTrack(Track t) => _Entry(entryId, t, userQueued: userQueued);
}

/// The one way the app plays music (Android PlaybackManager). Screens call
/// these methods; nothing else talks to [PlaybackEngine].
///
/// The native engine owns the queue the system controls (lock screen,
/// 播控中心, headset keys), its play order (shuffle) and repeat, so playback
/// and auto-advance outlive the UI; this mirrors it as [Track]s and turns
/// each into an [EngineItem] through the active [MusicSource].
///
/// Besides the UI state it drives, with the UI gone as long as the Dart
/// isolate runs: the activity log (one PLAYED row per started track), play
/// reporting (scrobble, offline queue) and the system heart. It must be
/// built at app start (a root widget listens to [playbackControllerProvider]).
class PlaybackController extends Notifier<NowPlayingState> {
  StreamSubscription<EngineState>? _engineStates;
  StreamSubscription<EngineEvent>? _engineEvents;
  AppLifecycleListener? _lifecycle;
  late ScrobbleReporter _scrobbles;
  final _notices = StreamController<PlaybackNotice>.broadcast();

  List<_Entry> _entries = const [];

  /// Engine play order; null = identity (no order reported yet).
  List<int>? _order;

  /// Dart-assigned queue revision; native echoes it, older events are stale.
  int _revision = 0;

  /// Bumped by every start, skip-to and disconnect; an in-flight start whose
  /// generation moved on aborts silently.
  int _generation = 0;

  /// Remembered across queues, written with every start (not persisted).
  PlayMode _preferredMode = PlayMode.defaultMode;
  String? _activeProfileId;
  String? _queueProfileId;
  MediaId? _lastRecordedTrackId;
  EngineState? _lastEngine;
  Future<void>? _rehydrating;
  final Map<MediaId, int> _ratingWrites = {};
  int _entrySeq = 0;

  /// The app's window is in front (lifecycle resumed / inactive): a notice
  /// can go to the shell's snackbar. Behind the lock screen or 播控中心 it
  /// goes to a system toast instead (Android SessionQuickActions.toast).
  bool _foreground = true;
  final String _entryPrefix = Random().nextInt(1 << 32).toRadixString(36);

  PlaybackEngine get _engine => ref.read(playbackEngineProvider);
  QueuePlaybackEngine? get _queueEngine => switch (_engine) {
        final QueuePlaybackEngine e => e,
        _ => null,
      };
  PlaybackClock get _clock => ref.read(playbackClockProvider);

  /// Heart / rating failures for the UI to toast.
  Stream<PlaybackNotice> get notices => _notices.stream;

  /// Shows [text] on the shell's snackbar (above Now Playing too).
  void showMessage(String text) => _notices.add(TextNotice(text));

  @override
  NowPlayingState build() {
    final clock = _clock;
    KeyValueStore? store;
    try {
      store = ref.read(keyValueStoreProvider);
    } catch (_) {
      store = null; // Tests without storage: the queue lives in memory.
    }
    _scrobbles = ScrobbleReporter(
      tracker: ScrobbleTracker(monotonicMs: clock.monotonicMs, wallMs: clock.wallMs),
      targetFor: _scrobbleTarget,
      store: store,
      log: _log,
    );
    final engine = _engine;
    _engineStates = engine.states.listen(_onEngineState, onError: (Object e) => _log('engine stream: $e'));
    if (engine is QueuePlaybackEngine) {
      _engineEvents = engine.events.listen(_onEngineEvent, onError: (Object e) => _log('engine events: $e'));
      _rehydrating = _rehydrate();
    }
    ref.listen(activeSourceProvider, (_, next) {
      if (next.hasValue) _onSourceChanged();
    }, fireImmediately: true);
    // The heart has one owner (favoriteOverridesProvider): a toggle on an
    // album row, the artist page, Now Playing or the system heart lands here.
    ref.listen(favoriteOverridesProvider, (_, next) => _applyFavoriteOverrides(next));
    // Volume leveling applies at once, to the song playing too (Android reads
    // the setting for every buffer).
    ref.listen(replayGainProvider, (previous, next) {
      if (previous != null && next != null && previous != next) unawaited(_applyReplayGain(next));
    });
    try {
      final now = WidgetsBinding.instance.lifecycleState;
      _foreground = now == null || now == AppLifecycleState.resumed || now == AppLifecycleState.inactive;
      _lifecycle = AppLifecycleListener(onResume: _onResume, onStateChange: _onLifecycle);
    } catch (_) {
      _lifecycle = null; // No binding (plain unit tests).
    }
    ref.onDispose(() {
      _engineStates?.cancel();
      _engineEvents?.cancel();
      _lifecycle?.dispose();
      _scrobbles.dispose();
      _notices.close();
    });
    return NowPlayingState(repeat: _preferredMode.repeat, shuffle: _preferredMode.shuffled);
  }

  // ---- starting playback ----

  /// Replaces the queue with [tracks] and plays from [startIndex] (Android
  /// PlaybackManager.play). [context] is where the play started; it labels
  /// Now Playing and decides what the activity log records. A Play / Shuffle
  /// button passes its own (shuffled) order with [shuffled] true; that never
  /// touches the remembered play mode. Never throws: a failure shows as
  /// [PlaybackErrorKind.playFailed].
  Future<void> playTracks(
    List<Track> tracks, {
    int startIndex = 0,
    PlaybackContext context = const NoPlaybackContext(),
    bool shuffled = false,
    int startPositionMs = 0,
    bool startPaused = false,
  }) async {
    final generation = ++_generation;
    if (tracks.isEmpty) return;
    final start = startIndex.clamp(0, tracks.length - 1);
    try {
      final source = await ref.read(activeSourceProvider.future);
      if (!_current(generation) || source == null) return;
      final profileId = await _readProfileId();
      if (!_current(generation)) return;
      final leveling = await _replayGainSettings();
      if (!_current(generation)) return;
      final built = await _build(source, tracks, leveling);
      if (!_current(generation) || await _readProfileId() != profileId || !_current(generation)) return;
      if (built.isEmpty) return;
      // The tapped entry, by position (the same song may be queued twice).
      var playableStart = built.indexWhere((b) => b.$1 >= start);
      if (playableStart < 0) playableStart = built.length - 1;

      final entries = [for (final b in built) _Entry(_newEntryId(), _withFavoriteOverride(b.$2))];
      final items = [
        for (var i = 0; i < built.length; i++) _withEntry(built[i].$3, entries[i].entryId, context: context),
      ];
      _entries = entries;
      _queueProfileId = profileId;
      _lastRecordedTrackId = null;
      _order = _preferredMode.shuffled ? null : identityOrder(entries.length);
      final revision = ++_revision;
      final now = _clock.wallMs();
      // Optimistic: the tapped song shows at once (no Idle flash), but
      // nothing plays yet: isPlaying, history and scrobbles follow the engine.
      _publish(_derive(
        base: NowPlayingState(
          queue: [for (final e in entries) e.track],
          index: playableStart,
          phase: PlaybackPhase.loading,
          isPlaying: false,
          playWhenReady: !startPaused,
          positionMs: startPositionMs,
          sampledAtMs: now,
          repeat: _preferredMode.repeat,
          shuffle: _preferredMode.shuffled,
          context: context,
        ),
      ));
      ref.read(playheadProvider.notifier).update(Playhead(
            entryId: entries[playableStart].entryId,
            positionMs: startPositionMs,
            sampledAtMs: now,
            durationMs: _durationHint(entries[playableStart].track),
          ));
      final rich = _queueEngine;
      if (rich != null) {
        await rich.startQueue(
          items,
          revision: revision,
          startIndex: playableStart,
          startPositionMs: startPositionMs,
          playWhenReady: !startPaused,
          repeat: _preferredMode.repeat.engine,
          shuffle: _preferredMode.shuffled,
        );
      } else {
        await _engine.setQueue(items,
            startIndex: playableStart, startPositionMs: startPositionMs, playWhenReady: !startPaused);
      }
      _relevelIfChanged(leveling);
      if (shuffled) _log('started a pre-shuffled queue of ${items.length}');
    } catch (e, st) {
      _log('play failed: ${e.runtimeType}', st);
      if (_current(generation)) {
        _publish(state.copyWith(phase: PlaybackPhase.error, isPlaying: false, errorKind: PlaybackErrorKind.playFailed));
      }
    }
  }

  /// A queue of one.
  Future<void> playSingle(Track track, {PlaybackContext context = const NoPlaybackContext()}) =>
      playTracks([track], context: context);

  /// Reads a queue, then plays it (an artist's Play / Shuffle reads every
  /// release first). A newer start cancels it; a failed or empty read starts
  /// nothing.
  Future<void> playWhenLoaded(
    Future<List<Track>> Function() load, {
    PlaybackContext context = const NoPlaybackContext(),
    bool shuffle = false,
  }) async {
    final generation = ++_generation;
    final List<Track> tracks;
    try {
      tracks = await load();
    } catch (e) {
      _log('queue read failed: ${e.runtimeType}');
      return;
    }
    if (!_current(generation) || tracks.isEmpty) return;
    final ordered = shuffle ? ([...tracks]..shuffle()) : tracks;
    await playTracks(ordered, context: context, shuffled: shuffle);
  }

  // ---- transport ----

  /// Resume (PLAY). At the end of the queue the engine restarts from the
  /// first item of the play order; after an error it reloads the current item.
  Future<void> play() async {
    if (state.phase == PlaybackPhase.idle && _entries.isNotEmpty && state.index >= 0) {
      // The native queue is gone (host released): start it again where we were.
      return _restartAt(state.index);
    }
    _publish(state.copyWith(playWhenReady: true));
    return _engine.play();
  }

  Future<void> pause() {
    _publish(state.copyWith(playWhenReady: false));
    return _engine.pause();
  }

  Future<void> togglePlayPause() => state.playWhenReady ? pause() : play();
  Future<void> next() => _engine.next();

  /// Restart the song when more than 3 s in (or nothing before it), else the
  /// previous song in play order (Spotify rule).
  Future<void> previous() => _engine.previous();

  /// Jump to queue index [index] (cancels a queue still being read).
  Future<void> skipTo(int index) {
    if (index < 0 || index >= _entries.length) return Future.value();
    _generation++;
    _lastRecordedTrackId = null;
    return _engine.skipTo(index);
  }

  Future<void> seekTo(int positionMs) {
    final duration = state.effectiveDurationMs;
    final target = positionMs < 0 ? 0 : (duration != null && duration > 0 && positionMs > duration ? duration : positionMs);
    // Show the new position at once; the engine's next sample confirms it.
    final now = _clock.wallMs();
    _publish(state.copyWith(positionMs: target, sampledAtMs: now));
    final head = ref.read(playheadProvider);
    ref.read(playheadProvider.notifier).update(Playhead(
          entryId: head.entryId,
          positionMs: target,
          sampledAtMs: now,
          isPlaying: head.isPlaying,
          durationMs: head.durationMs,
        ));
    return _engine.seekTo(target);
  }

  /// Stops and drops the queue (ends the AVSession and background task).
  Future<void> stop() => clearQueue();

  // ---- play mode ----

  /// Shuffle × repeat (Android's six play modes), done engine-side so the
  /// lock screen, auto-advance and next / previous agree.
  Future<void> setPlayMode({required bool shuffle, required RepeatMode repeat}) =>
      setMode(PlayMode.of(repeat, shuffle));

  Future<void> setMode(PlayMode mode) async {
    final before = PlayMode.of(state.repeat, state.shuffle);
    _preferredMode = mode;
    var next = state.copyWith(repeat: mode.repeat, shuffle: mode.shuffled);
    if (!mode.shuffled) _order = identityOrder(_entries.length);
    _publish(_derive(base: next));
    final rich = _queueEngine;
    if (rich == null || _entries.isEmpty) return;
    try {
      if (before.repeat != mode.repeat) await rich.setRepeat(mode.repeat.engine);
      if (before.shuffled != mode.shuffled) await rich.setShuffle(mode.shuffled);
    } catch (e) {
      _log('play mode not applied: ${e.runtimeType}');
    }
  }

  // ---- queue edits ----

  /// Play next ([next]) inserts after the current entry; Add to queue
  /// inserts after the user-queued run that follows it. With nothing loaded
  /// the tracks become the queue, paused.
  Future<void> addToQueue(List<Track> tracks, {bool next = false}) async {
    if (tracks.isEmpty) return;
    final source = await ref.read(activeSourceProvider.future);
    if (source == null) return;
    final leveling = await _replayGainSettings();
    final built = await _build(source, tracks, leveling);
    if (built.isEmpty) return;
    if (_entries.isEmpty || state.index < 0) {
      await playTracks([for (final b in built) b.$2], startPaused: true);
      return;
    }
    final rich = _queueEngine;
    if (rich == null) {
      _log('queue edits need the v1 engine');
      return;
    }
    final current = state.index;
    var at = current + 1;
    if (!next) {
      while (at < _entries.length && _entries[at].userQueued) {
        at++;
      }
    }
    final added = [for (final b in built) _Entry(_newEntryId(), _withFavoriteOverride(b.$2), userQueued: true)];
    final items = [
      for (var i = 0; i < built.length; i++) _withEntry(built[i].$3, added[i].entryId, context: state.context),
    ];
    _entries = [..._entries]..insertAll(at, added);
    _order = orderAfterInsert(_orderOrIdentity(_entries.length - added.length), at, added.length,
        shuffle: state.shuffle, current: current);
    final revision = ++_revision;
    _publish(_derive(base: state.copyWith(index: current >= at ? current + added.length : current)));
    await rich.insert(at, items, revision: revision);
    _relevelIfChanged(leveling);
  }

  /// Removes queue entry [index]; the current entry can't be removed.
  Future<void> removeQueueItem(int index) async {
    final rich = _queueEngine;
    if (rich == null || index < 0 || index >= _entries.length || index == state.index) return;
    _entries = [..._entries]..removeAt(index);
    _order = orderAfterRemove(_orderOrIdentity(_entries.length + 1), index);
    final revision = ++_revision;
    final current = state.index;
    _publish(_derive(base: state.copyWith(index: index < current ? current - 1 : current)));
    await rich.removeAt(index, revision: revision);
  }

  Future<void> moveQueueItem(int from, int to) async {
    final rich = _queueEngine;
    if (rich == null || from == to || from < 0 || to < 0 || from >= _entries.length || to >= _entries.length) return;
    final entries = [..._entries];
    entries.insert(to, entries.removeAt(from));
    _entries = entries;
    _order = orderAfterMove(_orderOrIdentity(entries.length), from, to, shuffle: state.shuffle);
    final revision = ++_revision;
    _publish(_derive(base: state.copyWith(index: indexAfterMove(state.index, from, to))));
    await rich.move(from, to, revision: revision);
  }

  /// Removes every user-queued entry except the current one.
  Future<void> clearUserQueue() async {
    for (var i = _entries.length - 1; i >= 0; i--) {
      if (_entries[i].userQueued && i != state.index) await removeQueueItem(i);
    }
  }

  /// Empties the player; the bar and Now Playing go idle.
  Future<void> clearQueue() async {
    _generation++;
    _resetQueue();
    _publish(NowPlayingState(repeat: _preferredMode.repeat, shuffle: _preferredMode.shuffled));
    await _engine.stop();
  }

  /// Account switch / sign-out: aborts in-flight starts, stops the engine,
  /// ends the scrobble session.
  Future<void> disconnect() async {
    _generation++;
    _resetQueue();
    _ratingWrites.clear();
    _publish(NowPlayingState(repeat: _preferredMode.repeat, shuffle: _preferredMode.shuffled));
    ref.read(playheadProvider.notifier).update(Playhead.none);
    try {
      await _engine.stop();
    } catch (e) {
      _log('stop failed: ${e.runtimeType}');
    }
  }

  // ---- favourite / rating ----

  /// Hearts / unhearts [id] (default: the current track) through
  /// [favoriteOverridesProvider], the heart's one owner: optimistic on every
  /// queue entry of that song, the system heart and every list showing it,
  /// reverted if the source write fails. A second tap while a write is in
  /// flight is ignored. Returns whether the write landed.
  Future<bool> toggleFavorite({MediaId? id}) => _toggleFavorite(id, fromSystem: false);

  Future<bool> _toggleFavorite(MediaId? id, {required bool fromSystem}) async {
    final target = id ?? state.current?.id;
    if (target == null || target.rawId.trim().isEmpty) return false;
    final overrides = ref.read(favoriteOverridesProvider.notifier);
    if (overrides.isWriting(target)) return false;
    final source = ref.read(activeSourceProvider).value;
    if (source == null || !source.capabilities.contains(Capability.favorites) || source.provider != target.provider) {
      return false;
    }
    final entry = _entries.where((e) => e.track.id == target).firstOrNull;
    if (entry == null) return false;
    final current = overrides.resolve(target, entry.track.isStarred);
    final ok = await overrides.setFavorite(target, target: !current, previous: current);
    if (!ok) {
      _log('favorite write failed');
      if (ref.mounted) _notify(FavoriteWriteFailed(target, favorite: !current), fromSystem: fromSystem);
    }
    return ok;
  }

  /// Sets the current track's rating (0 clears): optimistic, reverted on
  /// failure. The latest of overlapping writes wins.
  Future<bool> setRating(int rating) async {
    final track = state.current;
    if (track == null) return false;
    final source = ref.read(activeSourceProvider).value;
    if (source == null || !source.capabilities.contains(Capability.rating) || source.provider != track.id.provider) {
      return false;
    }
    final value = rating.clamp(0, 5);
    final previous = track.userRating;
    final token = (_ratingWrites[track.id] ?? 0) + 1;
    _ratingWrites[track.id] = token;
    _applyToTrack(track.id, (t) => _withRating(t, value));
    try {
      await source.writeActions.setRating(track.id, value);
      return true;
    } catch (e) {
      _log('rating write failed: ${e.runtimeType}');
      if (_ratingWrites[track.id] == token) {
        _applyToTrack(track.id, (t) => _withRating(t, previous));
        _notices.add(RatingWriteFailed(track.id));
      }
      return false;
    } finally {
      if (_ratingWrites[track.id] == token) _ratingWrites.remove(track.id);
    }
  }

  // ---- engine → state ----

  void _onEngineState(EngineState e) {
    final revision = e.revision;
    if (revision != null && revision < _revision) return; // Describes a queue we already replaced.
    if (revision != null && revision > _revision) _revision = revision;
    if (_entries.isEmpty && e.index >= 0) {
      // The native host holds a queue this isolate never built (UI re-created).
      final rich = _queueEngine;
      if (rich != null) {
        _rehydrating ??= _rehydrate();
        return;
      }
    }
    _lastEngine = e;
    if (e.order != null && e.order!.length == _entries.length) _order = e.order;
    if (e.repeat != null || e.shuffle != null) {
      _preferredMode = PlayMode.of(
        e.repeat == null ? _preferredMode.repeat : RepeatModeEngine.fromEngine(e.repeat!),
        e.shuffle ?? _preferredMode.shuffled,
      );
    }
    var index = e.index < _entries.length ? e.index : -1;
    final entryId = e.currentEntryId;
    if (entryId != null && index >= 0 && _entries[index].entryId != entryId) {
      final byEntry = _entries.indexWhere((x) => x.entryId == entryId);
      if (byEntry >= 0) index = byEntry;
    }
    if (e.phase == PlaybackPhase.idle && e.index < 0) {
      // Engine stopped (system stop, host released): nothing is loaded.
      if (_entries.isNotEmpty) _resetQueue();
      index = -1;
    }
    final track = index >= 0 ? _entries[index].track : null;
    final duration = (e.durationMs != null && e.durationMs! > 0) ? e.durationMs : _durationHint(track);
    final playWhenReady = e.playWhenReady ??
        (e.isPlaying || (e.phase == PlaybackPhase.loading && state.playWhenReady));

    final prev = state;
    final sameEntry = index == prev.index && _entryIdAt(index) == prev.currentEntryId;
    final expected = prev.positionAt(e.sampledAtMs);
    final snap = !sameEntry ||
        prev.isPlaying != e.isPlaying ||
        prev.phase != e.phase ||
        prev.repeatPlays != e.repeatPlays ||
        (e.positionMs - expected).abs() > PlaybackPositionPolicy.snapMs;
    final queueTracks = _entries.isEmpty ? const <Track>[] : prev.queue;

    var next = prev.copyWith(
      queue: queueTracks,
      index: index,
      phase: index < 0 ? PlaybackPhase.idle : e.phase,
      isPlaying: index >= 0 && e.isPlaying,
      playWhenReady: index >= 0 && playWhenReady,
      positionMs: snap ? e.positionMs : prev.positionMs,
      sampledAtMs: snap ? e.sampledAtMs : prev.sampledAtMs,
      durationMs: duration,
      clearDuration: duration == null,
      repeat: _preferredMode.repeat,
      shuffle: _preferredMode.shuffled,
      repeatPlays: e.repeatPlays,
      clearError: e.phase != PlaybackPhase.error,
    );
    if (e.phase == PlaybackPhase.error) {
      next = next.copyWith(errorKind: errorKindOf(e.errorMessage), errorMessage: e.errorMessage);
    }
    if (index < 0) next = next.copyWith(context: const NoPlaybackContext());
    _publish(_derive(base: next));
    ref.read(playheadProvider.notifier).update(Playhead(
          entryId: _entryIdAt(index),
          positionMs: e.positionMs,
          sampledAtMs: e.sampledAtMs,
          isPlaying: e.isPlaying,
          durationMs: duration,
        ));
    _recordPlayIfDue();
    _scrobbles.update(_scrobbleSnapshot());
    if (!e.tick) _syncSessionHeart();
  }

  void _onEngineEvent(EngineEvent event) {
    switch (event) {
      case OpenNowPlayingEvent():
        ref.read(nowPlayingOpenRequestsProvider.notifier).request();
      case ToggleFavoriteCommand(:final id):
        _toggleFavorite(MediaId.tryParse(id) ?? state.current?.id, fromSystem: true).ignore();
      case NetworkAvailableEvent():
        _flushActive();
    }
  }

  /// Rebuilds the queue from the native host (ability / Flutter engine
  /// re-created while the process lived on).
  Future<void> _rehydrate() async {
    final rich = _queueEngine;
    if (rich == null) return;
    try {
      final snapshot = await rich.snapshot();
      if (!ref.mounted || snapshot == null || snapshot.items.isEmpty || _entries.isNotEmpty) return;
      final entries = <_Entry>[];
      for (final item in snapshot.items) {
        final track = TrackCodec.fromItem(item);
        if (track == null) return; // Not a queue this app wrote.
        entries.add(_Entry(item.entryId ?? _newEntryId(), _withFavoriteOverride(track)));
      }
      _entries = entries;
      _revision = snapshot.state.revision ?? _revision;
      _order = snapshot.state.order;
      _queueProfileId = _activeProfileId ?? await _readProfileId();
      final index = snapshot.state.index;
      // Already recorded by the UI that started it.
      if (index >= 0 && index < entries.length) _lastRecordedTrackId = entries[index].track.id;
      final current = index >= 0 && index < snapshot.items.length ? snapshot.items[index] : snapshot.items.first;
      state = state.copyWith(
        queue: [for (final e in entries) e.track],
        index: -1,
        context: PlaybackContextCodec.read(current.extra),
      );
      _onEngineState(snapshot.state);
      unawaited(_relevelStale(snapshot.items));
    } catch (e) {
      _log('rehydrate failed: ${e.runtimeType}');
    } finally {
      _rehydrating = null;
    }
  }

  // ---- activity log ----

  /// One PLAYED row per started track (Home spec §18): when audio plays and
  /// the track differs from the last one recorded. Reset by every start and
  /// skip-to, so repeat-one replays and back-to-back same ids record once.
  void _recordPlayIfDue() {
    final track = state.current;
    if (!state.isPlaying || track == null || track.id == _lastRecordedTrackId) return;
    _lastRecordedTrackId = track.id;
    final event = playedActivity(track, state.context, _clock.wallMs());
    unawaited(_record(event, _queueProfileId));
    unawaited(_recordAlbumPlay(track, _queueProfileId));
  }

  /// One play of the song's album for Memories (Android play_history): the
  /// album's play count, first and latest play. Visits never count.
  Future<void> _recordAlbumPlay(Track track, String? profileId) async {
    final albumId = track.albumId;
    try {
      final store = await ref.read(memoryStoreProvider.future);
      if (store == null || (profileId != null && store.profileId != profileId)) return;
      // The song's own play too (the artist page's Most Played), album or not.
      await store.recordTrackPlay(track, atMs: _clock.wallMs());
      if (albumId == null) return;
      await store.recordAlbumPlay(
        albumId,
        atMs: _clock.wallMs(),
        albumName: track.album,
        artistName: track.artist,
        coverArtKey: track.coverArt?.storageKey,
      );
    } catch (e) {
      _log('album play not recorded: ${e.runtimeType}'); // Never affects playback.
    }
  }

  Future<void> _record(ActivityEvent event, String? profileId) async {
    try {
      final log = await ref.read(activityLogProvider.future);
      if (log == null || (profileId != null && _activeProfileId != null && profileId != _activeProfileId)) return;
      await log.record(event);
    } catch (e) {
      _log('activity not recorded: ${e.runtimeType}'); // Never affects playback.
    }
  }

  // ---- scrobbling ----

  ScrobbleSnapshot? _scrobbleSnapshot() {
    final s = state;
    final track = s.current;
    final entryId = s.currentEntryId;
    final profileId = _activeProfileId;
    if (track == null || entryId == null || profileId == null || profileId != _queueProfileId) return null;
    final source = ref.read(activeSourceProvider).value;
    if (source == null ||
        !source.capabilities.contains(Capability.playReporting) ||
        track.id.provider != source.provider) {
      return null;
    }
    final head = ref.read(playheadProvider);
    return ScrobbleSnapshot(
      profileId: profileId,
      provider: track.id.provider,
      songId: track.id.rawId,
      playKey: '$entryId#${s.repeatPlays}',
      isPlaying: s.isPlaying,
      positionMs: head.positionMs,
      durationMs: s.effectiveDurationMs ?? 0,
    );
  }

  MusicPlayback? _scrobbleTarget(String profileId) {
    if (profileId != _activeProfileId) return null;
    final source = ref.read(activeSourceProvider).value;
    if (source == null || !source.capabilities.contains(Capability.playReporting)) return null;
    return source.playback;
  }

  // ---- account, lifecycle ----

  Future<void> _onSourceChanged() async {
    final profileId = await _readProfileId();
    if (!ref.mounted) return;
    final previous = _activeProfileId;
    _activeProfileId = profileId;
    if (_queueProfileId != null && profileId != _queueProfileId && (_entries.isNotEmpty || previous != null)) {
      // A queue of the outgoing account never plays on the next one.
      _queueProfileId = null;
      await disconnect();
    }
    if (profileId != null) _scrobbles.flush(profileId).ignore();
  }

  void _onResume() => _flushActive();

  /// Sends the active account's queued plays (resume, network back).
  void _flushActive() {
    final profileId = _activeProfileId;
    if (profileId != null) _scrobbles.flush(profileId).ignore();
  }

  void _onLifecycle(AppLifecycleState next) =>
      _foreground = next == AppLifecycleState.resumed || next == AppLifecycleState.inactive;

  /// Whether the window counts as in front (tests stand in for the lifecycle).
  @visibleForTesting
  set debugForeground(bool value) => _foreground = value;

  /// A failure the user should hear about. From the system card with the app
  /// behind it, the shell's snackbar would wait unseen until the next visit:
  /// a system toast says it now instead (Android shows a Toast).
  void _notify(PlaybackNotice notice, {bool fromSystem = false}) {
    if (!fromSystem || _foreground) {
      _notices.add(notice);
      return;
    }
    final text = _systemStrings().noticeMessage(notice);
    if (text == null) return;
    try {
      ref.read(windowControlsProvider).showToast(text).catchError((Object e) => _log('toast failed: ${e.runtimeType}'));
    } catch (e) {
      _log('toast failed: ${e.runtimeType}');
    }
  }

  PlaybackStrings _systemStrings() {
    try {
      return WidgetsBinding.instance.platformDispatcher.locale.languageCode == 'zh'
          ? PlaybackStrings.zhHans
          : PlaybackStrings.en;
    } catch (_) {
      return PlaybackStrings.en;
    }
  }

  Future<String?> _readProfileId() async {
    try {
      return (await ref.read(profileRepositoryProvider).activeProfile())?.id;
    } catch (_) {
      return null;
    }
  }

  // ---- helpers ----

  bool _current(int generation) => ref.mounted && generation == _generation;

  /// (original index, track, item) for every track the engine can play,
  /// leveled by [leveling].
  Future<List<(int, Track, EngineItem)>> _build(
    MusicSource source,
    List<Track> tracks,
    ReplayGainSettings leveling,
  ) async {
    final items = await Future.wait([for (final t in tracks) _itemFor(source, t, leveling)]);
    return [
      for (var i = 0; i < tracks.length; i++)
        if (items[i] case final item?) (i, tracks[i], item),
    ];
  }

  Future<EngineItem?> _itemFor(MusicSource source, Track track, ReplayGainSettings leveling) async {
    final handle = await source.playback.handleFor(track);
    if (handle is! DirectStream) return null; // External controllers arrive with Spotify.
    final cover = track.coverArt;
    return EngineItem(
      id: track.id.toString(),
      uri: handle.uri,
      headers: handle.headers,
      // A downloaded song plays from its file, offline and at the quality it
      // was kept at, whatever the network would stream (OfflineFirstDataSource);
      // the stream above is what plays if the download is removed meanwhile.
      localUri: offlinePlayableUri(ref, source, track.id),
      title: track.title ?? '',
      artist: track.artist,
      album: track.album,
      // The lock screen's size (AVSession wants a light image).
      artworkUri: cover == null ? null : source.resolveCoverUrl(cover, size: lockScreenArtworkPx),
      durationMs: _durationHint(track),
      extra: TrackCodec.encode(track),
      gain: engineGain(track.replayGain, leveling),
    );
  }

  /// The volume-leveling choice; the default when it can't be read.
  Future<ReplayGainSettings> _replayGainSettings() async {
    try {
      return await ref.read(replayGainProvider.notifier).ready();
    } catch (_) {
      return const ReplayGainSettings();
    }
  }

  /// The choice changed while a queue was built with [used]: the change
  /// re-leveled the queue before the new items reached the engine, so level
  /// them again.
  void _relevelIfChanged(ReplayGainSettings used) {
    final now = ref.read(replayGainProvider);
    if (now != null && now != used) unawaited(_applyReplayGain(now));
  }

  /// A rehydrated queue keeps the gains the native host holds; re-levels it
  /// when they are not what the current choice gives (a choice that could not
  /// be stored, a change while the UI was re-created).
  Future<void> _relevelStale(List<EngineItem> items) async {
    final settings = await _replayGainSettings();
    if (!ref.mounted || _entries.isEmpty) return;
    final held = {for (final i in items) ?i.entryId: i.gain};
    final stale = _entries.any((e) {
      final gain = held[e.entryId];
      return gain != null && (gain - engineGain(e.track.replayGain, settings)).abs() > 1e-6;
    });
    if (stale) await _applyReplayGain(settings);
  }

  /// Tells the engine every entry's gain under [settings].
  Future<void> _applyReplayGain(ReplayGainSettings settings) async {
    final rich = _queueEngine;
    if (rich == null || _entries.isEmpty) return;
    final gains = {for (final e in _entries) e.entryId: engineGain(e.track.replayGain, settings)};
    _log('volume leveling ${settings.mode.key}: ${gains.length} entries');
    try {
      await rich.setGains(gains);
    } catch (e) {
      _log('gains not applied: ${e.runtimeType}');
    }
  }

  /// The cover size handed to the system (AVSession / lock screen).
  static const lockScreenArtworkPx = 512;

  /// [item] keyed to [entryId], carrying [context] in its extra so a
  /// rehydrated queue keeps its PLAYING FROM label.
  EngineItem _withEntry(EngineItem item, String entryId, {required PlaybackContext context}) => EngineItem(
        id: item.id,
        uri: item.uri,
        headers: item.headers,
        localUri: item.localUri,
        title: item.title,
        artist: item.artist,
        album: item.album,
        artworkUri: item.artworkUri,
        durationMs: item.durationMs,
        entryId: entryId,
        extra: PlaybackContextCodec.attach(item.extra, context),
        gain: item.gain,
      );

  String _newEntryId() => '$_entryPrefix-${_entrySeq++}';

  String? _entryIdAt(int index) => index >= 0 && index < _entries.length ? _entries[index].entryId : null;

  List<int> _orderOrIdentity(int length) {
    final order = _order;
    return order != null && order.length == length ? order : identityOrder(length);
  }

  /// Fills the queue-derived fields of [base] from [_entries] and [_order].
  NowPlayingState _derive({required NowPlayingState base}) {
    final order = _orderOrIdentity(_entries.length);
    final index = base.index;
    final sameQueue = base.queue.length == _entries.length &&
        base.entryIds.length == _entries.length &&
        _sameEntries(base);
    return base.copyWith(
      queue: sameQueue ? base.queue : [for (final e in _entries) e.track],
      entryIds: sameQueue ? base.entryIds : [for (final e in _entries) e.entryId],
      userQueued: {
        for (var i = 0; i < _entries.length; i++)
          if (_entries[i].userQueued) i,
      },
      upcoming: index < 0 ? const [] : upcomingOf(order, index),
      nextIndex: index < 0 ? -1 : nextIndexOf(order, index, base.repeat),
    );
  }

  bool _sameEntries(NowPlayingState s) {
    for (var i = 0; i < _entries.length; i++) {
      if (!identical(s.queue[i], _entries[i].track) || s.entryIds[i] != _entries[i].entryId) return false;
    }
    return true;
  }

  void _publish(NowPlayingState next) {
    if (!ref.mounted) return;
    var candidate = next;
    // Keep list instances stable when their contents did not change.
    if (listEquals(candidate.upcoming, state.upcoming)) candidate = candidate.copyWith(upcoming: state.upcoming);
    final failed = candidate.errorKind;
    final wasFailing = state.errorKind != null;
    if (candidate != state) state = candidate;
    if (failed != null && !wasFailing) _notices.add(PlaybackFailed(failed));
  }

  Track _withFavoriteOverride(Track track) {
    final starred = ref.read(favoriteOverridesProvider)[track.id];
    return starred == null || starred == track.isStarred ? track : track.copyWith(isStarred: starred);
  }

  /// Mirrors heart overrides onto queued tracks and the system heart.
  void _applyFavoriteOverrides(Map<MediaId, bool> overrides) {
    if (_entries.isEmpty || overrides.isEmpty) return;
    var changed = false;
    final entries = <_Entry>[];
    for (final e in _entries) {
      final starred = overrides[e.track.id];
      if (starred != null && starred != e.track.isStarred) {
        changed = true;
        entries.add(e.withTrack(e.track.copyWith(isStarred: starred)));
      } else {
        entries.add(e);
      }
    }
    if (!changed) return;
    _entries = entries;
    _publish(_derive(base: state));
    _syncSessionHeart();
  }

  void _applyToTrack(MediaId id, Track Function(Track) change) {
    if (!_entries.any((e) => e.track.id == id)) return;
    _entries = [for (final e in _entries) e.track.id == id ? e.withTrack(change(e.track)) : e];
    _publish(_derive(base: state));
  }

  /// Mirrors the current track's heart onto the system session; hides it
  /// when the source can't favourite.
  void _syncSessionHeart() {
    final rich = _queueEngine;
    final track = state.current;
    if (rich == null || track == null) return;
    final source = ref.read(activeSourceProvider).value;
    final supported = source != null &&
        source.capabilities.contains(Capability.favorites) &&
        source.provider == track.id.provider &&
        track.id.rawId.trim().isNotEmpty;
    final key = '${track.id}|${supported ? track.isStarred : null}';
    if (key == _sessionHeartKey) return;
    _sessionHeartKey = key;
    rich.setFavorite(track.id.toString(), supported ? track.isStarred : null).catchError((Object e) {
      _log('session heart not set: ${e.runtimeType}');
    });
  }

  String? _sessionHeartKey;

  Future<void> _restartAt(int index) async {
    final tracks = [for (final e in _entries) e.track];
    await playTracks(tracks, startIndex: index, context: state.context);
  }

  void _resetQueue() {
    _entries = const [];
    _order = null;
    _revision++;
    _queueProfileId = null;
    _lastRecordedTrackId = null;
    _lastEngine = null;
    _sessionHeartKey = null;
    _scrobbles.update(null);
  }

  int? _durationHint(Track? track) {
    final sec = track?.durationSec;
    return sec == null || sec <= 0 ? null : sec * 1000;
  }

  /// The engine state last applied (diagnostics, tests).
  @visibleForTesting
  EngineState? get lastEngineState => _lastEngine;

  void _log(String message, [StackTrace? st]) {
    // Never log URLs: they carry the account's token and salt.
    debugPrint('YoinPlayback: $message');
  }
}

Track _withRating(Track t, int? rating) =>
    rating == null || rating <= 0 ? t.copyWith(clearUserRating: true) : t.copyWith(userRating: rating);

/// What a mid-play engine failure means to the user. The Apple Music player tags Apple's
/// content-restriction refusal (failureType 3082) so the message can say what to change.
PlaybackErrorKind errorKindOf(String? engineMessage) =>
    engineMessage != null && engineMessage.contains(appleMusicContentRestrictedTag)
        ? PlaybackErrorKind.appleMusicContentRestricted
        : PlaybackErrorKind.interrupted;

/// The tag the HarmonyOS Apple Music player puts in its error text for failureType 3082.
const appleMusicContentRestrictedTag = 'apple_music_content_restricted';

final playbackControllerProvider = NotifierProvider<PlaybackController, NowPlayingState>(PlaybackController.new);
