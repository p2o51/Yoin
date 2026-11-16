/// Edit Home's row resize (HomeRowsResize.kt; D1 option ②, owner 2026-10-05,
/// H5: a bottom-right handle shaped like a rounded ⌟, "像 iOS 那样"). Each
/// section that takes presets (Activities, Jump Back In) has ONE driver, its
/// content height: the handle drag follows the finger 1:1 (rubber-band past
/// the smallest and largest stop), a release springs it to a stop with the
/// release velocity, and the keyboard, the reader, Undo and Reset spring it
/// from wherever it is. While it is between two stops the section builds
/// both and every card interpolates between its two places ([HomeRowsBody] /
/// [HomeRowsCard]): cards both stops show move; cards only the larger one
/// shows fade and scale in, staggered top to bottom; a card that changes kind
/// (a strip growing into a wide card) fades through. The stop's preset is
/// written (with Undo) the moment the finger lifts. Every per-frame value is
/// read in layout or paint.
library;

import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/physics.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import 'home_metrics.dart';
import 'home_row_presets.dart';
import 'home_section.dart';

// ---------------------------------------------------------------------------
// Pure geometry.

/// Composed stops of a section: [a] ≤ [b]; [a] == [b] is one stop.
@immutable
final class HomeRowsPair {
  const HomeRowsPair(this.a, this.b);
  final int a;
  final int b;

  @override
  bool operator ==(Object other) => other is HomeRowsPair && other.a == a && other.b == b;

  @override
  int get hashCode => Object.hash(a, b);

  @override
  String toString() => 'HomeRowsPair($a, $b)';
}

/// The stop pair that brackets content height [h] given the measured stop
/// [heights] (index → px) of a [count]-stop ladder: the highest measured stop
/// at or under [h] and the one above it — built even before it is measured,
/// so its height arrives the next frame. Past the largest measured stop it is
/// that stop alone (the rubber band); under the smallest, the one below it if
/// any. [fallback] when nothing is measured.
HomeRowsPair homeRowsPair(double h, int count, Map<int, double> heights, {required int fallback}) {
  if (count <= 0) return const HomeRowsPair(0, 0);
  final top = heights[count - 1];
  if (top != null && h >= top) return HomeRowsPair(count - 1, count - 1);
  for (var i = count - 2; i >= 0; i--) {
    final hi = heights[i];
    if (hi == null) continue;
    if (h >= hi) return HomeRowsPair(i, i + 1);
  }
  var lowest = -1;
  for (var i = 0; i < count; i++) {
    if (heights[i] != null) {
      lowest = i;
      break;
    }
  }
  if (lowest < 0) return HomeRowsPair(fallback, fallback);
  return lowest > 0 ? HomeRowsPair(lowest - 1, lowest) : const HomeRowsPair(0, 0);
}

/// The stop [h] has reached from [current], measured [heights] only: it moves
/// a stop on once [h] is [hysteresis] past the midpoint to it.
int homeRowsNearest(double h, int current, int count, Map<int, double> heights, double hysteresis) {
  var n = current.clamp(0, math.max(0, count - 1)).toInt();
  while (n < count - 1) {
    final here = heights[n];
    final next = heights[n + 1];
    if (here == null || next == null) break;
    if (h > (here + next) / 2 + hysteresis) {
      n++;
    } else {
      break;
    }
  }
  while (n > 0) {
    final here = heights[n];
    final below = heights[n - 1];
    if (here == null || below == null) break;
    if (h < (below + here) / 2 - hysteresis) {
      n--;
    } else {
      break;
    }
  }
  return n;
}

/// Where a release lands: [nearest], or one stop further in the direction of
/// [velocity] (px/s, + = taller) when it is at least [flingVelocity] and its
/// [projectSeconds] projection from [h] crosses the midpoint to that stop.
int homeRowsReleaseStop(
  double h,
  int nearest,
  int count,
  Map<int, double> heights,
  double velocity, {
  double flingVelocity = HomeRowsMetrics.flingVelocity,
  double projectSeconds = HomeRowsMetrics.projectSeconds,
}) {
  if (velocity.abs() < flingVelocity) return nearest;
  final dir = velocity > 0 ? 1 : -1;
  final next = (nearest + dir).clamp(0, count - 1).toInt();
  if (next == nearest) return nearest;
  final here = heights[nearest];
  final there = heights[next];
  if (here == null || there == null) return nearest;
  final midpoint = (here + there) / 2;
  final projected = h + velocity * projectSeconds;
  return (dir > 0 ? projected > midpoint : projected < midpoint) ? next : nearest;
}

/// The rubber band past the smallest or largest stop (HomeEditGeometry.kt rubber).
double homeRowsRubber(double overscroll) {
  const range = HomeRowsMetrics.rubberRange;
  return range * (1 - 1 / (overscroll * HomeRowsMetrics.rubberK / range + 1));
}

/// The auto-scroll's ramp over the edge zone: 0 at its inner edge, 1 at the screen's, eased in.
double homeRowsEdgeRamp(double depth) {
  final x = depth.clamp(0.0, 1.0);
  return x * x;
}

double _smoothstep(double edge0, double edge1, double x) {
  if (edge1 <= edge0) return x >= edge1 ? 1 : 0;
  final t = ((x - edge0) / (edge1 - edge0)).clamp(0.0, 1.0);
  return t * t * (3 - 2 * t);
}

double _lerp(double a, double b, double t) => a + (b - a) * t;

/// The finger's velocity over its last moments (HomeEditVelocity), px/s.
final class HomeRowsVelocity {
  final List<int> _times = [];
  final List<double> _positions = [];

  void add(int timeMs, double y) {
    _times.add(timeMs);
    _positions.add(y);
    while (_times.length > 2 && timeMs - _times.first > HomeRowsMetrics.velocityWindowMs) {
      _times.removeAt(0);
      _positions.removeAt(0);
    }
  }

  double velocity(int nowMs) {
    if (_times.length < 2) return 0;
    final last = _times.length - 1;
    if (nowMs - _times[last] > HomeRowsMetrics.velocityStaleMs) return 0;
    var first = 0;
    while (first < _times.length - 2 && _times[last] - _times[first] > HomeRowsMetrics.velocityWindowMs) {
      first++;
    }
    final elapsed = _times[last] - _times[first];
    if (elapsed <= HomeRowsMetrics.velocityMinDtMs) return 0;
    final v = (_positions[last] - _positions[first]) / (elapsed / 1000);
    return v.clamp(-HomeRowsMetrics.maxVelocity, HomeRowsMetrics.maxVelocity);
  }
}

// ---------------------------------------------------------------------------
// One section's track.

/// A card's rect in its layer and what it renders as there.
final class _CardRect {
  const _CardRect(this.rect, this.kind);
  final Rect rect;
  final Object kind;
}

/// What a card does this frame: its offset toward its place in the other
/// stop, its alpha and its scale. Identity at rest.
@immutable
final class HomeRowsCardLook {
  const HomeRowsCardLook({this.dx = 0, this.dy = 0, this.alpha = 1, this.scale = 1});
  final double dx;
  final double dy;
  final double alpha;
  final double scale;

  static const identity = HomeRowsCardLook();

  bool get isIdentity => dx == 0 && dy == 0 && alpha == 1 && scale == 1;

  @override
  String toString() => 'HomeRowsCardLook($dx, $dy, α$alpha, ×$scale)';
}

class _Notifier extends ChangeNotifier {
  void notify() => notifyListeners();
}

/// One section's resize: its driver ([height], valid while [active]), the
/// ladder its body reported, each stop's measured height and card rects, and
/// the handle's visuals. Written by [HomeRowsEngine] (gestures, settles) and
/// by the body (ladder, heights, rects).
class HomeRowsTrack {
  HomeRowsTrack(this.section, TickerProvider vsync)
    : height = AnimationController.unbounded(vsync: vsync),
      hot = AnimationController.unbounded(vsync: vsync),
      pulse = AnimationController.unbounded(vsync: vsync, value: 1) {
    height.addListener(_updatePair);
  }

  final HomeSection section;

  /// Content height while [active]: the one driver.
  final AnimationController height;

  /// The handle's held look (0 rest, 1 held). Paint only.
  final AnimationController hot;

  /// The handle's scale pulse on each stop crossing: 1 at rest. Paint only.
  final AnimationController pulse;

  final _Notifier _structure = _Notifier();

  /// Fires when [active], [dragging], [target], [pair] or the ladder change.
  Listenable get structure => _structure;

  /// Between stops, or springing to one: the body builds the pair and follows [height].
  bool active = false;

  /// The handle is held.
  bool dragging = false;

  /// The stop a settle is heading for, built (hidden) until measured.
  int? target;

  List<List<HomeRowPreset>> _stopPresets = const [];
  List<int> _rowCounts = const [];
  int _restStop = 0;
  Object? _ladderKey;
  double _heightsWidth = -1;

  /// The presets of each stop.
  List<List<HomeRowPreset>> get stopPresets => _stopPresets;

  /// Rows each stop shows (for the reader's label).
  List<int> get rowCounts => _rowCounts;

  /// The stop of the section's current preset.
  int get restStop => _restStop;

  int get stopCount => _stopPresets.length;

  /// Measured height of each stop, at [_heightsWidth].
  final Map<int, double> heights = {};

  final Map<int, Map<Object, _CardRect>> _rects = {};
  int _rectVersion = 0;

  HomeRowsPair _pair = const HomeRowsPair(0, 0);

  /// The two stops shown now (the rest stop alone when not [active]).
  HomeRowsPair get pair => active ? _pair : HomeRowsPair(_restStop, _restStop);

  // Gesture bookkeeping (the engine's).
  int _settleToken = 0;
  int _nearest = 0;
  ({int token, double velocity})? _pendingSettle;
  bool _afterLayoutScheduled = false;
  SpringDescription _settleSpring = YoinSprings.defaultSpatial;

  void _notify() => _structure.notify();

  void _updatePair() {
    if (!active) return;
    final next = homeRowsPair(height.value, stopCount, heights, fallback: _restStop);
    if (next == _pair) return;
    _pair = next;
    _notify();
  }

  /// The body's ladder. A new [key] (other cards, another screen) forgets
  /// every measured height and rect; [reset] then puts the track at rest.
  void setLadder(Object key, List<List<HomeRowPreset>> stops, List<int> rows, int rest, {required VoidCallback reset}) {
    var changed = false;
    if (key != _ladderKey) {
      _ladderKey = key;
      heights.clear();
      _rects.clear();
      _rectVersion++;
      if (active) reset();
      changed = true;
    }
    if (!_deepEquals(_stopPresets, stops)) {
      _stopPresets = stops;
      changed = true;
    }
    if (!listEquals(_rowCounts, rows)) {
      _rowCounts = rows;
      changed = true;
    }
    if (_restStop != rest) {
      _restStop = rest;
      changed = true;
    }
    if (changed) _notify();
  }

  /// The stop [preset] resolves to here.
  int stopOf(HomeRowPreset preset) => math.max(0, _stopPresets.indexWhere((p) => p.contains(preset)));

  /// The body measured [stop] at [px] tall, [width] wide (in layout: no notifications).
  void recordHeight(int stop, double width, double px) {
    if (width != _heightsWidth) {
      _heightsWidth = width;
      heights.clear();
    }
    heights[stop] = px;
  }

  void recordRect(int stop, Object key, Object kind, Rect rect) {
    final map = _rects.putIfAbsent(stop, () => {});
    final old = map[key];
    if (old != null && old.rect == rect && old.kind == kind) return;
    map[key] = _CardRect(rect, kind);
    _rectVersion++;
  }

  void forgetRect(int stop, Object key) {
    if (_rects[stop]?.remove(key) != null) _rectVersion++;
  }

  /// After the body's layout: the pair and a waiting settle catch up after the frame.
  void afterLayout() {
    if (_afterLayoutScheduled) return;
    if (!active && _pendingSettle == null) return;
    _afterLayoutScheduled = true;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      _afterLayoutScheduled = false;
      _updatePair();
      final pending = _pendingSettle;
      final to = target == null ? null : heights[target!];
      if (pending != null && pending.token == _settleToken && to != null) {
        _pendingSettle = null;
        _runSettle(to, pending.velocity, pending.token);
      }
    });
  }

  void _runSettle(double to, double velocity, int token) {
    final simulation = SpringSimulation(
      _settleSpring,
      height.value,
      to,
      velocity,
      tolerance: const Tolerance(distance: HomeRowsMetrics.settleThreshold, velocity: 1),
    );
    height.animateWith(simulation).whenCompleteOrCancel(() {
      if (_settleToken == token && !dragging) {
        if (height.value != to && !height.isAnimating) height.value = to;
        active = false;
        target = null;
        _notify();
      }
    });
  }

  // ── Card transforms ────────────────────────────────────────────────────

  (int, int, int)? _rankKey;
  Map<Object, double> _rankCache = const {};

  double _progress(HomeRowsPair pair) {
    if (pair.a == pair.b) return 0;
    final from = heights[pair.a];
    final to = heights[pair.b];
    if (from == null || to == null) return 0;
    if (to - from < 1) return height.value >= to ? 1 : 0;
    return ((height.value - from) / (to - from)).clamp(0.0, 1.0);
  }

  /// Where an incoming card's fade starts: staggered top to bottom, then left to right.
  double _incomingStart(HomeRowsPair pair, Object key) {
    final cacheKey = (pair.a, pair.b, _rectVersion);
    if (_rankKey != cacheKey) {
      final lower = _rects[pair.a] ?? const {};
      final incoming =
          [
            for (final e in (_rects[pair.b] ?? const <Object, _CardRect>{}).entries)
              if (!lower.containsKey(e.key)) e,
          ]..sort((x, y) {
            final byTop = x.value.rect.top.compareTo(y.value.rect.top);
            return byTop != 0 ? byTop : x.value.rect.left.compareTo(y.value.rect.left);
          });
      final n = incoming.length;
      _rankCache = {
        for (var k = 0; k < n; k++)
          incoming[k].key: n > 1
              ? HomeRowsMetrics.incomingFirst + HomeRowsMetrics.incomingSpread * k / (n - 1)
              : HomeRowsMetrics.incomingLone,
      };
      _rankKey = cacheKey;
    }
    return _rankCache[key] ?? HomeRowsMetrics.incomingLone;
  }

  /// Card [key] of stop [stop]'s layer this frame (HomeRowsTrack.applyCard).
  HomeRowsCardLook cardLook(int stop, Object key, {required bool reduced}) {
    if (!active) return HomeRowsCardLook.identity;
    final pair = this.pair;
    if (stop != pair.a && stop != pair.b) {
      // The settle target, built only to be measured.
      return const HomeRowsCardLook(alpha: 0);
    }
    if (pair.a == pair.b) return HomeRowsCardLook.identity;
    final p = _progress(pair);
    final lower = _rects[pair.a]?[key];
    final upper = _rects[pair.b]?[key];
    final sameLook =
        lower != null &&
        upper != null &&
        lower.kind == upper.kind &&
        (lower.rect.width - upper.rect.width).abs() < 1 &&
        (lower.rect.height - upper.rect.height).abs() < 1;
    if (stop == pair.b) {
      if (lower != null && upper != null) {
        return HomeRowsCardLook(
          dx: (lower.rect.left - upper.rect.left) * (1 - p),
          dy: (lower.rect.top - upper.rect.top) * (1 - p),
          alpha: sameLook ? 1 : _smoothstep(HomeRowsMetrics.kindInStart, HomeRowsMetrics.kindInEnd, p),
        );
      }
      // New in the larger stop (or not placed yet: shown once it is).
      final start = _incomingStart(pair, key);
      final q = _smoothstep(start, math.min(0.98, start + HomeRowsMetrics.incomingSpan), p);
      return HomeRowsCardLook(alpha: q, scale: reduced ? 1 : _lerp(HomeRowsMetrics.incomingScale, 1, q));
    }
    if (upper == null) {
      // Only in the smaller stop (presets nest, so a card not placed yet).
      return HomeRowsCardLook(alpha: lower == null ? 0 : 1 - p);
    }
    if (sameLook) return const HomeRowsCardLook(alpha: 0);
    return HomeRowsCardLook(
      dx: (upper.rect.left - lower!.rect.left) * p,
      dy: (upper.rect.top - lower.rect.top) * p,
      alpha: 1 - _smoothstep(HomeRowsMetrics.kindOutStart, HomeRowsMetrics.kindOutEnd, p),
    );
  }

  void dispose() {
    height.dispose();
    hot.dispose();
    pulse.dispose();
    _structure.dispose();
  }
}

bool _deepEquals(List<List<HomeRowPreset>> a, List<List<HomeRowPreset>> b) {
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (!listEquals(a[i], b[i])) return false;
  }
  return true;
}

// ---------------------------------------------------------------------------
// The engine.

/// Scrolls the feed under a held handle near the screen's edges (global px).
abstract interface class HomeRowsAutoScroll {
  /// Scroll speed (px/s, + = content up) for a finger at [fingerGlobalY], before the ladder's limits.
  double speed(double fingerGlobalY);

  /// Scrolls by [px]; returns what the list consumed.
  double scrollBy(double px);
}

/// What the resize says through the hands (HomeEditFeedback).
abstract interface class HomeRowsFeedback {
  /// A stop crossed.
  void segmentTick();

  /// Dragged past the smallest or largest stop.
  void threshold();

  /// A changed preset written.
  void confirm();
}

/// [YoinHaptics] behind the resize.
final class YoinHomeRowsFeedback implements HomeRowsFeedback {
  const YoinHomeRowsFeedback();

  @override
  void segmentTick() => YoinHaptics.tick();

  @override
  void threshold() => YoinHaptics.contextClick();

  @override
  void confirm() => YoinHaptics.confirm();
}

class _Drag {
  _Drag({required this.track, required this.startStop, required this.h0, required this.y0, required this.velocity})
    : lastY = y0,
      raw = h0;
  final HomeRowsTrack track;
  final int startStop;
  final double h0;
  final double y0;
  double lastY;
  double scrolled = 0;
  double raw;
  bool overMax = false;
  bool overMin = false;
  final HomeRowsVelocity velocity;
}

/// Every section's [HomeRowsTrack] and the gestures and settles that drive
/// them. One per Home; [commit] writes a preset through the edit controller
/// (persisted, with Undo) and answers whether it changed anything.
class HomeRowsEngine {
  HomeRowsEngine({
    required TickerProvider vsync,
    required this.editing,
    required this.carrying,
    required this.currentPreset,
    required this.commit,
    required this.reduced,
    this.feedback = const YoinHomeRowsFeedback(),
  }) : _tracks = {
         for (final s in HomeSection.values)
           if (s.supportsRows) s: HomeRowsTrack(s, vsync),
       } {
    _autoScrollTicker = vsync.createTicker(_onAutoScrollTick);
  }

  final bool Function() editing;

  /// A section drag is in progress: the handle doesn't grab.
  final bool Function() carrying;
  final HomeRowPreset Function(HomeSection section) currentPreset;
  final bool Function(HomeSection section, HomeRowPreset preset) commit;
  final bool Function() reduced;
  final HomeRowsFeedback feedback;

  final Map<HomeSection, HomeRowsTrack> _tracks;
  late final Ticker _autoScrollTicker;
  Duration? _autoScrollLast;

  /// The feed under a held handle; Home sets it.
  HomeRowsAutoScroll? autoScroll;

  final Map<HomeSection, bool Function(Offset globalPosition)> _handles = {};

  /// [section]'s handle is on screen; [hit] says whether a global position is on it.
  void attachHandle(HomeSection section, bool Function(Offset globalPosition) hit) => _handles[section] = hit;

  void detachHandle(HomeSection section, bool Function(Offset globalPosition) hit) {
    // Tear-offs of one method are equal, not necessarily identical.
    if (_handles[section] == hit) _handles.remove(section);
  }

  /// A finger landed at global [position] on the feed: grabs the handle it is
  /// on ([grab]), if any. The handle's touch box reaches past its section's
  /// box (its bottom edge is the plate's), where only the feed sees the finger.
  bool grabAt(Offset position, int timeMs) {
    if (_drag != null) return false;
    for (final entry in _handles.entries) {
      if (entry.value(position)) return grab(entry.key, position.dy, timeMs);
    }
    return false;
  }

  HomeRowsTrack? track(HomeSection section) => _tracks[section];

  /// Some block is between stops.
  bool get resizing => _tracks.values.any((t) => t.active);

  /// Some handle is held (the section drag stays out).
  bool get dragging => _drag != null;

  _Drag? _drag;

  SpringDescription get _settleSpring => reduced() ? YoinSprings.fastEffects : YoinSprings.defaultSpatial;

  // ── The handle ─────────────────────────────────────────────────────────

  /// The finger went down on [section]'s handle at [fingerGlobalY]. Starts
  /// the resize unless another handle or a section drag has the page, or there
  /// is only one stop. A settle in flight is caught where it is.
  bool grab(HomeSection section, double fingerGlobalY, int timeMs) {
    final track = _tracks[section];
    if (track == null || !editing() || carrying() || _drag != null || track.stopCount < 2) return false;
    final double h0;
    if (track.active) {
      h0 = track.height.value;
    } else {
      final rest = track.heights[track.restStop];
      if (rest == null) return false;
      h0 = rest;
    }
    track._settleToken++;
    track._pendingSettle = null;
    track.height.value = h0;
    track.target = null;
    track.active = true;
    track.dragging = true;
    track._nearest = homeRowsNearest(h0, track.restStop, track.stopCount, track.heights, 0);
    track._pair = homeRowsPair(h0, track.stopCount, track.heights, fallback: track.restStop);
    _drag = _Drag(
      track: track,
      startStop: track.restStop,
      h0: h0,
      y0: fingerGlobalY,
      velocity: HomeRowsVelocity()..add(timeMs, h0),
    );
    _spring(track.hot, 1, YoinSprings.fastEffects);
    track._notify();
    return true;
  }

  /// The held finger is at [fingerGlobalY].
  void dragTo(double fingerGlobalY, int timeMs) {
    final d = _drag;
    if (d == null) return;
    d.lastY = fingerGlobalY;
    _update(d, timeMs);
    // Frames only while the finger sits in an edge zone with room to go.
    if (!_autoScrollTicker.isActive && _autoScrollSpeed(d) != 0) {
      _autoScrollLast = null;
      _autoScrollTicker.start();
    }
  }

  /// The feed's scroll speed for [d] now (px/s): 0 outside the edge zones or with no room left to resize.
  double _autoScrollSpeed(_Drag d) {
    final scroller = autoScroll;
    if (scroller == null) return 0;
    final track = d.track;
    final speed = scroller.speed(d.lastY);
    final hi = track.heights[track.stopCount - 1];
    final lo = track.heights[0];
    final h = track.height.value;
    if (speed > 0 && (hi == null || h >= hi)) return 0;
    if (speed < 0 && (lo == null || h <= lo)) return 0;
    return speed;
  }

  void _onAutoScrollTick(Duration elapsed) {
    final d = _drag;
    final scroller = autoScroll;
    final last = _autoScrollLast;
    _autoScrollLast = elapsed;
    final speed = d == null ? 0.0 : _autoScrollSpeed(d);
    if (d == null || scroller == null || speed == 0) {
      _autoScrollTicker.stop();
      return;
    }
    if (last == null) return;
    final dt = math.min((elapsed - last).inMicroseconds / Duration.microsecondsPerSecond, 1 / 24);
    final consumed = scroller.scrollBy(speed * dt);
    if (consumed == 0) {
      // The list can't move further: stop until the finger moves again.
      _autoScrollTicker.stop();
      return;
    }
    d.scrolled += consumed;
    _update(d, null);
  }

  void _update(_Drag d, int? timeMs) {
    final track = d.track;
    final count = track.stopCount;
    final raw = d.h0 + (d.lastY - d.y0) + d.scrolled;
    d.raw = raw;
    final lo = track.heights[0];
    final hi = track.heights[count - 1];
    double shown;
    if (hi != null && raw > hi) {
      if (!d.overMax) {
        d.overMax = true;
        feedback.threshold();
      }
      shown = hi + homeRowsRubber(raw - hi);
    } else if (lo != null && raw < lo) {
      if (!d.overMin) {
        d.overMin = true;
        feedback.threshold();
      }
      shown = lo - homeRowsRubber(lo - raw);
    } else {
      shown = raw;
    }
    track.height.value = math.max(0.0, shown);
    if (timeMs != null) d.velocity.add(timeMs, raw);
    final next = homeRowsNearest(shown, track._nearest, count, track.heights, HomeRowsMetrics.hysteresis);
    if (next != track._nearest) {
      track._nearest = next;
      feedback.segmentTick();
      _pulse(track);
    }
  }

  /// The finger lifted ([cancelled]: taken by the system). Lands on a stop and writes its preset.
  void release({bool cancelled = false, int? timeMs}) {
    final d = _drag;
    if (d == null) return;
    _drag = null;
    _autoScrollTicker.stop();
    final track = d.track;
    final count = track.stopCount;
    final shown = track.height.value;
    final velocity = cancelled || timeMs == null ? 0.0 : d.velocity.velocity(timeMs);
    final stop = cancelled ? d.startStop : homeRowsReleaseStop(shown, track._nearest, count, track.heights, velocity);
    if (stop != track._nearest) {
      track._nearest = stop;
      feedback.segmentTick();
      _pulse(track);
    }
    final lo = track.heights[0] ?? shown;
    final hi = track.heights[count - 1] ?? shown;
    final outside = shown > hi || shown < lo;
    track.dragging = false;
    _spring(track.hot, 0, YoinSprings.defaultEffects);
    _settle(track, stop, outside ? velocity * HomeRowsMetrics.overVelocityShare : velocity);
    if (stop != d.startStop && stop < track.stopPresets.length) {
      final preset = homeRowPresetFrom(track.stopPresets[stop], currentPreset(track.section));
      if (commit(track.section, preset)) feedback.confirm();
    }
  }

  // ── Keyboard, the reader ───────────────────────────────────────────────

  /// True when [section] has a stop [delta] away from its current one (+1 more rows, −1 fewer).
  bool canStep(HomeSection section, int delta) {
    final track = _tracks[section];
    if (track == null) return false;
    final stop = track.restStop + delta;
    return stop >= 0 && stop < track.stopCount;
  }

  /// One stop more (+1) or fewer (−1) rows: writes the preset; the settle follows it ([onLayoutChange]).
  bool step(HomeSection section, int delta) {
    final track = _tracks[section];
    if (track == null || !editing() || _drag != null || carrying()) return false;
    final stop = track.restStop + delta;
    if (stop < 0 || stop >= track.stopCount) return false;
    final preset = homeRowPresetFrom(track.stopPresets[stop], currentPreset(section));
    if (!commit(section, preset)) return false;
    feedback.segmentTick();
    _pulse(track);
    return true;
  }

  // ── Layout changes (drop, step, Undo, Reset) ───────────────────────────

  /// An applied edit. A section whose preset changed springs from where it
  /// shows now to its new stop — unless its release is already heading there.
  void onLayoutChange(HomeLayout previous, HomeLayout next) {
    if (!editing()) return;
    for (final entry in _tracks.entries) {
      final section = entry.key;
      final track = entry.value;
      final from = previous.rowsOf(section);
      final to = next.rowsOf(section);
      if (from == to || track.stopCount == 0) continue;
      final stop = track.stopOf(to);
      if (track.active && track.target == stop) continue;
      if (!track.active && stop == track.restStop) continue;
      if (track.dragging) continue;
      final double start;
      if (track.active) {
        start = track.height.value;
      } else {
        final rest = track.heights[track.restStop];
        if (rest == null) continue;
        start = rest;
      }
      track.height.value = start;
      track.active = true;
      track._pair = homeRowsPair(start, track.stopCount, track.heights, fallback: track.restStop);
      _settle(track, stop, 0);
    }
  }

  void _settle(HomeRowsTrack track, int stop, double velocity) {
    final token = ++track._settleToken;
    track.target = stop;
    track._settleSpring = _settleSpring;
    track._notify();
    final to = track.heights[stop];
    if (to == null) {
      // A stop never shown yet is built (hidden) and measured first.
      track._pendingSettle = (token: token, velocity: velocity);
      track.afterLayout();
      return;
    }
    track._pendingSettle = null;
    track._runSettle(to, velocity, token);
  }

  void _pulse(HomeRowsTrack track) {
    if (reduced()) return;
    track.pulse.value = 1;
    track.pulse.animateWith(
      SpringSimulation(
        YoinSprings.fastSpatial,
        1,
        1,
        HomeRowsMetrics.pulseVelocity,
        tolerance: const Tolerance(distance: 0.002, velocity: 0.01),
      ),
    );
  }

  void _spring(AnimationController c, double to, SpringDescription spring) {
    if (reduced()) {
      c.value = to;
      return;
    }
    c.animateWith(
      SpringSimulation(spring, c.value, to, c.velocity, tolerance: const Tolerance(distance: 0.002, velocity: 0.01)),
    );
  }

  /// Animates the hover look of [section]'s handle toward [hot] (unless held).
  void hover(HomeSection section, double hot) {
    final track = _tracks[section];
    if (track == null || track.dragging) return;
    _spring(track.hot, hot, YoinSprings.fastEffects);
  }

  // ── Session ────────────────────────────────────────────────────────────

  /// Edit mode is ending: a held handle lets go where it is.
  void onExit() {
    if (_drag != null) release(cancelled: true);
  }

  /// Everything at rest now (a section drag starting, an account switch).
  void snapAll() {
    _autoScrollTicker.stop();
    _drag = null;
    for (final track in _tracks.values) {
      _snapTrack(track);
    }
  }

  void _snapTrack(HomeRowsTrack track) {
    track._settleToken++;
    track._pendingSettle = null;
    track.height.stop();
    final wasActive = track.active || track.dragging || track.target != null;
    track.dragging = false;
    track.active = false;
    track.target = null;
    track.hot.value = 0;
    if (wasActive) track._notify();
  }

  /// A track's ladder changed under it: back to rest.
  void resetTrack(HomeRowsTrack track) {
    if (_drag?.track == track) {
      _autoScrollTicker.stop();
      _drag = null;
    }
    _snapTrack(track);
  }

  void dispose() {
    _autoScrollTicker.dispose();
    for (final track in _tracks.values) {
      track.dispose();
    }
  }
}

// ---------------------------------------------------------------------------
// The body: one layer per built stop.

/// Home's resize engine, for the sections below it.
class HomeRowsScope extends InheritedWidget {
  const HomeRowsScope({super.key, required this.engine, required super.child});

  final HomeRowsEngine engine;

  static HomeRowsEngine? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<HomeRowsScope>()?.engine;

  @override
  bool updateShouldNotify(HomeRowsScope oldWidget) => oldWidget.engine != engine;
}

/// The layer a card is built in: its stop and the cards registered there.
class HomeRowsLayer {
  HomeRowsLayer(this.track, this.stop);
  final HomeRowsTrack track;
  final int stop;

  /// The layer's own box (the body's child), set in the body's layout:
  /// card rects are measured from it.
  RenderBox? root;
}

class _LayerScope extends InheritedWidget {
  const _LayerScope({required this.layer, required this.reduced, required super.child});
  final HomeRowsLayer layer;
  final bool reduced;

  @override
  bool updateShouldNotify(_LayerScope oldWidget) => oldWidget.layer != layer || oldWidget.reduced != reduced;
}

/// A section's cards at its row preset. With a [track] (Home, in or out of
/// edit mode) the stop of [preset] renders in a layer of its own; while the
/// track is [HomeRowsTrack.active] the stops around its height render
/// together, their cards interpolating (wrap every card in [HomeRowsCard]),
/// and the body is as tall as the driver, clipped just below the plate.
/// Without a track (previews, tests) it is just [builder] at [preset]'s stop.
///
/// [ladder] is the section's ladder on this screen (home_row_presets.dart);
/// [ladderKey] its value-equal identity — the key of everything measured.
class HomeRowsBody<T> extends StatefulWidget {
  const HomeRowsBody({
    super.key,
    required this.track,
    required this.ladder,
    required this.ladderKey,
    required this.preset,
    required this.builder,
  });

  final HomeRowsTrack? track;
  final List<HomeRowStop<T>> ladder;
  final Object ladderKey;
  final HomeRowPreset preset;
  final Widget Function(BuildContext context, HomeRowStop<T> stop) builder;

  @override
  State<HomeRowsBody<T>> createState() => _HomeRowsBodyState<T>();
}

class _HomeRowsBodyState<T> extends State<HomeRowsBody<T>> {
  final Map<int, HomeRowsLayer> _layers = {};

  @override
  void initState() {
    super.initState();
    widget.track?.structure.addListener(_changed);
    _syncLadder();
  }

  @override
  void didUpdateWidget(HomeRowsBody<T> oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.track != widget.track) {
      oldWidget.track?.structure.removeListener(_changed);
      widget.track?.structure.addListener(_changed);
      _layers.clear();
    }
    _syncLadder();
  }

  @override
  void dispose() {
    widget.track?.structure.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  int get _rest => homeRowStopIndexOf(widget.ladder, widget.preset);

  /// The track learns the ladder after the frame (it notifies its listeners).
  void _syncLadder() {
    final track = widget.track;
    if (track == null) return;
    final key = widget.ladderKey;
    final stops = [for (final s in widget.ladder) s.presets];
    final rows = [for (final s in widget.ladder) s.rowCount];
    final rest = _rest;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      if (!mounted || widget.track != track) return;
      final engine = HomeRowsScope.maybeOf(context);
      track.setLadder(key, stops, rows, rest, reset: () => engine?.resetTrack(track));
    });
  }

  HomeRowsLayer _layerFor(HomeRowsTrack track, int stop) => _layers.putIfAbsent(stop, () => HomeRowsLayer(track, stop));

  @override
  Widget build(BuildContext context) {
    final ladder = widget.ladder;
    final rest = _rest;
    final track = widget.track;
    if (ladder.isEmpty) return const SizedBox.shrink();
    if (track == null) return widget.builder(context, ladder[rest]);
    final live = track.active && track.stopCount == ladder.length;
    final List<int> composed;
    if (!live) {
      composed = [rest];
    } else {
      final pair = track.pair;
      composed = ({pair.a, pair.b, ?track.target}.where((i) => i >= 0 && i < ladder.length).toList()..sort());
      if (composed.isEmpty) composed.add(rest);
    }
    final reduced = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    return _RowsBody(
      track: track,
      live: live,
      stops: composed,
      layers: [for (final i in composed) _layerFor(track, i)],
      children: [
        for (final i in composed)
          KeyedSubtree(
            key: ValueKey(i),
            child: _LayerScope(layer: _layerFor(track, i), reduced: reduced, child: widget.builder(context, ladder[i])),
          ),
      ],
    );
  }
}

class _RowsBody extends MultiChildRenderObjectWidget {
  const _RowsBody({
    required this.track,
    required this.live,
    required this.stops,
    required this.layers,
    required super.children,
  });

  final HomeRowsTrack track;
  final bool live;
  final List<int> stops;
  final List<HomeRowsLayer> layers;

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderRowsBody(track, live, stops, layers);

  @override
  void updateRenderObject(BuildContext context, _RenderRowsBody renderObject) {
    renderObject
      ..track = track
      ..live = live
      ..stops = stops
      ..layers = layers;
  }
}

class _RowsBodyParentData extends ContainerBoxParentData<RenderBox> {}

class _RenderRowsBody extends RenderBox
    with
        ContainerRenderObjectMixin<RenderBox, _RowsBodyParentData>,
        RenderBoxContainerDefaultsMixin<RenderBox, _RowsBodyParentData> {
  _RenderRowsBody(this._track, this._live, this._stops, this._layers);

  HomeRowsTrack _track;
  set track(HomeRowsTrack value) {
    if (identical(value, _track)) return;
    if (attached) _track.height.removeListener(markNeedsLayout);
    _track = value;
    if (attached) _track.height.addListener(markNeedsLayout);
    markNeedsLayout();
  }

  bool _live;
  set live(bool value) {
    if (value == _live) return;
    _live = value;
    markNeedsLayout();
  }

  List<int> _stops;
  set stops(List<int> value) {
    if (listEquals(value, _stops)) return;
    _stops = value;
    markNeedsLayout();
  }

  List<HomeRowsLayer> _layers;
  set layers(List<HomeRowsLayer> value) {
    _layers = value;
    markNeedsLayout();
  }

  final LayerHandle<ClipRectLayer> _clip = LayerHandle<ClipRectLayer>();

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _RowsBodyParentData) child.parentData = _RowsBodyParentData();
  }

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _track.height.addListener(markNeedsLayout);
  }

  @override
  void detach() {
    _track.height.removeListener(markNeedsLayout);
    super.detach();
  }

  @override
  void dispose() {
    _clip.layer = null;
    super.dispose();
  }

  @override
  void performLayout() {
    final width = constraints.hasBoundedWidth ? constraints.maxWidth : constraints.minWidth;
    final inner = BoxConstraints(minWidth: width, maxWidth: width);
    var child = firstChild;
    var i = 0;
    double? first;
    while (child != null) {
      child.layout(inner, parentUsesSize: true);
      (child.parentData! as _RowsBodyParentData).offset = Offset.zero;
      first ??= child.size.height;
      if (i < _stops.length) _track.recordHeight(_stops[i], width, child.size.height);
      if (i < _layers.length) _layers[i].root = child;
      child = childAfter(child);
      i++;
    }
    final height = _live && _track.active ? math.max(0.0, _track.height.value) : (first ?? 0.0);
    size = constraints.constrain(Size(width, height));
    _track.afterLayout();
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    if (!_live || !_track.active) {
      _clip.layer = null;
      defaultPaint(context, offset);
      return;
    }
    // Clipped just below the plate; free to the sides and above (a lifted card's scale).
    final big = size.width;
    final clip = Rect.fromLTRB(-big, -size.height, size.width + big, size.height + HomeEditMetrics.plateOutsetV);
    _clip.layer = context.pushClipRect(needsCompositing, offset, clip, defaultPaint, oldLayer: _clip.layer);
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);
}

/// Card [cardKey] of a row-preset section, rendering as [kind] (a card that is
/// another kind in the neighbouring stop fades through). Records where the
/// card sits in its stop and, while the section resizes, moves, fades and
/// scales it between stops. A plain [child] outside a [HomeRowsBody].
class HomeRowsCard extends SingleChildRenderObjectWidget {
  const HomeRowsCard({super.key, required this.cardKey, required this.kind, required super.child});

  final Object cardKey;
  final Object kind;

  @override
  RenderObject createRenderObject(BuildContext context) {
    final scope = context.dependOnInheritedWidgetOfExactType<_LayerScope>();
    return RenderHomeRowsCard(cardKey, kind, scope?.layer, scope?.reduced ?? false);
  }

  @override
  void updateRenderObject(BuildContext context, RenderHomeRowsCard renderObject) {
    final scope = context.dependOnInheritedWidgetOfExactType<_LayerScope>();
    renderObject.update(cardKey, kind, scope?.layer, scope?.reduced ?? false);
  }
}

/// [HomeRowsCard]'s render object.
class RenderHomeRowsCard extends RenderProxyBox {
  RenderHomeRowsCard(this.key, this.kind, this._layer, this._reduced);

  Object key;
  Object kind;
  HomeRowsLayer? _layer;
  bool _reduced;
  bool _compositing = false;

  final LayerHandle<TransformLayer> _transform = LayerHandle<TransformLayer>();
  final LayerHandle<OpacityLayer> _opacity = LayerHandle<OpacityLayer>();

  void update(Object key, Object kind, HomeRowsLayer? layer, bool reduced) {
    if (key != this.key || kind != this.kind || !identical(layer, _layer)) {
      if (attached) _leave();
      this.key = key;
      this.kind = kind;
      _layer = layer;
      if (attached) _join();
      markNeedsPaint();
    }
    _reduced = reduced;
  }

  void _join() {
    final layer = _layer;
    if (layer == null) return;
    layer.track.height.addListener(markNeedsPaint);
    layer.track.structure.addListener(_onStructure);
    _compositing = layer.track.active;
  }

  void _leave() {
    final layer = _layer;
    if (layer == null) return;
    layer.track.height.removeListener(markNeedsPaint);
    layer.track.structure.removeListener(_onStructure);
    layer.track.forgetRect(layer.stop, key);
  }

  void _onStructure() {
    final active = _layer?.track.active ?? false;
    if (active != _compositing) {
      _compositing = active;
      markNeedsCompositingBitsUpdate();
    }
    markNeedsPaint();
  }

  @override
  bool get alwaysNeedsCompositing => _compositing;

  @override
  void attach(PipelineOwner owner) {
    super.attach(owner);
    _join();
  }

  @override
  void detach() {
    _leave();
    super.detach();
  }

  @override
  void dispose() {
    _transform.layer = null;
    _opacity.layer = null;
    super.dispose();
  }

  /// Where this card sits in its stop's layer, recorded as it paints (layout
  /// is done by then: the transform to the layer's box is final).
  void _record(HomeRowsLayer layer) {
    final root = layer.root;
    if (root == null || !root.attached || !hasSize) return;
    final rect = MatrixUtils.transformRect(getTransformTo(root), Offset.zero & size);
    layer.track.recordRect(layer.stop, key, kind, rect);
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final layer = _layer;
    if (layer != null) _record(layer);
    final look = layer == null ? HomeRowsCardLook.identity : layer.track.cardLook(layer.stop, key, reduced: _reduced);
    if (look.isIdentity) {
      _transform.layer = null;
      _opacity.layer = null;
      super.paint(context, offset);
      return;
    }
    if (look.alpha <= 0.001) {
      _transform.layer = null;
      _opacity.layer = null;
      return;
    }
    final center = size.center(Offset.zero);
    final matrix = Matrix4.identity()
      ..translateByDouble(look.dx + center.dx, look.dy + center.dy, 0, 1)
      ..scaleByDouble(look.scale, look.scale, 1, 1)
      ..translateByDouble(-center.dx, -center.dy, 0, 1);
    void inner(PaintingContext c, Offset o) {
      if (look.alpha >= 0.999) {
        _opacity.layer = null;
        super.paint(c, o);
      } else {
        _opacity.layer = c.pushOpacity(o, (look.alpha * 255).round(), super.paint, oldLayer: _opacity.layer);
      }
    }

    _transform.layer = context.pushTransform(needsCompositing, offset, matrix, inner, oldLayer: _transform.layer);
  }
}
