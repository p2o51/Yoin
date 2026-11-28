/// The in-place drag's decisions (owner 2026-11-22; Android PR #292), a Dart port of `:shared`
/// HomeDragCore.kt for parity phase 4: which arrangement the finger asks for frame by frame (a 12dp travel
/// gate after each change, decided only on a layout that has caught up), the edge scroll and its start
/// guard, no pairing while the edge scroll moves the rows, the re-decide on release against what is laid
/// out, and the rollback. The platform keeps the lift, the glide, the kicks, the haptics and the list
/// itself: each frame it hands the core what is laid out ([HomeDragView]) and applies what it answers
/// ([HomeDragFrame]). Replayed against the timelines in home-drag.json.
library;

import 'dart:math' as math;

import 'home_catalog.dart';
import 'home_drag_rules.dart';
import 'home_edit_rules.dart';

/// A laid-out row item, keyed by its first section, and its vertical extent in px (the list's coordinates).
final class DragItemBand {
  const DragItemBand(this.key, this.top, this.bottom);

  final HomeSection key;
  final double top;
  final double bottom;
}

/// What the list shows on this frame: the row items laid out, top to bottom ([visible]), the content's
/// left edge and width, the area whose ends scroll a held block ([areaTop], [areaBottom]), and whether the
/// rows are in two columns.
final class HomeDragView {
  const HomeDragView({
    required this.visible,
    required this.contentLeft,
    required this.contentWidth,
    required this.areaTop,
    required this.areaBottom,
    required this.twoColumn,
  });

  final List<DragItemBand> visible;
  final double contentLeft;
  final double contentWidth;
  final double areaTop;
  final double areaBottom;
  final bool twoColumn;
}

/// What a frame asks of the platform.
sealed class HomeDragFrame {
  const HomeDragFrame();
}

/// Nothing this frame.
final class HomeDragFrameNone extends HomeDragFrame {
  const HomeDragFrameNone();
}

/// The drag moved to [arrangement], the dragged block now on [side] (null: a row of its own). Lay it out,
/// keep the list where it is and tick. A frame that steps doesn't scroll.
final class HomeDragFrameStep extends HomeDragFrame {
  const HomeDragFrameStep(this.arrangement, this.side);

  final DragArrangement arrangement;
  final HomeDragLane? side;
}

/// Scroll the list by [px] (+ = toward the end), then report what it consumed ([HomeDragCore.scrolled]).
final class HomeDragFrameScroll extends HomeDragFrame {
  const HomeDragFrameScroll(this.px);

  final double px;
}

/// The drag's decision state and reducer. One drag at a time: [start] it, feed every finger move ([move])
/// and every frame ([frame], then [scrolled] after a [HomeDragFrameScroll]), tell it when the dragged block
/// was placed in the live arrangement ([placed]), and end it with [release] (or drop it with [end]).
/// Lengths are px; [density] is px per dp.
class HomeDragCore {
  HomeDragCore(double density)
    : _travelPx = HomeDragTokens.changeTravel * density,
      _edgePx = HomeDragTokens.autoScrollEdge * density,
      _minSpeedPx = HomeDragTokens.autoScrollMin * density,
      _maxSpeedPx = HomeDragTokens.autoScrollMax * density,
      _pushPx = HomeDragTokens.edgeStartPush * density,
      _bufferPx = HomeDragRuleTokens.rowBuffer * density;

  final double _travelPx;
  final double _edgePx;
  final double _minSpeedPx;
  final double _maxSpeedPx;
  final double _pushPx;
  final double _bufferPx;

  /// The block being dragged; null between drags.
  HomeSection? get dragging => _dragging;
  HomeSection? _dragging;

  /// The live arrangement while dragging; null between drags.
  DragArrangement? get arrangement => _arrangement;
  DragArrangement? _arrangement;

  /// The dragged block's side in a pair; null when it is a row of its own.
  HomeDragLane? get side => _side;
  HomeDragLane? _side;

  /// The arrangement before the lift: a rollback brings it back.
  DragArrangement? get origin => _origin;
  DragArrangement? _origin;

  /// The finger, px.
  double get fingerX => _fingerX;
  double _fingerX = 0;
  double get fingerY => _fingerY;
  double _fingerY = 0;

  /// The finger's offset from the dragged block's top-left at the start.
  double get grabX => _grabX;
  double _grabX = 0;
  double get grabY => _grabY;
  double _grabY = 0;

  // How far the finger (or the scroll under it) went since the last change.
  double _travel = 0;

  // The edge scroll moved the rows on the last frame that scrolled: no block pairs while it runs.
  bool _edgeScrolling = false;

  // The start-in-edge guard: the band the finger was lifted in (±1, 0 none), where, and how long it rested.
  int _edgeGuard = 0;
  double _edgeGuardY = 0;
  double _edgeGuardMs = 0;

  // The finger where the last decision was made (NaN: none yet), the arrangement (and side) before the last
  // change, and the arrangement the dragged block was last placed in (the one the list laid out).
  double _decidedX = double.nan;
  double _decidedY = double.nan;
  (DragArrangement, HomeDragLane?)? _beforeStep;
  DragArrangement? _laidOut;

  (DragArrangement, bool)? _rowsFor;
  List<HomeFeedRow> _rowsCache = const [];

  /// [arrangement]'s rows, cached for the last arrangement asked.
  List<HomeFeedRow> rowsOf(DragArrangement arrangement, bool twoColumn) {
    final key = _rowsFor;
    if (key == null || key.$1 != arrangement || key.$2 != twoColumn) {
      _rowsCache = dragRows(arrangement.ids, arrangement.pairs, twoColumn);
      _rowsFor = (arrangement, twoColumn);
    }
    return _rowsCache;
  }

  /// Starts dragging [dragged] in [before] (the enabled order and, in [twoColumn], the pairs laid out side
  /// by side) with the finger at ([fingerX], [fingerY]) and the block drawn with its top-left at
  /// ([blockLeft], [blockTop]). A half starts beside its partner. A finger already in an edge band of
  /// [areaTop]..[areaBottom] arms the start guard.
  void start({
    required HomeSection dragged,
    required DragArrangement before,
    required bool twoColumn,
    required double fingerX,
    required double fingerY,
    required double blockLeft,
    required double blockTop,
    required double areaTop,
    required double areaBottom,
  }) {
    final lane = dragLaneOf(dragged, rowsOf(before, twoColumn));
    _fingerX = fingerX;
    _fingerY = fingerY;
    _grabX = fingerX - blockLeft;
    _grabY = fingerY - blockTop;
    _decidedX = double.nan;
    _decidedY = double.nan;
    _beforeStep = null;
    _laidOut = null;
    _side = lane == HomeDragLane.full ? null : lane;
    _origin = before;
    _travel = 0;
    _edgeScrolling = false;
    _edgeGuard = dragEdgeOf(fingerY, areaTop, areaBottom, dragEdgeBand(_edgePx, areaTop, areaBottom));
    _edgeGuardY = fingerY;
    _edgeGuardMs = 0;
    _arrangement = before;
    _dragging = dragged;
  }

  /// The finger moved to ([x], [y]); a NaN [x] keeps the last x. The next frame decides on it.
  void move(double x, double y) {
    if (_dragging == null) return;
    final nx = x.isNaN ? _fingerX : x;
    final dx = nx - _fingerX;
    final dy = y - _fingerY;
    _travel += math.sqrt(dx * dx + dy * dy);
    _fingerX = nx;
    _fingerY = y;
  }

  /// The dragged block was placed in the live arrangement (the list laid it out).
  void placed() {
    _laidOut = _arrangement;
  }

  /// One frame, [dtSeconds] after the last ([HomeDragTokens.maxFrameSeconds] at most; 0 on the first),
  /// against the layout of the frame before ([view]): one decision, or else the edge scroll.
  HomeDragFrame frame(double dtSeconds, HomeDragView view) {
    final dragged = _dragging;
    final live = _arrangement;
    if (dragged == null || live == null) return const HomeDragFrameNone();
    final rows = rowsOf(live, view.twoColumn);
    final k = dragRowIndexOf(dragged, rows);
    if (k < 0) return const HomeDragFrameNone();
    if (_travel >= _travelPx && dragLayoutFollows([for (final b in view.visible) b.key], rows, rows[k].first)) {
      final step = _decide(dragged, live, _side, rows, k, view);
      _decidedX = _fingerX;
      _decidedY = _fingerY;
      if (step != null) {
        _beforeStep = (live, _side);
        _arrangement = step.arrangement;
        _side = step.side;
        _travel = 0;
        return HomeDragFrameStep(step.arrangement, step.side);
      }
    }
    final edge = dragEdgeBand(_edgePx, view.areaTop, view.areaBottom);
    if (_edgeGuard != 0) {
      _edgeGuardMs += dtSeconds * 1000;
      final inBand = dragEdgeOf(_fingerY, view.areaTop, view.areaBottom, edge) == _edgeGuard;
      final holds = dragEdgeGuardHolds(
        _edgeGuard,
        _edgeGuardY,
        _fingerY,
        _edgeGuardMs,
        _pushPx,
        HomeDragTokens.edgeStartDwellMs,
      );
      if (!inBand || !holds) {
        _edgeGuard = 0;
      } else {
        _edgeScrolling = false;
        return const HomeDragFrameNone();
      }
    }
    final speed = dragAutoScrollSpeed(_fingerY, view.areaTop, view.areaBottom, edge, _minSpeedPx, _maxSpeedPx);
    if (speed == 0) {
      _edgeScrolling = false;
    } else if (dtSeconds > 0) {
      return HomeDragFrameScroll(speed * dtSeconds);
    }
    return const HomeDragFrameNone();
  }

  /// The list consumed [consumedPx] of a [HomeDragFrameScroll]: travel for the next change; at the list's
  /// end nothing moved.
  void scrolled(double consumedPx) {
    final moved = consumedPx.abs();
    _travel += moved;
    // At the list's end nothing moves: the finger pairs there as anywhere.
    _edgeScrolling = moved > 0;
  }

  /// The finger lifted. Not [cancelled]: its last moves, since the last frame decided, decide now against
  /// the layout the list shows ([view]) with no travel wait. Returns the arrangement to write when it
  /// differs from [origin] (null: nothing to write, the order goes back to how it was). The drag stays
  /// readable until [end].
  DragArrangement? release({required bool cancelled, required HomeDragView view}) {
    if (_dragging == null) return null;
    if (!cancelled) _decideOnRelease(view);
    final a = _arrangement;
    return a != null && !cancelled && a != _origin ? a : null;
  }

  /// An exit arrived mid-drag ([dragExitKeepsDrop]): Done and the app's own exits write the live
  /// arrangement when it differs from [origin]; Back and a blank tap roll back (null). Rotation, a new
  /// column count or a resize roll back too (null, as a cancelled [release]).
  DragArrangement? exit(HomeEditExitReason reason) {
    final a = _arrangement;
    return a != null && _dragging != null && dragExitKeepsDrop(reason) && a != _origin ? a : null;
  }

  /// The drag is over: every decision value back at rest.
  void end() {
    _dragging = null;
    _arrangement = null;
    _side = null;
  }

  // A quick drag lets go within a frame or two of its last moves: decide where the finger let go.
  void _decideOnRelease(HomeDragView view) {
    final dragged = _dragging;
    final live = _arrangement;
    if (dragged == null || live == null) return;
    if (_fingerX == _decidedX && _fingerY == _decidedY) return;
    final prior = _beforeStep;
    final laidOut = _laidOut;
    final (base, baseSide) = prior != null && laidOut != null && laidOut != live && laidOut == prior.$1
        ? prior
        : (live, _side);
    final rows = rowsOf(base, view.twoColumn);
    final k = dragRowIndexOf(dragged, rows);
    if (k < 0) return;
    if (!dragLayoutFollows([for (final b in view.visible) b.key], rows, rows[k].first)) return;
    final step = _decide(dragged, base, baseSide, rows, k, view);
    _arrangement = step?.arrangement ?? base;
    _side = step != null ? step.side : baseSide;
    _decidedX = _fingerX;
    _decidedY = _fingerY;
  }

  DragSideStepArrange? _decide(
    HomeSection dragged,
    DragArrangement live,
    HomeDragLane? side,
    List<HomeFeedRow> rows,
    int k,
    HomeDragView view,
  ) {
    final bands = <DragRowBand>[];
    for (final item in view.visible) {
      for (final row in rows) {
        if (row.first == item.key) {
          bands.add(DragRowBand(row, item.top, item.bottom));
          break;
        }
      }
    }
    if (view.twoColumn) {
      final rel = dragRel(_fingerX, view.contentLeft, view.contentWidth);
      // While the edge scroll carries rows under the finger, it pairs with none of them.
      final step = dragSideStep(
        ids: live.ids,
        pairs: live.pairs,
        dragged: dragged,
        side: side,
        rel: rel,
        fingerY: _fingerY,
        bands: bands,
        bufferPx: _bufferPx,
        pairing: !_edgeScrolling,
      );
      switch (step) {
        case DragSideStepArrange():
          return step.arrangement != live || step.side != side ? step : null;
        case DragSideStepStay():
          return null;
        case DragSideStepVertical():
          break;
      }
    }
    DragRowBand? bandOf(HomeFeedRow row) {
      for (final b in bands) {
        if (b.row == row) return b;
      }
      return null;
    }

    final slot = bandOf(rows[k]);
    if (slot == null) return null;
    final prev = k - 1 >= 0 ? bandOf(rows[k - 1]) : null;
    final next = k + 1 < rows.length ? bandOf(rows[k + 1]) : null;
    final dir = dragSwapDir(_fingerY - _grabY, slot, prev, next);
    if (dir == 0) return null;
    final ids = dragSwapRow(live.ids, live.pairs, dragged, dir, view.twoColumn);
    return DragSideStepArrange(DragArrangement(ids, live.pairs), null);
  }
}
