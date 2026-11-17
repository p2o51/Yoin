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

  group('FlightKeys.resolve', () {
    const pillRect = Rect.fromLTWH(100, 2440, 40, 40);
    const playerRect = Rect.fromLTWH(24, 330, 300, 300);

    test('should_keepTheFirstRect_when_theLeavingEndMoves', () {
      final keys = FlightKeys()..leavesPill = false;
      final player = keys.player[FlightElement.title]!;
      expect(keys.resolve(player, playerRect), playerRect);
      // The player slides away under the proxies; they don't chase it.
      expect(keys.resolve(player, playerRect.shift(const Offset(0, 900))), playerRect);
    });

    test('should_followTheLiveRect_when_theArrivingEndMoves', () {
      final keys = FlightKeys()..leavesPill = false;
      final pill = keys.pill[FlightElement.title]!;
      expect(keys.resolve(pill, pillRect.shift(const Offset(0, 120))), pillRect.shift(const Offset(0, 120)));
      expect(keys.resolve(pill, pillRect), pillRect);
    });

    test('should_keepTheLastRect_when_theSlotLeavesTheTree', () {
      final keys = FlightKeys();
      final player = keys.player[FlightElement.cover]!;
      expect(keys.resolve(player, null), isNull);
      keys.resolve(player, playerRect);
      expect(keys.resolve(player, null), playerRect);
    });

    test('should_freezeTheFormerArrivingEnd_when_theFlightReverses', () {
      final keys = FlightKeys()..leavesPill = true;
      final player = keys.player[FlightElement.cover]!;
      keys.resolve(player, playerRect.shift(const Offset(0, 400)));
      keys.resolve(player, playerRect.shift(const Offset(0, 200)));
      keys.leavesPill = false; // closed mid-open
      expect(keys.resolve(player, playerRect), playerRect.shift(const Offset(0, 200)));
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

    testWidgets('should_mirrorTheLiveScroll_when_givenAProbe', (tester) async {
      final probe = MarqueeProbe();
      Widget probed({required bool ticking}) => MaterialApp(
        home: Center(
          child: SizedBox(
            width: 80,
            child: TickerMode(
              enabled: ticking,
              child: Marquee(
                text: 'A title far too long to fit in eighty pixels',
                style: const TextStyle(fontSize: 14),
                initialDelay: const Duration(milliseconds: 100),
                probe: probe,
              ),
            ),
          ),
        ),
      );
      await tester.pumpWidget(probed(ticking: true));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      expect(probe.offset, greaterThan(0));
      expect(probe.offset, offset(tester));

      await tester.pumpWidget(probed(ticking: false));
      expect(probe.offset, 0);
    });

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
