import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui';

import '../home_ui_state.dart';

/// The entity backdrop shapes (MaterialShapes Bun / Circle / Ghostish and the
/// pressed Triangle), as paths in a 100 × 100 box. Bun, Circle and Ghostish
/// are the approximations from docs/handoff/home-edit-mode/impl/proto.js
/// (owner-accepted for v1); the Triangle is a rounded triangle. Every path
/// starts at the top centre and runs clockwise so [morphPath] can pair points.
///
/// TODO(harmony): port androidx.graphics.shapes (RoundedPolygon + Morph) for exact parity; get a visual sign-off.
abstract final class BackdropShapes {
  static Path forKind(HomeCardKind kind) => switch (kind) {
        HomeCardKind.album => bun,
        HomeCardKind.song || HomeCardKind.artist => circle,
        HomeCardKind.playlist => ghostish,
      };

  static final Path bun = Path()
    ..moveTo(50, 4)
    ..cubicTo(78, 4, 94, 16, 94, 32)
    ..cubicTo(94, 42, 88, 47, 86, 50)
    ..cubicTo(88, 53, 94, 58, 94, 68)
    ..cubicTo(94, 84, 78, 96, 50, 96)
    ..cubicTo(22, 96, 6, 84, 6, 68)
    ..cubicTo(6, 58, 12, 53, 14, 50)
    ..cubicTo(12, 47, 6, 42, 6, 32)
    ..cubicTo(6, 16, 22, 4, 50, 4)
    ..close();

  static final Path circle = Path()
    ..moveTo(50, 4)
    ..arcToPoint(const Offset(50, 96), radius: const Radius.circular(46))
    ..arcToPoint(const Offset(50, 4), radius: const Radius.circular(46))
    ..close();

  static final Path ghostish = Path()
    ..moveTo(50, 5)
    ..cubicTo(75, 5, 92, 22, 92, 47)
    ..lineTo(92, 84)
    ..cubicTo(92, 93, 84, 97, 77, 91)
    ..cubicTo(72, 87, 67, 87, 62, 91)
    ..cubicTo(57, 96, 43, 96, 38, 91)
    ..cubicTo(33, 87, 28, 87, 23, 91)
    ..cubicTo(16, 97, 8, 93, 8, 84)
    ..lineTo(8, 47)
    ..cubicTo(8, 22, 25, 5, 50, 5)
    ..close();

  /// Rounded triangle, apex up.
  static final Path triangle = _roundedPolygon(const [Offset(50, 6), Offset(95, 88), Offset(5, 88)], 16);

  static Path _roundedPolygon(List<Offset> v, double radius) {
    final n = v.length;
    Offset toward(Offset from, Offset to) {
      final d = to - from;
      return from + d / d.distance * math.min(radius, d.distance / 2);
    }

    // Start at the apex rounding's midpoint (the top centre), clockwise.
    final a0 = toward(v[0], v[n - 1]);
    final b0 = toward(v[0], v[1]);
    final mid = a0 * 0.25 + v[0] * 0.5 + b0 * 0.25;
    final c1 = (v[0] + b0) / 2;
    final path = Path()
      ..moveTo(mid.dx, mid.dy)
      ..quadraticBezierTo(c1.dx, c1.dy, b0.dx, b0.dy);
    for (var i = 1; i < n; i++) {
      final a = toward(v[i], v[i - 1]);
      final b = toward(v[i], v[(i + 1) % n]);
      path
        ..lineTo(a.dx, a.dy)
        ..quadraticBezierTo(v[i].dx, v[i].dy, b.dx, b.dy);
    }
    final c0 = (a0 + v[0]) / 2;
    return path
      ..lineTo(a0.dx, a0.dy)
      ..quadraticBezierTo(c0.dx, c0.dy, mid.dx, mid.dy)
      ..close();
  }

  static const int _samples = 96;
  static final Map<Path, List<Offset>> _sampled = {};

  static List<Offset> _sample(Path path) => _sampled[path] ??= () {
        final metric = path.computeMetrics().first;
        return [
          for (var i = 0; i < _samples; i++) metric.getTangentForOffset(metric.length * i / _samples)!.position,
        ];
      }();

  /// [from] at t = 0, [to] at t = 1 (t may overshoot), scaled to [size].
  static Path morphPath(Path from, Path to, double t, Size size) {
    final sx = size.width / 100, sy = size.height / 100;
    if (t.abs() < 0.001) return from.transform(_scaleMatrix(sx, sy));
    final a = _sample(from), b = _sample(to);
    final path = Path();
    for (var i = 0; i < _samples; i++) {
      final p = Offset.lerp(a[i], b[i], t)!;
      if (i == 0) {
        path.moveTo(p.dx * sx, p.dy * sy);
      } else {
        path.lineTo(p.dx * sx, p.dy * sy);
      }
    }
    return path..close();
  }

  static Float64List _scaleMatrix(double sx, double sy) =>
      Float64List.fromList([sx, 0, 0, 0, 0, sy, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
}
