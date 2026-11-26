/// Home edit mode's pure rules besides the layout and the drag (docs/design.md §主页 › 就地编辑; Android
/// PR #292), a Dart port of `:shared` HomeEditRules.kt for parity phase 4: the resize handle's horizontal
/// axis, the pinch into the compact list, the exit reasons and the undo depth. The session rules that need a
/// layout (the draft at a new width, the edit step, the echo, the persist rule) are in home_layout_core.dart.
/// Replayed against home-drag.json (span and pinch vectors).
library;

// ── The span handle (large-screen Home, stage 1, owner 2026-11-16) ──────

/// Where a section sits in its row.
enum HomeSpan { full, leftHalf, rightHalf }

/// The axis a handle drag has locked to.
enum HomeHandleAxis { horizontal, vertical }

/// What a span drag or key writes.
enum HomeSpanCommit { none, toHalf, toFull }

/// Numbers of the horizontal resize (dp unless noted).
abstract final class HomeSpanRules {
  /// The gap between the two halves of a row.
  static const double columnGap = 20;

  /// A drag can pull a block this much narrower than a half (a rubber band past it), a fraction.
  static const double underHalf = .82;
}

/// Where a section's block sits now, for a span drag: its span, width, a half's and a full row's width (px).
final class HomeSpanInfo {
  const HomeSpanInfo({
    required this.span,
    required this.currentPx,
    required this.halfPx,
    required this.fullPx,
    required this.hasNext,
  });

  final HomeSpan span;
  final double currentPx;
  final double halfPx;
  final double fullPx;

  /// A full row has a row after it to pull up beside it.
  final bool hasNext;

  @override
  bool operator ==(Object other) =>
      other is HomeSpanInfo &&
      other.span == span &&
      other.currentPx == currentPx &&
      other.halfPx == halfPx &&
      other.fullPx == fullPx &&
      other.hasNext == hasNext;

  @override
  int get hashCode => Object.hash(span, currentPx, halfPx, fullPx, hasNext);
}

/// The axis a drag locks to once it has moved past [slopPx] (the larger component wins; a tie is
/// vertical), or null while it hasn't. Without [horizontal] it is always vertical. Pure.
HomeHandleAxis? homeHandleAxis(double dx, double dy, double slopPx, bool horizontal) {
  if (dx.abs() <= slopPx && dy.abs() <= slopPx) return null;
  if (!horizontal) return HomeHandleAxis.vertical;
  return dx.abs() > dy.abs() ? HomeHandleAxis.horizontal : HomeHandleAxis.vertical;
}

/// The dragged block's live width, px: [startPx] moved by the finger's [dx] — a right half grows
/// leftward, so its width follows −dx — held between a little under [halfPx] and [fullPx]. Pure.
double homeSpanDragWidth(HomeSpan from, double startPx, double dx, double halfPx, double fullPx) {
  final raw = from == HomeSpan.rightHalf ? startPx - dx : startPx + dx;
  final lo = halfPx * HomeSpanRules.underHalf;
  return raw < lo ? lo : (raw > fullPx ? fullPx : raw);
}

/// The span a block of [widthPx] snaps to: half below the midpoint of [halfPx] and [fullPx], else full. Pure.
HomeSpan homeSpanTarget(double widthPx, double halfPx, double fullPx) =>
    widthPx < (halfPx + fullPx) / 2 ? HomeSpan.leftHalf : HomeSpan.full;

/// What a release from [from] snapping to [target] writes. Pure.
HomeSpanCommit homeSpanCommit(HomeSpan from, HomeSpan target, bool hasNext) {
  if (from == HomeSpan.full && target != HomeSpan.full) return hasNext ? HomeSpanCommit.toHalf : HomeSpanCommit.none;
  if (from != HomeSpan.full && target == HomeSpan.full) return HomeSpanCommit.toFull;
  return HomeSpanCommit.none;
}

/// ← / → on a focused handle; null for a key that does nothing. Pure.
HomeSpanCommit? homeSpanKey(HomeSpan from, bool left, bool hasNext) => switch (from) {
  HomeSpan.full => left && hasNext ? HomeSpanCommit.toHalf : null,
  HomeSpan.leftHalf => !left ? HomeSpanCommit.toFull : null,
  HomeSpan.rightHalf => left ? HomeSpanCommit.toFull : null,
};

// ── The pinch into the compact list (owner 2026-11-22) ──────────────────

/// Numbers of the list's pinch.
abstract final class HomeListPinchRules {
  /// A pinch to this fraction of the fingers' starting span takes the feed all the way into the list.
  static const double pinchInFull = .55;

  /// A pinch out to this multiple of the starting span brings the feed all the way back.
  static const double pinchOutFull = 1.8;

  /// Let go this far of the way (or further) and the change finishes; short of it, it springs back.
  static const double pinchCommit = .35;
}

double _unit(double v) => v < 0 ? 0 : (v > 1 ? 1 : v);

/// The list's progress for a pinch at [scale] (the fingers' span over their starting span) begun
/// [fromOn] (in the list) or not: in from the feed, 0 → 1 as the span closes to
/// [HomeListPinchRules.pinchInFull]; out from the list, 1 → 0 as it opens to
/// [HomeListPinchRules.pinchOutFull]. The other way does nothing. Pure.
double homeListPinchProgress(bool fromOn, double scale) {
  if (!fromOn) return _unit((1 - scale) / (1 - HomeListPinchRules.pinchInFull));
  return 1 - _unit((scale - 1) / (HomeListPinchRules.pinchOutFull - 1));
}

/// A pinch begun [fromOn] let go at [progress] lands in the list (true) or the feed. Pure.
bool homeListSettlesOn(bool fromOn, double progress) =>
    !fromOn ? progress >= HomeListPinchRules.pinchCommit : progress > 1 - HomeListPinchRules.pinchCommit;

// ── The edit session ─────────────────────────────────────────────────────

/// Why Home edit mode ended. Only [done] confirms with a haptic.
enum HomeEditExitReason {
  /// The bar's Done button.
  done,

  /// System back.
  back,

  /// A tap on blank page space.
  blank,

  /// The app left edit mode on its own (profile switch, section change, stop).
  programmatic,
}

/// Edit mode keeps this many Undo steps; the oldest goes first.
const int homeEditUndoMax = 20;
