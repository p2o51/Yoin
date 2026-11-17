// Copyright 2023 The Android Open Source Project
// Licensed under the Apache License, Version 2.0 (see ../graphics_shapes.dart).
// Port of graphics-shapes 1.1.0 `FeatureMapping.kt` and `DoubleMapper`
// (FloatMapping.kt).

part of '../graphics_shapes.dart';

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
  // Distances compare at Float precision, as Kotlin computes them: a
  // symmetric shape's mirrored pairs tie there and keep their list order,
  // where doubles would split them by a rounding error and pick the mirror.
  final indexed = [for (var i = 0; i < distanceVertexList.length; i++) (i, distanceVertexList[i])];
  indexed.sort((a, b) {
    final c = _toFloat(a.$2.$1).compareTo(_toFloat(b.$2.$1));
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

final Float32List _floatBuffer = Float32List(1);

/// [x] rounded to the nearest 32-bit float.
double _toFloat(double x) => (_floatBuffer..[0] = x)[0];

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
      if (_progressDistance(f1.progress, before1) < distanceEpsilon ||
          _progressDistance(f1.progress, after1) < distanceEpsilon ||
          _progressDistance(f2.progress, before2) < distanceEpsilon ||
          _progressDistance(f2.progress, after2) < distanceEpsilon) {
        return;
      }
      if (n > 1 && !_progressInRange(f2.progress, before2, after2)) return;
    }
    mapping.insert(insertionIndex, (f1.progress, f2.progress));
    _usedF1.add(f1);
    _usedF2.add(f2);
  }
}
