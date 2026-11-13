import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'haptics.dart';
import 'playhead_clock.dart';

/// WaveProgressBar.kt numbers (WaveBarTokens not in tokens.dart yet).
abstract final class WaveBarMetrics {
  static const double touchHeight = 40;
  static const double indicatorHeight = 18;
  static const double stroke = 4 * 0.92;
  static const double gap = 1;
  static const double wavelength = 24;
  static const double amplitudeFraction = 0.28;

  /// One wavelength per second while playing.
  static const double speed = 24;
  static const double trackAlpha = 0.14;
  static const Duration bubbleLinger = Duration(milliseconds: 650);
}

/// The M3 Expressive wavy linear indicator with scrubbing (WaveProgressBar.kt).
/// The head reads the shared [PlayheadClock] at paint time; only this
/// painter repaints per tick. Press shows a preview bubble; a tap seeks on
/// release, a horizontal drag seeks on drag end (the song keeps playing).
class WaveProgressBar extends StatefulWidget {
  const WaveProgressBar({
    super.key,
    required this.clock,
    required this.isPlaying,
    required this.durationMs,
    required this.onSeek,
    this.visible = true,
  });

  final PlayheadClock clock;
  final bool isPlaying;
  final int? durationMs;

  /// Seek to a fraction of the song.
  final ValueChanged<double> onSeek;

  /// False while the controls slot is folded away (Expanded stage): the wave stops.
  final bool visible;

  @override
  State<WaveProgressBar> createState() => _WaveProgressBarState();
}

class _WaveProgressBarState extends State<WaveProgressBar> with TickerProviderStateMixin {
  /// Wave amplitude 0 (paused, flat) ⇄ 1 (playing), eased ~500 ms (M3E).
  late final SpringValue _amplitude = SpringValue(
    vsync: this,
    initial: widget.isPlaying ? 1 : 0,
    spring: YoinSprings.of(MotionTokens.expressiveSlowEffects),
  );

  /// The finger's fraction while pressed / dragged (null = none).
  final ValueNotifier<double?> _preview = ValueNotifier(null);
  Timer? _linger;
  bool _dragging = false;

  @override
  void didUpdateWidget(WaveProgressBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    _amplitude.animateTo(widget.isPlaying && widget.visible ? 1 : 0);
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

  void _release({required bool seek, required bool tap}) {
    final fraction = _preview.value;
    if (seek && fraction != null && _canSeek) {
      tap ? YoinHaptics.lightTick() : YoinHaptics.tick();
      widget.onSeek(fraction);
    }
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
        return Semantics(
          slider: true,
          value: formatPlaybackTime(widget.clock.positionMs()),
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapDown: _canSeek ? (d) => _press(_fractionAt(d.localPosition, width)) : null,
            onTapUp: _canSeek ? (_) => _release(seek: true, tap: true) : null,
            onTapCancel: _canSeek ? () => _release(seek: false, tap: true) : null,
            onHorizontalDragStart: _canSeek
                ? (d) {
                    _dragging = true;
                    _press(_fractionAt(d.localPosition, width));
                  }
                : null,
            onHorizontalDragUpdate: _canSeek ? (d) => _preview.value = _fractionAt(d.localPosition, width) : null,
            onHorizontalDragEnd: _canSeek
                ? (_) {
                    _dragging = false;
                    _release(seek: true, tap: false);
                  }
                : null,
            onHorizontalDragCancel: _canSeek
                ? () {
                    _dragging = false;
                    _release(seek: false, tap: false);
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
                        painter: _WavePainter(
                          clock: widget.clock,
                          amplitude: _amplitude,
                          preview: _preview,
                          travelling: widget.isPlaying && widget.visible,
                          progressColor: colors.primary,
                          trackColor: colors.surfaceContainerHighest.withValues(alpha: WaveBarMetrics.trackAlpha),
                        ),
                      ),
                    ),
                  ),
                  ValueListenableBuilder<double?>(
                    valueListenable: _preview,
                    builder: (context, fraction, _) => fraction == null
                        ? const SizedBox.shrink()
                        : _PreviewBubble(fraction: fraction, width: width, durationMs: duration),
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

class _PreviewBubble extends StatelessWidget {
  const _PreviewBubble({required this.fraction, required this.width, required this.durationMs});

  final double fraction;
  final double width;
  final int durationMs;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelLarge!.copyWith(
      color: colors.onSecondaryContainer,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    final label = formatPlaybackTime((fraction * durationMs).round());
    final painter = TextPainter(
      text: TextSpan(text: label, style: style),
      textDirection: Directionality.of(context),
      textScaler: MediaQuery.textScalerOf(context),
    )..layout();
    final bubbleWidth = painter.width + 24;
    final double x = (width * fraction - bubbleWidth / 2).clamp(0.0, math.max(0.0, width - bubbleWidth));
    return Positioned(
      left: x,
      bottom: WaveBarMetrics.touchHeight,
      child: Material(
        color: colors.secondaryContainer,
        shape: const StadiumBorder(),
        elevation: 0,
        surfaceTintColor: colors.surfaceTint,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          child: Text(label, style: style),
        ),
      ),
    );
  }
}

class _WavePainter extends CustomPainter {
  _WavePainter({
    required this.clock,
    required this.amplitude,
    required this.preview,
    required this.travelling,
    required this.progressColor,
    required this.trackColor,
  }) : super(repaint: Listenable.merge([clock, amplitude.animation, preview]));

  final PlayheadClock clock;
  final SpringValue amplitude;
  final ValueNotifier<double?> preview;
  final bool travelling;
  final Color progressColor;
  final Color trackColor;

  @override
  void paint(Canvas canvas, Size size) {
    final now = clock.nowMs;
    final fraction = preview.value ?? clock.progress(now);
    const stroke = WaveBarMetrics.stroke;
    final mid = size.height / 2;
    final head = (size.width * fraction).clamp(0.0, size.width);
    final amp = (size.height - stroke) / 2 * WaveBarMetrics.amplitudeFraction * 2 * amplitude.value.clamp(0.0, 1.0);
    final shift = travelling ? (now / 1000 * WaveBarMetrics.speed) % WaveBarMetrics.wavelength : 0.0;

    // Track (flat, butt cap) after the head.
    final trackStart = head + stroke / 2 + WaveBarMetrics.gap + stroke / 2;
    if (trackStart < size.width) {
      canvas.drawLine(
        Offset(trackStart, mid),
        Offset(size.width, mid),
        Paint()
          ..color = trackColor
          ..strokeWidth = stroke
          ..strokeCap = StrokeCap.butt,
      );
    }
    // Indicator (wave, round cap) up to the head.
    if (head <= stroke / 2) return;
    final path = Path();
    const step = 2.0;
    for (var x = stroke / 2; x <= head; x += step) {
      final y = mid + math.sin((x - shift) / WaveBarMetrics.wavelength * 2 * math.pi) * amp;
      x == stroke / 2 ? path.moveTo(x, y) : path.lineTo(x, y);
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = progressColor
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round,
    );
  }

  @override
  bool shouldRepaint(_WavePainter old) =>
      old.travelling != travelling || old.progressColor != progressColor || old.trackColor != trackColor;
}
