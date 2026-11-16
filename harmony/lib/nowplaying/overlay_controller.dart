import 'dart:math' as math;

import 'package:flutter/physics.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import 'flight.dart';
import 'now_playing_presentation.dart';

/// The stage: Compact (rest), Expanded (the lyrics / note page), Immersive
/// (FOCUS: tap the cover — it grows, the chrome around it retreats).
enum NowPlayingStage { compact, expanded, immersive }

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
      focus = OverlaySpring(vsync: vsync, initial: 0),
      flight = OverlaySpring(vsync: vsync, initial: 0),
      full = OverlaySpring(vsync: vsync, initial: 0),
      inset = OverlaySpring(vsync: vsync, initial: 0),
      dragDx = OverlaySpring(vsync: vsync, initial: 0) {
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

  /// Focus (Immersive) progress: 0 = rest, 1 = the cover grown. Driven, like
  /// [detail], only by [_reconcileStage].
  final OverlaySpring focus;

  /// Shared pill ⇄ player flight: 0 = at the pill, 1 = at the player.
  final OverlaySpring flight;

  /// Panel (0) ⇄ Full (1): the panel family's one container.
  final OverlaySpring full;

  /// The fraction of the panel's width the content beside it gives up
  /// (0 = none, 1 = the whole panel), on the panel's own spring.
  final OverlaySpring inset;

  /// The side panel dragged toward the end, in logical px (≥ 0).
  final OverlaySpring dragDx;

  /// Slots the shared flight measures.
  final FlightKeys flightKeys = FlightKeys();

  /// Whether the cover has artwork to fly (no URL: it fades with the overlay).
  bool coverCanFly = true;

  /// The flight only moves elements it can draw. The edge capsule's upright
  /// pill turns its text sideways: there only the cover travels.
  bool flightCanFly(FlightElement element) => switch (_shown.presentation.pillMorph) {
    PillMorph.none => false,
    PillMorph.cover => element == FlightElement.cover && coverCanFly,
    PillMorph.coverAndText when _window.chromeForm == ShellChromeForm.edgeSplit =>
      element == FlightElement.cover && coverCanFly,
    PillMorph.coverAndText => element != FlightElement.cover || coverCanFly,
  };

  bool _expanded = false;
  NowPlayingStage _stage = NowPlayingStage.compact;
  bool _flightActive = false;
  bool _reduceMotion = false;
  double _height = 800;
  YoinWindowInfo _window = const YoinWindowInfo(360, 800);
  bool _fullscreen = false;

  /// Live frame (this window, this Full flag) and the frame the player is
  /// shown in: latched at open and kept through the exit, so a resize or a
  /// flag reset mid-exit never turns a leaving panel into a full player.
  NowPlayingFrame _live = NowPlayingFrame.resolve(const YoinWindowInfo(360, 800), fullscreen: false);
  NowPlayingFrame _shown = NowPlayingFrame.resolve(const YoinWindowInfo(360, 800), fullscreen: false);

  /// Back layering seen from outside (the queue sheet hooks in here).
  bool Function()? handleInnerBack;

  /// A sheet is open over the player (the queue, a song's sheet, About):
  /// it takes the window's back before any surface beside the player.
  bool Function()? innerSheetOpen;

  /// The note composer's back level (the screen hooks in here): while it
  /// is being written in, back only puts the pen down — before the stage,
  /// focus and Full → panel.
  bool Function()? handleComposerBack;

  bool get expanded => _expanded;
  NowPlayingStage get stage => _stage;

  /// The cover can focus in the body shown now (it grows by max(40, 12 %)).
  /// The body sets it on every layout; a stale Immersive drops back there.
  bool focusOffered = false;

  /// The frame the player is shown in (see [_shown]).
  NowPlayingFrame get frame => _shown;
  NowPlayingPresentation get presentation => _shown.presentation;

  /// The side panel is the presentation (open, not Full).
  bool get panelOpen => _expanded && _shown.presentation == NowPlayingPresentation.panel;

  /// The panel family's player is Full (enlarged phone / two columns).
  bool get fullscreen => _fullscreen;

  /// The window Now Playing opens in (the chrome sets it on every build; no
  /// notification of its own — the chrome's dependents rebuild with it).
  /// A size-class change while open applies Android's Full-flag rules.
  set window(YoinWindowInfo value) {
    if (value == _window) return;
    final previous = nowPlayingSizeClass(_window);
    _window = value;
    final current = nowPlayingSizeClass(value);
    if (previous != current) {
      _fullscreen = fullscreenAfterLayoutChange(
        previous: previous,
        current: current,
        expanded: _expanded,
        wasFullscreen: _fullscreen,
      );
    }
    final before = _shown;
    _resolveFrame(settle: false);
    if (_expanded && _shown != before) {
      _reconcileFrame();
      // Set from a build: tell the overlay's listeners after this frame.
      _notifyAfterFrame();
    }
  }

  YoinWindowInfo get window => _window;
  bool _disposed = false;

  double _minContentWidth = NowPlayingPanelTokens.minContentWidth;

  /// What the host keeps beside the side panel (adaptive-principles §4): a
  /// phone column, or two columns + gutter while the detail column is open.
  /// Where it can't fit there is no panel and Full opens directly; the
  /// column is never dropped or rearranged for Now Playing. A change while
  /// open keeps a Full player Full (the floor dropping under it never turns
  /// it into a panel by itself); a panel that no longer fits goes Full.
  double get minContentWidth => _minContentWidth;
  set minContentWidth(double value) {
    if (value == _minContentWidth) return;
    _minContentWidth = value;
    if (_expanded && _shown.presentation != NowPlayingPresentation.panel) _fullscreen = true;
    final before = _shown;
    _resolveFrame(settle: false);
    if (_expanded && _shown != before) {
      _reconcileFrame();
      _notifyAfterFrame();
    }
  }

  /// Set from a build or a listener mid-frame: tell the listeners after it.
  void _notifyAfterFrame() {
    SchedulerBinding.instance.addPostFrameCallback((_) {
      if (!_disposed) notifyListeners();
    });
    SchedulerBinding.instance.ensureVisualUpdate();
  }

  /// A detail column is about to open (adaptive-principles §4: the page
  /// asked for is always visible). Where the panel fits beside two columns
  /// ([columnsMinWidth]) a Full player steps back to the panel; where it
  /// doesn't, the player closes.
  void yieldToColumn(double columnsMinWidth) {
    if (!_expanded) return;
    if (canOpenNowPlayingPanel(_window, minContentWidth: columnsMinWidth)) {
      if (_shown.presentation != NowPlayingPresentation.panel) _setFullscreen(false);
    } else {
      close();
    }
  }

  /// A resize while open moved the player to another frame: the container,
  /// the content beside it and the bar follow on their springs.
  void _reconcileFrame() {
    final panel = _shown.presentation == NowPlayingPresentation.panel;
    final spring = YoinSprings.standardDefaultSpatial;
    if (_shown.panelFamily) {
      full.animateTo(panel ? 0 : 1, spring);
    } else {
      full.snapTo(1);
      dragDx.snapTo(0);
      open.snapTo(1);
    }
    inset.animateTo(panel ? 1 : 0, spring);
    bar.animateTo(panel ? 0 : 1, spring);
    final noExpanded =
        _shown.presentation == NowPlayingPresentation.dualPane ||
        _shown.presentation == NowPlayingPresentation.tabletop;
    if (noExpanded && _stage == NowPlayingStage.expanded) {
      _stage = NowPlayingStage.compact;
      detail.snapTo(0);
    }
  }

  /// The overlay is composed: open, or still leaving.
  bool get isMounted => _expanded || open.value > 0.0005 || fade.value > 0.005;

  /// The cover / title / artist fly between pill and player right now.
  bool get flightActive => _flightActive;

  /// While mounted but not fully settled, clip to the screen corners.
  bool get isMoving => open.isAnimating || dragDy.value > 0.5 || dragDx.value > 0.5;

  /// Drag fraction, rotating the chevron 180°.
  double get dismissFraction => (dragDy.value / nowPlayingDismissDistance).clamp(0.0, 1.0);

  /// The bar may be composed (it isn't while the player is fully up). It
  /// stays composed, invisible, until the shared flight has landed: the pill
  /// is the flight's source slot, and dropping it mid-flight left both ends
  /// hidden with nothing to paint — the cover, title and artist blinked out
  /// for a few frames right before the player settled.
  bool get barComposed => bar.value < 0.999 || bar.isAnimating || _flightActive;

  Listenable get motion => Listenable.merge([
    open.animation,
    fade.animation,
    dragDy.animation,
    bar.animation,
    full.animation,
    dragDx.animation,
  ]);

  /// The content beside the panel moves on these.
  late final Listenable besideMotion = Listenable.merge([inset.animation, dragDx.animation]);

  /// The end inset the content beside the panel gives up this frame, px:
  /// the panel's width on its spring, minus the drag carrying it away 1:1.
  double get besideInset => math.max(0.0, inset.value * _shown.panelWidth - dragDx.value);

  set reduceMotion(bool value) => _reduceMotion = value;

  /// The overlay's height; drag ↔ open conversions use it.
  set height(double value) {
    if (value > 0) _height = value;
  }

  double get height => _height;

  /// Vertical offset of the player, in px (0 for the panel family, which
  /// slides in from the end).
  double get offsetY {
    if (_shown.panelFamily) return 0;
    return _reduceMotion ? dragDy.value : (1 - open.value) * _height + dragDy.value;
  }

  /// The panel family's container width this frame.
  double get containerWidth {
    final panel = _shown.panelWidth;
    return panel + (_window.width - panel) * full.value.clamp(0.0, 1.0);
  }

  /// The panel family's horizontal offset toward the end, px.
  double get offsetX {
    if (!_shown.panelFamily) return 0;
    final travel = _reduceMotion ? 0.0 : (1 - open.value) * containerWidth;
    return travel + dragDx.value;
  }

  void _resolveFrame({required bool settle}) {
    _live = NowPlayingFrame.resolve(_window, fullscreen: _fullscreen, minContentWidth: _minContentWidth);
    if (_expanded) _shown = _live;
    if (settle) notifyListeners();
  }

  void openPlayer() {
    if (_expanded) return;
    _expanded = true;
    // A fresh open is always the panel where one fits.
    _fullscreen = false;
    _resolveFrame(settle: false);
    if (_shown.panelFamily) {
      _openPanel();
      notifyListeners();
      return;
    }
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

  /// The panel family enters from the end on the Standard spring with no
  /// fade, no scrim and no flight; the bar stays (it folds to the two
  /// destinations), the content beside narrows on the same spring.
  void _openPanel() {
    full.snapTo(_shown.presentation == NowPlayingPresentation.panel ? 0 : 1);
    fade.snapTo(1);
    if (_reduceMotion) {
      open.snapTo(1);
      dragDx.snapTo(0);
      inset.snapTo(1);
    } else {
      open.animateTo(1, YoinSprings.standardDefaultSpatial);
      dragDx.animateTo(0, YoinSprings.standardDefaultSpatial);
      inset.animateTo(1, YoinSprings.standardDefaultSpatial);
    }
    bar.animateTo(0, YoinSprings.standardDefaultSpatial);
  }

  /// Panel ⇄ Full (the corner toggle; back from Full). Only where the pair exists.
  void toggleFullscreen() => _setFullscreen(!_fullscreen);

  void _setFullscreen(bool value) {
    if (!_expanded || !_live.panelAvailable || value == _fullscreen) return;
    _fullscreen = value;
    _resolveFrame(settle: false);
    final toFull = _shown.presentation != NowPlayingPresentation.panel;
    // The two columns have no Expanded substate (their right column always is).
    if (_shown.presentation == NowPlayingPresentation.dualPane && _stage == NowPlayingStage.expanded) {
      _stage = NowPlayingStage.compact;
      detail.snapTo(0);
    }
    dragDx.snapTo(0);
    if (_reduceMotion) {
      full.snapTo(toFull ? 1 : 0);
      inset.snapTo(toFull ? 0 : 1);
      bar.snapTo(toFull ? 1 : 0);
    } else {
      full.animateTo(toFull ? 1 : 0, YoinSprings.standardDefaultSpatial);
      inset.animateTo(toFull ? 0 : 1, YoinSprings.standardDefaultSpatial);
      bar.animateTo(toFull ? 1 : 0, YoinSprings.standardDefaultSpatial);
    }
    notifyListeners();
  }

  /// Every dismiss lands here. [velocity] (px/s, down positive) seeds the
  /// slide from a fling.
  void close({double velocity = 0}) {
    if (!_expanded) return;
    _expanded = false;
    if (_shown.panelFamily) {
      _closePanel(velocity: velocity);
      notifyListeners();
      return;
    }
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

  /// The panel family leaves toward the end from where the drag left it;
  /// [velocity] (px/s, toward the end) seeds the slide.
  void _closePanel({double velocity = 0}) {
    final width = containerWidth;
    if (_reduceMotion || width <= 0) {
      open.snapTo(0);
      dragDx.snapTo(0);
      inset.snapTo(0);
    } else {
      final folded = (open.value - dragDx.value / width).clamp(0.0, 1.0);
      dragDx.snapTo(0);
      open.snapTo(folded);
      // The beside content keeps the drag's give-back, then widens with the slide.
      inset.snapTo(inset.value * folded);
      open.animateTo(0, YoinSprings.standardDefaultSpatial, velocity: -velocity / width);
      inset.animateTo(0, YoinSprings.standardDefaultSpatial);
    }
    fade.animateTo(0, YoinSprings.defaultEffects);
    bar.animateTo(0, YoinSprings.standardDefaultSpatial);
  }

  /// System back / back key / side swipe (no progress on HarmonyOS: it
  /// commits). Returns false when the overlay isn't open. One level at a
  /// time: an inner sheet, the Expanded stage, focus, Full → panel, then close.
  bool handleBack() {
    if (!_expanded) return false;
    if (handleInnerBack?.call() ?? false) return true;
    if (handleComposerBack?.call() ?? false) return true;
    if (_stage == NowPlayingStage.expanded && _shown.presentation != NowPlayingPresentation.dualPane) {
      stepBackStage();
    } else if (_stage == NowPlayingStage.immersive) {
      stepBackStage();
    } else if (_fullscreen && _live.panelAvailable) {
      _setFullscreen(false);
    } else {
      close();
    }
    return true;
  }

  /// The chevron: Expanded → Compact, Compact → closed.
  void onChevron() => handleBack();

  void expandStage() {
    if (_stage == NowPlayingStage.expanded || !_expanded) return;
    // The two columns and the hinge layout have no Expanded substate.
    if (_shown.presentation == NowPlayingPresentation.dualPane ||
        _shown.presentation == NowPlayingPresentation.tabletop) {
      return;
    }
    _stage = NowPlayingStage.expanded;
    _reconcileStage();
  }

  void stepBackStage() {
    if (_stage == NowPlayingStage.compact) return;
    _stage = NowPlayingStage.compact;
    _reconcileStage();
  }

  /// Tap the cover: focus where it is offered (Compact → Immersive), back
  /// to rest from focus.
  void toggleFocus() {
    if (!_expanded) return;
    if (_stage == NowPlayingStage.immersive) {
      stepBackStage();
    } else if (_stage == NowPlayingStage.compact && focusOffered) {
      _stage = NowPlayingStage.immersive;
      _reconcileStage();
    }
  }

  /// The body can't focus (any more): a stale Immersive drops back to rest.
  void dropFocusIfUnoffered() {
    if (_stage == NowPlayingStage.immersive && !focusOffered) stepBackStage();
  }

  /// The one settle owner of the stage: both progresses spring toward the
  /// stage's pose (memory project_np_stage_settle_owner).
  void _reconcileStage() {
    final detailTarget = _stage == NowPlayingStage.expanded ? 1.0 : 0.0;
    final focusTarget = _stage == NowPlayingStage.immersive ? 1.0 : 0.0;
    if (_reduceMotion) {
      detail.snapTo(detailTarget);
      focus.snapTo(focusTarget);
    } else {
      final spring = YoinSprings.of(MotionTokens.stageSettle);
      detail.animateTo(detailTarget, spring);
      focus.animateTo(focusTarget, spring);
    }
    notifyListeners();
  }

  // Drag-to-dismiss (disabled in Expanded: the lyric list scrolls; focus keeps it). Down on
  // the full-window player; toward the end on the side panel; none on the
  // panel's Full states (top bar and back close them).
  // The hinge layout has none either (TabletopPlayingContent): the chevron and back close it.
  bool get canDrag =>
      _expanded &&
      _stage != NowPlayingStage.expanded &&
      !_shown.panelFamily &&
      _shown.presentation != NowPlayingPresentation.tabletop;

  bool get canDragPanel =>
      _expanded && _stage != NowPlayingStage.expanded && _shown.presentation == NowPlayingPresentation.panel;

  /// Fraction of the dismiss distance the panel has been dragged.
  double get panelDismissFraction => (dragDx.value / NowPlayingPanelTokens.dismissDistance).clamp(0.0, 1.0);

  void panelDragUpdate(double delta) {
    if (!canDragPanel) return;
    dragDx.snapTo((dragDx.value + delta).clamp(0.0, double.infinity));
  }

  void panelDragEnd(double velocity) {
    if (!canDragPanel) return;
    if (dragDx.value > NowPlayingPanelTokens.dismissDistance || velocity > NowPlayingPanelTokens.dismissVelocity) {
      close(velocity: velocity < 0 ? 0 : velocity);
    } else {
      dragDx.animateTo(0, YoinSprings.standardDefaultSpatial, velocity: -velocity);
    }
  }

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
    if (_shown.presentation.pillMorph == PillMorph.none) return;
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
      if (_stage != NowPlayingStage.compact || detail.value != 0 || focus.value != 0) {
        _stage = NowPlayingStage.compact;
        detail.snapTo(0);
        focus.snapTo(0);
      }
      // Gone: the next open starts as the panel from frame one.
      _fullscreen = false;
      dragDx.snapTo(0);
      _resolveFrame(settle: false);
      _shown = _live;
      changed = true;
    }
    if (changed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    open.dispose();
    fade.dispose();
    bar.dispose();
    dragDy.dispose();
    detail.dispose();
    focus.dispose();
    flight.dispose();
    full.dispose();
    inset.dispose();
    dragDx.dispose();
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

/// The window as it is before the Now Playing side panel takes its share
/// (BesideNowPlayingPanel provides it next to the narrowed [YoinWindowScope]).
/// Decisions that belong to the window rather than to the content's width —
/// whether the detail column exists at all — read this, so opening the panel
/// never hands the column's pages over as pushed pages.
class NowPlayingHostWindowScope extends InheritedWidget {
  const NowPlayingHostWindowScope({super.key, required this.window, required super.child});

  final YoinWindowInfo window;

  /// The host window, or [YoinWindowInfo.of] where no panel host is above.
  static YoinWindowInfo of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<NowPlayingHostWindowScope>()?.window ?? YoinWindowInfo.of(context);

  @override
  bool updateShouldNotify(NowPlayingHostWindowScope oldWidget) => oldWidget.window != window;
}
