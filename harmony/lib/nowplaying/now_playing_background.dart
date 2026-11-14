import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/playback_theme.dart';

/// Room constants (NowPlayingAuroraBackground.kt L1404-1436, RoomAmbient.kt).
abstract final class RoomMetrics {
  static const double darkPointAlpha = 0.42;
  static const double lightSaturation = 1.8;
  static const double neighbourAlpha = 0.42;
  static const double auraAlpha = 0.36;
  static const double veilAlpha = 0.40;
  static const Duration washDuration = Duration(milliseconds: 820);

  /// Focus's plain colour field: the points' peak alpha.
  static const double focusFieldAlphaDark = 0.36;
  static const double focusFieldAlphaLight = 0.30;
}

/// Now Playing's reactive room: STILL at rest (no frame callbacks). On a
/// cover-colour change the old wash crosses to the new one over 820 ms
/// (EaseOutCubic, draw phase only; the scheme itself is cut).
/// Dark: top gradient + four screen-blended soft points of the cover's
/// lifted colours. Light: the "iOS room" — neighbour blobs, a blurred cover
/// aura and a veil, under a 1.8 saturation boost.
class NowPlayingBackground extends StatefulWidget {
  const NowPlayingBackground({super.key, required this.field, this.focus});

  final CoverField? field;

  /// Focus (tap the cover): the room fills with the cover's colours.
  final Animation<double>? focus;

  @override
  State<NowPlayingBackground> createState() => _NowPlayingBackgroundState();
}

class _NowPlayingBackgroundState extends State<NowPlayingBackground> with SingleTickerProviderStateMixin {
  late final AnimationController _wash = AnimationController(vsync: this, duration: RoomMetrics.washDuration, value: 1);
  late final Animation<double> _eased = CurvedAnimation(parent: _wash, curve: Curves.easeOutCubic);
  CoverField? _from;

  @override
  void didUpdateWidget(NowPlayingBackground oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.field != widget.field) {
      _from = oldWidget.field;
      if (_from != null && widget.field != null && !MediaQuery.disableAnimationsOf(context)) {
        _wash.forward(from: 0);
      } else {
        _wash.value = 1;
      }
    }
  }

  @override
  void dispose() {
    _wash.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return RepaintBoundary(
      child: CustomPaint(
        painter: _RoomPainter(
          scheme: scheme,
          from: _from,
          to: widget.field,
          wash: _eased,
          focus: widget.focus ?? kAlwaysDismissedAnimation,
        ),
        child: const SizedBox.expand(),
      ),
    );
  }
}

class _RoomPainter extends CustomPainter {
  _RoomPainter({required this.scheme, required this.from, required this.to, required this.wash, required this.focus})
    : super(repaint: Listenable.merge([wash, focus]));

  final ColorScheme scheme;
  final CoverField? from;
  final CoverField? to;
  final Animation<double> wash;
  final Animation<double> focus;

  static Color _lift(Color c) {
    double ch(double v) => math.min(1, v * 1.15 + 25 / 255);
    return Color.from(alpha: 1, red: ch(c.r), green: ch(c.g), blue: ch(c.b));
  }

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final dark = scheme.brightness == Brightness.dark;
    if (dark) {
      canvas.drawRect(
        rect,
        Paint()
          ..shader = LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [scheme.surfaceContainer, scheme.surface],
          ).createShader(rect),
      );
    } else {
      canvas.saveLayer(rect, Paint()..colorFilter = ColorFilter.matrix(_saturation(RoomMetrics.lightSaturation)));
      canvas.drawRect(rect, Paint()..color = scheme.surface);
    }
    final e = wash.value.clamp(0.0, 1.0);
    if (from != null && e < 1) _paintField(canvas, size, from, 1 - e, dark);
    _paintField(canvas, size, to, from == null ? 1 : e, dark);
    if (!dark) {
      canvas.drawRect(rect, Paint()..color = scheme.surface.withValues(alpha: RoomMetrics.veilAlpha));
      canvas.restore();
    }
    final f = focus.value.clamp(0.0, 1.0);
    if (f > 0.001) _paintFocusField(canvas, size, to, f, dark);
  }

  /// Focus's colour field, plain (FocusField.kt's 16-point animated field
  /// is not ported): the cover's main hues and its most vivid colour as soft
  /// points round the room, opening out from the cover with the focus.
  void _paintFocusField(Canvas canvas, Size size, CoverField? field, double f, bool dark) {
    final w = size.width, h = size.height;
    final palette = field?.palette ?? [scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer];
    final accent = field?.accent ?? scheme.primary;
    const origin = Offset(0.5, 0.3);
    final points = <(Color, Offset, double)>[
      (palette[0], const Offset(0.08, 0.12), 0.75),
      (palette[1 % palette.length], const Offset(0.92, 0.18), 0.70),
      (palette[2 % palette.length], const Offset(0.05, 0.62), 0.65),
      (palette[3 % palette.length], const Offset(0.95, 0.70), 0.65),
      (accent, const Offset(0.5, 0.98), 0.80),
    ];
    final cap = dark ? RoomMetrics.focusFieldAlphaDark : RoomMetrics.focusFieldAlphaLight;
    for (final (c, at, r) in points) {
      final p = Offset.lerp(origin, at, f)!;
      final center = Offset(w * p.dx, h * p.dy);
      final radius = w * r * (0.6 + 0.4 * f);
      final color = dark ? _lift(c) : c;
      final a = cap * f;
      canvas.drawCircle(
        center,
        radius,
        Paint()
          ..blendMode = dark ? BlendMode.screen : BlendMode.srcOver
          ..shader = RadialGradient(
            colors: [
              color.withValues(alpha: a),
              color.withValues(alpha: a * 0.4),
              color.withValues(alpha: 0),
            ],
            stops: const [0, 0.6, 1],
          ).createShader(Rect.fromCircle(center: center, radius: radius)),
      );
    }
  }

  void _paintField(Canvas canvas, Size size, CoverField? field, double alpha, bool dark) {
    if (alpha <= 0) return;
    final w = size.width, h = size.height;
    if (dark) {
      final k1 = _lift(field?.palette[0] ?? scheme.primary);
      final k2 = _lift(field?.palette[1] ?? scheme.tertiary);
      final k3 = _lift(field?.palette[2] ?? scheme.secondary);
      void point(Color c, double x, double y, double r) {
        final center = Offset(w * x, h * y);
        final radius = w * r;
        final a = RoomMetrics.darkPointAlpha * alpha;
        canvas.drawCircle(
          center,
          radius,
          Paint()
            ..blendMode = BlendMode.screen
            ..shader = RadialGradient(
              colors: [
                c.withValues(alpha: a),
                c.withValues(alpha: a * 0.4),
                c.withValues(alpha: 0),
              ],
              stops: const [0, 0.6, 1],
            ).createShader(Rect.fromCircle(center: center, radius: radius)),
        );
      }

      point(k1, 0.12, 0.06, 0.85);
      point(k3, 0.86, 0.10, 0.70);
      point(k2, 0.52, 0.30, 0.75);
      point(k1, 0.95, 0.42, 0.50);
      return;
    }
    // Light room. Neighbour blobs default to secondary / tertiary containers
    // (previous / next song colours are not wired yet).
    final k = (w / 402).clamp(0.85, 1.6);
    void blob(Color c, double cy, double height, double a) {
      canvas.drawOval(
        Rect.fromCenter(center: Offset(w / 2, cy), width: w * 1.2, height: height * k),
        Paint()
          ..color = c.withValues(alpha: a * alpha)
          ..maskFilter = MaskFilter.blur(BlurStyle.normal, 50 * k),
      );
    }

    blob(scheme.secondaryContainer, 60 * k, 300, RoomMetrics.neighbourAlpha);
    blob(scheme.tertiaryContainer, h - 50 * k, 320, RoomMetrics.neighbourAlpha);
    canvas.drawCircle(
      Offset(w / 2, h / 2),
      180 * k,
      Paint()
        ..color = (field?.palette[0] ?? scheme.primary).withValues(alpha: RoomMetrics.auraAlpha * alpha)
        ..maskFilter = MaskFilter.blur(BlurStyle.normal, 95 * k),
    );
  }

  static List<double> _saturation(double s) {
    const lr = 0.2126, lg = 0.7152, lb = 0.0722;
    final sr = (1 - s) * lr, sg = (1 - s) * lg, sb = (1 - s) * lb;
    return [
      sr + s, sg, sb, 0, 0, //
      sr, sg + s, sb, 0, 0, //
      sr, sg, sb + s, 0, 0, //
      0, 0, 0, 1, 0,
    ];
  }

  @override
  bool shouldRepaint(_RoomPainter old) => old.scheme != scheme || old.from != from || old.to != to;
}
