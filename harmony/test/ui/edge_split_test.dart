import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/posture/display_posture.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/edge_split.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

void main() {
  group('segments', () {
    test('should_splitAroundCutout_when_edgeHasCentredCamera', () {
      // A 390 tall landscape phone with its camera at 176..212 on the capsules' edge.
      final s = computeEdgeSplitSegments(
        windowHeight: 390,
        topInset: 0,
        bottomInset: 0,
        cutoutTop: 176,
        cutoutBottom: 212,
      );
      expect(s.upperTop, 14);
      expect(s.upperBottom, 168);
      expect(s.lowerTop, 220);
      expect(s.lowerBottom, 374);
      expect(s.roomy, isFalse);
    });

    test('should_closeGapAndGainSlot_when_edgeHasNoCutout', () {
      final s = computeEdgeSplitSegments(windowHeight: 390, topInset: 0, bottomInset: 0);
      // The lower capsule keeps the place a centred 36 tall cutout would give it.
      expect(s.lowerTop, (14 + 374) / 2 + 18 + 8);
      expect(s.upperBottom, s.lowerTop - EdgeSplitTokens.cutoutGap);
      expect(s.roomy, isTrue);
    });

    test('should_keepClearOfCornerCutout_when_noRoomForACapsuleBesideIt', () {
      // A cutout near the top: no 128 capsule fits above it.
      final s = computeEdgeSplitSegments(
        windowHeight: 390,
        topInset: 0,
        bottomInset: 0,
        cutoutTop: 20,
        cutoutBottom: 60,
      );
      expect(s.upperTop, 68);
      expect(s.roomy, isTrue);
      expect(s.upperHeight, greaterThan(0));
      expect(s.lowerBottom, 374);
    });

    test('should_respectSystemInsets_when_statusAndNavigationBarsShow', () {
      final s = computeEdgeSplitSegments(windowHeight: 390, topInset: 24, bottomInset: 20);
      expect(s.upperTop, 28);
      expect(s.lowerBottom, 390 - 16 - 20);
    });
  });

  test('should_standOnCutoutEdge_when_phoneTurnedTheOtherWay', () {
    expect(resolveEdgeSplitSide(hasLeftCutout: false, hasRightCutout: true), EdgeSplitSide.right);
    expect(resolveEdgeSplitSide(hasLeftCutout: true, hasRightCutout: true), EdgeSplitSide.left);
    expect(resolveEdgeSplitSide(hasLeftCutout: false, hasRightCutout: false), EdgeSplitSide.left);
    expect(edgeSplitGroupX(windowWidth: 844, side: EdgeSplitSide.left), 8);
    expect(edgeSplitGroupX(windowWidth: 844, side: EdgeSplitSide.right, edgeShift: 10), 844 - 8 - 10 - 64);
  });

  group('shell chrome insets', () {
    const phone = YoinWindowInfo(844, 390);
    const padding = EdgeInsets.only(left: 0, right: 0, bottom: 12);

    test('should_reserveCapsuleBandOnCutoutEdge_when_edgeSplit', () {
      const cutouts = EdgeCutouts(left: SideCutout(top: 176, bottom: 212, inset: 30));
      final insets = resolveShellChromeInsets(window: phone, viewPadding: padding, cutouts: cutouts);
      expect(insets.left, EdgeSplitTokens.contentStart);
      expect(insets.right, 0);
      expect(insets.bottom, 12);
    });

    test('should_mirrorBand_when_cutoutOnTheRight', () {
      const cutouts = EdgeCutouts(right: SideCutout(top: 176, bottom: 212, inset: 30));
      final insets = resolveShellChromeInsets(window: phone, viewPadding: padding, cutouts: cutouts);
      expect(insets.right, EdgeSplitTokens.contentStart);
      expect(insets.left, 0);
    });

    test('should_clearOtherEdgeCutout_when_capsulesOnTheLeft', () {
      // Cutouts on both edges: the capsules stay left, the content stops short of the right one.
      const cutouts = EdgeCutouts(
        left: SideCutout(top: 176, bottom: 212, inset: 30),
        right: SideCutout(top: 176, bottom: 212, inset: 28),
      );
      final insets = resolveShellChromeInsets(window: phone, viewPadding: padding, cutouts: cutouts);
      expect(insets.left, EdgeSplitTokens.contentStart);
      expect(insets.right, 28);
    });

    test('should_reserveBarHeight_when_barForms', () {
      expect(
        resolveShellChromeInsets(window: const YoinWindowInfo(412, 915), viewPadding: padding).bottom,
        BarTokens.height + 2 * BarTokens.portraitVerticalMargin + 12,
      );
      expect(
        resolveShellChromeInsets(window: const YoinWindowInfo(800, 1280), viewPadding: padding).bottom,
        BarTokens.centeredHeight + BarTokens.centeredBottomMargin + BarTokens.floatingContentGap + 12,
      );
    });
  });

  testWidgets('should_insetPageButKeepWashFullBleed_when_bandOnLandscapePhone', (tester) async {
    tester.view.physicalSize = const Size(844, 390);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    const page = Key('page');
    const wash = Key('wash');
    Widget app() => MediaQuery(
      data: const MediaQueryData(size: Size(844, 390)),
      child: Directionality(
        textDirection: TextDirection.ltr,
        child: YoinWindowScope.measured(
          child: const ShellChromeBand(
            background: SizedBox.expand(key: wash),
            child: SizedBox.expand(key: page),
          ),
        ),
      ),
    );
    await tester.pumpWidget(app());
    expect(tester.getRect(find.byKey(page)).left, EdgeSplitTokens.contentStart);
    expect(tester.getRect(find.byKey(wash)).left, 0);
    // A tall window: the page fills it, no band to paint.
    tester.view.physicalSize = const Size(412, 915);
    await tester.pumpWidget(app());
    expect(tester.getRect(find.byKey(page)).left, 0);
    expect(find.byKey(wash), findsNothing);
  });
}
