import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/model/model.dart';
import '../platform/playback/playback_engine.dart';
import '../playback/now_playing_state.dart';
import '../playback/playback_context.dart';
import '../playback/play_mode.dart';
import '../playback/playback_controller.dart';

/// What Now Playing and the bar read from the playback controller, minus the
/// position. This is the ONE place that knows the controller's state shape:
/// if the playback chain changes its fields, only [PlayerSnapshot.of]
/// changes. Positions come from `playheadProvider` through the clock.
///
/// Equality leaves the position out (memory: np_position_dedup_invariant),
/// so a position sample never rebuilds anything that watches the snapshot.
@immutable
final class PlayerSnapshot {
  const PlayerSnapshot({
    this.queue = const [],
    this.index = -1,
    this.phase = PlaybackPhase.idle,
    this.isPlaying = false,
    this.playWhenReady = false,
    this.durationMs,
    this.playMode = PlayMode.repeatAll,
    this.errorKind,
    this.context = const NoPlaybackContext(),
    this.upcoming = const [],
  });

  factory PlayerSnapshot.of(NowPlayingState s) => PlayerSnapshot(
    queue: s.queue,
    index: s.index,
    phase: s.phase,
    isPlaying: s.isPlaying,
    playWhenReady: s.playWhenReady,
    durationMs: s.effectiveDurationMs,
    playMode: s.playMode,
    errorKind: s.errorKind,
    context: s.context,
    upcoming: s.upcoming,
  );

  static const idle = PlayerSnapshot();

  final List<Track> queue;
  final int index;
  final PlaybackPhase phase;
  final bool isPlaying;

  /// What PLAY|PAUSE shows: the intent, held through buffering and seeks.
  final bool playWhenReady;
  final int? durationMs;
  final PlayMode playMode;

  /// Why playback failed, if it did. The UI words it through
  /// PlaybackStrings.errorMessage; the engine's raw text never reaches here.
  final PlaybackErrorKind? errorKind;
  final PlaybackContext context;

  Track? get track => index >= 0 && index < queue.length ? queue[index] : null;
  bool get hasTrack => track != null;

  /// Queue indices still to play after the current one, in play order
  /// (shuffle aware, no wrap).
  final List<int> upcoming;

  @override
  bool operator ==(Object other) =>
      other is PlayerSnapshot &&
      listEquals(other.queue, queue) &&
      other.index == index &&
      other.phase == phase &&
      other.isPlaying == isPlaying &&
      other.playWhenReady == playWhenReady &&
      other.durationMs == durationMs &&
      other.playMode == playMode &&
      other.errorKind == errorKind &&
      identical(other.context, context) &&
      listEquals(other.upcoming, upcoming);

  @override
  int get hashCode => Object.hash(
    Object.hashAll(queue),
    index,
    phase,
    isPlaying,
    playWhenReady,
    durationMs,
    playMode,
    errorKind,
    context,
    Object.hashAll(upcoming),
  );
}

/// The narrow, position-free projection the shell and Now Playing watch.
final playerSnapshotProvider = Provider<PlayerSnapshot>(
  (ref) => PlayerSnapshot.of(ref.watch(playbackControllerProvider)),
);
