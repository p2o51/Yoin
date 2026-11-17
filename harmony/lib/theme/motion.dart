import 'package:flutter/animation.dart';
import 'package:flutter/physics.dart';

import 'tokens.dart';

/// Springs as Flutter physics, from [MotionTokens].
abstract final class YoinSprings {
  static SpringDescription of(SpringToken token) => SpringDescription.withDampingRatio(
        mass: 1,
        stiffness: token.stiffness,
        ratio: token.damping,
      );

  // Standard role (the bar, chrome): Android wraps YoinButtonGroup in YoinMotionRole.Standard.
  static final standardFastSpatial = of(MotionTokens.standardFastSpatial);
  static final standardDefaultSpatial = of(MotionTokens.standardDefaultSpatial);

  // Expressive role (pages, Now Playing).
  static final fastSpatial = of(MotionTokens.expressiveFastSpatial);
  static final defaultSpatial = of(MotionTokens.expressiveDefaultSpatial);
  static final slowSpatial = of(MotionTokens.expressiveSlowSpatial);
  static final fastEffects = of(MotionTokens.expressiveFastEffects);
  static final defaultEffects = of(MotionTokens.expressiveDefaultEffects);
}

/// One animated number driven by a spring. Retargeting starts from the
/// current value *and velocity*, so an interrupted move never jumps — the
/// same contract as Compose's animate*AsState with a spring spec.
class SpringValue {
  SpringValue({
    required TickerProvider vsync,
    required double initial,
    required this.spring,
  }) : _controller = AnimationController.unbounded(vsync: vsync, value: initial);

  final AnimationController _controller;
  SpringDescription spring;
  double? _target;

  Animation<double> get animation => _controller;
  double get value => _controller.value;

  /// Springs toward [target] unless it is already the target.
  void animateTo(double target) {
    if (_target == target) return;
    _target = target;
    final simulation = SpringSimulation(
      spring,
      _controller.value,
      target,
      _controller.velocity,
      tolerance: const Tolerance(distance: 0.0005, velocity: 0.001),
    );
    _controller.animateWith(simulation);
  }

  /// Jumps to [value] (drag input, reduced motion).
  void snapTo(double value) {
    _target = value;
    _controller.value = value;
  }

  void dispose() => _controller.dispose();
}
