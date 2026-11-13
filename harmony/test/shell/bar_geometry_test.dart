import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/shell/bar_geometry.dart';
import 'package:yoin/theme/tokens.dart';

void main() {
  // A 412 dp phone: 412 − 32 margins − 20 row padding.
  const inner = 360.0;

  test('should_foldPillToZero_when_idle', () {
    final g = resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 1));
    expect(g.pillWidth, 0);
    expect(g.homeWidth, (inner - 16) / 2);
    expect(g.splitWidth + BarTokens.itemGap + g.pillWidth + g.librarySlotWidth, inner);
    expect(g.composePill, isFalse);
    expect(g.labelAlpha, closeTo(1, 1e-9));
  });

  test('should_giveSelectedSectionWiderButton_when_navPose', () {
    final g = resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 0, homeSelection: 1));
    expect(g.homeWidth, 48 * 1.5);
    expect(g.libraryWidth, 48);
    expect(g.pillWidth, inner - 72 - 48 - 16);
    expect(g.composePill, isTrue);
    expect(g.labelAlpha, 0);
    expect(g.pillAlpha, 1);
  });

  test('should_growPressedButtonAndSqueezeOthers_when_pressed', () {
    final pressedHome = resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 0, homePress: 1));
    expect(pressedHome.homeWidth, 48 * 1.75);
    final pressedPill = resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 0, pillPress: 1));
    expect(pressedPill.homeWidth, closeTo(48 * (1.5 - 0.12), 1e-9));
    expect(pressedPill.libraryWidth, closeTo(48 * (1.0 - 0.12), 1e-9));
    expect(pressedPill.pillWidth, greaterThan(inner - 72 - 48 - 16));
  });

  test('should_clampAspectToMinimum_when_squeezedHard', () {
    final g = resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 0, homeSelection: 0, pillPress: 3));
    expect(g.homeWidth, 48 * BarAspects.min);
  });

  test('should_showSplitAndPill_when_detailPose', () {
    final g = resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 1, detailPose: 1));
    expect(g.splitWidth, BarTokens.splitWidth);
    expect(g.librarySlotWidth, 0);
    expect(g.pillWidth, inner - BarTokens.splitWidth - BarTokens.itemGap);
    expect(g.composePill, isTrue, reason: 'the detail pose always has a pill, even with nothing playing');
  });

  test('should_keepSlotsSummingToInnerWidth_when_midMorph', () {
    for (final idle in [0.0, 0.3, 0.7, 1.0]) {
      for (final detail in [0.0, 0.5, 1.0]) {
        final g = resolveBarGeometry(
          BarGeometryInput(innerWidth: inner, idle: idle, detailPose: detail, homeSelection: 0.4),
        );
        expect(g.splitWidth + BarTokens.itemGap + g.pillWidth + g.librarySlotWidth, closeTo(inner, 1e-9));
      }
    }
  });

  test('should_fadeLabelsInOnlyOverIdleTail_when_idleMorphs', () {
    expect(resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 0.5)).labelAlpha, 0);
    expect(resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 0.775)).labelAlpha, closeTo(0.5, 1e-9));
    expect(resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 0.25)).pillAlpha, closeTo(0.5, 1e-9));
    expect(resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 0.996)).composePill, isFalse);
  });

  test('should_fadeNavOutAndSplitIn_when_detailPoseMorphs', () {
    BarGeometry at(double d) => resolveBarGeometry(BarGeometryInput(innerWidth: inner, idle: 0, detailPose: d));
    expect(at(0).navAlpha, 1);
    expect(at(0).splitAlpha, 0);
    expect(at(0.3).navAlpha, closeTo(0.5, 1e-9));
    expect(at(0.4).splitAlpha, 0);
    expect(at(0.7).splitAlpha, closeTo(0.5, 1e-9));
    expect(at(0.6).navAlpha, 0);
    expect(at(1).splitAlpha, 1);
  });

  test('should_showPillWhole_when_anyDetailPoseEvenIdle', () {
    final g = resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 1, detailPose: 0.01));
    expect(g.pillAlpha, 1);
    expect(g.composePill, isTrue);
  });

  test('should_keepSplitAtFixedWidth_when_portraitDetail', () {
    final g = resolveBarGeometry(const BarGeometryInput(innerWidth: inner, idle: 0, detailPose: 1));
    expect(g.splitDetailWidth, BarTokens.splitWidth);
    expect(g.extrasSlotWidth, 0);
  });

  test('should_stretchSplitAndFixPill_when_centredDetailWithShuffle', () {
    // A 600 dp centred bar: 600 − 2 × 8 row padding.
    const centredInner = 584.0;
    final g = resolveBarGeometry(
      const BarGeometryInput(
        innerWidth: centredInner,
        buttonHeight: BarTokens.centeredButtonHeight,
        idle: 0,
        detailPose: 1,
        centered: true,
        promotedCount: 1,
      ),
    );
    const extras = BarTokens.centeredButtonHeight + BarTokens.itemGap;
    expect(g.extrasSlotWidth, extras);
    expect(g.pillWidth, closeTo(BarTokens.detailPillWidth, 1e-9));
    expect(g.splitWidth, centredInner - BarTokens.detailPillWidth - BarTokens.itemGap - extras);
    expect(g.splitWidth + BarTokens.itemGap + g.extrasSlotWidth + g.pillWidth + g.librarySlotWidth, centredInner);
  });

  test('should_neverShrinkSplitBelowItsWidth_when_centredBarNarrow', () {
    final g = resolveBarGeometry(
      const BarGeometryInput(innerWidth: 360, idle: 0, detailPose: 1, centered: true, promotedCount: 1),
    );
    expect(g.splitDetailWidth, BarTokens.splitWidth);
    expect(g.pillWidth, greaterThanOrEqualTo(0));
  });

  test('should_keepSlotsSummingToInnerWidth_when_centredMidMorph', () {
    for (final detail in [0.0, 0.25, 0.5, 0.75, 1.0]) {
      final g = resolveBarGeometry(
        BarGeometryInput(innerWidth: 584, idle: 0.3, detailPose: detail, centered: true, promotedCount: 1),
      );
      expect(
        g.splitWidth + BarTokens.itemGap + g.extrasSlotWidth + g.pillWidth + g.librarySlotWidth,
        closeTo(584, 1e-9),
      );
    }
  });
}
