/// Home edit mode's press charge, lift and strip carry (Android ui/home/edit
/// HomeCarryEngine.kt and HomeEditGeometry.kt §Press, §Strips, port sheet
/// §2.2–2.4 and §4), number for number. Everything is page px (dp).
///
/// The lift and its tint are one shared pair, so only one block is ever
/// lifted. The carry folds every section into a compact strip (Drag), the
/// strips reorder under the finger, the released strip settles into its slot
/// (Settle), the order commits, and the stack unfolds back into the feed
/// (Unfold).
library;

import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' show Color, Offset, Rect, Size, lerpDouble;

import 'package:flutter/animation.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/physics.dart';

import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'home_edit_wiggle.dart';
import 'home_metrics.dart';
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

/// The strip stack, computed once at fold start (port sheet §4.2). Page px.
@immutable
final class StripMetrics {
  const StripMetrics({
    required this.count,
    required this.height,
    required this.pitch,
    required this.stackHeight,
    required this.left,
    required this.width,
    required this.top,
    required this.coverSize,
    required this.safeTop,
    required this.safeBottom,
  });

  final int count;

  /// Strip height h_s.
  final double height;
  final double pitch;
  final double stackHeight;
  final double left;
  final double width;

  /// Slot 0's top.
  final double top;
  final double coverSize;
  final double safeTop;
  final double safeBottom;

  double slotY(int index) => top + index * pitch;
}

/// Lays out [count] strips so the carried one ([carriedIndex]) is centred on [fingerY], inside the safe area.
StripMetrics stripMetrics({
  required int count,
  required int carriedIndex,
  required double fingerY,
  required double safeTop,
  required double safeBottom,
  required double contentLeft,
  required double contentWidth,
}) {
  final n = math.max(1, count);
  const gap = HomeCarryTokens.stripGap;
  final height = (((safeBottom - safeTop) - (n - 1) * gap) / n).clamp(
    HomeCarryTokens.stripMinH,
    HomeCarryTokens.stripMaxH,
  );
  final pitch = height + gap;
  final stackHeight = n * pitch - gap;
  // (k + ½)·pitch − gap/2 puts strip k's centre exactly on the finger.
  final double top = (fingerY - (carriedIndex + 0.5) * pitch + gap / 2).clamp(
    safeTop,
    math.max(safeTop, safeBottom - stackHeight),
  );
  return StripMetrics(
    count: n,
    height: height,
    pitch: pitch,
    stackHeight: stackHeight,
    left: contentLeft,
    width: math.min(contentWidth, HomeCarryTokens.stripMaxW),
    top: top,
    coverSize: (height - HomeCarryTokens.stripCoverInset).clamp(
      HomeCarryTokens.stripCoverMin,
      HomeCarryTokens.stripCoverMax,
    ),
    safeTop: safeTop,
    safeBottom: safeBottom,
  );
}

/// Where a strip flies from (B_i): its visible plate, or an invisible strip at its side's edge.
@immutable
final class StripStart {
  const StripStart(this.rect, this.alpha);
  final Rect rect;
  final double alpha;
}

/// B_i for a section whose feed plate is [plate] (page px, null when not laid
/// out; [above] then picks the side). The visible part of the plate inside
/// the safe area, or an invisible 0.96 strip at the safe edge it left by.
StripStart stripStart(Rect? plate, StripMetrics metrics, {required bool above}) {
  if (plate != null) {
    final top = math.max(plate.top, metrics.safeTop);
    final bottom = math.min(plate.bottom, metrics.safeBottom);
    if (bottom - top > 1) return StripStart(Rect.fromLTRB(plate.left, top, plate.right, bottom), 1);
  }
  final offAbove = plate == null ? above : plate.bottom <= metrics.safeTop;
  final y = offAbove ? metrics.safeTop : metrics.safeBottom - metrics.height;
  const scale = HomeCarryTokens.offscreenStripScale;
  const inset = (1 - scale) / 2;
  return StripStart(
    Rect.fromLTWH(
      metrics.left + metrics.width * inset,
      y + metrics.height * inset,
      metrics.width * scale,
      metrics.height * scale,
    ),
    0,
  );
}

/// One strip at one fold value. Page px.
@immutable
final class StripFrame {
  const StripFrame({
    required this.rect,
    required this.radius,
    required this.plateAlpha,
    required this.labelOffset,
    required this.labelAlpha,
    required this.tint,
    required this.shadowAlpha,
  });

  final Rect rect;
  final double radius;
  final double plateAlpha;

  /// The label keeps its slot size (w × h_s); only its position follows [rect].
  final Offset labelOffset;
  final double labelAlpha;

  /// Weight toward the first cover's colour; lerp only where the section has a cover.
  final double tint;

  /// Carried strip only.
  final double shadowAlpha;
}

/// Strip frame (port sheet §4.4) at [fold], which overshoots on purpose: the
/// geometry uses it unclamped. [slotTop] is the strip's slot plus its offset
/// (drag display, settle or make-way).
StripFrame stripFrame({
  required StripStart start,
  required double slotTop,
  required StripMetrics metrics,
  required double fold,
  required bool carried,
  required double lift,
}) {
  var slot = Rect.fromLTWH(metrics.left, slotTop, metrics.width, metrics.height);
  if (carried) {
    final grow = HomeCarryTokens.liftScale * lift / 2;
    slot = Rect.fromLTRB(
      slot.left - slot.width * grow,
      slot.top - slot.height * grow,
      slot.right + slot.width * grow,
      slot.bottom + slot.height * grow,
    );
  }
  final rect = Rect.fromLTRB(
    lerpDouble(start.rect.left, slot.left, fold)!,
    lerpDouble(start.rect.top, slot.top, fold)!,
    lerpDouble(start.rect.right, slot.right, fold)!,
    lerpDouble(start.rect.bottom, slot.bottom, fold)!,
  );
  final alpha = lerpDouble(start.alpha, 1, fold.clamp(0.0, 1.0))!;
  final radius = math.max(0.0, math.min(HomeCarryTokens.plateRadius, rect.height / 2));
  return StripFrame(
    rect: rect,
    radius: radius,
    plateAlpha: alpha * carrySmoothstep(0, HomeCarryTokens.foldPlateEnd, fold),
    labelOffset: Offset(rect.left, rect.center.dy - metrics.height / 2),
    labelAlpha: alpha * carrySmoothstep(HomeCarryTokens.foldLabelStart, 1, fold),
    tint: HomeCarryTokens.stripTint * carrySmoothstep(HomeCarryTokens.foldHoleStart, 1, fold),
    shadowAlpha: carried ? lift.clamp(0.0, 1.0) * carrySmoothstep(HomeCarryTokens.foldShadowStart, 1, fold) : 0,
  );
}

/// The drop hole at [holeY]: slot-sized, under every strip.
Rect holeRect(StripMetrics metrics, double holeY) => Rect.fromLTWH(metrics.left, holeY, metrics.width, metrics.height);

/// Feed sections fade out as the strips fold in: the exact complement of the strip plates.
double feedFoldAlpha(double fold) => 1 - carrySmoothstep(0, HomeCarryTokens.foldPlateEnd, fold);

/// How the feed shows [feedFoldAlpha] (port sheet §4.5): a page-ground wash
/// of this alpha over the sections and tray (below the header), the same
/// picture as fading each block, without an offscreen pass per block.
double feedFoldWash(double fold) => 1 - feedFoldAlpha(fold);

/// The sections' and tray's own alpha under the wash: drawn, or skipped once it covers them.
double feedFoldShown(double fold) => feedFoldAlpha(fold) > 0 ? 1 : 0;

double holeAlpha(double fold) => carrySmoothstep(HomeCarryTokens.foldHoleStart, 1, fold);

/// Displayed overscroll for a raw [overscroll] past the end slots. Resists from the first px.
double rubber(double overscroll) {
  const range = HomeCarryTokens.rubberD;
  return range * (1 - 1 / (overscroll * HomeCarryTokens.rubberK / range + 1));
}

/// Inverse of [rubber], for re-grabbing a strip that is settling from an overscroll.
double unrubber(double displayed) {
  const range = HomeCarryTokens.rubberD;
  final ratio = math.min(displayed / range, HomeCarryTokens.unrubberMaxRatio);
  return range / HomeCarryTokens.rubberK * (1 / (1 - ratio) - 1);
}

/// A strip's plate colour (port sheet §4.4, critique 8): the feed plate's
/// colour (the carried strip follows its lift tint, so the hand-off doesn't
/// snap), then [tint] of the way to the first cover's palette base.
Color stripPlateColor({
  required bool carried,
  required double liftTint,
  required Color plate,
  required Color liftedPlate,
  required Color? coverBase,
  required double tint,
}) {
  final base = carried ? Color.lerp(plate, liftedPlate, liftTint.clamp(0.0, 1.0))! : plate;
  return coverBase != null && tint > HomeCarryTokens.stripTintVisible ? Color.lerp(base, coverBase, tint)! : base;
}

/// The side an unlaid-out section of [order] comes from: above unless it
/// follows every visible block. With no block visible, [pastHeader] decides.
bool carryIsAbove(HomeSection section, List<HomeSection> order, List<HomeSection> visible, {required bool pastHeader}) {
  var last = -1;
  for (final v in visible) {
    last = math.max(last, order.indexOf(v));
  }
  return last >= 0 ? order.indexOf(section) < last : pastHeader;
}

/// Release velocity (px/s) over the last [HomeCarryTokens.velocityWindowMs]:
/// a two-point slope, 0 when the finger rested before lifting, clamped to
/// ±[maxVelocity]. Feed it finger y in page px.
class HomeEditVelocity {
  HomeEditVelocity({this.maxVelocity = HomeCarryTokens.maxV});

  final double maxVelocity;
  final List<int> _times = [];
  final List<double> _positions = [];

  void reset() {
    _times.clear();
    _positions.clear();
  }

  void add(int uptimeMs, double y) {
    _times.add(uptimeMs);
    _positions.add(y);
    while (_times.length > 2 && uptimeMs - _times[0] > HomeCarryTokens.velocityWindowMs) {
      _times.removeAt(0);
      _positions.removeAt(0);
    }
  }

  double velocity(int nowMs) {
    if (_times.length < 2) return 0;
    final last = _times.length - 1;
    if (nowMs - _times[last] > HomeCarryTokens.velocityStaleMs) return 0;
    var first = 0;
    while (first < _times.length - 2 && _times[last] - _times[first] > HomeCarryTokens.velocityWindowMs) {
      first++;
    }
    final elapsedMs = _times[last] - _times[first];
    if (elapsedMs <= HomeCarryTokens.velocityMinDtMs) return 0;
    final velocity = (_positions[last] - _positions[first]) / (elapsedMs / 1000);
    return velocity.clamp(-maxVelocity, maxVelocity);
  }
}

// ── Springs ───────────────────────────────────────────────────────────────

/// Every spring the press, lift and carry run (HomeEditSpecs, Expressive
/// role). Under reduced motion every spatial spring becomes fastEffects.
@immutable
final class HomeCarrySpecs {
  factory HomeCarrySpecs({required bool reduced}) {
    final fastEffects = YoinSprings.fastEffects;
    final defaultSpatial = reduced ? fastEffects : YoinSprings.defaultSpatial;
    final fastSpatial = reduced ? fastEffects : YoinSprings.fastSpatial;
    final slowSpatial = reduced ? fastEffects : YoinSprings.slowSpatial;
    return HomeCarrySpecs._(
      reduced: reduced,
      fold: defaultSpatial,
      settle: defaultSpatial,
      neighbour: defaultSpatial,
      hole: fastSpatial,
      chargeUp: slowSpatial,
      chargeDown: fastSpatial,
      liftUp: fastSpatial,
      liftDown: defaultSpatial,
      liftTint: fastEffects,
    );
  }

  const HomeCarrySpecs._({
    required this.reduced,
    required this.fold,
    required this.settle,
    required this.neighbour,
    required this.hole,
    required this.chargeUp,
    required this.chargeDown,
    required this.liftUp,
    required this.liftDown,
    required this.liftTint,
  });

  final bool reduced;
  final SpringDescription fold;
  final SpringDescription settle;
  final SpringDescription neighbour;
  final SpringDescription hole;
  final SpringDescription chargeUp;
  final SpringDescription chargeDown;
  final SpringDescription liftUp;
  final SpringDescription liftDown;

  /// Both ways.
  final SpringDescription liftTint;
}

/// One value on a spring with its own threshold (Compose's Animatable). An
/// animation retargets from the current value and velocity; [animateTo]
/// answers whether it ran to the end (false: interrupted by another
/// animation or a snap).
class CarrySpring {
  CarrySpring(TickerProvider vsync, [double initial = 0])
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
  HomeEditPressState(TickerProvider vsync) : charge = CarrySpring(vsync);

  final CarrySpring charge;

  /// The pressed block. Kept through the release, so the charge eases out where it was.
  HomeSection? section;

  /// The press point in block-content px: the scale's pivot.
  Offset origin = Offset.zero;

  void start(HomeSection section, Offset origin, HomeCarrySpecs specs) {
    // No charge and no plate latch under reduced motion (§1.2).
    if (specs.reduced) return;
    this.section = section;
    this.origin = origin;
    charge.animateTo(1, specs.chargeUp, HomeCarryTokens.unitThreshold);
  }

  void release(HomeCarrySpecs specs) {
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

// ── Carry ─────────────────────────────────────────────────────────────────

enum CarryPhase { drag, settle, unfold }

/// The feed as the strip carry sees it (Home's list).
abstract interface class CarryHost {
  /// Shown sections in the layout's order (data, never what happens to be built).
  List<HomeSection> displayedOrder();

  /// The section's plate in page px; null if it is not laid out.
  Rect? plateRect(HomeSection section);

  /// Which side an unlaid-out section left by, for its B_i.
  bool isAbove(HomeSection section);

  /// The band between the status tide and the bar, page px.
  (double top, double bottom) safeArea();

  double contentLeft();

  double contentWidth();

  /// Scrolls the dropped block under its slot, then waits for a layout in [order].
  Future<void> anchorAndAwaitLayout(HomeSection dropped, List<HomeSection> order, double slotTop);
}

/// One strip carry (port sheet §4.14). [carried], [orig] and [metrics] are
/// fixed at fold start.
class CarrySession {
  CarrySession({
    required this.carried,
    required this.orig,
    required this.metrics,
    required this.starts,
    required this.lastY,
  }) : ids = orig,
       k = orig.indexOf(carried);

  final HomeSection carried;

  /// The order at fold start: the drop commits only if [ids] differs.
  final List<HomeSection> orig;
  final StripMetrics metrics;

  /// The live order; the carried strip sits at [k].
  List<HomeSection> ids;
  int k;

  /// B_i: where each strip flies from, re-read from the feed after the anchor.
  Map<HomeSection, StripStart> starts;

  /// The carried strip's offset from slot [k] while dragged, rubber-banded past the ends.
  double display = 0;
  CarryPhase phase = CarryPhase.drag;

  double dragOffset = 0;
  double lastY;
  bool wasOver = false;

  int get count => orig.length;

  bool get changed => !listEquals(ids, orig);

  /// [offset] from slot [k] runs past the first or last slot.
  bool isOver(double offset) => (k == 0 && offset < 0) || (k == count - 1 && offset > 0);
}

/// Lift and the strip carry (port sheet §2.3–2.4, §4). Notifies when a block
/// is lifted or let go and when a carry starts, changes phase or ends;
/// [paint] ticks for everything the strips draw.
class HomeCarryEngine extends ChangeNotifier {
  HomeCarryEngine({
    required TickerProvider vsync,
    required this.specs,
    required this.wiggle,
    required this.commitOrder,
    required this.finishDeferredExit,
    int Function()? uptimeMs,
  }) : _uptimeMs = uptimeMs ?? _monotonicMs,
       lift = CarrySpring(vsync),
       liftTint = CarrySpring(vsync),
       fold = CarrySpring(vsync),
       holeY = CarrySpring(vsync),
       settleY = CarrySpring(vsync),
       _stripOffsets = {for (final s in HomeSection.values) s: CarrySpring(vsync)} {
    _paint = Listenable.merge([
      _paintTick,
      lift.listenable,
      liftTint.listenable,
      fold.listenable,
      holeY.listenable,
      settleY.listenable,
      for (final o in _stripOffsets.values) o.listenable,
    ]);
  }

  static final Stopwatch _watch = Stopwatch()..start();
  static int _monotonicMs() => _watch.elapsedMilliseconds;

  /// The springs (reduced motion swaps them).
  HomeCarrySpecs Function() specs;
  final HomeEditWiggle wiggle;

  /// Writes a new order (one Undo step); false when it changed nothing.
  final bool Function(List<HomeSection> order) commitOrder;

  /// An exit that waited for the carry: [done] is the bar's Done.
  final void Function({required bool done}) finishDeferredExit;
  final int Function() _uptimeMs;

  final _PaintTick _paintTick = _PaintTick();
  late final Listenable _paint;

  /// Everything the strips draw.
  Listenable get paint => _paint;

  // ── Lift (§2.3 step 5, §2.4) ──────────────────────────────────────────

  /// Scale 1 + .02·lift and a 6 dp shadow on the lifted block.
  final CarrySpring lift;

  /// Plate colour toward surfaceContainerHighest.
  final CarrySpring liftTint;

  HomeSection? get liftSection => _liftSection;
  HomeSection? _liftSection;

  /// The press point in block-content px: the lift's pivot.
  Offset get liftOrigin => _liftOrigin;
  Offset _liftOrigin = Offset.zero;

  /// Picks [section] up at [origin]: its kicks stop, a block still lifted elsewhere is let go at once.
  void liftBlock(HomeSection section, Offset origin) {
    final retire = _liftSection != null && _liftSection != section;
    _liftSection = section;
    _liftOrigin = origin;
    if (retire) {
      lift.snapTo(0);
      liftTint.snapTo(0);
    }
    lift.animateTo(1, specs().liftUp, HomeCarryTokens.unitThreshold);
    liftTint.animateTo(1, specs().liftTint, HomeCarryTokens.unitThreshold);
    wiggle.stopKicks(section);
    notifyListeners();
  }

  /// Puts the lifted block down where it is; [kick] > 0 kicks it, showing as the lift falls.
  void dropLift(double kick) {
    final section = _liftSection;
    if (section == null) return;
    lift.animateTo(0, specs().liftDown, HomeCarryTokens.unitThreshold).then((done) {
      if (done && _liftSection == section && _session == null) _letGo();
    });
    liftTint.animateTo(0, specs().liftTint, HomeCarryTokens.unitThreshold);
    if (kick > 0) wiggle.impulse(section, kick);
  }

  void _letGo() {
    _liftSection = null;
    notifyListeners();
  }

  void _snapLiftToRest() {
    lift.snapTo(0);
    liftTint.snapTo(0);
    if (_liftSection != null) _letGo();
  }

  // ── Exits (port sheet §5.5, critique 4) ───────────────────────────────

  /// Edit mode's animated exit: a block still lifted drops where it is with no kick, and the press lets go.
  void onExit(HomeEditPressState press) {
    dropLift(0);
    press.release(specs());
  }

  /// A snap exit: lift and press at rest now.
  void onSnapExit(HomeEditPressState press) {
    _snapLiftToRest();
    press.snapOff();
  }

  // ── Strips (§4) ───────────────────────────────────────────────────────

  /// Set by Home while it is built.
  CarryHost? host;

  /// Feed ⇄ strips, overshooting on purpose (§4.4).
  final CarrySpring fold;

  /// The drop hole's top in page px.
  final CarrySpring holeY;

  /// The released strip's offset from its slot while it settles.
  final CarrySpring settleY;

  // Make-way offsets: a swap's jump lands in the move itself, even while the neighbour is still in flight.
  final Map<HomeSection, CarrySpring> _stripOffsets;

  /// A neighbour's make-way offset (§4.6).
  double stripOffset(HomeSection section) => _stripOffsets[section]!.value;

  CarrySession? get session => _session;
  CarrySession? _session;

  /// A carry is in Drag, Settle or Unfold.
  bool get isCarrying => _session != null;

  /// Settling or unfolding: presses only re-grab (§4.12).
  bool get isBusy => _session?.phase == CarryPhase.settle || _session?.phase == CarryPhase.unfold;

  final HomeEditVelocity _velocity = HomeEditVelocity();

  // Settle, then commit, anchor, unfold and finish. A re-grab or an abort moves it on, so the commit never runs.
  int _settleGeneration = 0;

  // An exit that arrived mid-carry; it runs at finish (§4.13). True: the bar's Done.
  bool? _pendingExit;

  /// Folds every section into a strip with the lifted block's strip centred
  /// on [fingerY] (§4.1–4.3). The order is the layout's, B_i the feed plates
  /// as they stand now.
  void start(double fingerY, int uptimeMs) {
    final host = this.host;
    final carried = _liftSection;
    if (host == null || carried == null || _session != null) return;
    final ids = host.displayedOrder();
    final k = ids.indexOf(carried);
    if (k < 0) return;
    final safe = host.safeArea();
    final metrics = stripMetrics(
      count: ids.length,
      carriedIndex: k,
      fingerY: fingerY,
      safeTop: safe.$1,
      safeBottom: safe.$2,
      contentLeft: host.contentLeft(),
      contentWidth: host.contentWidth(),
    );
    ids.forEach(_settleStrip);
    holeY.snapTo(metrics.slotY(k));
    _velocity
      ..reset()
      ..add(uptimeMs, fingerY);
    _pendingExit = null;
    _session = CarrySession(
      carried: carried,
      orig: List.unmodifiable(ids),
      metrics: metrics,
      starts: _startsFor(host, ids, metrics),
      lastY: fingerY,
    );
    fold.animateTo(1, specs().fold, HomeCarryTokens.foldThreshold);
    wiggle.carrying = true;
    notifyListeners();
  }

  /// The strips' metrics if [count] sections folded now, slot top aside: what
  /// the strip stack sizes its labels by before a carry. Null without a host.
  StripMetrics? provisionalMetrics(int count) {
    final host = this.host;
    if (host == null) return null;
    final safe = host.safeArea();
    return stripMetrics(
      count: count,
      carriedIndex: 0,
      fingerY: safe.$1,
      safeTop: safe.$1,
      safeBottom: safe.$2,
      contentLeft: host.contentLeft(),
      contentWidth: host.contentWidth(),
    );
  }

  /// 1:1 in y, a swap each time the finger passes half a pitch, a rubber band past the ends (§4.6).
  void move(double y, int uptimeMs) {
    final s = _session;
    if (s == null || s.phase != CarryPhase.drag) return;
    s.dragOffset += y - s.lastY;
    s.lastY = y;
    _velocity.add(uptimeMs, y);
    final half = s.metrics.pitch / 2;
    while (s.dragOffset > half && s.k < s.count - 1) {
      _swap(s, 1);
    }
    while (s.dragOffset < -half && s.k > 0) {
      _swap(s, -1);
    }
    final over = s.isOver(s.dragOffset);
    s.display = over ? s.dragOffset.sign * rubber(s.dragOffset.abs()) : s.dragOffset;
    final overNow = over && s.dragOffset.abs() > HomeCarryTokens.overHaptic;
    if (overNow && !s.wasOver) YoinHaptics.threshold();
    s.wasOver = overNow;
    _repaint();
  }

  /// The finger lifted (or the carry was cancelled: no fling). The strip
  /// settles into its slot, a fast release projecting it one slot at most,
  /// then the order commits and the stack unfolds (§4.8–4.11).
  void release({required bool cancelled, required int uptimeMs}) {
    final s = _session;
    if (s == null) {
      // The carry never started: put the block down where it is.
      dropLift(0);
      return;
    }
    if (s.phase != CarryPhase.drag) return;
    s.phase = CarryPhase.settle;
    final v = cancelled ? 0.0 : _velocity.velocity(uptimeMs);
    // Where the strip shows now, read before a fling moves its slot.
    final shownY = s.metrics.slotY(s.k) + s.display;
    if (v.abs() > HomeCarryTokens.flingV) {
      final dir = v > 0 ? 1 : -1;
      if ((dir > 0 && s.k < s.count - 1) || (dir < 0 && s.k > 0)) _swap(s, dir);
    }
    final from = shownY - s.metrics.slotY(s.k);
    final generation = ++_settleGeneration;
    settleY.snapTo(from);
    notifyListeners();
    settleY.animateTo(0, specs().settle, HomeCarryTokens.settlePxThreshold, velocity: v).then((done) {
      if (done && generation == _settleGeneration && identical(_session, s)) _commitAndUnfold(s, generation);
    });
  }

  /// Catches the strip while it settles (§4.12): a press on it, ±8 dp, takes
  /// it back where it is and the drag goes on. Never while unfolding.
  bool tryRegrab(Offset position, int uptimeMs) {
    final s = _session;
    if (s == null || s.phase != CarryPhase.settle) return false;
    final m = s.metrics;
    final settle = settleY.value;
    final top = m.slotY(s.k) + settle;
    final inside =
        position.dx >= m.left &&
        position.dx <= m.left + m.width &&
        position.dy >= top - HomeCarryTokens.regrabSlop &&
        position.dy <= top + m.height + HomeCarryTokens.regrabSlop;
    if (!inside) return false;
    _settleGeneration++;
    // The settle stops where it stands.
    settleY.snapTo(settle);
    final over = s.isOver(settle);
    s
      ..dragOffset = over ? settle.sign * unrubber(settle.abs()) : settle
      ..display = settle
      ..lastY = position.dy
      ..wasOver = over && s.dragOffset.abs() > HomeCarryTokens.overHaptic
      ..phase = CarryPhase.drag;
    _velocity
      ..reset()
      ..add(uptimeMs, position.dy);
    _animateHole(m.slotY(s.k));
    YoinHaptics.dragStart();
    notifyListeners();
    return true;
  }

  /// An exit arrived mid-carry (§4.13): a drag is released as cancelled, a
  /// changed order still commits, and the exit runs at finish. With no carry
  /// there is nothing to wait for.
  void deferExit({required bool done}) {
    final s = _session;
    if (s == null) {
      finishDeferredExit(done: done);
      return;
    }
    _pendingExit = done;
    if (s.phase == CarryPhase.drag) release(cancelled: true, uptimeMs: _uptimeMs());
  }

  /// A snap exit mid-carry: a changed order commits now ([commit]), then every carry value is at rest (§4.13).
  void abortNow({bool commit = true}) {
    final s = _session;
    if (s == null) return;
    // In Unfold the order is already committed.
    if (commit && s.phase != CarryPhase.unfold && s.changed) commitOrder(s.ids);
    _settleGeneration++;
    HomeSection.values.forEach(_settleStrip);
    _pendingExit = null;
    _session = null;
    fold.snapTo(0);
    settleY.snapTo(0);
    holeY.snapTo(0);
    _snapLiftToRest();
    wiggle.carrying = false;
    notifyListeners();
  }

  /// The strip frame of [section] at the current fold (§4.4).
  StripFrame? frameFor(HomeSection section) {
    final s = _session;
    if (s == null) return null;
    final index = s.ids.indexOf(section);
    final start = s.starts[section];
    if (index < 0 || start == null) return null;
    final carried = section == s.carried;
    final offset = !carried ? stripOffset(section) : (s.phase == CarryPhase.drag ? s.display : settleY.value);
    return stripFrame(
      start: start,
      slotTop: s.metrics.slotY(index) + offset,
      metrics: s.metrics,
      fold: fold.value,
      carried: carried,
      lift: lift.value,
    );
  }

  // The neighbour takes the carried strip's slot but keeps its place on
  // screen, then makes way (critique 6); the hole follows the carried strip.
  void _swap(CarrySession s, int dir) {
    final k = s.k;
    final j = k + dir;
    final ids = [...s.ids];
    final neighbour = ids[j];
    ids[j] = ids[k];
    ids[k] = neighbour;
    s.ids = ids;
    _makeWay(neighbour, dir * s.metrics.pitch);
    s.k = j;
    s.dragOffset -= dir * s.metrics.pitch;
    _animateHole(s.metrics.slotY(j));
    YoinHaptics.segmentTick();
  }

  void _makeWay(HomeSection section, double by) {
    final offset = _stripOffsets[section]!;
    offset.snapTo(offset.value + by);
    offset.animateTo(0, specs().neighbour, HomeCarryTokens.neighbourPxThreshold, velocity: 0);
  }

  void _settleStrip(HomeSection section) => _stripOffsets[section]!.snapTo(0);

  // From its current motion, as the prototype's retargets do.
  void _animateHole(double y) => holeY.animateTo(y, specs().hole, HomeCarryTokens.holePxThreshold);

  Map<HomeSection, StripStart> _startsFor(CarryHost host, List<HomeSection> ids, StripMetrics metrics) => {
    for (final s in ids) s: stripStart(host.plateRect(s), metrics, above: host.isAbove(s)),
  };

  // §4.9–4.10: commit, anchor the dropped block under its slot, re-read B_i
  // from the new layout, and unfold with the lift falling alongside.
  Future<void> _commitAndUnfold(CarrySession s, int generation) async {
    s.phase = CarryPhase.unfold;
    notifyListeners();
    final changed = s.changed && commitOrder(s.ids);
    final host = this.host;
    if (host != null) {
      await host.anchorAndAwaitLayout(s.carried, s.ids, s.metrics.slotY(s.k));
      if (generation != _settleGeneration || !identical(_session, s)) return;
      s.starts = _startsFor(host, s.ids, s.metrics);
    }
    lift.animateTo(0, specs().liftDown, HomeCarryTokens.unitThreshold);
    liftTint.animateTo(0, specs().liftTint, HomeCarryTokens.unitThreshold);
    final unfolded = await fold.animateTo(0, specs().fold, HomeCarryTokens.foldThreshold);
    if (!unfolded || generation != _settleGeneration || !identical(_session, s)) return;
    _finish(s, changed: changed);
  }

  // §4.11: the strips go, the sway comes back to 1, the dropped block and
  // every block that moved get a kick, then a deferred exit runs.
  void _finish(CarrySession s, {required bool changed}) {
    _session = null;
    _snapLiftToRest();
    wiggle
      ..carrying = false
      ..touch()
      ..impulse(s.carried, HomeWiggleMetrics.kickDrop);
    if (changed) {
      for (var index = 0; index < s.ids.length; index++) {
        final section = s.ids[index];
        if (section != s.carried && s.orig.indexOf(section) != index) {
          wiggle.impulse(section, HomeWiggleMetrics.kickNeighbour);
        }
      }
    }
    notifyListeners();
    final exit = _pendingExit;
    if (exit != null) {
      _pendingExit = null;
      finishDeferredExit(done: exit);
    }
  }

  void _repaint() => _paintTick.tick();

  @override
  void notifyListeners() {
    super.notifyListeners();
    _repaint();
  }

  @override
  void dispose() {
    _settleGeneration++;
    lift.dispose();
    liftTint.dispose();
    fold.dispose();
    holeY.dispose();
    settleY.dispose();
    for (final o in _stripOffsets.values) {
      o.dispose();
    }
    _paintTick.dispose();
    super.dispose();
  }
}

class _PaintTick extends ChangeNotifier {
  void tick() => notifyListeners();
}
