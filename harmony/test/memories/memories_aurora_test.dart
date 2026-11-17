import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/memories_aurora.dart';
import 'package:yoin/memories/palette/memory_palette.dart';
import 'package:yoin/theme/tokens.dart';

const _red = MemoryPalette(
  base: Color(0xFFFF0000),
  accent: Color(0xFF800000),
  deep: Color(0xFF400000),
  soft: Color(0xFFFFC0C0),
);
const _blue = MemoryPalette(
  base: Color(0xFF0000FF),
  accent: Color(0xFF000080),
  deep: Color(0xFF000040),
  soft: Color(0xFFC0C0FF),
);

void main() {
  test('should_lerpNeighbourPalettes_when_pagerBetweenCards', () {
    final (base, accent) = deckAuroraColors([_red, _blue], 0.5);
    expect(base, Color.lerp(_red.base, _blue.base, 0.5));
    expect(accent, Color.lerp(_red.accent, _blue.accent, 0.5));
  });

  test('should_holdEndPalette_when_pagerOverscrolls', () {
    expect(deckAuroraColors([_red, _blue], -0.4).$1, _red.base);
    expect(deckAuroraColors([_red, _blue], 1.6).$1, _blue.base);
    expect(deckAuroraColors([_red], 0).$1, _red.base);
  });

  test('should_beClear_when_deckHasNoPalettes', () {
    expect(deckAuroraColors(const [], 0).$1.a, 0);
  });

  test('should_turnTheField_when_pagerMoves', () {
    List<Offset> centres(double position) => [
      for (final b in auroraBlooms(
        size: const Size(400, 800),
        position: position,
        base: _red.base,
        accent: _red.accent,
        dark: false,
        presence: 1,
      ))
        b.center,
    ];
    // Moving with the finger: a fifth of a page moves every bloom.
    final rest = centres(1), moved = centres(1.2);
    for (var i = 0; i < 3; i++) {
      expect((moved[i] - rest[i]).distance, greaterThan(1));
    }
    // Each bloom on its own orbit: the third turns faster than the first (parallax).
    final first = (centres(0.1)[0] - centres(0)[0]).distance;
    final third = (centres(0.1)[2] - centres(0)[2]).distance;
    expect(third, greaterThan(first));
  });

  test('should_shrinkDeeperBlooms_when_layered', () {
    final blooms = auroraBlooms(
      size: const Size(400, 800),
      position: 0,
      base: _red.base,
      accent: _red.accent,
      dark: true,
      presence: 1,
    );
    expect(blooms, hasLength(3));
    // Radius ≈ half the longest side, the deeper the smaller (at the same wobble they'd be strictly ordered).
    for (final b in blooms) {
      expect(b.radius, inInclusiveRange(800 * 0.2, 800 * 0.62));
      expect(b.alpha, lessThanOrEqualTo(MemoriesAuroraTokens.alphaDark));
    }
  });

  test('should_scaleAlphaWithPresence_when_fading', () {
    double alpha(double presence, bool dark) => auroraBlooms(
      size: const Size(400, 800),
      position: 0,
      base: _red.base,
      accent: _red.accent,
      dark: dark,
      presence: presence,
    ).first.alpha;
    expect(alpha(0.5, false), closeTo(alpha(1, false) / 2, 1e-9));
    expect(alpha(1, true), greaterThan(alpha(1, false)));
  });

  testWidgets('should_repaintWithPager_when_deckSwipes', (tester) async {
    final position = ValueNotifier<double>(0);
    var painted = 0;
    await tester.pumpWidget(
      MaterialApp(
        home: MemoriesAurora(
          palettes: const [_red, _blue],
          position: () {
            painted++;
            return position.value;
          },
          repaint: position,
          visible: true,
          dark: false,
          ground: Colors.white,
        ),
      ),
    );
    await tester.pumpAndSettle();
    final before = painted;
    // At rest nothing new is drawn.
    await tester.pump(const Duration(seconds: 1));
    expect(painted, before);
    position.value = 0.3;
    await tester.pump();
    expect(painted, greaterThan(before));
  });

  testWidgets('should_fadeOut_when_deckRetreats', (tester) async {
    final position = ValueNotifier<double>(0);
    Widget aurora(bool visible) => MaterialApp(
      home: MemoriesAurora(
        palettes: const [_red],
        position: () => position.value,
        repaint: position,
        visible: visible,
        dark: false,
        ground: Colors.white,
      ),
    );
    MemoriesAuroraPainter painter() =>
        tester
                .widget<CustomPaint>(
                  find.descendant(of: find.byType(MemoriesAurora), matching: find.byType(CustomPaint)),
                )
                .painter!
            as MemoriesAuroraPainter;
    await tester.pumpWidget(aurora(true));
    await tester.pumpAndSettle();
    expect(painter().presence.value, closeTo(1, 0.01));
    await tester.pumpWidget(aurora(false));
    await tester.pump(const Duration(milliseconds: 50));
    // On the slow effects spring: still on its way out, not cut.
    expect(painter().presence.value, inExclusiveRange(0.01, 1));
    await tester.pumpAndSettle();
    expect(painter().presence.value, closeTo(0, 0.01));
  });
}
