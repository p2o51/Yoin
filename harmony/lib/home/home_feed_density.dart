/// Home feed density (FeedUnits.kt, HomeFeedDensity.kt): a wider container
/// gets MORE cards and columns, never bigger ones — card insides (Activities
/// 96 / 80 / 48 covers, the 100 dp JBI cover, Recently Added's 52 / 82, every
/// type size) are the same on every screen. The discrete inputs (frame class,
/// feed units N, cover columns) come from the container's RESTING width
/// ([YoinWindowInfo]); margins, column widths and covers follow the live width
/// in layout. Phones (N ≤ 2) and the landscape handset keep their
/// compositions byte for byte. Pure: no Flutter widgets, unit-tested directly.
library;

import 'dart:math' as math;

import '../theme/tokens.dart';
import '../ui/adaptive/yoin_window_info.dart';
import 'home_metrics.dart';

// ---------------------------------------------------------------------------
// Frame.

/// The page frame a feed is laid into, judged height first.
enum FeedFrameClass {
  /// Height < 480: 24 dp margins, full width.
  landscape,

  /// Compact / Medium: 16 dp margins, content capped at 688 (the 720 column).
  capped,

  /// Tabletop: the same capped column, always centred (it can rest wider than 840).
  cappedCentred,

  /// Wide (≥ 840) and tall: the canvas edge to edge inside 32 dp margins.
  wide,
}

/// feedFrameClass: compact height → landscape; tabletop → cappedCentred; ≥ 840 → wide; else capped.
FeedFrameClass feedFrameClassOf(YoinWindowInfo info) {
  if (info.isCompactHeight) return FeedFrameClass.landscape;
  if (info.isTabletop) return FeedFrameClass.cappedCentred;
  if (info.layoutMode == LayoutMode.wide) return FeedFrameClass.wide;
  return FeedFrameClass.capped;
}

/// The content width C inside a [width]-wide container: Wide W − 64,
/// Capped min(W, 720) − 32, Landscape W − 48. Never negative.
double feedContentWidth(double width, FeedFrameClass frame) => math.max(
      0,
      switch (frame) {
        FeedFrameClass.landscape => width - 2 * HomeFeedMetrics.landscapeMargin,
        FeedFrameClass.wide => width - 2 * HomeFeedMetrics.wideMargin,
        FeedFrameClass.capped ||
        FeedFrameClass.cappedCentred =>
          math.min(width, HomeFeedMetrics.cappedContainerMax) - 2 * HomeFeedMetrics.cappedMargin,
      },
    );

/// feedUnitsFor: non-Wide C < 310 → 1, C < 448 → 2, else ⌊(C + 10) / 140⌋ in
/// 3..4; Wide ⌊(C + 10) / 140⌋ in 5..8. As container widths: 342 / 480 / 582 /
/// 600 / 840 / 894 / 1034 / 1174.
int feedUnitsFor(double contentWidth, {required bool wide}) {
  final c = contentWidth + HomeFeedMetrics.edgeEpsilon;
  final fitted = ((c + HomeFeedMetrics.unitGap) / HomeFeedMetrics.unitPitch).floor();
  if (wide) return fitted.clamp(HomeFeedMetrics.wideMinUnits, HomeFeedMetrics.maxUnits);
  if (c < HomeFeedMetrics.phoneNarrowBelow) return 1;
  if (c < HomeFeedMetrics.threeUnitsMinContent) return 2;
  return fitted.clamp(3, HomeFeedMetrics.cappedMaxUnits);
}

/// feedCoverColumnsFor: phone-sized (≥ 112 dp, 12 apart) cover columns,
/// ⌊(C + 12) / 124⌋ in 3..10. As content widths: 360 / 484 / 608 / 732 / 856 / 980 / 1104 / 1228.
int feedCoverColumns(double contentWidth) =>
    ((contentWidth + HomeFeedMetrics.edgeEpsilon + HomeFeedMetrics.coverColumnGutter) / HomeFeedMetrics.coverColumnUnit)
        .floor()
        .clamp(HomeFeedMetrics.minCoverColumns, HomeFeedMetrics.maxCoverColumns);

/// The feed's side margins (content = W − start − end).
final class FeedFrameInsets {
  const FeedFrameInsets(this.start, this.end);
  final double start;
  final double end;

  static FeedFrameInsets lerp(FeedFrameInsets a, FeedFrameInsets b, double t) =>
      FeedFrameInsets(a.start + (b.start - a.start) * t, a.end + (b.end - a.end) * t);

  @override
  bool operator ==(Object other) => other is FeedFrameInsets && other.start == start && other.end == end;

  @override
  int get hashCode => Object.hash(start, end);

  @override
  String toString() => 'FeedFrameInsets($start, $end)';
}

/// feedFrameInsets. Landscape 24 / 24; Wide 32 / 32; Capped start = 16 +
/// max(0, (min(W, 840) − 720) / 2), content = min(W − start − 16, 688), end =
/// the rest — today's centred column at rest (800 → 56), start-aligned past 840
/// (only mid-spring); CappedCentred always centres.
FeedFrameInsets feedFrameInsets(double width, FeedFrameClass frame) {
  const m = HomeFeedMetrics.cappedMargin;
  const maxContent = HomeFeedMetrics.cappedContainerMax - 2 * m;
  final w = math.max(0.0, width);
  switch (frame) {
    case FeedFrameClass.landscape:
      return const FeedFrameInsets(HomeFeedMetrics.landscapeMargin, HomeFeedMetrics.landscapeMargin);
    case FeedFrameClass.wide:
      return const FeedFrameInsets(HomeFeedMetrics.wideMargin, HomeFeedMetrics.wideMargin);
    case FeedFrameClass.capped || FeedFrameClass.cappedCentred:
      final centringWidth = frame == FeedFrameClass.capped ? math.min(w, HomeFeedMetrics.cappedRestMax) : w;
      final start = m + math.max(0.0, (centringWidth - HomeFeedMetrics.cappedContainerMax) / 2);
      final content = math.max(0.0, math.min(w - start - m, maxContent));
      return FeedFrameInsets(start, math.max(0.0, w - start - content));
  }
}

/// The discrete side of the feed, from the container's resting width: changes
/// only when the rest width crosses a threshold, never mid-spring.
final class HomeFeedDensity {
  const HomeFeedDensity({required this.frameClass, required this.units, required this.coverColumns});

  /// Reads [info] (the container's own, resting, width and height).
  factory HomeFeedDensity.of(YoinWindowInfo info) {
    final frame = feedFrameClassOf(info);
    final content = feedContentWidth(info.width, frame);
    return HomeFeedDensity(
      frameClass: frame,
      units: feedUnitsFor(content, wide: info.layoutMode == LayoutMode.wide && !info.isTabletop),
      coverColumns: feedCoverColumns(content),
    );
  }

  final FeedFrameClass frameClass;

  /// Feed units N (1..8).
  final int units;

  /// Phone-sized cover columns (3..10).
  final int coverColumns;

  /// A landscape handset (height < 480).
  bool get compactHeight => frameClass == FeedFrameClass.landscape;

  @override
  bool operator ==(Object other) =>
      other is HomeFeedDensity && other.frameClass == frameClass && other.units == units && other.coverColumns == coverColumns;

  @override
  int get hashCode => Object.hash(frameClass, units, coverColumns);

  @override
  String toString() => 'HomeFeedDensity($frameClass, N=$units, K=$coverColumns)';
}

// ---------------------------------------------------------------------------
// Activities bento.

/// How the bento composes. Only [units] is described by data.
enum BentoRecipe {
  /// Landscape handset: one row, hero 2 : small 1 : wide 1.4.
  landscape,

  /// N = 1: hero, two equal smalls, one strip.
  phoneNarrow,

  /// N = 2: hero, small | wide 1:2, one strip.
  phone,

  /// N ≥ 3: every card on N true unit columns, rows from the staggered palette.
  units,
}

/// One resolved bento. [row1], [row2] and [strips] describe [BentoRecipe.units] only.
final class ActivityBentoSpec {
  const ActivityBentoSpec({
    required this.recipe,
    required this.units,
    this.row1 = const [],
    this.row2 = const [],
    this.strips = 0,
    this.row1Height = 0,
  });

  final BentoRecipe recipe;

  /// N, clamped to 1..8 (0 for the landscape row, which is the same at any N).
  final int units;

  /// Spans in units; `row1[0]` is the hero slot (or the first wide when there is no hero).
  final List<int> row1;

  /// Spans in units: 1 small, 2 wide.
  final List<int> row2;

  /// Equal-width strips closing the bento.
  final int strips;

  /// Row 1's height before font scale: 124 for N ≤ 4, 128 from N = 5.
  final double row1Height;

  /// Every card the bento shows, the hero slot included.
  int get totalSlots => switch (recipe) {
        BentoRecipe.units => row1.length + row2.length + strips,
        BentoRecipe.phone || BentoRecipe.phoneNarrow => 4,
        BentoRecipe.landscape => 3,
      };

  int get supportingSlots => totalSlots - 1;

  @override
  bool operator ==(Object other) =>
      other is ActivityBentoSpec &&
      other.recipe == recipe &&
      other.units == units &&
      _listEq(other.row1, row1) &&
      _listEq(other.row2, row2) &&
      other.strips == strips &&
      other.row1Height == row1Height;

  @override
  int get hashCode => Object.hash(recipe, units, Object.hashAll(row1), Object.hashAll(row2), strips, row1Height);

  @override
  String toString() => 'ActivityBentoSpec($recipe, N=$units, $row1 / $row2, strips $strips)';
}

bool _listEq(List<int> a, List<int> b) {
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i] != b[i]) return false;
  }
  return true;
}

typedef _Rows = (List<int>, List<int>);

/// The Units palette (owner 2026-10-03: keep the stagger and some randomness).
/// Item counts 5 / 7 / 8 / 10 / 12 / 13 for N = 3..8; row 1 tapers after the
/// hero, row 2's seams avoid row 1's. Option order is the contract:
/// `options[seed mod size]`.
const Map<int, (int, List<_Rows>)> activityUnitPalette = {
  3: (1, [([2, 1], [1, 2])]),
  4: (2, [([2, 2], [1, 2, 1])]),
  5: (2, [([2, 2, 1], [1, 2, 2]), ([2, 2, 1], [2, 1, 2])]),
  6: (2, [([2, 2, 1, 1], [1, 2, 2, 1]), ([2, 2, 2], [1, 1, 1, 2, 1]), ([2, 2, 2], [1, 2, 1, 1, 1])]),
  7: (3, [
    ([2, 2, 2, 1], [1, 2, 1, 1, 2]),
    ([2, 2, 2, 1], [1, 2, 2, 1, 1]),
    ([3, 2, 1, 1], [1, 1, 2, 1, 2]),
    ([3, 2, 1, 1], [1, 1, 2, 2, 1]),
  ]),
  8: (3, [
    ([2, 2, 2, 1, 1], [1, 2, 2, 1, 2]),
    ([2, 2, 2, 2], [1, 2, 1, 1, 2, 1]),
    ([3, 2, 2, 1], [1, 1, 2, 1, 1, 2]),
    ([3, 2, 2, 1], [1, 1, 2, 2, 1, 1]),
  ]),
};

/// activityBentoSpec: compact height → landscape (any N); N ≤ 1 → phoneNarrow;
/// N = 2 → phone; N ≥ 3 → units with `options[seed mod size]`. Without a hero
/// only options whose lead is 2 units are eligible (a 3-unit wide reads hollow).
ActivityBentoSpec activityBentoSpec({required int feedUnits, required bool compactHeight, required int seed, bool hasHero = true}) {
  final units = feedUnits.clamp(1, HomeFeedMetrics.maxUnits);
  if (compactHeight) return const ActivityBentoSpec(recipe: BentoRecipe.landscape, units: 0, row1Height: HomeBentoMetrics.heroHeight);
  if (units <= 1) return ActivityBentoSpec(recipe: BentoRecipe.phoneNarrow, units: units);
  if (units == 2) return const ActivityBentoSpec(recipe: BentoRecipe.phone, units: 2);
  final (strips, options) = activityUnitPalette[units]!;
  final eligible = hasHero ? options : options.where((o) => o.$1.first == 2).toList();
  final rows = eligible[seed % eligible.length]; // Dart's % is non-negative for a positive divisor.
  return ActivityBentoSpec(
    recipe: BentoRecipe.units,
    units: units,
    row1: rows.$1,
    row2: rows.$2,
    strips: strips,
    row1Height: units <= HomeFeedMetrics.cappedMaxUnits ? HomeBentoMetrics.heroHeight : HomeBentoMetrics.unitsRow1HeightWide,
  );
}

/// What a unit slot renders.
enum SlotKind { hero, wide, small, strip }

/// One card of the units grid. Rows 0/1: [startUnit] is the first unit column
/// and [span] the unit columns covered. Strip row ([row] 2): [startUnit] is
/// the strip's index and [span] its equal shares. [entryIndex] −1 = the hero,
/// else an index into the SUPPORTING list.
final class ActivitySlot {
  const ActivitySlot(this.row, this.startUnit, this.span, this.kind, this.entryIndex);
  final int row;
  final int startUnit;
  final int span;
  final SlotKind kind;
  final int entryIndex;

  ActivitySlot copyWith({int? span, SlotKind? kind}) => ActivitySlot(row, startUnit, span ?? this.span, kind ?? this.kind, entryIndex);

  @override
  bool operator ==(Object other) =>
      other is ActivitySlot &&
      other.row == row &&
      other.startUnit == startUnit &&
      other.span == span &&
      other.kind == kind &&
      other.entryIndex == entryIndex;

  @override
  int get hashCode => Object.hash(row, startUnit, span, kind, entryIndex);

  @override
  String toString() => 'ActivitySlot(r$row u$startUnit ×$span $kind #$entryIndex)';
}

/// Supporting entries a composition seats: every slot but the hero's, or every slot without a hero.
int seatedSupportingCount(ActivityBentoSpec spec, {required bool hasHero}) => hasHero ? spec.supportingSlots : spec.totalSlots;

/// activityUnitSlots: walks row 1, row 2, then the strips, handing out
/// supporting entries in order. `row1[0]` is the hero when [hasHero], else a
/// wide taking `supporting[0]`. When [supportingCount] runs out the bento
/// degrades from the bottom (owner W8): strips first, then row 2's tail; the
/// row the supply ran out in ends on its last card, stretched to the row's end
/// (a small that reaches 2+ units reads as a wide). Non-units recipes → [].
List<ActivitySlot> activityUnitSlots(ActivityBentoSpec spec, {required bool hasHero, required int supportingCount}) {
  if (spec.recipe != BentoRecipe.units) return const [];
  final unitRows = [spec.row1, spec.row2];
  final slots = <ActivitySlot>[];
  var next = 0;
  for (var rowIndex = 0; rowIndex < unitRows.length; rowIndex++) {
    var start = 0;
    final spans = unitRows[rowIndex];
    for (var i = 0; i < spans.length; i++) {
      final span = spans[i];
      if (rowIndex == 0 && i == 0 && hasHero) {
        slots.add(ActivitySlot(0, 0, span, SlotKind.hero, -1));
      } else {
        if (next >= supportingCount) return _withLastRowFilled(slots, spec.units, spec.strips);
        slots.add(ActivitySlot(rowIndex, start, span, span >= 2 ? SlotKind.wide : SlotKind.small, next++));
      }
      start += span;
    }
  }
  for (var strip = 0; strip < spec.strips; strip++) {
    if (next >= supportingCount) return _withLastRowFilled(slots, spec.units, spec.strips);
    slots.add(ActivitySlot(unitRows.length, strip, 1, SlotKind.strip, next++));
  }
  return slots;
}

List<ActivitySlot> _withLastRowFilled(List<ActivitySlot> slots, int units, int strips) {
  if (slots.isEmpty) return slots;
  final last = slots.last;
  final rowEnd = last.kind == SlotKind.strip ? strips : units;
  final span = rowEnd - last.startUnit;
  if (span <= last.span) return slots;
  final kind = switch (last.kind) {
    SlotKind.hero || SlotKind.strip => last.kind,
    _ => span >= 2 ? SlotKind.wide : SlotKind.small,
  };
  return [...slots.take(slots.length - 1), last.copyWith(span: span, kind: kind)];
}

/// Java's `String.hashCode` (s[0]·31^(n−1) + … over UTF-16 units, 32-bit wrap):
/// fixed by the Java spec, so the seed — and the layout — matches Android's.
int javaStringHash(String s) {
  var h = 0;
  for (final unit in s.codeUnits) {
    h = (31 * h + unit).toSigned(32);
  }
  return h;
}

/// activityLayoutSeed: the hash of a stable data key (the hero's `entityType:id`), masked non-negative; null → 0.
int activityLayoutSeed(String? key) => key == null ? 0 : javaStringHash(key) & 0x7fffffff;

// ---------------------------------------------------------------------------
// Jump Back In.

/// Rows of a K-lane template: 3 lanes stack four, 4–5 three, wider two.
int jbiTemplateRows(int columns) => columns <= HomeJbiMetrics.fourRowMaxColumns
    ? 4
    : columns <= HomeJbiMetrics.threeRowMaxColumns
        ? 3
        : 2;

/// The templated cover side: the phone's 0.84 rhythm in 100–128 dp, never wider than the column.
double jbiTemplateCoverSide(double columnWidth) => math.min(
      columnWidth,
      (columnWidth * HomeJbiMetrics.wideCoverFraction).clamp(HomeTokens.jbiCover, HomeJbiMetrics.wideCoverMax),
    );

// ---------------------------------------------------------------------------
// Recently Added.

/// Track rows: the phone (N ≤ 2) and the landscape handset 2, wider feeds 4.
int recentlyAddedTrackRows(int feedUnits, {required bool compactHeight}) =>
    compactHeight || feedUnits <= 2 ? HomeShelfMetrics.phoneTrackRows : HomeShelfMetrics.tallTrackRows;

/// Album cards per shelf column: one beside two rows, two beside four.
int recentlyAddedAlbumStack(int trackRows) => math.max(1, trackRows ~/ HomeShelfMetrics.phoneTrackRows);

/// Track column ceiling by the RESTING cover columns: phone / landscape 2;
/// ≤ 4 → 2; 5–7 → 3 (800 dp portrait tablet); ≥ 8 → 4 (1280 dp landscape tablet).
int recentlyAddedTrackColumns(int feedUnits, int coverColumns, {required bool compactHeight}) {
  if (compactHeight || feedUnits <= 2) return HomeShelfMetrics.phoneTrackColumns;
  if (coverColumns <= HomeShelfMetrics.twoColumnsMaxCoverColumns) return HomeShelfMetrics.phoneTrackColumns;
  if (coverColumns <= HomeShelfMetrics.threeColumnsMaxCoverColumns) return 3;
  return HomeShelfMetrics.maxTrackColumns;
}

/// Columns [tracks] tracks actually use under a [maxColumns] ceiling: just
/// enough for [rows] rows, never fewer than 2 (4 tracks stay a 2 × 2).
int recentlyAddedGridColumns(int tracks, {int rows = HomeShelfMetrics.phoneTrackRows, int maxColumns = HomeShelfMetrics.phoneTrackColumns}) =>
    ((tracks + rows - 1) ~/ rows).clamp(HomeShelfMetrics.phoneTrackColumns, math.max(HomeShelfMetrics.phoneTrackColumns, maxColumns));

/// Albums the shelf shows under a [columns]-column ceiling: 12, 16 at 3, 20 at 4.
int recentlyAddedAlbumLimit(int columns) => columns <= HomeShelfMetrics.phoneTrackColumns
    ? HomeShelfMetrics.phoneAlbums
    : columns == 3
        ? HomeShelfMetrics.threeColumnAlbums
        : HomeShelfMetrics.fourColumnAlbums;

/// The track grid's width for content width [contentWidth]: landscape
/// min((C − 14) × 0.45, 340); 2 columns clamp((C − 14) × 2.6/3.6, 226.78, 340);
/// 3+ columns whole 166 dp cells and their 8 dp gaps (514 / 688).
double recentlyAddedGridWidth(double contentWidth, {required bool compactHeight, int columns = HomeShelfMetrics.phoneTrackColumns}) {
  const gap = HomeShelfMetrics.itemGap;
  if (compactHeight) return math.min((contentWidth - gap) * HomeShelfMetrics.trackGridLandscapeShare, HomeShelfMetrics.trackGridMax);
  if (columns > HomeShelfMetrics.phoneTrackColumns) {
    return HomeShelfMetrics.trackTileWidth * columns + HomeTokens.recentlyAddedTileGap * (columns - 1);
  }
  return ((contentWidth - gap) * HomeShelfMetrics.trackGridShare).clamp(HomeShelfMetrics.trackGridMin, HomeShelfMetrics.trackGridMax);
}
