import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_metrics.dart';
import 'package:yoin/home/home_row_presets.dart';
import 'package:yoin/home/home_rows_resize.dart';
import 'package:yoin/home/home_section.dart';

class _Feedback implements HomeRowsFeedback {
  final List<String> calls = [];

  @override
  void confirm() => calls.add('confirm');

  @override
  void segmentTick() => calls.add('tick');

  @override
  void threshold() => calls.add('threshold');
}

void main() {
  group('geometry', () {
    const heights = {0: 100.0, 1: 200.0, 2: 300.0};

    test('should_bracketTheHeight_when_betweenMeasuredStops', () {
      expect(homeRowsPair(150, 3, heights, fallback: 0), const HomeRowsPair(0, 1));
      expect(homeRowsPair(200, 3, heights, fallback: 0), const HomeRowsPair(1, 2));
      expect(homeRowsPair(320, 3, heights, fallback: 0), const HomeRowsPair(2, 2));
      expect(homeRowsPair(80, 3, heights, fallback: 0), const HomeRowsPair(0, 0));
    });

    test('should_buildTheNeighbourBeforeItIsMeasured_when_onlyRestIsKnown', () {
      // At rest on stop 1 only: growing builds stop 2, shrinking builds stop 0.
      expect(homeRowsPair(200, 3, {1: 200}, fallback: 1), const HomeRowsPair(1, 2));
      expect(homeRowsPair(190, 3, {1: 200}, fallback: 1), const HomeRowsPair(0, 1));
      expect(homeRowsPair(190, 3, const {}, fallback: 1), const HomeRowsPair(1, 1));
    });

    test('should_moveOnPastTheMidpointWithHysteresis_when_findingTheNearestStop', () {
      expect(homeRowsNearest(155, 0, 3, heights, 6), 0);
      expect(homeRowsNearest(157, 0, 3, heights, 6), 1);
      expect(homeRowsNearest(145, 1, 3, heights, 6), 1);
      expect(homeRowsNearest(143, 1, 3, heights, 6), 0);
      expect(homeRowsNearest(290, 0, 3, heights, 6), 2);
      expect(homeRowsNearest(150, 0, 3, {0: 100}, 6), 0);
    });

    test('should_travelOneMoreStop_when_releasedFast', () {
      expect(homeRowsReleaseStop(140, 0, 3, heights, 100), 0);
      expect(homeRowsReleaseStop(140, 0, 3, heights, 700), 1); // 140 + 105 > 150
    });

    test('should_projectOnlyAcrossTheMidpoint_when_fling', () {
      expect(homeRowsReleaseStop(110, 0, 3, heights, 660), 1);
      expect(homeRowsReleaseStop(250, 2, 3, heights, 700), 2);
      expect(homeRowsReleaseStop(190, 1, 3, heights, -700), 0);
      expect(homeRowsReleaseStop(260, 1, 3, heights, -660), 1);
    });

    test('should_easeTheBand_when_pastTheEnds', () {
      expect(homeRowsRubber(0), 0);
      expect(homeRowsRubber(1000), lessThan(HomeRowsMetrics.rubberRange));
      expect(homeRowsRubber(20), lessThan(20));
      expect(homeRowsEdgeRamp(0.5), 0.25);
      expect(homeRowsEdgeRamp(2), 1);
    });

    test('should_measureRecentVelocity_when_fingerMoves', () {
      final v = HomeRowsVelocity()
        ..add(0, 0)
        ..add(10, 10)
        ..add(20, 20);
      expect(v.velocity(20), closeTo(1000, 1));
      expect(v.velocity(200), 0); // stale
      expect(HomeRowsVelocity().velocity(0), 0);
    });
  });

  group('engine', () {
    late _Feedback feedback;
    late List<(HomeSection, HomeRowPreset)> commits;
    late HomeLayout layout;
    late bool editing;

    HomeRowsEngine engineFor(TickerProvider vsync) {
      feedback = _Feedback();
      commits = [];
      layout = HomeLayout.defaultLayout;
      editing = true;
      return HomeRowsEngine(
        vsync: vsync,
        editing: () => editing,
        carrying: () => false,
        currentPreset: (s) => layout.rowsOf(s),
        commit: (s, p) {
          final next = withSectionRows(layout, s, p);
          if (identical(next, layout)) return false;
          commits.add((s, p));
          layout = next;
          return true;
        },
        reduced: () => false,
        feedback: feedback,
      );
    }

    /// Activities on a four-stop phone ladder, resting on L (stop 2), every stop measured.
    HomeRowsTrack seed(HomeRowsEngine engine) {
      final track = engine.track(HomeSection.activities)!;
      track.setLadder(
        'k',
        const [
          [HomeRowPreset.s],
          [HomeRowPreset.m],
          [HomeRowPreset.l],
          [HomeRowPreset.xl],
        ],
        const [1, 2, 3, 4],
        2,
        reset: () => engine.resetTrack(track),
      );
      for (final (i, h) in [(0, 124.0), (1, 252.0), (2, 310.0), (3, 438.0)]) {
        track.recordHeight(i, 400, h);
      }
      return track;
    }

    testWidgets('should_followTheFingerAndWriteTheStop_when_handleDragged', (tester) async {
      final engine = engineFor(tester);
      final track = seed(engine);
      expect(engine.grab(HomeSection.activities, 500, 0), isTrue);
      expect(track.active, isTrue);
      expect(track.height.value, 310);
      engine.dragTo(450, 16);
      expect(track.height.value, 260); // 1:1
      engine.dragTo(420, 32);
      expect(feedback.calls, contains('tick'));
      engine.release(timeMs: 200);
      expect(commits, [(HomeSection.activities, HomeRowPreset.m)]);
      expect(feedback.calls.last, 'confirm');
      expect(track.target, 1);
      await tester.pumpAndSettle();
      expect(track.active, isFalse);
      expect(track.height.value, closeTo(252, 0.5));
      engine.dispose();
    });

    testWidgets('should_rubberBand_when_draggedPastTheLargestStop', (tester) async {
      final engine = engineFor(tester);
      final track = seed(engine);
      engine.grab(HomeSection.activities, 0, 0);
      engine.dragTo(400, 16); // raw 710: 272 past XL
      expect(track.height.value, greaterThan(438));
      expect(track.height.value, lessThan(438 + HomeRowsMetrics.rubberRange));
      expect(feedback.calls, contains('threshold'));
      engine.release(timeMs: 500);
      expect(commits.single, (HomeSection.activities, HomeRowPreset.xl));
      await tester.pumpAndSettle();
      expect(track.height.value, closeTo(438, 0.5));
      engine.dispose();
    });

    testWidgets('should_goBackToTheStartStop_when_cancelled', (tester) async {
      final engine = engineFor(tester);
      final track = seed(engine);
      engine.grab(HomeSection.activities, 0, 0);
      engine.dragTo(-150, 16);
      engine.release(cancelled: true);
      expect(commits, isEmpty);
      expect(track.target, 2);
      await tester.pumpAndSettle();
      expect(track.active, isFalse);
      engine.dispose();
    });

    testWidgets('should_refuseTheHandle_when_notEditingOrOneStop', (tester) async {
      final engine = engineFor(tester);
      seed(engine);
      editing = false;
      expect(engine.grab(HomeSection.activities, 0, 0), isFalse);
      editing = true;
      final jbi = engine.track(HomeSection.jumpBackIn)!;
      jbi.setLadder('j', const [HomeRowPreset.values], const [4], 0, reset: () {});
      jbi.recordHeight(0, 400, 300);
      expect(engine.grab(HomeSection.jumpBackIn, 0, 0), isFalse);
      expect(engine.canStep(HomeSection.jumpBackIn, 1), isFalse);
      expect(engine.track(HomeSection.recentlyAdded), isNull);
      engine.dispose();
    });

    testWidgets('should_stepOnePreset_when_keyboardOrReader', (tester) async {
      final engine = engineFor(tester);
      final track = seed(engine);
      expect(engine.canStep(HomeSection.activities, 1), isTrue);
      expect(engine.step(HomeSection.activities, 1), isTrue);
      expect(commits.single, (HomeSection.activities, HomeRowPreset.xl));
      // The layout change springs the block from L to XL.
      engine.onLayoutChange(HomeLayout.defaultLayout, layout);
      expect(track.active, isTrue);
      expect(track.target, 3);
      await tester.pump(const Duration(milliseconds: 16));
      expect(track.pair, const HomeRowsPair(2, 3));
      await tester.pumpAndSettle();
      expect(track.active, isFalse);
      engine.dispose();
    });

    testWidgets('should_keepTheStoredPreset_when_landingOnAMergedStop', (tester) async {
      final engine = engineFor(tester);
      final track = engine.track(HomeSection.jumpBackIn)!;
      layout = withSectionRows(layout, HomeSection.jumpBackIn, HomeRowPreset.s);
      track.setLadder(
        'j',
        const [
          [HomeRowPreset.s, HomeRowPreset.m],
          [HomeRowPreset.l, HomeRowPreset.xl],
        ],
        const [1, 2],
        0,
        reset: () {},
      );
      track.recordHeight(0, 400, 100);
      track.recordHeight(1, 400, 200);
      expect(engine.step(HomeSection.jumpBackIn, 1), isTrue);
      expect(commits.single, (HomeSection.jumpBackIn, HomeRowPreset.l));
      engine.dispose();
    });

    testWidgets('should_springBack_when_undone', (tester) async {
      final engine = engineFor(tester);
      final track = seed(engine);
      final before = layout;
      final after = withSectionRows(layout, HomeSection.activities, HomeRowPreset.s);
      engine.onLayoutChange(before, after);
      expect(track.target, 0);
      await tester.pump(const Duration(milliseconds: 16));
      await tester.pump(const Duration(milliseconds: 50));
      final mid = track.height.value;
      expect(mid, lessThan(310));
      // Undo mid-flight: springs on from where it shows.
      engine.onLayoutChange(after, before);
      expect(track.target, 2);
      expect(track.height.value, mid);
      await tester.pumpAndSettle();
      expect(track.height.value, closeTo(310, 0.5));
      engine.dispose();
    });

    testWidgets('should_restEverything_when_snapped', (tester) async {
      final engine = engineFor(tester);
      final track = seed(engine);
      engine.grab(HomeSection.activities, 0, 0);
      expect(engine.dragging, isTrue);
      engine.snapAll();
      expect(engine.dragging, isFalse);
      expect(track.active, isFalse);
      // A new ladder (other cards) forgets what was measured.
      track.setLadder('other', track.stopPresets, track.rowCounts, 2, reset: () {});
      expect(track.heights, isEmpty);
      await tester.pump();
      engine.dispose();
    });

    testWidgets('should_interpolateCards_when_betweenStops', (tester) async {
      final engine = engineFor(tester);
      final track = seed(engine);
      track.recordRect(1, 'a', 'small', const Rect.fromLTWH(0, 0, 100, 100));
      track.recordRect(2, 'a', 'small', const Rect.fromLTWH(0, 50, 100, 100));
      track.recordRect(1, 'b', 'wide', const Rect.fromLTWH(0, 120, 300, 100));
      track.recordRect(2, 'b', 'strip', const Rect.fromLTWH(0, 160, 300, 40));
      track.recordRect(2, 'c', 'small', const Rect.fromLTWH(0, 220, 100, 80));
      expect(track.cardLook(2, 'a', reduced: false), HomeRowsCardLook.identity);
      engine.grab(HomeSection.activities, 0, 0);
      engine.dragTo(-29, 16); // halfway between M (252) and L (310)
      expect(track.pair, const HomeRowsPair(1, 2));
      final moved = track.cardLook(2, 'a', reduced: false);
      expect(moved.dy, closeTo(-25, 0.5));
      expect(moved.alpha, 1);
      // Its lower copy hides (the upper one moves).
      expect(track.cardLook(1, 'a', reduced: false).alpha, 0);
      // A new card fades and grows in; reduced motion only fades.
      final incoming = track.cardLook(2, 'c', reduced: false);
      expect(incoming.alpha, inExclusiveRange(0, 1));
      expect(incoming.scale, lessThan(1));
      expect(track.cardLook(2, 'c', reduced: true).scale, 1);
      // A card changing kind fades through.
      expect(track.cardLook(1, 'b', reduced: false).alpha, lessThan(1));
      expect(track.cardLook(2, 'b', reduced: false).alpha, lessThan(1));
      engine.release(cancelled: true);
      await tester.pumpAndSettle();
      engine.dispose();
    });
  });

  testWidgets('should_followTheDriverAndClip_when_bodyIsActive', (tester) async {
    late HomeRowsEngine engine;
    late HomeLayout layout;
    final ladder = [
      for (final (i, p) in HomeRowPreset.values.indexed) HomeRowStop<int>([p], i + 1, i),
    ];
    Widget body(HomeRowPreset preset) => Directionality(
      textDirection: TextDirection.ltr,
      child: HomeRowsScope(
        engine: engine,
        child: Align(
          alignment: Alignment.topLeft,
          child: SizedBox(
            width: 300,
            child: HomeRowsBody<int>(
              track: engine.track(HomeSection.activities),
              ladder: ladder,
              ladderKey: 'test',
              preset: preset,
              builder: (context, stop) => Column(
                children: [
                  for (var i = 0; i <= stop.payload; i++)
                    HomeRowsCard(
                      key: ValueKey(i),
                      cardKey: i,
                      kind: 'row',
                      child: SizedBox(height: 50, child: Text('row $i')),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    layout = HomeLayout.defaultLayout;
    engine = HomeRowsEngine(
      vsync: tester,
      editing: () => true,
      carrying: () => false,
      currentPreset: (s) => layout.rowsOf(s),
      commit: (s, p) {
        layout = withSectionRows(layout, s, p);
        return true;
      },
      reduced: () => false,
      feedback: _Feedback(),
    );
    await tester.pumpWidget(body(HomeRowPreset.l));
    await tester.pump();
    final track = engine.track(HomeSection.activities)!;
    expect(track.stopCount, 4);
    expect(track.restStop, 2);
    expect(tester.getSize(find.byType(HomeRowsBody<int>)).height, 150);
    expect(find.text('row 3'), findsNothing);
    // Drag 25 down: XL is built (measured the next frame) and the body follows the driver.
    expect(engine.grab(HomeSection.activities, 0, 0), isTrue);
    engine.dragTo(25, 16);
    await tester.pump();
    await tester.pump();
    expect(find.text('row 3'), findsOneWidget);
    expect(track.heights[3], 200);
    expect(tester.getSize(find.byType(HomeRowsBody<int>)).height, 175);
    engine.dragTo(45, 32);
    await tester.pump();
    expect(tester.getSize(find.byType(HomeRowsBody<int>)).height, 195);
    engine.release(timeMs: 400);
    expect(layout.rowsOf(HomeSection.activities), HomeRowPreset.xl);
    await tester.pumpWidget(body(HomeRowPreset.xl));
    await tester.pumpAndSettle();
    expect(track.active, isFalse);
    expect(tester.getSize(find.byType(HomeRowsBody<int>)).height, 200);
    await tester.pumpWidget(const SizedBox.shrink());
    engine.dispose();
  });
}
