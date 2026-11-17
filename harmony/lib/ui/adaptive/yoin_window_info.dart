import 'package:flutter/widgets.dart';

import '../../platform/posture/display_posture.dart';

/// Width classes (ui/experience/WindowAdaptiveRuntime.kt LayoutMode), plus
/// the fold posture that overrides them.
enum LayoutMode {
  /// < 600: phones, the Pura X outer screen, narrow split windows.
  compact,

  /// 600–840: fold inner screens, portrait tablets, half windows.
  medium,

  /// ≥ 840: landscape tablets, the tri-fold fully open, PC windows.
  wide,

  /// Half folded on a horizontal hinge (kickstand): the window splits top /
  /// bottom on the crease, whatever its width.
  tabletop,
}

/// The bar's form, decided by the window (FloatingBottomBar / ShellChromeForm).
enum ShellChromeForm {
  /// Width < 600 and height ≥ 480, plus Tabletop: the portrait bottom bar.
  portraitBar,

  /// Height < 480: a landscape handset; split capsules at the edge.
  edgeSplit,

  /// Width ≥ 600 and height ≥ 480: the bar centred and capped at 600.
  centeredBar,
}

/// What a page may know about its container (docs/adaptive-principles.md §1):
/// its own width and height, judged height first. Pages read
/// [YoinWindowInfo.of], never MediaQuery sizes directly; a container that
/// gives away width provides [forPaneWidth] to its subtree.
@immutable
final class YoinWindowInfo {
  const YoinWindowInfo(this.width, this.height, {this.isTabletop = false, this.hingeBounds});

  static const double mediumWidth = 600;
  static const double wideWidth = 840;
  static const double mediumHeight = 480;

  final double width;
  final double height;

  /// Half-folded with the hinge horizontal (fed from ArkTS display fold status).
  final bool isTabletop;

  /// The horizontal hinge in this window's logical px while [isTabletop]
  /// (the kickstand layout splits on it); null otherwise or when the crease
  /// can't be read (the layout then splits in half).
  final Rect? hingeBounds;

  /// Shorter than 480: a landscape handset. Check this before [layoutMode].
  /// A Tabletop window never is (it keeps the portrait bar on its deck).
  bool get isCompactHeight => !isTabletop && height < mediumHeight;

  LayoutMode get layoutMode => isTabletop
      ? LayoutMode.tabletop
      : width >= wideWidth
      ? LayoutMode.wide
      : width >= mediumWidth
      ? LayoutMode.medium
      : LayoutMode.compact;

  /// resolveShellChromeForm: Tabletop first, then height, then width.
  ShellChromeForm get chromeForm => isTabletop
      ? ShellChromeForm.portraitBar
      : isCompactHeight
      ? ShellChromeForm.edgeSplit
      : width >= mediumWidth
      ? ShellChromeForm.centeredBar
      : ShellChromeForm.portraitBar;

  /// The same window seen from a column [paneWidth] wide (a Tabletop window
  /// keeps its posture whatever the column).
  YoinWindowInfo forPaneWidth(double paneWidth) =>
      YoinWindowInfo(paneWidth, height, isTabletop: isTabletop, hingeBounds: hingeBounds);

  static YoinWindowInfo of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<YoinWindowScope>()?.info ?? const YoinWindowInfo(360, 800);

  @override
  bool operator ==(Object other) =>
      other is YoinWindowInfo &&
      other.width == width &&
      other.height == height &&
      other.isTabletop == isTabletop &&
      other.hingeBounds == hingeBounds;

  @override
  int get hashCode => Object.hash(width, height, isTabletop, hingeBounds);

  @override
  String toString() => 'YoinWindowInfo($width × $height${isTabletop ? ', tabletop $hingeBounds' : ''})';
}

/// Provides [YoinWindowInfo] to a subtree.
class YoinWindowScope extends InheritedWidget {
  const YoinWindowScope({super.key, required this.info, required super.child});

  final YoinWindowInfo info;

  /// Measures [child]'s box and provides it as the window; [posture] (the
  /// display's fold status and crease, `yoin/posture`) makes it Tabletop
  /// when it is half folded on a horizontal crease across this box.
  static Widget measured({Key? key, DisplayPosture posture = DisplayPosture.flat, required Widget child}) =>
      LayoutBuilder(
        key: key,
        builder: (context, constraints) {
          final size = Size(constraints.maxWidth, constraints.maxHeight);
          final hinge = posture.tabletopHinge(size);
          return YoinWindowScope(
            info: YoinWindowInfo(size.width, size.height, isTabletop: hinge != null, hingeBounds: hinge),
            child: child,
          );
        },
      );

  @override
  bool updateShouldNotify(YoinWindowScope oldWidget) => oldWidget.info != info;
}
