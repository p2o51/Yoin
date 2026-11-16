// Portions Copyright 2024 The Android Open Source Project, Apache License 2.0
// (see graphics_shapes.dart): `pathFromCubics` follows material3
// 1.5.0-alpha27 `internal/ShapeUtil.kt`, [PolygonBorder] its
// `RoundedPolygon.toShape()`, [MorphBorder] Android Yoin's MorphProgressShape.

/// Flutter paths and borders for [RoundedPolygon] and [Morph].
library;

import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/painting.dart';

import 'graphics_shapes.dart';

export 'graphics_shapes.dart';
export 'material_shapes.dart';

/// material3 ShapeUtil.pathFromCubics: the cubics as one path, closed by
/// default; a non-zero [startAngle] (degrees) turns it so its first point sits
/// at that angle around ([pivotX], [pivotY]) — the turn itself is about (0, 0).
Path pathFromCubics(
  List<ShapeCubic> cubics, {
  Path? into,
  int startAngle = 0,
  bool repeatPath = false,
  bool closePath = true,
  double pivotX = 0,
  double pivotY = 0,
}) {
  final path = (into ?? Path())..reset();
  if (cubics.isEmpty) return path;
  path.moveTo(cubics.first.anchor0X, cubics.first.anchor0Y);
  for (final c in cubics) {
    path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y);
  }
  if (repeatPath) {
    path.lineTo(cubics.first.anchor0X, cubics.first.anchor0Y);
    for (final c in cubics) {
      path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y);
    }
  }
  if (closePath) path.close();
  if (startAngle == 0) return path;
  final angleToFirstCubic = math.atan2(cubics.first.anchor0Y - pivotY, cubics.first.anchor0X - pivotX) * 180 / math.pi;
  final turn = (-angleToFirstCubic + startAngle) * math.pi / 180;
  final turned = path.transform(_rotationZ(turn));
  return path
    ..reset()
    ..addPath(turned, Offset.zero);
}

Float64List _rotationZ(double radians) {
  final c = math.cos(radians);
  final s = math.sin(radians);
  return Float64List.fromList([c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
}

Float64List _scaleThenTranslate(double sx, double sy, double dx, double dy) =>
    Float64List.fromList([sx, 0, 0, 0, 0, sy, 0, 0, 0, 0, 1, 0, dx, dy, 0, 1]);

extension RoundedPolygonPath on RoundedPolygon {
  /// RoundedPolygon.toPath (material3): the outline in the polygon's own units.
  Path toPath({Path? into, int startAngle = 0, bool repeatPath = false, bool closePath = true}) => pathFromCubics(
    cubics,
    into: into,
    startAngle: startAngle,
    repeatPath: repeatPath,
    closePath: closePath,
    pivotX: centerX,
    pivotY: centerY,
  );
}

extension MorphPath on Morph {
  /// Morph.toPath(progress) (material3): the outline at [progress].
  Path toPath(double progress, {Path? into, int startAngle = 0, bool repeatPath = false, bool closePath = true}) =>
      pathFromCubics(
        asCubics(progress),
        into: into,
        startAngle: startAngle,
        repeatPath: repeatPath,
        closePath: closePath,
      );
}

/// RoundedPolygon.toShape()'s outline: the unit-square [path] scaled to
/// [rect]'s size and centred on it by its bounds.
Path polygonOutline(Path unitPath, Rect rect) {
  final scaled = unitPath.transform(_scaleThenTranslate(rect.width, rect.height, 0, 0));
  final shift = rect.center - scaled.getBounds().center;
  return scaled.shift(shift);
}

/// MorphProgressShape's outline: [morph] at [progress] (clamped to 0–1, as
/// Android does), scaled from the unit square to [rect].
Path morphOutline(Morph morph, double progress, Rect rect) =>
    morph.toPath(progress.clamp(0.0, 1.0)).transform(_scaleThenTranslate(rect.width, rect.height, rect.left, rect.top));

/// A [ShapeBorder] of a normalized [RoundedPolygon]: Compose's
/// `RoundedPolygon.toShape()` — scaled to the box and centred on it.
class PolygonBorder extends OutlinedBorder {
  PolygonBorder(this.polygon, {super.side, this.startAngle = 0})
    : _unitPath = _unitPaths[(polygon, startAngle)] ??= polygon.toPath(startAngle: startAngle);

  static final Map<(RoundedPolygon, int), Path> _unitPaths = {};

  final RoundedPolygon polygon;
  final int startAngle;
  final Path _unitPath;

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) => polygonOutline(_unitPath, rect);

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) =>
      polygonOutline(_unitPath, rect.deflate(side.strokeInset));

  /// The stroke follows the outline moved by [BorderSide.strokeAlign]
  /// (inside by default, as Compose's `Modifier.border` keeps it), not
  /// centred on the edge with half of it spilling out.
  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {
    if (side.style == BorderStyle.none) return;
    canvas.drawPath(getOuterPath(rect.inflate(side.strokeOffset / 2)), side.toPaint());
  }

  @override
  PolygonBorder copyWith({BorderSide? side}) => PolygonBorder(polygon, side: side ?? this.side, startAngle: startAngle);

  @override
  ShapeBorder scale(double t) => PolygonBorder(polygon, side: side.scale(t), startAngle: startAngle);

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.all(side.strokeInset);

  @override
  bool operator ==(Object other) =>
      other is PolygonBorder &&
      identical(other.polygon, polygon) &&
      other.side == side &&
      other.startAngle == startAngle;

  @override
  int get hashCode => Object.hash(identityHashCode(polygon), side, startAngle);
}

/// A [ShapeBorder] of a [Morph] frozen at [progress] (Android Yoin's
/// MorphProgressShape): scaled from the unit square to the box.
class MorphBorder extends OutlinedBorder {
  const MorphBorder(this.morph, this.progress, {super.side});

  final Morph morph;
  final double progress;

  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) => morphOutline(morph, progress, rect);

  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) =>
      morphOutline(morph, progress, rect.deflate(side.strokeInset));

  /// Strokes like [PolygonBorder.paint]: inside the outline by default.
  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {
    if (side.style == BorderStyle.none) return;
    canvas.drawPath(getOuterPath(rect.inflate(side.strokeOffset / 2)), side.toPaint());
  }

  @override
  MorphBorder copyWith({BorderSide? side}) => MorphBorder(morph, progress, side: side ?? this.side);

  @override
  ShapeBorder scale(double t) => MorphBorder(morph, progress, side: side.scale(t));

  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.all(side.strokeInset);

  @override
  bool operator ==(Object other) =>
      other is MorphBorder && identical(other.morph, morph) && other.progress == progress && other.side == side;

  @override
  int get hashCode => Object.hash(identityHashCode(morph), progress, side);
}
