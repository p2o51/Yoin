import 'package:flutter/physics.dart';
import 'package:flutter/widgets.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'flight.dart';

enum NowPlayingStage { compact, expanded }

/// Release past this drag commits the dismiss (Android 240 px ≈ 91 dp @2.625×).
const double nowPlayingDismissDistance = 90;

/// …or a downward fling faster than this (800 px/s ≈ 300 dp/s).
const double nowPlayingDismissVelocity = 300;

/// One spring-driven number whose retarget can be seeded with a velocity
/// (SpringValue keeps the current velocity but can't take a fling's).
class OverlaySpring {
  OverlaySpring({required TickerProvider vsync, required double initial})
    : _controller = AnimationController.unbounded(vsync: vsync, value: initial);

  final AnimationController _controller;
  double? _target;

  Animation<double> get animation => _controller;
  double get value => _controller.value;
  double? get target => _target;
  bool get isAnimating => _controller.isAnimating;

  void animateTo(double target, SpringDescription spring, {double? velocity}) {
    if (_target == target && velocity == null && _controller.isAnimating) return;
    _target = target;
    if (_controller.value == target && velocity == null) return;
    _controller.animateWith(
      SpringSimulation(
        spring,
        _controller.value,
        target,
        velocity ?? _controller.velocity,
        tolerance: const Tolerance(distance: 0.0005, velocity: 0.001),
      ),
    );
  }

  void snapTo(double value) {
    _target = value;
    _controller.value = value;
  }

  void dispose() => _controller.dispose();
}

/// Now Playing's one controller (ShellOverlayDown). Every input — pill tap,
/// the chevron, system back, the drag and its fling — goes through here, and
/// the overlay reads ONE displacement: y = (1 − [open]) × H + [dragDy]. A
/// committed drag folds dragDy into [open] and springs out from there with
/// the finger's velocity; nothing ever restarts.
///
/// Also owns the stage (Compact ⇄ Expanded lyrics): [detail] is driven
/// only by [_reconcileStage] (single settle owner, memory
/// project_np_stage_settle_owner); back handlers just flip [stage].
class NowPlayingOverlayController extends ChangeNotifier {
  NowPlayingOverlayController({required TickerProvider vsync})
    : open = OverlaySpring(vsync: vsync, initial: 0),
      fade = OverlaySpring(vsync: vsync, initial: 0),
      bar = OverlaySpring(vsync: vsync, initial: 0),
      dragDy = OverlaySpring(vsync: vsync, initial: 0),
      detail = OverlaySpring(vsync: vsync, initial: 0),
      flight = OverlaySpring(vsync: vsync, initial: 0) {
    open.animation.addStatusListener(_onSettle);
    flight.animation.addStatusListener(_onSettle);
    fade.animation.addStatusListener(_onSettle);
  }

  /// 0 = closed, 1 = fully up.
  final OverlaySpring open;

  /// The overlay's and scrim's alpha (effects spring; rides the slide).
  final OverlaySpring fade;

  /// 0 = the bar shows, 1 = the bar has left.
  final OverlaySpring bar;

  /// The drag-down offset in logical px (≥ 0).
  final OverlaySpring dragDy;

  /// Stage detail progress: 0 = Compact, 1 = Expanded lyrics.
  final OverlaySpring detail;

  /// Shared pill ⇄ player flight: 0 = at the pill, 1 = at the player.
  final OverlaySpring flight;

  /// Slots the shared flight measures.
  final FlightKeys flightKeys = FlightKeys();

  /// Whether the cover has artwork to fly (no URL: it fades with the overlay).
  bool coverCanFly = true;

  /// The flight only moves elements it can draw.
  bool flightCanFly(FlightElement element) => element != FlightElement.cover || coverCanFly;

  bool _expanded = false;
  NowPlayingStage _stage = NowPlayingStage.compact;
  bool _flightActive = false;
  bool _reduceMotion = false;
  double _height = 800;

  /// Back layering seen from outside (the queue sheet hooks in here).
  bool Function()? handleInnerBack;

  bool get expanded => _expanded;
  NowPlayingStage get stage => _stage;

  /// The overlay is composed: open, or still leaving.
  bool get isMounted => _expanded || open.value > 0.0005 || fade.value > 0.005;

  /// The cover / title / artist fly between pill and player right now.
  bool get flightActive => _flightActive;

  /// While mounted but not fully settled, clip to the screen corners.
  bool get isMoving => open.isAnimating || dragDy.value > 0.5;

  /// Drag fraction, rotating the chevron 180°.
  double get dismissFraction => (dragDy.value / nowPlayingDismissDistance).clamp(0.0, 1.0);

  /// The bar may be composed (it isn't while the player is fully up). It
  /// stays composed, invisible, until the shared flight has landed: the pill
  /// is the flight's source slot, and dropping it mid-flight left both ends
  /// hidden with nothing to paint — the cover, title and artist blinked out
  /// for a few frames right before the player settled.
  bool get barComposed => bar.value < 0.999 || bar.isAnimating || _flightActive;

  Listenable get motion => Listenable.merge([open.animation, fade.animation, dragDy.animation, bar.animation]);

  set reduceMotion(bool value) => _reduceMotion = value;

  /// The overlay's height; drag ↔ open conversions use it.
  set height(double value) {
    if (value > 0) _height = value;
  }

  double get height => _height;

  /// Vertical offset of the player, in px.
  double get offsetY => _reduceMotion ? dragDy.value : (1 - open.value) * _height + dragDy.value;

  void openPlayer() {
    if (_expanded) return;
    _expanded = true;
    if (_reduceMotion) {
      open.snapTo(1);
      dragDy.snapTo(0);
    } else {
      open.animateTo(1, YoinSprings.defaultSpatial);
      // A re-open during the exit takes the pose back on the drag-reset spring.
      dragDy.animateTo(0, YoinSprings.standardDefaultSpatial);
      _startFlight(toPlayer: true);
    }
    fade.animateTo(1, YoinSprings.defaultEffects);
    bar.animateTo(1, YoinSprings.standardDefaultSpatial);
    notifyListeners();
  }

  /// Every dismiss lands here. [velocity] (px/s, down positive) seeds the
  /// slide from a fling.
  void close({double velocity = 0}) {
    if (!_expanded) return;
    _expanded = false;
    if (_reduceMotion) {
      dragDy.snapTo(0);
      open.snapTo(0);
    } else {
      // Fold the drag into the slide: the same pose, one value from now on.
      final folded = (open.value - dragDy.value / _height).clamp(0.0, 1.0);
      dragDy.snapTo(0);
      open.snapTo(folded);
      open.animateTo(0, YoinSprings.standardDefaultSpatial, velocity: -velocity / _height);
      _startFlight(toPlayer: false);
    }
    fade.animateTo(0, YoinSprings.defaultEffects);
    bar.animateTo(0, YoinSprings.standardDefaultSpatial);
    notifyListeners();
  }

  /// System back / back key / side swipe (no progress on HarmonyOS: it
  /// commits). Returns false when the overlay isn't open.
  bool handleBack() {
    if (!_expanded) return false;
    if (handleInnerBack?.call() ?? false) return true;
    if (_stage == NowPlayingStage.expanded) {
      stepBackStage();
    } else {
      close();
    }
    return true;
  }

  /// The chevron: Expanded → Compact, Compact → closed.
  void onChevron() => handleBack();

  void expandStage() {
    if (_stage == NowPlayingStage.expanded || !_expanded) return;
    _stage = NowPlayingStage.expanded;
    _reconcileStage();
  }

  void stepBackStage() {
    if (_stage == NowPlayingStage.compact) return;
    _stage = NowPlayingStage.compact;
    _reconcileStage();
  }

  void _reconcileStage() {
    final target = _stage == NowPlayingStage.expanded ? 1.0 : 0.0;
    if (_reduceMotion) {
      detail.snapTo(target);
    } else {
      detail.animateTo(target, YoinSprings.of(MotionTokens.stageSettle));
    }
    notifyListeners();
  }

  // Drag-to-dismiss (disabled in Expanded: the lyric list scrolls).
  bool get canDrag => _expanded && _stage == NowPlayingStage.compact;

  void dragUpdate(double delta) {
    if (!canDrag) return;
    final next = (dragDy.value + delta).clamp(0.0, double.infinity);
    dragDy.snapTo(next);
  }

  void dragEnd(double velocity) {
    if (!canDrag) return;
    if (dragDy.value > nowPlayingDismissDistance || velocity > nowPlayingDismissVelocity) {
      close(velocity: velocity < 0 ? 0 : velocity);
    } else {
      dragDy.animateTo(0, YoinSprings.standardDefaultSpatial, velocity: -velocity);
    }
  }

  void _startFlight({required bool toPlayer}) {
    // A new flight measures afresh (the window may have changed since); a
    // reversed one leaves from where its arriving end was last drawn.
    if (!_flightActive) flightKeys.lastRects.clear();
    flightKeys.leavesPill = toPlayer;
    // Read before this frame's build mutes the hidden hero marquee (which
    // rewinds it): the close leaves the title where it had scrolled to.
    flightKeys.heroTitleScroll = toPlayer ? 0 : flightKeys.heroTitleMarquee.offset;
    _flightActive = true;
    flight.animateTo(
      toPlayer ? 1 : 0,
      // Open rides the player's twins, close the bar's (spec item 4).
      toPlayer ? YoinSprings.slowSpatial : YoinSprings.standardDefaultSpatial,
    );
  }

  void _onSettle(AnimationStatus _) {
    var changed = false;
    if (_flightActive && !flight.isAnimating && !open.isAnimating) {
      _flightActive = false;
      changed = true;
    }
    // Disposed pose: reset the stage only once the exit has finished.
    if (!_expanded && !open.isAnimating && !fade.isAnimating && open.value <= 0.0005) {
      if (_stage != NowPlayingStage.compact || detail.value != 0) {
        _stage = NowPlayingStage.compact;
        detail.snapTo(0);
      }
      changed = true;
    }
    if (changed) notifyListeners();
  }

  @override
  void dispose() {
    open.dispose();
    fade.dispose();
    bar.dispose();
    dragDy.dispose();
    detail.dispose();
    flight.dispose();
    super.dispose();
  }
}

/// Gives the chrome's widgets (the pill, Now Playing) the overlay controller.
class NowPlayingOverlayScope extends InheritedNotifier<NowPlayingOverlayController> {
  const NowPlayingOverlayScope({super.key, required NowPlayingOverlayController controller, required super.child})
    : super(notifier: controller);

  static NowPlayingOverlayController? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<NowPlayingOverlayScope>()?.notifier;

  /// Without subscribing to its changes.
  static NowPlayingOverlayController? read(BuildContext context) =>
      context.getInheritedWidgetOfExactType<NowPlayingOverlayScope>()?.notifier;
}
