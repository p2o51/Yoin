/// Jump Back In, templated (HomeJbiTemplate.kt; owner 2026-10-04: the stagger
/// has to read across AND down, and no cover may be bigger than its
/// neighbours). On feeds of 3+ units the shelf is K lanes of phone-sized cover
/// columns, every cover the same size. Each lane starts at its own height —
/// lane 0 at the top, every next one drifting to 0 / ¼ / ½ of a row, never
/// its left neighbour's — so no row line runs across the grid. A seed from the
/// data (the leading cover) picks the drift, so the same shelf always lands
/// the same way; a wider pane gets more lanes, never bigger covers.
///
/// Up to two signal cards (a rating / note card) sit among the covers:
/// standing (1×2: cover over its copy) or lying (2×1: cover beside its copy,
/// its two columns one lane). A standing card's neighbours start level with
/// it; a lying card's right neighbour starts half a row lower. The same draws
/// from the same seeded generator ([KotlinRandom]) as Android, so a shelf
/// with the same leading id lands exactly as there. The phone's packed shelf
/// ([packWidgetRows], [trimToPhoneShelf]) lives here too. Pure; unit-tested.
library;

import 'dart:math' as math;

import 'home_feed_density.dart';
import 'home_metrics.dart';
import 'home_ui_state.dart';

/// Kotlin's `kotlin.random.Random(seed: Int)` (XorWow, Marsaglia 2003) with
/// 32-bit wrap-around, so a seeded template matches Android's draw for draw.
final class KotlinRandom {
  KotlinRandom(int seed) {
    final s1 = seed.toSigned(32);
    final s2 = s1 >> 31;
    _x = s1;
    _y = s2;
    _z = 0;
    _w = 0;
    _v = (~s1).toSigned(32);
    _addend = (_shl(s1, 10) ^ _ushr(s2, 4)).toSigned(32);
    assert((_x | _y | _z | _w | _v) != 0);
    for (var i = 0; i < 64; i++) {
      nextInt();
    }
  }

  late int _x, _y, _z, _w, _v, _addend;

  static int _shl(int v, int n) => (v << n).toSigned(32);
  static int _ushr(int v, int n) => (v & 0xffffffff) >> n;

  /// A uniformly distributed 32-bit signed int.
  int nextInt() {
    var t = _x;
    t = (t ^ _ushr(t, 2)).toSigned(32);
    _x = _y;
    _y = _z;
    _z = _w;
    final v0 = _v;
    _w = v0;
    t = ((t ^ _shl(t, 1)) ^ v0 ^ _shl(v0, 4)).toSigned(32);
    _v = t;
    _addend = (_addend + 362437).toSigned(32);
    return (t + _addend).toSigned(32);
  }

  int _nextBits(int bitCount) {
    final r = nextInt();
    return bitCount == 0 ? 0 : _ushr(r, 32 - bitCount);
  }

  /// `Random.nextBoolean()`.
  bool nextBoolean() => _nextBits(1) != 0;

  /// `Random.nextInt(until)`: uniform in [0, until), the same rejection loop as Kotlin.
  int nextIntBelow(int until) {
    assert(until > 0);
    if (until & -until == until) return _nextBits(until.bitLength - 1);
    while (true) {
      final bits = _ushr(nextInt(), 1);
      final v = bits % until;
      if ((bits - v + (until - 1)).toSigned(32) >= 0) return v;
    }
  }
}

/// One piece kind and the cells it spans.
enum JbiPieceKind {
  cover(1, 1),
  tallSignal(1, 2),
  wideSignal(2, 1);

  const JbiPieceKind(this.columnSpan, this.rowSpan);
  final int columnSpan;
  final int rowSpan;

  bool get isSignal => this != cover;
}

/// A piece at its top-left cell; [row] counts from its lane's own top.
final class JbiPiece {
  const JbiPiece(this.column, this.row, [this.kind = JbiPieceKind.cover]);
  final int column;
  final int row;
  final JbiPieceKind kind;

  int get endColumn => column + kind.columnSpan;
  int get endRow => row + kind.rowSpan;

  JbiPiece copyWith({int? column, int? row, JbiPieceKind? kind}) => JbiPiece(column ?? this.column, row ?? this.row, kind ?? this.kind);

  @override
  bool operator ==(Object other) => other is JbiPiece && other.column == column && other.row == row && other.kind == kind;

  @override
  int get hashCode => Object.hash(column, row, kind);

  @override
  String toString() => 'JbiPiece(${kind.name}, $column, $row)';
}

/// [columns] lanes of [rows] cells, every cell tiled exactly once. [offsets]
/// (one per column, in row pitches) is how far each column starts below the
/// grid's top; a lying card's two columns share theirs. [pieces] are in
/// reading order: by where they sit on screen (row + offset), then column.
final class JbiTemplate {
  const JbiTemplate({required this.columns, required this.rows, required this.offsets, required this.pieces});
  final int columns;
  final int rows;
  final List<double> offsets;
  final List<JbiPiece> pieces;

  int get covers => pieces.where((p) => p.kind == JbiPieceKind.cover).length;

  /// Where [piece] sits, in row pitches from the grid's top.
  double topOf(JbiPiece piece) => piece.row + offsets[piece.column];

  @override
  bool operator ==(Object other) =>
      other is JbiTemplate &&
      other.columns == columns &&
      other.rows == rows &&
      _listEquals(other.offsets, offsets) &&
      _listEquals(other.pieces, pieces);

  @override
  int get hashCode => Object.hash(columns, rows, Object.hashAll(offsets), Object.hashAll(pieces));
}

bool _listEquals<T>(List<T> a, List<T> b) {
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i] != b[i]) return false;
  }
  return true;
}

/// A card seated on its piece.
typedef JbiCell = ({HomeWidgetCard card, JbiPiece piece});

/// A [template] with its [cells] (same order as the template's pieces).
final class JbiLayout {
  const JbiLayout(this.template, this.cells);
  final JbiTemplate template;
  final List<JbiCell> cells;

  /// Same template, same cards on the same pieces (cards by stable id).
  @override
  bool operator ==(Object other) {
    if (other is! JbiLayout || other.template != template || other.cells.length != cells.length) return false;
    for (var i = 0; i < cells.length; i++) {
      if (other.cells[i].piece != cells[i].piece || other.cells[i].card.stableId != cells[i].card.stableId) return false;
    }
    return true;
  }

  @override
  int get hashCode => Object.hash(template, Object.hashAll(cells.map((c) => c.card.stableId)));
}

/// At most this many signal cards on one template.
const int jbiMaxSignals = 2;

/// The phone's packed shelf: 3 × 4 = 12 cells; a signal card takes two.
const int jbiPhoneShelfCells = 12;
const int _jbiSignalCells = 2;
const int _jbiTwoSignalMinCells = 9;

/// Past the phone's twelve cells, a signal card for every this many more.
const int jbiCellsPerExtraSignal = 6;
const double _jbiHalfRow = 0.5;

/// The template for [columns] lanes of [rows] cells with [signals] signal
/// cards (jbiTemplate). Seeded: the same arguments return the same template.
/// Each card stands (1×2) or lies (2×1) by the seed — on one row both lie;
/// the two never share a lane and stand apart when there is room. Lane 0
/// always starts at the top.
JbiTemplate jbiTemplate(int columns, int rows, int seed, {int signals = 0}) {
  final random = KotlinRandom((seed * 1000003).toSigned(32) + columns * 101 + rows * 13);
  final wanted = [
    for (var i = 0; i < signals.clamp(0, jbiMaxSignals); i++)
      rows >= 2 && random.nextBoolean() ? JbiPieceKind.tallSignal : JbiPieceKind.wideSignal,
  ];
  final lyingWanted = wanted.where((k) => k == JbiPieceKind.wideSignal).length;
  // A lying card with no pair of columns free stands up.
  final standing = [
    for (final k in wanted)
      k == JbiPieceKind.wideSignal && columns < 2 * lyingWanted && rows >= 2 ? JbiPieceKind.tallSignal : k,
  ];
  // On one row with too few columns for both lying cards, only the first goes in.
  final kinds = standing.fold<int>(0, (n, k) => n + k.columnSpan) > columns ? standing.take(1).toList() : standing;
  final placement = _bestPlacement(columns, kinds, random);
  final pairStarts = [
    for (var i = 0; i < kinds.length; i++)
      if (kinds[i] == JbiPieceKind.wideSignal) placement[i],
  ];
  final lanes = _lanesOf(columns, pairStarts);
  final spanning = <JbiPiece>[];
  for (var i = 0; i < kinds.length; i++) {
    final kind = kinds[i];
    final column = placement[i];
    final int row;
    if (kind == JbiPieceKind.tallSignal) {
      row = random.nextIntBelow(rows - 1);
    } else if (column + kind.columnSpan < columns) {
      row = 0;
    } else {
      row = random.nextIntBelow(rows);
    }
    spanning.add(JbiPiece(column, row, kind));
  }
  final group = _runsOf(lanes, spanning);
  // Runs in left-to-right order (they are contiguous).
  final byRun = <int, List<int>>{};
  for (var i = 0; i < lanes.length; i++) {
    byRun.putIfAbsent(group[i], () => []).add(i);
  }
  final runs = byRun.values.toList()..sort((a, b) => a.first.compareTo(b.first));
  final runOfLane = List<int>.filled(lanes.length, 0);
  for (var r = 0; r < runs.length; r++) {
    for (final lane in runs[r]) {
      runOfLane[lane] = r;
    }
  }
  // A lying card's run at 0 (unless it is itself a right neighbour), the run on its right half a row below it.
  final required = List<double?>.filled(runs.length, null);
  final lying = spanning.where((p) => p.kind == JbiPieceKind.wideSignal).toList()..sort((a, b) => a.column.compareTo(b.column));
  for (final card in lying) {
    final laneIndex = lanes.indexWhere((l) => card.column >= l.$1 && card.column < l.$2);
    final cardRun = runOfLane[laneIndex];
    if (laneIndex + 1 >= runOfLane.length) continue;
    final rightRun = runOfLane[laneIndex + 1];
    if (rightRun == cardRun) continue;
    final top = required[cardRun] ?? 0.0;
    required[cardRun] = top;
    required[rightRun] = top + _jbiHalfRow;
  }
  final runOffsets = _laneOffsets(required, random);
  final offsets = List<double>.filled(columns, 0);
  for (var r = 0; r < runs.length; r++) {
    for (final laneIndex in runs[r]) {
      for (var c = lanes[laneIndex].$1; c < lanes[laneIndex].$2; c++) {
        offsets[c] = runOffsets[r];
      }
    }
  }
  final taken = List.generate(rows, (_) => List.filled(columns, false));
  for (final piece in spanning) {
    for (var r = piece.row; r < piece.endRow; r++) {
      for (var c = piece.column; c < piece.endColumn; c++) {
        taken[r][c] = true;
      }
    }
  }
  final pieces = [
    ...spanning,
    for (var r = 0; r < rows; r++)
      for (var c = 0; c < columns; c++)
        if (!taken[r][c]) JbiPiece(c, r),
  ]..sort(_readingOrder(offsets));
  return JbiTemplate(columns: columns, rows: rows, offsets: offsets, pieces: pieces);
}

int Function(JbiPiece, JbiPiece) _readingOrder(List<double> offsets) => (a, b) {
      final byTop = (a.row + offsets[a.column]).compareTo(b.row + offsets[b.column]);
      return byTop != 0 ? byTop : a.column.compareTo(b.column);
    };

/// Lanes left to right as [start, end) columns: a lying card's two columns are one.
List<(int, int)> _lanesOf(int columns, List<int> pairStarts) {
  final lanes = <(int, int)>[];
  var c = 0;
  while (c < columns) {
    if (pairStarts.contains(c)) {
      lanes.add((c, c + 2));
      c += 2;
    } else {
      lanes.add((c, c + 1));
      c += 1;
    }
  }
  return lanes;
}

int _laneOf(List<(int, int)> lanes, int column) => lanes.indexWhere((l) => column >= l.$1 && column < l.$2);

/// Run id per lane: a standing card's lane joins both neighbours (the lanes
/// beside its copy); every other lane is a run of its own.
List<int> _runsOf(List<(int, int)> lanes, List<JbiPiece> cards) {
  final group = List<int>.generate(lanes.length, (i) => i);
  void join(int a, int b) {
    if (a < 0 || a >= lanes.length || b < 0 || b >= lanes.length) return;
    final from = group[b];
    final to = group[a];
    for (var i = 0; i < group.length; i++) {
      if (group[i] == from) group[i] = to;
    }
  }

  for (final card in cards) {
    if (card.kind != JbiPieceKind.tallSignal) continue;
    final laneIndex = _laneOf(lanes, card.column);
    join(laneIndex, laneIndex - 1);
    join(laneIndex, laneIndex + 1);
  }
  return group;
}

/// The cards' columns ([kinds] in order): of every placement where they don't
/// overlap, those keeping the two a lane apart and then leaving the most runs
/// of lanes to stagger, the seed picking among them.
List<int> _bestPlacement(int columns, List<JbiPieceKind> kinds, KotlinRandom random) {
  if (kinds.isEmpty) return const [];
  Iterable<int> spots(JbiPieceKind kind) sync* {
    for (var c = 0; c <= columns - kind.columnSpan; c++) {
      yield c;
    }
  }

  final candidates = <List<int>>[];
  if (kinds.length == 1) {
    for (final a in spots(kinds[0])) {
      candidates.add([a]);
    }
  } else {
    for (final a in spots(kinds[0])) {
      for (final b in spots(kinds[1])) {
        final aEnd = a + kinds[0].columnSpan;
        final bEnd = b + kinds[1].columnSpan;
        if (a < bEnd && b < aEnd) continue;
        candidates.add([a, b]);
      }
    }
  }
  final scores = <int>[];
  for (final placement in candidates) {
    final pairs = [
      for (var i = 0; i < kinds.length; i++)
        if (kinds[i] == JbiPieceKind.wideSignal) placement[i],
    ];
    final lanes = _lanesOf(columns, pairs);
    final cards = [for (var i = 0; i < kinds.length; i++) JbiPiece(placement[i], 0, kinds[i])];
    final runs = _runsOf(lanes, cards).toSet().length;
    final apart = cards.length < 2 || (_laneOf(lanes, cards[0].column) - _laneOf(lanes, cards[1].column)).abs() >= 2;
    scores.add((apart ? 1000 : 0) + runs);
  }
  final best = scores.reduce(math.max);
  final top = [
    for (var i = 0; i < candidates.length; i++)
      if (scores[i] == best) candidates[i],
  ];
  return top[random.nextIntBelow(top.length)];
}

/// One offset per run of lanes, in row pitches; a [required] one always wins.
/// Otherwise the first is at 0 and each next one drifts to one of {0, ¼, ½},
/// never its left neighbour's nor a required right neighbour's.
List<double> _laneOffsets(List<double?> required, KotlinRandom random) {
  final out = <double>[];
  for (var i = 0; i < required.length; i++) {
    final fixed = required[i];
    if (fixed != null) {
      out.add(fixed);
    } else if (i == 0) {
      out.add(0);
    } else {
      final next = i + 1 < required.length ? required[i + 1] : null;
      var choices = HomeJbiMetrics.driftSteps.where((s) => s != out.last && s != next).toList();
      if (choices.isEmpty) choices = HomeJbiMetrics.driftSteps.where((s) => s != out.last).toList();
      out.add(choices[random.nextIntBelow(choices.length)]);
    }
  }
  return out;
}

/// Seats [cards] (signal cards first, at most two — one while the covers
/// can't fill a row; the rest covers in ranking order) on [columns] lanes of
/// up to [rows] cells. A shelf too short for [rows] takes one row fewer,
/// down to one; null = not even one row (the caller falls back to the
/// packed grid).
JbiLayout? jbiLayout(List<HomeWidgetCard> cards, {required int columns, required int rows, required int seed}) {
  if (columns < 2 || rows < 1) return null;
  final compacts = [for (final c in cards) if (!c.expanded) c];
  final signals = [for (final c in cards) if (c.expanded) c].take(jbiSparseSignalLimit(compacts.length, columns)).toList();
  for (var r = rows; r >= 1; r--) {
    final template = jbiTemplate(columns, r, seed, signals: signals.length);
    if (template.covers > compacts.length) continue;
    var s = 0;
    var c = 0;
    return JbiLayout(template, [
      for (final piece in template.pieces) (card: piece.kind.isSignal ? signals[s++] : compacts[c++], piece: piece),
    ]);
  }
  return null;
}

/// Signal cards a shelf of [covers] covers takes on [columns] lanes (owner
/// 2026-11-12): while the covers can't fill one row, one card; else two.
int jbiSparseSignalLimit(int covers, int columns) => covers < columns ? 1 : jbiMaxSignals;

/// Signal cards a [rows]-row grid of [cells] cells takes: one on a single row
/// or under nine cells, two up to the phone's twelve, one more per six past.
int jbiSignalLimit(int rows, int cells) {
  if (rows <= 1 || cells < _jbiTwoSignalMinCells) return 1;
  if (cells <= jbiPhoneShelfCells) return 2;
  return 2 + (cells - jbiPhoneShelfCells) ~/ jbiCellsPerExtraSignal;
}

/// The shelf's seed: its leading cover's id, so a signal card arriving doesn't re-roll the layout.
int jbiLayoutSeed(List<HomeWidgetCard> cards) {
  for (final c in cards) {
    if (!c.expanded) return activityLayoutSeed(c.stableId);
  }
  return activityLayoutSeed(null);
}

/// The packed shelf of [cells] cells: up to [maxSignals] wide cards first,
/// then covers for the cells left (trimToPhoneShelf).
List<HomeWidgetCard> trimToPhoneShelf(List<HomeWidgetCard> cards, {int cells = jbiPhoneShelfCells, int maxSignals = jbiMaxSignals}) {
  final wide = [for (final c in cards) if (c.expanded) c].take(math.max(0, maxSignals)).toList();
  final compacts = [for (final c in cards) if (!c.expanded) c];
  return [...wide, ...compacts.take(math.max(0, cells - wide.length * _jbiSignalCells))];
}

/// Packs the shelf into rows of [columns] units — a wide card two, a cover
/// one. Each wide card shares its row with the next `columns - 2` covers,
/// alternating which side it hugs; leftover covers fill whole rows. At 3
/// columns this is Android's shipped phone packing exactly.
List<List<HomeWidgetCard>> packWidgetRows(List<HomeWidgetCard> cards, int columns) {
  final wide = [for (final c in cards) if (c.expanded) c];
  final compact = [for (final c in cards) if (!c.expanded) c];
  final rows = <List<HomeWidgetCard>>[];
  var next = 0;
  for (var pair = 0; pair < wide.length; pair++) {
    final fill = <HomeWidgetCard>[];
    while (fill.length < columns - 2 && next < compact.length) {
      fill.add(compact[next++]);
    }
    rows.add(fill.isEmpty ? [wide[pair]] : (pair.isEven ? [wide[pair], ...fill] : [...fill, wide[pair]]));
  }
  for (var i = next; i < compact.length; i += columns) {
    rows.add(compact.sublist(i, math.min(i + columns, compact.length)));
  }
  return rows;
}

// ---------------------------------------------------------------------------
// Row presets (HomeJbiTemplate.kt jbiPresetLayout).

/// [layout] at [rows] rows (D1 row presets), keeping every card it can where
/// it is, so neighbouring presets interpolate card by card.
///
/// More rows: whole rows are appended at the bottom of every lane, seated
/// with the next covers in [cards] in reading order (a row the shelf can't
/// fill is left off); the template's own rows and offsets stay. The rows
/// added bring signal cards of their own when [cards] has more than the
/// template seated ([jbiGrowSignals]): each stands in a lane away from the
/// others, over the old bottom row and the first new one, and the cover it
/// stands on moves down into the new rows — first of them, so the order of
/// every card the smaller layout seated holds.
///
/// Fewer rows: the bottom rows are cut. A signal card past the new bottom
/// moves up in its own lane (a standing one on a single row lies down across
/// its neighbour); past [jbiSignalLimit] the lower-ranked one leaves. Covers
/// above the cut keep their cards; a cell a moved or departed signal leaves to
/// a cover takes the first of the displaced covers in the old reading order,
/// so the result seats only cards [layout] seated. Offsets stay.
JbiLayout jbiPresetLayout(JbiLayout layout, List<HomeWidgetCard> cards, int rows) {
  final template = layout.template;
  if (rows == template.rows) return layout;
  if (rows > template.rows) return _growJbiLayout(layout, cards, rows);
  return _shrinkJbiLayout(layout, math.max(1, rows));
}

/// Signal cards [addedRows] rows of [columns] lanes bring along: one for every
/// [jbiCellsPerExtraSignal] cells they add, rounded to the nearest, at least one.
int jbiGrowSignals(int addedRows, int columns) =>
    math.max(1, (addedRows * columns + jbiCellsPerExtraSignal ~/ 2) ~/ jbiCellsPerExtraSignal);

int Function(JbiPiece, JbiPiece) _reading(List<double> offsets) => (a, b) {
      final byTop = (a.row + offsets[a.column]).compareTo(b.row + offsets[b.column]);
      return byTop != 0 ? byTop : a.column.compareTo(b.column);
    };

JbiLayout _growJbiLayout(JbiLayout layout, List<HomeWidgetCard> cards, int rows) {
  final template = layout.template;
  final columns = template.columns;
  final oldRows = template.rows;
  final seated = {for (final c in layout.cells) c.card.stableId};
  final spare = [for (final c in cards) if (!c.expanded && !seated.contains(c.stableId)) c];
  final spareSignals = [for (final c in cards) if (c.expanded && !seated.contains(c.stableId)) c];
  // As many whole rows as the shelf fills: each standing card covers one new
  // cell and lends its old cover another, so it takes no extra cover.
  var addedRows = rows - oldRows;
  var standing = <int>[];
  while (addedRows > 0) {
    final wanted = math.min(spareSignals.length, jbiGrowSignals(addedRows, columns));
    standing = _growSignalColumns(layout, wanted);
    if (spare.length >= addedRows * columns - 2 * standing.length) break;
    addedRows--;
  }
  if (addedRows <= 0) return layout;
  final reading = _reading(template.offsets);
  final bottom = oldRows - 1;
  // The covers the new cards stand on, in the old reading order: they move down first.
  final lent = [
    for (final c in layout.cells)
      if (c.piece.kind == JbiPieceKind.cover && c.piece.row == bottom && standing.contains(c.piece.column)) c,
  ]..sort((a, b) => reading(a.piece, b.piece));
  final kept = [for (final c in layout.cells) if (!lent.contains(c)) c];
  final sortedStanding = [...standing]..sort();
  final signalCells = <JbiCell>[
    for (var i = 0; i < sortedStanding.length; i++)
      (card: spareSignals[i], piece: JbiPiece(sortedStanding[i], bottom, JbiPieceKind.tallSignal)),
  ];
  final newCovers = <JbiPiece>[
    for (var r = oldRows; r < oldRows + addedRows; r++)
      for (var c = 0; c < columns; c++)
        if (!(r == oldRows && standing.contains(c))) JbiPiece(c, r),
  ]..sort(reading);
  final supply = [...lent.map((c) => c.card), ...spare];
  var next = 0;
  final cells = <JbiCell>[
    ...kept,
    ...signalCells,
    for (final piece in newCovers) (card: supply[next++], piece: piece),
  ]..sort((a, b) => reading(a.piece, b.piece));
  return JbiLayout(
    JbiTemplate(columns: columns, rows: oldRows + addedRows, offsets: template.offsets, pieces: [for (final c in cells) c.piece]),
    cells,
  );
}

/// Up to [count] lanes for new standing cards over [layout]'s bottom row: a
/// lane whose bottom cell is a cover, never beside another signal card on
/// that row or another new one, farthest from the signals already there
/// first (ties by a seed from the template, so the same shelf grows the same
/// way). Fewer when the lanes run out.
List<int> _growSignalColumns(JbiLayout layout, int count) {
  if (count <= 0) return const [];
  final template = layout.template;
  final bottom = template.rows - 1;
  final signalColumns = <int>{
    for (final cell in layout.cells)
      if (cell.piece.kind.isSignal)
        for (var c = cell.piece.column; c < cell.piece.endColumn; c++) c,
  };
  final bottomSignal = <int>{
    for (final cell in layout.cells)
      if (cell.piece.kind.isSignal && bottom >= cell.piece.row && bottom < cell.piece.endRow)
        for (var c = cell.piece.column; c < cell.piece.endColumn; c++) c,
  };
  final coverOnBottom = <int>{
    for (final cell in layout.cells)
      if (cell.piece.kind == JbiPieceKind.cover && cell.piece.row == bottom) cell.piece.column,
  };
  final random = KotlinRandom(template.columns * 7919 + template.rows * 104729 + template.pieces.length);
  final picked = <int>[];
  for (var n = 0; n < count; n++) {
    final taken = [...signalColumns, ...picked];
    final open = [
      for (var c = 0; c < template.columns; c++)
        if (coverOnBottom.contains(c) &&
            !picked.contains(c) &&
            !bottomSignal.contains(c - 1) &&
            !bottomSignal.contains(c + 1) &&
            !picked.contains(c - 1) &&
            !picked.contains(c + 1))
          c,
    ];
    if (open.isEmpty) return picked;
    int distance(int c) => taken.isEmpty ? template.columns : taken.map((t) => (t - c).abs()).reduce(math.min);
    final best = open.map(distance).reduce(math.max);
    final ties = [for (final c in open) if (distance(c) == best) c];
    picked.add(ties[random.nextIntBelow(ties.length)]);
  }
  return picked;
}

JbiLayout _shrinkJbiLayout(JbiLayout layout, int rows) {
  final template = layout.template;
  final columns = template.columns;
  final taken = List.generate(rows, (_) => List.filled(columns, false));
  bool fits(JbiPiece piece) {
    if (piece.column < 0 || piece.endColumn > columns || piece.row < 0 || piece.endRow > rows) return false;
    for (var r = piece.row; r < piece.endRow; r++) {
      for (var c = piece.column; c < piece.endColumn; c++) {
        if (taken[r][c]) return false;
      }
    }
    return true;
  }

  void take(JbiPiece piece) {
    for (var r = piece.row; r < piece.endRow; r++) {
      for (var c = piece.column; c < piece.endColumn; c++) {
        taken[r][c] = true;
      }
    }
  }

  // Signals by card rank (the incoming order), at most the limit for this size.
  final signals = [for (final c in layout.cells) if (c.piece.kind.isSignal) c];
  final limit = jbiSignalLimit(rows, rows * columns);
  final kept = <JbiCell>[];
  for (final cell in signals.take(limit)) {
    final piece = cell.piece;
    final List<JbiPiece> candidates;
    if (piece.endRow <= rows) {
      candidates = [piece];
    } else if (piece.kind == JbiPieceKind.tallSignal && rows >= 2) {
      candidates = [piece.copyWith(row: rows - 2)];
    } else if (piece.kind == JbiPieceKind.wideSignal) {
      candidates = [piece.copyWith(row: rows - 1)];
    } else {
      // A standing card on one row lies down, across its right neighbour or else its left.
      candidates = [
        JbiPiece(piece.column, rows - 1, JbiPieceKind.wideSignal),
        JbiPiece(piece.column - 1, rows - 1, JbiPieceKind.wideSignal),
      ];
    }
    JbiPiece? placed;
    for (final c in candidates) {
      if (fits(c)) {
        placed = c;
        break;
      }
    }
    if (placed == null) continue;
    take(placed);
    kept.add((card: cell.card, piece: placed));
  }

  final oldCovers = [for (final c in layout.cells) if (c.piece.kind == JbiPieceKind.cover) c];
  final coverAt = {for (final c in oldCovers) (c.piece.column, c.piece.row): c};
  final keptCovers = <JbiCell>[];
  final open = <JbiPiece>[];
  for (var r = 0; r < rows; r++) {
    for (var c = 0; c < columns; c++) {
      if (taken[r][c]) continue;
      final old = coverAt[(c, r)];
      if (old != null) {
        keptCovers.add(old);
      } else {
        open.add(JbiPiece(c, r));
      }
    }
  }
  final keptIds = {for (final c in keptCovers) c.card.stableId};
  // Displaced covers, best first (the old reading order), fill the open cells in the new one.
  final displaced = [for (final c in oldCovers) if (!keptIds.contains(c.card.stableId)) c];
  final reading = _reading(template.offsets);
  open.sort(reading);
  final refilled = <JbiCell>[
    for (var i = 0; i < open.length && i < displaced.length; i++) (card: displaced[i].card, piece: open[i]),
  ];
  final cells = [...kept, ...keptCovers, ...refilled]..sort((a, b) => reading(a.piece, b.piece));
  return JbiLayout(
    JbiTemplate(columns: columns, rows: rows, offsets: template.offsets, pieces: [for (final c in cells) c.piece]),
    cells,
  );
}
