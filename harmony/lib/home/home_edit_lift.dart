/// Home edit mode's press charge and lift (Android ui/home/edit HomeEditGeometry.kt §Press,
/// HomeEditMotion.kt, port sheet §2.2–2.4), number for number: the normal-mode press charge that shrinks
/// a block about the finger and pre-shows its plate, the entry plate's latch, the lift's scale and tint,
/// and the springs edit mode runs (reduced motion swaps every spatial one for fastEffects). Page px (dp).
/// The drag itself is home_drag_engine.dart.
library;

import 'dart:math' as math;

import 'package:flutter/animation.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/physics.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'home_section.dart';

// ── Pure geometry ─────────────────────────────────────────────────────────

/// GLSL smoothstep.
double carrySmoothstep(double edge0, double edge1, double x) {
  final t = ((x - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

/// R_press in block-content px: a [HomeCarryTokens.pressBox] square around
/// [origin], clipped to the content (not the plate), then outset by
/// [HomeCarryTokens.pressOutset] × [charge].
Rect pressRect(Offset origin, Size content, double charge) {
  const half = HomeCarryTokens.pressBox / 2;
  final outset = HomeCarryTokens.pressOutset * charge.clamp(0.0, 1.0);
  final w = math.max(0.0, content.width), h = math.max(0.0, content.height);
  return Rect.fromLTRB(
    (origin.dx - half).clamp(0.0, w) - outset,
    (origin.dy - half).clamp(0.0, h) - outset,
    (origin.dx + half).clamp(0.0, w) + outset,
    (origin.dy + half).clamp(0.0, h) + outset,
  );
}

/// Scale of a charging block (about the press point).
double chargeScale(double charge) => 1 - HomeCarryTokens.chargeScale * charge;

/// Plate pre-show alpha while charging; drawn only where it beats the plate's own alpha.
double chargePlateAlpha(double charge) =>
    HomeCarryTokens.chargePlateAlpha * carrySmoothstep(0, 1, charge.clamp(0.0, 1.0));

/// Scale of a lifted block (about the press point).
double liftScale(double lift) => 1 + HomeCarryTokens.liftScale * lift;

/// The entry block's plate alpha while it grows from R_press: the latched pre-show, until the plate's own alpha passes it.
double latchedPlateAlpha(double latch, double plateAlpha) =>
    math.max(HomeCarryTokens.chargePlateAlpha * carrySmoothstep(0, 1, latch), plateAlpha);

// ── Springs ───────────────────────────────────────────────────────────────

/// Every spring the press, the lift, the drag and the list run (HomeEditSpecs,
/// Expressive role). Under reduced motion every spatial spring becomes fastEffects.
@immutable
final class HomeEditSpecs {
  factory HomeEditSpecs({required bool reduced}) {
    final fastEffects = YoinSprings.fastEffects;
    final defaultSpatial = reduced ? fastEffects : YoinSprings.defaultSpatial;
    final fastSpatial = reduced ? fastEffects : YoinSprings.fastSpatial;
    final slowSpatial = reduced ? fastEffects : YoinSprings.slowSpatial;
    return HomeEditSpecs._(
      reduced: reduced,
      fold: defaultSpatial,
      placement: defaultSpatial,
      chargeUp: slowSpatial,
      chargeDown: fastSpatial,
      liftUp: fastSpatial,
      liftDown: defaultSpatial,
      liftTint: fastEffects,
    );
  }

  const HomeEditSpecs._({
    required this.reduced,
    required this.fold,
    required this.placement,
    required this.chargeUp,
    required this.chargeDown,
    required this.liftUp,
    required this.liftDown,
    required this.liftTint,
  });

  final bool reduced;

  /// Feed ⇄ compact list.
  final SpringDescription fold;

  /// A block making way for the dragged one.
  final SpringDescription placement;
  final SpringDescription chargeUp;
  final SpringDescription chargeDown;
  final SpringDescription liftUp;

  /// The lift falling, and the dropped block's glide into its slot.
  final SpringDescription liftDown;

  /// Both ways.
  final SpringDescription liftTint;
}

/// One value on a spring with its own threshold (Compose's Animatable). An
/// animation retargets from the current value and velocity; [animateTo]
/// answers whether it ran to the end (false: interrupted by another
/// animation or a snap).
class HomeEditSpring {
  HomeEditSpring(TickerProvider vsync, [double initial = 0])
    : _controller = AnimationController.unbounded(vsync: vsync, value: initial);

  final AnimationController _controller;

  Listenable get listenable => _controller;
  double get value => _controller.value;
  bool get isAnimating => _controller.isAnimating;

  void snapTo(double value) => _controller.value = value;

  Future<bool> animateTo(double target, SpringDescription spring, double threshold, {double? velocity}) async {
    final simulation = SpringSimulation(
      spring,
      _controller.value,
      target,
      velocity ?? _controller.velocity,
      tolerance: Tolerance(distance: threshold, velocity: threshold * 10),
    );
    try {
      await _controller.animateWith(simulation).orCancel;
    } on TickerCanceled {
      return false;
    }
    // Lands on the target, as the Animatable does.
    _controller.value = target;
    return true;
  }

  void dispose() => _controller.dispose();
}

// ── Press ─────────────────────────────────────────────────────────────────

/// The normal-mode press charge (port sheet §2.2): from T/2 the pressed block
/// shrinks about the finger and pre-shows its plate in R_press.
class HomeEditPressState {
  HomeEditPressState(TickerProvider vsync) : charge = HomeEditSpring(vsync);

  final HomeEditSpring charge;

  /// The pressed block. Kept through the release, so the charge eases out where it was.
  HomeSection? section;

  /// The press point in block-content px: the scale's pivot.
  Offset origin = Offset.zero;

  void start(HomeSection section, Offset origin, HomeEditSpecs specs) {
    // No charge and no plate latch under reduced motion (§1.2).
    if (specs.reduced) return;
    this.section = section;
    this.origin = origin;
    charge.animateTo(1, specs.chargeUp, HomeCarryTokens.unitThreshold);
  }

  void release(HomeEditSpecs specs) {
    if (charge.value == 0 && !charge.isAnimating) return;
    charge.animateTo(0, specs.chargeDown, HomeCarryTokens.unitThreshold);
  }

  void snapOff() => charge.snapTo(0);

  void dispose() => charge.dispose();
}

/// The entry plate's start (port sheet §2.3): R_press in block-content px and
/// the charge it was latched at.
@immutable
final class PlateFrom {
  const PlateFrom(this.section, this.rect, this.latch);
  final HomeSection section;
  final Rect rect;
  final double latch;

  PlateFrom withLatch(double latch) => PlateFrom(section, rect, latch);
}
