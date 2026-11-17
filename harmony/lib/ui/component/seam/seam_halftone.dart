/// Halftone geometry of the seams (SeamHalftone.kt). A staggered lattice
/// anchored to the element; every dot is evaluated against each active seam
/// and keeps the smallest radius (and that seam's swirl):
///
///  - Top ([SeamDotsSeam]): curve C under fixed chrome, the Dots style. The
///    radius follows the dot's distance below the seam — zero at the seam,
///    whole at band + front.
///  - Tail ([SeamTail]): the bottom field. Content opens up over the
///    approach, runs on under the floating bar as a still lattice to the
///    screen's bottom edge, and dots whose lightness is close to the bar's
///    give way next to it.
///
/// The ragged front and the swirl are scaled by the disorder D and phased by
/// position plus the flow's lag, never by time, so at rest the print is still
/// (and, with D = 0, perfectly regular).
library;

import 'dart:math' as math;
import 'dart:ui' show Color, Offset, Rect, Size;

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

/// The dot at ([column], [row]) of [lattice] over an element [width] ×
/// [height], against the [top] seam and the [tail] field (either may be
/// absent). [lum] is the element's CIELAB L* at the dot's site (NaN when
/// unknown or transparent); only the tail's lightness yield reads it.
SeamDot seamDot({
  required SeamLattice lattice,
  required double width,
  required double height,
  required int column,
  required int row,
  required double seed,
  required double disorder,
  SeamDotsSeam? top,
  SeamTail? tail,
  double lum = double.nan,
}) {
  final stagger = row.isEven ? 0.0 : 0.5;
  final lx = (column + 0.5 + stagger) * lattice.cellWidth;
  final ly = (row + 0.5) * lattice.rowHeight;
  final u = lx / width;
  final v = ly / height;
  final bendJitter = seamBendJitter(u, column, row, seed);
  var radius = lattice.fullRadius;
  var envelope = 0.0;
  var flow = 0.0;
  var touched = false;
  if (top != null) {
    final below = ly - top.seam;
    if (below < top.solid) {
      final near = _smooth((below / (top.band * 0.35)).clamp(0.0, 1.0));
      final shifted = below + (bendJitter / 0.22) * top.front * near * disorder;
      final t = (shifted / top.band).clamp(0.0, 1.0);
      radius = t <= 0 ? 0 : SeamTokens.dotRadius * lattice.cellWidth * math.pow(t, SeamTokens.dotGamma);
      envelope = math.sin(t * math.pi) * math.sqrt(1 - t) * disorder;
      flow = shifted / top.band + top.flowShift;
      touched = true;
    }
  }
  if (tail != null) {
    var tailRadius = lattice.fullRadius;
    var tailEnvelope = 0.0;
    var tailFlow = 0.0;
    var tailTouched = false;
    final fromStart = ly - tail.y0;
    if (fromStart > 0) {
      var approach = 1.0;
      final double k;
      if (ly < tail.yb && tail.len > 0.5) {
        final front =
            (bendJitter / 0.22) * tail.front * _smooth((fromStart / tail.frontNear).clamp(0.0, 1.0)) * disorder;
        approach = ((fromStart + front) / tail.len).clamp(0.0, 1.0);
        tailEnvelope = math.sin(approach * math.pi) * disorder;
        k = _lerp(SeamTokens.fieldApproachRadius, tail.k0, _smooth(approach));
      } else {
        k = _lerp(tail.k0, tail.k1, ((ly - tail.yb) / math.max(1, tail.end - tail.yb)).clamp(0.0, 1.0));
      }
      tailRadius = _lerp(lattice.fullRadius, k * lattice.cellWidth, tail.reveal);
      tailEnvelope *= tail.reveal;
      tailFlow = approach * 2 + tail.flowShift;
      tailTouched = true;
    }
    if (!lum.isNaN) {
      final toBar = tail.barDistance(lx, ly);
      if (toBar < tail.yieldReach + tail.yieldInset) {
        final yielded = tailRadius * seamYieldFactor(tail, lum, ly, toBar);
        if (yielded < tailRadius) {
          tailRadius = yielded;
          tailTouched = true;
        }
      }
    }
    if (tailTouched && (!touched || tailRadius < radius)) {
      radius = tailRadius;
      envelope = tailEnvelope;
      flow = tailFlow;
      touched = true;
    }
  }
  return (
    x: lx + lattice.cellWidth * 0.42 * math.sin((v * 1.4 + u * 0.45 - flow * 0.8) * _tau) * envelope,
    y: ly + lattice.rowHeight * 0.38 * math.sin((v * 0.7 - u * 1.3 + flow * 0.65) * _tau) * envelope,
    radius: radius,
  );
}

/// The lattice site of ([column], [row]): where a dot sits when nothing moves it.
({double x, double y}) seamSite(SeamLattice lattice, int column, int row) =>
    (x: (column + 0.5 + (row.isEven ? 0.0 : 0.5)) * lattice.cellWidth, y: (row + 0.5) * lattice.rowHeight);

/// The bottom field around the floating bar, in the print's own px (window
/// geometry translated by the print's origin).
final class SeamTail {
  const SeamTail({
    required this.y0,
    required this.yb,
    required this.len,
    required this.end,
    required this.guard,
    required this.k0,
    required this.k1,
    required this.front,
    required this.frontNear,
    required this.flowShift,
    required this.reveal,
    required this.bar,
    required this.barRadius,
    required this.barLum,
    required this.bgLum,
    required this.yieldReach,
    required this.yieldInset,
    required this.fadeEnd,
    required this.page,
  });

  /// The approach starts here and ends at [yb] (behind the bar's top edge); [len] = yb − y0.
  final double y0;
  final double yb;
  final double len;

  /// The screen's bottom edge.
  final double end;

  /// Above this no row can be in the bar's lightness reach.
  final double guard;

  /// The pure field's radius (cells) at the bar and at the screen's edge.
  final double k0;
  final double k1;
  final double front;
  final double frontNear;

  /// The flow's lag behind the content, in [SeamTokens.fieldFlowScale] units.
  final double flowShift;

  /// Field presence (0..1): the whole field fades while the bar is away.
  final double reveal;

  /// The bar's rounded rectangle and its corner.
  final Rect bar;
  final double barRadius;

  /// CIELAB L* of the bar and of the page at the bar.
  final double barLum;
  final double bgLum;
  final double yieldReach;
  final double yieldInset;

  /// The 退色 ramp's far end: the screen's bottom edge plus a little overshoot.
  final double fadeEnd;

  /// The page colour the dots ease toward (退色).
  final Color page;

  /// Rows above this (print px) are untouched by the field.
  double get top => math.min(y0, guard);

  /// How far the dots have eased toward the page colour at [y] (before [reveal]).
  double fadeAt(double y) {
    if (y <= y0) return 0;
    if (y < bar.top) return SeamTokens.fieldFadeAtBar * (y - y0) / math.max(1, bar.top - y0);
    final depth = ((y - bar.top) / math.max(1, fadeEnd - bar.top)).clamp(0.0, 1.0);
    return SeamTokens.fieldFadeAtBar + (SeamTokens.fieldFadeAtEdge - SeamTokens.fieldFadeAtBar) * depth;
  }

  /// Signed distance from ([x], [y]) to the bar's rounded rectangle (negative inside).
  double barDistance(double x, double y) {
    final halfWidth = bar.width * 0.5;
    final halfHeight = bar.height * 0.5;
    final radius = math.min(barRadius, math.min(halfWidth, halfHeight));
    final qx = (x - (bar.left + halfWidth)).abs() - (halfWidth - radius);
    final qy = (y - (bar.top + halfHeight)).abs() - (halfHeight - radius);
    final ox = math.max(qx, 0.0), oy = math.max(qy, 0.0);
    return math.sqrt(ox * ox + oy * oy) + math.min(math.max(qx, qy), 0.0) - radius;
  }

  /// Whether a row at [y] can be within the lightness yield's reach of the bar.
  bool yieldRow(double y) => y > bar.top - yieldReach - yieldInset && y < bar.bottom + yieldReach + yieldInset;

  /// The field for a print [printSize] whose top-left is [printTopLeft] in
  /// the window, or null while no row of it can be touched. [bar] is the
  /// bar's rounded rectangle in the window, [rootHeight] the window's height;
  /// [reveal] the field's presence; [remaining] how far the content can still
  /// scroll (the approach shrinks over the last [SeamTokens.fieldRestDistance]);
  /// [pageAtBar] / [pageAtEdge] the page colour behind the bar's top and at
  /// the window's bottom; [barColor] the bar's container (null: the page's).
  static SeamTail? configure({
    required Offset printTopLeft,
    required Size printSize,
    required Rect bar,
    required double barRadius,
    required Color? barColor,
    required double rootHeight,
    required double reveal,
    required double remaining,
    required double stretch,
    required double lagPx,
    required Color pageAtBar,
    required Color pageAtEdge,
  }) {
    if (reveal <= 0.001 || bar.isEmpty || rootHeight <= 0 || printSize.isEmpty) return null;
    final rest = (remaining / SeamTokens.fieldRestDistance).clamp(0.0, 1.0);
    const hidden = SeamTokens.fieldHidden;
    final length = (SeamTokens.fieldStart + hidden + SeamTokens.fieldStretch * stretch) * rest;
    final yb = bar.top + hidden;
    final y0 = yb - length;
    final guard = bar.top - (SeamTokens.yieldReach + SeamTokens.yieldInset) - 1;
    // Wholly above the field and the bar's guard rows: untouched.
    final lattice = SeamLattice.of(printSize.width);
    if (printTopLeft.dy + printSize.height < math.min(y0, guard) - lattice.rowHeight * 2) return null;
    final k0 = seamRadiusForCoverage(SeamTokens.fieldCoverage);
    final dx = printTopLeft.dx, dy = printTopLeft.dy;
    return SeamTail(
      y0: y0 - dy,
      yb: yb - dy,
      len: length,
      end: rootHeight - dy,
      guard: guard - dy,
      k0: k0,
      k1: k0 * SeamTokens.fieldThinning,
      front: SeamTokens.fieldFront,
      frontNear: SeamTokens.fieldFrontNear,
      flowShift: lagPx / SeamTokens.fieldFlowScale,
      reveal: reveal.clamp(0.0, 1.0),
      bar: bar.shift(Offset(-dx, -dy)),
      barRadius: barRadius,
      barLum: seamLightness(barColor ?? pageAtBar),
      bgLum: seamLightness(pageAtBar),
      yieldReach: SeamTokens.yieldReach,
      yieldInset: SeamTokens.yieldInset,
      fadeEnd: rootHeight + SeamTokens.fieldFadeOvershoot - dy,
      page: Color.lerp(pageAtBar, pageAtEdge, 0.5)!,
    );
  }
}

/// 按亮度让位: a dot whose lightness (after 退色) is within reach of the bar's
/// shrinks to nothing [SeamTail.yieldInset] from the bar's edge and is whole
/// again [SeamTail.yieldReach] further out. ΔL* ≤ 6 yields fully, ≥ 18 not at all.
double seamYieldFactor(SeamTail tail, double lum, double y, double toBar) {
  final seen = _lerp(lum, tail.bgLum, tail.fadeAt(y) * tail.reveal);
  const span = SeamTokens.yieldNoneDelta - SeamTokens.yieldFullDelta;
  final apart = (((seen - tail.barLum).abs() - SeamTokens.yieldFullDelta) / span).clamp(0.0, 1.0);
  final need = (1 - _smooth(apart)) * tail.reveal;
  if (need <= 0) return 1;
  return _lerp(1, _smooth(((toBar - tail.yieldInset) / tail.yieldReach).clamp(0.0, 1.0)), need);
}

/// CIELAB L* (0–100) of [color]: the tone axis M3's palettes are built on (HCT tone = L*).
double seamLightness(Color color) => seamLightnessRgb(color.r, color.g, color.b);

/// L* of an sRGB colour given as 0–1 channels.
double seamLightnessRgb(double red, double green, double blue) {
  final y = 0.2126 * _linear(red) + 0.7152 * _linear(green) + 0.0722 * _linear(blue);
  return y > 0.008856 ? 116 * math.pow(y, 1 / 3) - 16 : 903.3 * y;
}

double _linear(double channel) =>
    channel <= 0.04045 ? channel / 12.92 : math.pow((channel + 0.055) / 1.055, 2.4).toDouble();

/// Field dot radius, in cells, for a lattice coverage (the share of the area the dots print).
double seamRadiusForCoverage(double coverage) => math.sqrt(coverage * 0.866 / math.pi);

/// The dots of an element's mask: rows wholly clear of every seam merge into
/// one solid band, [solidFrom] to [solidTo].
final class SeamDotsMask {
  const SeamDotsMask({required this.dots, required this.solidFrom, this.solidTo = double.infinity});

  final List<SeamDot> dots;

  /// From here (element px) the print is whole…
  final double solidFrom;

  /// …down to here (infinite: to the bottom, no bottom field).
  final double solidTo;

  /// The mask leaves every pixel of the band [from, to) whole.
  bool covers(double from, double to) => solidFrom <= from && solidTo >= to;

  /// The dots of [top] and [tail] over an element [width] × [height] whose
  /// lattice is anchored at its own origin. Dots under [minRadius] are left
  /// out, and so are dots wholly under the (opaque) bar once it is fully in.
  /// [lum] gives the content's L* at a lattice site (NaN: unknown). [within]
  /// limits the work to the dots that can reach that part of the print (one
  /// piece of it).
  static SeamDotsMask build({
    required double width,
    required double height,
    SeamDotsSeam? top,
    SeamTail? tail,
    required double seed,
    required double disorder,
    double minRadius = SeamTokens.minDotRadius,
    double Function(int column, int row)? lum,
    Rect? within,
  }) {
    final lattice = SeamLattice.of(width);
    final reach = lattice.rowHeight * 2;
    final solidFrom = top == null ? 0.0 : math.max(0.0, top.seam + top.solid + reach);
    final solidTo = tail == null ? double.infinity : math.min(height, tail.top - reach);
    final rows = (height / lattice.rowHeight).ceil();
    final firstRow = top == null ? -1 : math.max(-1, (top.seam / lattice.rowHeight - 1.5).floor());
    final dots = <SeamDot>[];
    // A dot (with its swirl) reaches no further than two rows / columns from its site.
    final rowFrom = within == null ? firstRow : math.max(firstRow, ((within.top - reach) / lattice.rowHeight).floor());
    final rowTo = within == null ? rows : math.min(rows, ((within.bottom + reach) / lattice.rowHeight).ceil());
    final columnFrom = within == null ? -1 : math.max(-1, (within.left / lattice.cellWidth).floor() - 3);
    final columnTo = within == null
        ? lattice.columns
        : math.min(lattice.columns, (within.right / lattice.cellWidth).ceil() + 2);
    for (var row = rowFrom; row <= rowTo; row++) {
      // Rows whose dots lie wholly inside the solid band add nothing.
      final centre = (row + 0.5) * lattice.rowHeight;
      if (centre - lattice.fullRadius >= solidFrom && centre + lattice.fullRadius <= solidTo) continue;
      for (var column = columnFrom; column <= columnTo; column++) {
        final dot = seamDot(
          lattice: lattice,
          width: width,
          height: height,
          column: column,
          row: row,
          seed: seed,
          disorder: disorder,
          top: top,
          tail: tail,
          lum: tail != null && lum != null && tail.yieldRow(centre) ? lum(column, row) : double.nan,
        );
        if (dot.radius < minRadius) continue;
        // Wholly under the opaque bar: skipped, once the bar is fully there.
        if (tail != null && tail.reveal >= 1 && tail.barDistance(dot.x, dot.y) < -dot.radius - 0.5) continue;
        dots.add(dot);
      }
    }
    return SeamDotsMask(dots: dots, solidFrom: solidFrom, solidTo: solidTo);
  }
}

double _smooth(double x) => x * x * (3 - 2 * x);

double _lerp(double a, double b, double t) => a + (b - a) * t;

double _hash(double x, double y) {
  final value = math.sin(x * 127.1 + y * 311.7) * 43758.547;
  return value - value.floorToDouble();
}
