import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/now_playing_presentation.dart';
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

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

  testWidgets('should_stepOutOfFocusFirst_when_back', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.focusOffered = true;
    c.toggleFocus();
    expect(c.stage, NowPlayingStage.immersive);
    await tester.pumpAndSettle();
    expect(c.focus.value, closeTo(1, 0.001));
    expect(c.detail.value, closeTo(0, 0.001));
    expect(c.canDrag, isTrue, reason: 'focus keeps drag-to-dismiss');

    expect(c.handleBack(), isTrue);
    expect(c.stage, NowPlayingStage.compact);
    expect(c.expanded, isTrue, reason: 'back leaves focus before it closes');
    await tester.pumpAndSettle();
    expect(c.focus.value, closeTo(0, 0.001));
  });

  testWidgets('should_ignoreCoverTap_when_focusNotOffered', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.focusOffered = false;
    c.toggleFocus();
    expect(c.stage, NowPlayingStage.compact);
  });

  testWidgets('should_dropStaleFocus_when_bodyStopsOffering', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.focusOffered = true;
    c.toggleFocus();
    await tester.pumpAndSettle();
    c.focusOffered = false;
    c.dropFocusIfUnoffered();
    expect(c.stage, NowPlayingStage.compact);
    await tester.pumpAndSettle();
  });

  testWidgets('should_expandFromFocus_when_lyricsTapped', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.focusOffered = true;
    c.toggleFocus();
    await tester.pumpAndSettle();
    c.expandStage();
    await tester.pumpAndSettle();
    expect(c.stage, NowPlayingStage.expanded);
    expect(c.detail.value, closeTo(1, 0.001));
    expect(c.focus.value, closeTo(0, 0.001), reason: 'one settle owner drives both');
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

  testWidgets('should_leaveFromTheHeroTitlesScroll_when_closing', (tester) async {
    await setUpController(tester);
    c.openPlayer();
    await tester.pumpAndSettle();
    expect(c.flightKeys.leavesPill, isTrue);
    expect(c.flightKeys.heroTitleScroll, 0);

    c.flightKeys.heroTitleMarquee.offset = 42;
    c.close();
    expect(c.flightKeys.leavesPill, isFalse, reason: 'a close leaves the player');
    expect(c.flightKeys.heroTitleScroll, 42);

    c.openPlayer();
    expect(c.flightKeys.heroTitleScroll, 0, reason: 'an open lands on a rewound marquee');
    await tester.pumpAndSettle();
  });

  testWidgets('should_openSidePanelBesideContent_when_wideWindow', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(1440, 900);
    c.openPlayer();
    expect(c.panelOpen, isTrue);
    expect(c.flightActive, isFalse, reason: 'the panel opens beside the bar: no morph');
    await tester.pumpAndSettle();
    expect(c.offsetX, closeTo(0, 0.5));
    expect(c.besideInset, closeTo(420, 0.5));
    expect(c.barComposed, isTrue, reason: 'the bar stays, folded to its destinations');
  });

  testWidgets('should_stepFullToPanelThenClose_when_backAfterToggle', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(1440, 900);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.toggleFullscreen();
    expect(c.presentation, NowPlayingPresentation.dualPane);
    await tester.pumpAndSettle();
    expect(c.containerWidth, closeTo(1440, 0.5));
    expect(c.besideInset, closeTo(0, 0.5));

    expect(c.handleBack(), isTrue);
    expect(c.presentation, NowPlayingPresentation.panel);
    await tester.pumpAndSettle();
    expect(c.containerWidth, closeTo(420, 0.5));

    expect(c.handleBack(), isTrue);
    expect(c.expanded, isFalse);
    await tester.pumpAndSettle();
    expect(c.isMounted, isFalse);
    expect(c.fullscreen, isFalse, reason: 'the next open starts as the panel');
  });

  testWidgets('should_closeFromDraggedPose_when_panelDraggedPastThreshold', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(800, 1280);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.panelDragUpdate(120);
    expect(c.offsetX, closeTo(120, 0.5));
    expect(c.besideInset, closeTo(280, 0.5), reason: 'the content beside takes the width back 1:1');
    c.panelDragEnd(0);
    expect(c.expanded, isFalse);
    await tester.pumpAndSettle();
    expect(c.isMounted, isFalse);
  });

  testWidgets('should_openFullDirectly_when_detailColumnLeavesNoRoomForPanel', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(1100, 800);
    c.minContentWidth = 744;
    c.openPlayer();
    expect(c.panelOpen, isFalse);
    expect(c.presentation, NowPlayingPresentation.dualPane);
    expect(c.frame.panelAvailable, isFalse, reason: 'no corner toggle: there is no panel to go back to');
    await tester.pumpAndSettle();
    expect(c.besideInset, 0, reason: 'the columns under a Full player are never narrowed');
    expect(c.handleBack(), isTrue);
    expect(c.expanded, isFalse, reason: 'back from this Full closes (no panel step)');
    await tester.pumpAndSettle();
  });

  testWidgets('should_narrowBothColumns_when_panelFitsBesideThem', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(1280, 800);
    c.minContentWidth = 744;
    c.openPlayer();
    expect(c.panelOpen, isTrue);
    await tester.pumpAndSettle();
    expect(c.besideInset, closeTo(420, 0.5));
  });

  testWidgets('should_keepFull_when_columnLeavesUnderFullPlayer', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(1100, 800);
    c.minContentWidth = 744;
    c.openPlayer();
    await tester.pumpAndSettle();
    c.minContentWidth = 320;
    await tester.pump();
    expect(c.presentation, NowPlayingPresentation.dualPane, reason: 'the floor dropping never turns Full into a panel');
    expect(c.frame.panelAvailable, isTrue, reason: 'the toggle (and back to the panel) exists again');
    expect(c.handleBack(), isTrue);
    expect(c.presentation, NowPlayingPresentation.panel);
    await tester.pumpAndSettle();
  });

  testWidgets('should_keepLatchedPanelFrame_when_columnOpensDuringClose', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(1100, 800);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.close();
    c.minContentWidth = 744;
    await tester.pump(const Duration(milliseconds: 16));
    expect(c.presentation, NowPlayingPresentation.panel, reason: 'a leaving panel stays a panel');
    expect(c.offsetY, 0);
    await tester.pumpAndSettle();
    expect(c.isMounted, isFalse);
    expect(c.frame.presentation, NowPlayingPresentation.dualPane, reason: 'the next open goes straight to Full');
  });

  testWidgets('should_closePanel_when_columnOpensWherePanelCannotStay', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(1100, 800);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.yieldToColumn(744);
    expect(c.expanded, isFalse);
    await tester.pumpAndSettle();
  });

  testWidgets('should_stepFullBackToPanel_when_columnOpensWherePanelFits', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(1440, 900);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.toggleFullscreen();
    await tester.pumpAndSettle();
    c.yieldToColumn(744);
    expect(c.expanded, isTrue);
    expect(c.presentation, NowPlayingPresentation.panel);
    await tester.pumpAndSettle();
  });

  testWidgets('should_giveWidthBack1to1_when_panelDraggedOnWideWindow', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(1100, 800);
    c.openPlayer();
    await tester.pumpAndSettle();
    final panel = c.frame.panelWidth;
    for (final dx in [10.0, 40.0, 90.0]) {
      c.panelDragUpdate(dx - c.dragDx.value);
      expect(c.besideInset + c.offsetX, closeTo(panel, 0.5), reason: 'content edge meets panel edge at $dx');
    }
    c.panelDragEnd(0);
    await tester.pump(const Duration(milliseconds: 50));
    expect(c.besideInset + c.offsetX, closeTo(panel, 1), reason: 'and keeps meeting it through the exit');
    await tester.pumpAndSettle();
  });

  testWidgets('should_onlyCloseComposer_when_backWhileWritingInFull', (tester) async {
    await setUpController(tester);
    c.window = const YoinWindowInfo(1100, 800);
    c.openPlayer();
    await tester.pumpAndSettle();
    c.toggleFullscreen();
    await tester.pumpAndSettle();
    var writing = true;
    c.handleComposerBack = () {
      if (!writing) return false;
      writing = false;
      return true;
    };
    expect(c.handleBack(), isTrue);
    expect(writing, isFalse, reason: 'the pen goes down first');
    expect(c.presentation, NowPlayingPresentation.dualPane, reason: 'and Full stays Full (QA r2_37)');
    expect(c.handleBack(), isTrue);
    expect(c.presentation, NowPlayingPresentation.panel, reason: 'the next back is Full → panel');
    await tester.pumpAndSettle();
  });
}
