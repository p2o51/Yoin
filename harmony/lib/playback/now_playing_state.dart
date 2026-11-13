import '../data/model/model.dart';
import '../platform/playback/playback_engine.dart';

enum RepeatMode { off, all, one }

/// What the app knows about playback (Android PlaybackState, trimmed).
///
/// Position is a *sample*: [positionMs] read at [sampledAtMs] (epoch ms).
/// Widgets that show progress extrapolate with [positionAt] from their own
/// ticker; nothing rebuilds the tree once a frame for the clock
/// (Android keeps positionMs out of NowPlayingUiState for the same reason).
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
  });

  static const idle = NowPlayingState();

  /// The queue in play order (after shuffle).
  final List<Track> queue;

  /// Index of the current track in [queue], -1 when nothing is loaded.
  final int index;
  final PlaybackPhase phase;
  final bool isPlaying;
  final int positionMs;
  final int sampledAtMs;

  /// The engine's duration when known, else the track's.
  final int? durationMs;
  final bool shuffle;
  final RepeatMode repeat;
  final String? errorMessage;

  Track? get current => index >= 0 && index < queue.length ? queue[index] : null;
  bool get hasQueue => current != null;
  bool get hasNext => index + 1 < queue.length || repeat == RepeatMode.all;

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
      );
}
