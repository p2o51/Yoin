import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart' show PointerDeviceKind, VelocityTracker;
import 'package:flutter/physics.dart';
import 'package:flutter/rendering.dart' show ScrollDirection;
import 'package:flutter/scheduler.dart' show SchedulerBinding, SchedulerPhase;
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';

/// Hides the window's bottom bar while the Home feed scrolls down and brings
/// it back on the way up (owner 2026-11-16; ShellBarScrollHide.kt).
///
/// One progress, [fraction] (0 shown, 1 hidden), and two inputs that both
/// feed it: the feed's scroll moves it 1:1 with the content (only scroll the
/// list really took, so a feed too short to scroll never hides the bar), and
/// the end of a gesture settles it on the bar's spatial spring to whichever
/// end it is nearer, or the way a fling was heading. A pull past the top
/// always reveals.
///
/// Only the plain Home feed under a bottom-bar form moves it: the edge
/// capsules stay, and edit mode, Memories, Now Playing, the detail column
/// and a page pushed over the shell all keep the bar. The shell and the
/// chrome each say whether their side allows it ([shellAllows],
/// [chromeAllows]); when either stops allowing it, the bar settles back —
/// for a page pushed over Home that is the bar rising into place with the
/// page's entrance (Android: the detail window's own bar rises,
/// DETAIL_EXTRA_BAR_RISES; here the window has one bar).
class ShellBarScrollHide {
  ShellBarScrollHide({this.distance = BarTokens.scrollHideDistance, SpringDescription? spring})
    : spring = spring ?? YoinSprings.standardDefaultSpatial;

  /// How far the feed scrolls to take the bar fully out (logical px).
  final double distance;

  /// The theme's spatial spring (Standard role, as YoinMotion.spatialSpring() at the shell).
  final SpringDescription spring;

  final ValueNotifier<double> _fraction = ValueNotifier(0);

  /// 0 = the bar shows, 1 = the feed's scroll has taken it out.
  ValueListenable<double> get fraction => _fraction;

  AnimationController? _settle;
  TickerProvider? _vsync;

  /// Reduced motion: a settle lands at once.
  bool reduced = false;

  /// Gives the settle its ticker (the chrome owns one); without it settles snap.
  void attach(TickerProvider vsync) {
    _settle?.dispose();
    _vsync = vsync;
    _settle = AnimationController.unbounded(vsync: vsync, value: _fraction.value)
      ..addListener(() {
        final c = _settle;
        if (c != null && c.isAnimating) _fraction.value = c.value.clamp(0.0, 1.0);
      });
  }

  /// Takes the settle's ticker back; [vsync] only lets go of its own (a chrome
  /// leaving after its successor attached keeps the successor's).
  void detach([TickerProvider? vsync]) {
    if (vsync != null && !identical(vsync, _vsync)) return;
    _settle?.dispose();
    _settle = null;
    _vsync = null;
  }

  bool _shellAllows = true;
  bool _chromeAllows = true;

  /// Home's feed is up as itself: Home selected, not being edited, no
  /// Memories, no detail column, no page pushed over the shell.
  set shellAllows(bool value) {
    if (_shellAllows == value) return;
    _shellAllows = value;
    _enabledChanged();
  }

  /// The chrome's side: a bottom-bar form (not the edge capsules) and no Now Playing.
  set chromeAllows(bool value) {
    if (_chromeAllows == value) return;
    _chromeAllows = value;
    _enabledChanged();
  }

  bool get enabled => _shellAllows && _chromeAllows;

  void _enabledChanged() {
    if (!enabled) {
      _resetGesture();
      _settleTo(0);
    }
  }

  /// Brings the bar back on its spring (Home shown again).
  void show() => _settleTo(0);

  /// Puts the bar back with no motion, for when nothing of the window is on
  /// screen (the app went to the background; Android ON_STOP).
  void showUnseen() {
    _settle?.stop();
    _snapGeneration++;
    _fraction.value = 0;
  }

  /// The feed moved by [hideDelta] logical px that count (positive = the
  /// content went up, toward hiding; negative = down, toward showing).
  /// Android onPostScroll.
  void scrolled(double hideDelta) {
    if (!enabled || hideDelta == 0) return;
    _settle?.stop();
    _snapGeneration++;
    _fraction.value = (_fraction.value + hideDelta / distance).clamp(0.0, 1.0);
  }

  /// A gesture let go, [velocity] in physical px/s (positive = toward
  /// hiding): settles to the end a fling was heading for, else the nearer
  /// one. Android onPostFling.
  void released(double velocity) {
    if (!enabled) return;
    final f = _fraction.value;
    if (f <= 0 || f >= 1) return;
    final threshold = BarTokens.scrollHideFlingDecisionPx;
    final target = velocity > threshold
        ? 1.0
        : velocity < -threshold
        ? 0.0
        : (f >= 0.5 ? 1.0 : 0.0);
    _settleTo(target);
  }

  void _settleTo(double target) {
    if (_fraction.value == target) {
      _settle?.stop();
      return;
    }
    final c = _settle;
    if (c == null || reduced) {
      c?.stop();
      _snap(target);
      return;
    }
    _snapGeneration++;
    // From where the bar is, from rest (Android animate() starts at velocity 0).
    c.value = _fraction.value;
    c.animateWith(
      SpringSimulation(
        spring,
        _fraction.value,
        target,
        0,
        tolerance: const Tolerance(distance: 0.0005, velocity: 0.001),
      ),
    );
  }

  int _snapGeneration = 0;

  /// Lands at [value] at once; a gate that changed mid-build lands after the
  /// frame (the bar can't be told while the tree is being built).
  void _snap(double value) {
    final generation = ++_snapGeneration;
    final binding = SchedulerBinding.instance;
    if (binding.schedulerPhase != SchedulerPhase.persistentCallbacks) {
      _fraction.value = value;
      return;
    }
    binding.addPostFrameCallback((_) {
      if (generation == _snapGeneration) _fraction.value = value;
    });
  }

  // ---- the feed's scroll notifications ----

  /// A user is moving the feed (a drag, its fling, a wheel), not the app.
  bool _userScrolling = false;

  /// The current scroll began with a finger: its end settles the bar.
  bool _dragged = false;
  VelocityTracker? _tracker;
  double? _releaseVelocity;
  final Stopwatch _clock = Stopwatch()..start();

  void _resetGesture() {
    _dragged = false;
    _tracker = null;
    _releaseVelocity = null;
  }

  /// Reads the Home feed's own (depth 0, vertical) scroll notifications.
  /// [devicePixelRatio] turns the release velocity into physical px/s.
  void onScrollNotification(ScrollNotification n, {required double devicePixelRatio}) {
    if (n.depth != 0 || n.metrics.axis != Axis.vertical) return;
    switch (n) {
      case UserScrollNotification(:final direction):
        _userScrolling = direction != ScrollDirection.idle;
      case ScrollStartNotification(:final dragDetails):
        _resetGesture();
        if (dragDetails != null) {
          _dragged = true;
          _userScrolling = true;
          _tracker = VelocityTracker.withKind(PointerDeviceKind.touch);
        }
      case ScrollUpdateNotification(:final dragDetails, :final scrollDelta):
        if (dragDetails != null) {
          _tracker?.addPosition(dragDetails.sourceTimeStamp ?? _clock.elapsed, Offset(0, n.metrics.pixels));
        } else if (_dragged && _releaseVelocity == null) {
          // The finger left and the fling took over: its speed as it let go.
          _releaseVelocity = _tracker?.getVelocity().pixelsPerSecond.dy ?? 0;
        }
        if (_userScrolling && scrollDelta != null) scrolled(scrollHideDeltaOf(n.metrics, scrollDelta));
      case OverscrollNotification(:final overscroll):
        // Only a pull past the top the list couldn't take: that always reveals.
        if (_userScrolling && overscroll < 0) scrolled(overscroll);
      case ScrollEndNotification(:final dragDetails):
        if (_dragged) {
          // Ended while still held (no fling): the finger's own speed, else the fling's.
          final v = dragDetails != null ? -(dragDetails.primaryVelocity ?? 0) : (_releaseVelocity ?? 0);
          released(v * devicePixelRatio);
        }
        _resetGesture();
      default:
        break;
    }
  }

  void dispose() {
    detach();
    _fraction.dispose();
  }
}

/// The part of a scroll by [scrollDelta] (to [metrics].pixels) that counts
/// toward hiding the bar: what moved inside the content's range, plus a pull
/// further past the top. A bounce past the bottom, or back from either edge,
/// is no scroll the list took.
double scrollHideDeltaOf(ScrollMetrics metrics, double scrollDelta) {
  final now = metrics.pixels;
  final before = now - scrollDelta;
  final lo = metrics.minScrollExtent;
  final hi = math.max(lo, metrics.maxScrollExtent);
  var delta = now.clamp(lo, hi) - before.clamp(lo, hi);
  final pastTop = math.min(0.0, now - lo) - math.min(0.0, before - lo);
  if (pastTop < 0) delta += pastTop;
  return delta;
}

/// The window's one scroll-hide: Home feeds it, the shell and the chrome
/// gate it, the bar reads it.
final shellBarScrollHideProvider = Provider<ShellBarScrollHide>((ref) {
  final hide = ShellBarScrollHide();
  ref.onDispose(hide.dispose);
  return hide;
});
