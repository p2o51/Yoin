import 'package:flutter/foundation.dart';

import '../data/model/model.dart';
import '../platform/playback/playback_engine.dart';
import 'play_mode.dart';
import 'playback_context.dart';

enum RepeatMode { off, all, one }

/// Why playback stopped with an error; the UI picks the words
/// (PlaybackStrings.errorMessage). The raw engine text is never shown.
enum PlaybackErrorKind {
  /// The engine failed mid-play (np_msg_playback_interrupted).
  interrupted,

  /// The queue could not even be started (player_play_failed).
  playFailed,
}

/// What the app knows about playback (Android PlaybackState, trimmed).
///
/// Position is a *sample*: [positionMs] read at [sampledAtMs] (epoch ms).
/// Widgets that show progress extrapolate with [positionAt] from their own
/// ticker; nothing rebuilds the tree once a frame for the clock (Android
/// keeps positionMs out of NowPlayingUiState for the same reason). The
/// sample here only moves on a discontinuity (track change, play / pause,
/// seek, drift beyond [PlaybackPositionPolicy.snapMs]); the once-a-second
/// engine samples go to `playheadProvider` instead, so a tick never
/// republishes this state.
@immutable
final class NowPlayingState {
  const NowPlayingState({
    this.queue = const [],
    this.index = -1,
    this.phase = PlaybackPhase.idle,
    this.isPlaying = false,
    this.positionMs = 0,
    this.sampledAtMs = 0,
    this.durationMs,
    this.shuffle = false,
    this.repeat = RepeatMode.off,
    this.errorMessage,
    this.context = const NoPlaybackContext(),
    this.playWhenReady = false,
    this.entryIds = const [],
    this.userQueued = const {},
    this.upcoming = const [],
    this.nextIndex = -1,
    this.repeatPlays = 0,
    this.errorKind,
  });

  static const idle = NowPlayingState();

  /// The queue in queue order (what the queue sheet lists). The play order
  /// under shuffle is [upcoming] / [nextIndex].
  final List<Track> queue;

  /// Index of the current track in [queue], -1 when nothing is loaded.
  final int index;
  final PlaybackPhase phase;

  /// Audio is actually coming out (drives listened time and history).
  final bool isPlaying;
  final int positionMs;
  final int sampledAtMs;

  /// The engine's duration when known, else the track's.
  final int? durationMs;
  final bool shuffle;
  final RepeatMode repeat;

  /// Raw diagnostic text for logs (never shown; see [errorKind]).
  final String? errorMessage;

  /// Where the current queue came from.
  final PlaybackContext context;

  /// The user's intent: the play button shows PAUSE while this is true,
  /// through a buffering or seek dip (Android playWhenReadyOf).
  final bool playWhenReady;

  /// One stable id per [queue] entry (the same song queued twice is two entries).
  final List<String> entryIds;

  /// Indices into [queue] added by Play next / Add to queue.
  final Set<int> userQueued;

  /// Queue indices still to play after the current one, in play order,
  /// without wrapping (shuffle included).
  final List<int> upcoming;

  /// The queue index that plays next (shuffle order, repeat-all wrap), -1
  /// at the end of the queue under repeat off or in a queue of one.
  final int nextIndex;

  /// Auto-repeats of the current entry (repeat one).
  final int repeatPlays;
  final PlaybackErrorKind? errorKind;

  Track? get current => index >= 0 && index < queue.length ? queue[index] : null;
  bool get hasQueue => current != null;
  bool get hasNext => nextIndex >= 0 || (queue.length == 1 && repeat != RepeatMode.off);
  Track? get nextTrack => nextIndex >= 0 && nextIndex < queue.length ? queue[nextIndex] : null;
  PlayMode get playMode => PlayMode.of(repeat, shuffle);

  /// The current entry's stable id, null when nothing is loaded.
  String? get currentEntryId => index >= 0 && index < entryIds.length ? entryIds[index] : null;

  int? get effectiveDurationMs {
    final seconds = current?.durationSec;
    return durationMs ?? (seconds == null ? null : seconds * 1000);
  }

  /// Position at [nowMs], extrapolated while playing and clamped to the duration.
  int positionAt(int nowMs) {
    final raw = isPlaying ? positionMs + (nowMs - sampledAtMs) : positionMs;
    final max = effectiveDurationMs;
    final clampedLow = raw < 0 ? 0 : raw;
    return max != null && clampedLow > max ? max : clampedLow;
  }

  NowPlayingState copyWith({
    List<Track>? queue,
    int? index,
    PlaybackPhase? phase,
    bool? isPlaying,
    int? positionMs,
    int? sampledAtMs,
    int? durationMs,
    bool clearDuration = false,
    bool? shuffle,
    RepeatMode? repeat,
    String? errorMessage,
    bool clearError = false,
    PlaybackContext? context,
    bool? playWhenReady,
    List<String>? entryIds,
    Set<int>? userQueued,
    List<int>? upcoming,
    int? nextIndex,
    int? repeatPlays,
    PlaybackErrorKind? errorKind,
  }) =>
      NowPlayingState(
        queue: queue ?? this.queue,
        index: index ?? this.index,
        phase: phase ?? this.phase,
        isPlaying: isPlaying ?? this.isPlaying,
        positionMs: positionMs ?? this.positionMs,
        sampledAtMs: sampledAtMs ?? this.sampledAtMs,
        durationMs: clearDuration ? null : (durationMs ?? this.durationMs),
        shuffle: shuffle ?? this.shuffle,
        repeat: repeat ?? this.repeat,
        errorMessage: clearError ? null : (errorMessage ?? this.errorMessage),
        context: context ?? this.context,
        playWhenReady: playWhenReady ?? this.playWhenReady,
        entryIds: entryIds ?? this.entryIds,
        userQueued: userQueued ?? this.userQueued,
        upcoming: upcoming ?? this.upcoming,
        nextIndex: nextIndex ?? this.nextIndex,
        repeatPlays: repeatPlays ?? this.repeatPlays,
        errorKind: clearError ? null : (errorKind ?? this.errorKind),
      );

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is NowPlayingState &&
          other.index == index &&
          other.phase == phase &&
          other.isPlaying == isPlaying &&
          other.playWhenReady == playWhenReady &&
          other.positionMs == positionMs &&
          other.sampledAtMs == sampledAtMs &&
          other.durationMs == durationMs &&
          other.shuffle == shuffle &&
          other.repeat == repeat &&
          other.errorMessage == errorMessage &&
          other.errorKind == errorKind &&
          identical(other.context, context) &&
          other.nextIndex == nextIndex &&
          other.repeatPlays == repeatPlays &&
          _sameList(other.queue, queue) &&
          _sameList(other.entryIds, entryIds) &&
          _sameList(other.upcoming, upcoming) &&
          setEquals(other.userQueued, userQueued);

  @override
  int get hashCode => Object.hash(index, phase, isPlaying, playWhenReady, positionMs, sampledAtMs, durationMs, shuffle,
      repeat, errorKind, nextIndex, repeatPlays, queue.length, entryIds.length, upcoming.length);
}

bool _sameList<T>(List<T> a, List<T> b) => identical(a, b) || listEquals(a, b);

/// When a fresh engine sample replaces the published one.
abstract final class PlaybackPositionPolicy {
  /// A sample that differs from the extrapolation by more than this snaps
  /// (a seek, a stall, a slow clock); smaller drift is the 1 s tick.
  static const snapMs = 400;
}
