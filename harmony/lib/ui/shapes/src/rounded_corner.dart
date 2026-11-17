// Copyright 2023 The Android Open Source Project
// Licensed under the Apache License, Version 2.0 (see ../graphics_shapes.dart).
// Port of graphics-shapes 1.1.0 `RoundedCorner` (RoundedPolygon.kt).

part of '../graphics_shapes.dart';

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
    if (expectedRoundCut < distanceEpsilon || allowedCut < distanceEpsilon || cornerRadius < distanceEpsilon) {
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
    if (den.abs() < distanceEpsilon) return null;
    final num = _dot(p1 - p0, rotatedD1);
    if (den.abs() < distanceEpsilon * num.abs()) return null;
    final k = num / den;
    return p0 + d0 * k;
  }
}
