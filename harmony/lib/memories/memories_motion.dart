/// Memories' two controllers (Android experience/RevealState and
/// showcase/MemoriesDiaryState): the outer **q**, the retreat to Home
/// (0 = open, 1 = closed), and the inner **p**, the card ⇄ diary morph
/// (0 = card, 1 = diary, below 0 the card's own pull-down). Every gesture is
/// handed to exactly one of them; each has ONE settle owner: any new input
/// stops the running spring where it is and carries on from there.
library;

import 'package:flutter/physics.dart';
import 'package:flutter/widgets.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';

/// The predictive back settle (Android YoinMotion.predictiveBackSettleSpring):
/// low-bouncy, medium-low stiffness × 1.3. q's every spring rides it.
final SpringDescription memoriesRevealSpring = SpringDescription.withDampingRatio(
  mass: 1,
  stiffness: 400 * MotionTokens.predictiveBackSpeedMultiplier,
  ratio: 0.75,
);

/// A dismiss release rule in px and px/s.
@immutable
final class DismissRule {
  const DismissRule({required this.commitPx, required this.flingPxPerSec, required this.flickBackPxPerSec});
  final double commitPx;
  final double flingPxPerSec;
  final double flickBackPxPerSec;

  /// The rule for a drag the page started following only after [slopUpPx]
  /// of upward travel: the commit stays a finger distance from touch-down.
  DismissRule afterSlop(double slopUpPx) => DismissRule(
    commitPx: (commitPx - slopUpPx).clamp(0, double.infinity),
    flingPxPerSec: flingPxPerSec,
    flickBackPxPerSec: flickBackPxPerSec,
  );

  static const body = DismissRule(
    commitPx: MemoriesTokens.dismissTrigger,
    flingPxPerSec: MemoriesTokens.dismissFling,
    flickBackPxPerSec: MemoriesTokens.flickBack,
  );
  static const bar = DismissRule(
    commitPx: MemoriesTokens.barDismissTrigger,
    flingPxPerSec: MemoriesTokens.barDismissFling,
    flickBackPxPerSec: MemoriesTokens.flickBack,
  );
}

/// The dismiss decision, pure (twostate4 `releaseQ`): a flick back stays
/// open even past the commit; past [DismissRule.commitPx], or fast enough
/// toward closed, closes. [dismissVelocity] is positive toward closed.
double chooseDismissTarget(double dismissedPx, double dismissVelocity, DismissRule rule) {
  if (dismissVelocity <= -rule.flickBackPxPerSec) return 0;
  if (dismissedPx >= rule.commitPx || dismissVelocity >= rule.flingPxPerSec) return 1;
  return 0;
}

/// Home's pull release (RevealState.chooseTarget): a fling either way
/// decides, else the half line. [velocityFraction] is positive toward closed.
double chooseRevealTarget(double fraction, double velocityFraction) {
  if (velocityFraction <= -MemoriesTokens.revealFlingFraction) return 0;
  if (velocityFraction >= MemoriesTokens.revealFlingFraction) return 1;
  return fraction < MemoriesTokens.revealPositionThreshold ? 0 : 1;
}

/// Past closed (raw > 1) an iOS rubber band saturates at +[c]; the open end
/// is hard-clamped (nothing to reveal above "fully open").
double revealRubberBand(double raw, [double c = MemoriesTokens.revealRubberBand]) {
  if (raw <= 0) return 0;
  if (raw <= 1) return raw;
  final over = raw - 1;
  return 1 + c * (1 - 1 / (1 + over / c));
}

/// A spring-driven number with one settle owner; a settle can be seeded
/// with a fling velocity and reports where it lands.
class _Spring {
  _Spring({required TickerProvider vsync, required double initial})
    : controller = AnimationController.unbounded(vsync: vsync, value: initial);

  final AnimationController controller;
  double? target;

  double get value => controller.value;
  bool get isAnimating => controller.isAnimating;

  void stop() {
    controller.stop();
    target = null;
  }

  void set(double v) {
    controller.stop();
    target = null;
    controller.value = v;
  }

  Future<void> animateTo(double to, SpringDescription spring, {double velocity = 0}) {
    target = to;
    if ((controller.value - to).abs() < 1e-4 && velocity.abs() < 1e-3) {
      controller.value = to;
      return Future.value();
    }
    return controller
        .animateWith(
          SpringSimulation(
            spring,
            controller.value,
            to,
            velocity,
            tolerance: const Tolerance(distance: 0.0005, velocity: 0.001),
          ),
        )
        .orCancel
        // Landed: rest exactly on the target (the tolerance leaves it a hair short).
        .then((_) {
          if (target == to) controller.value = to;
        })
        .catchError((Object _) {});
  }

  void dispose() => controller.dispose();
}

/// q: the reveal of the top-anchored Memories page. Drags move it 1:1 with
/// the finger; a release springs it with the finger's velocity kept.
class MemoriesRevealController extends ChangeNotifier {
  MemoriesRevealController({required TickerProvider vsync, double initial = 1})
    : _q = _Spring(vsync: vsync, initial: initial) {
    _q.controller.addListener(notifyListeners);
    _q.controller.addStatusListener(_onStatus);
  }

  final _Spring _q;

  /// Reduced motion: the page fades in place instead of moving.
  bool reducedMotion = false;

  /// Fired once a closing settle lands at 1.
  VoidCallback? onClosed;

  /// Fired the moment a release or a call decides to open.
  VoidCallback? onOpening;

  Animation<double> get animation => _q.controller;
  double get fraction => _q.value;
  double? get settleTarget => _q.target;
  bool get isSettling => _q.isAnimating;
  bool get isVisible => _q.value < 0.999;
  bool get isFullyOpen => _q.value <= 0.001;

  /// The page is open or heading open: system back belongs to it.
  bool get ownsBack => (_q.target ?? _q.value) < 0.5;

  void _onStatus(AnimationStatus status) {
    if (status == AnimationStatus.completed && _q.target == 1 && _q.value >= 0.999) onClosed?.call();
  }

  /// A finger delta in px; positive (down) opens. Stops any settle.
  void dragBy(double deltaPx, double heightPx) {
    if (heightPx <= 0) return;
    _q.stop();
    _q.controller.value = revealRubberBand(_q.value - deltaPx / heightPx);
  }

  /// Home's pull release: the closer end, or the fling's.
  Future<double> settle(double velocityPxPerSec, double heightPx) {
    final v = heightPx > 0 ? -velocityPxPerSec / heightPx : 0.0;
    final target = chooseRevealTarget(_q.value, v);
    if (target == 0) onOpening?.call();
    return _q.animateTo(target, _spring, velocity: v).then((_) => target);
  }

  /// A retreat release judged in px (Memories' card body or bar).
  Future<double> settleDismiss(double velocityPxPerSec, double heightPx, DismissRule rule) {
    final dismissVelocity = -velocityPxPerSec;
    final target = chooseDismissTarget(_q.value * heightPx, dismissVelocity, rule);
    final v = heightPx > 0 ? dismissVelocity / heightPx : 0.0;
    return _q.animateTo(target, _spring, velocity: target >= 1 ? v.clamp(0, double.infinity) : v).then((_) => target);
  }

  void open() {
    onOpening?.call();
    _q.animateTo(0, _spring);
  }

  void close() => _q.animateTo(1, _spring);

  void snapTo(double v) => _q.set(v.clamp(0, 1));

  SpringDescription get _spring => reducedMotion ? YoinSprings.defaultEffects : memoriesRevealSpring;

  @override
  void dispose() {
    _q.dispose();
    super.dispose();
  }
}

/// Finger travel → morph travel for a diary pull (prototype `wToV`). The
/// first [bandPx] below the top run at half speed when [banded]; below 0
/// the card's rubber band applies.
double diaryPullToMorph(
  double travel,
  double distance,
  double band,
  bool banded, [
  double rubberBand = MemoriesTokens.diaryRubberBand,
]) {
  final double morph;
  if (!banded || travel >= distance) {
    morph = travel;
  } else if (travel >= distance - band) {
    morph = distance - (distance - travel) * 0.5;
  } else {
    morph = travel + band / 2;
  }
  return morph < 0 ? morph * rubberBand : morph;
}

/// Inverse of [diaryPullToMorph].
double diaryMorphToPull(
  double morphPx,
  double distance,
  double band,
  bool banded, [
  double rubberBand = MemoriesTokens.diaryRubberBand,
]) {
  final morph = morphPx < 0 && rubberBand > 0 ? morphPx / rubberBand : morphPx;
  if (!banded || morph >= distance) return morph;
  if (morph >= distance - band / 2) return distance - (distance - morph) * 2;
  return morph - band / 2;
}

/// Where a released diary pull settles (prototype `releaseV`).
/// [openVelocity] is positive toward the diary (finger up).
double chooseDiaryReleaseTarget({
  required double fraction,
  required double openVelocity,
  required double distance,
  required double band,
  required double flick,
  required bool fromScrolled,
}) {
  if (fraction < 0) return 0;
  if (fraction >= 1 || distance <= 0) return 1;
  if (fromScrolled && fraction * distance >= distance - band / 2) return 1;
  if (openVelocity > flick) return 1;
  if (openVelocity < -flick) return 0;
  return fraction > 0.5 ? 1 : 0;
}

/// p: the card ⇄ diary morph. Diary scroll is not part of p; only a pull
/// past the top of the text reaches [pullBy].
class MemoriesDiaryController extends ChangeNotifier {
  MemoriesDiaryController({required TickerProvider vsync}) : _p = _Spring(vsync: vsync, initial: 0) {
    _p.controller.addListener(notifyListeners);
  }

  final _Spring _p;
  bool reducedMotion = false;

  double _travel = 0;
  bool _banded = false;
  bool _fromScrolled = false;
  double _ceiling = 1;

  static const double _distance = MemoriesTokens.diaryMorphDistance;
  static const double _band = MemoriesTokens.diaryPullBand;
  static const double _minFraction = -MemoriesTokens.diaryRubberBandFloor / MemoriesTokens.diaryMorphDistance;

  Animation<double> get animation => _p.controller;
  double get fraction => _p.value;
  bool get isSettling => _p.isAnimating;

  /// System back steps the diary from here up.
  bool get isDiaryLevel => _p.value >= 0.5;

  double stop() {
    _p.stop();
    return _p.value;
  }

  void snapTo(double v) => _p.set(v.clamp(_minFraction, 1));

  /// A finger takes p (caught where it is). [banded]: out of the diary text;
  /// [fromScrolled]: that text was scrolled at finger-down; [ceiling] caps p
  /// for this pull (the card's own pull-down passes 0).
  void startPull({bool banded = false, bool fromScrolled = false, double ceiling = 1}) {
    _p.stop();
    _banded = banded;
    _fromScrolled = banded && fromScrolled;
    // Never below where p already is: a pull that catches a spring above the ceiling must not jump.
    final c = ceiling.clamp(0.0, 1.0);
    final here = _p.value > 1 ? 1.0 : _p.value;
    _ceiling = c > here ? c : here;
    _travel = diaryMorphToPull(_p.value * _distance, _distance, _band, banded);
  }

  /// A finger delta (positive = down = toward the card). Returns the part p took.
  double pullBy(double deltaPx) {
    _p.stop();
    final maxTravel = diaryMorphToPull(_ceiling * _distance, _distance, _band, _banded);
    final minTravel = diaryMorphToPull(_minFraction * _distance, _distance, _band, _banded);
    final next = (_travel - deltaPx).clamp(minTravel, maxTravel);
    final consumed = _travel - next;
    _travel = next;
    final morph = diaryPullToMorph(next, _distance, _band, _banded);
    _p.controller.value = (morph / _distance).clamp(_minFraction, 1.0);
    return consumed;
  }

  /// Release; [velocityPxPerSec] positive = finger down. Returns the target.
  Future<double> releasePull(double velocityPxPerSec) {
    final open = -velocityPxPerSec;
    final target = chooseDiaryReleaseTarget(
      fraction: _p.value,
      openVelocity: open,
      distance: _distance,
      band: _band,
      flick: MemoriesTokens.flickBack,
      fromScrolled: _fromScrolled,
    );
    final heldAtTop = _fromScrolled && target >= 1 && _p.value < 1 && _p.value * _distance >= _distance - _band / 2;
    final v = heldAtTop ? 0.0 : open / _distance;
    return _p.animateTo(target, _spring, velocity: v).then((_) => target);
  }

  void open() => _p.animateTo(1, _spring);
  void close() => _p.animateTo(0, _spring);

  SpringDescription get _spring =>
      reducedMotion ? YoinSprings.of(MotionTokens.expressiveSlowEffects) : YoinSprings.defaultSpatial;

  @override
  void dispose() {
    _p.dispose();
    super.dispose();
  }
}

/// Hermite smoothstep of x between [a] and [b].
double smoothstep(double a, double b, double x) {
  if (b == a) return x < a ? 0 : 1;
  final t = ((x - a) / (b - a)).clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

double lerpDouble(double a, double b, double t) => a + (b - a) * t;
