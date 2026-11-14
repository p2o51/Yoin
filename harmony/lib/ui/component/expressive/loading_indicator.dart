import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

// M3 Expressive LoadingIndicator, ported from the compiled classes Android
// Yoin ships (read with javap, not guessed):
//
// * androidx.compose.material3 1.5.0-alpha27 — `LoadingIndicatorKt`
//   (indeterminate impl, morph sequence, scale factor, path processing),
//   `LoadingIndicatorDefaults`, `tokens/LoadingIndicatorTokens`,
//   `MaterialShapes` (the seven indeterminate shapes and their helpers).
// * androidx.graphics:graphics-shapes 1.1.0 — `CornerRounding`, `Cubic`,
//   `Feature`, `RoundedCorner`, `RoundedPolygon` (+ `circle`, `star`,
//   `normalized`, bounds), `Morph`, `MeasuredPolygon`, `LengthMeasurer`,
//   `FeatureMapping`, `DoubleMapper`, `FloatMapping`.
//
// Kotlin computes in Float; this runs in double. Everything else (order of
// operations, epsilons, edge cases) follows the original.

// ───────────────────────────── graphics-shapes ─────────────────────────────

/// Utils.DistanceEpsilon.
const double _distanceEpsilon = 1e-4;

/// Utils.AngleEpsilon.
const double _angleEpsilon = 1e-6;

double _interpolate(double start, double stop, double fraction) => (1 - fraction) * start + fraction * stop;

double _positiveModulo(double num, double mod) => (num % mod + mod) % mod;

double _square(double x) => x * x;

double _distance(double x, double y) => math.sqrt(x * x + y * y);

double _distanceSquared(double x, double y) => x * x + y * y;

/// Utils.directionVector: the unit vector of (x, y).
Offset _directionVector(double x, double y) {
  final d = _distance(x, y);
  assert(d > 0, 'Required distance greater than zero');
  return Offset(x / d, y / d);
}

Offset _direction(Offset p) => _directionVector(p.dx, p.dy);

Offset _rotate90(Offset p) => Offset(-p.dy, p.dx);

double _dot(Offset a, Offset b) => a.dx * b.dx + a.dy * b.dy;

/// PointKt.clockwise: true when [other] turns clockwise from [p].
bool _clockwise(Offset p, Offset other) => p.dx * other.dy - p.dy * other.dx > 0;

Offset _radialToCartesian(double radius, double angleRadians, [Offset center = Offset.zero]) =>
    Offset(math.cos(angleRadians), math.sin(angleRadians)) * radius + center;

/// How a polygon corner is rounded: [radius] of the round part, and
/// [smoothing] (0–1) of the flanking curves that ease into it.
@immutable
final class CornerRounding {
  const CornerRounding(this.radius, {this.smoothing = 0});

  /// CornerRounding.Unrounded.
  static const unrounded = CornerRounding(0);

  final double radius;
  final double smoothing;
}

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

  Offset pointOnCurve(double t) {
    final u = 1 - t;
    return Offset(
      anchor0X * (u * u * u) + control0X * (3 * t * u * u) + control1X * (3 * t * t * u) + anchor1X * (t * t * t),
      anchor0Y * (u * u * u) + control0Y * (3 * t * u * u) + control1Y * (3 * t * t * u) + anchor1Y * (t * t * t),
    );
  }

  bool get zeroLength =>
      (anchor0X - anchor1X).abs() < _distanceEpsilon && (anchor0Y - anchor1Y).abs() < _distanceEpsilon;

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

  ShapeCubic transformed(Offset Function(Offset) f) {
    final a0 = f(Offset(anchor0X, anchor0Y));
    final c0 = f(Offset(control0X, control0Y));
    final c1 = f(Offset(control1X, control1Y));
    final a1 = f(Offset(anchor1X, anchor1Y));
    return ShapeCubic.fromPoints(a0, c0, c1, a1);
  }

  /// Cubic.calculateBounds(approximate = true): the hull of all four points.
  void _approximateBoundsInto(List<double> bounds) {
    if (zeroLength) {
      bounds[0] = anchor0X;
      bounds[1] = anchor0Y;
      bounds[2] = anchor0X;
      bounds[3] = anchor0Y;
      return;
    }
    bounds[0] = math.min(math.min(anchor0X, anchor1X), math.min(control0X, control1X));
    bounds[1] = math.min(math.min(anchor0Y, anchor1Y), math.min(control0Y, control1Y));
    bounds[2] = math.max(math.max(anchor0X, anchor1X), math.max(control0X, control1X));
    bounds[3] = math.max(math.max(anchor0Y, anchor1Y), math.max(control0Y, control1Y));
  }

  @override
  bool operator ==(Object other) => other is ShapeCubic && listEquals(other.points, points);

  @override
  int get hashCode => Object.hashAll(points);

  @override
  String toString() => 'ShapeCubic($points)';
}

/// A run of cubics on a polygon outline: a (possibly rounded) corner or the
/// straight edge between two corners (graphics-shapes `Feature`).
sealed class ShapeFeature {
  ShapeFeature(List<ShapeCubic> cubics) : cubics = List<ShapeCubic>.unmodifiable(cubics);

  final List<ShapeCubic> cubics;

  ShapeFeature transformed(Offset Function(Offset) f);
}

final class CornerFeature extends ShapeFeature {
  CornerFeature(super.cubics, {required this.convex});

  final bool convex;

  @override
  CornerFeature transformed(Offset Function(Offset) f) =>
      CornerFeature([for (final c in cubics) c.transformed(f)], convex: convex);
}

final class EdgeFeature extends ShapeFeature {
  EdgeFeature(super.cubics);

  @override
  EdgeFeature transformed(Offset Function(Offset) f) => EdgeFeature([for (final c in cubics) c.transformed(f)]);
}

/// graphics-shapes `RoundedCorner`: the rounding of vertex [p1], given its
/// neighbours [p0] and [p2].
final class _RoundedCorner {
  _RoundedCorner(this.p0, this.p1, this.p2, CornerRounding rounding) {
    final v01 = p0 - p1;
    final v21 = p2 - p1;
    final d01 = v01.distance;
    final d21 = v21.distance;
    if (d01 > 0 && d21 > 0) {
      d1 = v01 / d01;
      d2 = v21 / d21;
      cornerRadius = rounding.radius;
      smoothing = rounding.smoothing;
      cosAngle = _dot(d1, d2);
      sinAngle = math.sqrt(1 - _square(cosAngle));
      expectedRoundCut = sinAngle > 1e-3 ? cornerRadius * (cosAngle + 1) / sinAngle : 0;
    } else {
      d1 = Offset.zero;
      d2 = Offset.zero;
      cornerRadius = 0;
      smoothing = 0;
      cosAngle = 0;
      sinAngle = 0;
      expectedRoundCut = 0;
    }
  }

  final Offset p0;
  final Offset p1;
  final Offset p2;
  late final Offset d1;
  late final Offset d2;
  late final double cornerRadius;
  late final double smoothing;
  late final double cosAngle;
  late final double sinAngle;
  late final double expectedRoundCut;

  double get expectedCut => (1 + smoothing) * expectedRoundCut;

  List<ShapeCubic> getCubics(double allowedCut0, double allowedCut1) {
    final allowedCut = math.min(allowedCut0, allowedCut1);
    if (expectedRoundCut < _distanceEpsilon || allowedCut < _distanceEpsilon || cornerRadius < _distanceEpsilon) {
      return [ShapeCubic.straightLine(p1.dx, p1.dy, p1.dx, p1.dy)];
    }
    final actualRoundCut = math.min(allowedCut, expectedRoundCut);
    final actualSmoothing0 = _actualSmoothing(allowedCut0);
    final actualSmoothing1 = _actualSmoothing(allowedCut1);
    final actualR = cornerRadius * actualRoundCut / expectedRoundCut;
    final centerDistance = math.sqrt(_square(actualR) + _square(actualRoundCut));
    final center = p1 + _direction((d1 + d2) / 2) * centerDistance;
    final circleIntersection0 = p1 + d1 * actualRoundCut;
    final circleIntersection2 = p1 + d2 * actualRoundCut;
    final flanking0 = _flankingCurve(
      actualRoundCut,
      actualSmoothing0,
      p1,
      p0,
      circleIntersection0,
      circleIntersection2,
      center,
      actualR,
    );
    final flanking2 = _flankingCurve(
      actualRoundCut,
      actualSmoothing1,
      p1,
      p2,
      circleIntersection2,
      circleIntersection0,
      center,
      actualR,
    ).reverse();
    return [
      flanking0,
      ShapeCubic.circularArc(
        center.dx,
        center.dy,
        flanking0.anchor1X,
        flanking0.anchor1Y,
        flanking2.anchor0X,
        flanking2.anchor0Y,
      ),
      flanking2,
    ];
  }

  double _actualSmoothing(double allowedCut) {
    if (allowedCut > expectedCut) return smoothing;
    if (allowedCut > expectedRoundCut) {
      return smoothing * (allowedCut - expectedRoundCut) / (expectedCut - expectedRoundCut);
    }
    return 0;
  }

  static ShapeCubic _flankingCurve(
    double actualRoundCut,
    double actualSmoothingValues,
    Offset corner,
    Offset sideStart,
    Offset circleSegmentIntersection,
    Offset otherCircleSegmentIntersection,
    Offset circleCenter,
    double actualR,
  ) {
    final sideDirection = _direction(sideStart - corner);
    final curveStart = corner + sideDirection * actualRoundCut * (1 + actualSmoothingValues);
    final p = Offset.lerp(
      circleSegmentIntersection,
      (circleSegmentIntersection + otherCircleSegmentIntersection) / 2,
      actualSmoothingValues,
    )!;
    final curveEnd = circleCenter + _directionVector(p.dx - circleCenter.dx, p.dy - circleCenter.dy) * actualR;
    final circleTangent = _rotate90(curveEnd - circleCenter);
    final anchorEnd = _lineIntersection(sideStart, sideDirection, curveEnd, circleTangent) ?? circleSegmentIntersection;
    final anchorStart = (curveStart + anchorEnd * 2) / 3;
    return ShapeCubic.fromPoints(curveStart, anchorStart, anchorEnd, curveEnd);
  }

  static Offset? _lineIntersection(Offset p0, Offset d0, Offset p1, Offset d1) {
    final rotatedD1 = _rotate90(d1);
    final den = _dot(d0, rotatedD1);
    if (den.abs() < _distanceEpsilon) return null;
    final num = _dot(p1 - p0, rotatedD1);
    if (den.abs() < _distanceEpsilon * num.abs()) return null;
    final k = num / den;
    return p0 + d0 * k;
  }
}

/// A closed shape of rounded corners and the edges between them
/// (graphics-shapes `RoundedPolygon`).
final class RoundedPolygon {
  RoundedPolygon._(List<ShapeFeature> features, this.centerX, this.centerY)
    : features = List<ShapeFeature>.unmodifiable(features),
      cubics = List<ShapeCubic>.unmodifiable(_buildCubics(features, centerX, centerY));

  /// RoundedPolygon(vertices, rounding, perVertexRounding, centerX, centerY).
  /// [vertices] is x0, y0, x1, y1…; a null centre is the vertices' average.
  factory RoundedPolygon.fromVertices(
    List<double> vertices, {
    CornerRounding rounding = CornerRounding.unrounded,
    List<CornerRounding>? perVertexRounding,
    double? centerX,
    double? centerY,
  }) {
    assert(vertices.length >= 6, 'Polygons must have at least 3 vertices');
    assert(vertices.length.isEven, 'The vertices array should have even size');
    final n = vertices.length ~/ 2;
    assert(perVertexRounding == null || perVertexRounding.length == n);
    Offset vertex(int i) => Offset(vertices[i * 2], vertices[i * 2 + 1]);

    final roundedCorners = <_RoundedCorner>[
      for (var i = 0; i < n; i++)
        _RoundedCorner(vertex((i + n - 1) % n), vertex(i), vertex((i + 1) % n), perVertexRounding?[i] ?? rounding),
    ];
    // Per side: does it fit both corners' cuts? Split the space first for
    // the round cuts, then for smoothing if any is left.
    final cutAdjusts = <(double, double)>[
      for (var ix = 0; ix < n; ix++)
        () {
          final expectedRoundCut = roundedCorners[ix].expectedRoundCut + roundedCorners[(ix + 1) % n].expectedRoundCut;
          final expectedCut = roundedCorners[ix].expectedCut + roundedCorners[(ix + 1) % n].expectedCut;
          final v = vertex(ix);
          final next = vertex((ix + 1) % n);
          final sideSize = _distance(v.dx - next.dx, v.dy - next.dy);
          if (expectedRoundCut > sideSize) return (sideSize / expectedRoundCut, 0.0);
          if (expectedCut > sideSize) return (1.0, (sideSize - expectedRoundCut) / (expectedCut - expectedRoundCut));
          return (1.0, 1.0);
        }(),
    ];
    final corners = <List<ShapeCubic>>[];
    for (var i = 0; i < n; i++) {
      final allowedCuts = [0.0, 0.0];
      for (var delta = 0; delta <= 1; delta++) {
        final (roundCutRatio, cutRatio) = cutAdjusts[(i + n - 1 + delta) % n];
        allowedCuts[delta] =
            roundedCorners[i].expectedRoundCut * roundCutRatio +
            (roundedCorners[i].expectedCut - roundedCorners[i].expectedRoundCut) * cutRatio;
      }
      corners.add(roundedCorners[i].getCubics(allowedCuts[0], allowedCuts[1]));
    }
    final features = <ShapeFeature>[];
    for (var i = 0; i < n; i++) {
      final prev = vertex((i + n - 1) % n);
      final curr = vertex(i);
      final next = vertex((i + 1) % n);
      features.add(CornerFeature(corners[i], convex: _clockwise(curr - prev, next - curr)));
      final last = corners[i].last;
      final first = corners[(i + 1) % n].first;
      features.add(
        EdgeFeature([ShapeCubic.straightLine(last.anchor1X, last.anchor1Y, first.anchor0X, first.anchor0Y)]),
      );
    }
    double cx;
    double cy;
    if (centerX == null || centerY == null) {
      var sx = 0.0;
      var sy = 0.0;
      for (var i = 0; i < n; i++) {
        sx += vertices[i * 2];
        sy += vertices[i * 2 + 1];
      }
      cx = sx / n;
      cy = sy / n;
    } else {
      cx = centerX;
      cy = centerY;
    }
    return RoundedPolygon._(features, cx, cy);
  }

  /// RoundedPolygon(numVertices, radius, centerX, centerY, rounding,
  /// perVertexRounding): a regular polygon, first vertex at angle 0.
  factory RoundedPolygon.regular(
    int numVertices, {
    double radius = 1,
    double centerX = 0,
    double centerY = 0,
    CornerRounding rounding = CornerRounding.unrounded,
    List<CornerRounding>? perVertexRounding,
  }) {
    final vertices = <double>[];
    for (var i = 0; i < numVertices; i++) {
      final v = _radialToCartesian(radius, math.pi / numVertices * 2 * i) + Offset(centerX, centerY);
      vertices
        ..add(v.dx)
        ..add(v.dy);
    }
    return RoundedPolygon.fromVertices(
      vertices,
      rounding: rounding,
      perVertexRounding: perVertexRounding,
      centerX: centerX,
      centerY: centerY,
    );
  }

  /// RoundedPolygon.circle: a regular polygon whose corners are rounded by
  /// the full [radius], so its outline is a circle.
  factory RoundedPolygon.circle({int numVertices = 8, double radius = 1, double centerX = 0, double centerY = 0}) {
    assert(numVertices >= 3, 'Circle must have at least three vertices');
    final theta = math.pi / numVertices;
    final polygonRadius = radius / math.cos(theta);
    return RoundedPolygon.regular(
      numVertices,
      rounding: CornerRounding(radius),
      radius: polygonRadius,
      centerX: centerX,
      centerY: centerY,
    );
  }

  /// RoundedPolygon.star: [numVerticesPerRadius] outer and as many inner
  /// vertices, alternating, first outer vertex at angle 0.
  factory RoundedPolygon.star(
    int numVerticesPerRadius, {
    double radius = 1,
    double innerRadius = .5,
    CornerRounding rounding = CornerRounding.unrounded,
    CornerRounding? innerRounding,
    List<CornerRounding>? perVertexRounding,
    double centerX = 0,
    double centerY = 0,
  }) {
    assert(radius > 0 && innerRadius > 0, 'Star radii must both be greater than 0');
    assert(innerRadius < radius, 'innerRadius must be less than radius');
    var pvRounding = perVertexRounding;
    if (pvRounding == null && innerRounding != null) {
      pvRounding = [
        for (var i = 0; i < numVerticesPerRadius; i++) ...[rounding, innerRounding],
      ];
    }
    final vertices = <double>[];
    for (var i = 0; i < numVerticesPerRadius; i++) {
      final outer = _radialToCartesian(radius, math.pi / numVerticesPerRadius * 2 * i);
      final inner = _radialToCartesian(innerRadius, math.pi / numVerticesPerRadius * (2 * i + 1));
      vertices.addAll([outer.dx + centerX, outer.dy + centerY, inner.dx + centerX, inner.dy + centerY]);
    }
    return RoundedPolygon.fromVertices(
      vertices,
      rounding: rounding,
      perVertexRounding: pvRounding,
      centerX: centerX,
      centerY: centerY,
    );
  }

  final List<ShapeFeature> features;
  final double centerX;
  final double centerY;

  /// The closed outline: each cubic's anchor1 is the next one's anchor0.
  final List<ShapeCubic> cubics;

  static List<ShapeCubic> _buildCubics(List<ShapeFeature> features, double centerX, double centerY) {
    final out = <ShapeCubic>[];
    ShapeCubic? firstCubic;
    ShapeCubic? lastCubic;
    List<ShapeCubic>? firstFeatureSplitStart;
    List<ShapeCubic>? firstFeatureSplitEnd;
    // Start the outline in the middle of the first corner's arc.
    if (features.isNotEmpty && features[0].cubics.length == 3) {
      final (start, end) = features[0].cubics[1].split(.5);
      firstFeatureSplitStart = [features[0].cubics[0], start];
      firstFeatureSplitEnd = [end, features[0].cubics[2]];
    }
    for (var i = 0; i <= features.length; i++) {
      final List<ShapeCubic> featureCubics;
      if (i == 0 && firstFeatureSplitEnd != null) {
        featureCubics = firstFeatureSplitEnd;
      } else if (i == features.length) {
        if (firstFeatureSplitStart == null) break;
        featureCubics = firstFeatureSplitStart;
      } else {
        featureCubics = features[i].cubics;
      }
      for (final cubic in featureCubics) {
        if (!cubic.zeroLength) {
          if (lastCubic != null) out.add(lastCubic);
          lastCubic = cubic;
          firstCubic ??= cubic;
        } else if (lastCubic != null) {
          // Skipping several near-zero curves in a row can open a gap; keep
          // the last cubic ending at the latest anchor.
          final p = [...lastCubic.points];
          p[6] = cubic.anchor1X;
          p[7] = cubic.anchor1Y;
          lastCubic = ShapeCubic(p);
        }
      }
    }
    if (lastCubic != null && firstCubic != null) {
      out.add(
        ShapeCubic.of(
          lastCubic.anchor0X,
          lastCubic.anchor0Y,
          lastCubic.control0X,
          lastCubic.control0Y,
          lastCubic.control1X,
          lastCubic.control1Y,
          firstCubic.anchor0X,
          firstCubic.anchor0Y,
        ),
      );
    } else {
      out.add(ShapeCubic.of(centerX, centerY, centerX, centerY, centerX, centerY, centerX, centerY));
    }
    return out;
  }

  RoundedPolygon transformed(Offset Function(Offset) f) {
    final center = f(Offset(centerX, centerY));
    return RoundedPolygon._([for (final feature in features) feature.transformed(f)], center.dx, center.dy);
  }

  /// RoundedPolygon.normalized: scaled and centred into the unit square,
  /// keeping the aspect ratio.
  RoundedPolygon normalized() {
    final bounds = calculateBounds();
    final width = bounds[2] - bounds[0];
    final height = bounds[3] - bounds[1];
    final side = math.max(width, height);
    final offsetX = (side - width) / 2 - bounds[0];
    final offsetY = (side - height) / 2 - bounds[1];
    return transformed((p) => Offset((p.dx + offsetX) / side, (p.dy + offsetY) / side));
  }

  /// calculateBounds(approximate = true): [left, top, right, bottom] of the
  /// control-point hull.
  List<double> calculateBounds() {
    var minX = double.maxFinite;
    var minY = double.maxFinite;
    var maxX = -double.maxFinite;
    var maxY = -double.maxFinite;
    final b = List<double>.filled(4, 0);
    for (final cubic in cubics) {
      cubic._approximateBoundsInto(b);
      minX = math.min(minX, b[0]);
      minY = math.min(minY, b[1]);
      maxX = math.max(maxX, b[2]);
      maxY = math.max(maxY, b[3]);
    }
    return [minX, minY, maxX, maxY];
  }

  /// calculateMaxBounds: the square around the centre that holds the
  /// shape at any rotation (anchors and cubic midpoints).
  List<double> calculateMaxBounds() {
    var maxDistSquared = 0.0;
    for (final cubic in cubics) {
      final anchorDistance = _distanceSquared(cubic.anchor0X - centerX, cubic.anchor0Y - centerY);
      final middle = cubic.pointOnCurve(.5);
      final middleDistance = _distanceSquared(middle.dx - centerX, middle.dy - centerY);
      maxDistSquared = math.max(maxDistSquared, math.max(anchorDistance, middleDistance));
    }
    final d = math.sqrt(maxDistSquared);
    return [centerX - d, centerY - d, centerX + d, centerY + d];
  }

  /// The outline as a Flutter [Path] (same coordinates).
  Path toPath([Path? path]) => _cubicsToPath(cubics, path);
}

Path _cubicsToPath(List<ShapeCubic> cubics, [Path? reuse]) {
  final path = (reuse ?? Path())..reset();
  if (cubics.isEmpty) return path;
  path.moveTo(cubics.first.anchor0X, cubics.first.anchor0Y);
  for (final c in cubics) {
    path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y);
  }
  return path..close();
}

// ── Morph (Morph.kt, PolygonMeasure.kt, FeatureMapping.kt, FloatMapping.kt) ──

@immutable
final class _ProgressableFeature {
  const _ProgressableFeature(this.progress, this.feature);
  final double progress;
  final ShapeFeature feature;
}

/// LengthMeasurer: arc length approximated by 3 chords per cubic.
abstract final class _LengthMeasurer {
  static const int _segments = 3;

  static double measureCubic(ShapeCubic c) => _closestProgressTo(c, double.infinity).$2;

  static double findCubicCutPoint(ShapeCubic c, double m) => _closestProgressTo(c, m).$1;

  static (double, double) _closestProgressTo(ShapeCubic cubic, double threshold) {
    var total = 0.0;
    var remainder = threshold;
    var prev = Offset(cubic.anchor0X, cubic.anchor0Y);
    for (var i = 1; i <= _segments; i++) {
      final progress = i / _segments;
      final point = cubic.pointOnCurve(progress);
      final segment = (point - prev).distance;
      if (segment >= remainder) {
        return (progress - (1.0 - remainder / segment) / _segments, threshold);
      }
      remainder -= segment;
      total += segment;
      prev = point;
    }
    return (1.0, total);
  }
}

final class _MeasuredCubic {
  _MeasuredCubic(this.cubic, this.startOutlineProgress, this.endOutlineProgress)
    : assert(endOutlineProgress >= startOutlineProgress),
      measuredSize = _LengthMeasurer.measureCubic(cubic);

  final ShapeCubic cubic;
  final double measuredSize;
  double startOutlineProgress;
  double endOutlineProgress;

  (_MeasuredCubic, _MeasuredCubic) cutAtProgress(double cutOutlineProgress) {
    final bounded = cutOutlineProgress.clamp(startOutlineProgress, endOutlineProgress);
    final outlineProgressSize = endOutlineProgress - startOutlineProgress;
    final progressFromStart = bounded - startOutlineProgress;
    final relativeProgress = progressFromStart / outlineProgressSize;
    final t = _LengthMeasurer.findCubicCutPoint(cubic, relativeProgress * measuredSize);
    final (c1, c2) = cubic.split(t);
    return (_MeasuredCubic(c1, startOutlineProgress, bounded), _MeasuredCubic(c2, bounded, endOutlineProgress));
  }
}

final class _MeasuredPolygon {
  _MeasuredPolygon._(this.features, List<ShapeCubic> cubics, List<double> outlineProgress)
    : assert(outlineProgress.length == cubics.length + 1),
      assert(outlineProgress.first == 0),
      assert(outlineProgress.last == 1) {
    var startOutlineProgress = 0.0;
    for (var index = 0; index < cubics.length; index++) {
      // Drop "empty" cubics.
      if (outlineProgress[index + 1] - outlineProgress[index] > _distanceEpsilon) {
        this.cubics.add(_MeasuredCubic(cubics[index], startOutlineProgress, outlineProgress[index + 1]));
        startOutlineProgress = outlineProgress[index + 1];
      }
    }
    // Empty cubics may have been dropped at the end: the last one ends at 1.
    this.cubics.last.endOutlineProgress = 1;
  }

  factory _MeasuredPolygon.measure(RoundedPolygon polygon) {
    final cubics = <ShapeCubic>[];
    final featureToCubic = <(ShapeFeature, int)>[];
    for (final feature in polygon.features) {
      for (var cubicIndex = 0; cubicIndex < feature.cubics.length; cubicIndex++) {
        if (feature is CornerFeature && cubicIndex == feature.cubics.length ~/ 2) {
          featureToCubic.add((feature, cubics.length));
        }
        cubics.add(feature.cubics[cubicIndex]);
      }
    }
    final measures = <double>[0];
    for (final cubic in cubics) {
      measures.add(measures.last + _LengthMeasurer.measureCubic(cubic));
    }
    final totalMeasure = measures.last;
    final outlineProgress = [for (final m in measures) m / totalMeasure];
    final features = [
      for (final (feature, ix) in featureToCubic)
        _ProgressableFeature(_positiveModulo((outlineProgress[ix] + outlineProgress[ix + 1]) / 2, 1), feature),
    ];
    return _MeasuredPolygon._(features, cubics, outlineProgress);
  }

  final List<_ProgressableFeature> features;
  final List<_MeasuredCubic> cubics = [];

  _MeasuredCubic? operator [](int index) => index >= 0 && index < cubics.length ? cubics[index] : null;

  int get length => cubics.length;

  /// Starts the outline at [cuttingPoint] instead of 0.
  _MeasuredPolygon cutAndShift(double cuttingPoint) {
    assert(cuttingPoint >= 0 && cuttingPoint <= 1, 'Cutting point is expected to be between 0 and 1');
    if (cuttingPoint < _distanceEpsilon) return this;
    final targetIndex = cubics.indexWhere(
      (it) => cuttingPoint >= it.startOutlineProgress && cuttingPoint <= it.endOutlineProgress,
    );
    final target = cubics[targetIndex];
    final (b1, b2) = target.cutAtProgress(cuttingPoint);
    final retCubics = <ShapeCubic>[b2.cubic];
    for (var i = 1; i < cubics.length; i++) {
      retCubics.add(cubics[(i + targetIndex) % cubics.length].cubic);
    }
    retCubics.add(b1.cubic);
    final retOutlineProgress = <double>[
      for (var index = 0; index < cubics.length + 2; index++)
        if (index == 0)
          0
        else if (index == cubics.length + 1)
          1
        else
          _positiveModulo(cubics[(targetIndex + index - 1) % cubics.length].endOutlineProgress - cuttingPoint, 1),
    ];
    final newFeatures = [
      for (final f in features) _ProgressableFeature(_positiveModulo(f.progress - cuttingPoint, 1), f.feature),
    ];
    return _MeasuredPolygon._(newFeatures, retCubics, retOutlineProgress);
  }
}

/// FloatMapping.progressInRange.
bool _progressInRange(double progress, double progressFrom, double progressTo) => progressTo >= progressFrom
    ? progress >= progressFrom && progress <= progressTo
    : progress >= progressFrom || progress <= progressTo;

/// FloatMapping.progressDistance: distance on the 0–1 loop.
double _progressDistance(double p1, double p2) {
  final it = (p1 - p2).abs();
  return math.min(it, 1 - it);
}

/// FloatMapping.linearMap.
double _linearMap(List<double> xValues, List<double> yValues, double x) {
  assert(x >= 0 && x <= 1, 'Invalid progress: $x');
  final n = xValues.length;
  var segmentStartIndex = -1;
  for (var i = 0; i < n; i++) {
    if (_progressInRange(x, xValues[i], xValues[(i + 1) % n])) {
      segmentStartIndex = i;
      break;
    }
  }
  final segmentEndIndex = (segmentStartIndex + 1) % n;
  final segmentSizeX = _positiveModulo(xValues[segmentEndIndex] - xValues[segmentStartIndex], 1);
  final segmentSizeY = _positiveModulo(yValues[segmentEndIndex] - yValues[segmentStartIndex], 1);
  final positionInSegment = segmentSizeX < 0.001
      ? 0.5
      : _positiveModulo(x - xValues[segmentStartIndex], 1) / segmentSizeX;
  return _positiveModulo(yValues[segmentStartIndex] + segmentSizeY * positionInSegment, 1);
}

/// DoubleMapper: a piecewise-linear, wrapping map between two outlines'
/// progress.
final class _DoubleMapper {
  _DoubleMapper(List<(double, double)> mappings)
    : _source = [for (final m in mappings) m.$1],
      _target = [for (final m in mappings) m.$2];

  final List<double> _source;
  final List<double> _target;

  double map(double x) => _linearMap(_source, _target, x);

  double mapBack(double x) => _linearMap(_target, _source, x);
}

const List<(double, double)> _identityMapping = [(0, 0), (0.5, 0.5)];

/// FeatureMapping.featureRepresentativePoint.
Offset _featureRepresentativePoint(ShapeFeature feature) => Offset(
  (feature.cubics.first.anchor0X + feature.cubics.last.anchor1X) / 2,
  (feature.cubics.first.anchor0Y + feature.cubics.last.anchor1Y) / 2,
);

/// FeatureMapping.featureDistSquared: corners of different convexity never pair.
double _featureDistSquared(ShapeFeature f1, ShapeFeature f2) {
  if (f1 is CornerFeature && f2 is CornerFeature && f1.convex != f2.convex) return double.maxFinite;
  final d = _featureRepresentativePoint(f1) - _featureRepresentativePoint(f2);
  return d.distanceSquared;
}

/// FeatureMapping.featureMapper: pairs each outline's corners with the
/// nearest compatible corner of the other.
_DoubleMapper _featureMapper(List<_ProgressableFeature> features1, List<_ProgressableFeature> features2) {
  final filtered1 = [
    for (final f in features1)
      if (f.feature is CornerFeature) f,
  ];
  final filtered2 = [
    for (final f in features2)
      if (f.feature is CornerFeature) f,
  ];
  return _DoubleMapper(_doMapping(filtered1, filtered2));
}

List<(double, double)> _doMapping(List<_ProgressableFeature> features1, List<_ProgressableFeature> features2) {
  final distanceVertexList = <(double, _ProgressableFeature, _ProgressableFeature)>[];
  for (final f1 in features1) {
    for (final f2 in features2) {
      final d = _featureDistSquared(f1.feature, f2.feature);
      if (d != double.maxFinite) distanceVertexList.add((d, f1, f2));
    }
  }
  // Stable sort by distance (Kotlin sortedBy is stable; List.sort is not).
  final indexed = [for (var i = 0; i < distanceVertexList.length; i++) (i, distanceVertexList[i])];
  indexed.sort((a, b) {
    final c = a.$2.$1.compareTo(b.$2.$1);
    return c != 0 ? c : a.$1.compareTo(b.$1);
  });
  final sorted = [for (final e in indexed) e.$2];
  if (sorted.isEmpty) return _identityMapping;
  if (sorted.length == 1) {
    final (_, f1, f2) = sorted.first;
    return [(f1.progress, f2.progress), ((f1.progress + .5) % 1, (f2.progress + .5) % 1)];
  }
  final helper = _MappingHelper();
  for (final (_, f1, f2) in sorted) {
    helper.addMapping(f1, f2);
  }
  return helper.mapping;
}

final class _MappingHelper {
  final List<(double, double)> mapping = [];
  final Set<_ProgressableFeature> _usedF1 = {};
  final Set<_ProgressableFeature> _usedF2 = {};

  void addMapping(_ProgressableFeature f1, _ProgressableFeature f2) {
    if (_usedF1.contains(f1) || _usedF2.contains(f2)) return;
    // binarySearchBy(f1.progress) { it.first }
    var lo = 0;
    var hi = mapping.length - 1;
    var index = -1;
    while (lo <= hi) {
      final mid = (lo + hi) >> 1;
      final cmp = mapping[mid].$1.compareTo(f1.progress);
      if (cmp < 0) {
        lo = mid + 1;
      } else if (cmp > 0) {
        hi = mid - 1;
      } else {
        index = mid;
        break;
      }
    }
    if (index >= 0) throw ArgumentError("There can't be two features with the same progress");
    final insertionIndex = lo;
    final n = mapping.length;
    if (n >= 1) {
      final (before1, before2) = mapping[(insertionIndex + n - 1) % n];
      final (after1, after2) = mapping[insertionIndex % n];
      if (_progressDistance(f1.progress, before1) < _distanceEpsilon ||
          _progressDistance(f1.progress, after1) < _distanceEpsilon ||
          _progressDistance(f2.progress, before2) < _distanceEpsilon ||
          _progressDistance(f2.progress, after2) < _distanceEpsilon) {
        return;
      }
      if (n > 1 && !_progressInRange(f2.progress, before2, after2)) return;
    }
    mapping.insert(insertionIndex, (f1.progress, f2.progress));
    _usedF1.add(f1);
    _usedF2.add(f2);
  }
}

/// A morph between two [RoundedPolygon]s (graphics-shapes `Morph`): their
/// outlines are cut into matching cubic pairs once; [asCubics] interpolates.
final class ShapeMorph {
  ShapeMorph(this.start, this.end) : _match = _matchPolygons(start, end);

  final RoundedPolygon start;
  final RoundedPolygon end;
  final List<(ShapeCubic, ShapeCubic)> _match;

  /// Number of cubic pairs the outlines were cut into.
  int get cubicCount => _match.length;

  /// The outline at [progress] (0 = [start], 1 = [end]; beyond extrapolates,
  /// as a spring overshoot does on Android).
  List<ShapeCubic> asCubics(double progress) {
    final out = <ShapeCubic>[];
    ShapeCubic? firstCubic;
    ShapeCubic? lastCubic;
    for (final (a, b) in _match) {
      final cubic = ShapeCubic([for (var i = 0; i < 8; i++) _interpolate(a.points[i], b.points[i], progress)]);
      firstCubic ??= cubic;
      if (lastCubic != null) out.add(lastCubic);
      lastCubic = cubic;
    }
    if (lastCubic != null && firstCubic != null) {
      out.add(
        ShapeCubic.of(
          lastCubic.anchor0X,
          lastCubic.anchor0Y,
          lastCubic.control0X,
          lastCubic.control0Y,
          lastCubic.control1X,
          lastCubic.control1Y,
          firstCubic.anchor0X,
          firstCubic.anchor0Y,
        ),
      );
    }
    return out;
  }

  Path toPath(double progress, [Path? path]) => _cubicsToPath(asCubics(progress), path);

  static List<(ShapeCubic, ShapeCubic)> _matchPolygons(RoundedPolygon p1, RoundedPolygon p2) {
    final measuredPolygon1 = _MeasuredPolygon.measure(p1);
    final measuredPolygon2 = _MeasuredPolygon.measure(p2);
    final doubleMapper = _featureMapper(measuredPolygon1.features, measuredPolygon2.features);
    final polygon2CutPoint = doubleMapper.map(0);
    final bs1 = measuredPolygon1;
    final bs2 = measuredPolygon2.cutAndShift(polygon2CutPoint);

    final ret = <(ShapeCubic, ShapeCubic)>[];
    var i1 = 0;
    var i2 = 0;
    var b1 = bs1[i1++];
    var b2 = bs2[i2++];
    while (b1 != null && b2 != null) {
      final b1a = i1 == bs1.length ? 1.0 : b1.endOutlineProgress;
      final b2a = i2 == bs2.length
          ? 1.0
          : doubleMapper.mapBack(_positiveModulo(b2.endOutlineProgress + polygon2CutPoint, 1));
      final minb = math.min(b1a, b2a);
      _MeasuredCubic seg1;
      _MeasuredCubic? newb1;
      if (b1a > minb + _angleEpsilon) {
        (seg1, newb1) = b1.cutAtProgress(minb);
      } else {
        seg1 = b1;
        newb1 = bs1[i1++];
      }
      _MeasuredCubic seg2;
      _MeasuredCubic? newb2;
      if (b2a > minb + _angleEpsilon) {
        (seg2, newb2) = b2.cutAtProgress(_positiveModulo(doubleMapper.map(minb) - polygon2CutPoint, 1));
      } else {
        seg2 = b2;
        newb2 = bs2[i2++];
      }
      ret.add((seg1.cubic, seg2.cubic));
      b1 = newb1;
      b2 = newb2;
    }
    assert(b1 == null && b2 == null, 'Expected both Polygon\'s Cubic to be fully matched');
    return ret;
  }
}

// ───────────────────────────── MaterialShapes ─────────────────────────────

/// MaterialShapes.Companion.PointNRound.
typedef _PointNRound = (Offset o, CornerRounding r);

/// The MaterialShapes the loading indicator uses, each `.normalized()` as
/// the Compose getters return them. Values are MaterialShapes 1.5.0-alpha27.
abstract final class MaterialShapes {
  static const _cornerRound15 = CornerRounding(.15);
  static const _cornerRound50 = CornerRounding(.5);

  /// MaterialShapes.rotateNeg45 / rotateNeg90 (Matrix.rotateZ about 0,0).
  static Offset Function(Offset) _rotateZ(double degrees) {
    final a = degrees * math.pi / 180;
    final c = math.cos(a);
    final s = math.sin(a);
    return (p) => Offset(c * p.dx - s * p.dy, s * p.dx + c * p.dy);
  }

  static final RoundedPolygon circle = RoundedPolygon.circle(numVertices: 10).normalized();

  static final RoundedPolygon oval = RoundedPolygon.circle()
      .transformed((p) => Offset(p.dx, p.dy * 0.64))
      .transformed(_rotateZ(-45))
      .normalized();

  static final RoundedPolygon pill = _customPolygon(
    [
      (const Offset(0.961, 0.039), const CornerRounding(0.426)),
      (const Offset(1.001, 0.428), CornerRounding.unrounded),
      (const Offset(1.0, 0.609), const CornerRounding(1)),
    ],
    reps: 2,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon pentagon = _customPolygon(
    [
      (const Offset(0.5, -0.009), const CornerRounding(0.172)),
      (const Offset(1.03, 0.365), const CornerRounding(0.164)),
      (const Offset(0.828, 0.97), const CornerRounding(0.169)),
    ],
    reps: 1,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon sunny = RoundedPolygon.star(8, innerRadius: 0.8, rounding: _cornerRound15).normalized();

  static final RoundedPolygon cookie4Sided = _customPolygon([
    (const Offset(1.237, 1.236), const CornerRounding(0.258)),
    (const Offset(0.5, 0.918), const CornerRounding(0.233)),
  ], reps: 4).normalized();

  static final RoundedPolygon cookie9Sided = RoundedPolygon.star(
    9,
    innerRadius: 0.8,
    rounding: _cornerRound50,
  ).transformed(_rotateZ(-90)).normalized();

  static final RoundedPolygon softBurst = _customPolygon([
    (const Offset(0.193, 0.277), const CornerRounding(0.053)),
    (const Offset(0.176, 0.055), const CornerRounding(0.053)),
  ], reps: 10).normalized();

  /// MaterialShapes.customPolygon: [pnr] repeated [reps] times around
  /// [center] (mirrored on alternate repeats when [mirroring]).
  static RoundedPolygon _customPolygon(
    List<_PointNRound> pnr, {
    required int reps,
    Offset center = const Offset(0.5, 0.5),
    bool mirroring = false,
  }) {
    final actualList = _doRepeat(pnr, reps, center, mirroring);
    return RoundedPolygon.fromVertices(
      [
        for (final p in actualList) ...[p.$1.dx, p.$1.dy],
      ],
      perVertexRounding: [for (final p in actualList) p.$2],
      centerX: center.dx,
      centerY: center.dy,
    );
  }

  static List<_PointNRound> _doRepeat(List<_PointNRound> list, int reps, Offset center, bool mirroring) {
    if (mirroring) {
      final out = <_PointNRound>[];
      final angles = [for (final p in list) _angleDegrees(p.$1 - center)];
      final distances = [for (final p in list) (p.$1 - center).distance];
      final actualReps = reps * 2;
      final sectionAngle = 360 / actualReps;
      for (var it = 0; it < actualReps; it++) {
        for (var index = 0; index < list.length; index++) {
          final i = it.isEven ? index : list.length - 1 - index;
          if (i > 0 || it.isEven) {
            final a = _toRadians(
              sectionAngle * it + (it.isEven ? angles[i] : sectionAngle - angles[i] + 2 * angles[0]),
            );
            final finalPoint = Offset(math.cos(a), math.sin(a)) * distances[i] + center;
            out.add((finalPoint, list[i].$2));
          }
        }
      }
      return out;
    }
    final np = list.length;
    return [
      for (var it = 0; it < np * reps; it++)
        (_rotateDegrees(list[it % np].$1, (it ~/ np) * 360 / reps, center), list[it % np].$2),
    ];
  }

  static double _toRadians(double degrees) => degrees / 360 * 2 * math.pi;

  static double _angleDegrees(Offset p) => math.atan2(p.dy, p.dx) * 180 / math.pi;

  static Offset _rotateDegrees(Offset p, double angle, Offset center) {
    final a = _toRadians(angle);
    final off = p - center;
    return center + Offset(off.dx * math.cos(a) - off.dy * math.sin(a), off.dx * math.sin(a) + off.dy * math.cos(a));
  }
}

// ──────────────────────────── LoadingIndicator ────────────────────────────

/// LoadingIndicatorDefaults + tokens/LoadingIndicatorTokens + the private
/// constants of LoadingIndicator.kt.
abstract final class LoadingIndicatorDefaults {
  /// LoadingIndicatorTokens.ContainerWidth / ContainerHeight (dp).
  static const double containerWidth = 48;
  static const double containerHeight = 48;

  /// LoadingIndicatorTokens.ActiveSize: the shape's size inside the container (dp).
  static const double indicatorSize = 38;

  /// ActiveIndicatorScale = IndicatorSize / min(ContainerWidth, ContainerHeight).
  static const double activeIndicatorScale = indicatorSize / containerWidth;

  /// GlobalRotationDurationMillis: one linear full turn.
  static const Duration globalRotationDuration = Duration(milliseconds: 4666);

  /// MorphIntervalMillis: a new morph starts this often.
  static const Duration morphInterval = Duration(milliseconds: 650);

  /// The morph spring: spring(dampingRatio 0.6, stiffness 200, visibilityThreshold 0.1).
  static const double morphDampingRatio = 0.6;
  static const double morphStiffness = 200;
  static const double morphVisibilityThreshold = 0.1;

  /// FullRotation / QuarterRotation (degrees): each morph also turns the shape a quarter.
  static const double fullRotation = 360;
  static const double quarterRotation = 90;

  /// IndeterminateIndicatorPolygons, in order.
  static final List<RoundedPolygon> indeterminateIndicatorPolygons = [
    MaterialShapes.softBurst,
    MaterialShapes.cookie9Sided,
    MaterialShapes.pentagon,
    MaterialShapes.pill,
    MaterialShapes.sunny,
    MaterialShapes.cookie4Sided,
    MaterialShapes.oval,
  ];

  /// morphSequence(polygons, circularSequence = true): normalized pairs,
  /// the last morphing back into the first.
  static List<ShapeMorph> morphSequence(List<RoundedPolygon> polygons, {required bool circular}) => [
    for (var i = 0; i < polygons.length; i++)
      if (i + 1 < polygons.length)
        ShapeMorph(polygons[i].normalized(), polygons[i + 1].normalized())
      else if (circular)
        ShapeMorph(polygons[i].normalized(), polygons[0].normalized()),
  ];

  /// calculateScaleFactor: shrink so every shape fits the container at any rotation.
  static double calculateScaleFactor(List<RoundedPolygon> polygons) {
    var scaleFactor = 1.0;
    for (final polygon in polygons) {
      final bounds = polygon.calculateBounds();
      final maxBounds = polygon.calculateMaxBounds();
      final scaleX = (bounds[2] - bounds[0]) / (maxBounds[2] - maxBounds[0]);
      final scaleY = (bounds[3] - bounds[1]) / (maxBounds[3] - maxBounds[1]);
      scaleFactor = math.min(scaleFactor, math.max(scaleX, scaleY));
    }
    return scaleFactor;
  }

  static final List<ShapeMorph> indeterminateMorphs = morphSequence(indeterminateIndicatorPolygons, circular: true);

  static final double indeterminateScaleFactor =
      calculateScaleFactor(indeterminateIndicatorPolygons) * activeIndicatorScale;

  /// How long Compose plays the morph spring before snapping to 1:
  /// SpringEstimation.estimateAnimationDurationMillis (under-damped case)
  /// with displacement and velocity in visibility-threshold units.
  static final Duration morphSpringDuration = () {
    const p0 = 1 / morphVisibilityThreshold; // |0 - 1| / threshold
    const v0 = 0.0;
    final omega = math.sqrt(morphStiffness);
    final r = -morphDampingRatio * omega;
    final imaginary = math.sqrt(4 * morphStiffness - _square(2 * morphDampingRatio * omega)) * 0.5;
    const c1 = p0;
    final c2 = (v0 - r * c1) / imaginary;
    final c = math.sqrt(c1 * c1 + c2 * c2);
    final seconds = math.log(1 / c) / r;
    return Duration(milliseconds: (seconds * 1000).truncate());
  }();

  /// The morph spring's value [elapsed] after it starts from 0 toward 1
  /// (Compose SpringSimulation, under-damped), 1 once its duration is over.
  static double morphProgressAt(Duration elapsed) {
    if (elapsed >= morphSpringDuration) return 1;
    final t = elapsed.inMicroseconds / Duration.microsecondsPerSecond;
    final omega = math.sqrt(morphStiffness);
    final dampedFreq = omega * math.sqrt(1 - morphDampingRatio * morphDampingRatio);
    const displacement = -1.0; // start - target
    final cosCoeff = displacement;
    final sinCoeff = (morphDampingRatio * omega * displacement) / dampedFreq;
    final x =
        math.exp(-morphDampingRatio * omega * t) *
        (cosCoeff * math.cos(dampedFreq * t) + sinCoeff * math.sin(dampedFreq * t));
    return 1 + x;
  }
}

/// One frame of the indeterminate indicator.
@immutable
final class LoadingIndicatorFrame {
  const LoadingIndicatorFrame({required this.morphIndex, required this.morphProgress, required this.rotationDegrees});

  final int morphIndex;
  final double morphProgress;
  final double rotationDegrees;

  /// The frame [elapsed] after the indicator appears. A morph starts every
  /// [LoadingIndicatorDefaults.morphInterval]; it springs 0 → 1 (with
  /// overshoot) and turns a quarter; once settled the next morph starts from
  /// the same shape and angle, so holding at 1 equals Compose's "advance the
  /// index, snap to 0, add 90°". The whole shape also turns once per 4666 ms.
  factory LoadingIndicatorFrame.at(Duration elapsed, {int morphCount = 7}) {
    final interval = LoadingIndicatorDefaults.morphInterval.inMicroseconds;
    final us = elapsed.inMicroseconds;
    final k = us ~/ interval;
    final local = Duration(microseconds: us - k * interval);
    final progress = LoadingIndicatorDefaults.morphProgressAt(local);
    final targetAngle = (LoadingIndicatorDefaults.quarterRotation * (k + 1)) % LoadingIndicatorDefaults.fullRotation;
    final rotationPeriod = LoadingIndicatorDefaults.globalRotationDuration.inMicroseconds;
    final global = (us % rotationPeriod) / rotationPeriod * LoadingIndicatorDefaults.fullRotation;
    return LoadingIndicatorFrame(
      morphIndex: k % morphCount,
      morphProgress: progress,
      rotationDegrees: progress * LoadingIndicatorDefaults.quarterRotation + targetAngle + global,
    );
  }

  /// The resting frame shown with reduced motion: the first shape, unmoved.
  static const still = LoadingIndicatorFrame(
    morphIndex: 0,
    morphProgress: 0,
    rotationDegrees: LoadingIndicatorDefaults.quarterRotation,
  );
}

enum _IndicatorPalette { material, yoin }

/// M3 Expressive indeterminate LoadingIndicator / ContainedLoadingIndicator:
/// a shape that keeps turning while it springs through SoftBurst, 9-sided
/// cookie, pentagon, pill, sunny, 4-sided cookie and oval.
///
/// [contained] draws the 48 dp round container behind it (the shape fills
/// 38/48 of it); the whole thing scales with [size], like Compose's
/// `Modifier.size` on the indicator. Colours default to Material's
/// (contained: primaryContainer / onPrimaryContainer; bare: primary).
/// [ExpressiveLoadingIndicator.yoin] is Android Yoin's `YoinLoadingIndicator`:
/// contained, secondaryContainer / onSecondaryContainer.
class ExpressiveLoadingIndicator extends StatefulWidget {
  const ExpressiveLoadingIndicator({
    super.key,
    this.size = LoadingIndicatorDefaults.containerWidth,
    this.contained = false,
    this.indicatorColor,
    this.containerColor,
    this.semanticsLabel,
  }) : _palette = _IndicatorPalette.material;

  /// YoinLoadingIndicator(size = 48.dp).
  const ExpressiveLoadingIndicator.yoin({
    super.key,
    this.size = LoadingIndicatorDefaults.containerWidth,
    this.semanticsLabel,
  }) : contained = true,
       indicatorColor = null,
       containerColor = null,
       _palette = _IndicatorPalette.yoin;

  final double size;
  final bool contained;
  final Color? indicatorColor;
  final Color? containerColor;
  final String? semanticsLabel;
  final _IndicatorPalette _palette;

  @override
  State<ExpressiveLoadingIndicator> createState() => _ExpressiveLoadingIndicatorState();
}

class _ExpressiveLoadingIndicatorState extends State<ExpressiveLoadingIndicator> with SingleTickerProviderStateMixin {
  final ValueNotifier<LoadingIndicatorFrame> _frame = ValueNotifier(LoadingIndicatorFrame.still);
  late final Ticker _ticker = createTicker((elapsed) => _frame.value = LoadingIndicatorFrame.at(elapsed));
  bool _reduceMotion = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _reduceMotion = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    if (_reduceMotion) {
      if (_ticker.isActive) _ticker.stop();
      _frame.value = LoadingIndicatorFrame.still;
    } else if (!_ticker.isActive) {
      _ticker.start();
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    _frame.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final Color ink;
    final Color? container;
    switch (widget._palette) {
      case _IndicatorPalette.yoin:
        ink = colors.onSecondaryContainer;
        container = colors.secondaryContainer;
      case _IndicatorPalette.material:
        ink = widget.indicatorColor ?? (widget.contained ? colors.onPrimaryContainer : colors.primary);
        container = widget.contained ? (widget.containerColor ?? colors.primaryContainer) : null;
    }
    return Semantics(
      label: widget.semanticsLabel,
      child: RepaintBoundary(
        child: SizedBox.square(
          dimension: widget.size,
          child: CustomPaint(
            painter: LoadingIndicatorPainter(frame: _frame, indicatorColor: ink, containerColor: container),
          ),
        ),
      ),
    );
  }
}

/// Paints one [LoadingIndicatorFrame]: the container (CornerFull), then the
/// current morph, scaled by the indeterminate scale factor, centred on its
/// bounds and rotated about the centre (LoadingIndicatorImpl + processPath).
class LoadingIndicatorPainter extends CustomPainter {
  LoadingIndicatorPainter({required this.frame, required this.indicatorColor, this.containerColor})
    : super(repaint: frame);

  final ValueListenable<LoadingIndicatorFrame> frame;
  final Color indicatorColor;
  final Color? containerColor;

  final Path _path = Path();

  @override
  void paint(Canvas canvas, Size size) {
    if (size.isEmpty) return;
    if (containerColor != null) {
      final rect = Offset.zero & size;
      canvas.drawRRect(
        RRect.fromRectAndRadius(rect, Radius.circular(size.shortestSide / 2)),
        Paint()..color = containerColor!,
      );
    }
    final f = frame.value;
    final morphs = LoadingIndicatorDefaults.indeterminateMorphs;
    final cubics = morphs[f.morphIndex % morphs.length].asCubics(f.morphProgress);
    final scale = LoadingIndicatorDefaults.indeterminateScaleFactor;
    final sx = size.width * scale;
    final sy = size.height * scale;
    // Path.getBounds on Android is the control-point hull; centre on it.
    var minX = double.maxFinite, minY = double.maxFinite, maxX = -double.maxFinite, maxY = -double.maxFinite;
    for (final c in cubics) {
      for (var i = 0; i < 8; i += 2) {
        minX = math.min(minX, c.points[i] * sx);
        maxX = math.max(maxX, c.points[i] * sx);
        minY = math.min(minY, c.points[i + 1] * sy);
        maxY = math.max(maxY, c.points[i + 1] * sy);
      }
    }
    final center = size.center(Offset.zero);
    final dx = center.dx - (minX + maxX) / 2;
    final dy = center.dy - (minY + maxY) / 2;
    _path.reset();
    _path.moveTo(cubics.first.anchor0X * sx + dx, cubics.first.anchor0Y * sy + dy);
    for (final c in cubics) {
      _path.cubicTo(
        c.control0X * sx + dx,
        c.control0Y * sy + dy,
        c.control1X * sx + dx,
        c.control1Y * sy + dy,
        c.anchor1X * sx + dx,
        c.anchor1Y * sy + dy,
      );
    }
    _path.close();
    canvas
      ..save()
      ..translate(center.dx, center.dy)
      ..rotate(f.rotationDegrees * math.pi / 180)
      ..translate(-center.dx, -center.dy)
      ..drawPath(_path, Paint()..color = indicatorColor)
      ..restore();
  }

  @override
  bool shouldRepaint(LoadingIndicatorPainter old) =>
      old.frame != frame || old.indicatorColor != indicatorColor || old.containerColor != containerColor;
}
