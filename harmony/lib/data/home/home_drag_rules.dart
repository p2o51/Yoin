/// Home edit reordering (owner 2026-11-22; two-column rules by design, approved 2026-11-22), a Dart port
/// of `:shared` HomeDragRules.kt for parity phase 4: a block dragged in place, in the feed or in the
/// pinched list. The other blocks make way as its slot changes, and a finger near the top or bottom
/// scrolls. In two columns:
///  1. A half dragged within its own row (grown 16dp above and below) only changes sides, across the
///     middle with hysteresis (0.45 / 0.55); only a finger out of the row leaves the partner full.
///  2. Over another block's full row: its top and bottom quarters put the dragged block in a row of its
///     own above or below it; in its middle half the outer 30% each side pairs with it, and between them
///     the half-travel rule moves rows.
///  3. Over a pair of two others: a row of its own above or below it, by the row's middle. Never three
///     across, never splitting someone else's pair.
/// Width is the span handle's; a drag changes position and pairing only. Everything here is pure;
/// home_drag_core.dart drives it. Replayed against home-drag.json.
library;

import 'dart:math' as math;

import 'home_catalog.dart';
import 'home_edit_rules.dart';

/// Where a section sits across a two-column row.
enum HomeDragLane { full, left, right }

/// Numbers of the drop rules (dp unless noted).
abstract final class HomeDragRuleTokens {
  /// A half's own row reaches this far above and below it.
  static const double rowBuffer = 16;

  /// A right half swaps to the left once the finger is left of this fraction across the content…
  static const double swapToLeft = .45;

  /// …and a left half swaps right past this one. Between the two it stays (hysteresis).
  static const double swapToRight = .55;

  /// The top and the bottom fraction of another full row that put the dragged block in a row of its own.
  static const double rowGapZone = .25;

  /// Over a full row's middle: left of this fraction across the content pairs as [dragged | row]…
  static const double pairLeft = .30;

  /// …right of this one as [row | dragged]. Between the two the half-travel rule decides.
  static const double pairRight = .70;
}

/// Numbers of the in-place drag (dp unless noted).
abstract final class HomeDragTokens {
  /// The list scrolls while the finger is this close to the bar (or the status tide)…
  static const double autoScrollEdge = 96;

  /// …but a band never takes more than this fraction of the scroll area (a short window keeps its middle).
  static const double autoScrollEdgeMaxFraction = .25;

  /// …from this many dp/s where the band starts…
  static const double autoScrollMin = 200;

  /// …easing in (depth squared) to this many at the edge.
  static const double autoScrollMax = 1400;

  /// Lifted with the finger already in a band, it scrolls once the finger pushes this much further…
  static const double edgeStartPush = 4;

  /// …or rests there this long, ms.
  static const double edgeStartDwellMs = 120;

  /// A change waits for this much finger (or scroll) travel since the last one.
  static const double changeTravel = 12;

  /// A frame after a stall scrolls no more than this far in time, s.
  static const double maxFrameSeconds = 1 / 24;
}

/// An arrangement of the drag: the order and the pairs (left → right, each pair adjacent in [ids]).
/// Equal as Kotlin's data class: [ids] in order, [pairs] as a map (order aside).
final class DragArrangement {
  const DragArrangement(this.ids, this.pairs);

  final List<HomeSection> ids;
  final Map<HomeSection, HomeSection> pairs;

  @override
  bool operator ==(Object other) =>
      other is DragArrangement && homeListEqual(other.ids, ids) && homePairsEqual(other.pairs, pairs);

  @override
  int get hashCode =>
      Object.hash(Object.hashAll(ids), Object.hashAllUnordered(pairs.entries.map((e) => Object.hash(e.key, e.value))));

  @override
  String toString() => 'DragArrangement(${ids.map((s) => s.id)}, ${pairs.entries.map((e) => '${e.key.id}|${e.value.id}')})';
}

/// A laid-out row's vertical extent in px.
final class DragRowBand {
  const DragRowBand(this.row, this.top, this.bottom);

  final HomeFeedRow row;
  final double top;
  final double bottom;

  double get center => (top + bottom) / 2;

  @override
  bool operator ==(Object other) => other is DragRowBand && other.row == row && other.top == top && other.bottom == bottom;

  @override
  int get hashCode => Object.hash(row, top, bottom);
}

/// The drag's rows: [ids] in order; in [twoColumn], [pairs] side by side.
List<HomeFeedRow> dragRows(List<HomeSection> ids, Map<HomeSection, HomeSection> pairs, bool twoColumn) =>
    homeFeedRows(ids, pairs, twoColumn);

/// [section]'s lane in [rows] (full when absent).
HomeDragLane dragLaneOf(HomeSection section, List<HomeFeedRow> rows) {
  for (final row in rows) {
    if (row is HomeFeedRowHalves) {
      if (row.left == section) return HomeDragLane.left;
      if (row.right == section) return HomeDragLane.right;
    }
  }
  return HomeDragLane.full;
}

/// The index of the row holding [section], or -1.
int dragRowIndexOf(HomeSection section, List<HomeFeedRow> rows) => rows.indexWhere((r) => r.sections.contains(section));

/// [pairs] without the pair [section] is in: lifted out, its partner is full.
Map<HomeSection, HomeSection> dragUnpair(Map<HomeSection, HomeSection> pairs, HomeSection section) => {
  for (final e in pairs.entries)
    if (e.key != section && e.value != section) e.key: e.value,
};

/// The finger's fraction across the content; NaN with no finger or no width.
double dragRel(double fingerX, double contentLeft, double contentWidth) =>
    contentWidth <= 0 || fingerX.isNaN ? double.nan : (fingerX - contentLeft) / contentWidth;

/// Rule 1: the side a half on [side] of its own pair takes with the finger at [rel] across the content.
HomeDragLane dragPairSide(HomeDragLane side, double rel) {
  if (rel.isNaN) return side;
  if (side == HomeDragLane.right && rel < HomeDragRuleTokens.swapToLeft) return HomeDragLane.left;
  if (side == HomeDragLane.left && rel > HomeDragRuleTokens.swapToRight) return HomeDragLane.right;
  return side;
}

/// Rule 1: the finger at [fingerY] is still in the row [own], grown by [bufferPx] above and below.
bool dragInOwnRow(double fingerY, DragRowBand own, double bufferPx) =>
    fingerY >= own.top - bufferPx && fingerY <= own.bottom + bufferPx;

/// Where over another full row the finger is: its top quarter, its middle half or its bottom quarter.
enum DragRowPart { above, middle, below }

/// Rule 2: the part of [band] the finger at [fingerY] is over.
DragRowPart dragRowPart(double fingerY, DragRowBand band) {
  final height = band.bottom - band.top;
  if (height <= 0) return fingerY < band.center ? DragRowPart.above : DragRowPart.below;
  final f = (fingerY - band.top) / height;
  if (f < HomeDragRuleTokens.rowGapZone) return DragRowPart.above;
  if (f > 1 - HomeDragRuleTokens.rowGapZone) return DragRowPart.below;
  return DragRowPart.middle;
}

/// Rule 2: over a full row's middle, the side the dragged block pairs on at [rel], or null between.
HomeDragLane? dragPairZone(double rel) {
  if (rel.isNaN) return null;
  if (rel < HomeDragRuleTokens.pairLeft) return HomeDragLane.left;
  if (rel > HomeDragRuleTokens.pairRight) return HomeDragLane.right;
  return null;
}

/// The dragged block may go beside [row]: a full row of another section, or the pair it is already in.
bool dragSideAllowed(HomeFeedRow row, HomeSection dragged) => switch (row) {
  HomeFeedRowFull(:final section) => section != dragged,
  HomeFeedRowHalves(:final left, :final right) => left == dragged || right == dragged,
};

/// [dragged] put beside [target] on [side] (left or right): out of wherever it was, next to the target in
/// the order, the two a pair.
DragArrangement dragPlaceBeside(
  List<HomeSection> ids,
  Map<HomeSection, HomeSection> pairs,
  HomeSection dragged,
  HomeSection target,
  HomeDragLane side,
) {
  assert(side != HomeDragLane.full && dragged != target);
  final rest = [for (final s in ids) if (s != dragged) s];
  final unpaired = dragUnpair(dragUnpair(pairs, dragged), target);
  final at = rest.indexOf(target);
  final order = [...rest]..insert(side == HomeDragLane.left ? at : at + 1, dragged);
  final pair = side == HomeDragLane.left ? MapEntry(dragged, target) : MapEntry(target, dragged);
  return DragArrangement(order, {...unpaired, pair.key: pair.value});
}

/// [dragged] as a full row of its own at [gap] among the two-column rows without it (0 = first, their
/// count = last).
DragArrangement dragInsertRow(List<HomeSection> ids, Map<HomeSection, HomeSection> pairs, HomeSection dragged, int gap) {
  final rest = [for (final s in ids) if (s != dragged) s];
  final unpaired = dragUnpair(pairs, dragged);
  final rows = dragRows(rest, unpaired, true);
  final at = gap >= 0 && gap < rows.length ? rest.indexOf(rows[gap].first) : rest.length;
  final order = [...rest]..insert(at, dragged);
  return DragArrangement(order, unpaired);
}

/// Between rows, the dragged block's own row swapped one row [dir] (±1): it jumps the whole neighbouring
/// row, a pair included.
List<HomeSection> dragSwapRow(
  List<HomeSection> ids,
  Map<HomeSection, HomeSection> pairs,
  HomeSection dragged,
  int dir,
  bool twoColumn,
) {
  final rows = dragRows(ids, pairs, twoColumn);
  final k = dragRowIndexOf(dragged, rows);
  final n = k + dir;
  if (n < 0 || n >= rows.length) return ids;
  final neighbour = rows[n];
  final rest = [for (final s in ids) if (s != dragged) s];
  final at = dir > 0 ? rest.indexOf(neighbour.sections.last) + 1 : rest.indexOf(neighbour.first);
  return [...rest]..insert(at, dragged);
}

/// Between rows, the half-travel rule: the dragged block, drawn with its top at [visualTop], takes the
/// slot above when it is closer to where it would sit there than to its own [slot], or the slot below
/// likewise. Rows not laid out (null) never swap. Returns -1, 0 or +1.
int dragSwapDir(double visualTop, DragRowBand slot, DragRowBand? prev, DragRowBand? next) {
  if (prev != null && visualTop < (slot.top + prev.top) / 2) return -1;
  if (next != null) {
    final topThere = next.bottom - (slot.bottom - slot.top);
    if (visualTop > (slot.top + topThere) / 2) return 1;
  }
  return 0;
}

/// What the finger asks of a two-column drag ([dragSideStep]).
sealed class DragSideStep {
  const DragSideStep();
}

/// A row of its own over its own row, a gap or another row's middle: the half-travel rule decides.
final class DragSideStepVertical extends DragSideStep {
  const DragSideStepVertical();
}

/// Beside its partner, staying there.
final class DragSideStepStay extends DragSideStep {
  const DragSideStepStay();
}

/// A new arrangement, the dragged block now on [side] (null: a row of its own).
final class DragSideStepArrange extends DragSideStep {
  const DragSideStepArrange(this.arrangement, this.side);

  final DragArrangement arrangement;
  final HomeDragLane? side;

  @override
  bool operator ==(Object other) => other is DragSideStepArrange && other.arrangement == arrangement && other.side == side;

  @override
  int get hashCode => Object.hash(arrangement, side);
}

/// Two columns: where the finger at [rel] across the content and [fingerY] puts the [dragged] block, now
/// a half on [side] of a pair (null: a row of its own). [bands] are the laid-out rows of the live
/// arrangement, top to bottom; [bufferPx] is [HomeDragRuleTokens.rowBuffer]. Not [pairing] (the edge
/// scroll is moving the rows under the finger): a full row's middle pairs with nothing. Pure.
DragSideStep dragSideStep({
  required List<HomeSection> ids,
  required Map<HomeSection, HomeSection> pairs,
  required HomeSection dragged,
  required HomeDragLane? side,
  required double rel,
  required double fingerY,
  required List<DragRowBand> bands,
  required double bufferPx,
  bool pairing = true,
}) {
  DragRowBand? own;
  for (final b in bands) {
    if (b.row.sections.contains(dragged)) {
      own = b;
      break;
    }
  }
  final partner = side != null ? dragPartnerOf(pairs, dragged) : null;
  if (side != null && partner != null) {
    // Its own row not laid out (scrolled off): nothing to judge against yet.
    if (own == null) return const DragSideStepStay();
    if (dragInOwnRow(fingerY, own, bufferPx)) {
      final next = dragPairSide(side, rel);
      if (next == side) return const DragSideStepStay();
      return DragSideStepArrange(dragPlaceBeside(ids, pairs, dragged, partner, next), next);
    }
  } else if (own != null && fingerY >= own.top && fingerY <= own.bottom) {
    return const DragSideStepVertical();
  }
  DragRowBand? under;
  for (final b in bands) {
    if (!identical(b, own) && fingerY >= b.top && fingerY <= b.bottom) {
      under = b;
      break;
    }
  }
  DragSideStepArrange ownRow(DragRowBand band, bool below) => _ownRowBy(ids, pairs, dragged, band, below);
  if (under == null) {
    if (partner == null) return const DragSideStepVertical();
    DragRowBand? above;
    for (final b in bands) {
      if (b.bottom < fingerY) above = b;
    }
    DragRowBand? belowBand;
    for (final b in bands) {
      if (b.top > fingerY) {
        belowBand = b;
        break;
      }
    }
    if (above != null) return ownRow(above, true);
    if (belowBand != null) return ownRow(belowBand, false);
    return const DragSideStepStay();
  }
  final row = under.row;
  if (row is HomeFeedRowFull) {
    switch (dragRowPart(fingerY, under)) {
      case DragRowPart.above:
        return ownRow(under, false);
      case DragRowPart.below:
        return ownRow(under, true);
      case DragRowPart.middle:
        final zone = pairing ? dragPairZone(rel) : null;
        if (zone != null) return DragSideStepArrange(dragPlaceBeside(ids, pairs, dragged, row.section, zone), zone);
        if (partner == null) return const DragSideStepVertical();
        return ownRow(under, fingerY > under.center);
    }
  }
  return ownRow(under, fingerY > under.center);
}

// A row of its own right above (or [below]) [band]'s row; a band of the dragged block's own pair counts as
// its partner's.
DragSideStepArrange _ownRowBy(
  List<HomeSection> ids,
  Map<HomeSection, HomeSection> pairs,
  HomeSection dragged,
  DragRowBand band,
  bool below,
) {
  final anchor = band.row.sections.firstWhere((s) => s != dragged, orElse: () => band.row.first);
  return DragSideStepArrange(dragInsertRow(ids, pairs, dragged, _dragGapBy(ids, pairs, dragged, anchor, below)), null);
}

/// [section]'s partner in [pairs], or null when it is a row of its own.
HomeSection? dragPartnerOf(Map<HomeSection, HomeSection> pairs, HomeSection section) {
  final right = pairs[section];
  if (right != null) return right;
  for (final e in pairs.entries) {
    if (e.value == section) return e.key;
  }
  return null;
}

// The gap above (or [below]) the row holding [section], among the two-column rows without [dragged].
int _dragGapBy(
  List<HomeSection> ids,
  Map<HomeSection, HomeSection> pairs,
  HomeSection dragged,
  HomeSection section,
  bool below,
) {
  final rest = dragRows([for (final s in ids) if (s != dragged) s], dragUnpair(pairs, dragged), true);
  final i = dragRowIndexOf(section, rest);
  final index = i < 0 ? 0 : i;
  return below ? index + 1 : index;
}

/// Rule 4 (anchor): the finger's x offset in the dragged block, [grabX] of [oldWidth], at the same
/// fraction of [newWidth] once its slot turns half or full. Pure.
double dragRescaleGrabX(double grabX, double oldWidth, double newWidth) =>
    oldWidth <= 0 || newWidth <= 0 || oldWidth == newWidth ? grabX : grabX / oldWidth * newWidth;

/// A slot's width across the content: a full row's, or a half's beside the column [gap].
double dragLaneWidth(HomeDragLane lane, double contentWidth, double gap) {
  if (lane == HomeDragLane.full) return contentWidth;
  final w = (contentWidth - gap) / 2;
  return w < 0 ? 0 : w;
}

// ── Edge scroll ───────────────────────────────────────────────────────────

/// -1 in the [edge]-deep band at [top], +1 in the one at [bottom], else 0. A band narrower than two edges
/// takes the nearer end.
int dragEdgeOf(double y, double top, double bottom, double edge) {
  if (edge <= 0 || bottom <= top || y.isNaN) return 0;
  final inTop = y < top + edge;
  final inBottom = y > bottom - edge;
  if (inTop && (!inBottom || y < (top + bottom) / 2)) return -1;
  if (inBottom) return 1;
  return 0;
}

/// The edge band's depth for an [edgePx] token over the scroll area [top]..[bottom]: the token, but never
/// more than [HomeDragTokens.autoScrollEdgeMaxFraction] of the area (a landscape phone keeps its middle half
/// free of edge scroll). Pure.
double dragEdgeBand(double edgePx, double top, double bottom) {
  final area = bottom - top;
  if (area <= 0) return edgePx;
  return math.min(edgePx, area * HomeDragTokens.autoScrollEdgeMaxFraction);
}

/// The feed's scroll speed (px/s, + = toward the end) for a finger at [y]: 0 outside the edge bands;
/// inside, from [min] where the band starts to [max] at the edge and beyond, easing in with depth². Pure.
double dragAutoScrollSpeed(double y, double top, double bottom, double edge, double min, double max) {
  final dir = dragEdgeOf(y, top, bottom, edge);
  if (dir == 0) return 0;
  var depth = dir < 0 ? (top + edge - y) / edge : (y - (bottom - edge)) / edge;
  depth = depth < 0 ? 0 : (depth > 1 ? 1 : depth);
  return dir * (min + (max - min) * depth * depth);
}

/// The start-in-edge guard: a block lifted with the finger already in an edge band ([startEdge] ±1, at
/// [startY]) doesn't scroll until the finger has pushed [pushPx] further toward that edge or rested
/// [dwellMs] there ([heldMs]). True while the guard still holds. Pure.
bool dragEdgeGuardHolds(int startEdge, double startY, double y, double heldMs, double pushPx, double dwellMs) =>
    startEdge != 0 && heldMs < dwellMs && startEdge * (y - startY) < pushPx;

// ── Interruptions ─────────────────────────────────────────────────────────

/// An exit that arrives mid-drag keeps the drop only for Done and the app's own exits; Back (and a blank
/// tap) put the order back as it was before the lift. Pure.
bool dragExitKeepsDrop(HomeEditExitReason reason) => switch (reason) {
  HomeEditExitReason.done || HomeEditExitReason.programmatic => true,
  HomeEditExitReason.back || HomeEditExitReason.blank => false,
};

/// The laid-out rows ([visibleKeys]: each visible row item's first section, top to bottom) already follow
/// [rows]: every known one in order, the dragged block's ([draggedKey]) among them. Unknown keys are
/// ignored. A drag decides only on a layout that has caught up with its last change. Pure.
bool dragLayoutFollows(List<HomeSection> visibleKeys, List<HomeFeedRow> rows, HomeSection draggedKey) {
  final firsts = [for (final r in rows) r.first];
  final indices = [for (final k in visibleKeys) firsts.indexOf(k)].where((i) => i >= 0).toList();
  if (!visibleKeys.contains(draggedKey)) return false;
  for (var i = 0; i + 1 < indices.length; i++) {
    if (indices[i] >= indices[i + 1]) return false;
  }
  return true;
}
