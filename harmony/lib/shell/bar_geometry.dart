import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/foundation.dart';

import '../theme/tokens.dart';

/// Bar aspect constants (YoinButtonGroup.kt L1127-1131; spec: BarTokens
/// additions selectedAspect / pressExpand / neighbourSqueeze / minAspect —
/// not in tokens.dart yet).
abstract final class BarAspects {
  static const double selected = 1.5;
  static const double resting = 1.0;
  static const double pressExpand = 0.25;
  static const double neighbourSqueeze = 0.12;
  static const double min = 0.7;
}

/// The inputs of one bar frame, all already spring-animated.
@immutable
final class BarGeometryInput {
  const BarGeometryInput({
    required this.innerWidth,
    this.buttonHeight = BarTokens.buttonHeight,
    this.homeSelection = 1,
    this.idle = 1,
    this.homePress = 0,
    this.libraryPress = 0,
    this.pillPress = 0,
    this.detailPose = 0,
    this.centered = false,
    this.promotedCount = 0,
  });

  /// The row's width inside the surface's padding (window − 2 × 16 − 2 × 10 on a phone).
  final double innerWidth;

  /// 48 on the portrait bar, 44 on the centred bar.
  final double buttonHeight;

  /// 1 = Home selected, 0 = Library selected.
  final double homeSelection;

  /// 1 = nothing playing (two labelled halves), 0 = a track exists (nav pose).
  final double idle;

  /// Press echoes, 0..1 each.
  final double homePress;
  final double libraryPress;
  final double pillPress;

  /// 0 = nav pose, 1 = a detail page's pose (Play split + pill).
  final double detailPose;

  /// The centred bar (Medium / Wide): in the detail pose the Play split
  /// stretches and the pill is [BarTokens.detailPillWidth] wide.
  final bool centered;

  /// Page actions pulled out of the ▾ menu into round buttons beside the
  /// split (the centred bar promotes Shuffle); 0 on the portrait bar.
  final int promotedCount;
}

/// Widths of one bar frame (the phone subset of resolveBarGeometry: morph 0,
/// pane 0, navOnly 0, edit 0, plus the detail pose of library spec D8).
/// Row = [home / split slot][gap][extras slot][pill][library slot = gap + library button].
@immutable
final class BarGeometry {
  const BarGeometry({
    required this.homeWidth,
    required this.splitWidth,
    required this.pillWidth,
    required this.librarySlotWidth,
    required this.libraryWidth,
    required this.extrasSlotWidth,
    required this.splitDetailWidth,
    required this.navAlpha,
    required this.splitAlpha,
    required this.labelAlpha,
    required this.pillAlpha,
    required this.composePill,
  });

  /// The Home button's width (fades out into the Play split in detail pose).
  final double homeWidth;

  /// The leading slot's width: Home → Play split as the detail pose grows.
  final double splitWidth;
  final double pillWidth;

  /// Gap + Library button; 0 in detail pose.
  final double librarySlotWidth;
  final double libraryWidth;

  /// The promoted extras after the split, each with its trailing gap; 0 in nav pose.
  final double extrasSlotWidth;

  /// The split's width at rest in the detail pose.
  final double splitDetailWidth;

  /// Home / Library fade out over the first 60 % of the pose change, the
  /// split and its extras fade in over the last 60 % (YoinButtonGroup).
  final double navAlpha;
  final double splitAlpha;

  /// Home / Library labels (idle tail).
  final double labelAlpha;
  final double pillAlpha;

  /// The pill is composed only while it could be seen (an idle 0 dp pill
  /// would still marquee and hit-test).
  final bool composePill;

  double get leadingSlotWidth => splitWidth;
}

double _clamp01(double v) => v.clamp(0.0, 1.0);

BarGeometry resolveBarGeometry(BarGeometryInput i) {
  const gap = BarTokens.itemGap;
  final selection = _clamp01(i.homeSelection);
  double aspect(double selected, double press) => math.max(
    BarAspects.min,
    lerpDouble(BarAspects.resting, BarAspects.selected, selected)! +
        BarAspects.pressExpand * press -
        BarAspects.neighbourSqueeze * i.pillPress,
  );
  final homeRest = i.buttonHeight * aspect(selection, i.homePress);
  final libRest = i.buttonHeight * aspect(1 - selection, i.libraryPress);

  // The idle halves (idleWeight): the detail pose replaces the left slot
  // with the split and drops the right one, so it needs no factor of its own.
  final detail = _clamp01(i.detailPose);
  final idle = _clamp01(i.idle);
  final idleHalf = math.max(0.0, (i.innerWidth - 2 * gap) / 2);
  final homeW = lerpDouble(homeRest, idleHalf, idle)!;
  final libW = lerpDouble(libRest, idleHalf, idle)!;

  // CenteredBar detail pose: [Play split (stretches)] [extras…] [pill 200].
  final extrasWidth = (i.buttonHeight + gap) * math.max(0, i.promotedCount);
  final splitDetailW = i.centered
      ? math.max(BarTokens.splitWidth, i.innerWidth - BarTokens.detailPillWidth - gap - extrasWidth)
      : BarTokens.splitWidth;
  final splitW = lerpDouble(homeW, splitDetailW, detail)!;
  final extrasSlot = lerpDouble(0, extrasWidth, detail)!;
  final librarySlot = lerpDouble(gap + libW, 0, detail)!;
  final pillW = math.max(0.0, i.innerWidth - splitW - gap - extrasSlot - librarySlot);

  return BarGeometry(
    homeWidth: homeW,
    splitWidth: splitW,
    pillWidth: pillW,
    librarySlotWidth: librarySlot,
    libraryWidth: libW,
    extrasSlotWidth: extrasSlot,
    splitDetailWidth: splitDetailW,
    navAlpha: _clamp01(1 - detail / 0.6),
    splitAlpha: _clamp01((detail - 0.4) / 0.6),
    labelAlpha: _clamp01((idle - 0.55) / 0.45),
    // Any detail pose shows the pill whole ("Nothing playing" when idle).
    pillAlpha: detail > 0.005 ? 1 : _clamp01(1 - idle / 0.5),
    composePill: idle < 0.995 || detail > 0.005,
  );
}
