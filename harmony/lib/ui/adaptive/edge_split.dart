/// The short window's Button Group geometry (ShellChromeForm.EdgeSplit,
/// WindowAdaptiveRuntime.kt "Edge-split geometry", 断点交接 §2.2), and the
/// space every page leaves for the group in each form (LocalShellChromeInsets).
///
/// Pure where it can be (unit-tested); the widgets at the bottom read the live
/// window: [YoinWindowInfo], the system insets (MediaQuery.viewPadding) and the
/// cutouts from [DisplayPostureScope] (`yoin/posture`).
library;

import 'dart:math' as math;

import 'package:flutter/widgets.dart';

import '../../platform/posture/display_posture.dart';
import '../../theme/tokens.dart';
import 'yoin_window_info.dart';

typedef _E = EdgeSplitTokens;

/// Which edge of a short window holds the capsules: the edge with the camera
/// cutout, so the chrome sits in the band content can't use. A phone turned
/// the other way moves its cutout — and the capsules with it — to the right.
/// No cutout (or one on both edges): the left, as designed.
enum EdgeSplitSide { left, right }

EdgeSplitSide resolveEdgeSplitSide({required bool hasLeftCutout, required bool hasRightCutout}) =>
    hasRightCutout && !hasLeftCutout ? EdgeSplitSide.right : EdgeSplitSide.left;

/// Where the cutouts sit on the window's side edges right now (EdgeCutouts).
@immutable
final class EdgeCutouts {
  const EdgeCutouts({this.left, this.right});

  static const EdgeCutouts none = EdgeCutouts();

  factory EdgeCutouts.of(DisplayPosture posture) => EdgeCutouts(left: posture.leftCutout, right: posture.rightCutout);

  final SideCutout? left;
  final SideCutout? right;

  double get leftInset => left?.inset ?? 0;
  double get rightInset => right?.inset ?? 0;

  /// The edge the capsules live on: the cutout's.
  EdgeSplitSide get side => resolveEdgeSplitSide(hasLeftCutout: left != null, hasRightCutout: right != null);

  /// The cutout on [side]'s edge, or null.
  SideCutout? get sideCutout => side == EdgeSplitSide.right ? right : left;

  @override
  bool operator ==(Object other) => other is EdgeCutouts && other.left == left && other.right == right;

  @override
  int get hashCode => Object.hash(left, right);
}

/// The two capsules' vertical extents, in window logical px.
@immutable
final class EdgeSplitSegments {
  const EdgeSplitSegments({
    required this.upperTop,
    required this.upperBottom,
    required this.lowerTop,
    required this.lowerBottom,
    required this.hasCutout,
  });

  final double upperTop;
  final double upperBottom;
  final double lowerTop;
  final double lowerBottom;

  /// The capsules sit around a real cutout on their edge.
  final bool hasCutout;

  double get upperHeight => upperBottom - upperTop;
  double get lowerHeight => lowerBottom - lowerTop;

  /// No cutout to step around: the upper capsule closes the gap and gains a
  /// slot (Shuffle, in the detail pose).
  bool get roomy => !hasCutout;

  @override
  bool operator ==(Object other) =>
      other is EdgeSplitSegments &&
      other.upperTop == upperTop &&
      other.upperBottom == upperBottom &&
      other.lowerTop == lowerTop &&
      other.lowerBottom == lowerBottom &&
      other.hasCutout == hasCutout;

  @override
  int get hashCode => Object.hash(upperTop, upperBottom, lowerTop, lowerBottom, hasCutout);

  @override
  String toString() => 'EdgeSplitSegments($upperTop..$upperBottom | $lowerTop..$lowerBottom, cutout $hasCutout)';
}

/// Cut the edge into the two capsules around its cutout (computeEdgeSplitSegments).
///
/// Upper = [topInset] + 4 (at least 14) → cutout.top − 8; lower = cutout.bottom
/// + 8 → window − 16 − [bottomInset]. Without a cutout the lower capsule keeps
/// the place a centred 36 tall cutout would give it and the upper one closes
/// the gap. A corner cutout (no room for a capsule on one side of it) is
/// kept clear of and the rest split as if there were none.
EdgeSplitSegments computeEdgeSplitSegments({
  required double windowHeight,
  required double topInset,
  required double bottomInset,
  double? cutoutTop,
  double? cutoutBottom,
}) {
  var top = math.max(_E.topMargin, topInset + _E.topInsetAir);
  var bottom = windowHeight - _E.bottomMargin - bottomInset;
  if (cutoutTop != null && cutoutBottom != null) {
    final upperBottom = cutoutTop - _E.cutoutGap;
    final lowerTop = cutoutBottom + _E.cutoutGap;
    if (upperBottom - top >= _E.minCapsule && bottom - lowerTop >= _E.minCapsule) {
      return EdgeSplitSegments(
        upperTop: top,
        upperBottom: upperBottom,
        lowerTop: lowerTop,
        lowerBottom: bottom,
        hasCutout: true,
      );
    }
    final middle = (top + bottom) / 2;
    if (cutoutTop > middle) {
      bottom = math.min(bottom, upperBottom);
    } else {
      top = math.max(top, lowerTop);
    }
  }
  final lowerTop = (top + bottom) / 2 + _E.nominalCutoutHeight / 2 + _E.cutoutGap;
  return EdgeSplitSegments(
    upperTop: top,
    upperBottom: lowerTop - _E.cutoutGap,
    lowerTop: lowerTop,
    lowerBottom: bottom,
    hasCutout: false,
  );
}

/// The capsules' x in the window: [EdgeSplitTokens.groupInset] in from their
/// edge, stepped inward by a navigation inset on that edge ([edgeShift]).
double edgeSplitGroupX({required double windowWidth, required EdgeSplitSide side, double edgeShift = 0}) =>
    side == EdgeSplitSide.right ? windowWidth - _E.groupInset - edgeShift - _E.groupWidth : _E.groupInset + edgeShift;

/// The space a page leaves for the Button Group (rememberShellChromeInsets),
/// physical sides: the bar forms reserve their height at the bottom; the edge
/// capsules reserve the 84 band on their edge (+ a navigation inset there)
/// and the cutout / navigation inset on the other.
EdgeInsets resolveShellChromeInsets({
  required YoinWindowInfo window,
  required EdgeInsets viewPadding,
  EdgeCutouts cutouts = EdgeCutouts.none,
}) => switch (window.chromeForm) {
  ShellChromeForm.edgeSplit =>
    cutouts.side == EdgeSplitSide.right
        ? EdgeInsets.only(
            left: math.max(cutouts.leftInset, viewPadding.left),
            right: _E.contentStart + viewPadding.right,
            bottom: viewPadding.bottom,
          )
        : EdgeInsets.only(
            left: _E.contentStart + viewPadding.left,
            right: math.max(cutouts.rightInset, viewPadding.right),
            bottom: viewPadding.bottom,
          ),
  ShellChromeForm.centeredBar => EdgeInsets.only(
    bottom:
        BarTokens.centeredHeight +
        (window.layoutMode == LayoutMode.wide ? BarTokens.centeredBottomMarginWide : BarTokens.centeredBottomMargin) +
        BarTokens.floatingContentGap +
        viewPadding.bottom,
  ),
  ShellChromeForm.portraitBar => EdgeInsets.only(
    bottom: BarTokens.height + 2 * BarTokens.portraitVerticalMargin + viewPadding.bottom,
  ),
};

/// The display's posture for a subtree (main.dart provides the live one).
class DisplayPostureScope extends InheritedWidget {
  const DisplayPostureScope({super.key, required this.posture, required super.child});

  final DisplayPosture posture;

  static DisplayPosture of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<DisplayPostureScope>()?.posture ?? DisplayPosture.flat;

  /// The window's side cutouts.
  static EdgeCutouts cutoutsOf(BuildContext context) => EdgeCutouts.of(of(context));

  @override
  bool updateShouldNotify(DisplayPostureScope oldWidget) => oldWidget.posture != posture;
}

/// The live [resolveShellChromeInsets] for this window.
EdgeInsets shellChromeInsetsOf(BuildContext context) => resolveShellChromeInsets(
  window: YoinWindowInfo.of(context),
  viewPadding: MediaQuery.viewPaddingOf(context),
  cutouts: DisplayPostureScope.cutoutsOf(context),
);

/// The live [computeEdgeSplitSegments] for a [windowHeight] window, around
/// the cutout on the capsules' edge.
EdgeSplitSegments edgeSplitSegmentsOf(BuildContext context, double windowHeight) {
  final padding = MediaQuery.viewPaddingOf(context);
  final cutout = DisplayPostureScope.cutoutsOf(context).sideCutout;
  return computeEdgeSplitSegments(
    windowHeight: windowHeight,
    topInset: padding.top,
    bottomInset: padding.bottom,
    cutoutTop: cutout?.top,
    cutoutBottom: cutout?.bottom,
  );
}

/// Landscape handsets (ShellChromeForm.EdgeSplit): the page starts past the
/// 84 capsule band and stops short of a cutout on the other edge
/// (edgeContentPadding / detailChromeBand). [background] runs full-bleed
/// under the band (pages paint their wash to the screen edge, never a white
/// strip). Everywhere else the child as it is.
class ShellChromeBand extends StatelessWidget {
  const ShellChromeBand({super.key, required this.child, this.background});

  final Widget child;
  final Widget? background;

  @override
  Widget build(BuildContext context) {
    final window = YoinWindowInfo.of(context);
    final edge = window.chromeForm == ShellChromeForm.edgeSplit;
    final insets = edge ? shellChromeInsetsOf(context) : EdgeInsets.zero;
    // Always the same tree, so the page keeps its state across a rotation.
    // The band already holds the side insets: the page inside doesn't add them
    // again, and it reads the width it is given (a column gives away width:
    // forPaneWidth), not the window's — Home's feed density and the grids.
    final padded = Padding(
      padding: EdgeInsets.only(left: insets.left, right: insets.right),
      child: YoinWindowScope(
        info: edge ? window.forPaneWidth(math.max(0.0, window.width - insets.horizontal)) : window,
        child: _WithoutSideInsets(active: edge, child: child),
      ),
    );
    final background = this.background;
    if (background == null) return padded;
    // The wash only where the band shows; the page keyed so it keeps its state when the wash comes and goes.
    return Stack(
      fit: StackFit.expand,
      children: [
        if (edge) Positioned.fill(child: background),
        KeyedSubtree(key: const ValueKey('shell.band.page'), child: padded),
      ],
    );
  }
}

/// [child] with the side padding and view padding taken out (the band holds them).
class _WithoutSideInsets extends StatelessWidget {
  const _WithoutSideInsets({required this.active, required this.child});

  final bool active;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final data = MediaQuery.of(context);
    // Always a MediaQuery, so the page below keeps its place in the tree.
    return MediaQuery(
      data: active
          ? data.copyWith(
              padding: data.padding.copyWith(left: 0, right: 0),
              viewPadding: data.viewPadding.copyWith(left: 0, right: 0),
            )
          : data,
      child: child,
    );
  }
}
