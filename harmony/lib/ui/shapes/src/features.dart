// Copyright 2023 The Android Open Source Project
// Licensed under the Apache License, Version 2.0 (see ../graphics_shapes.dart).
// Port of graphics-shapes 1.1.0 `CornerRounding.kt` and `Feature.kt`.

part of '../graphics_shapes.dart';

/// How a polygon corner is rounded: [radius] of the round part, and
/// [smoothing] (0–1) of the flanking curves that ease into it.
@immutable
final class CornerRounding {
  const CornerRounding(this.radius, {this.smoothing = 0})
    : assert(radius >= 0, 'radius must be >= 0'),
      assert(smoothing >= 0 && smoothing <= 1, 'smoothing must be in 0..1');

  /// CornerRounding.Unrounded.
  static const unrounded = CornerRounding(0);

  final double radius;
  final double smoothing;

  @override
  bool operator ==(Object other) => other is CornerRounding && other.radius == radius && other.smoothing == smoothing;

  @override
  int get hashCode => Object.hash(radius, smoothing);

  @override
  String toString() => 'CornerRounding($radius, smoothing: $smoothing)';
}

/// A run of cubics on a polygon outline: a (possibly rounded) corner or the
/// straight edge between two corners (graphics-shapes `Feature`).
sealed class ShapeFeature {
  ShapeFeature(List<ShapeCubic> cubics) : cubics = List<ShapeCubic>.unmodifiable(cubics);

  final List<ShapeCubic> cubics;

  ShapeFeature transformed(PointTransformer f);

  /// The same feature run backwards (a corner flips its convexity).
  ShapeFeature reversed();

  bool get isIgnorableFeature;
  bool get isEdge;
  bool get isConvexCorner;
  bool get isConcaveCorner;

  /// Feature.Factory.buildIgnorableFeature.
  static ShapeFeature buildIgnorableFeature(List<ShapeCubic> cubics) => _validated(EdgeFeature(cubics));

  /// Feature.Factory.buildEdge.
  static ShapeFeature buildEdge(ShapeCubic cubic) => EdgeFeature([cubic]);

  /// Feature.Factory.buildConvexCorner.
  static ShapeFeature buildConvexCorner(List<ShapeCubic> cubics) => _validated(CornerFeature(cubics, convex: true));

  /// Feature.Factory.buildConcaveCorner.
  static ShapeFeature buildConcaveCorner(List<ShapeCubic> cubics) => _validated(CornerFeature(cubics, convex: false));

  static ShapeFeature _validated(ShapeFeature feature) {
    if (feature.cubics.isEmpty) throw ArgumentError('Features need at least one cubic.');
    for (var i = 1; i < feature.cubics.length; i++) {
      final prev = feature.cubics[i - 1];
      final cubic = feature.cubics[i];
      if ((cubic.anchor0X - prev.anchor1X).abs() > distanceEpsilon ||
          (cubic.anchor0Y - prev.anchor1Y).abs() > distanceEpsilon) {
        throw ArgumentError(
          'Feature must be continuous, with the anchor points of all cubics matching the anchor points of the '
          'preceding and succeeding cubics',
        );
      }
    }
    return feature;
  }
}

final class CornerFeature extends ShapeFeature {
  CornerFeature(super.cubics, {this.convex = true});

  final bool convex;

  @override
  CornerFeature transformed(PointTransformer f) =>
      CornerFeature([for (final c in cubics) c.transformed(f)], convex: convex);

  @override
  CornerFeature reversed() => CornerFeature([for (final c in cubics.reversed) c.reverse()], convex: !convex);

  @override
  bool get isIgnorableFeature => false;

  @override
  bool get isEdge => false;

  @override
  bool get isConvexCorner => convex;

  @override
  bool get isConcaveCorner => !convex;

  @override
  String toString() => 'Corner: cubics=$cubics convex=$convex';
}

final class EdgeFeature extends ShapeFeature {
  EdgeFeature(super.cubics);

  @override
  EdgeFeature transformed(PointTransformer f) => EdgeFeature([for (final c in cubics) c.transformed(f)]);

  @override
  EdgeFeature reversed() => EdgeFeature([for (final c in cubics.reversed) c.reverse()]);

  @override
  bool get isIgnorableFeature => true;

  @override
  bool get isEdge => true;

  @override
  bool get isConvexCorner => false;

  @override
  bool get isConcaveCorner => false;

  @override
  String toString() => 'Edge';
}
