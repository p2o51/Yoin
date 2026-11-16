// Copyright 2023 The Android Open Source Project
// Licensed under the Apache License, Version 2.0 (see ../graphics_shapes.dart).
// Port of graphics-shapes 1.1.0 `RoundedPolygon.kt` and `Shapes.kt`.

part of '../graphics_shapes.dart';

/// Kotlin's Float.MIN_VALUE (the smallest positive float), which
/// RoundedPolygon.calculateBounds starts its maxima from.
const double _kotlinFloatMinValue = 1.4e-45;

/// A closed shape of rounded corners and the edges between them
/// (graphics-shapes `RoundedPolygon`).
final class RoundedPolygon {
  RoundedPolygon._(List<ShapeFeature> features, this.centerX, this.centerY)
    : features = List<ShapeFeature>.unmodifiable(features),
      cubics = List<ShapeCubic>.unmodifiable(_buildCubics(features, centerX, centerY));

  /// RoundedPolygon(features, centerX, centerY): an outline from ready-made
  /// [features]; a null centre is the average of their cubics' start points.
  factory RoundedPolygon.fromFeatures(List<ShapeFeature> features, {double? centerX, double? centerY}) {
    if (features.length < 2) throw ArgumentError('Polygons must have at least 2 features');
    final vertices = <double>[
      for (final feature in features)
        for (final cubic in feature.cubics) ...[cubic.anchor0X, cubic.anchor0Y],
    ];
    final center = _calculateCenter(vertices);
    return RoundedPolygon._(features, centerX ?? center.dx, centerY ?? center.dy);
  }

  /// RoundedPolygon(source): a copy of [source].
  factory RoundedPolygon.from(RoundedPolygon source) =>
      RoundedPolygon._(source.features, source.centerX, source.centerY);

  /// RoundedPolygon(vertices, rounding, perVertexRounding, centerX, centerY).
  /// [vertices] is x0, y0, x1, y1…; a null centre is the vertices' average.
  factory RoundedPolygon.fromVertices(
    List<double> vertices, {
    CornerRounding rounding = CornerRounding.unrounded,
    List<CornerRounding?>? perVertexRounding,
    double? centerX,
    double? centerY,
  }) {
    if (vertices.length < 6) throw ArgumentError('Polygons must have at least 3 vertices');
    if (vertices.length.isOdd) throw ArgumentError('The vertices array should have even size');
    if (perVertexRounding != null && perVertexRounding.length * 2 != vertices.length) {
      throw ArgumentError(
        'perVertexRounding list should be either null or the same size as the number of vertices '
        '(vertices.size / 2)',
      );
    }
    final n = vertices.length ~/ 2;
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
    final Offset center;
    if (centerX == null || centerY == null) {
      center = _calculateCenter(vertices);
    } else {
      center = Offset(centerX, centerY);
    }
    return RoundedPolygon._(features, center.dx, center.dy);
  }

  /// RoundedPolygon(numVertices, radius, centerX, centerY, rounding,
  /// perVertexRounding): a regular polygon, first vertex at angle 0.
  factory RoundedPolygon.regular(
    int numVertices, {
    double radius = 1,
    double centerX = 0,
    double centerY = 0,
    CornerRounding rounding = CornerRounding.unrounded,
    List<CornerRounding?>? perVertexRounding,
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
    if (numVertices < 3) throw ArgumentError('Circle must have at least three vertices');
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

  /// RoundedPolygon.rectangle: vertices bottom-right, bottom-left, top-left,
  /// top-right (y grows down).
  factory RoundedPolygon.rectangle({
    double width = 2,
    double height = 2,
    CornerRounding rounding = CornerRounding.unrounded,
    List<CornerRounding?>? perVertexRounding,
    double centerX = 0,
    double centerY = 0,
  }) {
    final left = centerX - width / 2;
    final top = centerY - height / 2;
    final right = centerX + width / 2;
    final bottom = centerY + height / 2;
    return RoundedPolygon.fromVertices(
      [right, bottom, left, bottom, left, top, right, top],
      rounding: rounding,
      perVertexRounding: perVertexRounding,
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
    List<CornerRounding?>? perVertexRounding,
    double centerX = 0,
    double centerY = 0,
  }) {
    if (radius <= 0 || innerRadius <= 0) throw ArgumentError('Star radii must both be greater than 0');
    if (innerRadius >= radius) throw ArgumentError('innerRadius must be less than radius');
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

  /// RoundedPolygon.pill: a stadium [width] × [height], ends fully round.
  factory RoundedPolygon.pill({
    double width = 2,
    double height = 1,
    double smoothing = 0,
    double centerX = 0,
    double centerY = 0,
  }) {
    if (width <= 0 || height <= 0) throw ArgumentError('Pill shapes must have positive width and height');
    final wHalf = width / 2;
    final hHalf = height / 2;
    return RoundedPolygon.fromVertices(
      [
        wHalf + centerX,
        hHalf + centerY,
        -wHalf + centerX,
        hHalf + centerY,
        -wHalf + centerX,
        -hHalf + centerY,
        wHalf + centerX,
        -hHalf + centerY,
      ],
      rounding: CornerRounding(math.min(wHalf, hHalf), smoothing: smoothing),
      centerX: centerX,
      centerY: centerY,
    );
  }

  /// RoundedPolygon.pillStar: a star whose points follow a pill outline.
  factory RoundedPolygon.pillStar({
    double width = 2,
    double height = 1,
    int numVerticesPerRadius = 8,
    double innerRadiusRatio = .5,
    CornerRounding rounding = CornerRounding.unrounded,
    CornerRounding? innerRounding,
    List<CornerRounding?>? perVertexRounding,
    double vertexSpacing = .5,
    double startLocation = 0,
    double centerX = 0,
    double centerY = 0,
  }) {
    if (width <= 0 || height <= 0) throw ArgumentError('Pill shapes must have positive width and height');
    if (innerRadiusRatio <= 0 || innerRadiusRatio > 1) throw ArgumentError('innerRadius must be between 0 and 1');
    var pvRounding = perVertexRounding;
    if (pvRounding == null && innerRounding != null) {
      pvRounding = [
        for (var i = 0; i < numVerticesPerRadius; i++) ...[rounding, innerRounding],
      ];
    }
    return RoundedPolygon.fromVertices(
      _pillStarVerticesFromNumVerts(
        numVerticesPerRadius,
        width,
        height,
        innerRadiusRatio,
        vertexSpacing,
        startLocation,
        centerX,
        centerY,
      ),
      rounding: rounding,
      perVertexRounding: pvRounding,
      centerX: centerX,
      centerY: centerY,
    );
  }

  static List<double> _pillStarVerticesFromNumVerts(
    int numVerticesPerRadius,
    double width,
    double height,
    double innerRadius,
    double vertexSpacing,
    double startLocation,
    double centerX,
    double centerY,
  ) {
    final endcapRadius = math.min(width, height);
    final vSegLen = math.max(height - width, 0.0);
    final hSegLen = math.max(width - height, 0.0);
    final vSegHalf = vSegLen / 2;
    final hSegHalf = hSegLen / 2;
    final circlePerimeter = 2 * math.pi * endcapRadius * _interpolate(innerRadius, 1, vertexSpacing);
    final perimeter = 2 * hSegLen + 2 * vSegLen + circlePerimeter;
    final sections = List<double>.filled(11, 0);
    sections[0] = 0;
    sections[1] = vSegLen / 2;
    sections[2] = sections[1] + circlePerimeter / 4;
    sections[3] = sections[2] + hSegLen;
    sections[4] = sections[3] + circlePerimeter / 4;
    sections[5] = sections[4] + vSegLen;
    sections[6] = sections[5] + circlePerimeter / 4;
    sections[7] = sections[6] + hSegLen;
    sections[8] = sections[7] + circlePerimeter / 4;
    sections[9] = sections[8] + vSegLen / 2;
    sections[10] = perimeter;
    final tPerVertex = perimeter / (2 * numVerticesPerRadius);
    var inner = false;
    var currSecIndex = 0;
    var secStart = 0.0;
    var secEnd = sections[1];
    var t = startLocation * perimeter;
    final result = <double>[];
    final rectBR = Offset(hSegHalf, vSegHalf);
    final rectBL = Offset(-hSegHalf, vSegHalf);
    final rectTL = Offset(-hSegHalf, -vSegHalf);
    final rectTR = Offset(hSegHalf, -vSegHalf);
    for (var i = 0; i < numVerticesPerRadius * 2; i++) {
      final boundedT = t % perimeter;
      if (boundedT < secStart) currSecIndex = 0;
      while (boundedT >= sections[(currSecIndex + 1) % sections.length]) {
        currSecIndex = (currSecIndex + 1) % sections.length;
        secStart = sections[currSecIndex];
        secEnd = sections[(currSecIndex + 1) % sections.length];
      }
      final tInSection = boundedT - secStart;
      final tProportion = tInSection / (secEnd - secStart);
      final currRadius = inner ? endcapRadius * innerRadius : endcapRadius;
      final vertex = switch (currSecIndex) {
        0 => Offset(currRadius, tProportion * vSegHalf),
        1 => _radialToCartesian(currRadius, tProportion * math.pi / 2) + rectBR,
        2 => Offset(hSegHalf - tProportion * hSegLen, currRadius),
        3 => _radialToCartesian(currRadius, math.pi / 2 + tProportion * math.pi / 2) + rectBL,
        4 => Offset(-currRadius, vSegHalf - tProportion * vSegLen),
        5 => _radialToCartesian(currRadius, math.pi + tProportion * math.pi / 2) + rectTL,
        6 => Offset(-hSegHalf + tProportion * hSegLen, -currRadius),
        7 => _radialToCartesian(currRadius, math.pi * 1.5 + tProportion * math.pi / 2) + rectTR,
        _ => Offset(currRadius, -vSegHalf + tProportion * vSegHalf),
      };
      result
        ..add(vertex.dx + centerX)
        ..add(vertex.dy + centerY);
      t += tPerVertex;
      inner = !inner;
    }
    return result;
  }

  final List<ShapeFeature> features;
  final double centerX;
  final double centerY;

  Offset get center => Offset(centerX, centerY);

  /// The closed outline: each cubic's anchor1 is the next one's anchor0.
  final List<ShapeCubic> cubics;

  static Offset _calculateCenter(List<double> vertices) {
    var cumulativeX = 0.0;
    var cumulativeY = 0.0;
    for (var index = 0; index < vertices.length; index += 2) {
      cumulativeX += vertices[index];
      cumulativeY += vertices[index + 1];
    }
    final n = vertices.length ~/ 2;
    return Offset(cumulativeX / n, cumulativeY / n);
  }

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
    // The outline must be contiguous.
    var prev = out.last;
    for (final cubic in out) {
      if ((cubic.anchor0X - prev.anchor1X).abs() > distanceEpsilon ||
          (cubic.anchor0Y - prev.anchor1Y).abs() > distanceEpsilon) {
        throw ArgumentError(
          'RoundedPolygon must be contiguous, with the anchor points of all curves matching the anchor points of '
          'the preceding and succeeding cubics',
        );
      }
      prev = cubic;
    }
    return out;
  }

  /// The same polygon with every point (and the centre) mapped by [f].
  RoundedPolygon transformed(PointTransformer f) {
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

  /// calculateBounds: [left, top, right, bottom]. [approximate] (the
  /// default, as on Android) uses each cubic's control-point hull.
  List<double> calculateBounds({bool approximate = true}) {
    var minX = double.maxFinite;
    var minY = double.maxFinite;
    var maxX = _kotlinFloatMinValue;
    var maxY = _kotlinFloatMinValue;
    final b = List<double>.filled(4, 0);
    for (final cubic in cubics) {
      cubic._boundsInto(b, approximate);
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

  @override
  String toString() => '[RoundedPolygon. Cubics = $cubics || Features = $features || Center = ($centerX, $centerY)]';
}
