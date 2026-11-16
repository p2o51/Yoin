import 'dart:async';

import 'package:flutter/animation.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/physics.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'landing_models.dart';

/// The landing's springs, by role (YoinMotion.*Spec as LandingScreen.kt uses them).
abstract final class LandingSprings {
  static final spatial = YoinSprings.defaultSpatial;
  static final fastSpatial = YoinSprings.fastSpatial;
  static final slowSpatial = YoinSprings.slowSpatial;
  static final effects = YoinSprings.defaultEffects;
  static final slowEffects = YoinSprings.of(MotionTokens.expressiveSlowEffects);
  static final mascotLand = YoinSprings.of(MotionTokens.mascotLand);
  static final mascotGesture = YoinSprings.of(MotionTokens.mascotGesture);
  static final mascotHop = YoinSprings.of(MotionTokens.mascotHop);
  static final mascotShake = YoinSprings.of(MotionTokens.mascotShake);
}

/// One value on a spring, like Compose's Animatable: [animateTo] retargets
/// from the current value and velocity (or a given [velocity]) and completes
/// when it settles — or when a later call or [snapTo] takes over.
class LandingAnim {
  LandingAnim(TickerProvider vsync, double initial) : _c = AnimationController.unbounded(vsync: vsync, value: initial);

  final AnimationController _c;

  Animation<double> get animation => _c;
  double get value => _c.value;
  bool get isAnimating => _c.isAnimating;

  Future<void> animateTo(double target, SpringDescription spring, {double? velocity}) {
    final simulation = SpringSimulation(
      spring,
      _c.value,
      target,
      velocity ?? _c.velocity,
      tolerance: const Tolerance(distance: 0.0005, velocity: 0.001),
    );
    final done = Completer<void>();
    _c.animateWith(simulation).orCancel.then((_) => done.complete(), onError: (Object _) => done.complete());
    return done.future;
  }

  void snapTo(double value) {
    _c.stop();
    _c.value = value;
  }

  void dispose() => _c.dispose();
}

/// The single motion owner of the landing (LandingStage.kt LandingMotion):
/// one progress [t] (from step → to step) drives the floating window, the
/// mascot, the speech bubble and the pill; [handoff] turns the pill into the
/// shell's bar. Everything reads these in layout or paint — a step change
/// never rebuilds the stage.
class LandingMotion extends ChangeNotifier {
  LandingMotion(TickerProvider vsync)
    : t = LandingAnim(vsync, 1),
      handoff = LandingAnim(vsync, 0),
      fly = LandingAnim(vsync, 0),
      bubblePop = LandingAnim(vsync, 1),
      pillIn = LandingAnim(vsync, 1),
      reveal = LandingAnim(vsync, 1) {
    t.animation.addListener(_onT);
  }

  /// from → to, 0..1 (springs may overshoot a little).
  final LandingAnim t;

  /// The hand-off into the shell: window into the pill, pill into the bar, mascot to the corner.
  final LandingAnim handoff;

  /// The mascot's flight to the greeting corner during the hand-off (later and slower than [handoff]).
  final LandingAnim fly;

  /// The bubble's own pop when its line changes.
  final LandingAnim bubblePop;

  /// The pill rising in at first launch.
  final LandingAnim pillIn;

  /// The whole landing's alpha: fades in over a visible shell, out at the hand-off.
  final LandingAnim reveal;

  LandingStep from = LandingStep.hello;
  LandingStep to = LandingStep.hello;
  int _generation = 0;
  LandingStep _bubbleStep = LandingStep.hello;

  bool get settled => from == to;

  /// Everything the stage reads per frame.
  Listenable get frame =>
      Listenable.merge([this, t.animation, handoff.animation, fly.animation, bubblePop.animation, pillIn.animation]);

  /// The step whose line the bubble shows: it swaps at the transition's midpoint.
  LandingStep get bubbleStep => _bubbleStep;

  LandingStep _bubbleFor() => (from == to || t.value >= 0.5) ? to : from;

  void _onT() {
    final shown = _bubbleFor();
    if (shown != _bubbleStep) {
      _bubbleStep = shown;
      onBubbleChanged?.call();
      notifyListeners();
    }
  }

  /// Called when [bubbleStep] changes (the bubble pops from its tail).
  VoidCallback? onBubbleChanged;

  /// Moves to [target] on [spring], from wherever the stage is.
  Future<void> go(LandingStep target, SpringDescription spring) async {
    if (target == to && settled) return;
    final showing = (settled || t.value >= 0.5) ? to : from;
    final generation = ++_generation;
    t.snapTo(0);
    from = showing;
    to = target;
    _bubbleStep = _bubbleFor();
    notifyListeners();
    await t.animateTo(1, spring);
    if (generation != _generation) return;
    from = to;
    notifyListeners();
  }

  /// Lands on [step] at once (the first frame, reduced motion).
  void snap(LandingStep step) {
    _generation++;
    from = step;
    to = step;
    t.snapTo(1);
    final changed = _bubbleStep != step;
    _bubbleStep = step;
    if (changed) onBubbleChanged?.call();
    notifyListeners();
  }

  /// Pops the bubble from its tail when the line changes.
  void popBubble(SpringDescription spring) {
    bubblePop.snapTo(bubblePopFrom);
    bubblePop.animateTo(1, spring);
  }

  static const double bubblePopFrom = 0.82;

  @override
  void dispose() {
    t.animation.removeListener(_onT);
    t.dispose();
    handoff.dispose();
    fly.dispose();
    bubblePop.dispose();
    pillIn.dispose();
    reveal.dispose();
    super.dispose();
  }
}
