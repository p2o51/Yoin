/// Curve C halftone geometry of the Dots top seam (SeamHalftone.kt, the Top
/// half; the bottom field is not ported). A staggered lattice anchored to the
/// element; a dot's radius follows its distance below the seam — zero at the
/// seam, whole at band + front. The ragged front and the swirl are scaled by
/// the disorder D and phased by position plus the flow's lag, never by time,
/// so at rest the print is still (and, with D = 0, perfectly regular).
library;

import 'dart:math' as math;

import '../../../theme/tokens.dart';

const double _tau = math.pi * 2;

/// The seams' disorder D (静整动乱): 0 at rest, rising toward 1 while scrolling.
double seamDisorder(double speedDpPerSec) => 1 - math.exp(-math.max(0, speedDpPerSec) / SeamTokens.disorderSpeed);

/// The Dots band G for the flow's stretch.
double seamDotsBand(double stretch) => _lerp(SeamTokens.band, SeamTokens.bandStretched, stretch);

/// The staggered lattice over an element [width] wide.
final class SeamLattice {
  const SeamLattice._(this.columns, this.cellWidth, this.rowHeight, this.fullRadius);

  factory SeamLattice.of(double width, {double pitch = SeamTokens.pitch}) {
    final columns = math.max(4, (width / pitch).round());
    final cellWidth = width / columns;
    final rowHeight = cellWidth * 0.866;
    return SeamLattice._(
      columns,
      cellWidth,
      rowHeight,
      math.sqrt(cellWidth * cellWidth + rowHeight * rowHeight) * 0.53,
    );
  }

  final int columns;
  final double cellWidth;
  final double rowHeight;

  /// A whole dot: covers its cell and overlaps its neighbours.
  final double fullRadius;
}

/// The curve C seam, in the element's own px; [band] (G) and [front] (F) already revealed.
final class SeamDotsSeam {
  const SeamDotsSeam({required this.seam, required this.band, required this.front, required this.flowShift});

  /// The seam's y in the element (positive: the seam is that far below its top).
  final double seam;
  final double band;
  final double front;

  /// The flow's lag behind the content, in bands.
  final double flowShift;

  /// Below this the print is whole.
  double get solid => band + front;

  /// The seam for an element whose top is [offsetFromSeam] below the line, or
  /// null while no dot of it can be touched (or the band hasn't opened yet).
  static SeamDotsSeam? configure({
    required double offsetFromSeam,
    required double reveal,
    required double stretch,
    required double lagPx,
  }) {
    final band = seamDotsBand(stretch) * reveal;
    if (band < 0.5) return null;
    final seam = SeamDotsSeam(
      seam: -offsetFromSeam,
      band: band,
      front: _lerp(SeamTokens.front, SeamTokens.frontStretched, stretch) * reveal,
      flowShift: lagPx / band,
    );
    // Wholly below the seam's reach: untouched.
    return seam.seam + seam.solid + SeamTokens.pitch < 0 ? null : seam;
  }
}

/// One dot: its displaced centre and radius.
typedef SeamDot = ({double x, double y, double radius});

/// Per-dot front offset: the item's bend plus the dot's own jitter, about ±0.22.
double seamBendJitter(double u, int column, int row, double seed) {
  final bend = 0.10 * math.sin((u * 0.9 + seed * 0.37) * _tau) + 0.05 * math.sin((u * 2.3 - seed * 0.21 + 0.3) * _tau);
  final jitter = (_hash(column + seed * 31, row + seed * 7) - 0.5) * 0.14;
  return bend + jitter;
}

/// The dot at ([column], [row]) of [lattice] over an element [width] × [height].
SeamDot seamDot({
  required SeamLattice lattice,
  required double width,
  required double height,
  required int column,
  required int row,
  required double seed,
  required double disorder,
  required SeamDotsSeam top,
}) {
  final stagger = row.isEven ? 0.0 : 0.5;
  final lx = (column + 0.5 + stagger) * lattice.cellWidth;
  final ly = (row + 0.5) * lattice.rowHeight;
  final u = lx / width;
  final v = ly / height;
  var radius = lattice.fullRadius;
  var envelope = 0.0;
  var flow = 0.0;
  final below = ly - top.seam;
  if (below < top.solid) {
    final near = _smooth((below / (top.band * 0.35)).clamp(0.0, 1.0));
    final shifted = below + (seamBendJitter(u, column, row, seed) / 0.22) * top.front * near * disorder;
    final t = (shifted / top.band).clamp(0.0, 1.0);
    radius = t <= 0 ? 0 : SeamTokens.dotRadius * lattice.cellWidth * math.pow(t, SeamTokens.dotGamma);
    envelope = math.sin(t * math.pi) * math.sqrt(1 - t) * disorder;
    flow = shifted / top.band + top.flowShift;
  }
  return (
    x: lx + lattice.cellWidth * 0.42 * math.sin((v * 1.4 + u * 0.45 - flow * 0.8) * _tau) * envelope,
    y: ly + lattice.rowHeight * 0.38 * math.sin((v * 0.7 - u * 1.3 + flow * 0.65) * _tau) * envelope,
    radius: radius,
  );
}

/// The rows of an element's mask that need dots: rows wholly clear of the
/// seam merge into one solid rect from [SeamDotsMask.solidFrom] down.
final class SeamDotsMask {
  const SeamDotsMask({required this.dots, required this.solidFrom});

  final List<SeamDot> dots;

  /// Everything below this y (element px) prints whole.
  final double solidFrom;

  /// The dots of [top] over an element [width] × [height] whose lattice is
  /// anchored at its own origin. Dots under [minRadius] are left out.
  static SeamDotsMask build({
    required double width,
    required double height,
    required SeamDotsSeam top,
    required double seed,
    required double disorder,
    double minRadius = 0.3,
  }) {
    final lattice = SeamLattice.of(width);
    final reach = lattice.rowHeight * 2;
    final solidFrom = math.max(0.0, top.seam + top.solid + reach);
    final rows = (height / lattice.rowHeight).ceil();
    final firstRow = math.max(-1, (top.seam / lattice.rowHeight - 1.5).floor());
    final dots = <SeamDot>[];
    for (var row = firstRow; row <= rows; row++) {
      // Rows whose dots lie wholly inside the solid rect add nothing.
      final centre = (row + 0.5) * lattice.rowHeight;
      if (centre - lattice.fullRadius >= solidFrom) continue;
      for (var column = -1; column <= lattice.columns; column++) {
        final dot = seamDot(
          lattice: lattice,
          width: width,
          height: height,
          column: column,
          row: row,
          seed: seed,
          disorder: disorder,
          top: top,
        );
        if (dot.radius >= minRadius) dots.add(dot);
      }
    }
    return SeamDotsMask(dots: dots, solidFrom: solidFrom);
  }
}

double _smooth(double x) => x * x * (3 - 2 * x);

double _lerp(double a, double b, double t) => a + (b - a) * t;

double _hash(double x, double y) {
  final value = math.sin(x * 127.1 + y * 311.7) * 43758.547;
  return value - value.floorToDouble();
}
