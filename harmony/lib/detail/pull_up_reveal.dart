import 'dart:math' as math;
import 'dart:ui' show lerpDouble;

import 'package:flutter/physics.dart';
import 'package:flutter/widgets.dart';

/// The Album / Playlist pull-up reshape (Android DetailPullUpReshape.kt +
/// RevealState.kt): one fraction, 1 = hero, 0 = track list.
///
/// These numbers belong in lib/theme/tokens.dart (DetailTokens) — that file
/// is shared and read-only for this task, so they live here until moved.
abstract final class PullUpTokens {
  /// Travel = this share of the page height (DetailPullUpTravelFraction).
  static const double travelFraction = 0.55;

  /// Rubber band past the hero end.
  static const double rubberBand = 0.30;

  /// Release by speed: |velocity / travel| at least this (per second).
  static const double flingFractionPerSec = 1.6;

  /// Release by position: below this the list wins.
  static const double positionThreshold = 0.5;

  /// predictiveBackSettleSpring: LowBouncy damping, 400 × 1.3 stiffness.
  static const double settleDamping = 0.75;
  static const double settleStiffness = 400 * 1.3;

  /// Hero and list slide this far during the hand-off.
  static const double handoffShift = 28;

  /// The header title lifts this much as the stamp forms, and settles to [titleScale].
  static const double titleLift = 6;
  static const double titleScale = 0.9;

  /// Corner of the cover at rest (Hero) and as a stamp (Cover).
  static const double heroCorner = 8;
  static const double stampCorner = 4;

  /// Hero slot = cover + this band (the deferred brand blocks' room).
  static const double heroBand = 56;

  /// The bar counts as formed (status-bar icons go light) past this (AlbumBarDockedThreshold).
  static const double barDockedThreshold = 0.5;
}

/// Pure hand-off math (layout/paint read it per frame).
abstract final class PullUpMath {
  static double smoothStep(double edge0, double edge1, double x) {
    if (edge1 == edge0) return x < edge0 ? 0 : 1;
    final t = ((x - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
    return t * t * (3 - 2 * t);
  }

  /// Past 1 the overshoot is capped (iOS-style); below 0 hard clamp.
  static double applyRubberBand(double raw, [double c = PullUpTokens.rubberBand]) {
    if (raw < 0) return 0;
    if (raw <= 1) return raw;
    final over = raw - 1;
    return 1 + c * (1 - 1 / (1 + over / c));
  }

  /// Which end a release lands on: true = list (expanded).
  static bool releaseToList(double fraction, double velocityPxPerSec, double travelPx) {
    final v = travelPx <= 0 ? 0.0 : velocityPxPerSec / travelPx;
    if (v <= -PullUpTokens.flingFractionPerSec) return true;
    if (v >= PullUpTokens.flingFractionPerSec) return false;
    return fraction < PullUpTokens.positionThreshold;
  }

  static double travel(double pageHeight) => pageHeight * PullUpTokens.travelFraction;

  /// expand = 1 - fraction.
  static double heroSlotHeight(double expand, double restHeight) => lerpDouble(restHeight, 0, expand.clamp(0.0, 1.0))!;
  static double heroAlpha(double expand) => 1 - smoothStep(0, .55, expand);
  static double heroShift(double expand) => -PullUpTokens.handoffShift * smoothStep(0, .6, expand);
  static double listAlpha(double expand) => smoothStep(.3, .8, expand);
  static double listShift(double expand) => PullUpTokens.handoffShift * (1 - smoothStep(.25, 1, expand));
  static double textProgress(double expand) => smoothStep(.25, .7, expand);

  /// The flying cover: rest rect → stamp, only moving and scaling.
  static Rect coverRect(Rect rest, Rect stamp, double expand) => Rect.lerp(rest, stamp, smoothStep(0, 1, expand))!;
  static double coverCorner(double expand) =>
      lerpDouble(PullUpTokens.heroCorner, PullUpTokens.stampCorner, smoothStep(0, 1, expand))!;

  /// Hero cover side on Compact: min(0.74 × width, 300).
  static double heroCoverSide(double width, {double fraction = 0.74, double max = 300}) =>
      math.min(width * fraction, max);
}

/// The one reveal state per page. [expanded] is the durable truth; only
/// [_settle] animates the fraction, gestures drag it live and on release
/// set [expanded] and launch the settle — never a second animator.
class RevealController extends ChangeNotifier {
  RevealController({required TickerProvider vsync})
    : _controller = AnimationController.unbounded(vsync: vsync, value: 1) {
    _controller.addListener(notifyListeners);
  }

  final AnimationController _controller;
  double _raw = 1;
  bool _expanded = false;
  double travelPx = 1;

  /// 1 = hero, 0 = list (rubber-banded above 1).
  double get fraction => _controller.value;
  double get expand => (1 - fraction).clamp(0.0, 1.0);
  bool get expanded => _expanded;

  static final settleSpring = SpringDescription.withDampingRatio(
    mass: 1,
    stiffness: PullUpTokens.settleStiffness,
    ratio: PullUpTokens.settleDamping,
  );

  /// A finger moved [dyPx] (positive = down). Cancels a settle in flight.
  void dragBy(double dyPx) {
    if (_controller.isAnimating) {
      _controller.stop();
      _raw = _controller.value;
    }
    _raw = (_raw + dyPx / travelPx).clamp(0.0, double.infinity);
    _controller.value = PullUpMath.applyRubberBand(_raw);
  }

  /// The finger lifted at [velocityPxPerSec] (positive = down).
  void release(double velocityPxPerSec) {
    _expanded = PullUpMath.releaseToList(fraction, velocityPxPerSec, travelPx);
    _settle(velocityPxPerSec / travelPx);
  }

  /// Programmatic: the stamp's tap (collapse to the hero), or open the list.
  void setExpanded(bool value, {bool animate = true}) {
    _expanded = value;
    if (animate) {
      _settle(0);
    } else {
      _controller.value = value ? 0 : 1;
      _raw = _controller.value;
    }
  }

  void _settle(double velocityFractionPerSec) {
    final target = _expanded ? 0.0 : 1.0;
    _controller.animateWith(
      SpringSimulation(
        settleSpring,
        _controller.value,
        target,
        velocityFractionPerSec,
        tolerance: const Tolerance(distance: 0.0005, velocity: 0.001),
      ),
    );
    _raw = target;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }
}

/// The track list's nested-scroll rule (Android onPreScroll): at the top,
/// pulling down drives the fraction toward the hero; pulling up while the
/// fraction is above 0 drives it toward the list; otherwise the list
/// scrolls. A release with the fraction between the ends commits with the
/// fling velocity.
class RevealScrollPhysics extends ClampingScrollPhysics {
  const RevealScrollPhysics(this.reveal, {super.parent});

  final RevealController reveal;

  @override
  RevealScrollPhysics applyTo(ScrollPhysics? ancestor) => RevealScrollPhysics(reveal, parent: buildParent(ancestor));

  @override
  bool shouldAcceptUserOffset(ScrollMetrics position) => true;

  @override
  double applyPhysicsToUserOffset(ScrollMetrics position, double offset) {
    final atTop = position.pixels <= position.minScrollExtent + 0.5;
    final pullingDownAtTop = offset > 0 && atTop && reveal.fraction < 1;
    final pullingUpStillCollapsing = offset < 0 && reveal.fraction > 0.0005;
    if (pullingDownAtTop || pullingUpStillCollapsing) {
      reveal.dragBy(offset);
      _dragging[reveal] = true;
      return 0;
    }
    return super.applyPhysicsToUserOffset(position, offset);
  }

  @override
  Simulation? createBallisticSimulation(ScrollMetrics position, double velocity) {
    if (_dragging[reveal] == true) {
      _dragging[reveal] = null;
      // Scroll velocity is in pixels (positive = content up = finger up).
      reveal.release(-velocity);
      if (reveal.fraction > 0.0005) return null;
    }
    return super.createBallisticSimulation(position, velocity);
  }

  static final _dragging = Expando<bool>();
}
