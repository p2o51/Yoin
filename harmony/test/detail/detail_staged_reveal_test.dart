import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/detail/playlist_sleeves.dart';
import 'package:yoin/detail/reshape_scaffold.dart';
import 'package:yoin/home/widgets/home_motion.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/theme/tokens.dart';

ReshapeContent _content({bool sleeves = true}) => ReshapeContent(
  coverUrl: null,
  heroBelow: (_) => const Text('Length'),
  listChildren: const [Text('row')],
  backdrop: sleeves
      ? (cover, leaving) => PlaylistSleeves(urls: const [null, null], cover: cover, leaving: leaving, corner: 8)
      : null,
);

Widget _page(ReshapeContent? content, {bool reduce = false}) => MaterialApp(
  theme: yoinTheme(Brightness.light),
  home: Builder(
    builder: (context) => MediaQuery(
      data: MediaQuery.of(context).copyWith(disableAnimations: reduce),
      child: Scaffold(
        body: ReshapeDetailScaffold(title: 'Mix', meta: const [], content: content),
      ),
    ),
  ),
);

/// The cover's beat (it rises 20) and the hero details' (they rise 14).
double _beat(WidgetTester tester, double rise) =>
    tester.widget<StagedBeat>(find.byWidgetPredicate((w) => w is StagedBeat && w.rise == rise)).beat.value;

double _cover(WidgetTester tester) => _beat(tester, DetailTokens.revealCoverRise);
double _details(WidgetTester tester) => _beat(tester, DetailTokens.revealDetailsRise);

/// Each sleeve's opacity, start sleeve first.
List<double> _sleeves(WidgetTester tester) {
  final positioned = find.descendant(of: find.byType(PlaylistSleeves), matching: find.byType(Positioned));
  final opacities = [
    for (final e in positioned.evaluate())
      tester
          .widget<Opacity>(find.descendant(of: find.byWidget(e.widget), matching: find.byType(Opacity)).first)
          .opacity,
  ];
  // Drawn end sleeve first (the start one on top).
  return opacities.reversed.toList();
}

Future<void> _frames(WidgetTester tester, int ms) async {
  for (var t = 0; t < ms; t += 16) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

void main() {
  group('geometry', () {
    test('should_startBehindTheCoverSmallAndClear_when_theSleeveBeatIsZero', () {
      final pose = PlaylistSleeveGeometry.revealPose(0);
      expect(pose.grow, PlaylistSleeveGeometry.revealScaleFrom);
      expect(pose.open, 0);
      expect(pose.alpha, 0);
      expect(PlaylistSleeveGeometry.centerOffset(0, 200, 0.2, rtl: false, open: 0), Offset.zero);
    });

    test('should_beOpaqueBeforeItLands_when_halfWayOpen', () {
      final pose = PlaylistSleeveGeometry.revealPose(0.5);
      expect(pose.alpha, closeTo(0.7, 1e-9));
      expect(pose.grow, closeTo(0.8, 1e-9));
      expect(PlaylistSleeveGeometry.revealPose(0.8).alpha, 1);
    });

    test('should_restWhereItAlwaysDid_when_landed', () {
      final pose = PlaylistSleeveGeometry.revealPose(1);
      expect((pose.grow, pose.open, pose.alpha), (1.0, 1.0, 1.0));
      expect(
        PlaylistSleeveGeometry.centerOffset(1, 200, 0.2, rtl: false, open: pose.open),
        const Offset(0.2 * 200, PlaylistSleeveGeometry.drop * 200),
      );
    });
  });

  group('reveal', () {
    testWidgets('should_beSettled_when_createdAsPlayed', (tester) async {
      final reveal = StagedReveal.once(vsync: tester, played: true);
      addTearDown(reveal.dispose);
      expect(reveal.played, isTrue);
      expect(reveal.beats.map((b) => b.value), [1, 1, 1]);
    });

    testWidgets('should_startEachBeatOnItsDelay_when_started', (tester) async {
      final reveal = StagedReveal.once(
        vsync: tester,
        delays: const [Duration(milliseconds: 50), Duration(milliseconds: 100)],
      );
      addTearDown(reveal.dispose);
      reveal.start(reducedMotion: false);
      // The clock's first frame is 0 ms; a beat moves from the frame after it starts.
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 60));
      await tester.pump(const Duration(milliseconds: 16));
      expect(reveal.beats[0].value, greaterThan(0));
      expect(reveal.beats[1].value, 0);
      await tester.pump(const Duration(milliseconds: 40));
      await tester.pump(const Duration(milliseconds: 16));
      expect(reveal.beats[1].value, greaterThan(0));
      await tester.pumpAndSettle();
      expect(reveal.beats.map((b) => b.value >= 0.999), [true, true]);
    });
  });

  group('page', () {
    testWidgets('should_landTheCoverThenTheDetailsThenTheSleeves_when_firstOpened', (tester) async {
      await tester.pumpWidget(_page(_content()));
      // Parked until the page has content on screen.
      expect(_cover(tester), 0);
      expect(_details(tester), 0);
      expect(_sleeves(tester), [0, 0]);

      await _frames(tester, 48);
      expect(_cover(tester), greaterThan(0));
      expect(_details(tester), 0, reason: 'the details wait a beat (90 ms)');
      expect(_sleeves(tester), [0, 0], reason: 'the sleeves open 50 ms after the cover starts');

      await _frames(tester, 112);
      expect(_details(tester), greaterThan(0));
      final sleeves = _sleeves(tester);
      expect(sleeves[0], greaterThan(0));
      expect(sleeves[0], greaterThan(sleeves[1]), reason: 'the start sleeve first, the end one 50 ms later');

      await _frames(tester, 2000);
      expect(_cover(tester), greaterThanOrEqualTo(0.999));
      expect(_details(tester), greaterThanOrEqualTo(0.999));
      expect(_sleeves(tester), [1, 1]);
    });

    testWidgets('should_waitForTheContent_when_thePageOpensLoading', (tester) async {
      await tester.pumpWidget(_page(null));
      await _frames(tester, 500);
      await tester.pumpWidget(_page(_content()));
      expect(_cover(tester), 0);
      await _frames(tester, 48);
      expect(_cover(tester), greaterThan(0));
    });

    testWidgets('should_notReplay_when_thePageRebuildsWithNewContent', (tester) async {
      await tester.pumpWidget(_page(_content()));
      await _frames(tester, 2000);
      await tester.pumpWidget(_page(_content(sleeves: false)));
      await tester.pumpWidget(_page(_content()));
      await tester.pump();
      expect(_cover(tester), greaterThanOrEqualTo(0.999));
      expect(_details(tester), greaterThanOrEqualTo(0.999));
      // Sleeves that come back with a settled cover are settled from the start.
      expect(_sleeves(tester), [1, 1]);
    });

    testWidgets('should_showEverythingAtOnce_when_reducedMotion', (tester) async {
      await tester.pumpWidget(_page(_content(), reduce: true));
      await tester.pump();
      await tester.pump();
      expect(_cover(tester), 1);
      expect(_details(tester), 1);
      expect(_sleeves(tester), [1, 1]);
    });
  });
}
