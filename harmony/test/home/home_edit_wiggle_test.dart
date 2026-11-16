import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_edit_wiggle.dart';
import 'package:yoin/home/home_metrics.dart';
import 'package:yoin/home/home_section.dart';

void main() {
  group('geometry', () {
    test('should_startLater_when_furtherFromOrigin', () {
      expect(rippleProgress(1, 3, 0, 5), 1);
      expect(rippleProgress(0.3, 0, 0, 5), greaterThan(rippleProgress(0.3, 3, 0, 5)));
      expect(rippleProgress(0, 0, 0, 1), 0);
    });

    test('should_detuneEachSection_when_paramsRead', () {
      final a = swayParams(HomeSection.activities.id), b = swayParams(HomeSection.jumpBackIn.id);
      expect(a.freqHz, inInclusiveRange(2.4 * 0.93, 2.4 * 1.07));
      expect(swayParams(HomeSection.activities.id).phaseRad, a.phaseRad); // stable
      expect((a.freqHz, a.phaseRad) == (b.freqHz, b.phaseRad), isFalse);
    });

    test('should_swingAboutOneDp_when_cardSized', () {
      expect(cardAmpDeg(100, 100), closeTo(1.1, 1e-9));
      expect(cardAmpDeg(400, 100), HomeWiggleMetrics.cardAmpMinDeg);
      expect(cardAmpDeg(10, 10), HomeWiggleMetrics.cardAmpMaxDeg);
      expect(blockThetaDeg(10000, 10000), HomeWiggleMetrics.thetaMinDeg);
    });

    test('should_fadeNearTheBars_when_edgeBandRead', () {
      expect(edgeBand(100, 200, 50, 800, 48), 1);
      expect(edgeBand(60, 160, 50, 800, 48), closeTo(10 / 48, 1e-9));
      expect(edgeBand(40, 140, 50, 800, 48), 0);
    });

    test('should_addToTheRinging_when_kickedAgain', () {
      expect(kickInitialVelocity(0.5, -1, false, 0), -0.5 * HomeWiggleMetrics.kickGain);
      expect(kickInitialVelocity(0.5, 1, true, -3), -3 - 0.5 * HomeWiggleMetrics.kickGain);
    });

    test('should_alternate_when_cardsNeighbour', () {
      expect(cardAlt(0), 1);
      expect(cardAlt(1), -1);
      expect(cardAngleDeg(0.5, 0, 0, 1, 1, 1, 1), -cardAngleDeg(0.5, 0, 0, -1, 1, 1, 1));
      expect(cardAngleDeg(0.5, 0, 0, 1, 1, 0, 1), 0); // a lifted card stills
    });
  });

  group('HomeEditWiggle', () {
    testWidgets('should_swayThenSettle_when_untouchedForSixSeconds', (tester) async {
      var now = 0;
      final w = HomeEditWiggle(vsync: tester, clock: () => now);
      final order = [HomeSection.activities, HomeSection.jumpBackIn];
      w.update(editing: true, progress: 1, order: order, reduced: false);
      for (var i = 0; i < 30; i++) {
        now += 16;
        await tester.pump(const Duration(milliseconds: 16));
      }
      expect(w.envelope, greaterThan(0.9));
      expect(w.clockRunning, isTrue);
      expect(w.sway(HomeSection.activities), isNot(0));
      now += HomeWiggleMetrics.idleMs + 1;
      for (var i = 0; i < 120; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      expect(w.envelope, lessThan(0.01));
      expect(w.clockRunning, isFalse); // a still page costs nothing
      // A touch brings it back.
      w.touch();
      for (var i = 0; i < 30; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      expect(w.envelope, greaterThan(0.9));
      w.update(editing: false, progress: 0, order: order, reduced: false);
      await tester.pumpAndSettle();
      expect(w.envelope.abs(), lessThan(0.01));
      w.dispose();
    });

    testWidgets('should_swayLess_when_carrying', (tester) async {
      final w = HomeEditWiggle(vsync: tester, clock: () => 0);
      w.update(editing: true, progress: 1, order: const [HomeSection.activities], reduced: false);
      w.carrying = true;
      for (var i = 0; i < 90; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      expect(w.envelope, closeTo(HomeWiggleMetrics.dragEnvelope, 0.05));
      w.update(editing: false, progress: 0, order: const [HomeSection.activities], reduced: false);
      for (var i = 0; i < 120; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      w.dispose();
    });

    testWidgets('should_neitherSwayNorKick_when_reducedMotion', (tester) async {
      final w = HomeEditWiggle(vsync: tester, clock: () => 0);
      w.update(editing: true, progress: 1, order: const [HomeSection.activities], reduced: true);
      w.impulse(HomeSection.activities, 1);
      await tester.pump(const Duration(milliseconds: 100));
      expect(w.clockRunning, isFalse);
      expect(w.blockKick(HomeSection.activities), 0);
      expect(
        w.cardAngle(
          HomeSection.activities,
          alt: 1,
          kickKey: null,
          size: const Size(100, 100),
          top: 300,
          bottom: 400,
          safeTop: 0,
          safeBottom: 1000,
        ),
        0,
      );
      w.dispose();
    });

    testWidgets('should_ringAndDecay_when_kicked', (tester) async {
      final w = HomeEditWiggle(vsync: tester, clock: () => 0);
      w.update(editing: false, progress: 0, order: const [HomeSection.activities], reduced: false);
      w.impulseCard(HomeSection.jumpBackIn, 'card', HomeWiggleMetrics.kickTap);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 30));
      expect(w.cardKick(HomeSection.jumpBackIn, 'card').abs(), greaterThan(0.05));
      await tester.pumpAndSettle();
      expect(w.cardKick(HomeSection.jumpBackIn, 'card'), 0);
      w.dispose();
    });

    testWidgets('should_kickEachBlockOnce_when_entering', (tester) async {
      final w = HomeEditWiggle(vsync: tester, clock: () => 0);
      final order = [HomeSection.activities, HomeSection.jumpBackIn];
      w.update(editing: true, progress: 0.2, order: order, reduced: false);
      expect(w.blockKick(HomeSection.activities), 0);
      w.update(editing: true, progress: 1, order: order, reduced: false);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 20));
      expect(w.blockKick(HomeSection.activities).abs(), greaterThan(0));
      w.update(editing: false, progress: 0, order: order, reduced: false);
      await tester.pumpAndSettle();
      w.dispose();
    });
  });

  testWidgets('should_rotateCardsInPaint_when_editingSways', (tester) async {
    final w = HomeEditWiggle(vsync: tester, clock: () => 0);
    w.update(editing: true, progress: 1, order: const [HomeSection.recentlyAdded], reduced: false);
    await tester.pumpWidget(
      MaterialApp(
        home: Center(
          child: HomeEditWiggleScope(
            wiggle: w,
            section: HomeSection.recentlyAdded,
            liftGain: () => 1,
            safeArea: () => (0, 10000),
            child: const Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                HomeEditCard(index: 0, child: SizedBox.square(dimension: 80)),
                HomeEditCard(index: 1, child: SizedBox.square(dimension: 80)),
                SizedBox(width: 96, height: 80, child: HomeEditBlockWiggle(child: SizedBox.expand())),
              ],
            ),
          ),
        ),
      ),
    );
    for (var i = 0; i < 20; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(tester.takeException(), isNull);
    w.update(editing: false, progress: 0, order: const [HomeSection.recentlyAdded], reduced: false);
    await tester.pumpAndSettle();
    w.dispose();
  });

  testWidgets('should_coverTheBleedOutsideThePlate_when_editing', (tester) async {
    Widget shelf(double progress) => MaterialApp(
      home: Scaffold(
        body: HomeEditPlateScope(
          progress: progress,
          outsetStart: 12,
          outsetEnd: 12,
          child: const HomeEditShelfClip(
            escapeStart: 16,
            escapeEnd: 16,
            child: SizedBox(height: 120, child: ColoredBox(color: Colors.red)),
          ),
        ),
      ),
    );
    await tester.pumpWidget(shelf(0));
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(shelf(1));
    expect(tester.takeException(), isNull);
    expect(find.byType(HomeEditShelfClip), findsOneWidget);
  });
}
