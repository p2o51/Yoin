import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/painting.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/shapes/shape_path.dart';

// Golden numbers come from the real Kotlin classes on the JVM (see
// golden/MaterialShapesGolden.java). Kotlin runs in Float, hence the slack.
const _tol = 2e-5;

Map<String, dynamic> _golden() =>
    jsonDecode(File('test/ui/shapes/golden/material_shapes_golden.json').readAsStringSync()) as Map<String, dynamic>;

List<double> _flat(List<ShapeCubic> cubics) => [for (final c in cubics) ...c.points];

void _expectAllClose(List<double> actual, List<dynamic> golden, String what) {
  expect(actual.length, golden.length, reason: '$what length');
  for (var i = 0; i < golden.length; i++) {
    expect(actual[i], closeTo((golden[i] as num).toDouble(), _tol), reason: '$what[$i]');
  }
}

void _expectClosed(List<ShapeCubic> cubics, String what) {
  for (var i = 0; i < cubics.length; i++) {
    final next = cubics[(i + 1) % cubics.length];
    expect((cubics[i].anchor1 - next.anchor0).distance, lessThan(1e-9), reason: '$what cubic $i');
  }
}

void main() {
  final golden = _golden();
  final shapes = golden['shapes'] as Map<String, dynamic>;
  final morphs = golden['morphs'] as Map<String, dynamic>;

  group('MaterialShapes', () {
    test('should_listEveryComposeShape_when_enumerated', () {
      expect(MaterialShapes.all.keys, shapes.keys);
    });

    for (final name in shapes.keys) {
      test('should_matchCompose_when_building_$name', () {
        final g = shapes[name] as Map<String, dynamic>;
        final p = MaterialShapes.all[name]!;
        expect(p.features.length, g['features'], reason: 'feature count');
        expect(p.cubics.length, g['cubics'], reason: 'cubic count');
        _expectAllClose(p.calculateBounds(), g['bounds'] as List, 'bounds');
        _expectAllClose(p.calculateMaxBounds(), g['maxBounds'] as List, 'maxBounds');
        _expectAllClose([p.centerX, p.centerY], g['center'] as List, 'center');
        _expectAllClose(_flat(p.cubics), g['points'] as List, 'points');
      });

      test('should_closeAndFitUnitSquare_when_normalized_$name', () {
        final p = MaterialShapes.all[name]!;
        _expectClosed(p.cubics, name);
        final b = p.calculateBounds();
        expect(math.max(b[2] - b[0], b[3] - b[1]), closeTo(1, 1e-9));
        expect(b[0], greaterThanOrEqualTo(-1e-9));
        expect(b[1], greaterThanOrEqualTo(-1e-9));
        expect(p.toPath().contains(const Offset(.5, .5)), isTrue);
      });
    }

    test('should_mirrorAboutVerticalAxis_when_backdropShapes', () {
      // Bun, Ghostish, Heart and the turned Triangle are mirrored left/right:
      // every anchor has a twin across x = 0.5.
      for (final p in [MaterialShapes.bun, MaterialShapes.ghostish, MaterialShapes.heart, MaterialShapes.triangle]) {
        final anchors = [for (final c in p.cubics) c.anchor0];
        for (final a in anchors) {
          final twin = Offset(1 - a.dx, a.dy);
          expect(anchors.any((b) => (b - twin).distance < 1e-6), isTrue, reason: '$a has no mirror');
        }
      }
    });
  });

  group('Morph', () {
    for (final key in morphs.keys) {
      test('should_matchCompose_when_morphing_$key', () {
        final g = morphs[key] as Map<String, dynamic>;
        final [a, b] = key.split('>');
        final morph = Morph(MaterialShapes.all[a]!, MaterialShapes.all[b]!);
        expect(morph.cubicCount, g['pairs']);
        _expectAllClose(_flat(morph.asCubics(.5)), g['half'] as List, 'half');
      });
    }

    test('should_equalEndpoints_when_progressIsZeroOrOne', () {
      for (final target in [MaterialShapes.triangle, MaterialShapes.cookie9Sided]) {
        for (final base in [MaterialShapes.bun, MaterialShapes.circle, MaterialShapes.ghostish]) {
          final morph = Morph(base, target);
          // The matched outline is the same curve cut into more pieces: compare
          // sampled points rather than cubic lists.
          for (final (progress, shape) in [(0.0, base), (1.0, target)]) {
            final morphed = morph.toPath(progress);
            final own = shape.toPath();
            for (final m in own.computeMetrics()) {
              for (var i = 0; i < 32; i++) {
                final p = m.getTangentForOffset(m.length * i / 32)!.position;
                expect(_distanceToPath(morphed, p), lessThan(2e-3), reason: '$progress at $p');
              }
            }
            _expectClosed(morph.asCubics(progress), 'morph');
          }
        }
      }
    });

    test('should_unionBothShapes_when_calculatingBounds', () {
      final morph = Morph(MaterialShapes.bun, MaterialShapes.triangle);
      final b = morph.calculateBounds();
      final bun = MaterialShapes.bun.calculateBounds();
      final tri = MaterialShapes.triangle.calculateBounds();
      expect(b, [
        math.min(bun[0], tri[0]),
        math.min(bun[1], tri[1]),
        math.max(bun[2], tri[2]),
        math.max(bun[3], tri[3]),
      ]);
    });

    test('should_visitEveryPair_when_forEachCubic', () {
      final morph = Morph(MaterialShapes.circle, MaterialShapes.cookie9Sided);
      var count = 0;
      morph.forEachCubic(.3, (_) => count++);
      expect(count, morph.cubicCount);
    });
  });

  group('RoundedPolygon factories', () {
    test('should_roundEnds_when_pill', () {
      final pill = RoundedPolygon.pill(width: 4, height: 2);
      final b = pill.calculateBounds(approximate: false);
      _expectAllClose(b, [-2, -1, 2, 1], 'pill bounds');
    });

    test('should_orderVerticesFromBottomRight_when_rectangle', () {
      final r = RoundedPolygon.rectangle(width: 4, height: 2, centerX: 2, centerY: 1);
      final corners = r.features.whereType<CornerFeature>().toList();
      expect(corners.first.cubics.first.anchor0, const Offset(4, 2));
      expect(corners.length, 4);
    });

    test('should_alternateRadii_when_pillStar', () {
      final s = RoundedPolygon.pillStar(numVerticesPerRadius: 6, innerRadiusRatio: .5);
      final corners = s.features.whereType<CornerFeature>().toList();
      expect(corners.length, 12);
      expect([for (final c in corners) c.convex], [for (var i = 0; i < 12; i++) i.isEven]);
    });

    test('should_findTrueExtremes_when_boundsNotApproximate', () {
      final c = ShapeCubic.of(0, 0, 0, 1, 1, 1, 1, 0);
      final exact = c.calculateBounds();
      final hull = c.calculateBounds(approximate: true);
      expect(exact[3], closeTo(.75, 1e-12));
      expect(hull[3], 1);
    });

    test('should_rejectBrokenOutline_when_featuresDisjoint', () {
      expect(
        () =>
            ShapeFeature.buildConvexCorner([ShapeCubic.straightLine(0, 0, 1, 0), ShapeCubic.straightLine(2, 0, 3, 0)]),
        throwsArgumentError,
      );
    });
  });

  group('Flutter adapters', () {
    test('should_centreOnTheBox_when_polygonBorder', () {
      const rect = Rect.fromLTWH(10, 20, 100, 60);
      for (final p in MaterialShapes.all.values) {
        final b = PolygonBorder(p).getOuterPath(rect).getBounds();
        expect(b.center.dx, closeTo(rect.center.dx, 1e-3));
        expect(b.center.dy, closeTo(rect.center.dy, 1e-3));
        expect(b.width, lessThanOrEqualTo(rect.width + 1e-3));
        expect(b.height, lessThanOrEqualTo(rect.height + 1e-3));
      }
    });

    test('should_scaleTheUnitSquare_when_morphBorder', () {
      const rect = Rect.fromLTWH(0, 0, 50, 50);
      final morph = Morph(MaterialShapes.bun, MaterialShapes.triangle);
      final atZero = MorphBorder(morph, 0).getOuterPath(rect).getBounds();
      final bun = MaterialShapes.bun.calculateBounds();
      expect(atZero.left, closeTo(bun[0] * 50, 1e-3));
      expect(atZero.bottom, closeTo(bun[3] * 50, 1e-3));
      // Progress is clamped to 0–1, as Android's MorphProgressShape does.
      expect(
        MorphBorder(morph, 1.3).getOuterPath(rect).getBounds(),
        MorphBorder(morph, 1).getOuterPath(rect).getBounds(),
      );
    });

    test('should_putFirstPointAtAngle_when_startAngleGiven', () {
      // material3 turns the path about (0, 0) so that its first point sits at
      // startAngle around the (turned) centre.
      final p = MaterialShapes.cookie9Sided;
      final first0 = p.cubics.first.anchor0;
      final turn = (90 - math.atan2(first0.dy - p.centerY, first0.dx - p.centerX) * 180 / math.pi) * math.pi / 180;
      Offset rotate(Offset o) =>
          Offset(o.dx * math.cos(turn) - o.dy * math.sin(turn), o.dx * math.sin(turn) + o.dy * math.cos(turn));
      final first = p.toPath(startAngle: 90).computeMetrics().first.getTangentForOffset(0)!.position;
      expect((first - rotate(first0)).distance, lessThan(1e-9));
      final fromCenter = first - rotate(p.center);
      expect(math.atan2(fromCenter.dy, fromCenter.dx) * 180 / math.pi, closeTo(90, 1e-6));
    });
  });
}

double _distanceToPath(Path path, Offset p) {
  var best = double.infinity;
  for (final m in path.computeMetrics()) {
    for (var i = 0; i <= 2000; i++) {
      final q = m.getTangentForOffset(m.length * i / 2000)!.position;
      best = math.min(best, (q - p).distance);
    }
  }
  return best;
}
