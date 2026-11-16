// Copyright 2023 The Android Open Source Project
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
// A Dart port of androidx.graphics:graphics-shapes 1.1.0 (the version
// Android Yoin ships; read from the compiled classes, not guessed):
// `CornerRounding`, `Cubic`, `Feature`, `RoundedCorner`, `RoundedPolygon`
// and `Shapes.kt` (circle, rectangle, star, pill, pillStar), `Morph`,
// `PolygonMeasure` (`MeasuredPolygon`, `LengthMeasurer`), `FeatureMapping`,
// `FloatMapping` (`DoubleMapper`) and `Utils`.
//
// Kotlin computes in Float; this runs in double. Everything else (order of
// operations, epsilons, edge cases) follows the original. Not ported, as Yoin
// never uses them: `SvgPathParser`, `FeatureSerializer`, `PolygonValidator`,
// `FeatureDetector`, `ArcConverter` and the `MutableCubic` callbacks
// (`Morph.forEachCubic` hands out immutable cubics instead).

/// RoundedPolygon, Morph and friends (androidx graphics-shapes).
library;

import 'dart:math' as math;
import 'dart:typed_data' show Float32List;
import 'dart:ui' show Offset;

import 'package:flutter/foundation.dart' show immutable, listEquals;

part 'src/cubic.dart';
part 'src/features.dart';
part 'src/rounded_corner.dart';
part 'src/rounded_polygon.dart';
part 'src/polygon_measure.dart';
part 'src/feature_mapping.dart';
part 'src/morph.dart';

// ── Utils.kt ──

/// Utils.DistanceEpsilon.
const double distanceEpsilon = 1e-4;

/// Utils.AngleEpsilon.
const double angleEpsilon = 1e-6;

/// Utils.RelaxedDistanceEpsilon (vertices this close are one).
const double relaxedDistanceEpsilon = 5e-3;

double _interpolate(double start, double stop, double fraction) => (1 - fraction) * start + fraction * stop;

/// Utils.positiveModulo, with Kotlin's truncating `%` ([double.remainder]):
/// Dart's own `%` is already non-negative, and adding [mod] to its result
/// rounds a tiny negative [num] to exactly 0 where Kotlin gets just under
/// [mod] (a cut a hair past a cubic's end then drops that cubic).
double _positiveModulo(double num, double mod) => (num.remainder(mod) + mod) % mod;

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

/// Utils.radialToCartesian.
Offset _radialToCartesian(double radius, double angleRadians, [Offset center = Offset.zero]) =>
    Offset(math.cos(angleRadians), math.sin(angleRadians)) * radius + center;

/// A point mapping for [RoundedPolygon.transformed] / [Morph] cubics
/// (graphics-shapes `PointTransformer`).
typedef PointTransformer = Offset Function(Offset point);
