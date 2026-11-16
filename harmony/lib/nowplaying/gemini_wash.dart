/// The AI's wash over Now Playing (Android NowPlayingAuroraBackground's
/// drawGeminiWash, and GeminiKeyHint's glow): while the AI thinks — a
/// question on its way, or About's first fetch — a pastel wash runs from the
/// top, its hue going round primary → Gemini blue → secondary → tertiary
/// pulled toward a slow rainbow, with two corner blooms and fine dots where it
/// fades. When About needs a key that isn't set up, the same wash held to one
/// soft pink shows for a few seconds, at most every three days.
///
/// Still when idle: the clock only runs while either is visible.
library;

import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' show PointMode;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';

class GeminiWash extends StatefulWidget {
  const GeminiWash({super.key, required this.thinking, required this.keyHint});

  /// The AI is writing.
  final bool thinking;

  /// The key hint's pink glow is up.
  final bool keyHint;

  @override
  State<GeminiWash> createState() => _GeminiWashState();
}

class _GeminiWashState extends State<GeminiWash> with TickerProviderStateMixin {
  // Envelopes: in while thinking, out when the answer lands (slow effects spring).
  late final SpringValue _active = SpringValue(
    vsync: this,
    initial: widget.thinking ? 1 : 0,
    spring: YoinSprings.of(MotionTokens.expressiveSlowEffects),
  );
  late final SpringValue _hint = SpringValue(
    vsync: this,
    initial: widget.keyHint ? 1 : 0,
    spring: YoinSprings.of(MotionTokens.expressiveSlowEffects),
  );
  late final Ticker _ticker = createTicker(_tick);
  final ValueNotifier<double> _clock = ValueNotifier(0);
  Duration _base = Duration.zero;
  double _offset = 0;

  @override
  void initState() {
    super.initState();
    _active.animation.addListener(_gate);
    _hint.animation.addListener(_gate);
    WidgetsBinding.instance.addPostFrameCallback((_) => _gate());
  }

  @override
  void didUpdateWidget(GeminiWash old) {
    super.didUpdateWidget(old);
    _active.animateTo(widget.thinking ? 1 : 0);
    _hint.animateTo(widget.keyHint ? 1 : 0);
    _gate();
  }

  bool get _visible => widget.thinking || widget.keyHint || _active.value > 0.01 || _hint.value > 0.01;

  void _gate() {
    if (!mounted) return;
    final run = _visible && !MediaQuery.disableAnimationsOf(context);
    if (run && !_ticker.isActive) {
      _base = Duration.zero;
      _offset = _clock.value;
      _ticker.start();
    } else if (!run && _ticker.isActive) {
      _ticker.stop();
    }
  }

  void _tick(Duration elapsed) {
    if (_base == Duration.zero) _base = elapsed;
    // Continuous across restarts.
    _clock.value = _offset + (elapsed - _base).inMicroseconds / 1000.0;
  }

  @override
  void dispose() {
    _ticker.dispose();
    _active.dispose();
    _hint.dispose();
    _clock.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return IgnorePointer(
      child: RepaintBoundary(
        child: CustomPaint(
          painter: _WashPainter(
            repaint: Listenable.merge([_active.animation, _hint.animation, _clock]),
            active: () => _active.value,
            hint: () => _hint.value,
            clock: () => _clock.value,
            primary: scheme.primary,
            secondary: scheme.secondary,
            tertiary: scheme.tertiary,
            dark: scheme.surface.computeLuminance() < 0.5,
          ),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }
}

class _WashPainter extends CustomPainter {
  _WashPainter({
    required Listenable repaint,
    required this.active,
    required this.hint,
    required this.clock,
    required this.primary,
    required this.secondary,
    required this.tertiary,
    required this.dark,
  }) : super(repaint: repaint);

  final double Function() active;
  final double Function() hint;
  final double Function() clock;
  final Color primary;
  final Color secondary;
  final Color tertiary;
  final bool dark;

  @override
  void paint(Canvas canvas, Size size) {
    final t = clock();
    final a = active().clamp(0.0, 1.0);
    final h = hint().clamp(0.0, 1.0);
    if (a > 0.001) _wash(canvas, size, t, a);
    if (h > 0.001) {
      _wash(
        canvas,
        size,
        t,
        h,
        fixedHue: NowPlayingAboutTokens.keyHintHue,
        strength: NowPlayingAboutTokens.keyHintStrength,
      );
    }
  }

  static double _hueOf(Color c) => HSLColor.fromColor(c).hue;

  static double _lerpHue(double a, double b, double t) {
    final d = (((b - a + 540) % 360) + 360) % 360 - 180;
    return a + d * t;
  }

  static double _norm(double h) => ((h % 360) + 360) % 360;

  static double _smooth(double x) {
    final c = x.clamp(0.0, 1.0);
    return c * c * (3 - 2 * c);
  }

  void _bloom(Canvas canvas, Offset center, double radius, Color color, double alpha, BlendMode blend) {
    if (radius <= 0 || alpha <= 0.001) return;
    final paint = Paint()
      ..blendMode = blend
      ..shader = RadialGradient(
        colors: [
          color.withValues(alpha: alpha.clamp(0.0, 1.0)),
          color.withValues(alpha: 0),
        ],
      ).createShader(Rect.fromCircle(center: center, radius: radius));
    canvas.drawCircle(center, radius, paint);
  }

  void _wash(Canvas canvas, Size size, double t, double frac, {double? fixedHue, double strength = 1}) {
    final w = size.width;
    final h = size.height;
    final blend = dark ? BlendMode.screen : BlendMode.srcOver;
    final ring = [_hueOf(primary), NowPlayingAboutTokens.washBlueHue, _hueOf(secondary), _hueOf(tertiary)];
    double ringHue(double phase) {
      final x = ((phase % 4) + 4) % 4;
      final i = x.floor();
      return _lerpHue(ring[i], ring[(i + 1) % 4], _smooth(x - i));
    }

    const mix = NowPlayingAboutTokens.washRainbowMix;
    final phase = t / 2600;
    final rainbow = (t / 7000 * 360 + 200) % 360;
    final hueA = fixedHue ?? _lerpHue(ringHue(phase), rainbow, mix);
    final hueB = fixedHue != null ? fixedHue - 12 : _lerpHue(ringHue(phase + 1.3), rainbow + 70, mix);
    final hueC = fixedHue != null ? fixedHue + 10 : _lerpHue(ringHue(phase + 2.6), rainbow + 150, mix);
    final s = dark ? 0.78 : 0.95;
    final l = dark ? 0.52 : 0.82;
    final alpha = (dark ? 0.62 : 0.92) * frac.clamp(0.0, 1.0) * strength;
    Color hsl(double hue, double a, [double? lightness]) =>
        HSLColor.fromAHSL(a.clamp(0.0, 1.0), _norm(hue), s, lightness ?? l).toColor();

    final depth = h * (0.22 + 0.38 * _smooth(math.min(1.0, frac)));
    final bottom = depth + h * 0.12;
    final rect = Rect.fromLTWH(0, 0, w, bottom);
    canvas.drawRect(
      rect,
      Paint()
        ..blendMode = blend
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [hsl(hueA, alpha), hsl(hueA, alpha * 0.45), hsl(hueA, 0)],
          stops: const [0, 0.55, 1],
        ).createShader(rect),
    );
    final dx = math.sin(t / 3100) * w * 0.08;
    _bloom(canvas, Offset(w * 0.12 + dx, h * 0.04), w * 0.78, hsl(hueB, 1), alpha * 0.85, blend);
    _bloom(canvas, Offset(w * 0.92 - dx, h * 0.10), w * 0.70, hsl(hueC, 1), alpha * 0.75, blend);

    // Fine dots where the wash fades out.
    const grid = 6.0;
    const rMax = 1.3;
    final y0 = depth * 0.55;
    final y1 = depth + h * 0.14;
    // Batched by size into a few round-capped point draws (DotBatch).
    const buckets = 4;
    final points = List.generate(buckets, (_) => <double>[]);
    var row = 0;
    for (var y = y0; y < y1; y += grid, row++) {
      final k = 1 - (y - y0) / (y1 - y0);
      final kk = math.pow(k, 1.2).toDouble();
      for (var x = row.isOdd ? grid / 2 : 0.0; x < w; x += grid) {
        final n = 0.5 + 0.5 * math.sin(x * 0.045 + t / 900) * math.cos(y * 0.05 - t / 1300);
        final r = rMax * kk * (0.35 + 0.65 * n);
        if (r < 0.3) continue;
        points[((r / rMax) * buckets).floor().clamp(0, buckets - 1)]
          ..add(x)
          ..add(y);
      }
    }
    final dot = Paint()
      ..blendMode = blend
      ..strokeCap = StrokeCap.round
      ..color = hsl(hueB, (dark ? 0.55 : 0.45) * frac, dark ? 0.7 : 0.62);
    for (var b = 0; b < buckets; b++) {
      if (points[b].isEmpty) continue;
      dot.strokeWidth = 2 * rMax * (b + 0.5) / buckets;
      canvas.drawRawPoints(PointMode.points, Float32List.fromList(points[b]), dot);
    }
    _bloom(canvas, Offset(w * 0.5, h * 0.95), w * 0.6, hsl(hueB, 1), alpha * 0.35, blend);
  }

  @override
  bool shouldRepaint(_WashPainter old) =>
      old.primary != primary || old.secondary != secondary || old.tertiary != tertiary || old.dark != dark;
}
