import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';

void main() {
  late NowPlayingOverlayController c;

  Future<void> setUpController(WidgetTester tester) async {
    c = NowPlayingOverlayController(vsync: tester);
    c.height = 800;
    addTearDown(c.dispose);
  }

  testWidgets('should_riseAndHideBar_when_opened', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    expect(c.expanded, isTrue);
    expect(c.flightActive, isTrue);
    await tester.pumpAndSettle();
    expect(c.open.value, closeTo(1, 0.001));
    expect(c.offsetY, closeTo(0, 1));
    expect(c.barComposed, isFalse);
    expect(c.flightActive, isFalse);
  });

  testWidgets('should_stepExpandedToCompactThenClose_when_backTwice', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.expandStage();
    await tester.pumpAndSettle();
    expect(c.detail.value, closeTo(1, 0.001));

    expect(c.handleBack(), isTrue);
    expect(c.stage, NowPlayingStage.compact);
    expect(c.expanded, isTrue);
    await tester.pumpAndSettle();

    expect(c.handleBack(), isTrue);
    expect(c.expanded, isFalse);
    await tester.pumpAndSettle();
    expect(c.isMounted, isFalse);
    expect(c.handleBack(), isFalse, reason: 'closed: back belongs to the app again');
  });

  testWidgets('should_springBack_when_dragReleasedBeforeThreshold', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.dragUpdate(60);
    expect(c.offsetY, closeTo(60, 0.5));
    expect(c.dismissFraction, closeTo(60 / nowPlayingDismissDistance, 1e-6));
    c.dragEnd(0);
    await tester.pumpAndSettle();
    expect(c.expanded, isTrue);
    expect(c.dragDy.value, closeTo(0, 0.5));
  });

  testWidgets('should_commitFromDraggedPose_when_dragPastThreshold', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.dragUpdate(120);
    c.dragEnd(0);
    expect(c.expanded, isFalse);
    // One displacement: the dragged 120 px were folded into the slide, no jump.
    expect(c.offsetY, closeTo(120, 0.5));
    await tester.pumpAndSettle();
    expect(c.isMounted, isFalse);
  });

  testWidgets('should_commit_when_fastFling', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.dragUpdate(20);
    c.dragEnd(nowPlayingDismissVelocity + 1);
    expect(c.expanded, isFalse);
    await tester.pumpAndSettle();
  });

  testWidgets('should_ignoreDrag_when_expandedStage', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.expandStage();
    c.dragUpdate(200);
    expect(c.dragDy.value, 0);
    expect(c.canDrag, isFalse);
    await tester.pumpAndSettle();
  });

  testWidgets('should_resetStageOnlyAfterExit_when_closedFromExpanded', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.expandStage();
    await tester.pumpAndSettle();
    c.close();
    await tester.pump(const Duration(milliseconds: 16));
    expect(c.stage, NowPlayingStage.expanded, reason: 'the leaving player keeps its pose');
    await tester.pumpAndSettle();
    expect(c.stage, NowPlayingStage.compact);
    expect(c.detail.value, 0);
  });

  testWidgets('should_letInnerLayerTakeBackFirst_when_sheetOpen', (tester) async {
    await setUpController(tester);
    var sheetOpen = true;
    c.handleInnerBack = () {
      if (!sheetOpen) return false;
      sheetOpen = false;
      return true;
    };
    c.openPlayer();
    await tester.pumpAndSettle();
    expect(c.handleBack(), isTrue);
    expect(c.expanded, isTrue);
    expect(c.handleBack(), isTrue);
    expect(c.expanded, isFalse);
    await tester.pumpAndSettle();
  });

  testWidgets('should_onlyFade_when_reducedMotion', (tester) async {
    await setUpController(tester);
    c.reduceMotion = true;
    c.openPlayer();
    expect(c.open.value, 1);
    expect(c.offsetY, 0);
    expect(c.flightActive, isFalse);
    await tester.pumpAndSettle();
    expect(c.fade.value, closeTo(1, 0.001));
  });
  testWidgets('should_keepBarComposed_when_barSettledButFlightStillLanding', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    // The bar's spring (standard default) settles well before the flight's
    // (expressive slow); the pill must stay composed as the flight's source.
    var sawBarSettledMidFlight = false;
    for (var i = 0; i < 120 && c.flightActive; i++) {
      await tester.pump(const Duration(milliseconds: 16));
      if (!c.bar.isAnimating && c.bar.value >= 0.999 && c.flightActive) {
        sawBarSettledMidFlight = true;
        expect(c.barComposed, isTrue, reason: 'the flight still reads the pill slot');
      }
    }
    expect(sawBarSettledMidFlight, isTrue, reason: 'the bar spring settles before the flight');
    await tester.pumpAndSettle();
    expect(c.flightActive, isFalse);
    expect(c.barComposed, isFalse);
  });

  testWidgets('should_forgetLastRects_when_newFlightStarts', (tester) async {
    await setUpController(tester);
    final key = c.flightKeys.pill.values.first;
    c.flightKeys.lastRects[key] = const Rect.fromLTWH(0, 0, 10, 10);
    c.openPlayer();
    expect(c.flightKeys.lastRects, isEmpty);
    c.flightKeys.lastRects[key] = const Rect.fromLTWH(0, 0, 10, 10);
    c.close();
    expect(c.flightKeys.lastRects, isNotEmpty, reason: 'a reversed flight keeps its measurements');
    await tester.pumpAndSettle();
  });
}
