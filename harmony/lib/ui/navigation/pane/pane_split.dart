import 'dart:math' as math;

import 'package:flutter/foundation.dart';

/// The Wide window's two columns (PaneSplit.kt, adaptive-principles §3):
/// shell ｜ gutter ｜ detail column. One pure function owns every width; the
/// handle edits the shell's share 1:1 and the clamp is the only physics.
abstract final class PaneTokens {
  /// Air between the columns; the drag handle sits in it.
  static const double gutter = 24;

  /// Both columns keep at least a phone column.
  static const double minWidth = 360;

  /// From this window width the shell keeps at least 600 (1280 → 600 | 24 | 656).
  static const double shellPreferredFromWindow = 1080;
  static const double shellPreferredWidth = 600;
  static const double defaultShellFraction = 0.45;

  /// Two columns side by side: the floor the Now Playing panel must leave.
  static const double twoColumnsMinWidth = minWidth * 2 + gutter;

  /// The M3 vertical drag handle (VerticalDragHandle): 4 × 48, 12 × 52 held.
  static const double handleWidth = 4;
  static const double handleHeight = 48;
  static const double handleHeldWidth = 12;
  static const double handleHeldHeight = 52;
}

/// The shell's share of the content (window − gutter) before any drag.
double defaultShellFraction(double windowWidth) {
  final content = windowWidth - PaneTokens.gutter;
  if (content <= 0) return PaneTokens.defaultShellFraction;
  if (windowWidth >= PaneTokens.shellPreferredFromWindow) {
    return math.max(PaneTokens.defaultShellFraction, PaneTokens.shellPreferredWidth / content);
  }
  return PaneTokens.defaultShellFraction;
}

/// The drag range: neither column under [PaneTokens.minWidth].
(double, double) shellFractionBounds(double windowWidth) {
  final content = windowWidth - PaneTokens.gutter;
  if (content <= PaneTokens.minWidth * 2) return (0.5, 0.5);
  final min = PaneTokens.minWidth / content;
  return (min, 1 - min);
}

@immutable
final class PaneBudget {
  const PaneBudget({required this.shellWidth, required this.paneWidth, required this.shellFraction});

  final double shellWidth;
  final double paneWidth;

  /// The fraction actually applied (the requested one, clamped).
  final double shellFraction;

  @override
  bool operator ==(Object other) =>
      other is PaneBudget &&
      other.shellWidth == shellWidth &&
      other.paneWidth == paneWidth &&
      other.shellFraction == shellFraction;

  @override
  int get hashCode => Object.hash(shellWidth, paneWidth, shellFraction);
}

PaneBudget resolvePaneBudget(double windowWidth, double? shellFraction) {
  final content = math.max(0.0, windowWidth - PaneTokens.gutter);
  final (lo, hi) = shellFractionBounds(windowWidth);
  final fraction = (shellFraction ?? defaultShellFraction(windowWidth)).clamp(lo, hi);
  final shell = content * fraction;
  return PaneBudget(shellWidth: shell, paneWidth: content - shell, shellFraction: fraction);
}

/// The user's split after dragging the handle by [delta] (1:1, clamped).
double dragShellFraction(double windowWidth, double? current, double delta) {
  final content = windowWidth - PaneTokens.gutter;
  final from = current ?? defaultShellFraction(windowWidth);
  if (content <= 0) return from;
  final (lo, hi) = shellFractionBounds(windowWidth);
  return (from + delta / content).clamp(lo, hi);
}
