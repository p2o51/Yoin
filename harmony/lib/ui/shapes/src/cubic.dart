// Copyright 2023 The Android Open Source Project
// Licensed under the Apache License, Version 2.0 (see ../graphics_shapes.dart).
// Port of graphics-shapes 1.1.0 `Cubic.kt`.

part of '../graphics_shapes.dart';

/// One cubic Bézier: anchor0, control0, control1, anchor1 as 8 numbers
/// (graphics-shapes `Cubic`). Named apart from Flutter's easing `Cubic`.
@immutable
final class ShapeCubic {
  ShapeCubic(List<double> points)
    : assert(points.length == 8, 'Points array size should be 8'),
      points = List<double>.unmodifiable(points);

  ShapeCubic.of(double a0x, double a0y, double c0x, double c0y, double c1x, double c1y, double a1x, double a1y)
    : points = List<double>.unmodifiable([a0x, a0y, c0x, c0y, c1x, c1y, a1x, a1y]);

  ShapeCubic.fromPoints(Offset a0, Offset c0, Offset c1, Offset a1)
    : this.of(a0.dx, a0.dy, c0.dx, c0.dy, c1.dx, c1.dy, a1.dx, a1.dy);

  final List<double> points;

  double get anchor0X => points[0];
  double get anchor0Y => points[1];
  double get control0X => points[2];
  double get control0Y => points[3];
  double get control1X => points[4];
  double get control1Y => points[5];
  double get anchor1X => points[6];
  double get anchor1Y => points[7];

  Offset get anchor0 => Offset(anchor0X, anchor0Y);
  Offset get control0 => Offset(control0X, control0Y);
  Offset get control1 => Offset(control1X, control1Y);
  Offset get anchor1 => Offset(anchor1X, anchor1Y);

  /// Cubic.straightLine: control points at 1/3 and 2/3 of the segment.
  static ShapeCubic straightLine(double x0, double y0, double x1, double y1) => ShapeCubic.of(
    x0,
    y0,
    _interpolate(x0, x1, 1 / 3),
    _interpolate(y0, y1, 1 / 3),
    _interpolate(x0, x1, 2 / 3),
    _interpolate(y0, y1, 2 / 3),
    x1,
    y1,
  );

  /// Cubic.circularArc: the arc around (centerX, centerY) from (x0, y0) to
  /// (x1, y1), the short way round.
  static ShapeCubic circularArc(double centerX, double centerY, double x0, double y0, double x1, double y1) {
    final p0d = _directionVector(x0 - centerX, y0 - centerY);
    final p1d = _directionVector(x1 - centerX, y1 - centerY);
    final rotatedP0 = _rotate90(p0d);
    final rotatedP1 = _rotate90(p1d);
    final clockwise = rotatedP0.dx * (x1 - centerX) + rotatedP0.dy * (y1 - centerY) >= 0;
    final cosa = _dot(p0d, p1d);
    if (cosa > 0.999) return straightLine(x0, y0, x1, y1);
    final k =
        _distance(x0 - centerX, y0 - centerY) *
        4 /
        3 *
        (math.sqrt(2 * (1 - cosa)) - math.sqrt(1 - cosa * cosa)) /
        (1 - cosa) *
        (clockwise ? 1 : -1);
    return ShapeCubic.of(
      x0,
      y0,
      x0 + rotatedP0.dx * k,
      y0 + rotatedP0.dy * k,
      x1 - rotatedP1.dx * k,
      y1 - rotatedP1.dy * k,
      x1,
      y1,
    );
  }

  /// Cubic.empty: a zero-length cubic at (x0, y0).
  static ShapeCubic empty(double x0, double y0) => ShapeCubic.of(x0, y0, x0, y0, x0, y0, x0, y0);

  Offset pointOnCurve(double t) {
    final u = 1 - t;
    return Offset(
      anchor0X * (u * u * u) + control0X * (3 * t * u * u) + control1X * (3 * t * t * u) + anchor1X * (t * t * t),
      anchor0Y * (u * u * u) + control0Y * (3 * t * u * u) + control1Y * (3 * t * t * u) + anchor1Y * (t * t * t),
    );
  }

  bool get zeroLength => (anchor0X - anchor1X).abs() < distanceEpsilon && (anchor0Y - anchor1Y).abs() < distanceEpsilon;

  bool _zeroIsh(double value) => value.abs() < distanceEpsilon;

  /// Cubic.calculateBounds: [left, top, right, bottom]. [approximate] takes
  /// the hull of all four points (what Android's Path bounds report);
  /// otherwise the curve's true extremes.
  List<double> calculateBounds({bool approximate = false}) {
    final bounds = List<double>.filled(4, 0);
    _boundsInto(bounds, approximate);
    return bounds;
  }

  void _boundsInto(List<double> bounds, bool approximate) {
    if (zeroLength) {
      bounds[0] = anchor0X;
      bounds[1] = anchor0Y;
      bounds[2] = anchor0X;
      bounds[3] = anchor0Y;
      return;
    }
    var minX = math.min(anchor0X, anchor1X);
    var minY = math.min(anchor0Y, anchor1Y);
    var maxX = math.max(anchor0X, anchor1X);
    var maxY = math.max(anchor0Y, anchor1Y);
    if (approximate) {
      bounds[0] = math.min(minX, math.min(control0X, control1X));
      bounds[1] = math.min(minY, math.min(control0Y, control1Y));
      bounds[2] = math.max(maxX, math.max(control0X, control1X));
      bounds[3] = math.max(maxY, math.max(control0Y, control1Y));
      return;
    }
    // Extremes are where the derivative's x or y is zero: solve the quadratic.
    void extremes(double a, double b, double c, double Function(Offset) axis, void Function(double) take) {
      if (_zeroIsh(a)) {
        if (b != 0) {
          final t = 2 * c / (-2 * b);
          if (t >= 0 && t <= 1) take(axis(pointOnCurve(t)));
        }
        return;
      }
      final s = b * b - 4 * a * c;
      if (s < 0) return;
      final t1 = (-b + math.sqrt(s)) / (2 * a);
      if (t1 >= 0 && t1 <= 1) take(axis(pointOnCurve(t1)));
      final t2 = (-b - math.sqrt(s)) / (2 * a);
      if (t2 >= 0 && t2 <= 1) take(axis(pointOnCurve(t2)));
    }

    extremes(
      -anchor0X + 3 * control0X - 3 * control1X + anchor1X,
      2 * anchor0X - 4 * control0X + 2 * control1X,
      -anchor0X + control0X,
      (p) => p.dx,
      (x) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
      },
    );
    extremes(
      -anchor0Y + 3 * control0Y - 3 * control1Y + anchor1Y,
      2 * anchor0Y - 4 * control0Y + 2 * control1Y,
      -anchor0Y + control0Y,
      (p) => p.dy,
      (y) {
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      },
    );
    bounds[0] = minX;
    bounds[1] = minY;
    bounds[2] = maxX;
    bounds[3] = maxY;
  }

  /// Cubic.split: the two halves at [t].
  (ShapeCubic, ShapeCubic) split(double t) {
    final u = 1 - t;
    final p = pointOnCurve(t);
    return (
      ShapeCubic.of(
        anchor0X,
        anchor0Y,
        anchor0X * u + control0X * t,
        anchor0Y * u + control0Y * t,
        anchor0X * (u * u) + control0X * (2 * u * t) + control1X * (t * t),
        anchor0Y * (u * u) + control0Y * (2 * u * t) + control1Y * (t * t),
        p.dx,
        p.dy,
      ),
      ShapeCubic.of(
        p.dx,
        p.dy,
        control0X * (u * u) + control1X * (2 * u * t) + anchor1X * (t * t),
        control0Y * (u * u) + control1Y * (2 * u * t) + anchor1Y * (t * t),
        control1X * u + anchor1X * t,
        control1Y * u + anchor1Y * t,
        anchor1X,
        anchor1Y,
      ),
    );
  }

  ShapeCubic reverse() =>
      ShapeCubic.of(anchor1X, anchor1Y, control1X, control1Y, control0X, control0Y, anchor0X, anchor0Y);

  ShapeCubic operator +(ShapeCubic o) => ShapeCubic([for (var i = 0; i < 8; i++) points[i] + o.points[i]]);

  ShapeCubic operator *(double x) => ShapeCubic([for (final p in points) p * x]);

  ShapeCubic operator /(double x) => this * (1 / x);

  ShapeCubic transformed(PointTransformer f) => ShapeCubic.fromPoints(f(anchor0), f(control0), f(control1), f(anchor1));

  @override
  bool operator ==(Object other) => other is ShapeCubic && listEquals(other.points, points);

  @override
  int get hashCode => Object.hashAll(points);

  @override
  String toString() => 'ShapeCubic($points)';
}
