/// Home edit mode's lift, in-place drag and compact list (Android ui/home/edit HomeDragEngine.kt and
/// HomeEditList.kt's HomeEditListMode; owner 2026-11-22, design.md §主页 › 就地编辑 › 排序 / 捏合列表).
///
/// A drag reorders the live [HomeDragEngine.arrangement] the feed (or the pinched list) renders while the
/// finger moves: the dragged block follows the finger in its own layer, every other block makes way on the
/// spatial spring as its slot changes, and a finger near the top or bottom scrolls the list. The drop writes
/// the arrangement once (one Undo step, the confirm haptic) and the block glides from where it was let go
/// into its slot, then it and the blocks it moved are kicked. Back, a resize, a new width class or tabletop,
/// and a system cancel roll the order back to before the lift; Done keeps the drop.
///
/// Every decision — which arrangement the finger asks for, the 12dp travel gate, the edge scroll and its start
/// guard, the re-decide on release, the rollback — is the shared core's ([HomeDragCore], replayed against
/// home-drag.json), driven here frame by frame from a [Ticker]. This engine keeps the springs, the list host
/// and the frame loop. In two columns (the host's [HomeDragHost.twoColumn]) the core decides with rules ①–④
/// (beside placement) on the rows the host lays out, and a drop writes the order with its pairs.
library;

import 'package:flutter/rendering.dart' show SliverMultiBoxAdaptorParentData;
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../data/home/home_drag_core.dart';
import '../data/home/home_drag_rules.dart';
import '../data/home/home_edit_rules.dart';
import '../nowplaying/haptics.dart';
import '../theme/tokens.dart';
import 'home_edit_lift.dart';
import 'home_edit_wiggle.dart';
import 'home_metrics.dart';
import 'home_section.dart';

/// [box] has a place on screen this frame: attached, sized, and no list item above it waiting for its
/// place after a reorder (a sliver child whose layout offset is gone would throw on the way to global).
bool homeBoxPlaced(RenderBox? box) {
  if (box == null || !box.attached || !box.hasSize) return false;
  for (RenderObject? node = box; node != null; node = node.parent) {
    final data = node.parentData;
    if (data is SliverMultiBoxAdaptorParentData && data.layoutOffset == null) return false;
  }
  return true;
}

/// A list of section rows a block can be dragged in: the feed, or the pinched list. Page px.
abstract interface class HomeDragHost {
  /// Enabled sections in the draft's order that this list draws, from data (never from what is built).
  List<HomeSection> order();

  /// The row items laid out now, top to bottom, in page px (their gap above left out).
  List<DragItemBand> visibleRows();

  /// The whole extent a section's item takes in the list (its gap included); null when it isn't laid out.
  double? extentOf(HomeSection section);

  double contentLeft();

  double contentWidth();

  /// Where the list shows rows, between the status tide and the bar: its ends scroll a held block.
  (double, double) scrollArea();

  /// Scrolls by [px] (+ = toward the end); returns what the list consumed.
  double scrollBy(double px);

  /// The pinched list's rows, not the feed's.
  bool get isList;

  /// The rows are two columns: halves side by side ([pairs]), the bands keyed by each row's first section.
  bool get twoColumn;

  /// The pairs laid out side by side now, left → right (empty in one column).
  Map<HomeSection, HomeSection> pairs();

  /// Where [section]'s slot draws its content's top-left, page px; null when it isn't laid out.
  Offset? slotOf(HomeSection section);

  /// Each section's slot origin, relative to the list's, when [arrangement] is laid out: the other blocks make
  /// way from where they were to there.
  Map<HomeSection, Offset> placesOf(DragArrangement arrangement);
}

/// One column's places: every item at the sum of the extents above it.
Map<HomeSection, Offset> homeStackPlaces(List<HomeSection> order, double? Function(HomeSection) extentOf) {
  var y = 0.0;
  final out = <HomeSection, Offset>{};
  for (final s in order) {
    out[s] = Offset(0, y);
    y += extentOf(s) ?? 0;
  }
  return out;
}

/// Feed ⇄ list (HomeEditListMode): [progress] follows the pinch, then springs to where the pinch lands
/// ([on]). Draw and layer read [progress]; the hit test reads [on].
class HomeEditListMode extends ChangeNotifier {
  HomeEditListMode({required TickerProvider vsync, required this.specs}) : progress = HomeEditSpring(vsync) {
    progress.listenable.addListener(_onProgress);
  }

  /// The springs (reduced motion swaps them).
  HomeEditSpecs Function() specs;

  /// 0: the feed; 1: the list over it.
  final HomeEditSpring progress;

  /// Where the list is going: shown (a pinch in landed) or not. Presses go to the list while on.
  bool get on => _on;
  bool _on = false;

  /// The list is built: on, or on its way in or out.
  bool get shown => _on || progress.value > 0;

  /// The list fully covers the feed.
  bool get covers => progress.value >= 1;

  /// A pinch is following the fingers.
  bool get pinching => _pinching;

  bool _pinching = false;
  bool _pinchFrom = false;
  bool _armed = false;
  bool _wasShown = false;
  bool _wasCovering = false;

  void _onProgress() {
    final shown = this.shown, covering = covers;
    if (shown == _wasShown && covering == _wasCovering) return;
    _wasShown = shown;
    _wasCovering = covering;
    notifyListeners();
  }

  /// Two fingers went down in edit mode.
  void pinchStart() {
    _pinching = true;
    _pinchFrom = _on;
    _armed = false;
    notifyListeners();
  }

  /// The fingers are at [scale] of their starting span: the list follows them, a tick where letting go would land.
  void pinchBy(double scale) {
    if (!_pinching) return;
    final p = homeListPinchProgress(_pinchFrom, scale);
    final lands = homeListSettlesOn(_pinchFrom, p) != _pinchFrom;
    if (lands != _armed) {
      _armed = lands;
      YoinHaptics.segmentTick();
    }
    progress.snapTo(p);
  }

  /// A finger lifted: the list lands where the pinch left it.
  void pinchEnd() {
    if (!_pinching) return;
    _pinching = false;
    set(homeListSettlesOn(_pinchFrom, progress.value));
  }

  /// Springs to the list ([value]) or the feed.
  void set(bool value) {
    _on = value;
    notifyListeners();
    progress.animateTo(value ? 1 : 0, specs().fold, HomeCarryTokens.unitThreshold);
  }

  /// The header's one-hand switch: list ⇄ full layout, staying in edit mode. Ignored mid-pinch.
  void toggle() {
    if (_pinching) return;
    YoinHaptics.click();
    set(!_on);
  }

  /// Edit mode is ending: back to the feed alongside it.
  void close() {
    _pinching = false;
    if (_on || progress.value > 0) set(false);
  }

  /// Back to the feed now.
  void snapOff() {
    _pinching = false;
    _on = false;
    progress.snapTo(0);
    notifyListeners();
  }

  @override
  void dispose() {
    progress.listenable.removeListener(_onProgress);
    progress.dispose();
    super.dispose();
  }
}

/// Lift and the in-place drag (HomeDragEngine). Lift and its tint are one shared pair, so only one block is
/// ever lifted. Notifies when a block is lifted or let go, when a drag starts, steps, ends or settles, and
/// when the list comes or goes; [paint] ticks for everything the dragged block and its neighbours draw.
class HomeDragEngine extends ChangeNotifier {
  HomeDragEngine({
    required TickerProvider vsync,
    required this.specs,
    required this.wiggle,
    required this.commitArrangement,
    required this.finishDeferredExit,
  }) : lift = HomeEditSpring(vsync),
       liftTint = HomeEditSpring(vsync),
       _settleProgress = HomeEditSpring(vsync),
       _makeWay = {for (final s in HomeSection.values) s: HomeEditSpring(vsync)},
       _makeWayX = {for (final s in HomeSection.values) s: HomeEditSpring(vsync)},
       list = HomeEditListMode(vsync: vsync, specs: specs) {
    _ticker = vsync.createTicker(_onTick);
    _paint = Listenable.merge([
      _fingerTick,
      lift.listenable,
      liftTint.listenable,
      _settleProgress.listenable,
      for (final o in _makeWay.values) o.listenable,
      for (final o in _makeWayX.values) o.listenable,
    ]);
    list.addListener(notifyListeners);
  }

  /// The springs (reduced motion swaps them).
  HomeEditSpecs Function() specs;
  final HomeEditWiggle wiggle;

  /// Writes a drop (one Undo step, the confirm haptic): the order and, in [twoColumn], its pairs; false when
  /// it changed nothing.
  final bool Function(DragArrangement arrangement, {required bool twoColumn}) commitArrangement;

  /// An exit that waited for the drag to let go.
  final void Function(HomeEditExitReason reason) finishDeferredExit;

  late final Ticker _ticker;
  final ValueNotifier<int> _fingerTick = ValueNotifier(0);
  late final Listenable _paint;

  /// Everything the dragged block, its slot and its neighbours draw.
  Listenable get paint => _paint;

  // ── Lift (§2.3 step 5, §2.4) ──────────────────────────────────────────

  /// Scale 1 + .02·lift and a 6dp shadow on the lifted block.
  final HomeEditSpring lift;

  /// Plate colour toward surfaceContainerHighest.
  final HomeEditSpring liftTint;

  HomeSection? get liftSection => _liftSection;
  HomeSection? _liftSection;

  /// The press point in block-content px: the lift's pivot.
  Offset get liftOrigin => _liftOrigin;
  Offset _liftOrigin = Offset.zero;

  /// A rows or count handle is being dragged: lifts and drags wait for it. Set by Home.
  bool Function() resizeActive = _never;
  static bool _never() => false;

  /// Picks [section] up at [origin]: its kicks stop, a block still lifted elsewhere is let go at once.
  void liftBlock(HomeSection section, Offset origin) {
    if (resizeActive()) return;
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
      if (done && _liftSection == section && _dragging == null) _letGoLift();
    });
    liftTint.animateTo(0, specs().liftTint, HomeCarryTokens.unitThreshold);
    if (kick > 0) wiggle.impulse(section, kick);
  }

  void _letGoLift() {
    _liftSection = null;
    notifyListeners();
  }

  void _snapLiftToRest() {
    lift.snapTo(0);
    liftTint.snapTo(0);
    if (_liftSection != null) _letGoLift();
  }

  // ── Exits (port sheet §5.5, critique 4) ───────────────────────────────

  /// Edit mode's animated exit: a block still lifted drops where it is with no kick, the press lets go and
  /// the list folds back into the feed.
  void onExit(HomeEditPressState press) {
    dropLift(0);
    press.release(specs());
    list.close();
  }

  /// A snap exit: lift, press and list at rest now.
  void onSnapExit(HomeEditPressState press) {
    _snapLiftToRest();
    press.snapOff();
    list.snapOff();
  }

  // ── The pinched list ──────────────────────────────────────────────────

  /// Edit mode's compact list: a pinch in shows it over the feed, a pinch out folds it back.
  final HomeEditListMode list;

  /// The header's switch between the list and the full layout: ignored while a block is lifted or dragged.
  void toggleList() {
    if (_dragging != null || _liftSection != null || resizeActive()) return;
    list.toggle();
  }

  // ── Drag ──────────────────────────────────────────────────────────────

  /// The feed's rows. Set by Home while built.
  HomeDragHost? host;

  /// The pinched list's rows. Set by the list while built.
  HomeDragHost? listHost;

  /// The block being dragged; null between drags.
  HomeSection? get dragging => _dragging;
  HomeSection? _dragging;

  /// The live arrangement while dragging, rendered in place of the draft's; null between drags.
  DragArrangement? get arrangement => _arrangement;
  DragArrangement? _arrangement;

  /// The dropped block gliding from where it was let go into its slot.
  HomeSection? get settling => _settling;
  HomeSection? _settling;
  final HomeEditSpring _settleProgress;
  Offset? _settleFrom;

  /// The drag (or the glide) runs in the pinched list, not the feed.
  bool get activeList => _activeList;
  bool _activeList = false;

  /// The finger's x in page px, set by the gesture before each event.
  double fingerX = double.nan;

  /// A drag was let go from outside (an exit, a resize) while its finger is still down: the gesture
  /// swallows the rest of it. Cleared by the next drag.
  bool get letGo => _letGo;
  bool _letGo = false;

  /// A drag is on: Undo and the other edits wait for its drop.
  bool get isCarrying => _dragging != null;

  /// The block that draws over every other one: lifted, dragged, or gliding into its slot.
  HomeSection? get onTopSection => _dragging ?? _settling ?? _liftSection;

  final HomeDragCore _core = HomeDragCore(1);
  HomeDragHost? _active;
  Offset _finger = Offset.zero;
  Offset? _lastDrawn;
  HomeEditExitReason? _pendingExit;
  Duration? _lastTick;

  // The other blocks making way: each one's offset from where the list lays it out, springing to 0 (y, and x
  // for a block changing sides in two columns).
  final Map<HomeSection, HomeEditSpring> _makeWay;
  final Map<HomeSection, HomeEditSpring> _makeWayX;

  /// [section]'s offset from its laid-out place while it makes way ([inList]: the list's strip asks).
  double makeWay(HomeSection section, {required bool inList}) => inList == _activeList ? _makeWay[section]!.value : 0;

  /// [section]'s sideways offset from its laid-out place while it makes way (two columns).
  double makeWayX(HomeSection section, {required bool inList}) =>
      inList == _activeList ? _makeWayX[section]!.value : 0;

  /// The drag runs in two columns.
  bool get twoColumn => _twoColumn;
  bool _twoColumn = false;

  /// [section]'s slot shows the drop frame: it is being dragged, or gliding back into the slot. Draw only.
  bool slotShown(HomeSection section, {required bool inList}) =>
      (_dragging == section || _settling == section) && inList == _activeList;

  /// Where the dragged (or gliding) block's top-left is drawn now, page px; null when there is none.
  Offset? drawnTopLeft(HomeSection section, {required bool inList}) {
    if (inList != _activeList) return null;
    final host = _active;
    if (host == null) return null;
    if (_dragging == section) {
      final at = Offset(_finger.dx - _core.grabX, _finger.dy - _core.grabY);
      _lastDrawn = at;
      return at;
    }
    if (_settling == section) {
      final from = _settleFrom;
      final slot = _slotTopLeft(host, section);
      if (from == null || slot == null) return null;
      return Offset.lerp(from, slot, _settleProgress.value.clamp(0.0, 1.0));
    }
    return null;
  }

  Offset? _slotTopLeft(HomeDragHost host, HomeSection section) => host.slotOf(section);

  HomeDragView _viewOf(HomeDragHost host) {
    final (top, bottom) = host.scrollArea();
    return HomeDragView(
      visible: host.visibleRows(),
      contentLeft: host.contentLeft(),
      contentWidth: host.contentWidth(),
      areaTop: top,
      areaBottom: bottom,
      twoColumn: _twoColumn,
    );
  }

  /// Starts dragging the lifted block with the finger at ([fingerX], [fingerY]).
  void start(double fingerY) {
    // A drag that doesn't start stays the gesture's: its release puts the lift down.
    _letGo = false;
    final dragged = _liftSection;
    if (dragged == null || _dragging != null || resizeActive()) return;
    final host = list.on ? listHost : this.host;
    if (host == null) return;
    final ids = host.order();
    if (!ids.contains(dragged)) return;
    final twoColumn = host.twoColumn;
    final before = DragArrangement(ids, twoColumn ? host.pairs() : const {});
    final at = _slotTopLeft(host, dragged);
    if (at == null) return;
    final x = fingerX.isNaN ? at.dx : fingerX;
    // Picked up again mid-glide: from where it is drawn on its way.
    final shown = _settling == dragged ? (drawnTopLeft(dragged, inList: host.isList) ?? at) : at;
    _settling = null;
    _settleFrom = null;
    _active = host;
    _activeList = host.isList;
    _twoColumn = twoColumn;
    _finger = Offset(x, fingerY);
    _lastDrawn = null;
    _pendingExit = null;
    final (top, bottom) = host.scrollArea();
    _core.start(
      dragged: dragged,
      before: before,
      twoColumn: twoColumn,
      fingerX: x,
      fingerY: fingerY,
      blockLeft: shown.dx,
      blockTop: shown.dy,
      areaTop: top,
      areaBottom: bottom,
    );
    _arrangement = before;
    _dragging = dragged;
    wiggle.carrying = true;
    _lastTick = null;
    if (!_ticker.isActive) _ticker.start();
    notifyListeners();
  }

  /// The finger moved to [y] (and [fingerX]). The next frame decides on it.
  void move(double y) {
    if (_dragging == null) return;
    _core.move(fingerX, y);
    _finger = Offset(_core.fingerX, _core.fingerY);
    _fingerTick.value++;
  }

  /// The finger lifted: a changed arrangement is written now, and the block glides from where it was let go
  /// into its slot as the lift falls. [cancelled] (the system took the finger): nothing is written, the order
  /// goes back to before the lift. A drag that never started puts the lift down.
  void release({required bool cancelled}) {
    if (_dragging == null) {
      dropLift(0);
      return;
    }
    final host = _active;
    if (!cancelled && host != null) {
      _core.release(cancelled: false, view: _viewOf(host));
      _arrangement = _core.arrangement;
    }
    _finish(keep: !cancelled, glide: true);
  }

  /// A resize, a new width class or tabletop mid-drag (rule 5): the drag's geometry is gone, so it rolls
  /// back to before the lift, nothing written.
  void cancel() {
    if (_dragging == null) return;
    _letGo = true;
    _finish(keep: false, glide: false);
  }

  /// An exit arrived mid-drag: the drag ends at once and the exit runs right after. Done (and the app's own
  /// exits) write the live arrangement; Back rolls it back ([dragExitKeepsDrop]). False with no drag.
  bool deferExit(HomeEditExitReason reason) {
    if (_dragging == null) return false;
    _pendingExit = reason;
    _letGo = true;
    _finish(keep: dragExitKeepsDrop(reason), glide: true);
    return true;
  }

  /// A snap exit mid-drag (edit mode went at once, another account): every drag value at rest, nothing written.
  void abortNow() {
    if (_dragging != null) _letGo = true;
    _ticker.stop();
    _dragging = null;
    _arrangement = null;
    _core.end();
    _settling = null;
    _settleFrom = null;
    _pendingExit = null;
    for (final o in [..._makeWay.values, ..._makeWayX.values]) {
      o.snapTo(0);
    }
    _snapLiftToRest();
    wiggle.carrying = false;
    notifyListeners();
  }

  // Ends the drag: [keep] writes a changed arrangement, else the order before the lift comes back.
  // [glide]: the block glides from where it was let go into its slot.
  void _finish({required bool keep, required bool glide}) {
    final dragged = _dragging;
    if (dragged == null) return;
    final before = _core.origin ?? const DragArrangement([], {});
    final live = _arrangement ?? before;
    final host = _active;
    final from = glide ? (_lastDrawn ?? drawnTopLeft(dragged, inList: _activeList)) : null;
    _ticker.stop();
    // Rolled back: the blocks the drag moved make way back to where they were.
    if (!keep && live != before && host != null) _flip(host, live, before, dragged);
    // Written before the live arrangement goes, so the list never shows the order it started from.
    final changed = keep && live != before && commitArrangement(live, twoColumn: _twoColumn);
    _dragging = null;
    _arrangement = null;
    _core.end();
    wiggle.carrying = false;
    final moved = changed
        ? [
            for (var i = 0; i < before.ids.length; i++)
              if (i >= live.ids.length || live.ids[i] != before.ids[i]) before.ids[i],
          ]
        : const <HomeSection>[];
    _settle(dragged, from, moved);
    lift.animateTo(0, specs().liftDown, HomeCarryTokens.unitThreshold).then((done) {
      if (_liftSection == dragged && _dragging == null && _settling != dragged) _letGoLift();
    });
    liftTint.animateTo(0, specs().liftTint, HomeCarryTokens.unitThreshold);
    notifyListeners();
    final exit = _pendingExit;
    if (exit != null) {
      _pendingExit = null;
      finishDeferredExit(exit);
    }
  }

  // The dropped block glides home, then it and the blocks it [moved] are kicked.
  void _settle(HomeSection section, Offset? from, List<HomeSection> moved) {
    void kicks() {
      wiggle.touch();
      wiggle.impulse(section, HomeWiggleMetrics.kickDrop);
      for (final s in moved) {
        if (s != section) wiggle.impulse(s, HomeWiggleMetrics.kickNeighbour);
      }
    }

    if (from == null) {
      _settling = null;
      _settleFrom = null;
      kicks();
      if (_liftSection == section) _letGoLift();
      return;
    }
    _settling = section;
    _settleFrom = from;
    _settleProgress.snapTo(0);
    _settleProgress.animateTo(1, specs().liftDown, HomeCarryTokens.unitThreshold).then((done) {
      if (!done || _settling != section) return;
      _settling = null;
      _settleFrom = null;
      if (_liftSection == section && _dragging == null) _liftSection = null;
      kicks();
      notifyListeners();
    });
  }

  void _onTick(Duration elapsed) {
    final last = _lastTick;
    _lastTick = elapsed;
    final dt = last == null
        ? 0.0
        : ((elapsed - last).inMicroseconds / 1e6).clamp(0.0, HomeDragTokens.maxFrameSeconds).toDouble();
    final host = _active;
    if (host == null || _dragging == null) return;
    final live = _arrangement!;
    switch (_core.frame(dt, _viewOf(host))) {
      case HomeDragFrameStep(:final arrangement):
        _flip(host, live, arrangement, _dragging!);
        _arrangement = arrangement;
        YoinHaptics.segmentTick();
        notifyListeners();
      case HomeDragFrameScroll(:final px):
        _core.scrolled(host.scrollBy(px));
        _fingerTick.value++;
      case HomeDragFrameNone():
        break;
    }
    // Once this frame has laid the live arrangement out, the dragged block is placed in it.
    SchedulerBinding.instance.addPostFrameCallback((_) {
      if (_dragging != null) _core.placed();
    });
  }

  // The other blocks make way: each one laid out somewhere new by the arrangement [from] → [to] keeps where it is
  // drawn now and springs from there to its new place (the host says where each slot lands).
  void _flip(HomeDragHost host, DragArrangement from, DragArrangement to, HomeSection dragged) {
    final before = host.placesOf(from), after = host.placesOf(to);
    for (final s in to.ids) {
      if (s == dragged) continue;
      final a = after[s], b = before[s];
      if (a == null || b == null) continue;
      final delta = a - b;
      if (delta.dy != 0) {
        final spring = _makeWay[s]!;
        spring.snapTo(spring.value - delta.dy);
        spring.animateTo(0, specs().placement, HomeCarryTokens.neighbourPxThreshold);
      }
      if (delta.dx != 0) {
        final spring = _makeWayX[s]!;
        spring.snapTo(spring.value - delta.dx);
        spring.animateTo(0, specs().placement, HomeCarryTokens.neighbourPxThreshold);
      }
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    lift.dispose();
    liftTint.dispose();
    _settleProgress.dispose();
    for (final o in [..._makeWay.values, ..._makeWayX.values]) {
      o.dispose();
    }
    list.removeListener(notifyListeners);
    list.dispose();
    _fingerTick.dispose();
    super.dispose();
  }
}
