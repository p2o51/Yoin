/// Home edit mode's one gesture detector (Android ui/home/edit/HomeEditGestures.kt,
/// port sheet §2.1): the page hit test, a pure state machine fed one finger's
/// events and its timers, and the arena recognizer over the whole feed that
/// drives them.
///
/// In normal mode the machine only watches until the long-press threshold
/// (T), so a tap still opens the card and a slop still scrolls; at T/2 the
/// press charge starts on a block, and at T the finger is owned: edit mode
/// comes in with that block already lifted, and the first slop carries it.
/// In edit mode a body hold of max(150 ms, T/2) or a drag handle's slop
/// lifts, a tap kicks, a blank tap leaves, and a second finger before any
/// lift pinches the feed into the compact list (or out of it). The machine is
/// :shared HomeEditGestureMachine.kt's, replayed against home-edit-gestures.json.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';

import 'home_section.dart';

// ── Hit testing ───────────────────────────────────────────────────────────

/// What a press lands on, resolved once at pointer-down. Positions are page px.
sealed class HomeEditHit {
  const HomeEditHit();
}

/// A control the gestures leave alone: the header, the hide buttons, the ⌟ handles, the tray, the footer.
final class HomeEditHitExcluded extends HomeEditHit {
  const HomeEditHitExcluded();
}

/// [section]'s drag handle (edit mode only). [local] and [size] as for [HomeEditHitBlock].
final class HomeEditHitHandle extends HomeEditHit {
  const HomeEditHitHandle(this.section, this.local, [this.size = Size.zero]);
  final HomeSection section;
  final Offset local;
  final Size size;
}

/// Inside [section]'s band. [local] is in block-content px; [size] is the block content's.
final class HomeEditHitBlock extends HomeEditHit {
  const HomeEditHitBlock(this.section, this.local, [this.size = Size.zero]);
  final HomeSection section;
  final Offset local;
  final Size size;
}

/// Off every block. [nearest] is the closest visible section, the entry ripple's origin.
final class HomeEditHitBlank extends HomeEditHit {
  const HomeEditHitBlank(this.nearest);
  final HomeSection? nearest;
}

/// A section's vertical extent in page px. [bleeds]: a full-width shelf that takes touches in the margins too.
@immutable
final class SectionBand {
  const SectionBand(this.id, this.top, this.bottom, {this.bleeds = false});
  final HomeSection id;
  final double top;
  final double bottom;
  final bool bleeds;
}

/// Full-width shelves: a press in the page margin beside them lands on the shelf.
const Set<HomeSection> homeEditBleedingSections = {HomeSection.recentlyAdded, HomeSection.rediscover};

/// The band nearest to [y] (distance 0 inside); the first wins a tie. −1 when there are none.
int nearestSectionIndex(double y, List<SectionBand> bands) {
  var best = -1;
  var bestDistance = double.infinity;
  for (var i = 0; i < bands.length; i++) {
    final band = bands[i];
    final distance = y < band.top ? band.top - y : (y > band.bottom ? y - band.bottom : 0.0);
    if (distance < bestDistance) {
      best = i;
      bestDistance = distance;
    }
  }
  return best;
}

/// The band under ([x], [y]), or null for blank. In edit mode the plate
/// outsets count as the block. Bleeding shelves take any x inside their y range.
SectionBand? resolveSectionAt({
  required double x,
  required double y,
  required List<SectionBand> bands,
  required double contentLeft,
  required double contentRight,
  required double outsetH,
  required double outsetV,
  required bool editing,
}) {
  final padH = editing ? outsetH : 0.0;
  final padV = editing ? outsetV : 0.0;
  for (final band in bands) {
    if (y >= band.top - padV &&
        y <= band.bottom + padV &&
        (band.bleeds || x >= contentLeft - padH && x <= contentRight + padH)) {
      return band;
    }
  }
  return null;
}

/// The hit at [position] once exclusions are ruled out: [handle] wins (edit
/// mode), then the band under the finger (plate outsets count while
/// [editing]; bleeding shelves take the margins), else blank with the
/// nearest band.
HomeEditHit resolveHomeEditHit({
  required Offset position,
  required List<SectionBand> bands,
  required double contentLeft,
  required double contentRight,
  required Size outset,
  required bool editing,
  HomeSection? handle,
}) {
  HomeEditHitBlock block(SectionBand band) => HomeEditHitBlock(
    band.id,
    Offset(position.dx - contentLeft, position.dy - band.top),
    Size(math.max(0, contentRight - contentLeft), band.bottom - band.top),
  );
  if (handle != null) {
    for (final band in bands) {
      if (band.id != handle) continue;
      final b = block(band);
      return HomeEditHitHandle(handle, b.local, b.size);
    }
  }
  final band = resolveSectionAt(
    x: position.dx,
    y: position.dy,
    bands: bands,
    contentLeft: contentLeft,
    contentRight: contentRight,
    outsetH: outset.width,
    outsetV: outset.height,
    editing: editing,
  );
  if (band != null) return block(band);
  final nearest = nearestSectionIndex(position.dy, bands);
  return HomeEditHitBlank(nearest < 0 ? null : bands[nearest].id);
}

// ── Gesture machine ───────────────────────────────────────────────────────
// A port of :shared HomeEditGestureMachine.kt (parity p4; Android PR #292), replayed against
// shared/src/commonTest/resources/golden/home-edit-gestures.json (test/home/home_edit_gestures_golden_test.dart).

/// Numbers of the gesture machine (HomeEditGestureTokens).
abstract final class HomeEditGestureTokens {
  /// Edit mode's body hold never lifts sooner than this (ms); otherwise half the long-press timeout.
  static const int bodyHoldMinMs = 150;

  /// A tap's kick, and the one a lone lift puts down with.
  static const double kickTap = .5;

  /// A timer that wakes this late (ms) may have pointer events from before it still queued…
  static const int lateTimerMs = 8;

  /// …which reach the recognizer with the first frame the UI thread runs after it. A resting finger lifts this late at most.
  static const int stalledInputGraceMs = 120;
}

/// Where the tracked finger is in the gesture (port sheet §2.1).
enum HomeEditGestureState {
  /// No finger tracked.
  idle,

  /// Normal mode before the long-press threshold: watching only, so taps and scrolls go through.
  pressing,

  /// A block is lifted under the finger; a slop from the down point starts the carry.
  lifted,

  /// Edit mode on a block's body (or plate outset): lifts after the body hold.
  holding,

  /// Edit mode on a drag handle: the first slop lifts and carries at once.
  handleDown,

  /// Edit mode off every plate: a tap exits.
  blank,

  /// The finger drags the lifted block.
  carry,

  /// A second finger came down in edit mode: the two pinch the list in or out.
  pinch,

  /// The rest of the finger is swallowed.
  done,

  /// An edit-mode press given up to the list (a scroll); watched only for its up.
  passive,
}

enum HomeEditTimer {
  /// T/2: the press charge starts (blocks only).
  halfT,

  /// T: enter edit mode.
  longPress,

  /// Edit mode, max(150 ms, T/2) on a block body: lift.
  bodyHold,

  /// Edit mode, T on blank: its up no longer exits.
  blankHold,
}

/// A pending timer and its deadline (event time, ms).
@immutable
final class HomeEditTimerAt {
  const HomeEditTimerAt(this.kind, this.atMs);
  final HomeEditTimer kind;
  final int atMs;
}

/// Input to [HomeEditGestureMachine]. Times are pointer-event times (ms).
sealed class HomeEditGestureEvent {
  const HomeEditGestureEvent();
}

/// The tracked finger went down.
final class HomeEditDown extends HomeEditGestureEvent {
  const HomeEditDown({
    required this.hit,
    required this.position,
    required this.editing,
    required this.flingInProgress,
    required this.t,
    this.secondaryClick = false,
  });
  final HomeEditHit hit;
  final Offset position;
  final bool editing;

  /// The feed (or the list) is flinging, or stopped one a moment ago.
  final bool flingInProgress;
  final int t;

  /// A mouse or trackpad secondary-button press (desktop windowing).
  final bool secondaryClick;
}

final class HomeEditMove extends HomeEditGestureEvent {
  const HomeEditMove(this.position, this.t);
  final Offset position;
  final int t;
}

final class HomeEditUp extends HomeEditGestureEvent {
  const HomeEditUp(this.t);
  final int t;
}

/// The system or an ancestor took the finger.
final class HomeEditCancel extends HomeEditGestureEvent {
  const HomeEditCancel(this.t);
  final int t;
}

/// [kind] is due. [flinging]: the feed (or the list) is flinging as it fires, so a hold doesn't lift.
final class HomeEditTimerFired extends HomeEditGestureEvent {
  const HomeEditTimerFired(this.kind, {this.flinging = false});
  final HomeEditTimer kind;
  final bool flinging;
}

/// Edit mode ended under the finger (critique 4).
final class HomeEditEditEnded extends HomeEditGestureEvent {
  const HomeEditEditEnded();
}

/// The drag was let go from outside: an exit or a resize dropped it (proto forceDrop).
final class HomeEditCarryEnded extends HomeEditGestureEvent {
  const HomeEditCarryEnded();
}

/// A change of any other finger.
final class HomeEditOtherPointer extends HomeEditGestureEvent {
  const HomeEditOtherPointer();
}

/// A second finger went down [span] px from the tracked one.
final class HomeEditPinchDown extends HomeEditGestureEvent {
  const HomeEditPinchDown(this.span);
  final double span;
}

/// A finger of the pinch moved: the two are [span] px apart.
final class HomeEditPinchMove extends HomeEditGestureEvent {
  const HomeEditPinchMove(this.span);
  final double span;
}

/// The pinch's second finger lifted.
final class HomeEditPinchUp extends HomeEditGestureEvent {
  const HomeEditPinchUp();
}

/// Output of [HomeEditGestureMachine], applied in order by Home's executor.
sealed class HomeEditGestureAction {
  const HomeEditGestureAction();

  /// Dropped once edit mode has ended (critique 4): a body hold that fires
  /// after Done never lifts in normal mode, and a blank tap never exits twice.
  bool get editOnly => false;
}

/// The press is given up: taps, clicks and scrolls go to the page.
final class HomeEditAbandon extends HomeEditGestureAction {
  const HomeEditAbandon();
}

final class HomeEditStartCharge extends HomeEditGestureAction {
  const HomeEditStartCharge(this.section, this.local);
  final HomeSection section;
  final Offset local;
}

final class HomeEditReleaseCharge extends HomeEditGestureAction {
  const HomeEditReleaseCharge();
}

final class HomeEditLongPressHaptic extends HomeEditGestureAction {
  const HomeEditLongPressHaptic();
}

final class HomeEditDragStartHaptic extends HomeEditGestureAction {
  const HomeEditDragStartHaptic();
}

final class HomeEditEnter extends HomeEditGestureAction {
  const HomeEditEnter(this.origin, {required this.lifted});
  final HomeSection? origin;
  final bool lifted;
}

/// The entry plate's start, from the charge read before it is released.
final class HomeEditLatchPlate extends HomeEditGestureAction {
  const HomeEditLatchPlate(this.section, this.local, this.size);
  final HomeSection section;
  final Offset local;
  final Size size;

  @override
  bool get editOnly => true;
}

final class HomeEditLift extends HomeEditGestureAction {
  const HomeEditLift(this.section, this.local);
  final HomeSection section;
  final Offset local;

  @override
  bool get editOnly => true;
}

final class HomeEditDropLift extends HomeEditGestureAction {
  const HomeEditDropLift(this.kick);
  final double kick;
}

/// Placeholders held back above the lifted block may come in (spec §2.1.4-7).
final class HomeEditOpenPlaceholders extends HomeEditGestureAction {
  const HomeEditOpenPlaceholders();
}

final class HomeEditStartCarry extends HomeEditGestureAction {
  const HomeEditStartCarry(this.y, this.t);
  final double y;
  final int t;

  @override
  bool get editOnly => true;
}

final class HomeEditCarryMove extends HomeEditGestureAction {
  const HomeEditCarryMove(this.y, this.t);
  final double y;
  final int t;
}

final class HomeEditCarryRelease extends HomeEditGestureAction {
  const HomeEditCarryRelease({required this.cancelled, required this.t});
  final bool cancelled;
  final int t;
}

/// Two fingers down in edit mode: the list follows their span from here.
final class HomeEditPinchStart extends HomeEditGestureAction {
  const HomeEditPinchStart();

  @override
  bool get editOnly => true;
}

/// The fingers' span over the one they started at.
final class HomeEditPinchBy extends HomeEditGestureAction {
  const HomeEditPinchBy(this.scale);
  final double scale;
}

/// The pinch let go: the list lands where it was left.
final class HomeEditPinchEnd extends HomeEditGestureAction {
  const HomeEditPinchEnd();
}

/// An edit-mode tap: kick the card at [local] (or the block, for null or no card).
final class HomeEditBlockTap extends HomeEditGestureAction {
  const HomeEditBlockTap(this.section, this.local);
  final HomeSection section;
  final Offset? local;

  @override
  bool get editOnly => true;
}

final class HomeEditExitBlank extends HomeEditGestureAction {
  const HomeEditExitBlank();

  @override
  bool get editOnly => true;
}

/// Sway envelope back up, idle window restarted.
final class HomeEditTouch extends HomeEditGestureAction {
  const HomeEditTouch();
}

final class HomeEditPointerDown extends HomeEditGestureAction {
  const HomeEditPointerDown(this.down);
  final bool down;
}

/// Consume this change and every later one of the finger.
final class HomeEditConsumeRest extends HomeEditGestureAction {
  const HomeEditConsumeRest();
}

/// Consume the other finger's change: it is ignored, but must not scroll the list (spec ⑫).
final class HomeEditConsumeOther extends HomeEditGestureAction {
  const HomeEditConsumeOther();
}

/// The detector as a pure machine (port sheet §2.1, spec §2.1.2, §2.6): fed
/// one finger's events and its timers, it answers with actions. In normal mode
/// it only watches until the long-press threshold, so a tap still opens the
/// card and a slop still scrolls; from the threshold on the finger is owned.
/// In edit mode a body hold or a handle drag lifts, a tap kicks, a blank tap
/// exits, and a second finger before any lift pinches the list in or out.
/// [longPressMs] is T, [slopPx] the touch slop.
class HomeEditGestureMachine {
  HomeEditGestureMachine({required this.longPressMs, required this.slopPx})
    : _bodyHoldMs = math.max(HomeEditGestureTokens.bodyHoldMinMs, longPressMs ~/ 2);

  final int longPressMs;
  final double slopPx;
  final int _bodyHoldMs;

  HomeEditGestureState get state => _state;
  HomeEditGestureState _state = HomeEditGestureState.idle;

  final List<HomeEditTimerAt> _timers = [];

  Offset _downAt = Offset.zero;

  /// Where the tracked finger went down (page px).
  Offset get downAt => _downAt;

  /// Where the tracked finger was last seen (page px): a drag reads its x (two columns, the follow).
  Offset get position => _position;
  Offset _position = Offset.zero;
  HomeSection? _section;
  Offset _local = Offset.zero;
  Size _size = Size.zero;
  HomeSection? _nearest;

  // The finger belongs to an edit session: its up restarts the idle window and opens placeholders.
  bool _editGesture = false;
  bool _charging = false;
  bool _flingStop = false;
  bool _blankHeld = false;
  double _pinchSpan = 0;

  // The instant (event time) a hold lifted the block: a move from before it past the slop takes the lift back.
  static const int _never = -0x8000000000000000;
  int _liftAt = _never;

  /// The next timer to fire, or null.
  HomeEditTimerAt? get pendingTimer {
    HomeEditTimerAt? next;
    for (final t in _timers) {
      if (next == null || t.atMs < next.atMs) next = t;
    }
    return next;
  }

  List<HomeEditGestureAction> handle(HomeEditGestureEvent event) => switch (event) {
    HomeEditDown() => _down(event),
    HomeEditMove() => _move(event),
    HomeEditUp(:final t) => _up(t),
    HomeEditCancel(:final t) => _cancel(t),
    HomeEditTimerFired(:final kind, :final flinging) => _timer(kind, flinging),
    HomeEditEditEnded() => _editEnded(),
    HomeEditCarryEnded() => _carryEnded(),
    HomeEditOtherPointer() => _otherPointer(),
    HomeEditPinchDown(:final span) => _pinchDown(span),
    HomeEditPinchMove(:final span) =>
      _state == HomeEditGestureState.pinch && _pinchSpan > 0 ? [HomeEditPinchBy(span / _pinchSpan)] : const [],
    HomeEditPinchUp() => _pinchUp(),
  };

  List<HomeEditGestureAction> _otherPointer() =>
      _state != HomeEditGestureState.idle && _state != HomeEditGestureState.passive
      ? const [HomeEditConsumeOther()]
      : const [];

  // Before a lift, an edit-mode press (a scroll included) becomes a pinch; otherwise the finger is ignored.
  List<HomeEditGestureAction> _pinchDown(double span) {
    final canPinch =
        _editGesture &&
        span > 0 &&
        (_state == HomeEditGestureState.holding ||
            _state == HomeEditGestureState.handleDown ||
            _state == HomeEditGestureState.blank ||
            _state == HomeEditGestureState.passive);
    if (!canPinch) return _otherPointer();
    _timers.clear();
    _pinchSpan = span;
    _state = HomeEditGestureState.pinch;
    return const [HomeEditPinchStart(), HomeEditConsumeRest(), HomeEditConsumeOther()];
  }

  List<HomeEditGestureAction> _pinchUp() {
    if (_state != HomeEditGestureState.pinch) return const [];
    _state = HomeEditGestureState.done;
    return const [HomeEditPinchEnd()];
  }

  List<HomeEditGestureAction> _down(HomeEditDown event) {
    if (_state != HomeEditGestureState.idle) return const [];
    _reset();
    _downAt = event.position;
    _position = event.position;
    switch (event.hit) {
      case HomeEditHitBlock(:final section, :final local, :final size):
        _target(section, local, size);
      case HomeEditHitHandle(:final section, :final local, :final size):
        _target(section, local, size);
      case HomeEditHitBlank(:final nearest):
        _nearest = nearest;
      case HomeEditHitExcluded():
        break;
    }
    return event.editing ? _editDown(event) : _normalDown(event);
  }

  List<HomeEditGestureAction> _editDown(HomeEditDown event) {
    // Only primary presses edit; the mouse's secondary button does nothing here.
    if (event.secondaryClick) return const [];
    if (event.hit is HomeEditHitExcluded) return const [HomeEditTouch()];
    _editGesture = true;
    switch (event.hit) {
      // A press on a block that stops a fling (or lands just after one) is part of the
      // scroll: it never lifts, and its up kicks nothing.
      case HomeEditHitHandle():
        _state = event.flingInProgress ? HomeEditGestureState.passive : HomeEditGestureState.handleDown;
      case HomeEditHitBlock():
        if (event.flingInProgress) {
          _state = HomeEditGestureState.passive;
        } else {
          _timers.add(HomeEditTimerAt(HomeEditTimer.bodyHold, event.t + _bodyHoldMs));
          _state = HomeEditGestureState.holding;
        }
      default:
        _flingStop = event.flingInProgress;
        _timers.add(HomeEditTimerAt(HomeEditTimer.blankHold, event.t + longPressMs));
        _state = HomeEditGestureState.blank;
    }
    return const [HomeEditTouch(), HomeEditPointerDown(true)];
  }

  List<HomeEditGestureAction> _normalDown(HomeEditDown event) {
    if (event.hit is HomeEditHitExcluded) return const [];
    // A press that stops a fling is the scroll's, not a long press: left to the page untouched.
    if (event.flingInProgress && !event.secondaryClick) return const [];
    if (event.secondaryClick) {
      _editGesture = true;
      _state = HomeEditGestureState.done;
      return [
        HomeEditEnter(_section ?? _nearest, lifted: false),
        const HomeEditConsumeRest(),
        const HomeEditPointerDown(true),
      ];
    }
    _state = HomeEditGestureState.pressing;
    if (_section != null) _timers.add(HomeEditTimerAt(HomeEditTimer.halfT, event.t + longPressMs ~/ 2));
    _timers.add(HomeEditTimerAt(HomeEditTimer.longPress, event.t + longPressMs));
    return const [];
  }

  List<HomeEditGestureAction> _move(HomeEditMove event) {
    _position = event.position;
    final pastSlop = (event.position - _downAt).distance > slopPx;
    final section = _section;
    switch (_state) {
      case HomeEditGestureState.pressing:
        return pastSlop ? _abandon(HomeEditGestureState.idle) : const [];
      case HomeEditGestureState.holding || HomeEditGestureState.blank:
        return pastSlop ? _abandon(HomeEditGestureState.passive) : const [];
      case HomeEditGestureState.handleDown:
        if (!pastSlop || section == null) return const [];
        _state = HomeEditGestureState.carry;
        return [
          const HomeEditDragStartHaptic(),
          HomeEditLift(section, _local),
          HomeEditStartCarry(event.position.dy, event.t),
          const HomeEditConsumeRest(),
        ];
      // A move stamped before the lift that was already past the slop, handed over late (a long
      // frame held the input back past the hold's timer): the finger never held still that long,
      // so the lift is taken back and the rest of the finger swallowed. Never a carry: a fling
      // must not drag the block it started on.
      case HomeEditGestureState.lifted:
        if (pastSlop && event.t < _liftAt) {
          _timers.clear();
          _state = HomeEditGestureState.done;
          return const [HomeEditDropLift(0)];
        }
        if (!pastSlop) return const [];
        _state = HomeEditGestureState.carry;
        return [HomeEditStartCarry(event.position.dy, event.t)];
      case HomeEditGestureState.carry:
        return [HomeEditCarryMove(event.position.dy, event.t)];
      case HomeEditGestureState.idle ||
          HomeEditGestureState.done ||
          HomeEditGestureState.passive ||
          HomeEditGestureState.pinch:
        return const [];
    }
  }

  List<HomeEditGestureAction> _up(int t) {
    final section = _section;
    final List<HomeEditGestureAction> body = switch (_state) {
      HomeEditGestureState.pressing => [..._releaseCharge(), const HomeEditAbandon()],
      HomeEditGestureState.lifted => const [HomeEditDropLift(HomeEditGestureTokens.kickTap)],
      // A handle has no card under it: the block kicks.
      HomeEditGestureState.holding || HomeEditGestureState.handleDown =>
        section == null
            ? const []
            : [
                HomeEditBlockTap(section, _state == HomeEditGestureState.holding ? _local : null),
                const HomeEditConsumeRest(),
              ],
      HomeEditGestureState.blank =>
        _flingStop || _blankHeld ? const [] : const [HomeEditExitBlank(), HomeEditConsumeRest()],
      HomeEditGestureState.carry => [HomeEditCarryRelease(cancelled: false, t: t)],
      HomeEditGestureState.pinch => const [HomeEditPinchEnd()],
      HomeEditGestureState.idle || HomeEditGestureState.done || HomeEditGestureState.passive => const [],
    };
    return _finish(body);
  }

  List<HomeEditGestureAction> _cancel(int t) {
    final List<HomeEditGestureAction> body = switch (_state) {
      HomeEditGestureState.pressing => [..._releaseCharge(), const HomeEditAbandon()],
      HomeEditGestureState.lifted => const [HomeEditDropLift(0)],
      HomeEditGestureState.carry => [HomeEditCarryRelease(cancelled: true, t: t)],
      HomeEditGestureState.pinch => const [HomeEditPinchEnd()],
      _ => const [],
    };
    return _finish(body);
  }

  // An edit-session finger restarts the idle window as it lifts and opens held-back placeholders (critique 27).
  List<HomeEditGestureAction> _finish(List<HomeEditGestureAction> body) {
    final edit = _editGesture;
    _reset();
    if (!edit) return body;
    return [const HomeEditPointerDown(false), const HomeEditTouch(), ...body, const HomeEditOpenPlaceholders()];
  }

  List<HomeEditGestureAction> _timer(HomeEditTimer kind, bool flinging) {
    HomeEditTimerAt? due;
    for (final t in _timers) {
      if (t.kind == kind) {
        due = t;
        break;
      }
    }
    if (due == null) return const [];
    final at = due.atMs;
    _timers.removeWhere((t) => t.kind == kind);
    final section = _section;
    // The feed began to fling under a resting finger: no lift, the press is the scroll's.
    if (flinging && kind == HomeEditTimer.longPress && _state == HomeEditGestureState.pressing) {
      return _abandon(HomeEditGestureState.idle);
    }
    if (flinging && kind == HomeEditTimer.bodyHold && _state == HomeEditGestureState.holding) {
      return _abandon(HomeEditGestureState.passive);
    }
    switch (kind) {
      case HomeEditTimer.halfT:
        if (_state != HomeEditGestureState.pressing || section == null) return const [];
        _charging = true;
        return [HomeEditStartCharge(section, _local)];
      case HomeEditTimer.longPress:
        if (_state != HomeEditGestureState.pressing) return const [];
        _liftAt = at;
        return _threshold(section);
      case HomeEditTimer.bodyHold:
        if (_state != HomeEditGestureState.holding || section == null) return const [];
        _liftAt = at;
        _state = HomeEditGestureState.lifted;
        return [const HomeEditDragStartHaptic(), HomeEditLift(section, _local), const HomeEditConsumeRest()];
      case HomeEditTimer.blankHold:
        if (_state == HomeEditGestureState.blank) _blankHeld = true;
        return const [];
    }
  }

  // The threshold frame (port sheet §2.3); the order matters: the plate
  // latches the charge before it is released, and the lift needs edit mode.
  List<HomeEditGestureAction> _threshold(HomeSection? section) {
    _timers.clear();
    _charging = false;
    _editGesture = true;
    if (section == null) {
      _state = HomeEditGestureState.done;
      return [
        const HomeEditLongPressHaptic(),
        const HomeEditReleaseCharge(),
        HomeEditEnter(_nearest, lifted: false),
        const HomeEditConsumeRest(),
        const HomeEditPointerDown(true),
      ];
    }
    _state = HomeEditGestureState.lifted;
    return [
      const HomeEditLongPressHaptic(),
      HomeEditEnter(section, lifted: true),
      HomeEditLatchPlate(section, _local, _size),
      const HomeEditReleaseCharge(),
      HomeEditLift(section, _local),
      const HomeEditConsumeRest(),
      const HomeEditPointerDown(true),
    ];
  }

  // proto disownGesture: a press still waiting on edit mode must not lift or exit in normal mode.
  List<HomeEditGestureAction> _editEnded() {
    switch (_state) {
      case HomeEditGestureState.lifted ||
          HomeEditGestureState.holding ||
          HomeEditGestureState.handleDown ||
          HomeEditGestureState.blank:
        _timers.clear();
        _state = HomeEditGestureState.done;
        return const [HomeEditConsumeRest()];
      default:
        return const [];
    }
  }

  List<HomeEditGestureAction> _carryEnded() {
    if (_state == HomeEditGestureState.carry) _state = HomeEditGestureState.done;
    return const [];
  }

  List<HomeEditGestureAction> _abandon(HomeEditGestureState next) {
    _timers.clear();
    final release = _releaseCharge();
    _state = next;
    return [...release, const HomeEditAbandon()];
  }

  List<HomeEditGestureAction> _releaseCharge() {
    if (!_charging) return const [];
    _charging = false;
    return const [HomeEditReleaseCharge()];
  }

  void _target(HomeSection section, Offset local, Size size) {
    _section = section;
    _local = local;
    _size = size;
  }

  void _reset() {
    _state = HomeEditGestureState.idle;
    _timers.clear();
    _section = null;
    _local = Offset.zero;
    _size = Size.zero;
    _nearest = null;
    _editGesture = false;
    _charging = false;
    _flingStop = false;
    _blankHeld = false;
    _pinchSpan = 0;
    _liftAt = _never;
  }
}

/// Runs [machine]'s timers on the pointer events' own clock (their stamps, ms),
/// never on when a wait happens to wake (HomeEditGestureTimeline). Every sample
/// of the finger is handled at its stamp, and a timer due at or before a stamp
/// fires first. So a hold lifts only if the finger stayed within the slop up to
/// the hold's instant by the events themselves: a long frame that hands a
/// fling's moves over late can't let the hold run ahead of them. A move from
/// before the instant that comes even later than that takes the lift back
/// ([HomeEditGestureMachine]). [flinging] is asked as a hold fires.
class HomeEditGestureTimeline {
  HomeEditGestureTimeline(this.machine, {required this.flinging, required this.step});

  final HomeEditGestureMachine machine;
  final bool Function() flinging;
  final List<HomeEditGestureAction> Function(HomeEditGestureEvent event) step;

  /// The event clock: the latest instant handled, a sample's stamp or a timer's.
  int get now => _now;
  int _now = 0;

  /// The gesture starts at its down's stamp [t].
  void start(int t) => _now = t;

  /// How long (ms, from [now]) input may take before the next timer is due; null with none pending.
  int? untilDue() {
    final timer = machine.pendingTimer;
    return timer == null ? null : math.max(0, timer.atMs - _now);
  }

  /// The wait for the next timer woke at [wallMs] with no input: how much longer
  /// to wait for input held back from before the timer's instant. Only a wait
  /// that woke late (a long frame) has any to wait for.
  int graceAfter(int wallMs) {
    final timer = machine.pendingTimer;
    if (timer == null) return 0;
    return wallMs - timer.atMs > HomeEditGestureTokens.lateTimerMs ? HomeEditGestureTokens.stalledInputGraceMs : 0;
  }

  /// No input came by the next timer's instant (and its grace): it fires.
  void timeout() {
    final timer = machine.pendingTimer;
    if (timer != null) _fire(timer);
  }

  /// Timers due at or before [t] fire, oldest first; then the clock is at [t].
  void advanceTo(int t) {
    while (true) {
      final timer = machine.pendingTimer;
      if (timer == null || timer.atMs > t) break;
      _fire(timer);
    }
    _now = math.max(_now, t);
  }

  /// A sample of the tracked finger, handled at its own stamp: whatever was due before it goes first.
  List<HomeEditGestureAction> sample(HomeEditGestureEvent event) {
    final t = switch (event) {
      HomeEditMove(:final t) || HomeEditUp(:final t) || HomeEditCancel(:final t) => t,
      _ => _now,
    };
    advanceTo(t);
    return step(event);
  }

  void _fire(HomeEditTimerAt timer) {
    _now = math.max(_now, timer.atMs);
    final lifts = timer.kind == HomeEditTimer.bodyHold || timer.kind == HomeEditTimer.longPress;
    step(HomeEditTimerFired(timer.kind, flinging: lifts && flinging()));
  }
}

// ── Recognizer ────────────────────────────────────────────────────────────

/// Applies one batch of machine actions (Home's executor).
typedef HomeEditGestureRunner = void Function(List<HomeEditGestureAction> actions, HomeEditGestureMachine machine);

/// Home edit mode's detector over the whole feed (or the pinched list),
/// margins included (spec §2.1.2): one [HomeEditGestureMachine] per finger,
/// its timers run on the events' own clock ([HomeEditGestureTimeline]),
/// driven from the pointer router. Compose reads the Initial pass; here the
/// recognizer joins the arena undecided and only claims it when the machine
/// consumes (a lift, a carry, a blank tap, a pinch). A press the machine gives
/// up (a scroll, a tap on a card, a tap on a plate) is rejected so the page
/// gets it; an edit-mode press given up is still watched for its up, and a
/// second finger then still pinches. A second finger is tracked for the
/// pinch: its span against the first one's feeds the machine.
class HomeEditGestureRecognizer extends OneSequenceGestureRecognizer {
  HomeEditGestureRecognizer({super.debugOwner})
    : super(allowedButtonsFilter: (buttons) => buttons == kPrimaryButton || buttons == kSecondaryButton);

  /// The press at a page position; called once, at pointer-down.
  HomeEditHit Function(Offset pagePosition)? hitTest;

  /// Global → page px.
  Offset Function(Offset global)? toPage;
  bool Function()? editing;

  /// The drag was let go from outside (an exit, a resize) while the finger is down.
  bool Function()? carryLetGo;
  bool Function()? flingInProgress;
  HomeEditGestureRunner? run;

  /// T and the touch slop.
  int longPressMs = kLongPressTimeout.inMilliseconds;
  double slop = kTouchSlop;

  HomeEditGestureMachine? _machine;
  HomeEditGestureTimeline? _timeline;
  int? _primary;
  Offset _primaryAt = Offset.zero;
  int? _pinch;
  Offset _pinchAt = Offset.zero;
  final Set<int> _others = {};
  bool _accepted = false;
  bool _rejected = false;
  Timer? _timer;
  int _waitGeneration = 0;

  /// A finger the machine owns or watches.
  bool get active => _machine != null;

  // An event's stamp on the gesture's clock. Synthesized events (a test's, with no stamp) take the
  // clock as it stands, so they never land before a timer that has fired.
  int _stamp(PointerEvent event) {
    final t = event.timeStamp;
    return t == Duration.zero ? (_timeline?.now ?? 0) : t.inMilliseconds;
  }

  @override
  void addAllowedPointer(PointerDownEvent event) {
    final machine = _machine;
    if (machine != null) {
      _secondPointer(event, machine);
      return;
    }
    final hitTest = this.hitTest, toPage = this.toPage, run = this.run;
    if (hitTest == null || toPage == null || run == null) return;
    final position = toPage(event.position);
    final m = HomeEditGestureMachine(longPressMs: longPressMs, slopPx: slop);
    _accepted = false;
    _rejected = false;
    final t = event.timeStamp.inMilliseconds;
    final mouse = event.kind == PointerDeviceKind.mouse;
    final actions = m.handle(
      HomeEditDown(
        hit: hitTest(position),
        position: position,
        editing: editing?.call() ?? false,
        flingInProgress: flingInProgress?.call() ?? false,
        t: t,
        secondaryClick: mouse && (event.buttons & kSecondaryButton) != 0 && (event.buttons & kPrimaryButton) == 0,
      ),
    );
    if (m.state == HomeEditGestureState.idle) {
      // Excluded, or nothing to watch: never in the arena.
      run(actions, m);
      return;
    }
    _machine = m;
    _timeline = HomeEditGestureTimeline(
      m,
      flinging: () => flingInProgress?.call() ?? false,
      step: (e) {
        final stepped = m.handle(e);
        _apply(stepped, m);
        return stepped;
      },
    )..start(t);
    _primary = event.pointer;
    _primaryAt = position;
    startTrackingPointer(event.pointer, event.transform);
    _apply(actions, m);
    // A drag handle owns its press: the list must not take its slop first.
    if (m.state == HomeEditGestureState.handleDown) _accept();
    _schedule();
  }

  // Another finger while one is tracked: a pinch before any lift, else ignored (but it must not scroll the list).
  void _secondPointer(PointerDownEvent event, HomeEditGestureMachine machine) {
    final position = toPage?.call(event.position) ?? event.position;
    final List<HomeEditGestureAction> actions;
    if (_pinch == null) {
      actions = machine.handle(HomeEditPinchDown((position - _primaryAt).distance));
    } else {
      actions = machine.handle(const HomeEditOtherPointer());
    }
    if (actions.any((a) => a is HomeEditPinchStart)) {
      _pinch = event.pointer;
      _pinchAt = position;
    }
    if (actions.any((a) => a is HomeEditConsumeOther)) {
      _others.add(event.pointer);
      startTrackingPointer(event.pointer, event.transform);
      resolvePointer(event.pointer, GestureDisposition.accepted);
    }
    _apply(actions.where((a) => a is! HomeEditConsumeOther).toList(), machine);
    _afterStep(machine);
  }

  @override
  void handleEvent(PointerEvent event) {
    final m = _machine;
    if (event.pointer != _primary) {
      if (m != null && event.pointer == _pinch) {
        if (event is PointerMoveEvent) {
          _pinchAt = toPage?.call(event.position) ?? event.position;
          _apply(m.handle(HomeEditPinchMove((_pinchAt - _primaryAt).distance)), m);
        } else if (event is PointerUpEvent || event is PointerCancelEvent) {
          _pinch = null;
          _apply(m.handle(const HomeEditPinchUp()), m);
          _afterStep(m);
        }
      }
      if (_others.contains(event.pointer) && (event is PointerUpEvent || event is PointerCancelEvent)) {
        _others.remove(event.pointer);
        if (_pinch == event.pointer) _pinch = null;
        stopTrackingPointer(event.pointer);
      }
      return;
    }
    final timeline = _timeline;
    if (m == null || timeline == null) return;
    _checkOutside(m);
    final t = _stamp(event);
    if (event is PointerMoveEvent) {
      _primaryAt = toPage!(event.position);
      timeline.sample(HomeEditMove(_primaryAt, t));
      if (_pinch != null && m.state == HomeEditGestureState.pinch) {
        _apply(m.handle(HomeEditPinchMove((_pinchAt - _primaryAt).distance)), m);
      }
    } else if (event is PointerUpEvent) {
      _sampleEnd(timeline, m, HomeEditUp(t));
    } else if (event is PointerCancelEvent) {
      _sampleEnd(timeline, m, HomeEditCancel(t));
    }
    _afterStep(m);
  }

  // The finger's up (or cancel): what was due before it fires first; an up not accepted gives the press away.
  void _sampleEnd(HomeEditGestureTimeline timeline, HomeEditGestureMachine m, HomeEditGestureEvent end) {
    final t = switch (end) {
      HomeEditUp(:final t) || HomeEditCancel(:final t) => t,
      _ => timeline.now,
    };
    timeline.advanceTo(t);
    _apply(m.handle(end), m, up: true);
  }

  // Edit mode can end, or an exit let the drag go, while the finger rests.
  void _checkOutside(HomeEditGestureMachine m) {
    if (!(editing?.call() ?? false)) _apply(m.handle(const HomeEditEditEnded()), m);
    if (m.state == HomeEditGestureState.carry && (carryLetGo?.call() ?? false)) {
      _apply(m.handle(const HomeEditCarryEnded()), m);
    }
  }

  void _apply(List<HomeEditGestureAction> actions, HomeEditGestureMachine m, {bool up = false}) {
    if (actions.isEmpty) {
      if (up && !_accepted) _reject();
      return;
    }
    run?.call(actions, m);
    final consume = actions.any((a) => a is HomeEditConsumeRest);
    // An edit-mode tap on a plate goes to the page: the plate (its kick), a
    // Jump Back In card (its "not now"), a hide button.
    final tap = actions.any((a) => a is HomeEditBlockTap);
    if (consume && !(tap && !_accepted)) {
      _accept();
    } else if (actions.any((a) => a is HomeEditAbandon) || (up && !_accepted)) {
      _reject();
    }
  }

  void _accept() {
    final pointer = _primary;
    if (pointer == null || _accepted || _rejected) return;
    _accepted = true;
    resolvePointer(pointer, GestureDisposition.accepted);
  }

  void _reject() {
    final pointer = _primary;
    if (pointer == null || _accepted || _rejected) return;
    _rejected = true;
    // Still tracked: an edit-mode press given up waits for its up.
    resolvePointer(pointer, GestureDisposition.rejected);
  }

  void _afterStep(HomeEditGestureMachine m) {
    if (m.state == HomeEditGestureState.idle) {
      _end();
    } else {
      _schedule();
    }
  }

  // Waits for the next timer on the event clock; a wait that wakes late gives input held back
  // from before the timer's instant its grace before the timer fires.
  void _schedule() {
    _timer?.cancel();
    _timer = null;
    final timeline = _timeline;
    final due = timeline?.untilDue();
    if (timeline == null || due == null) return;
    final generation = ++_waitGeneration;
    final from = timeline.now;
    final watch = Stopwatch()..start();
    _timer = Timer(Duration(milliseconds: due), () {
      if (generation != _waitGeneration) return;
      _timer = null;
      final grace = timeline.graceAfter(from + watch.elapsedMilliseconds);
      if (grace <= 0) {
        _timeout(generation);
      } else {
        _timer = Timer(Duration(milliseconds: grace), () => _timeout(generation));
      }
    });
  }

  void _timeout(int generation) {
    if (generation != _waitGeneration) return;
    _timer = null;
    final m = _machine;
    final timeline = _timeline;
    if (m == null || timeline == null) return;
    _checkOutside(m);
    timeline.timeout();
    _afterStep(m);
  }

  void _end() {
    _timer?.cancel();
    _timer = null;
    _waitGeneration++;
    final pointer = _primary;
    _machine = null;
    _timeline = null;
    _primary = null;
    _pinch = null;
    if (pointer != null) {
      if (!_accepted) _reject();
      stopTrackingPointer(pointer);
    }
  }

  @override
  void acceptGesture(int pointer) {}

  @override
  void rejectGesture(int pointer) {
    if (pointer != _primary) return;
    _rejected = true;
    final m = _machine;
    final timeline = _timeline;
    if (m == null || timeline == null) return;
    // The list (or a card) took the finger first: what the machine's own slop
    // would have done — normal mode lets go, an edit-mode press is watched to its up.
    switch (m.state) {
      case HomeEditGestureState.pressing || HomeEditGestureState.holding || HomeEditGestureState.blank:
        final far = Offset(slop * 2 + 1, 0);
        run?.call(m.handle(HomeEditMove(m.downAt + far, timeline.now)), m);
      case HomeEditGestureState.handleDown || HomeEditGestureState.lifted || HomeEditGestureState.carry:
        run?.call(m.handle(HomeEditCancel(timeline.now)), m);
      default:
        break;
    }
    if (m.state == HomeEditGestureState.idle) _end();
  }

  @override
  void didStopTrackingLastPointer(int pointer) {}

  @override
  void dispose() {
    final m = _machine;
    if (m != null && m.state != HomeEditGestureState.idle) run?.call(m.handle(HomeEditCancel(_timeline?.now ?? 0)), m);
    _timer?.cancel();
    _machine = null;
    _timeline = null;
    _primary = null;
    super.dispose();
  }

  @override
  String get debugDescription => 'home edit gestures';
}
