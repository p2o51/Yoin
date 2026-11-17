// Copyright 2024 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// A Dart port of androidx.compose.material3 `MaterialShapes`, from
// material3 1.5.0-alpha27 (the version Android Yoin ships; numbers read from
// the compiled class). Every getter returns the shape `.normalized()` into
// the unit square, as the Compose getters do.

/// The 35 M3 Expressive shapes (MaterialShapes).
library;

import 'dart:math' as math;
import 'dart:ui' show Offset;

import 'graphics_shapes.dart';

/// MaterialShapes.Companion.PointNRound: a vertex and its rounding.
typedef _PointNRound = (Offset o, CornerRounding r);

/// The M3 Expressive shape set (androidx.compose.material3.MaterialShapes),
/// each normalized into the unit square and built once.
abstract final class MaterialShapes {
  static const _cornerRound15 = CornerRounding(.15);
  static const _cornerRound20 = CornerRounding(.2);
  static const _cornerRound30 = CornerRounding(.3);
  static const _cornerRound50 = CornerRounding(.5);
  static const _cornerRound100 = CornerRounding(1);
  static const _unrounded = CornerRounding.unrounded;

  /// Compose Matrix().rotateZ(degrees), applied to points about (0, 0).
  static PointTransformer _rotateZ(double degrees) {
    final a = degrees * math.pi / 180;
    final c = math.cos(a);
    final s = math.sin(a);
    return (p) => Offset(c * p.dx - s * p.dy, s * p.dx + c * p.dy);
  }

  /// Compose Matrix().scale(x, y).
  static PointTransformer _scale(double x, double y) =>
      (p) => Offset(p.dx * x, p.dy * y);

  static final PointTransformer _rotateNeg45 = _rotateZ(-45);
  static final PointTransformer _rotateNeg90 = _rotateZ(-90);
  static final PointTransformer _rotateNeg135 = _rotateZ(-135);

  static final RoundedPolygon circle = RoundedPolygon.circle(numVertices: 10).normalized();

  static final RoundedPolygon square = RoundedPolygon.rectangle(
    width: 1,
    height: 1,
    rounding: _cornerRound30,
  ).normalized();

  static final RoundedPolygon slanted = _customPolygon([
    (const Offset(0.926, 0.97), const CornerRounding(0.189, smoothing: 0.811)),
    (const Offset(-0.021, 0.967), const CornerRounding(0.187, smoothing: 0.057)),
  ], reps: 2).normalized();

  static final RoundedPolygon arch = RoundedPolygon.regular(
    4,
    perVertexRounding: const [_cornerRound100, _cornerRound100, _cornerRound20, _cornerRound20],
  ).transformed(_rotateNeg135).normalized();

  static final RoundedPolygon fan = _customPolygon([
    (const Offset(1.004, 1.0), const CornerRounding(0.148, smoothing: 0.417)),
    (const Offset(0.0, 1.0), const CornerRounding(0.151)),
    (const Offset(0.0, -0.003), const CornerRounding(0.148)),
    (const Offset(0.978, 0.02), const CornerRounding(0.803)),
  ], reps: 1).normalized();

  static final RoundedPolygon arrow = _customPolygon([
    (const Offset(0.5, 0.892), const CornerRounding(0.313)),
    (const Offset(-0.216, 1.05), const CornerRounding(0.207)),
    (const Offset(0.499, -0.16), const CornerRounding(0.215, smoothing: 1)),
    (const Offset(1.225, 1.06), const CornerRounding(0.211)),
  ], reps: 1).normalized();

  static final RoundedPolygon semiCircle = RoundedPolygon.rectangle(
    width: 1.6,
    height: 1,
    perVertexRounding: const [_cornerRound20, _cornerRound20, _cornerRound100, _cornerRound100],
  ).normalized();

  static final RoundedPolygon oval = RoundedPolygon.circle()
      .transformed(_scale(1, 0.64))
      .transformed(_rotateNeg45)
      .normalized();

  static final RoundedPolygon pill = _customPolygon(
    [
      (const Offset(0.961, 0.039), const CornerRounding(0.426)),
      (const Offset(1.001, 0.428), _unrounded),
      (const Offset(1.0, 0.609), const CornerRounding(1)),
    ],
    reps: 2,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon triangle = RoundedPolygon.regular(
    3,
    rounding: _cornerRound20,
  ).transformed(_rotateNeg90).normalized();

  static final RoundedPolygon diamond = _customPolygon([
    (const Offset(0.5, 1.096), const CornerRounding(0.151, smoothing: 0.524)),
    (const Offset(0.04, 0.5), const CornerRounding(0.159)),
  ], reps: 2).normalized();

  static final RoundedPolygon clamShell = _customPolygon([
    (const Offset(0.171, 0.841), const CornerRounding(0.159)),
    (const Offset(-0.02, 0.5), const CornerRounding(0.14)),
    (const Offset(0.17, 0.159), const CornerRounding(0.159)),
  ], reps: 2).normalized();

  static final RoundedPolygon pentagon = _customPolygon(
    [
      (const Offset(0.5, -0.009), const CornerRounding(0.172)),
      (const Offset(1.03, 0.365), const CornerRounding(0.164)),
      (const Offset(0.828, 0.97), const CornerRounding(0.169)),
    ],
    reps: 1,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon gem = _customPolygon(
    [
      (const Offset(0.499, 1.023), const CornerRounding(0.241, smoothing: 0.778)),
      (const Offset(-0.005, 0.792), const CornerRounding(0.208)),
      (const Offset(0.073, 0.258), const CornerRounding(0.228)),
      (const Offset(0.433, -0.0), const CornerRounding(0.491)),
    ],
    reps: 1,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon sunny = RoundedPolygon.star(8, innerRadius: 0.8, rounding: _cornerRound15).normalized();

  static final RoundedPolygon verySunny = _customPolygon([
    (const Offset(0.5, 1.08), const CornerRounding(0.085)),
    (const Offset(0.358, 0.843), const CornerRounding(0.085)),
  ], reps: 8).normalized();

  static final RoundedPolygon cookie4Sided = _customPolygon([
    (const Offset(1.237, 1.236), const CornerRounding(0.258)),
    (const Offset(0.5, 0.918), const CornerRounding(0.233)),
  ], reps: 4).normalized();

  static final RoundedPolygon cookie6Sided = _customPolygon([
    (const Offset(0.723, 0.884), const CornerRounding(0.394)),
    (const Offset(0.5, 1.099), const CornerRounding(0.398)),
  ], reps: 6).normalized();

  static final RoundedPolygon cookie7Sided = RoundedPolygon.star(
    7,
    innerRadius: 0.75,
    rounding: _cornerRound50,
  ).transformed(_rotateNeg90).normalized();

  static final RoundedPolygon cookie9Sided = RoundedPolygon.star(
    9,
    innerRadius: 0.8,
    rounding: _cornerRound50,
  ).transformed(_rotateNeg90).normalized();

  static final RoundedPolygon cookie12Sided = RoundedPolygon.star(
    12,
    innerRadius: 0.8,
    rounding: _cornerRound50,
  ).transformed(_rotateNeg90).normalized();

  static final RoundedPolygon ghostish = _customPolygon(
    [
      (const Offset(0.5, 0.0), const CornerRounding(1)),
      (const Offset(1.0, 0.0), const CornerRounding(1)),
      (const Offset(1.0, 1.14), const CornerRounding(0.254, smoothing: 0.106)),
      (const Offset(0.575, 0.906), const CornerRounding(0.253)),
    ],
    reps: 1,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon clover4Leaf = _customPolygon(
    [(const Offset(0.5, 0.074), _unrounded), (const Offset(0.725, -0.099), const CornerRounding(0.476))],
    reps: 4,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon clover8Leaf = _customPolygon([
    (const Offset(0.5, 0.036), _unrounded),
    (const Offset(0.758, -0.101), const CornerRounding(0.209)),
  ], reps: 8).normalized();

  static final RoundedPolygon burst = _customPolygon([
    (const Offset(0.5, -0.006), const CornerRounding(0.006)),
    (const Offset(0.592, 0.158), const CornerRounding(0.006)),
  ], reps: 12).normalized();

  static final RoundedPolygon softBurst = _customPolygon([
    (const Offset(0.193, 0.277), const CornerRounding(0.053)),
    (const Offset(0.176, 0.055), const CornerRounding(0.053)),
  ], reps: 10).normalized();

  static final RoundedPolygon boom = _customPolygon([
    (const Offset(0.457, 0.296), const CornerRounding(0.007)),
    (const Offset(0.5, -0.051), const CornerRounding(0.007)),
  ], reps: 15).normalized();

  static final RoundedPolygon softBoom = _customPolygon(
    [
      (const Offset(0.733, 0.454), _unrounded),
      (const Offset(0.839, 0.437), const CornerRounding(0.532)),
      (const Offset(0.949, 0.449), const CornerRounding(0.439, smoothing: 1)),
      (const Offset(0.998, 0.478), const CornerRounding(0.174)),
    ],
    reps: 16,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon flower = _customPolygon(
    [
      (const Offset(0.37, 0.187), _unrounded),
      (const Offset(0.416, 0.049), const CornerRounding(0.381)),
      (const Offset(0.479, 0.001), const CornerRounding(0.095)),
    ],
    reps: 8,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon puffy = _customPolygon(
    [
      (const Offset(0.5, 0.053), _unrounded),
      (const Offset(0.545, -0.04), const CornerRounding(0.405)),
      (const Offset(0.67, -0.035), const CornerRounding(0.426)),
      (const Offset(0.717, 0.066), const CornerRounding(0.574)),
      (const Offset(0.722, 0.128), _unrounded),
      (const Offset(0.777, 0.002), const CornerRounding(0.36)),
      (const Offset(0.914, 0.149), const CornerRounding(0.66)),
      (const Offset(0.926, 0.289), const CornerRounding(0.66)),
      (const Offset(0.881, 0.346), _unrounded),
      (const Offset(0.94, 0.344), const CornerRounding(0.126)),
      (const Offset(1.003, 0.437), const CornerRounding(0.255)),
    ],
    reps: 2,
    mirroring: true,
  ).transformed(_scale(1, 0.742)).normalized();

  static final RoundedPolygon puffyDiamond = _customPolygon(
    [
      (const Offset(0.87, 0.13), const CornerRounding(0.146)),
      (const Offset(0.818, 0.357), _unrounded),
      (const Offset(1.0, 0.332), const CornerRounding(0.853)),
    ],
    reps: 4,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon pixelCircle = _customPolygon(
    [
      (const Offset(0.5, 0.0), _unrounded),
      (const Offset(0.704, 0.0), _unrounded),
      (const Offset(0.704, 0.065), _unrounded),
      (const Offset(0.843, 0.065), _unrounded),
      (const Offset(0.843, 0.148), _unrounded),
      (const Offset(0.926, 0.148), _unrounded),
      (const Offset(0.926, 0.296), _unrounded),
      (const Offset(1.0, 0.296), _unrounded),
    ],
    reps: 2,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon pixelTriangle = _customPolygon(
    [
      (const Offset(0.11, 0.5), _unrounded),
      (const Offset(0.113, 0.0), _unrounded),
      (const Offset(0.287, 0.0), _unrounded),
      (const Offset(0.287, 0.087), _unrounded),
      (const Offset(0.421, 0.087), _unrounded),
      (const Offset(0.421, 0.17), _unrounded),
      (const Offset(0.56, 0.17), _unrounded),
      (const Offset(0.56, 0.265), _unrounded),
      (const Offset(0.674, 0.265), _unrounded),
      (const Offset(0.675, 0.344), _unrounded),
      (const Offset(0.789, 0.344), _unrounded),
      (const Offset(0.789, 0.439), _unrounded),
      (const Offset(0.888, 0.439), _unrounded),
    ],
    reps: 1,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon bun = _customPolygon(
    [
      (const Offset(0.796, 0.5), _unrounded),
      (const Offset(0.853, 0.518), const CornerRounding(1)),
      (const Offset(0.992, 0.631), const CornerRounding(1)),
      (const Offset(0.968, 1.0), const CornerRounding(1)),
    ],
    reps: 2,
    mirroring: true,
  ).normalized();

  static final RoundedPolygon heart = _customPolygon(
    [
      (const Offset(0.5, 0.268), const CornerRounding(0.016)),
      (const Offset(0.792, -0.066), const CornerRounding(0.958)),
      (const Offset(1.064, 0.276), const CornerRounding(1)),
      (const Offset(0.501, 0.946), const CornerRounding(0.129)),
    ],
    reps: 1,
    mirroring: true,
  ).normalized();

  /// Every shape by its Compose name, in MaterialShapes' order.
  static final Map<String, RoundedPolygon> all = {
    'Circle': circle,
    'Square': square,
    'Slanted': slanted,
    'Arch': arch,
    'Fan': fan,
    'Arrow': arrow,
    'SemiCircle': semiCircle,
    'Oval': oval,
    'Pill': pill,
    'Triangle': triangle,
    'Diamond': diamond,
    'ClamShell': clamShell,
    'Pentagon': pentagon,
    'Gem': gem,
    'Sunny': sunny,
    'VerySunny': verySunny,
    'Cookie4Sided': cookie4Sided,
    'Cookie6Sided': cookie6Sided,
    'Cookie7Sided': cookie7Sided,
    'Cookie9Sided': cookie9Sided,
    'Cookie12Sided': cookie12Sided,
    'Ghostish': ghostish,
    'Clover4Leaf': clover4Leaf,
    'Clover8Leaf': clover8Leaf,
    'Burst': burst,
    'SoftBurst': softBurst,
    'Boom': boom,
    'SoftBoom': softBoom,
    'Flower': flower,
    'Puffy': puffy,
    'PuffyDiamond': puffyDiamond,
    'PixelCircle': pixelCircle,
    'PixelTriangle': pixelTriangle,
    'Bun': bun,
    'Heart': heart,
  };

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

  static List<_PointNRound> _doRepeat(List<_PointNRound> points, int reps, Offset center, bool mirroring) {
    if (mirroring) {
      final out = <_PointNRound>[];
      final angles = [for (final p in points) _angleDegrees(p.$1 - center)];
      final distances = [for (final p in points) (p.$1 - center).distance];
      final actualReps = reps * 2;
      final sectionAngle = 360 / actualReps;
      for (var it = 0; it < actualReps; it++) {
        for (var index = 0; index < points.length; index++) {
          final i = it.isEven ? index : points.length - 1 - index;
          if (i > 0 || it.isEven) {
            final a = _toRadians(
              sectionAngle * it + (it.isEven ? angles[i] : sectionAngle - angles[i] + 2 * angles[0]),
            );
            final finalPoint = Offset(math.cos(a), math.sin(a)) * distances[i] + center;
            out.add((finalPoint, points[i].$2));
          }
        }
      }
      return out;
    }
    final np = points.length;
    return [
      for (var it = 0; it < np * reps; it++)
        (_rotateDegrees(points[it % np].$1, (it ~/ np) * 360 / reps, center), points[it % np].$2),
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
