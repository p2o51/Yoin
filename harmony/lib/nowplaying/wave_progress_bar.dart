import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import 'haptics.dart';
import 'nowplaying_strings.dart';
import 'playhead_clock.dart';

/// WaveProgressBar.kt numbers and the M3 Expressive tokens it inherits
/// (WaveBarTokens / M3E indicator tokens are not in tokens.dart yet).
abstract final class WaveBarMetrics {
  /// The touch box (`Box.height(40.dp)`).
  static const double touchHeight = 40;

  /// The indicator, aligned to the bottom of the touch box (`SeekTouchHeight`).
  static const double indicatorHeight = 18;

  /// `WavyProgressIndicatorDefaults.linearIndicatorStroke` (M3
  /// LinearProgressIndicatorTokens.ActiveThickness = 4 dp) × 0.92; the
  /// indicator has a round cap, the track the same width with a butt cap.
  static const double stroke = 4 * 0.92;

  /// `gapSize = 1.dp`, `stopSize = 0.dp` (no stop indicator).
  static const double gap = 1;

  /// `SeekWaveLength`.
  static const double wavelength = 24;

  /// `SeekWaveAmplitudeScale`: the amplitude fraction handed to M3E while
  /// playing (0 paused). M3E's fraction 1 is a wave whose crests reach the
  /// top and bottom of the indicator less the stroke, so the crest sits
  /// 0.28 × (18 − 3.68) / 2 ≈ 2 dp off the centre line.
  static const double amplitudeFraction = 0.28;

  /// `waveSpeed = SeekWaveLength`: one wavelength per second while playing.
  static const double speed = 24;

  /// The track's alpha (WaveProgressBar.kt: 0.14, 0.20 once a buffered value
  /// runs ahead; the HarmonyOS engine reports none). Android's role is
  /// surfaceContainerHighest; here it is onSurfaceVariant at the same alpha
  /// (see [waveTrackColor]).
  static const double trackAlpha = 0.14;

  /// The preview bubble stays this long after the finger lifts.
  static const Duration bubbleLinger = Duration(milliseconds: 650);

  /// M3E's amplitude easing (Increasing / DecreasingAmplitudeAnimationSpec):
  /// MotionTokens.DurationLong2 with EasingStandard on the way up and
  /// EasingEmphasizedAccelerate on the way down. M3E's own tween, not a
  /// Yoin surface motion, so it is ported as the tween it is.
  static const Duration amplitudeDuration = Duration(milliseconds: 500);
  static const Curve amplitudeIncreasing = Cubic(0.2, 0, 0, 1);
  static const Curve amplitudeDecreasing = Cubic(0.3, 0, 0.8, 0.15);
}

/// The track behind the head. Android tints surfaceContainerHighest at 14 %,
/// which is lighter than the light room behind the transport (and the
/// same tone as the dark one), so it vanishes on the aurora. The ink role
/// (onSurfaceVariant) at the same 14 % reads as a faint groove on either
/// room — the quiet track Android intends, actually visible.
Color waveTrackColor(ColorScheme colors) => colors.onSurfaceVariant.withValues(alpha: WaveBarMetrics.trackAlpha);

/// A horizontal run of the indicator or the track, in the painter's x.
@immutable
final class WaveSpan {
  const WaveSpan(this.start, this.end);

  final double start;
  final double end;

  @override
  bool operator ==(Object other) => other is WaveSpan && other.start == start && other.end == end;

  @override
  int get hashCode => Object.hash(start, end);

  @override
  String toString() => 'WaveSpan($start, $end)';
}

/// The geometry of M3E's determinate LinearWavyProgressIndicator
/// (LinearWavyProgressModifiers.kt, LinearProgressDrawingCache), for one
/// progress pair [0, fraction]. Pure, so it can be tested.
abstract final class WaveGeometry {
  /// `currentStrokeCapWidth`: half the widest stroke while either stroke is
  /// round and the bar is wider than tall, else 0. Both ends of the bar are
  /// inset by it so the round cap is never cut.
  static double capWidth(
    Size size, {
    double stroke = WaveBarMetrics.stroke,
    double trackStroke = WaveBarMetrics.stroke,
  }) => size.height > size.width ? 0 : math.max(stroke / 2, trackStroke / 2);

  /// The active indicator's path, cap to head (its round caps reach `cap`
  /// further on each side). Null when nothing is drawn: at 0 %, and while
  /// the head is still inside the start cap (a zero-length segment).
  static WaveSpan? indicator(double width, double fraction, double cap) {
    if (width < 2 * cap) return null;
    final barHead = fraction.clamp(0.0, 1.0) * width;
    if (barHead <= 0) return null;
    final head = barHead.clamp(cap, width - cap);
    if (head <= cap) return null;
    return WaveSpan(cap, head);
  }

  /// The track after the head (butt cap). It ends `cap` short of the right
  /// edge; the gap shrinks while the head is within `gap` of the start cap,
  /// and once the indicator is visible it also clears the indicator's round
  /// cap and the track's own half stroke (`adjustedTrackGapSize + 2 × cap`).
  static WaveSpan? track(double width, double fraction, double cap, {double gap = WaveBarMetrics.gap}) {
    if (width < 2 * cap) return null;
    final barHead = fraction.clamp(0.0, 1.0) * width;
    final adjustedGap = barHead < cap ? 0.0 : math.min(barHead - cap, gap);
    final indicatorVisible = barHead >= cap;
    final head = barHead.clamp(cap, width - cap);
    final spacing = indicatorVisible ? adjustedGap + cap * 2 : adjustedGap;
    final end = width - cap;
    if (end <= head + spacing) return null;
    return WaveSpan(math.max(cap, head + spacing), end);
  }

  /// M3E's full-amplitude wave at path x [u] (0 = the bar's left edge),
  /// relative to the centre line, +y down: quadratic half-waves of length
  /// λ/2 whose control points alternate ±(height − stroke), the first one
  /// down. A quadratic with its control mid-way peaks at half the control
  /// height, so the crests reach ±(height − stroke)/2.
  static double fullWave(
    double u, {
    required double height,
    double stroke = WaveBarMetrics.stroke,
    double wavelength = WaveBarMetrics.wavelength,
  }) {
    final half = wavelength / 2;
    final k = (u / half).floor();
    final t = u / half - k;
    final control = height - stroke;
    return (k.isEven ? 1 : -1) * 2 * t * (1 - t) * control;
  }

  /// What the head shows (WaveProgressBar.kt `indicatorProgress`): the
  /// settled progress while paused (even under a finger, where only the
  /// bubble moves), else the finger's fraction while one is down or
  /// lingering, else the clock.
  static double headFraction({required bool isPlaying, required double? preview, required double settled}) {
    if (!isPlaying) return settled;
    return preview ?? settled;
  }

  /// The wave's horizontal shift at [nowMs]: M3E's `waveOffset × wavelength`,
  /// one wavelength per (wavelength / speed) seconds, 0 when it is not
  /// travelling. The drawn wave is `fullWave(x + shift)`, so it runs toward
  /// the start of the bar.
  static double shiftAt(
    int nowMs, {
    double wavelength = WaveBarMetrics.wavelength,
    double speed = WaveBarMetrics.speed,
  }) {
    if (speed <= 0 || wavelength <= 0) return 0;
    final periodMs = math.max(50, (wavelength / speed * 1000).round());
    return (nowMs % periodMs) / periodMs * wavelength;
  }

  /// Appends the indicator from [span.start] to [span.end] at [amplitude]
  /// (0..1 of the full wave) to [path], as exact quadratic pieces of the
  /// full wave (each piece of a quadratic is itself a quadratic).
  static void addWave(
    Path path,
    WaveSpan span, {
    required double height,
    required double amplitude,
    required double shift,
    double stroke = WaveBarMetrics.stroke,
    double wavelength = WaveBarMetrics.wavelength,
  }) {
    final mid = height / 2;
    double y(double u) => mid + amplitude * fullWave(u, height: height, stroke: stroke, wavelength: wavelength);
    final u0 = span.start + shift;
    final u1 = span.end + shift;
    path.moveTo(span.start, y(u0));
    if (amplitude == 0) {
      path.lineTo(span.end, mid);
      return;
    }
    final half = wavelength / 2;
    final control = height - stroke;
    var a = u0;
    while (a < u1) {
      final k = (a / half).floor();
      final b = math.min(u1, (k + 1) * half);
      final t0 = a / half - k;
      final t1 = b / half - k;
      final sign = k.isEven ? 1.0 : -1.0;
      // y(t) = 2t(1−t)C, y'(t) = 2C(1−2t); the sub-curve's control point is
      // y(t0) + (t1 − t0)·y'(t0)/2, mid-way in x (x is linear in t).
      final y0 = sign * 2 * t0 * (1 - t0) * control;
      final ctrl = y0 + (t1 - t0) * sign * control * (1 - 2 * t0);
      path.quadraticBezierTo((a + b) / 2 - shift, mid + amplitude * ctrl, b - shift, y(b));
      a = b;
    }
  }
}

/// Song-moment note anchors on the bar (WaveProgressBar noteAnchorsMs):
/// fractions of the song, sorted.
List<double> noteAnchorFractions(List<int> anchorsMs, int durationMs) {
  if (durationMs <= 0) return const [];
  return [for (final ms in anchorsMs) (ms / durationMs).clamp(0.0, 1.0)]..sort();
}

/// "On a note": ±1.5 s of song, floored at 1 % of the bar.
double noteWindowFraction(int durationMs) => durationMs <= 0 ? 0 : math.max(1500 / durationMs, 0.01);

/// Magnetic snapping: inside the inner half of the window the thumb sticks
/// to the anchor; over the outer half it eases back to the finger on a t²
/// ramp that reaches the window edge exactly (a notch, not a wall).
double magnetizeToNotes(double raw, List<double> anchors, double window) {
  if (anchors.isEmpty || window <= 0) return raw;
  var nearest = anchors.first;
  for (final a in anchors) {
    if ((a - raw).abs() < (nearest - raw).abs()) nearest = a;
  }
  final d = raw - nearest;
  final ad = d.abs();
  final inner = window * 0.5;
  if (ad >= window) return raw;
  if (ad <= inner) return nearest;
  final t = (ad - inner) / (window - inner);
  return (nearest + d.sign * window * t * t).clamp(0.0, 1.0);
}

/// The M3 Expressive wavy linear indicator with scrubbing (WaveProgressBar.kt
/// over LinearWavyProgressIndicator). The head reads the shared
/// [PlayheadClock] at paint time; only this painter repaints per tick. Press
/// shows a preview bubble; a tap seeks on release, a horizontal drag seeks
/// on drag end (the song keeps playing).
class WaveProgressBar extends StatefulWidget {
  const WaveProgressBar({
    super.key,
    required this.clock,
    required this.isPlaying,
    required this.durationMs,
    required this.onSeek,
    this.visible = true,
    this.noteAnchorsMs = const [],
  });

  /// Song-moment notes: scrubbing across one ticks, the bubble reads "Note".
  final List<int> noteAnchorsMs;

  final PlayheadClock clock;
  final bool isPlaying;
  final int? durationMs;

  /// Seek to a fraction of the song.
  final ValueChanged<double> onSeek;

  /// False while the controls slot is folded away (Expanded stage): the wave
  /// stops travelling and the painter stops following the clock.
  final bool visible;

  @override
  State<WaveProgressBar> createState() => _WaveProgressBarState();
}

class _WaveProgressBarState extends State<WaveProgressBar> with SingleTickerProviderStateMixin {
  /// M3E's amplitude tween (0 ⇄ [WaveBarMetrics.amplitudeFraction], 500 ms).
  late final _Amplitude _amplitude = _Amplitude(
    vsync: this,
    initial: widget.isPlaying ? WaveBarMetrics.amplitudeFraction : 0,
  );

  /// The finger's fraction while pressed / dragged, and through the linger.
  final ValueNotifier<double?> _preview = ValueNotifier(null);
  Timer? _linger;
  bool _dragging = false;

  /// The finger's own fraction; [_preview] is its magnetized projection.
  double _raw = 0;
  double? _snapped;

  List<double> get _anchors => noteAnchorFractions(widget.noteAnchorsMs, widget.durationMs ?? 0);
  double get _window => noteWindowFraction(widget.durationMs ?? 0);

  void _drag(double raw) {
    final anchors = _anchors;
    final window = _window;
    final before = _raw;
    _raw = raw;
    _preview.value = magnetizeToNotes(raw, anchors, window);
    if (anchors.isEmpty) return;
    // A light tick the moment a note captures the thumb…
    double? captured;
    for (final a in anchors) {
      if ((a - raw).abs() <= window * 0.5) captured = a;
    }
    if (captured != null && captured != _snapped) YoinHaptics.lightTick();
    _snapped = captured;
    // …and a full tick per anchor the finger sweeps across.
    final lo = math.min(before, raw);
    final hi = math.max(before, raw);
    if (before != raw && anchors.any((a) => a > lo && a <= hi)) YoinHaptics.tick();
  }

  @override
  void didUpdateWidget(WaveProgressBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    // A step on play state only (seekWaveAmplitude): never on visibility or
    // progress, so a revealed bar shows the wave it had.
    _amplitude.animateTo(
      widget.isPlaying ? WaveBarMetrics.amplitudeFraction : 0,
      instant: MediaQuery.disableAnimationsOf(context),
    );
  }

  @override
  void dispose() {
    _linger?.cancel();
    _amplitude.dispose();
    _preview.dispose();
    super.dispose();
  }

  bool get _canSeek => (widget.durationMs ?? 0) > 0;

  double _fractionAt(Offset local, double width) => width <= 0 ? 0 : (local.dx / width).clamp(0.0, 1.0);

  void _press(double fraction) {
    _linger?.cancel();
    _preview.value = fraction;
  }

  void _release({required bool tap}) {
    final fraction = _preview.value;
    if (fraction != null && _canSeek) {
      tap ? YoinHaptics.lightTick() : YoinHaptics.tick();
      widget.onSeek(fraction);
    }
    _scheduleLinger();
  }

  void _scheduleLinger() {
    _linger?.cancel();
    _linger = Timer(WaveBarMetrics.bubbleLinger, () {
      if (mounted && !_dragging) _preview.value = null;
    });
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final duration = widget.durationMs ?? 0;
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        return _SliderSemantics(
          clock: widget.clock,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapDown: _canSeek ? (d) => _press(_fractionAt(d.localPosition, width)) : null,
            onTapUp: _canSeek ? (_) => _release(tap: true) : null,
            // A press that turns into another gesture clears at once (the
            // drag that wins next sets it again in the same event).
            onTapCancel: _canSeek
                ? () {
                    if (!_dragging) _preview.value = null;
                  }
                : null,
            onHorizontalDragStart: _canSeek
                ? (d) {
                    _dragging = true;
                    _raw = _fractionAt(d.localPosition, width);
                    _snapped = null;
                    _press(magnetizeToNotes(_raw, _anchors, _window));
                  }
                : null,
            onHorizontalDragUpdate: _canSeek ? (d) => _drag(_fractionAt(d.localPosition, width)) : null,
            onHorizontalDragEnd: _canSeek
                ? (_) {
                    _dragging = false;
                    _release(tap: false);
                  }
                : null,
            onHorizontalDragCancel: _canSeek
                ? () {
                    _dragging = false;
                    _preview.value = null;
                  }
                : null,
            child: SizedBox(
              height: WaveBarMetrics.touchHeight,
              child: Stack(
                clipBehavior: Clip.none,
                children: [
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: 0,
                    height: WaveBarMetrics.indicatorHeight,
                    child: RepaintBoundary(
                      child: CustomPaint(
                        painter: _WaveIndicatorPainter(
                          clock: widget.clock,
                          amplitude: _amplitude,
                          preview: _preview,
                          isPlaying: widget.isPlaying,
                          visible: widget.visible,
                          progressColor: colors.primary,
                          trackColor: waveTrackColor(colors),
                        ),
                      ),
                    ),
                  ),
                  ValueListenableBuilder<double?>(
                    valueListenable: _preview,
                    builder: (context, fraction, _) => fraction == null
                        ? const SizedBox.shrink()
                        : _PreviewBubble(
                            fraction: fraction,
                            width: width,
                            durationMs: duration,
                            onNote: _anchors.any((a) => (fraction - a).abs() <= _window),
                          ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

/// The slider's semantics value: the playhead's second, kept current as the
/// clock moves (playback, a seek from the keyboard's ← / →), rebuilding only
/// when the second changes.
class _SliderSemantics extends StatefulWidget {
  const _SliderSemantics({required this.clock, required this.child});

  final PlayheadClock clock;
  final Widget child;

  @override
  State<_SliderSemantics> createState() => _SliderSemanticsState();
}

class _SliderSemanticsState extends State<_SliderSemantics> {
  late int _second = widget.clock.positionMs() ~/ 1000;

  @override
  void initState() {
    super.initState();
    widget.clock.addListener(_onTick);
  }

  @override
  void didUpdateWidget(_SliderSemantics oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.clock != widget.clock) {
      oldWidget.clock.removeListener(_onTick);
      widget.clock.addListener(_onTick);
      _onTick();
    }
  }

  @override
  void dispose() {
    widget.clock.removeListener(_onTick);
    super.dispose();
  }

  void _onTick() {
    final second = widget.clock.positionMs() ~/ 1000;
    if (second != _second && mounted) setState(() => _second = second);
  }

  @override
  Widget build(BuildContext context) =>
      Semantics(slider: true, value: formatPlaybackTime(_second * 1000), child: widget.child);
}

/// M3E's amplitude Animatable: a 500 ms tween from wherever it is, eased
/// with EasingStandard up and EasingEmphasizedAccelerate down.
class _Amplitude {
  _Amplitude({required TickerProvider vsync, required double initial})
    : _from = initial,
      _to = initial,
      _controller = AnimationController(vsync: vsync, duration: WaveBarMetrics.amplitudeDuration, value: 1);

  final AnimationController _controller;
  double _from;
  double _to;
  Curve _curve = WaveBarMetrics.amplitudeIncreasing;

  Listenable get listenable => _controller;

  double get value => _from + (_to - _from) * _curve.transform(_controller.value);

  void animateTo(double target, {bool instant = false}) {
    if (target == _to) return;
    final current = value;
    _curve = current < target ? WaveBarMetrics.amplitudeIncreasing : WaveBarMetrics.amplitudeDecreasing;
    _from = current;
    _to = target;
    if (instant) {
      _controller.value = 1;
    } else {
      _controller.forward(from: 0);
    }
  }

  void dispose() => _controller.dispose();
}

class _PreviewBubble extends StatelessWidget {
  const _PreviewBubble({required this.fraction, required this.width, required this.durationMs, this.onNote = false});

  /// The thumb sits on a note: the bubble reads "Note" on the primary colour.
  final bool onNote;
  final double fraction;
  final double width;
  final int durationMs;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelLarge!.copyWith(
      color: onNote ? colors.onPrimary : colors.onSecondaryContainer,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    final label = onNote ? NowPlayingStrings.of(context).waveNote : formatPlaybackTime((fraction * durationMs).round());
    final painter = TextPainter(
      text: TextSpan(text: label, style: style),
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
    )..layout();
    final bubbleWidth = painter.width + 24;
    painter.dispose();
    final double x = (width * fraction - bubbleWidth / 2).clamp(0.0, math.max(0.0, width - bubbleWidth));
    // Align.TopStart of the 40 dp touch box, like Android: it rides over the
    // space above the 18 dp indicator.
    return Positioned(
      left: x,
      top: 0,
      child: Material(
        color: onNote ? colors.primary : colors.secondaryContainer,
        shape: const StadiumBorder(),
        elevation: 0,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Text(label, style: style),
        ),
      ),
    );
  }
}

/// Paints the track then the indicator, as M3E's DeterminateLinearWavyProgressNode.
class _WaveIndicatorPainter extends CustomPainter {
  _WaveIndicatorPainter({
    required this.clock,
    required _Amplitude amplitude,
    required this.preview,
    required this.isPlaying,
    required this.visible,
    required this.progressColor,
    required this.trackColor,
  }) : _amplitude = amplitude,
       // Hidden: no per-tick repaint, the wave stands still.
       super(repaint: Listenable.merge([if (visible) clock, amplitude.listenable, preview]));

  final PlayheadClock clock;
  final _Amplitude _amplitude;
  final ValueNotifier<double?> preview;
  final bool isPlaying;
  final bool visible;
  final Color progressColor;
  final Color trackColor;

  @override
  void paint(Canvas canvas, Size size) {
    final now = clock.nowMs;
    final fraction = WaveGeometry.headFraction(
      isPlaying: isPlaying,
      preview: preview.value,
      settled: clock.progress(now),
    );
    final cap = WaveGeometry.capWidth(size);
    final mid = size.height / 2;

    final track = WaveGeometry.track(size.width, fraction, cap);
    if (track != null) {
      canvas.drawLine(
        Offset(track.start, mid),
        Offset(track.end, mid),
        Paint()
          ..color = trackColor
          ..strokeWidth = WaveBarMetrics.stroke
          ..strokeCap = StrokeCap.butt,
      );
    }

    final indicator = WaveGeometry.indicator(size.width, fraction, cap);
    if (indicator == null) return;
    final amplitude = _amplitude.value.clamp(0.0, 1.0);
    final travelling = isPlaying && visible && amplitude > 0;
    final path = Path();
    WaveGeometry.addWave(
      path,
      indicator,
      height: size.height,
      amplitude: amplitude,
      shift: travelling ? WaveGeometry.shiftAt(now) : 0,
    );
    canvas.drawPath(
      path,
      Paint()
        ..color = progressColor
        ..style = PaintingStyle.stroke
        ..strokeWidth = WaveBarMetrics.stroke
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round,
    );
  }

  @override
  bool shouldRepaint(_WaveIndicatorPainter old) =>
      old.isPlaying != isPlaying ||
      old.visible != visible ||
      old.progressColor != progressColor ||
      old.trackColor != trackColor ||
      old.clock != clock;
}
