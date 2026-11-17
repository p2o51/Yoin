import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/expressive/loading_indicator.dart';
import 'package:yoin/ui/shapes/shape_path.dart';

// Golden numbers come from running the real Kotlin classes on the JVM
// (graphics-shapes 1.1.0, material3 1.5.0-alpha27 MaterialShapes,
// animation-core FloatSpringSpec). Kotlin runs in Float, hence 1e-5.
const _tol = 1e-5;

void _expectPoints(List<double> actual, List<double> golden) {
  expect(actual.length, golden.length);
  for (var i = 0; i < golden.length; i++) {
    expect(actual[i], closeTo(golden[i], _tol), reason: 'point[$i]');
  }
}

void main() {
  group('ShapeCubic', () {
    test('should_placeControlPointsAtThirds_when_straightLine', () {
      final c = ShapeCubic.straightLine(0, 0, 3, 6);
      _expectPoints(c.points, [0, 0, 1, 2, 2, 4, 3, 6]);
    });

    test('should_joinHalvesOnTheCurve_when_split', () {
      final c = ShapeCubic.of(0, 0, 1, 2, 3, 2, 4, 0);
      final (a, b) = c.split(.3);
      final p = c.pointOnCurve(.3);
      expect(a.anchor1X, closeTo(p.dx, 1e-12));
      expect(b.anchor0Y, closeTo(p.dy, 1e-12));
      expect(a.pointOnCurve(.5).dx, closeTo(c.pointOnCurve(.15).dx, 1e-12));
    });

    test('should_stayOnTheCircle_when_circularArc', () {
      final arc = ShapeCubic.circularArc(0, 0, 1, 0, 0, 1);
      for (var t = 0.0; t <= 1; t += .1) {
        expect(arc.pointOnCurve(t).distance, closeTo(1, 3e-4));
      }
    });
  });

  group('RoundedPolygon', () {
    test('should_keepSharpCorners_when_unrounded', () {
      final square = RoundedPolygon.fromVertices([0, 0, 1, 0, 1, 1, 0, 1]);
      expect(square.features.whereType<CornerFeature>().length, 4);
      expect(square.features.whereType<CornerFeature>().every((c) => c.convex), isTrue);
      expect(square.calculateBounds(), [0, 0, 1, 1]);
      // Zero-length corner cubics are dropped from the outline.
      expect(square.cubics.length, 4);
    });

    test('should_traceACircle_when_circle', () {
      final circle = RoundedPolygon.circle(numVertices: 10, radius: 2);
      for (final c in circle.cubics) {
        for (var t = 0.0; t <= 1; t += .25) {
          expect(c.pointOnCurve(t).distance, closeTo(2, 2e-3));
        }
      }
    });

    test('should_fitTheUnitSquare_when_normalized', () {
      final p = RoundedPolygon.star(5, radius: 3, innerRadius: 1, centerX: 10, centerY: 4).normalized();
      final b = p.calculateBounds();
      expect(math.max(b[2] - b[0], b[3] - b[1]), closeTo(1, 1e-12));
      expect(b[0], greaterThanOrEqualTo(-1e-12));
      expect(b[1], greaterThanOrEqualTo(-1e-12));
    });

    test('should_startMaximaAtFloatMinValue_when_shapeLiesBelowZero', () {
      // graphics-shapes starts calculateBounds' maxima at Float.MIN_VALUE (a
      // tiny positive number), so a shape wholly above y = 0 reports ~0 as its
      // bottom. Kept for parity with Android.
      final p = RoundedPolygon.star(5, radius: 3, innerRadius: 1, centerX: 10, centerY: -4);
      expect(p.calculateBounds()[3], closeTo(0, 1e-12));
    });

    test('should_markReflexCorners_when_starInnerVertices', () {
      final star = RoundedPolygon.star(4, innerRadius: .4);
      final corners = star.features.whereType<CornerFeature>().toList();
      expect([for (final c in corners) c.convex], [true, false, true, false, true, false, true, false]);
    });
  });

  group('MaterialShapes', () {
    // (cubics, features, bounds, first cubic) from the JVM run.
    final goldens = <String, (RoundedPolygon, int, int, List<double>, List<double>)>{
      'softBurst': (
        MaterialShapes.softBurst,
        41,
        40,
        [0.0170549, 0.0, 0.98294514, 1.0],
        [0.18663658, 0.27241567, 0.19362366, 0.26275796, 0.19729336, 0.25070024, 0.19632591, 0.23806655],
      ),
      'cookie9Sided': (
        MaterialShapes.cookie9Sided,
        19,
        36,
        [0.0, 0.014150613, 1.0, 0.9858494],
        [0.5, 0.014150618, 0.53648806, 0.014150613, 0.5729761, 0.027259544, 0.60187346, 0.053477466],
      ),
      'pentagon': (
        MaterialShapes.pentagon,
        11,
        10,
        [0.0, 0.04283998, 1.0, 0.95716006],
        [0.4999999, 0.04283998, 0.53375417, 0.04283998, 0.56750846, 0.053037085, 0.5964094, 0.0734313],
      ),
      'pill': (
        MaterialShapes.pill,
        13,
        20,
        [0.0, 0.0, 1.0, 1.0],
        [0.8731647, 0.12683535, 0.9403969, 0.19406751, 0.98515487, 0.2837738, 0.9954581, 0.38397372],
      ),
      'sunny': (
        MaterialShapes.sunny,
        33,
        32,
        [0.003094779, 0.0, 0.9969052, 1.0],
        [0.9969052, 0.5, 0.9969052, 0.5179457, 0.9907156, 0.5358914, 0.9783364, 0.5504177],
      ),
      'cookie4Sided': (
        MaterialShapes.cookie4Sided,
        17,
        16,
        [1.6295037E-4, 0.0, 0.99983704, 1.0],
        [0.8713652, 0.8707754, 0.8104821, 0.9317583, 0.71593565, 0.95913297, 0.62197703, 0.91859186],
      ),
      'oval': (
        MaterialShapes.oval,
        9,
        16,
        [0.0, 3.4430297E-8, 1.0, 1.0],
        [0.9084564, 0.091543645, 0.942462, 0.12554933, 0.9661038, 0.16991898, 0.97740257, 0.22267362],
      ),
      'circle': (
        MaterialShapes.circle,
        11,
        20,
        [0.0, 0.004045655, 1.0, 0.9959544],
        [1.0, 0.5, 1.0, 0.5517268, 0.9919087, 0.6034536, 0.97572625, 0.6532584],
      ),
    };
    goldens.forEach((name, g) {
      test('should_matchCompose_when_building_$name', () {
        final (polygon, cubics, features, bounds, first) = g;
        expect(polygon.cubics.length, cubics);
        expect(polygon.features.length, features);
        _expectPoints(polygon.calculateBounds(), bounds);
        _expectPoints(polygon.cubics.first.points, first);
      });
    });

    test('should_closeTheOutline_when_anyShape', () {
      for (final p in LoadingIndicatorDefaults.indeterminateIndicatorPolygons) {
        for (var i = 0; i < p.cubics.length; i++) {
          final a = p.cubics[i];
          final b = p.cubics[(i + 1) % p.cubics.length];
          expect(a.anchor1X, closeTo(b.anchor0X, 1e-9));
          expect(a.anchor1Y, closeTo(b.anchor0Y, 1e-9));
        }
      }
    });
  });

  group('Morph', () {
    // (cubic count, first cubic at progress 0.5) per morph of the sequence.
    final goldens = <(int, List<double>)>[
      (58, [0.12605643, 0.29939413, 0.14618999, 0.28794742, 0.16031507, 0.26637632, 0.16270694, 0.24274978]),
      (28, [0.40274924, 0.062158328, 0.4596898, 0.01727398, 0.54031175, 0.017274287, 0.597252, 0.06215924]),
      (22, [0.36438572, 0.07541332, 0.4254817, 0.032873295, 0.5017606, 0.019615741, 0.56994885, 0.037252]),
      (44, [0.58394504, 0.013966716, 0.58452356, 0.0142211635, 0.5850996, 0.014480587, 0.58567303, 0.014744989]),
      (48, [0.9419671, 0.520247, 0.94983387, 0.53196776, 0.9551028, 0.54512376, 0.9577738, 0.55840003]),
      (24, [0.9158131, 0.5897051, 0.9157777, 0.5996618, 0.9151722, 0.60961866, 0.9140246, 0.619544]),
      (48, [0.7087211, 0.07827679, 0.713807, 0.07859328, 0.718856, 0.078583576, 0.7237864, 0.07822077]),
    ];

    test('should_matchCompose_when_morphingTheIndeterminateSequence', () {
      final morphs = LoadingIndicatorDefaults.indeterminateMorphs;
      expect(morphs.length, 7);
      for (var i = 0; i < morphs.length; i++) {
        final (count, first) = goldens[i];
        final cubics = morphs[i].asCubics(.5);
        expect(cubics.length, count, reason: 'morph $i');
        _expectPoints(cubics.first.points, first);
      }
    });

    test('should_extrapolate_when_springOvershoots', () {
      // morph 0 at 1.1, last cubic (JVM golden).
      _expectPoints(
        LoadingIndicatorDefaults.indeterminateMorphs[0].asCubics(1.1).last.points,
        [0.07147384, 0.3253912, 0.077798344, 0.32004854, 0.08361246, 0.31416968, 0.08887279, 0.30782977],
      );
    });

    test('should_matchEndShapes_when_progressIsZeroOrOne', () {
      final morph = Morph(MaterialShapes.pill, MaterialShapes.sunny);
      // Cutting splits cubics (tighter control hulls), so compare the curves
      // themselves: their sampled extents.
      List<double> extent(List<ShapeCubic> cubics) {
        var b = [double.maxFinite, double.maxFinite, -double.maxFinite, -double.maxFinite];
        for (final c in cubics) {
          for (var t = 0.0; t <= 1; t += 1 / 32) {
            final p = c.pointOnCurve(t);
            b = [math.min(b[0], p.dx), math.min(b[1], p.dy), math.max(b[2], p.dx), math.max(b[3], p.dy)];
          }
        }
        return b;
      }

      void expectClose(List<double> a, List<double> b) {
        for (var i = 0; i < 4; i++) {
          expect(a[i], closeTo(b[i], 1e-3));
        }
      }

      expectClose(extent(morph.asCubics(0)), extent(MaterialShapes.pill.cubics));
      expectClose(extent(morph.asCubics(1)), extent(MaterialShapes.sunny.cubics));
    });
  });

  group('LoadingIndicatorDefaults', () {
    test('should_matchCompose_when_scalingTheIndicator', () {
      expect(LoadingIndicatorDefaults.activeIndicatorScale, 38 / 48);
      expect(LoadingIndicatorDefaults.indeterminateScaleFactor, closeTo(0.6852541, _tol));
    });

    test('should_settleIn297ms_when_morphSpringRuns', () {
      expect(LoadingIndicatorDefaults.morphSpringDuration, const Duration(milliseconds: 297));
    });

    test('should_followComposeSpring_when_sampledMidMorph', () {
      const golden = {50: 0.18466093, 100: 0.5273675, 150: 0.82690054, 200: 1.0110908, 250: 1.0863458, 296: 1.0919205};
      golden.forEach((ms, v) {
        expect(LoadingIndicatorDefaults.morphProgressAt(Duration(milliseconds: ms)), closeTo(v, _tol));
      });
      expect(LoadingIndicatorDefaults.morphProgressAt(const Duration(milliseconds: 297)), 1);
    });
  });

  group('LoadingIndicatorFrame', () {
    test('should_startAtSoftBurstTurnedAQuarter_when_justShown', () {
      final f = LoadingIndicatorFrame.at(Duration.zero);
      expect(f.morphIndex, 0);
      expect(f.morphProgress, 0);
      expect(f.rotationDegrees, 90);
    });

    test('should_advanceMorphAndQuarterTurn_when_intervalPasses', () {
      final f = LoadingIndicatorFrame.at(const Duration(milliseconds: 650));
      expect(f.morphIndex, 1);
      expect(f.morphProgress, 0);
      expect(f.rotationDegrees, closeTo(180 + 650 / 4666 * 360, 1e-9));
    });

    test('should_holdShapeAndAngleContinuous_when_crossingAMorphBoundary', () {
      final before = LoadingIndicatorFrame.at(const Duration(microseconds: 649999));
      final after = LoadingIndicatorFrame.at(const Duration(milliseconds: 650));
      expect(before.morphProgress, 1);
      expect(after.morphProgress, 0);
      expect(before.rotationDegrees, closeTo(after.rotationDegrees, 1e-3));
    });

    test('should_wrapToTheFirstMorph_when_sequenceEnds', () {
      expect(LoadingIndicatorFrame.at(const Duration(milliseconds: 650 * 7 + 1)).morphIndex, 0);
    });
  });

  group('ExpressiveLoadingIndicator', () {
    Widget host(Widget child, {bool disableAnimations = false}) => MaterialApp(
          home: MediaQuery(
            data: MediaQueryData(disableAnimations: disableAnimations),
            child: Center(child: child),
          ),
        );

    testWidgets('should_sizeTo48dp_when_defaultYoin', (tester) async {
      await tester.pumpWidget(host(const ExpressiveLoadingIndicator.yoin()));
      expect(tester.getSize(find.byType(ExpressiveLoadingIndicator)), const Size(48, 48));
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pump(const Duration(milliseconds: 400));
      expect(tester.takeException(), isNull);
    });

    testWidgets('should_paintContainerInSecondaryContainer_when_yoin', (tester) async {
      await tester.pumpWidget(host(const ExpressiveLoadingIndicator.yoin(size: 36)));
      final context = tester.element(find.byType(ExpressiveLoadingIndicator));
      final colors = Theme.of(context).colorScheme;
      final painter = tester
          .widgetList<CustomPaint>(find.descendant(of: find.byType(ExpressiveLoadingIndicator), matching: find.byType(CustomPaint)))
          .map((p) => p.painter)
          .whereType<LoadingIndicatorPainter>()
          .single;
      expect(painter.containerColor, colors.secondaryContainer);
      expect(painter.indicatorColor, colors.onSecondaryContainer);
      expect(tester.getSize(find.byType(ExpressiveLoadingIndicator)), const Size(36, 36));
    });

    testWidgets('should_useMaterialDefaults_when_uncontained', (tester) async {
      await tester.pumpWidget(host(const ExpressiveLoadingIndicator(size: 18)));
      final context = tester.element(find.byType(ExpressiveLoadingIndicator));
      final painter = tester
          .widgetList<CustomPaint>(find.descendant(of: find.byType(ExpressiveLoadingIndicator), matching: find.byType(CustomPaint)))
          .map((p) => p.painter)
          .whereType<LoadingIndicatorPainter>()
          .single;
      expect(painter.containerColor, isNull);
      expect(painter.indicatorColor, Theme.of(context).colorScheme.primary);
    });

    testWidgets('should_animate_when_motionAllowed', (tester) async {
      await tester.pumpWidget(host(const ExpressiveLoadingIndicator.yoin()));
      LoadingIndicatorPainter painter() => tester
          .widgetList<CustomPaint>(find.descendant(of: find.byType(ExpressiveLoadingIndicator), matching: find.byType(CustomPaint)))
          .map((p) => p.painter)
          .whereType<LoadingIndicatorPainter>()
          .single;
      await tester.pump(const Duration(milliseconds: 16));
      final first = painter().frame.value.rotationDegrees;
      await tester.pump(const Duration(milliseconds: 120));
      expect(painter().frame.value.rotationDegrees, isNot(first));
    });

    testWidgets('should_holdStill_when_animationsDisabled', (tester) async {
      await tester.pumpWidget(host(const ExpressiveLoadingIndicator.yoin(), disableAnimations: true));
      await tester.pump(const Duration(seconds: 1));
      final painter = tester
          .widgetList<CustomPaint>(find.descendant(of: find.byType(ExpressiveLoadingIndicator), matching: find.byType(CustomPaint)))
          .map((p) => p.painter)
          .whereType<LoadingIndicatorPainter>()
          .single;
      expect(painter.frame.value, same(LoadingIndicatorFrame.still));
      expect(tester.hasRunningAnimations, isFalse);
    });
  });
}
