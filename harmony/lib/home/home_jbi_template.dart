/// Jump Back In, templated (HomeJbiTemplate.kt; owner 2026-10-04: the stagger
/// has to read across AND down, and no cover may be bigger than its
/// neighbours). On feeds of 3+ units the shelf is K lanes of phone-sized cover
/// columns, every cover the same size. Each lane starts at its own height —
/// lane 0 at the top, every next one drifting to 0 / ¼ / ½ of a row, never
/// its left neighbour's — so no row line runs across the grid. A seed from the
/// data (the leading cover) picks the drift, so the same shelf always lands
/// the same way; a wider pane gets more lanes, never bigger covers.
///
/// Covers only: Harmony's Jump Back In has no rating / note signal cards yet,
/// so the template is Android's with zero signals — the same draws from the
/// same seeded generator ([KotlinRandom]), so a shelf with the same leading id
/// staggers exactly as on Android. Pure; unit-tested.
library;

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

/// A cover at its cell; [row] counts from its lane's own top.
final class JbiPiece {
  const JbiPiece(this.column, this.row);
  final int column;
  final int row;

  @override
  bool operator ==(Object other) => other is JbiPiece && other.column == column && other.row == row;

  @override
  int get hashCode => Object.hash(column, row);

  @override
  String toString() => 'JbiPiece($column, $row)';
}

/// [columns] lanes of [rows] cells. [offsets] (one per lane, in row pitches)
/// is how far each lane starts below the grid's top. [pieces] are in reading
/// order: by where they sit on screen (row + offset), then column.
final class JbiTemplate {
  const JbiTemplate({required this.columns, required this.rows, required this.offsets, required this.pieces});
  final int columns;
  final int rows;
  final List<double> offsets;
  final List<JbiPiece> pieces;

  int get covers => pieces.length;

  /// Where [piece] sits, in row pitches from the grid's top.
  double topOf(JbiPiece piece) => piece.row + offsets[piece.column];
}

/// A card seated on its piece.
typedef JbiCell = ({HomeWidgetCard card, JbiPiece piece});

/// A [template] with its [cells] (same order as the template's pieces).
final class JbiLayout {
  const JbiLayout(this.template, this.cells);
  final JbiTemplate template;
  final List<JbiCell> cells;
}

/// jbiTemplate with no signal cards: lane 0 at 0, each next lane at one of
/// [HomeJbiMetrics.driftSteps] other than its left neighbour's, by the seed.
JbiTemplate jbiTemplate(int columns, int rows, int seed) {
  final random = KotlinRandom((seed * 1000003).toSigned(32) + columns * 101 + rows * 13);
  final offsets = <double>[];
  for (var i = 0; i < columns; i++) {
    if (i == 0) {
      offsets.add(0);
    } else {
      final choices = HomeJbiMetrics.driftSteps.where((s) => s != offsets.last).toList();
      offsets.add(choices[random.nextIntBelow(choices.length)]);
    }
  }
  final pieces = [
    for (var r = 0; r < rows; r++)
      for (var c = 0; c < columns; c++) JbiPiece(c, r),
  ]..sort((a, b) {
      final byTop = (a.row + offsets[a.column]).compareTo(b.row + offsets[b.column]);
      return byTop != 0 ? byTop : a.column.compareTo(b.column);
    });
  return JbiTemplate(columns: columns, rows: rows, offsets: offsets, pieces: pieces);
}

/// Seats [cards] (covers, in ranking order) on [columns] lanes of up to [rows]
/// cells. A shelf too short for [rows] takes one row fewer, down to one;
/// null = not even one row (the caller falls back to the plain column grid).
JbiLayout? jbiLayout(List<HomeWidgetCard> cards, {required int columns, required int rows, required int seed}) {
  if (columns < 2 || rows < 1) return null;
  for (var r = rows; r >= 1; r--) {
    final template = jbiTemplate(columns, r, seed);
    if (template.covers > cards.length) continue;
    return JbiLayout(template, [
      for (var i = 0; i < template.pieces.length; i++) (card: cards[i], piece: template.pieces[i]),
    ]);
  }
  return null;
}

/// The shelf's seed: its leading cover's id.
int jbiLayoutSeed(List<HomeWidgetCard> cards) => activityLayoutSeed(cards.isEmpty ? null : cards.first.stableId);
