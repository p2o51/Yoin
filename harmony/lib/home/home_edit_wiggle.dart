/// Edit Home's wiggle (Android ui/home/edit/HomeEditWiggle.kt, HomeEditMotion.kt,
/// HomeEditGeometry.kt; port sheet §3): while editing, every card sways about
/// its centre — each section on its own detuned frequency and phase, cards
/// alternating direction — under one envelope that rises on entry (with an
/// overshoot), settles after six seconds without a touch and comes back on
/// the next one, drops to 0.6 while a section is carried and to 0 on leaving.
/// Kicks (entry, a tap, a drop, neighbours that moved) ring on a soft spring
/// on top. A block with no cards (a placeholder, the empty Activities card)
/// sways whole by Θ from its plate's size. Near the status bar and the bar the
/// sway fades out. Reduced motion: no sway, no kicks.
///
/// One clock for the page ([HomeEditWiggle]); it runs only while a sway can
/// show, and cards read it in paint only (a repaint, never a rebuild).
library;

import 'dart:math' as math;

import 'package:flutter/physics.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'home_metrics.dart';
import 'home_section.dart';

// ---------------------------------------------------------------- geometry (pure)

double _smoothstep(double x) {
  final t = x.clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

/// Ripple progress p_i of block [index] at edit progress [p]: blocks further
/// from the [origin] block start later.
double rippleProgress(double p, int index, int origin, int count) {
  const step = HomeWiggleMetrics.rippleStep;
  const maxSteps = HomeWiggleMetrics.rippleMaxSteps;
  final delay = step * math.min((index - origin).abs(), maxSteps);
  final maxDelay = step * math.min(math.max(count - 1, 0), maxSteps);
  return ((p - delay) / (1 - maxDelay)).clamp(0.0, 1.0);
}

/// 32-bit FNV-1a over UTF-16 code units, unsigned: stable across runs.
int fnv1a32(String value) {
  var hash = 0x811C9DC5;
  for (final unit in value.codeUnits) {
    hash = ((hash ^ unit) * 16777619) & 0xFFFFFFFF;
  }
  return hash;
}

/// A section's own sway: direction, frequency and phase.
@immutable
final class SwayParams {
  const SwayParams(this.sign, this.freqHz, this.phaseRad);
  final int sign;
  final double freqHz;
  final double phaseRad;
}

/// Kick and sway direction of a section.
int swaySign(String id) => fnv1a32(id) & 1 == 1 ? -1 : 1;

SwayParams swayParams(String id) {
  final hash = fnv1a32(id);
  final detune = ((hash >>> 4) % 201) / 100 - 1;
  return SwayParams(
    hash & 1 == 1 ? -1 : 1,
    HomeWiggleMetrics.swayHz * (1 + HomeWiggleMetrics.swayDetune * detune),
    (hash % 628) / 100,
  );
}

/// The normalised sway a block and its cards share: [ripple] is the block's
/// smoothstep(p_i), [timeSec] the one clock.
double swayValue(double envelope, double ripple, double timeSec, SwayParams params) {
  if (envelope <= HomeWiggleMetrics.swayFloor || ripple <= 0) return 0;
  final angle = 2 * math.pi * params.freqHz * timeSec + params.phaseRad;
  return params.sign * envelope * ripple * math.sin(angle);
}

/// Card amplitude A_c (degrees): tall cards count half their height, so a tall cell doesn't swing past 1 dp.
double cardAmpDeg(double width, double height) =>
    (HomeWiggleMetrics.cardAmpDeg * HomeWiggleMetrics.cardAmpWidth / math.max(math.max(width, height / 2), 1)).clamp(
      HomeWiggleMetrics.cardAmpMinDeg,
      HomeWiggleMetrics.cardAmpMaxDeg,
    );

/// Block-mode amplitude Θ (degrees): a 2 dp corner displacement over the plate's half-diagonal, clamped.
double blockThetaDeg(double plateW, double plateH) {
  final half = 0.5 * math.sqrt(plateW * plateW + plateH * plateH);
  final degrees = math.atan(HomeWiggleMetrics.thetaCorner / math.max(half, 1)) * 180 / math.pi;
  return degrees.clamp(HomeWiggleMetrics.thetaMinDeg, HomeWiggleMetrics.thetaMaxDeg);
}

/// Card direction by its index in the section: even +1, odd −1.
int cardAlt(int index) => index.isEven ? 1 : -1;

/// Sway fade near the status bar and the bar: 0 once [top]..[bottom] touches
/// the bands, linear over [fade] away from them.
double edgeBand(double top, double bottom, double safeTop, double safeBottom, double fade) {
  final distance = math.min(top - safeTop, safeBottom - bottom);
  return distance <= 0 ? 0 : (distance / fade).clamp(0.0, 1.0);
}

double _drawable(double degrees) => degrees.abs() < HomeWiggleMetrics.minAngleDeg ? 0 : degrees;

/// A card's angle (degrees); 0 when too small to draw. The block kick reaches every card.
double cardAngleDeg(double sway, double blockKick, double cardKick, int alt, double amp, double gain, double band) =>
    _drawable(((sway + blockKick) * alt + cardKick) * amp * gain * band);

/// A whole block's angle (degrees) for a block with no cards.
double blockAngleDeg(double sway, double blockKick, double theta, double gain, double band) =>
    _drawable((HomeWiggleMetrics.blockSwayFactor * sway + blockKick) * theta * gain * band);

/// Start velocity of a kick of [amplitude] (normalised, first peak = a): a
/// spring still ringing takes the kick along its current motion.
double kickInitialVelocity(double amplitude, int sign, bool running, double currentVelocity) {
  final base = amplitude * HomeWiggleMetrics.kickGain;
  if (running && currentVelocity.abs() > 1e-3) return currentVelocity + currentVelocity.sign * base;
  return base * sign;
}

// ---------------------------------------------------------------- the page's clock

/// The page's one wiggle: envelope, clock, ripple and kicks. Home creates it,
/// feeds it ([update], [touch], [pointerDown], [carrying]) and disposes it;
/// cards read it in paint through [HomeEditWiggleScope].
class HomeEditWiggle extends ChangeNotifier {
  HomeEditWiggle({required TickerProvider vsync, @visibleForTesting int Function()? clock})
    : _vsync = vsync,
      _clock = clock ?? _monotonicMs {
    _envelope = AnimationController.unbounded(vsync: vsync)..addListener(_onEnvelope);
    _ticker = vsync.createTicker(_onTick);
  }

  static final Stopwatch _watch = Stopwatch()..start();
  static int _monotonicMs() => _watch.elapsedMilliseconds;

  static final SpringDescription _envUp = YoinSprings.of(MotionTokens.expressiveFastSpatial);
  static final SpringDescription _envDown = YoinSprings.of(MotionTokens.expressiveDefaultSpatial);
  static final SpringDescription _kick = SpringDescription.withDampingRatio(
    mass: 1,
    stiffness: HomeWiggleMetrics.kickStiffness,
    ratio: HomeWiggleMetrics.kickDamping,
  );

  final TickerProvider _vsync;
  final int Function() _clock;
  late final AnimationController _envelope;
  late final Ticker _ticker;

  final Map<HomeSection, SwayParams> _params = {for (final s in HomeSection.values) s: swayParams(s.id)};
  final Map<HomeSection, AnimationController> _blockKicks = {};
  final Map<(HomeSection, Object), AnimationController> _cardKicks = {};
  final Set<HomeSection> _kicked = {};

  bool _editing = false;
  double _progress = 0;
  bool _reduced = false;
  bool _visible = true;
  List<HomeSection> _order = const [];
  HomeSection? _origin;
  HomeSection? _held;
  bool _carrying = false;
  bool _pointerDown = false;
  double _envelopeTarget = 0;
  int _lastTouch = 0;
  double _time = 0;
  Duration? _lastTick;

  /// The wiggle clock, seconds.
  double get time => _time;

  /// The sway envelope E.
  double get envelope => _envelope.value;

  bool get reduced => _reduced;

  /// The clock is running (a sway can show).
  bool get clockRunning => _ticker.isActive;

  /// Home's state, every frame P moves: editing, P, the sections in feed
  /// order, reduced motion, and whether Home can be seen.
  void update({
    required bool editing,
    required double progress,
    required List<HomeSection> order,
    required bool reduced,
    bool visible = true,
  }) {
    final enteringNow = editing && !_editing;
    _editing = editing;
    _progress = progress;
    _order = order;
    _reduced = reduced;
    _visible = visible;
    if (enteringNow) {
      _lastTouch = _clock();
      _kicked.clear();
    }
    if (!editing) _kicked.clear();
    _setEnvelope();
    _entryKicks();
    _syncClock();
  }

  /// The section the entering long-press landed on: the ripple spreads from it.
  set origin(HomeSection? section) => _origin = section;

  /// The section lifted by a drag: no entry kick, its sway fades with the lift.
  set held(HomeSection? section) => _held = section;

  /// A section is carried: the others sway at [HomeWiggleMetrics.dragEnvelope].
  set carrying(bool value) {
    if (_carrying == value) return;
    _carrying = value;
    _setEnvelope();
    _syncClock();
  }

  /// A finger is down on the page: the idle rule waits for it; the six seconds restart as it lifts.
  set pointerDown(bool value) {
    if (_pointerDown && !value && _editing) _lastTouch = _clock();
    _pointerDown = value;
  }

  /// Any press on the page or a bar click while editing: the sway comes back, the idle timer restarts.
  void touch() {
    _lastTouch = _clock();
    _setEnvelope();
    _syncClock();
  }

  /// Ripple progress p_i of [section] at the current P (a section not shown ripples as the origin).
  double ripple(HomeSection section) {
    final origin = math.max(0, _origin == null ? 0 : _order.indexOf(_origin!));
    final index = _order.indexOf(section);
    return rippleProgress(_progress, index < 0 ? origin : index, origin, _order.length);
  }

  /// The normalised sway of [section] (and all its cards).
  double sway(HomeSection section) {
    final e = envelope;
    if (e <= HomeWiggleMetrics.swayFloor) return 0;
    return swayValue(e, _smoothstep(ripple(section)), _time, _params[section]!);
  }

  double blockKick(HomeSection section) => _blockKicks[section]?.value ?? 0;

  double cardKick(HomeSection section, Object key) => _cardKicks[(section, key)]?.value ?? 0;

  /// Kicks the whole block (every card follows, alternating).
  void impulse(HomeSection section, double amplitude) {
    if (_reduced) return;
    _ring(_blockKicks.putIfAbsent(section, _newKick), amplitude, swaySign(section.id));
  }

  /// Kicks one card only (keyed by [key], its stable id), in its own direction [alt].
  void impulseCard(HomeSection section, Object key, double amplitude, {int alt = 1}) {
    if (_reduced) return;
    _ring(_cardKicks.putIfAbsent((section, key), _newKick), amplitude, swaySign(section.id) * alt);
  }

  AnimationController _newKick() {
    final kick = AnimationController.unbounded(vsync: _vsync)..addListener(notifyListeners);
    // A rung-out kick rests at exactly 0 (the spring ends within its tolerance).
    kick.addStatusListener((status) {
      if (status == AnimationStatus.completed && kick.value != 0) kick.value = 0;
    });
    return kick;
  }

  void _ring(AnimationController kick, double amplitude, int sign) {
    final velocity = kickInitialVelocity(amplitude, sign, kick.isAnimating, kick.velocity);
    kick.animateWith(
      SpringSimulation(_kick, kick.value, 0, velocity, tolerance: const Tolerance(distance: 0.01, velocity: 0.05)),
    );
  }

  /// Stills [section]'s kicks (a lift stills the block).
  void stopKicks(HomeSection section) {
    _blockKicks[section]?.value = 0;
    for (final entry in _cardKicks.entries) {
      if (entry.key.$1 == section) entry.value.value = 0;
    }
  }

  // ---------------------------------------------------------------- angles (paint-time reads)

  /// A card's angle (degrees). [top]/[bottom] are its global extent, [safeTop]/[safeBottom] the band's edges.
  double cardAngle(
    HomeSection section, {
    required int alt,
    required Object? kickKey,
    required Size size,
    required double top,
    required double bottom,
    required double safeTop,
    required double safeBottom,
    double liftGain = 1,
  }) {
    if (_reduced) return 0;
    final bk = blockKick(section);
    final ck = kickKey == null ? 0.0 : cardKick(section, kickKey);
    if (envelope <= HomeWiggleMetrics.swayFloor && bk == 0 && ck == 0) return 0;
    final band = edgeBand(top, bottom, safeTop, safeBottom, HomeWiggleMetrics.bandFade);
    return cardAngleDeg(sway(section), bk, ck, alt, cardAmpDeg(size.width, size.height), liftGain, band);
  }

  /// A whole block's angle (degrees), Θ from its [plate] size.
  double blockAngle(
    HomeSection section, {
    required Size plate,
    required double top,
    required double bottom,
    required double safeTop,
    required double safeBottom,
    double liftGain = 1,
  }) {
    if (_reduced) return 0;
    final bk = blockKick(section);
    if (envelope <= HomeWiggleMetrics.swayFloor && bk == 0) return 0;
    final band = edgeBand(top, bottom, safeTop, safeBottom, HomeWiggleMetrics.bandFade);
    return blockAngleDeg(sway(section), bk, blockThetaDeg(plate.width, plate.height), liftGain, band);
  }

  // ---------------------------------------------------------------- envelope and clock

  void _setEnvelope() {
    if (!_editing || _reduced) {
      if (_envelopeTarget != 0 || (!_envelope.isAnimating && _envelope.value != 0)) _animateEnvelope(0, _envDown);
      return;
    }
    final target = _carrying ? HomeWiggleMetrics.dragEnvelope : 1.0;
    if (_envelopeTarget != target || (!_envelope.isAnimating && _envelope.value != target)) {
      _animateEnvelope(target, _envUp);
    }
  }

  void _animateEnvelope(double target, SpringDescription spring) {
    _envelopeTarget = target;
    if (_reduced && target == 0) {
      _envelope.value = 0;
      return;
    }
    _envelope.animateWith(
      SpringSimulation(
        spring,
        _envelope.value,
        target,
        _envelope.velocity,
        tolerance: const Tolerance(distance: 0.001, velocity: 0.01),
      ),
    );
  }

  /// IdleSettle: six seconds without a touch, no finger down and no carry, and the sway settles.
  void _idleTick() {
    if (!_editing || _carrying || _pointerDown) return;
    if (_envelopeTarget > 0 && _clock() - _lastTouch > HomeWiggleMetrics.idleMs) _animateEnvelope(0, _envDown);
  }

  void _entryKicks() {
    if (!_editing) return;
    for (var i = 0; i < _order.length; i++) {
      final section = _order[i];
      if (_kicked.contains(section)) continue;
      if (ripple(section) >= HomeWiggleMetrics.entryKickAt) {
        _kicked.add(section);
        if (section != _held) impulse(section, HomeWiggleMetrics.kickEntry);
      }
    }
  }

  bool get _shouldRun =>
      !_reduced &&
      _visible &&
      (_editing || _progress > 0.0005) &&
      (_envelope.value > HomeWiggleMetrics.swayFloor || _envelope.isAnimating);

  void _syncClock() {
    if (_shouldRun) {
      if (!_ticker.isActive) {
        _lastTick = null;
        _ticker.start();
      }
    } else if (_ticker.isActive) {
      _ticker.stop();
    }
  }

  void _onEnvelope() => _syncClock();

  /// Advances the clock by the frame time capped at 1/24 s (1/60 s on the first frame after a resume).
  void _onTick(Duration elapsed) {
    final last = _lastTick;
    _lastTick = elapsed;
    _time += last == null ? 1 / 60 : math.min((elapsed - last).inMicroseconds / 1e6, 1 / 24);
    _idleTick();
    notifyListeners();
    if (!_shouldRun) _ticker.stop();
  }

  @override
  void dispose() {
    _ticker.dispose();
    _envelope.dispose();
    for (final k in [..._blockKicks.values, ..._cardKicks.values]) {
      k.dispose();
    }
    super.dispose();
  }
}

// ---------------------------------------------------------------- the cards

/// What a card inside a Home section needs to wiggle (HomeEditCardScope):
/// the page's [wiggle], its [section], the lift's fade ([liftGain]: 1 − lift
/// while this section is lifted, else 1) and the band the sway lives in
/// ([safeArea]: global top and bottom). Absent outside Home: cards stay still.
class HomeEditWiggleScope extends InheritedWidget {
  const HomeEditWiggleScope({
    super.key,
    required this.wiggle,
    required this.section,
    required this.liftGain,
    required this.safeArea,
    required super.child,
  });

  final HomeEditWiggle wiggle;
  final HomeSection section;
  final double Function() liftGain;
  final (double, double) Function() safeArea;

  static HomeEditWiggleScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<HomeEditWiggleScope>();

  @override
  bool updateShouldNotify(HomeEditWiggleScope old) =>
      !identical(old.wiggle, wiggle) || old.section != section || !identical(old.liftGain, liftGain);
}

/// Card [index] of its section: rotates about its centre in paint (taps are
/// unaffected). Without an [index] its direction alternates by where it sits
/// (a checker over its column and row). [kickKey] names it for a card kick.
class HomeEditCard extends StatelessWidget {
  const HomeEditCard({super.key, this.index, this.kickKey, required this.child});

  final int? index;
  final Object? kickKey;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scope = HomeEditWiggleScope.maybeOf(context);
    if (scope == null) return child;
    return _Wiggle(scope: scope, index: index, kickKey: kickKey, block: false, child: child);
  }
}

/// A block with no cards (a placeholder, the empty Activities card), swaying
/// whole by Θ from [plate] (its plate's size; its own size until known).
class HomeEditBlockWiggle extends StatelessWidget {
  const HomeEditBlockWiggle({super.key, this.plate, required this.child});

  final Size Function()? plate;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final scope = HomeEditWiggleScope.maybeOf(context);
    if (scope == null) return child;
    return _Wiggle(scope: scope, block: true, plate: plate, child: child);
  }
}

class _Wiggle extends SingleChildRenderObjectWidget {
  const _Wiggle({required this.scope, this.index, this.kickKey, required this.block, this.plate, super.child});

  final HomeEditWiggleScope scope;
  final int? index;
  final Object? kickKey;
  final bool block;
  final Size Function()? plate;

  @override
  _RenderWiggle createRenderObject(BuildContext context) =>
      _RenderWiggle(scope: scope, index: index, kickKey: kickKey, block: block, plate: plate);

  @override
  void updateRenderObject(BuildContext context, _RenderWiggle render) {
    render
      ..scope = scope
      ..index = index
      ..kickKey = kickKey
      ..block = block
      ..plate = plate;
  }
}

class _RenderWiggle extends RenderProxyBox {
  _RenderWiggle({
    required HomeEditWiggleScope scope,
    required this.index,
    required this.kickKey,
    required this.block,
    required this.plate,
  }) : _scope = scope;

  HomeEditWiggleScope _scope;
  int? index;
  Object? kickKey;
  bool block;
  Size Function()? plate;

  set scope(HomeEditWiggleScope value) {
    if (identical(value.wiggle, _scope.wiggle)) {
      _scope = value;
      return;
    }
    if (attached) _scope.wiggle.removeListener(markNeedsPaint);
    _scope = value;
    if (attached) _scope.wiggle.addListener(markNeedsPaint);
    markNeedsPaint();
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _scope.wiggle.addListener(markNeedsPaint);
  }

  @override
  void detach() {
    _scope.wiggle.removeListener(markNeedsPaint);
    super.detach();
  }

  /// The checker direction for a card with no index: its column and row by its own size.
  int _positionAlt(Offset global) {
    final w = math.max(size.width, 1.0), h = math.max(size.height, 1.0);
    final column = ((global.dx + w / 2) / w).floor();
    final row = ((global.dy + h / 2) / h).floor();
    return cardAlt(column + row);
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final wiggle = _scope.wiggle;
    final child = this.child;
    if (child == null) return;
    var angle = 0.0;
    if (!wiggle.reduced && hasSize) {
      final global = localToGlobal(Offset.zero);
      final (safeTop, safeBottom) = _scope.safeArea();
      final gain = _scope.liftGain();
      if (block) {
        final p = plate?.call();
        final plateSize = p == null || p.width <= 0 || p.height <= 0 ? size : p;
        final outsetV = math.max(0.0, (plateSize.height - size.height) / 2);
        angle = wiggle.blockAngle(
          _scope.section,
          plate: plateSize,
          top: global.dy - outsetV,
          bottom: global.dy + size.height + outsetV,
          safeTop: safeTop,
          safeBottom: safeBottom,
          liftGain: gain,
        );
      } else {
        angle = wiggle.cardAngle(
          _scope.section,
          alt: index == null ? _positionAlt(global) : cardAlt(index!),
          kickKey: kickKey,
          size: size,
          top: global.dy,
          bottom: global.dy + size.height,
          safeTop: safeTop,
          safeBottom: safeBottom,
          liftGain: gain,
        );
      }
    }
    if (angle == 0) {
      context.paintChild(child, offset);
      return;
    }
    final center = size.center(Offset.zero);
    final transform = Matrix4.identity()
      ..translateByDouble(center.dx, center.dy, 0, 1)
      ..rotateZ(angle * math.pi / 180)
      ..translateByDouble(-center.dx, -center.dy, 0, 1);
    context.pushTransform(needsCompositing, offset, transform, (context, offset) => context.paintChild(child, offset));
  }
}
