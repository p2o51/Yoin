import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/painting.dart';

// The M3 Expressive shapes Settings uses, built the way androidx
// graphics-shapes builds them (RoundedPolygon + RoundedCorner, smoothing
// included) from the numbers in material3 1.5.0-alpha27's MaterialShapes
// (read from the compiled class). Every shape is normalised into the unit
// square, centred, as MaterialShapes' public accessors do.

/// A vertex's rounding: [radius] in the shape's own units, [smoothing] 0..1.
final class CornerRounding {
  const CornerRounding(this.radius, [this.smoothing = 0]);
  final double radius;
  final double smoothing;

  static const unrounded = CornerRounding(0);
}

// MaterialShapes' shared roundings.
const _round15 = CornerRounding(0.15);
const _round20 = CornerRounding(0.2);
const _round50 = CornerRounding(0.5);
const _round100 = CornerRounding(1);

const double _distanceEpsilon = 1e-4;

/// Cookie9Sided: star(9, inner 0.8, rounding 0.5), turned -90°.
final Path cookie9Sided = _normalized(_transform(_star(9, 0.8, _round50), rotateDegrees: -90));

/// Cookie12Sided: star(12, inner 0.8, rounding 0.5), turned -90° (the wide
/// Settings' empty pane).
final Path cookie12Sided = _normalized(_transform(_star(12, 0.8, _round50), rotateDegrees: -90));

/// Pentagon: custom(0.5, -0.009 rounded 0.172), five times round (the tip pages' sixth silhouette).
final Path pentagon = _normalized(_custom(const [_Pnr(Offset(0.5, -0.009), CornerRounding(0.172))], reps: 5));

/// The eight avatar silhouettes (AccountIdentity.kt avatarPolygon), by slot.
final List<Path> avatarShapes = [
  // 0: Cookie6Sided.
  _normalized(_custom(const [
    _Pnr(Offset(0.723, 0.884), CornerRounding(0.394)),
    _Pnr(Offset(0.5, 1.099), CornerRounding(0.398)),
  ], reps: 6)),
  // 1: Clover4Leaf.
  _normalized(_custom(const [
    _Pnr(Offset(0.5, 0.074)),
    _Pnr(Offset(0.725, -0.099), CornerRounding(0.476)),
  ], reps: 4, mirroring: true)),
  // 2: Gem.
  _normalized(_custom(const [
    _Pnr(Offset(0.499, 1.023), CornerRounding(0.241, 0.778)),
    _Pnr(Offset(-0.005, 0.792), CornerRounding(0.208)),
    _Pnr(Offset(0.073, 0.258), CornerRounding(0.228)),
    _Pnr(Offset(0.433, -0.0), CornerRounding(0.491)),
  ], reps: 1, mirroring: true)),
  // 3: Arch: a diamond rounded 100/100/20/20, turned -135°.
  _normalized(_transform(
    _polygon(_regular(4, 1), const [_round100, _round100, _round20, _round20]),
    rotateDegrees: -135,
  )),
  // 4: Slanted.
  _normalized(_custom(const [
    _Pnr(Offset(0.926, 0.97), CornerRounding(0.189, 0.811)),
    _Pnr(Offset(-0.021, 0.967), CornerRounding(0.187, 0.057)),
  ], reps: 2)),
  // 5: Ghostish.
  _normalized(_custom(const [
    _Pnr(Offset(0.5, 0), CornerRounding(1)),
    _Pnr(Offset(1, 0), CornerRounding(1)),
    _Pnr(Offset(1, 1.14), CornerRounding(0.254, 0.106)),
    _Pnr(Offset(0.575, 0.906), CornerRounding(0.253)),
  ], reps: 1, mirroring: true)),
  // 6: Sunny: star(8, inner 0.8, rounding 0.15).
  _normalized(_star(8, 0.8, _round15)),
  // 7: Oval: circle(), scaled to 64% high, turned -45°.
  _normalized(_transform(_circle(8), scaleY: 0.64, rotateDegrees: -45)),
];

/// A [ShapeBorder] drawing a unit-square [path] scaled into the rect.
class PolygonBorder extends OutlinedBorder {
  const PolygonBorder(this.path, {super.side});

  final Path path;

  // Column-major 4×4: scale by the rect's size, then move to its corner.
  Path _scaled(Rect rect) => path.transform(Float64List.fromList([
        rect.width, 0, 0, 0, //
        0, rect.height, 0, 0, //
        0, 0, 1, 0, //
        rect.left, rect.top, 0, 1,
      ]));

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) => _scaled(rect);

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) => _scaled(rect.deflate(side.strokeInset));

  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {
    if (side.style == BorderStyle.none) return;
    canvas.drawPath(_scaled(rect), side.toPaint());
  }

  @override
  PolygonBorder copyWith({BorderSide? side}) => PolygonBorder(path, side: side ?? this.side);

  @override
  ShapeBorder scale(double t) => PolygonBorder(path, side: side.scale(t));

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.all(side.strokeInset);

  @override
  bool operator ==(Object other) => other is PolygonBorder && other.path == path && other.side == side;

  @override
  int get hashCode => Object.hash(path, side);
}

/// [from] [t] of the way to [to], both unit-square paths: each is sampled
/// along its length and the points travel straight across (the account's
/// shape changing when an older account it collided with is removed).
Path lerpShapePath(Path from, Path to, double t) {
  if (t <= 0) return from;
  if (t >= 1) return to;
  final a = _samples(from);
  final b = _samples(to);
  final path = Path();
  for (var i = 0; i < a.length; i++) {
    final p = Offset.lerp(a[i], b[i], t)!;
    i == 0 ? path.moveTo(p.dx, p.dy) : path.lineTo(p.dx, p.dy);
  }
  return path..close();
}

const int _sampleCount = 120;
final _sampleCache = Expando<List<Offset>>();

List<Offset> _samples(Path path) => _sampleCache[path] ??= () {
      final metric = path.computeMetrics().first;
      return [
        for (var i = 0; i < _sampleCount; i++)
          metric.getTangentForOffset(metric.length * i / _sampleCount)!.position,
      ];
    }();

// ── graphics-shapes, ported ──────────────────────────────────────────

final class _Pnr {
  const _Pnr(this.o, [this.r = CornerRounding.unrounded]);
  final Offset o;
  final CornerRounding r;
}

final class _Cubic {
  const _Cubic(this.a0, this.c0, this.c1, this.a1);
  final Offset a0;
  final Offset c0;
  final Offset c1;
  final Offset a1;

  _Cubic reverse() => _Cubic(a1, c1, c0, a0);
}

final class _Shape {
  const _Shape(this.cubics);
  final List<_Cubic> cubics;
}

Offset _direction(Offset v) {
  final d = v.distance;
  return d == 0 ? Offset.zero : v / d;
}

double _dot(Offset a, Offset b) => a.dx * b.dx + a.dy * b.dy;
Offset _rotate90(Offset v) => Offset(-v.dy, v.dx);

_Cubic _straight(Offset a, Offset b) => _Cubic(a, Offset.lerp(a, b, 1 / 3)!, Offset.lerp(a, b, 2 / 3)!, b);

_Cubic _circularArc(Offset center, Offset p0, Offset p1) {
  final p0d = _direction(p0 - center);
  final p1d = _direction(p1 - center);
  final r0 = _rotate90(p0d);
  final r1 = _rotate90(p1d);
  final clockwise = _dot(r0, p1 - center) >= 0;
  final cosa = _dot(p0d, p1d);
  if (cosa > 0.999) return _straight(p0, p1);
  final k = (p0 - center).distance *
      4 /
      3 *
      (math.sqrt(2 * (1 - cosa)) - math.sqrt(1 - cosa * cosa)) /
      (1 - cosa) *
      (clockwise ? 1 : -1);
  return _Cubic(p0, p0 + r0 * k, p1 - r1 * k, p1);
}

Offset? _lineIntersection(Offset p0, Offset d0, Offset p1, Offset d1) {
  final rotatedD1 = _rotate90(d1);
  final den = _dot(d0, rotatedD1);
  if (den.abs() < _distanceEpsilon) return null;
  final num = _dot(p1 - p0, rotatedD1);
  if (den.abs() < _distanceEpsilon * num.abs()) return null;
  return p0 + d0 * (num / den);
}

final class _RoundedCorner {
  _RoundedCorner(this.p0, this.p1, this.p2, CornerRounding rounding)
      : radius = rounding.radius,
        smoothing = rounding.smoothing {
    d1 = _direction(p0 - p1);
    d2 = _direction(p2 - p1);
    final cosAngle = _dot(d1, d2);
    final sinAngle = math.sqrt(math.max(0, 1 - cosAngle * cosAngle));
    expectedRoundCut = sinAngle > 1e-3 ? radius * (cosAngle + 1) / sinAngle : 0;
  }

  final Offset p0;
  final Offset p1;
  final Offset p2;
  final double radius;
  final double smoothing;
  late final Offset d1;
  late final Offset d2;
  late final double expectedRoundCut;

  double get expectedCut => (1 + smoothing) * expectedRoundCut;

  List<_Cubic> cubics(double allowedCut0, double allowedCut1) {
    final allowedCut = math.min(allowedCut0, allowedCut1);
    if (expectedRoundCut < _distanceEpsilon || allowedCut < _distanceEpsilon || radius < _distanceEpsilon) {
      return [_Cubic(p1, p1, p1, p1)];
    }
    final actualRoundCut = math.min(allowedCut, expectedRoundCut);
    final smoothing0 = _actualSmoothing(allowedCut0);
    final smoothing1 = _actualSmoothing(allowedCut1);
    final actualR = radius * actualRoundCut / expectedRoundCut;
    final centerDistance = math.sqrt(actualR * actualR + actualRoundCut * actualRoundCut);
    final center = p1 + _direction((d1 + d2) / 2) * centerDistance;
    final circle0 = p1 + d1 * actualRoundCut;
    final circle2 = p1 + d2 * actualRoundCut;
    final flank0 = _flank(actualRoundCut, smoothing0, p1, p0, circle0, circle2, center, actualR);
    final flank2 = _flank(actualRoundCut, smoothing1, p1, p2, circle2, circle0, center, actualR).reverse();
    return [flank0, _circularArc(center, flank0.a1, flank2.a0), flank2];
  }

  double _actualSmoothing(double allowedCut) {
    if (allowedCut > expectedCut) return smoothing;
    if (allowedCut > expectedRoundCut) {
      return smoothing * (allowedCut - expectedRoundCut) / (expectedCut - expectedRoundCut);
    }
    return 0;
  }

  _Cubic _flank(
    double actualRoundCut,
    double actualSmoothing,
    Offset corner,
    Offset sideStart,
    Offset circleSegmentIntersection,
    Offset otherCircleSegmentIntersection,
    Offset circleCenter,
    double actualR,
  ) {
    final sideDirection = _direction(sideStart - corner);
    final curveStart = corner + sideDirection * actualRoundCut * (1 + actualSmoothing);
    final p = Offset.lerp(
      circleSegmentIntersection,
      (circleSegmentIntersection + otherCircleSegmentIntersection) / 2,
      actualSmoothing,
    )!;
    final curveEnd = circleCenter + _direction(p - circleCenter) * actualR;
    final circleTangent = _rotate90(curveEnd - circleCenter);
    final anchorEnd = _lineIntersection(sideStart, sideDirection, curveEnd, circleTangent) ?? circleSegmentIntersection;
    final anchorStart = (curveStart + anchorEnd * 2) / 3;
    return _Cubic(curveStart, anchorStart, anchorEnd, curveEnd);
  }
}

/// RoundedPolygon(vertices, perVertexRounding).
_Shape _polygon(List<Offset> vertices, List<CornerRounding> roundings) {
  final n = vertices.length;
  final corners = [
    for (var i = 0; i < n; i++) _RoundedCorner(vertices[(i + n - 1) % n], vertices[i], vertices[(i + 1) % n], roundings[i]),
  ];
  // Each side: is there room for both corners' cuts?
  final cutAdjusts = [
    for (var i = 0; i < n; i++)
      () {
        final expectedRoundCut = corners[i].expectedRoundCut + corners[(i + 1) % n].expectedRoundCut;
        final expectedCut = corners[i].expectedCut + corners[(i + 1) % n].expectedCut;
        final sideSize = (vertices[i] - vertices[(i + 1) % n]).distance;
        if (expectedRoundCut > sideSize) return (sideSize / expectedRoundCut, 0.0);
        if (expectedCut > sideSize) return (1.0, (sideSize - expectedRoundCut) / (expectedCut - expectedRoundCut));
        return (1.0, 1.0);
      }(),
  ];
  final cornerCubics = [
    for (var i = 0; i < n; i++)
      () {
        double allowed(int delta) {
          final (roundCutRatio, cutRatio) = cutAdjusts[(i + n - 1 + delta) % n];
          return corners[i].expectedRoundCut * roundCutRatio +
              (corners[i].expectedCut - corners[i].expectedRoundCut) * cutRatio;
        }
        return corners[i].cubics(allowed(0), allowed(1));
      }(),
  ];
  final cubics = <_Cubic>[];
  for (var i = 0; i < n; i++) {
    cubics.addAll(cornerCubics[i]);
    cubics.add(_straight(cornerCubics[i].last.a1, cornerCubics[(i + 1) % n].first.a0));
  }
  return _Shape(cubics);
}

List<Offset> _regular(int n, double radius) => [
      for (var i = 0; i < n; i++) Offset(radius * math.cos(math.pi / n * 2 * i), radius * math.sin(math.pi / n * 2 * i)),
    ];

/// RoundedPolygon.star(n, radius 1, innerRadius, rounding).
_Shape _star(int n, double innerRadius, CornerRounding rounding) {
  final vertices = <Offset>[];
  for (var i = 0; i < n; i++) {
    vertices.add(Offset(math.cos(math.pi / n * 2 * i), math.sin(math.pi / n * 2 * i)));
    final a = math.pi / n * (2 * i + 1);
    vertices.add(Offset(innerRadius * math.cos(a), innerRadius * math.sin(a)));
  }
  return _polygon(vertices, List.filled(vertices.length, rounding));
}

/// RoundedPolygon.circle(n): a polygon whose rounded corners meet into a circle of radius 1.
_Shape _circle(int n) {
  final polygonRadius = 1 / math.cos(math.pi / n);
  return _polygon(_regular(n, polygonRadius), List.filled(n, const CornerRounding(1)));
}

/// MaterialShapes.customPolygon: [points] repeated [reps] times round the centre (0.5, 0.5),
/// every other copy mirrored when [mirroring].
_Shape _custom(List<_Pnr> points, {required int reps, bool mirroring = false}) {
  const center = Offset(0.5, 0.5);
  final repeated = <_Pnr>[];
  if (mirroring) {
    double degrees(Offset v) => math.atan2(v.dy, v.dx) * 180 / math.pi;
    final angles = [for (final p in points) degrees(p.o - center)];
    final distances = [for (final p in points) (p.o - center).distance];
    final actualReps = reps * 2;
    final sectionAngle = 360 / actualReps;
    for (var it = 0; it < actualReps; it++) {
      for (var index = 0; index < points.length; index++) {
        final i = it.isEven ? index : points.length - 1 - index;
        if (i > 0 || it.isEven) {
          final a = (sectionAngle * it + (it.isEven ? angles[i] : sectionAngle - angles[i] + 2 * angles[0])) * math.pi / 180;
          repeated.add(_Pnr(Offset(math.cos(a), math.sin(a)) * distances[i] + center, points[i].r));
        }
      }
    }
  } else {
    for (var k = 0; k < points.length * reps; k++) {
      final p = points[k % points.length];
      final a = (k ~/ points.length) * 2 * math.pi / reps;
      final v = p.o - center;
      final rotated = Offset(v.dx * math.cos(a) - v.dy * math.sin(a), v.dx * math.sin(a) + v.dy * math.cos(a));
      repeated.add(_Pnr(rotated + center, p.r));
    }
  }
  return _polygon([for (final p in repeated) p.o], [for (final p in repeated) p.r]);
}

/// Compose Matrix: scale first, then rotateZ([rotateDegrees]).
_Shape _transform(_Shape shape, {double scaleY = 1, double rotateDegrees = 0}) {
  final a = rotateDegrees * math.pi / 180;
  final c = math.cos(a);
  final s = math.sin(a);
  Offset f(Offset p) {
    final q = Offset(p.dx, p.dy * scaleY);
    return Offset(c * q.dx - s * q.dy, s * q.dx + c * q.dy);
  }
  return _Shape([for (final k in shape.cubics) _Cubic(f(k.a0), f(k.c0), f(k.c1), f(k.a1))]);
}

/// RoundedPolygon.normalized(): into the unit square, centred, aspect kept
/// (bounds from anchors and control points, as graphics-shapes' approximate bounds).
Path _normalized(_Shape shape) {
  var left = double.infinity, top = double.infinity, right = -double.infinity, bottom = -double.infinity;
  for (final k in shape.cubics) {
    for (final p in [k.a0, k.c0, k.c1, k.a1]) {
      left = math.min(left, p.dx);
      top = math.min(top, p.dy);
      right = math.max(right, p.dx);
      bottom = math.max(bottom, p.dy);
    }
  }
  final width = right - left;
  final height = bottom - top;
  final side = math.max(width, height);
  final offset = Offset((side - width) / 2 - left, (side - height) / 2 - top);
  Offset f(Offset p) => (p + offset) / side;
  final path = Path()..moveTo(f(shape.cubics.first.a0).dx, f(shape.cubics.first.a0).dy);
  for (final k in shape.cubics) {
    final c0 = f(k.c0), c1 = f(k.c1), a1 = f(k.a1);
    path.cubicTo(c0.dx, c0.dy, c1.dx, c1.dy, a1.dx, a1.dy);
  }
  return path..close();
}
