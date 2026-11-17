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
/// lifts, a tap kicks, and a blank tap leaves.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';

import '../theme/tokens.dart';
import 'home_metrics.dart';
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

  /// The finger drives the strip carry.
  carry,

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
    required this.carryBusy,
    required this.flingInProgress,
    required this.t,
    this.secondaryClick = false,
  });
  final HomeEditHit hit;
  final Offset position;
  final bool editing;

  /// The carry is settling or unfolding: a press can only catch the strip.
  final bool carryBusy;
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

final class HomeEditTimerFired extends HomeEditGestureEvent {
  const HomeEditTimerFired(this.kind);
  final HomeEditTimer kind;
}

/// The busy press's [HomeEditTryRegrab] caught the settling strip.
final class HomeEditRegrabbed extends HomeEditGestureEvent {
  const HomeEditRegrabbed();
}

/// Edit mode ended under the finger (critique 4).
final class HomeEditEditEnded extends HomeEditGestureEvent {
  const HomeEditEditEnded();
}

/// The carry was let go from outside: an exit dropped it into its settle (proto forceDrop).
final class HomeEditCarryEnded extends HomeEditGestureEvent {
  const HomeEditCarryEnded();
}

/// A change of any other finger.
final class HomeEditOtherPointer extends HomeEditGestureEvent {
  const HomeEditOtherPointer();
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

final class HomeEditTryRegrab extends HomeEditGestureAction {
  const HomeEditTryRegrab(this.position, this.t);
  final Offset position;
  final int t;
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
/// one finger's events and its timers, it answers with actions. [longPressMs]
/// is T, [slopPx] the touch slop.
class HomeEditGestureMachine {
  HomeEditGestureMachine({required this.longPressMs, required this.slopPx})
    : _bodyHoldMs = math.max(HomeCarryTokens.bodyHoldMinMs, longPressMs ~/ 2);

  final int longPressMs;
  final double slopPx;
  final int _bodyHoldMs;

  HomeEditGestureState get state => _state;
  HomeEditGestureState _state = HomeEditGestureState.idle;

  final List<HomeEditTimerAt> _timers = [];

  Offset _downAt = Offset.zero;
  HomeSection? _section;
  Offset _local = Offset.zero;
  Size _size = Size.zero;
  HomeSection? _nearest;

  // The finger belongs to an edit session: its up restarts the idle window and opens placeholders.
  bool _editGesture = false;
  bool _charging = false;
  bool _flingStop = false;
  bool _blankHeld = false;
  bool _awaitingRegrab = false;

  /// The next timer to fire, or null.
  HomeEditTimerAt? get pendingTimer {
    HomeEditTimerAt? next;
    for (final t in _timers) {
      if (next == null || t.atMs < next.atMs) next = t;
    }
    return next;
  }

  List<HomeEditGestureAction> handle(HomeEditGestureEvent event) {
    if (event is! HomeEditRegrabbed && event is! HomeEditOtherPointer) _awaitingRegrab = false;
    return switch (event) {
      HomeEditDown() => _down(event),
      HomeEditMove() => _move(event),
      HomeEditUp(:final t) => _up(t),
      HomeEditCancel(:final t) => _cancel(t),
      HomeEditTimerFired(:final kind) => _timer(kind),
      HomeEditRegrabbed() => _regrabbed(),
      HomeEditEditEnded() => _editEnded(),
      HomeEditCarryEnded() => _carryEnded(),
      HomeEditOtherPointer() =>
        _state != HomeEditGestureState.idle && _state != HomeEditGestureState.passive
            ? const [HomeEditConsumeOther()]
            : const [],
    };
  }

  List<HomeEditGestureAction> _down(HomeEditDown event) {
    if (_state != HomeEditGestureState.idle) return const [];
    _reset();
    _downAt = event.position;
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
    // Settling or unfolding: catch the strip, or swallow the press so the list can't move under it.
    if (event.carryBusy) {
      _editGesture = true;
      _awaitingRegrab = true;
      _state = HomeEditGestureState.done;
      return [
        const HomeEditTouch(),
        const HomeEditPointerDown(true),
        HomeEditTryRegrab(event.position, event.t),
        const HomeEditConsumeRest(),
      ];
    }
    if (event.hit is HomeEditHitExcluded) return const [HomeEditTouch()];
    _editGesture = true;
    switch (event.hit) {
      case HomeEditHitHandle():
        _state = HomeEditGestureState.handleDown;
      case HomeEditHitBlock():
        _timers.add(HomeEditTimerAt(HomeEditTimer.bodyHold, event.t + _bodyHoldMs));
        _state = HomeEditGestureState.holding;
      default:
        _flingStop = event.flingInProgress;
        _timers.add(HomeEditTimerAt(HomeEditTimer.blankHold, event.t + longPressMs));
        _state = HomeEditGestureState.blank;
    }
    return const [HomeEditTouch(), HomeEditPointerDown(true)];
  }

  List<HomeEditGestureAction> _normalDown(HomeEditDown event) {
    if (event.hit is HomeEditHitExcluded) return const [];
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
      case HomeEditGestureState.lifted:
        if (!pastSlop) return const [];
        _state = HomeEditGestureState.carry;
        return [HomeEditStartCarry(event.position.dy, event.t)];
      case HomeEditGestureState.carry:
        return [HomeEditCarryMove(event.position.dy, event.t)];
      case HomeEditGestureState.idle || HomeEditGestureState.done || HomeEditGestureState.passive:
        return const [];
    }
  }

  List<HomeEditGestureAction> _up(int t) {
    final section = _section;
    final List<HomeEditGestureAction> body = switch (_state) {
      HomeEditGestureState.pressing => [..._releaseCharge(), const HomeEditAbandon()],
      HomeEditGestureState.lifted => const [HomeEditDropLift(HomeWiggleMetrics.kickTap)],
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
      HomeEditGestureState.idle || HomeEditGestureState.done || HomeEditGestureState.passive => const [],
    };
    return _finish(body);
  }

  List<HomeEditGestureAction> _cancel(int t) {
    final List<HomeEditGestureAction> body = switch (_state) {
      HomeEditGestureState.pressing => [..._releaseCharge(), const HomeEditAbandon()],
      HomeEditGestureState.lifted => const [HomeEditDropLift(0)],
      HomeEditGestureState.carry => [HomeEditCarryRelease(cancelled: true, t: t)],
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

  List<HomeEditGestureAction> _timer(HomeEditTimer kind) {
    final before = _timers.length;
    _timers.removeWhere((t) => t.kind == kind);
    if (_timers.length == before) return const [];
    final section = _section;
    switch (kind) {
      case HomeEditTimer.halfT:
        if (_state != HomeEditGestureState.pressing || section == null) return const [];
        _charging = true;
        return [HomeEditStartCharge(section, _local)];
      case HomeEditTimer.longPress:
        return _state == HomeEditGestureState.pressing ? _threshold(section) : const [];
      case HomeEditTimer.bodyHold:
        if (_state != HomeEditGestureState.holding || section == null) return const [];
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

  List<HomeEditGestureAction> _regrabbed() {
    if (_state == HomeEditGestureState.done && _awaitingRegrab) _state = HomeEditGestureState.carry;
    _awaitingRegrab = false;
    return const [];
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
  }
}

// ── Recognizer ────────────────────────────────────────────────────────────

/// Applies one batch of machine actions (Home's executor). [machine] answers
/// a re-grab in place.
typedef HomeEditGestureRunner = void Function(List<HomeEditGestureAction> actions, HomeEditGestureMachine machine);

/// Home edit mode's detector over the whole feed, margins included (spec
/// §2.1.2): one [HomeEditGestureMachine] per finger, driven from the
/// pointer router. Compose reads the Initial pass; here the recognizer
/// joins the arena undecided and only claims it when the machine consumes
/// (a lift, a carry, a blank tap, a busy press). A press the machine gives
/// up (a scroll, a tap on a card, a tap on a plate) is rejected so the page
/// gets it; an edit-mode press given up is still watched for its up.
class HomeEditGestureRecognizer extends OneSequenceGestureRecognizer {
  HomeEditGestureRecognizer({super.debugOwner})
    : super(allowedButtonsFilter: (buttons) => buttons == kPrimaryButton || buttons == kSecondaryButton);

  /// The press at a page position; called once, at pointer-down.
  HomeEditHit Function(Offset pagePosition)? hitTest;

  /// Global → page px.
  Offset Function(Offset global)? toPage;
  bool Function()? editing;
  bool Function()? carryBusy;
  bool Function()? flingInProgress;
  HomeEditGestureRunner? run;

  /// T and the touch slop.
  int longPressMs = kLongPressTimeout.inMilliseconds;
  double slop = kTouchSlop;

  HomeEditGestureMachine? _machine;
  int? _primary;
  final Set<int> _others = {};
  bool _accepted = false;
  bool _rejected = false;
  int _now = 0;
  Timer? _timer;
  HomeEditTimerAt? _timerFor;

  /// A finger the machine owns or watches.
  bool get active => _machine != null;

  static int _ms(Duration d) => d.inMilliseconds;

  @override
  void addAllowedPointer(PointerDownEvent event) {
    final machine = _machine;
    if (machine != null) {
      // Another finger: ignored, but it must not scroll the list.
      if (machine.handle(const HomeEditOtherPointer()).any((a) => a is HomeEditConsumeOther)) {
        _others.add(event.pointer);
        startTrackingPointer(event.pointer, event.transform);
        resolvePointer(event.pointer, GestureDisposition.accepted);
      }
      return;
    }
    final hitTest = this.hitTest, toPage = this.toPage, run = this.run;
    if (hitTest == null || toPage == null || run == null) return;
    final position = toPage(event.position);
    final m = HomeEditGestureMachine(longPressMs: longPressMs, slopPx: slop);
    _now = _ms(event.timeStamp);
    _accepted = false;
    _rejected = false;
    final mouse = event.kind == PointerDeviceKind.mouse;
    final actions = m.handle(
      HomeEditDown(
        hit: hitTest(position),
        position: position,
        editing: editing?.call() ?? false,
        carryBusy: carryBusy?.call() ?? false,
        flingInProgress: flingInProgress?.call() ?? false,
        t: _now,
        secondaryClick: mouse && (event.buttons & kSecondaryButton) != 0 && (event.buttons & kPrimaryButton) == 0,
      ),
    );
    if (m.state == HomeEditGestureState.idle) {
      // Excluded, or nothing to watch: never in the arena.
      run(actions, m);
      return;
    }
    _machine = m;
    _primary = event.pointer;
    startTrackingPointer(event.pointer, event.transform);
    _apply(actions, m);
    // A drag handle owns its press: the list must not take its slop first.
    if (m.state == HomeEditGestureState.handleDown) _accept();
    _schedule();
  }

  @override
  void handleEvent(PointerEvent event) {
    if (event.pointer != _primary) {
      if (_others.contains(event.pointer) && (event is PointerUpEvent || event is PointerCancelEvent)) {
        _others.remove(event.pointer);
        stopTrackingPointer(event.pointer);
      }
      return;
    }
    final m = _machine;
    if (m == null) return;
    _now = math.max(_now, _ms(event.timeStamp));
    _checkOutside(m);
    final t = _ms(event.timeStamp);
    if (event is PointerMoveEvent) {
      _apply(m.handle(HomeEditMove(toPage!(event.position), t)), m);
    } else if (event is PointerUpEvent) {
      _apply(m.handle(HomeEditUp(t)), m, up: true);
    } else if (event is PointerCancelEvent) {
      _apply(m.handle(HomeEditCancel(t)), m, up: true);
    }
    _afterStep(m);
  }

  // Edit mode can end, or an exit drop the carry, while the finger rests. A carry that
  // never started or was torn down stays: the engine's release puts a lone lift down.
  void _checkOutside(HomeEditGestureMachine m) {
    if (!(editing?.call() ?? false)) _apply(m.handle(const HomeEditEditEnded()), m);
    if (m.state == HomeEditGestureState.carry && (carryBusy?.call() ?? false)) {
      _apply(m.handle(const HomeEditCarryEnded()), m);
    }
  }

  void _apply(List<HomeEditGestureAction> actions, HomeEditGestureMachine m, {bool up = false}) {
    if (actions.isEmpty) return;
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

  void _schedule() {
    final next = _machine?.pendingTimer;
    if (next == null) {
      _timer?.cancel();
      _timer = null;
      _timerFor = null;
      return;
    }
    if (_timer != null && identical(_timerFor, next)) return;
    _timer?.cancel();
    _timerFor = next;
    _timer = Timer(Duration(milliseconds: math.max(0, next.atMs - _now)), () {
      _timer = null;
      _timerFor = null;
      final m = _machine;
      if (m == null) return;
      _checkOutside(m);
      _now = math.max(_now, next.atMs);
      _apply(m.handle(HomeEditTimerFired(next.kind)), m);
      _afterStep(m);
    });
  }

  void _end() {
    _timer?.cancel();
    _timer = null;
    _timerFor = null;
    final pointer = _primary;
    _machine = null;
    _primary = null;
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
    if (m == null) return;
    // The list (or a card) took the finger first: what the machine's own slop
    // would have done — normal mode lets go, an edit-mode press is watched to its up.
    switch (m.state) {
      case HomeEditGestureState.pressing || HomeEditGestureState.holding || HomeEditGestureState.blank:
        final far = Offset(slop * 2 + 1, 0);
        final down = m._downAt;
        run?.call(m.handle(HomeEditMove(down + far, _now)), m);
      case HomeEditGestureState.handleDown || HomeEditGestureState.lifted || HomeEditGestureState.carry:
        run?.call(m.handle(HomeEditCancel(_now)), m);
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
    if (m != null && m.state != HomeEditGestureState.idle) run?.call(m.handle(HomeEditCancel(_now)), m);
    _timer?.cancel();
    _machine = null;
    _primary = null;
    super.dispose();
  }

  @override
  String get debugDescription => 'home edit gestures';
}
