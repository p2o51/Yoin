import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_ui_state.dart';
import 'package:yoin/home/widgets/backdrop_shapes.dart';
import 'package:yoin/home/widgets/home_artwork.dart';
import 'package:yoin/ui/shapes/shape_path.dart';

const _size = Size(100, 100);

List<double> _scaled(List<double> unit) => [for (final v in unit) v * 100];

/// A path's bounds are its cubics' control-point hull: between the shape's
/// true extremes and the hull of its features' (uncut) cubics, which a
/// morph cuts differently from the polygon's own outline.
void _expectBounds(Rect actual, RoundedPolygon shape) {
  final exact = _scaled(shape.calculateBounds(approximate: false));
  final points = [
    for (final f in shape.features)
      for (final c in f.cubics) ...[c.anchor0, c.control0, c.control1, c.anchor1],
  ];
  final hull = _scaled([
    points.map((p) => p.dx).reduce(math.min),
    points.map((p) => p.dy).reduce(math.min),
    points.map((p) => p.dx).reduce(math.max),
    points.map((p) => p.dy).reduce(math.max),
  ]);
  const e = 1e-3;
  expect(actual.left, inInclusiveRange(hull[0] - e, exact[0] + e));
  expect(actual.top, inInclusiveRange(hull[1] - e, exact[1] + e));
  expect(actual.right, inInclusiveRange(exact[2] - e, hull[2] + e));
  expect(actual.bottom, inInclusiveRange(exact[3] - e, hull[3] + e));
}

/// A point inside [inside]'s outline and outside [outside]'s, both unit shapes scaled to 100.
Offset _pointIn(RoundedPolygon inside, RoundedPolygon outside) {
  final a = inside.toPath().transform(Matrix4.diagonal3Values(100, 100, 1).storage);
  final b = outside.toPath().transform(Matrix4.diagonal3Values(100, 100, 1).storage);
  for (var y = 2.0; y < 100; y += 2) {
    for (var x = 2.0; x < 100; x += 2) {
      final p = Offset(x, y);
      if (a.contains(p) && !b.contains(p)) return p;
    }
  }
  throw StateError('no such point');
}

void main() {
  test('should_useAndroidMaterialShapes_when_mappingKinds', () {
    expect(BackdropShapes.forKind(HomeCardKind.album), same(MaterialShapes.bun));
    expect(BackdropShapes.forKind(HomeCardKind.song), same(MaterialShapes.circle));
    expect(BackdropShapes.forKind(HomeCardKind.artist), same(MaterialShapes.circle));
    expect(BackdropShapes.forKind(HomeCardKind.playlist), same(MaterialShapes.ghostish));
    expect(BackdropShapes.pressed, same(MaterialShapes.triangle));
  });

  test('should_matchEndShapes_when_pressedPathAtRestOrHeld', () {
    for (final kind in [HomeCardKind.album, HomeCardKind.song, HomeCardKind.playlist]) {
      final base = BackdropShapes.forKind(kind);
      _expectBounds(BackdropShapes.pressedPath(kind, 0, _size).getBounds(), base);
      _expectBounds(BackdropShapes.pressedPath(kind, 1, _size).getBounds(), MaterialShapes.triangle);
    }
  });

  test('should_clampProgress_when_springOvershoots', () {
    expect(
      BackdropShapes.pressedPath(HomeCardKind.album, 1.2, _size).getBounds(),
      BackdropShapes.pressedPath(HomeCardKind.album, 1, _size).getBounds(),
    );
    expect(
      BackdropShapes.pressedPath(HomeCardKind.album, -0.2, _size).getBounds(),
      BackdropShapes.pressedPath(HomeCardKind.album, 0, _size).getBounds(),
    );
  });

  test('should_reuseOneMorph_when_askedTwice', () {
    expect(BackdropShapes.pressMorph(HomeCardKind.album), same(BackdropShapes.pressMorph(HomeCardKind.album)));
    expect(BackdropShapes.pressMorph(HomeCardKind.song), same(BackdropShapes.pressMorph(HomeCardKind.artist)));
  });

  testWidgets('should_paintBunThenTriangle_when_pressProgressMoves', (tester) async {
    final morph = AnimationController(vsync: const TestVSync());
    addTearDown(morph.dispose);
    await tester.pumpWidget(
      Center(
        child: SizedBox.fromSize(
          size: _size,
          child: CustomPaint(painter: BackdropShapePainter(HomeCardKind.album, const Color(0xFF336699), morph)),
        ),
      ),
    );
    final bunOnly = _pointIn(MaterialShapes.bun, MaterialShapes.triangle);
    final triangleOnly = _pointIn(MaterialShapes.triangle, MaterialShapes.bun);
    final paintBox = find.byType(CustomPaint).last;
    expect(paintBox, paints..path(includes: [bunOnly], excludes: [triangleOnly]));
    morph.value = 1;
    await tester.pump();
    expect(paintBox, paints..path(includes: [triangleOnly], excludes: [bunOnly]));
  });
}
