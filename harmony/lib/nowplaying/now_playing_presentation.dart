import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../ui/adaptive/yoin_window_info.dart';

/// How Now Playing lays itself out in the current window
/// (NowPlayingPresentation.kt). ONE chain for every size: Closed → [panel] →
/// Full, where Full is the window's player for its tier — [phone] on
/// Compact, [enlarged] on Medium, [dualPane] on Wide. The panel's corner
/// toggle switches Panel ⇄ Full; back steps Full → Panel → Closed.
enum NowPlayingPresentation {
  /// Handsets, short windows, panes < 600: the single column, full window.
  phone,

  /// Medium or Wide and tall: the phone page in a phone-width panel sliding
  /// in from the end, the content beside it still usable.
  panel,

  /// Medium, Full: the phone column enlarged and centred.
  enlarged,

  /// Wide (≥ 840) and tall, Full: the two-column player.
  dualPane,

  /// A short, wide window (a landscape handset).
  landscape,
}

/// What of the pill grows into the player as it opens.
enum PillMorph { none, cover, coverAndText }

extension NowPlayingPresentationMorph on NowPlayingPresentation {
  // Landscape: Android flies the cover only (its title sits in the right
  // column). Harmony has no landscape player yet — a short window shows the
  // phone column — so it keeps the phone's morph until that layout lands.
  PillMorph get pillMorph => switch (this) {
    NowPlayingPresentation.phone || NowPlayingPresentation.enlarged => PillMorph.coverAndText,
    NowPlayingPresentation.landscape => PillMorph.coverAndText,
    NowPlayingPresentation.dualPane => PillMorph.cover,
    NowPlayingPresentation.panel => PillMorph.none,
  };

  /// Only the side panel leaves the host usable beside it.
  bool get coversHost => this != NowPlayingPresentation.panel;
}

abstract final class NowPlayingPanelTokens {
  /// The content beside the panel keeps at least a phone column.
  static const double minContentWidth = 320;
  static const double minWidth = 360;
  static const double maxWidth = 420;

  /// A short window at least this wide gets the landscape player.
  static const double landscapeMinWidth = 560;

  /// The panel's shadow (PanelShadowElevation) and its start corners
  /// (the screen's corner; HarmonyOS gives Flutter no value: 28).
  static const double shadowElevation = 16;
  static const double corner = 28;

  /// Drag-to-dismiss toward the end: the phone's distance / velocity.
  static const double dismissDistance = 90;
  static const double dismissVelocity = 300;

  /// The enlarged column: window − 2 × 80, in 360..640; scales from 400.
  static const double enlargedSideMargin = 80;
  static const double enlargedMaxColumn = 640;
  static const double phoneColumn = 400;

  /// The corner toggle (44 dp, OpenInFull / CloseFullscreen).
  static const double toggleSize = 44;
}

typedef _P = NowPlayingPanelTokens;

/// Half the window, clamped to phone widths (fold 690 → 360, tablet 800 → 400).
double nowPlayingPanelWidth(double windowWidth) => (windowWidth * 0.5).clamp(_P.minWidth, _P.maxWidth);

/// A tall Medium or Wide window keeping [minContentWidth] beside the panel.
bool canOpenNowPlayingPanel(YoinWindowInfo window, {double minContentWidth = _P.minContentWidth}) =>
    !window.isCompactHeight &&
    window.layoutMode != LayoutMode.compact &&
    window.width - nowPlayingPanelWidth(window.width) >= math.max(minContentWidth, _P.minContentWidth);

NowPlayingPresentation resolveNowPlayingPresentation(
  YoinWindowInfo window, {
  required bool fullscreen,
  double minContentWidth = _P.minContentWidth,
}) {
  if (window.isCompactHeight) {
    return window.width >= _P.landscapeMinWidth ? NowPlayingPresentation.landscape : NowPlayingPresentation.phone;
  }
  final panel = canOpenNowPlayingPanel(window, minContentWidth: minContentWidth);
  return switch (window.layoutMode) {
    LayoutMode.compact => NowPlayingPresentation.phone,
    LayoutMode.medium => panel && !fullscreen ? NowPlayingPresentation.panel : NowPlayingPresentation.enlarged,
    LayoutMode.wide => panel && !fullscreen ? NowPlayingPresentation.panel : NowPlayingPresentation.dualPane,
  };
}

/// Now Playing's frame in this window: what every party (the overlay, the
/// content beside a panel, the bar) reads from the same inputs.
@immutable
final class NowPlayingFrame {
  const NowPlayingFrame({required this.presentation, required this.panelAvailable, required this.windowWidth});

  factory NowPlayingFrame.resolve(YoinWindowInfo window, {required bool fullscreen}) => NowPlayingFrame(
    presentation: resolveNowPlayingPresentation(window, fullscreen: fullscreen),
    panelAvailable: canOpenNowPlayingPanel(window),
    windowWidth: window.width,
  );

  final NowPlayingPresentation presentation;

  /// A Medium or Wide window where the Panel ⇄ Full pair (and its toggle) exists.
  final bool panelAvailable;
  final double windowWidth;

  double get panelWidth => nowPlayingPanelWidth(windowWidth);

  /// Panel and its Full states share one container sliding in from the end.
  bool get panelFamily =>
      panelAvailable &&
      (presentation == NowPlayingPresentation.panel ||
          presentation == NowPlayingPresentation.enlarged ||
          presentation == NowPlayingPresentation.dualPane);

  @override
  bool operator ==(Object other) =>
      other is NowPlayingFrame &&
      other.presentation == presentation &&
      other.panelAvailable == panelAvailable &&
      other.windowWidth == windowWidth;

  @override
  int get hashCode => Object.hash(presentation, panelAvailable, windowWidth);
}

/// The enlarged phone column (tablet portrait 800 → 640; fold ≈ 530).
@immutable
final class NowPlayingEnlargedSpec {
  const NowPlayingEnlargedSpec({required this.columnWidth, required this.ratingColumn, required this.controlSize});

  final double columnWidth;
  final double ratingColumn;
  final double controlSize;

  @override
  bool operator ==(Object other) =>
      other is NowPlayingEnlargedSpec &&
      other.columnWidth == columnWidth &&
      other.ratingColumn == ratingColumn &&
      other.controlSize == controlSize;

  @override
  int get hashCode => Object.hash(columnWidth, ratingColumn, controlSize);
}

double nowPlayingEnlargedColumnWidth(double windowWidth) =>
    (windowWidth - _P.enlargedSideMargin * 2).clamp(_P.minWidth, _P.enlargedMaxColumn);

NowPlayingEnlargedSpec nowPlayingEnlargedSpec(double windowWidth) {
  final column = nowPlayingEnlargedColumnWidth(windowWidth);
  final scale = column / _P.phoneColumn;
  return NowPlayingEnlargedSpec(
    columnWidth: column,
    ratingColumn: (56 + 80 * (scale - 1)).clamp(56.0, 104.0),
    controlSize: scale >= 1.4 ? 60 : 56,
  );
}

/// The size class Now Playing's Full flag reacts to: height first, so a
/// short window counts as Compact whatever its width.
LayoutMode nowPlayingSizeClass(YoinWindowInfo window) =>
    window.isCompactHeight ? LayoutMode.compact : window.layoutMode;

/// Size switches while open (fullscreenAfterLayoutChange): growing out of
/// Compact lands in Full; Medium ⇄ Wide keeps the choice; any other arrival
/// starts as the panel. Outside the panel tiers the flag is inert.
bool fullscreenAfterLayoutChange({
  required LayoutMode? previous,
  required LayoutMode current,
  required bool expanded,
  required bool wasFullscreen,
}) {
  if (current != LayoutMode.medium && current != LayoutMode.wide) return wasFullscreen;
  if (previous == LayoutMode.compact && expanded) return true;
  if (previous == LayoutMode.medium || previous == LayoutMode.wide) return wasFullscreen;
  return false;
}
