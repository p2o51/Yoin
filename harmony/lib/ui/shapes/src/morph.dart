// Copyright 2023 The Android Open Source Project
// Licensed under the Apache License, Version 2.0 (see ../graphics_shapes.dart).
// Port of graphics-shapes 1.1.0 `Morph.kt`.

part of '../graphics_shapes.dart';

/// A morph between two [RoundedPolygon]s (graphics-shapes `Morph`): their
/// outlines are cut into matching cubic pairs once; [asCubics] interpolates.
final class Morph {
  Morph(this.start, this.end) : morphMatch = List.unmodifiable(match(start, end));

  final RoundedPolygon start;
  final RoundedPolygon end;

  /// The matched cubic pairs, start's then end's (Morph.morphMatch).
  final List<(ShapeCubic, ShapeCubic)> morphMatch;

  /// Number of cubic pairs the outlines were cut into.
  int get cubicCount => morphMatch.length;

  /// Morph.calculateBounds: the union of [start]'s and [end]'s bounds, so a
  /// container sized to it fits every frame of the morph (between 0 and 1).
  List<double> calculateBounds({bool approximate = true}) {
    final a = start.calculateBounds(approximate: approximate);
    final b = end.calculateBounds(approximate: approximate);
    return [math.min(a[0], b[0]), math.min(a[1], b[1]), math.max(a[2], b[2]), math.max(a[3], b[3])];
  }

  /// Morph.calculateMaxBounds: the union of both shapes' rotation-safe squares.
  List<double> calculateMaxBounds() {
    final a = start.calculateMaxBounds();
    final b = end.calculateMaxBounds();
    return [math.min(a[0], b[0]), math.min(a[1], b[1]), math.max(a[2], b[2]), math.max(a[3], b[3])];
  }

  /// Morph.forEachCubic: each matched pair interpolated at [progress], in
  /// order (not closed up like [asCubics]).
  void forEachCubic(double progress, void Function(ShapeCubic cubic) callback) {
    for (final (a, b) in morphMatch) {
      callback(ShapeCubic([for (var i = 0; i < 8; i++) _interpolate(a.points[i], b.points[i], progress)]));
    }
  }

  /// The outline at [progress] (0 = [start], 1 = [end]; beyond extrapolates,
  /// as a spring overshoot does on Android).
  List<ShapeCubic> asCubics(double progress) {
    final out = <ShapeCubic>[];
    ShapeCubic? firstCubic;
    ShapeCubic? lastCubic;
    for (final (a, b) in morphMatch) {
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

  /// Morph.match: cuts [p1]'s and [p2]'s outlines into pairs of cubics,
  /// corners matched to the nearest compatible corner.
  static List<(ShapeCubic, ShapeCubic)> match(RoundedPolygon p1, RoundedPolygon p2) {
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
      if (b1a > minb + angleEpsilon) {
        (seg1, newb1) = b1.cutAtProgress(minb);
      } else {
        seg1 = b1;
        newb1 = bs1[i1++];
      }
      _MeasuredCubic seg2;
      _MeasuredCubic? newb2;
      if (b2a > minb + angleEpsilon) {
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
