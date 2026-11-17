import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/flight.dart';
import 'package:yoin/nowplaying/now_playing_presentation.dart';
import 'package:yoin/nowplaying/now_playing_screen.dart' show tabletopCoverSize, tabletopSplit;
import 'package:yoin/nowplaying/overlay_controller.dart';
import 'package:yoin/nowplaying/transport.dart' show fitTransportControlSize, playModeNaturalWidth;
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

/// A fold half open on its kickstand: 1280 × 800 with the crease across the middle.
const _hinge = Rect.fromLTWH(0, 396, 1280, 8);
const _tabletop = YoinWindowInfo(1280, 800, isTabletop: true, hingeBounds: _hinge);

void main() {
  group('presentation', () {
    test('should_resolveTabletop_when_halfFoldedWhateverTheWidth', () {
      expect(resolveNowPlayingPresentation(_tabletop, fullscreen: false), NowPlayingPresentation.tabletop);
      expect(resolveNowPlayingPresentation(_tabletop, fullscreen: true), NowPlayingPresentation.tabletop);
      // The kickstand half of a small fold is short, but not a landscape handset.
      const short = YoinWindowInfo(840, 420, isTabletop: true, hingeBounds: Rect.fromLTWH(0, 200, 840, 8));
      expect(resolveNowPlayingPresentation(short, fullscreen: false), NowPlayingPresentation.tabletop);
    });

    test('should_haveNoPanelAndNoSharedCover_when_tabletop', () {
      expect(canOpenNowPlayingPanel(_tabletop), isFalse);
      final frame = NowPlayingFrame.resolve(_tabletop, fullscreen: false);
      expect(frame.panelFamily, isFalse);
      expect(NowPlayingPresentation.tabletop.pillMorph, PillMorph.none);
      expect(NowPlayingPresentation.tabletop.coversHost, isTrue);
    });

    test('should_keepFullFlag_when_foldingIntoTabletop', () {
      expect(
        fullscreenAfterLayoutChange(
          previous: LayoutMode.wide,
          current: nowPlayingSizeClass(_tabletop),
          expanded: true,
          wasFullscreen: true,
        ),
        isTrue,
      );
    });
  });

  group('kickstand geometry', () {
    test('should_splitOnTheHinge_when_creaseIsKnown', () {
      final split = tabletopSplit(800, _hinge);
      expect(split.display, 396);
      expect(split.gap, 8);
    });

    test('should_splitInHalf_when_creaseIsMissing', () {
      final split = tabletopSplit(800, null);
      expect(split.display, 400);
      expect(split.gap, 0);
    });

    test('should_sizeCoverByWidthAndDisplayHeight_when_laidOut', () {
      // min(1280 × 0.42 − 32, 396 − 104) = 292.
      expect(tabletopCoverSize(1280, 396), 292);
      // Never below 96.
      expect(tabletopCoverSize(300, 150), TabletopTokens.coverMin);
    });

    test('should_keep72Keys_when_deckRowFitsThem', () {
      // PLAY|PAUSE 130 + 8 + Next 72 + 8 + expand 72 + 8 + play mode 117.2 = 415.2.
      expect(fitTransportControlSize(rowWidth: 1180, controlSize: 72, playKeyWidth: 130, hasExtraKey: true), 72);
      expect(130 + 8 + 72 + 8 + 72 + 8 + playModeNaturalWidth(72), closeTo(415.2, 0.01));
      expect(fitTransportControlSize(rowWidth: 415.2, controlSize: 72, playKeyWidth: 130, hasExtraKey: true), 72);
    });

    test('should_fallBackTo48Keys_when_flipPhoneDeckIsTooNarrow', () {
      // A ~400 wide flip phone on its kickstand: 400 − 2 × 24 = 352 for the row.
      expect(fitTransportControlSize(rowWidth: 352, controlSize: 72, playKeyWidth: 130, hasExtraKey: true), 48);
      // Without the expand key a 340 row still takes 72 (335.2); with it, it doesn't.
      expect(fitTransportControlSize(rowWidth: 340, controlSize: 72, playKeyWidth: 130), 72);
      expect(fitTransportControlSize(rowWidth: 340, controlSize: 72, playKeyWidth: 130, hasExtraKey: true), 48);
    });
  });

  group('controller', () {
    late NowPlayingOverlayController c;

    Future<void> open(WidgetTester tester, YoinWindowInfo window) async {
      c = NowPlayingOverlayController(vsync: tester)
        ..height = window.height
        ..window = window;
      addTearDown(c.dispose);
      c.openPlayer();
      await tester.pumpAndSettle();
    }

    testWidgets('should_neitherDragNorExpand_when_tabletop', (tester) async {
      await open(tester, _tabletop);
      expect(c.presentation, NowPlayingPresentation.tabletop);
      expect(c.canDrag, isFalse);
      c.expandStage();
      expect(c.stage, NowPlayingStage.compact);
      expect(c.flightActive, isFalse, reason: 'no shared cover on the hinge');
      // Back closes it outright.
      expect(c.handleBack(), isTrue);
      expect(c.expanded, isFalse);
      await tester.pumpAndSettle();
    });

    testWidgets('should_dropExpandedStage_when_foldedIntoTabletopWhileOpen', (tester) async {
      await open(tester, const YoinWindowInfo(412, 915));
      c.expandStage();
      await tester.pumpAndSettle();
      expect(c.stage, NowPlayingStage.expanded);
      c.window = _tabletop;
      await tester.pumpAndSettle();
      expect(c.presentation, NowPlayingPresentation.tabletop);
      expect(c.stage, NowPlayingStage.compact);
    });

    testWidgets('should_flyCoverOnly_when_pillStandsUprightOnTheEdge', (tester) async {
      // A short split half under 560 wide: the phone player, but the bar is the edge capsules.
      await open(tester, const YoinWindowInfo(520, 400));
      expect(c.presentation, NowPlayingPresentation.phone);
      expect(c.flightCanFly(FlightElement.cover), isTrue);
      expect(c.flightCanFly(FlightElement.title), isFalse);
      expect(c.flightCanFly(FlightElement.artist), isFalse);
    });
  });
}
