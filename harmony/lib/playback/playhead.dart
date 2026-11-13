import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The latest position sample (about one a second while playing). Separate
/// from NowPlayingState so a tick rebuilds only what shows the time: a
/// progress widget watches this and extrapolates with [positionAt] from its
/// own Ticker; no Dart timer runs for the clock.
@immutable
final class Playhead {
  const Playhead({this.entryId, this.positionMs = 0, this.sampledAtMs = 0, this.isPlaying = false, this.durationMs});

  static const none = Playhead();

  /// The queue entry the sample belongs to (null when nothing is loaded).
  final String? entryId;
  final int positionMs;

  /// Epoch ms when [positionMs] was read.
  final int sampledAtMs;
  final bool isPlaying;
  final int? durationMs;

  /// Position at [nowMs] (epoch ms), clamped to [0, duration].
  int positionAt(int nowMs) {
    final raw = isPlaying ? positionMs + (nowMs - sampledAtMs) : positionMs;
    final low = raw < 0 ? 0 : raw;
    final max = durationMs;
    return max != null && max > 0 && low > max ? max : low;
  }

  @override
  bool operator ==(Object other) =>
      other is Playhead &&
      other.entryId == entryId &&
      other.positionMs == positionMs &&
      other.sampledAtMs == sampledAtMs &&
      other.isPlaying == isPlaying &&
      other.durationMs == durationMs;

  @override
  int get hashCode => Object.hash(entryId, positionMs, sampledAtMs, isPlaying, durationMs);
}

/// Written only by PlaybackController.
class PlayheadNotifier extends Notifier<Playhead> {
  @override
  Playhead build() => Playhead.none;

  // ignore: use_setters_to_change_properties
  void update(Playhead sample) => state = sample;
}

final playheadProvider = NotifierProvider<PlayheadNotifier, Playhead>(PlayheadNotifier.new);

/// Wall and monotonic time for the playback chain; tests override it.
abstract interface class PlaybackClock {
  /// Epoch ms (dates a play, position samples).
  int wallMs();

  /// Monotonic ms (listened time; immune to the user changing the time).
  int monotonicMs();
}

final class SystemPlaybackClock implements PlaybackClock {
  SystemPlaybackClock() : _watch = Stopwatch()..start();
  final Stopwatch _watch;
  @override
  int wallMs() => DateTime.now().millisecondsSinceEpoch;
  @override
  int monotonicMs() => _watch.elapsedMilliseconds;
}

final playbackClockProvider = Provider<PlaybackClock>((ref) => SystemPlaybackClock());
