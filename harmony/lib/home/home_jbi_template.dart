/// Jump Back In, templated (HomeJbiTemplate.kt; owner 2026-10-04: the stagger
/// has to read across AND down, and no cover may be bigger than its
/// neighbours). On feeds of 3+ units the shelf is K lanes of phone-sized cover
/// columns, every cover the same size. Each lane starts at its own height —
/// lane 0 at the top, every next one drifting to 0 / ¼ / ½ of a row, never
/// its left neighbour's — so no row line runs across the grid. A seed from the
/// data (the leading cover) picks the drift, so the same shelf always lands
/// the same way; a wider pane gets more lanes, never bigger covers.
///
/// Up to two signal cards (a rating / note card) sit among the covers, always
/// lying (2×1: cover beside its copy, its two columns one lane; owner
/// 2026-11-14 dropped the standing 1×2). A lying card's right neighbour starts
/// half a row lower; two cards keep a lane of covers between them, or stack
/// in the right-hand lane a row apart. The same draws from the same seeded
/// generator ([KotlinRandom]) as Android, so a shelf with the same leading id
/// lands exactly as there. The phone's packed shelf ([packWidgetRows],
/// [jbiSpreadRows], [trimToPhoneShelf]) lives here too. Pure; unit-tested.
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

/// The columns a lying signal card spans.
const int _jbiSignalSpan = 2;
const int _jbiTwoSignalMinCells = 9;

/// Past the phone's twelve cells, a signal card for every this many more.
const int jbiCellsPerExtraSignal = 6;
const double _jbiHalfRow = 0.5;

/// The template for [columns] lanes of [rows] cells with [signals] signal
/// cards (jbiTemplate). Seeded: the same arguments return the same template.
/// Every card lies (2×1, its cover beside its copy): owner 2026-11-14 ruled
/// the standing 1×2 out — a note under its album's title doesn't read as one
/// block with the picture. The cards never overlap and lie side by side only
/// with a lane of covers between them; when the width can't hold that (three
/// and four lanes) they stack in the right-hand lane with a row between. Lane
/// 0 always starts at the top, under the section title.
JbiTemplate jbiTemplate(int columns, int rows, int seed, {int signals = 0}) {
  final random = KotlinRandom((seed * 1000003).toSigned(32) + columns * 101 + rows * 13);
  final wanted = signals.clamp(0, jbiMaxSignals);

  // Where the cards go. A lying card's copy is on its right (owner:
  // 「默认文本在视觉右边……右边是一个 1×1 的封面，这时候右边就应该往下半格，
  // 以保证与左边区分开」): its right neighbour starts half a row lower, so the
  // copy's top lines up with the card's own cover and nothing else. It sits on
  // the top row, or the neighbour's cover above would put its title beside the
  // copy; a card in the right-hand lane has no neighbour there and takes any
  // row but the bottom one. Of every placement, the ones keeping the cards a
  // lane apart win, then those with a card in the right-hand lane (the lane a
  // longer preset can seat another card in, jbiPresetLayout); the seed picks
  // among them.
  List<JbiPiece> lying(int count) => [
    for (final column in _bestPlacement(columns, count, random))
      JbiPiece(column, column + _jbiSignalSpan < columns ? 0 : _rightLaneRow(rows, random), JbiPieceKind.wideSignal),
  ];
  final List<JbiPiece> spanning;
  if (wanted == 0 || columns < _jbiSignalSpan) {
    spanning = const [];
  } else if (wanted * _jbiSignalSpan + (wanted - 1) <= columns) {
    // Side by side only when a lane of covers fits between them.
    spanning = lying(wanted);
  } else if (rows >= 3) {
    // Too narrow for two a lane apart: both in the right-hand lane, a row of covers between them.
    final column = columns - _jbiSignalSpan;
    final first = random.nextIntBelow(rows - 2);
    final second = first + 2 + random.nextIntBelow(rows - first - 2);
    spanning = [JbiPiece(column, first, JbiPieceKind.wideSignal), JbiPiece(column, second, JbiPieceKind.wideSignal)];
  } else {
    // Too short to stack: only the first card goes in.
    spanning = lying(1);
  }
  final lanes = _lanesOf(columns, {for (final p in spanning) p.column});
  // A lying card's lane at 0 (unless it is itself a right neighbour), the lane on its right half a row below it.
  final required = List<double?>.filled(lanes.length, null);
  final starts = {for (final p in spanning) p.column}.toList()..sort();
  for (final column in starts) {
    final laneIndex = _laneOf(lanes, column);
    final rightLane = laneIndex + 1;
    if (rightLane >= lanes.length) continue;
    final top = required[laneIndex] ?? 0.0;
    required[laneIndex] = top;
    required[rightLane] = top + _jbiHalfRow;
  }
  final laneOffsets = _laneOffsets(required, random);
  final offsets = List<double>.filled(columns, 0);
  for (var i = 0; i < lanes.length; i++) {
    for (var c = lanes[i].$1; c < lanes[i].$2; c++) {
      offsets[c] = laneOffsets[i];
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

/// A right-hand card's row: any but the bottom one (when there are two or
/// more), so a longer preset can lay another card a row of covers below it.
int _rightLaneRow(int rows, KotlinRandom random) => random.nextIntBelow(math.max(1, rows - 1));

int Function(JbiPiece, JbiPiece) _readingOrder(List<double> offsets) => (a, b) {
      final byTop = (a.row + offsets[a.column]).compareTo(b.row + offsets[b.column]);
      return byTop != 0 ? byTop : a.column.compareTo(b.column);
    };

/// Lanes left to right as [start, end) columns: a lying card's two columns (from [pairStarts]) are one.
List<(int, int)> _lanesOf(int columns, Set<int> pairStarts) {
  final lanes = <(int, int)>[];
  var c = 0;
  while (c < columns) {
    if (pairStarts.contains(c) && c + 1 < columns) {
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

/// The first columns of [count] lying cards side by side: of every placement
/// where they don't overlap, those keeping them a lane apart (two copies side
/// by side read as one block), then those with a card in the right-hand lane,
/// the seed picking among them.
List<int> _bestPlacement(int columns, int count, KotlinRandom random) {
  if (count <= 0) return const [];
  final candidates = <List<int>>[];
  if (count == 1) {
    for (var a = 0; a <= columns - _jbiSignalSpan; a++) {
      candidates.add([a]);
    }
  } else {
    for (var a = 0; a <= columns - _jbiSignalSpan; a++) {
      for (var b = 0; b <= columns - _jbiSignalSpan; b++) {
        if (a < b + _jbiSignalSpan && b < a + _jbiSignalSpan) continue;
        candidates.add([a, b]);
      }
    }
  }
  int score(List<int> placement) {
    final apart = placement.length < 2 || (placement[0] - placement[1]).abs() > _jbiSignalSpan;
    final rightLane = placement.any((c) => c + _jbiSignalSpan == columns);
    return (apart ? 2 : 0) + (rightLane ? 1 : 0);
  }

  final scores = [for (final p in candidates) score(p)];
  final best = scores.reduce(math.max);
  final top = [
    for (var i = 0; i < candidates.length; i++)
      if (scores[i] == best) candidates[i],
  ];
  return top[random.nextIntBelow(top.length)];
}

/// One offset per lane, in row pitches; a [required] one always wins (the
/// lying cards' lanes and their right neighbours). Otherwise the first is at 0 and each next one drifts to one of {0, ¼, ½},
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
/// alternating which side it hugs; the other rows are full rows of covers.
/// Order otherwise follows the ranking, so the strongest cards still lead.
///
/// The wide rows are spread over the shelf ([jbiSpreadRows]) rather than
/// stacked at its top (owner 2026-11-14: a phone shelf pulled to six rows had
/// every memory in its first three, 「我希望它们出现得更均匀一些」); a short
/// supply keeps covers back for the wide rows still to come, so only the last
/// row is partial.
List<List<HomeWidgetCard>> packWidgetRows(List<HomeWidgetCard> cards, int columns) {
  final wide = [for (final c in cards) if (c.expanded) c];
  final compact = [for (final c in cards) if (!c.expanded) c];
  final fillPerWide = math.max(0, columns - 2);
  // The shelf's row count, as if every wide row came first.
  var left = compact.length;
  for (var i = 0; i < wide.length; i++) {
    left -= math.min(fillPerWide, left);
  }
  final rowCount = wide.length + (left + columns - 1) ~/ columns;
  final wideRows = jbiSpreadRows(wide.length, rowCount);
  final rows = <List<HomeWidgetCard>>[];
  var w = 0;
  var c = 0;
  while (w < wide.length || c < compact.length) {
    // A wide row when its turn has come, or when a full row of covers now
    // would leave the wide rows still to come short of covers.
    final coversAfterFullRow = compact.length - c - columns;
    if (w < wide.length && (rows.length >= wideRows[w] || coversAfterFullRow < fillPerWide * (wide.length - w))) {
      final big = wide[w];
      final fill = compact.sublist(c, math.min(c + fillPerWide, compact.length));
      c += fill.length;
      rows.add(fill.isEmpty ? [big] : (w.isEven ? [big, ...fill] : [...fill, big]));
      w++;
    } else {
      final end = math.min(c + columns, compact.length);
      rows.add(compact.sublist(c, end));
      c = end;
    }
  }
  return rows;
}

/// The rows [count] cards take on a shelf of [rows] rows, spread evenly: card
/// `i` on row ⌈i × rows ÷ count⌉ — the first on the top row, the rest about
/// rows ÷ count apart, never two on one row. More cards than rows: one per
/// row from the top.
List<int> jbiSpreadRows(int count, int rows) => count > rows
    ? [for (var i = 0; i < count; i++) i]
    : [for (var i = 0; i < count; i++) (i * rows + count - 1) ~/ count];

// ---------------------------------------------------------------------------
// Row presets (HomeJbiTemplate.kt jbiPresetLayout).

/// [layout] at [rows] rows (D1 row presets), keeping every card it can where
/// it is, so neighbouring presets interpolate card by card.
///
/// More rows: whole rows are appended at the bottom of every lane, seated
/// with the next covers in [cards] in reading order (a row the shelf can't
/// fill is left off); the template's own rows and offsets stay. The rows
/// added bring signal cards of their own when [cards] has more than the
/// template seated ([jbiGrowSignals]): each lies in the right-hand lane when
/// that lane already holds a lying card (no neighbour on its copy's side, so
/// any row reads right), a row of covers away from the card above it. Every
/// card the smaller layout seated keeps its cell.
///
/// Fewer rows: the bottom rows are cut. A signal card past the new bottom
/// moves up in its own lane; past [jbiSignalLimit], or with no free row a
/// cover apart from the card above, the lower-ranked one leaves. Covers above
/// the cut keep their cards; a cell a moved or departed signal leaves to a
/// cover takes the first of the displaced covers in the old reading order, so
/// the result seats only cards [layout] seated. Offsets stay.
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
  // As many whole rows as the shelf fills; each new card takes two cells.
  var addedRows = rows - oldRows;
  var added = <JbiPiece>[];
  while (addedRows > 0) {
    final wanted = math.min(spareSignals.length, jbiGrowSignals(addedRows, columns));
    added = _growSignalPieces(layout, addedRows, wanted);
    if (spare.length >= addedRows * columns - _jbiSignalCells * added.length) break;
    addedRows--;
  }
  if (addedRows <= 0) return layout;
  final reading = _reading(template.offsets);
  final sortedAdded = [...added]..sort(reading);
  final signalCells = <JbiCell>[
    for (var i = 0; i < sortedAdded.length; i++) (card: spareSignals[i], piece: sortedAdded[i]),
  ];
  final newCovers = <JbiPiece>[
    for (var r = oldRows; r < oldRows + addedRows; r++)
      for (var c = 0; c < columns; c++)
        if (!added.any((p) => p.row == r && c >= p.column && c < p.endColumn)) JbiPiece(c, r),
  ]..sort(reading);
  var next = 0;
  final cells = <JbiCell>[
    ...layout.cells,
    ...signalCells,
    for (final piece in newCovers) (card: spare[next++], piece: piece),
  ]..sort((a, b) => reading(a.piece, b.piece));
  return JbiLayout(
    JbiTemplate(columns: columns, rows: oldRows + addedRows, offsets: template.offsets, pieces: [for (final c in cells) c.piece]),
    cells,
  );
}

/// Up to [count] new lying cards in the [addedRows] rows under [layout]: only
/// in the right-hand lane, and only when a lying card already makes it one
/// lane (two columns of different lanes never merge — their offsets differ).
/// Each takes the first new row with a row of covers between it and the card
/// above it in that lane. Fewer when the rows run out.
List<JbiPiece> _growSignalPieces(JbiLayout layout, int addedRows, int count) {
  if (count <= 0) return const [];
  final template = layout.template;
  final column = template.columns - _jbiSignalSpan;
  final laneRows = <int>{
    for (final cell in layout.cells)
      if (cell.piece.kind == JbiPieceKind.wideSignal && cell.piece.column == column) cell.piece.row,
  };
  if (laneRows.isEmpty) return const [];
  final picked = <JbiPiece>[];
  for (var r = template.rows; r < template.rows + addedRows; r++) {
    if (picked.length == count) break;
    if (laneRows.contains(r - 1)) continue;
    laneRows.add(r);
    picked.add(JbiPiece(column, r, JbiPieceKind.wideSignal));
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
    final placed = piece.endRow <= rows ? piece : piece.copyWith(row: rows - 1);
    // Two cards stacked in one lane keep a row of covers between them.
    final touching = kept.any(
      (other) => other.piece.column == placed.column && (other.piece.row - placed.row).abs() == 1,
    );
    if (!fits(placed) || touching) continue;
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
