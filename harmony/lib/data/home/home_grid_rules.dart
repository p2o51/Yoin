/// Two-column Home's grid (owner 2026-11-16, plan 「首页格高方案」; Android PR #250 / #292), a Dart port of
/// `:shared` HomeGridRules.kt for parity phase 4. In two columns every section is a whole number of grid
/// units tall, so the two halves of a row always line up: height = T + n·U + (n − 1)·G. Lengths are dp
/// unless a name says px. Replayed against `shared/src/commonTest/resources/golden/home-grid.json`
/// (test/data/home/home_grid_golden_test.dart).
library;

import 'dart:math' as math;

import 'home_catalog.dart';
import 'home_edit_rules.dart';

/// Numbers of the two-column grid, dp.
abstract final class HomeGridTokens {
  /// One unit. 150 fits a 106dp cover and two lines; 120 is the denser option.
  static const double unit = 150;

  /// Between two units of one section.
  static const double gap = 14;

  /// The title row every section has in two columns.
  static const double titleRow = 40;

  /// A unit drag can pull this far past the smallest or largest count (a rubber band past it).
  static const double overdrag = 24;

  /// What a cover card's two caption lines (and the gap above them) take out of a unit: cover = U − 44.
  static const double captionBand = 44;

  /// The narrowest gap between two cover cards in a row; a page's spare width widens it.
  static const double columnGap = 12;

  /// Between two of Recently Added's track rows.
  static const double trackRowGap = 6;

  /// Recently Added's narrowest track column on the grid.
  static const double trackColumnMin = 280;

  /// The narrowest one-column Activities card on the grid.
  static const double activityColumnMin = 160;
}

/// Numbers of the two-column tier, dp.
abstract final class HomeTwoColumnTokens {
  /// The narrowest feed container that lays out in two columns (WindowSizeClass Expanded).
  static const double minWidth = 840;

  /// The shortest one (WindowSizeClass Medium height): a landscape handset never pairs.
  static const double minHeight = 480;

  /// The narrowest content a two-column frame has (840 − 2 × 32).
  static const double minContent = 776;
}

/// Kotlin's `kotlin.math.round`: to the nearest integer, ties to even (Dart's `round` ties away from zero).
double _roundHalfEven(double x) {
  final f = x.floorToDouble();
  final diff = x - f;
  if (diff < .5) return f;
  if (diff > .5) return f + 1;
  return f % 2 == 0 ? f : f + 1;
}

/// Kotlin's `roundToInt`: floor(x + 0.5).
int _roundToInt(double x) => (x + .5).floor();

double _clamp(double v, double lo, double hi) => v < lo ? lo : (v > hi ? hi : v);

int _clampInt(int v, int lo, int hi) => v < lo ? lo : (v > hi ? hi : v);

// ── The two-column tier ──────────────────────────────────────────────────

/// The feed lays out in two columns: its container (the feed's own width and height, not the window's) is
/// at least 840 × 480dp, and the device isn't in a tabletop posture. Android's `homeTwoColumn(info)`;
/// HarmonyOS's `FeedFrameClass.wide`. Pure.
bool homeTwoColumn(double containerWidthDp, double containerHeightDp, bool tabletop) =>
    !tabletop && containerWidthDp >= HomeTwoColumnTokens.minWidth && containerHeightDp >= HomeTwoColumnTokens.minHeight;

// ── Heights and columns ──────────────────────────────────────────────────

/// A cover shelf's cover side at [unit] dp: U − 44 (106 at 150).
double homeGridCoverDp([double unit = HomeGridTokens.unit]) => math.max(unit - HomeGridTokens.captionBand, 1);

/// Cover columns a [widthDp] page seats: ⌊(W + gap) / (cover + gap)⌋, at least 1.
int homeGridColumns(double widthDp, {double? coverDp, double gapDp = HomeGridTokens.columnGap}) {
  final cover = coverDp ?? homeGridCoverDp();
  return math.max(1, ((widthDp + gapDp) / (cover + gapDp)).floor());
}

/// A section's height in two columns at [units] units: T + n·U + (n − 1)·G, in dp.
double homeGridHeightDp(int units) {
  final n = math.max(units, 1);
  return HomeGridTokens.titleRow + n * HomeGridTokens.unit + (n - 1) * HomeGridTokens.gap;
}

/// The content height under the title row at [units] units, dp: n·U + (n − 1)·G.
double homeGridContentDp(int units) => homeGridHeightDp(units) - HomeGridTokens.titleRow;

/// Recently Added's track rows at [units] units: three a unit.
int homeGridTrackRows(int units) => 3 * math.max(units, 1);

/// Recently Added's track columns on the grid: ⌊W / 280⌋, at least 1.
int homeGridTrackColumns(double widthDp) => math.max(1, (widthDp / HomeGridTokens.trackColumnMin).floor());

/// One track row's height at [units] units, dp: the rows and their gaps fill the content box exactly.
double homeGridTrackRowDp(int units, [double gapDp = HomeGridTokens.trackRowGap]) {
  final rows = homeGridTrackRows(units);
  return (homeGridContentDp(units) - (rows - 1) * gapDp) / rows;
}

/// The count whose height ([homeGridHeightDp] × [density] px) is nearest [heightPx] within [range]
/// (the first of equals wins).
int homeUnitsNearest(double heightPx, HomeUnitRange range, double density) {
  int? best;
  var bestDistance = double.infinity;
  for (final n in range.values) {
    final d = (homeGridHeightDp(n) * density - heightPx).abs();
    if (d < bestDistance) {
      best = n;
      bestDistance = d;
    }
  }
  return best ?? range.first;
}

// ── Packing cards into unit rows ─────────────────────────────────────────

/// A card placed on the grid: [index] into the caller's list, [row], first [column], [span] columns.
final class HomeGridCell {
  const HomeGridCell(this.index, this.row, this.column, this.span);

  final int index;
  final int row;
  final int column;
  final int span;

  @override
  bool operator ==(Object other) =>
      other is HomeGridCell && other.index == index && other.row == row && other.column == column && other.span == span;

  @override
  int get hashCode => Object.hash(index, row, column, span);
}

/// Cards of [spans] columns each, row-major into [rows] rows of [columns]: a card that doesn't fit what
/// is left of a row starts the next; a span wider than the row is one column. Cards past the last row are
/// left out; no row starts with a gap. Pure.
List<HomeGridCell> homeGridPack(List<int> spans, int columns, int rows) {
  final cols = math.max(columns, 1);
  final out = <HomeGridCell>[];
  var row = 0;
  var column = 0;
  for (var index = 0; index < spans.length; index++) {
    final span = _clampInt(spans[index], 1, cols);
    if (column + span > cols) {
      row++;
      column = 0;
    }
    if (row >= rows) break;
    out.add(HomeGridCell(index, row, column, span));
    column += span;
  }
  return out;
}

/// Activities on the grid: one row of cards a unit. The lead entry spans two columns; after it the rows
/// alternate a wide card (two columns) and small ones (one). [columns] from [homeGridActivityColumns]. Pure.
List<int> homeGridActivitySpans(int count, int columns) {
  if (columns <= 1) return List.filled(count, 1);
  final out = <int>[];
  var column = 0;
  var inRow = 0;
  for (var i = 0; i < count; i++) {
    final span = inRow % 2 == 0 && columns - column >= 2 ? 2 : 1;
    out.add(span);
    column += span;
    inRow++;
    if (column >= columns) {
      column = 0;
      inRow = 0;
    }
  }
  return out;
}

/// Activities' columns on the grid: ⌊(W + gap) / (160 + gap)⌋, at least 2.
int homeGridActivityColumns(double widthDp, [double gapDp = HomeGridTokens.columnGap]) =>
    math.max(2, ((widthDp + gapDp) / (HomeGridTokens.activityColumnMin + gapDp)).floor());

/// A row's [columns] cells across [widthPx], [gapPx] apart, snapped to whole px: (left, width) of a cell
/// starting at [column] spanning [span]. Pure.
(int, int) homeGridCellX(int column, int span, int columns, int widthPx, int gapPx) {
  final cols = math.max(columns, 1);
  final cell = (widthPx - gapPx * (cols - 1)) / cols;
  final left = _roundHalfEven(column * (cell + gapPx)).toInt();
  final right = _roundHalfEven((column + span) * (cell + gapPx) - gapPx).toInt();
  return (left, math.max(right - left, 0));
}

/// The grid moves from [last] (0 for none yet) to [now]: its cells re-seat in place, at once. Pure.
bool homeGridReseats(int last, int now) => last != 0 && last != now;

// ── Rows of two halves ───────────────────────────────────────────────────

/// Where [section] sits in [rows] for a span drag ([HomeSpanInfo]); null when it isn't laid out. [fullPx]
/// is the content width. A full row can turn half only with the next enabled section displayed right after
/// it ([enabledOrder]).
HomeSpanInfo? homeSpanInfoOf(
  HomeSection section,
  List<HomeFeedRow> rows,
  List<HomeSection> enabledOrder,
  double fullPx,
  double gapPx,
) {
  final index = rows.indexWhere((row) => row.sections.contains(section));
  if (index < 0 || fullPx <= 0) return null;
  final halfPx = (fullPx - gapPx) / 2;
  final row = rows[index];
  switch (row) {
    case HomeFeedRowFull():
      final at = enabledOrder.indexOf(section) + 1;
      final next = at >= 0 && at < enabledOrder.length ? enabledOrder[at] : null;
      return HomeSpanInfo(
        span: HomeSpan.full,
        currentPx: fullPx,
        halfPx: halfPx,
        fullPx: fullPx,
        hasNext: next != null && index + 1 < rows.length && rows[index + 1].first == next,
      );
    case HomeFeedRowHalves(:final left):
      return HomeSpanInfo(
        span: left == section ? HomeSpan.leftHalf : HomeSpan.rightHalf,
        currentPx: halfPx,
        halfPx: halfPx,
        fullPx: fullPx,
        hasNext: true,
      );
  }
}

/// Widths (px) of a row's cells: a full row its section; a pair two halves [gapPx] apart. Under a span
/// drag ([dragged], [dragPx]) the dragged cell is [dragPx] wide and its partner takes what is left (never
/// under half a half). Pure.
List<int> homeRowCellWidths(HomeFeedRow row, int widthPx, int gapPx, HomeSection? dragged, int dragPx) {
  final half = (widthPx - gapPx) ~/ 2;
  switch (row) {
    case HomeFeedRowFull(:final section):
      return [dragged == section ? _clampInt(dragPx, 1, widthPx) : widthPx];
    case HomeFeedRowHalves(:final left, :final right):
      final minPartner = half ~/ 2;
      if (dragged == left) {
        final l = _clampInt(dragPx, 1, widthPx);
        return [l, math.max(widthPx - gapPx - l, minPartner)];
      }
      if (dragged == right) {
        final r = _clampInt(dragPx, 1, widthPx);
        return [math.max(widthPx - gapPx - r, minPartner), r];
      }
      return [half, widthPx - gapPx - half];
  }
}

// ── The half-column shelf pager ──────────────────────────────────────────

/// Numbers of the half-column pager, dp.
abstract final class HomeShelfPagerRules {
  /// The narrowest gap between two cards on a page; the page's spare width widens it.
  static const double minGap = 14;

  /// A release this fast (dp/s) turns the page even short of halfway.
  static const double flingVelocity = 400;
}

/// A page's layout: [perPage] cards of [cardPx], [gapPx] apart, one page every [stridePx].
final class ShelfPageGeometry {
  const ShelfPageGeometry({required this.pagePx, required this.cardPx, required this.perPage, required this.gapPx});

  final double pagePx;
  final double cardPx;
  final int perPage;
  final double gapPx;

  /// Scroll distance from one page to the next: a page plus the gap after it.
  double get stridePx => perPage * (cardPx + gapPx);

  @override
  bool operator ==(Object other) =>
      other is ShelfPageGeometry &&
      other.pagePx == pagePx &&
      other.cardPx == cardPx &&
      other.perPage == perPage &&
      other.gapPx == gapPx;

  @override
  int get hashCode => Object.hash(pagePx, cardPx, perPage, gapPx);
}

/// How [cardPx]-wide cards page across a [pagePx]-wide column: as many as fit with at least [minGapPx]
/// between them (at least one), the gaps stretched so the first card's left and the last one's right are
/// the column's edges. A single card per page keeps [minGapPx] past the page. Pure.
ShelfPageGeometry shelfPageGeometry(double pagePx, double cardPx, double minGapPx) {
  final page = math.max(pagePx, 1.0);
  final card = _clamp(cardPx, 1, page);
  final perPage = math.max(1, ((page + minGapPx) / (card + minGapPx)).floor());
  final gap = perPage > 1 ? (page - perPage * card) / (perPage - 1) : page - card + minGapPx;
  return ShelfPageGeometry(pagePx: page, cardPx: card, perPage: perPage, gapPx: gap);
}

/// A card's width (dp) when [perPage] of them, [HomeShelfPagerRules.minGap] apart, fill a [pageWidthDp]
/// page; at least 1.
double homeShelfCardWidthDp(double pageWidthDp, int perPage) =>
    math.max((pageWidthDp - HomeShelfPagerRules.minGap * (perPage - 1)) / perPage, 1);

/// Pages of [cardCount] cards after [leadPages] one-page lead items. Pure.
int shelfPageCount(int cardCount, int perPage, [int leadPages = 0]) =>
    leadPages + (cardCount <= 0 ? 0 : (cardCount / perPage).ceil());

/// The trailing filler that makes the last page whole, px (0 when it already is). Pure.
double shelfFillerPx(int cardCount, ShelfPageGeometry geometry) {
  if (cardCount <= 0) return 0;
  final missing = (geometry.perPage - cardCount % geometry.perPage) % geometry.perPage;
  return missing == 0 ? 0 : missing * (geometry.cardPx + geometry.gapPx) - geometry.gapPx;
}

/// The shelf's scroll from its start, px: item [index] [offsetPx] past the viewport's start, every card
/// one card-plus-gap, a lead item one stride. Pure.
double shelfScrollPx(int index, int offsetPx, ShelfPageGeometry geometry, [int leadPages = 0]) {
  final before = index < leadPages
      ? index * geometry.stridePx
      : leadPages * geometry.stridePx + (index - leadPages) * (geometry.cardPx + geometry.gapPx);
  return before + offsetPx;
}

/// How far [scrollPx] is from the nearest page boundary, px (0 = settled on a page). Pure.
double shelfOffPagePx(double scrollPx, double stridePx) {
  if (stridePx <= 0) return 0;
  // Kotlin's % keeps the dividend's sign; Dart's remainder() does the same.
  final into = (scrollPx.remainder(stridePx) + stridePx).remainder(stridePx);
  return math.min(into, stridePx - into);
}

/// The page a release lands on: one page from [startPage] in the release's direction — the swipe's own if
/// it is past [flingPx]/s, else whichever page [scrollPx] is closer to — never more than one page away,
/// within the shelf. Pure.
int shelfReleasePage(
  double scrollPx,
  double velocityPx,
  double stridePx,
  int startPage,
  int pageCount,
  double flingPx,
) {
  if (pageCount <= 1 || stridePx <= 0) return 0;
  final position = scrollPx / stridePx;
  final int target;
  if (velocityPx > flingPx) {
    target = position.floor() + 1;
  } else if (velocityPx < -flingPx) {
    target = position.ceil() - 1;
  } else {
    target = _roundToInt(position);
  }
  return _clampInt(_clampInt(target, startPage - 1, startPage + 1), 0, pageCount - 1);
}

/// The tide's reveal at [offPagePx] from a settled page: 0 settled, 1 from [revealPx] on. Pure.
double shelfTideReveal(double offPagePx, double revealPx) => revealPx <= 0 ? 0 : _clamp(offPagePx / revealPx, 0, 1);
