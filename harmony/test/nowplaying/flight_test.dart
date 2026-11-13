import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/flight.dart';
import 'package:yoin/nowplaying/marquee.dart';

void main() {
  // The pill's title slot (labelLarge, 20 px line) and the hero's (displaySmall, 44 px line).
  const from = Rect.fromLTWH(300, 2440, 160, 20);
  const to = Rect.fromLTWH(24, 700, 360, 44);
  const pillSize = Size(120, 20);
  const heroSize = Size(310, 44);

  group('flightTextPoses', () {
    test('should_drawOnlyThePillLineAtScaleOne_when_flightLandsOnThePill', () {
      final poses = flightTextPoses(from: from, to: to, t: 0, pillSize: pillSize, heroSize: heroSize, heroScaleX: 1.1);
      expect(poses.pill.origin, from.topLeft);
      expect(poses.pill.scale, 1);
      expect(poses.pill.scaleX, 1);
      expect(poses.pill.alpha, 1);
      expect(poses.hero.alpha, 0);
    });

    test('should_drawOnlyTheHeroLineAtItsRestScale_when_flightLandsOnThePlayer', () {
      final poses = flightTextPoses(from: from, to: to, t: 1, pillSize: pillSize, heroSize: heroSize, heroScaleX: 1.1);
      expect(poses.hero.origin, to.topLeft);
      expect(poses.hero.scale, 1);
      expect(poses.hero.scaleX, closeTo(1.1, 1e-9));
      expect(poses.hero.alpha, 1);
      expect(poses.pill.alpha, 0);
    });

    test('should_crossfadeWithAlphasSummingToOne_when_midFlight', () {
      for (final t in [0.3, 0.5, 0.7]) {
        final poses = flightTextPoses(from: from, to: to, t: t, pillSize: pillSize, heroSize: heroSize);
        expect(poses.pill.alpha + poses.hero.alpha, closeTo(1, 1e-9));
        expect(poses.pill.alpha, inExclusiveRange(0, 1));
      }
    });

    test('should_clipThePillLineToItsSlot_when_thePillTextOverflows', () {
      final poses = flightTextPoses(from: from, to: to, t: 0, pillSize: const Size(260, 20), heroSize: heroSize);
      expect(poses.pill.clip.left, from.left);
      expect(poses.pill.clip.width, from.width);
    });

    test('should_notClipThePillLine_when_thePillTextFits', () {
      final poses = flightTextPoses(
        from: const Rect.fromLTWH(300, 2440, 120, 20),
        to: to,
        t: 0,
        pillSize: const Size(120, 20),
        heroSize: heroSize,
      );
      expect(poses.pill.clip.width, greaterThanOrEqualTo(120));
    });
  });

  group('Marquee', () {
    Widget host({required bool ticking}) => MaterialApp(
      home: Center(
        child: SizedBox(
          width: 80,
          child: TickerMode(
            enabled: ticking,
            child: const Marquee(
              text: 'A title far too long to fit in eighty pixels',
              style: TextStyle(fontSize: 14),
              initialDelay: Duration(milliseconds: 100),
            ),
          ),
        ),
      ),
    );

    double offset(WidgetTester tester) => (tester.state(find.byType(Marquee)) as dynamic).debugOffset as double;

    testWidgets('should_restAtTheLineStart_when_itsSlotIsHiddenForTheFlight', (tester) async {
      await tester.pumpWidget(host(ticking: true));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      expect(offset(tester), greaterThan(0));

      await tester.pumpWidget(host(ticking: false));
      expect(offset(tester), 0);
    });

    testWidgets('should_waitTheInitialDelayAgain_when_shownAfterTheFlight', (tester) async {
      await tester.pumpWidget(host(ticking: true));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      await tester.pumpWidget(host(ticking: false));
      await tester.pump(const Duration(milliseconds: 300));

      await tester.pumpWidget(host(ticking: true));
      await tester.pump(const Duration(milliseconds: 16));
      await tester.pump(const Duration(milliseconds: 50));
      expect(offset(tester), 0);
      await tester.pump(const Duration(milliseconds: 400));
      expect(offset(tester), greaterThan(0));
    });
  });
}
