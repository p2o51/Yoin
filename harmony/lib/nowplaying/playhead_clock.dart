import 'dart:math' as math;

import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../playback/playhead.dart';

/// The pill wash's wave period and the step it advances by while steady
/// (NowPlayingPill.kt consts; PillTokens are not in tokens.dart yet).
const int waveClockPeriodMs = 3000;

/// The clock notifies at most this often (~30 Hz; spec item 16).
const int playbackClockStepMs = 33;

/// Where the drawn playhead is anchored: [positionMs] of song at [atMs] wall time.
@immutable
final class PlayheadAnchor {
  const PlayheadAnchor({required this.trackKey, required this.positionMs, required this.atMs, required this.isPlaying});

  final String? trackKey;
  final int positionMs;
  final int atMs;
  final bool isPlaying;

  /// Extrapolated at the song's real rate, never more than [playheadMaxLeadMs]
  /// past the anchor: a buffering stall the engine still calls playing
  /// holds the front instead of running it on.
  int positionAt(int nowMs) => isPlaying ? positionMs + (nowMs - atMs).clamp(0, playheadMaxLeadMs) : positionMs;
}

/// The engine's position sample period (PlaybackEngine.ets TICK_MS).
const int engineSampleMs = 1000;

/// How far the drawn front may run past its anchor (PlaybackProgressSmoother
/// MaxLeadMs, "two ticks and a bit"): Android's 600 ms is two 250 ms ticks
/// plus 100 ms; the HarmonyOS engine samples once a second, so two of its
/// ticks plus the same 600 ms of slack keep the front moving between samples.
const int playheadMaxLeadMs = 2 * engineSampleMs + smootherMaxLagMs;

/// How far a new sample may sit behind the drawn front and still be absorbed
/// instead of stepping the playhead back (PlaybackProgressSmoother MaxLeadMs).
const int smootherMaxLagMs = 600;

/// Re-anchors the drawn playhead on a new [sample] (PlaybackProgressSmoother):
/// a sample slightly behind the front (≤ 600 ms) keeps the front, so the
/// wave never steps back on jitter; a seek, a track change, a pause or a
/// bigger gap snaps to the sample.
PlayheadAnchor reanchor(PlayheadAnchor? previous, Playhead sample, int nowMs) {
  final snap = PlayheadAnchor(
    trackKey: sample.entryId,
    positionMs: sample.positionMs,
    atMs: sample.sampledAtMs,
    isPlaying: sample.isPlaying,
  );
  if (previous == null || previous.trackKey != sample.entryId || !sample.isPlaying || !previous.isPlaying) {
    return snap;
  }
  final drawn = previous.positionAt(sample.sampledAtMs);
  final lag = drawn - sample.positionMs;
  if (lag > 0 && lag <= smootherMaxLagMs) {
    return PlayheadAnchor(trackKey: sample.entryId, positionMs: drawn, atMs: sample.sampledAtMs, isPlaying: true);
  }
  return snap;
}

/// The one app-wide playback clock: extrapolated position for every leaf
/// that shows progress (pill wash, wave bar, time labels, lyric index) and
/// the shared wave phase. It ticks (~30 Hz) only while something listens,
/// the song plays and the app is in the foreground; a paused song draws a
/// still frame. Leaves repaint from it (CustomPainter repaint / listeners
/// that rebuild only on a visible change), never the shell.
class PlayheadClock extends ChangeNotifier {
  PlayheadClock({int Function()? now}) : _now = now ?? (() => DateTime.now().millisecondsSinceEpoch);

  final int Function() _now;

  PlayheadAnchor? _anchor;
  Playhead _sample = Playhead.none;
  Ticker? _ticker;
  int _lastNotifyMs = 0;
  bool _foreground = true;
  int _listeners = 0;
  AppLifecycleListener? _lifecycle;

  Playhead get sample => _sample;
  int get nowMs => _now();
  bool get isPlaying => _sample.isPlaying;
  int? get durationMs => _sample.durationMs;

  /// The drawn position now.
  int positionMs([int? nowMs]) {
    final anchor = _anchor;
    if (anchor == null) return 0;
    final raw = anchor.positionAt(nowMs ?? _now());
    final max = _sample.durationMs;
    if (raw < 0) return 0;
    return max != null && max > 0 && raw > max ? max : raw;
  }

  /// position / duration, 0 when the duration is unknown.
  double progress([int? nowMs]) {
    final d = _sample.durationMs;
    if (d == null || d <= 0) return 0;
    return (positionMs(nowMs) / d).clamp(0.0, 1.0);
  }

  /// The pill wave's phase, 2π per [waveClockPeriodMs], shared by every renderer.
  double wavePhase([int? nowMs]) => 2 * math.pi * ((nowMs ?? _now()) % waveClockPeriodMs) / waveClockPeriodMs;

  void update(Playhead sample) {
    final resumed = !_sample.isPlaying && sample.isPlaying;
    _sample = sample;
    _anchor = reanchor(resumed ? null : _anchor, sample, _now());
    _syncTicker();
    notifyListeners();
  }

  /// The Ability went to the background (or came back): stop ticking, and
  /// resume from the next fresh sample rather than a stale extrapolation.
  void setForeground(bool foreground) {
    if (_foreground == foreground) return;
    _foreground = foreground;
    _syncTicker();
  }

  @override
  void addListener(VoidCallback listener) {
    super.addListener(listener);
    _listeners++;
    _lifecycle ??= AppLifecycleListener(
      onStateChange: (state) =>
          setForeground(state == AppLifecycleState.resumed || state == AppLifecycleState.inactive),
    );
    _syncTicker();
  }

  @override
  void removeListener(VoidCallback listener) {
    super.removeListener(listener);
    _listeners = math.max(0, _listeners - 1);
    _syncTicker();
  }

  void _syncTicker() {
    final shouldTick = _listeners > 0 && _foreground && _sample.isPlaying;
    if (shouldTick) {
      _ticker ??= Ticker(_onTick, debugLabel: 'PlayheadClock');
      if (!_ticker!.isActive) _ticker!.start();
    } else if (_ticker?.isActive ?? false) {
      _ticker!.stop();
    }
  }

  void _onTick(Duration _) {
    final now = _now();
    if (now - _lastNotifyMs < playbackClockStepMs) return;
    _lastNotifyMs = now;
    notifyListeners();
  }

  @override
  void dispose() {
    _ticker?.dispose();
    _lifecycle?.dispose();
    super.dispose();
  }
}

final playheadClockProvider = Provider<PlayheadClock>((ref) {
  final clock = PlayheadClock();
  ref.listen(playheadProvider, (_, sample) => clock.update(sample), fireImmediately: true);
  ref.onDispose(clock.dispose);
  return clock;
});

/// m:ss, floored to whole seconds, no hours (3725 s → "62:05"; DurationFormat.kt).
String formatPlaybackTime(int ms) {
  final totalSeconds = math.max(0, ms) ~/ 1000;
  final minutes = totalSeconds ~/ 60;
  final seconds = totalSeconds % 60;
  return '$minutes:${seconds.toString().padLeft(2, '0')}';
}
