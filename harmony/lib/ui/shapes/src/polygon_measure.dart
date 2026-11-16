// Copyright 2023 The Android Open Source Project
// Licensed under the Apache License, Version 2.0 (see ../graphics_shapes.dart).
// Port of graphics-shapes 1.1.0 `PolygonMeasure.kt` (MeasuredPolygon,
// LengthMeasurer) and `FloatMapping.kt`.

part of '../graphics_shapes.dart';

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
      if (outlineProgress[index + 1] - outlineProgress[index] > distanceEpsilon) {
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
    if (cuttingPoint < distanceEpsilon) return this;
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
