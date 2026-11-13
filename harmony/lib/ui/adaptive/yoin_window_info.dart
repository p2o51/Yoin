import 'package:flutter/widgets.dart';

/// Width classes (ui/experience/WindowAdaptiveRuntime.kt LayoutMode).
enum LayoutMode {
  /// < 600: phones, the Pura X outer screen, narrow split windows.
  compact,

  /// 600–840: fold inner screens, portrait tablets, half windows.
  medium,

  /// ≥ 840: landscape tablets, the tri-fold fully open, PC windows.
  wide,
}

/// The bar's form, decided by the window (FloatingBottomBar / ShellChromeForm).
enum ShellChromeForm {
  /// Width < 600 and height ≥ 480: the portrait bottom bar.
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
  const YoinWindowInfo(this.width, this.height, {this.isTabletop = false});

  static const double mediumWidth = 600;
  static const double wideWidth = 840;
  static const double mediumHeight = 480;

  final double width;
  final double height;

  /// Half-folded with the hinge horizontal (fed from ArkTS display fold status).
  final bool isTabletop;

  /// Shorter than 480: a landscape handset. Check this before [layoutMode].
  bool get isCompactHeight => height < mediumHeight;

  LayoutMode get layoutMode => width >= wideWidth
      ? LayoutMode.wide
      : width >= mediumWidth
          ? LayoutMode.medium
          : LayoutMode.compact;

  ShellChromeForm get chromeForm => isCompactHeight
      ? ShellChromeForm.edgeSplit
      : width >= mediumWidth
          ? ShellChromeForm.centeredBar
          : ShellChromeForm.portraitBar;

  /// The same window seen from a column [paneWidth] wide.
  YoinWindowInfo forPaneWidth(double paneWidth) => YoinWindowInfo(paneWidth, height, isTabletop: isTabletop);

  static YoinWindowInfo of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<YoinWindowScope>()?.info ?? const YoinWindowInfo(360, 800);

  @override
  bool operator ==(Object other) =>
      other is YoinWindowInfo && other.width == width && other.height == height && other.isTabletop == isTabletop;

  @override
  int get hashCode => Object.hash(width, height, isTabletop);
}

/// Provides [YoinWindowInfo] to a subtree.
class YoinWindowScope extends InheritedWidget {
  const YoinWindowScope({super.key, required this.info, required super.child});

  final YoinWindowInfo info;

  /// Measures [child]'s box and provides it as the window.
  static Widget measured({Key? key, bool isTabletop = false, required Widget child}) => LayoutBuilder(
        key: key,
        builder: (context, constraints) => YoinWindowScope(
          info: YoinWindowInfo(constraints.maxWidth, constraints.maxHeight, isTabletop: isTabletop),
          child: child,
        ),
      );

  @override
  bool updateShouldNotify(YoinWindowScope oldWidget) => oldWidget.info != info;
}
