/// Row presets (HomeRowPresets.kt; D1, owner 2026-10-05): Activities and Jump
/// Back In each come in four presets — S, M, L (the composition from before
/// presets existed) and XL — and every screen turns a preset into its own
/// composition here. Everything is pure and pinned by home_row_presets_test;
/// the L of every screen is today's composition. Two presets that resolve to
/// the same composition on a screen (the landscape handset's S / M bento, a
/// short shelf's S and M) are one stop of that screen's ladder: the handle has
/// one detent there and the stored preset is kept.
///
/// The presets of a section are nested: each one seats every card of the one
/// below it (a card may move or change kind — a strip growing into a wide card
/// — but never disappears on the way up). The edit-mode resize
/// (home_rows_resize.dart) interpolates between neighbouring stops on exactly
/// that property.
library;

import 'dart:math' as math;

import '../theme/tokens.dart';
import 'home_feed_density.dart';
import 'home_jbi_template.dart';
import 'home_metrics.dart';
import 'home_section.dart';
import 'home_selection.dart';
import 'home_ui_state.dart';

/// One detent of a section's ladder on this screen: the [presets] that
/// resolve to [payload] (smallest first), how many rows it shows ([rowCount],
/// for the reader's label) and the composition itself.
final class HomeRowStop<T> {
  const HomeRowStop(this.presets, this.rowCount, this.payload);

  final List<HomeRowPreset> presets;
  final int rowCount;
  final T payload;

  /// The preset to store for this stop, coming from [from] ([homeRowPresetFrom]).
  HomeRowPreset presetFrom(HomeRowPreset from) => homeRowPresetFrom(presets, from);

  @override
  String toString() => 'HomeRowStop(${presets.map((p) => p.id).join('/')}, $rowCount rows)';
}

/// The preset to store for a stop standing for [presets], coming from
/// [from]: [from] itself when it is one of them (the stored choice is kept),
/// else L, else the one nearest [from].
HomeRowPreset homeRowPresetFrom(List<HomeRowPreset> presets, HomeRowPreset from) {
  if (presets.contains(from)) return from;
  if (presets.contains(HomeRowPreset.defaultPreset)) return HomeRowPreset.defaultPreset;
  var best = presets.first;
  for (final p in presets) {
    if ((p.index - from.index).abs() < (best.index - from.index).abs()) best = p;
  }
  return best;
}

/// The ladder for one section on one screen: every preset resolved by
/// [resolve] (composition, row count), consecutive presets with an equal
/// composition ([equals], `==` by default) merged into one stop.
List<HomeRowStop<T>> homeRowLadder<T>(
  (T, int) Function(HomeRowPreset preset) resolve, {
  bool Function(T a, T b)? equals,
}) {
  final same = equals ?? (T a, T b) => a == b;
  final stops = <HomeRowStop<T>>[];
  for (final preset in HomeRowPreset.values) {
    final (payload, rows) = resolve(preset);
    final last = stops.isEmpty ? null : stops.last;
    if (last != null && same(last.payload, payload)) {
      stops[stops.length - 1] = HomeRowStop([...last.presets, preset], last.rowCount, last.payload);
    } else {
      stops.add(HomeRowStop([preset], rows, payload));
    }
  }
  return stops;
}

/// The stop of [ladder] that [preset] resolves to (the first, for a preset not on it).
int homeRowStopIndexOf<T>(List<HomeRowStop<T>> ladder, HomeRowPreset preset) =>
    math.max(0, ladder.indexWhere((stop) => stop.presets.contains(preset)));

bool _listEq<T>(List<T> a, List<T> b) {
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i] != b[i]) return false;
  }
  return true;
}

// ---------------------------------------------------------------------------
// Activities.

/// How a row of the code-built bento (phone, narrow phone, landscape) lays out.
enum ActivityRowShape {
  /// The hero alone, full width, at its own height.
  hero,

  /// Cards side by side by weight, at [ActivityCodeRow.height] × font scale.
  cards,

  /// One strip, full width, at its own height.
  strip,
}

/// One card of a code-built row: its [kind], its share of the row ([weight])
/// and its entry ([entryIndex] into the supporting list; −1 = the hero). An
/// entry past the supply is a spacer of the same weight.
final class ActivityCodeSlot {
  const ActivityCodeSlot(this.kind, this.weight, this.entryIndex);
  final SlotKind kind;
  final double weight;
  final int entryIndex;

  @override
  bool operator ==(Object other) =>
      other is ActivityCodeSlot && other.kind == kind && other.weight == weight && other.entryIndex == entryIndex;

  @override
  int get hashCode => Object.hash(kind, weight, entryIndex);

  @override
  String toString() => 'ActivityCodeSlot($kind ×$weight #$entryIndex)';
}

/// One row of the code-built bento. [height] and [gap] only mean something for [ActivityRowShape.cards].
final class ActivityCodeRow {
  const ActivityCodeRow(this.shape, this.slots, {this.height = 0, this.gap = 0});
  final ActivityRowShape shape;
  final List<ActivityCodeSlot> slots;
  final double height;
  final double gap;

  @override
  bool operator ==(Object other) =>
      other is ActivityCodeRow &&
      other.shape == shape &&
      other.height == height &&
      other.gap == gap &&
      _listEq(other.slots, slots);

  @override
  int get hashCode => Object.hash(shape, height, gap, Object.hashAll(slots));

  @override
  String toString() => 'ActivityCodeRow($shape, $slots)';
}

/// The phone / narrow phone / landscape bento at [preset], with
/// [supportingCount] supporting entries on offer. Phone: S = the hero,
/// M = + small | wide, L = + one strip (today), XL = + a mirrored wide | small
/// before the strip, so L's strip grows into XL's wide card. Narrow phone: the
/// same with two equal smalls per row. Landscape: S = M = L = today's one row
/// (hero 2 : small 1 : wide 1.4 — the first screen keeps Recently Added in
/// view), XL a second row (wide 1.4 : small 1 : small 1). Without a hero, S is
/// the first card row. Entries go out in reading order; a row whose first
/// entry is past the supply is dropped, a later one becomes a spacer.
List<ActivityCodeRow> activityCodeRows(
  BentoRecipe recipe,
  HomeRowPreset preset, {
  required bool hasHero,
  required int supportingCount,
}) {
  var next = 0;
  ActivityCodeSlot slot(SlotKind kind, double weight) => ActivityCodeSlot(kind, weight, next++);
  final rows = <ActivityCodeRow>[];
  switch (recipe) {
    case BentoRecipe.landscape:
      final first = [
        if (hasHero) const ActivityCodeSlot(SlotKind.hero, HomeBentoMetrics.landscapeHeroWeight, -1),
        slot(SlotKind.small, HomeBentoMetrics.landscapeSmallWeight),
        slot(SlotKind.wide, HomeBentoMetrics.landscapeWideWeight),
      ];
      rows.add(
        ActivityCodeRow(
          ActivityRowShape.cards,
          first,
          height: HomeBentoMetrics.landscapeRowHeight,
          gap: HomeBentoMetrics.landscapeGap,
        ),
      );
      if (preset == HomeRowPreset.xl) {
        final second = [
          slot(SlotKind.wide, HomeBentoMetrics.landscapeWideWeight),
          slot(SlotKind.small, HomeBentoMetrics.landscapeSmallWeight),
          slot(SlotKind.small, HomeBentoMetrics.landscapeSmallWeight),
        ];
        if (second.first.entryIndex < supportingCount) {
          rows.add(
            ActivityCodeRow(
              ActivityRowShape.cards,
              second,
              height: HomeBentoMetrics.landscapeRowHeight,
              gap: HomeBentoMetrics.landscapeGap,
            ),
          );
        }
      }
    case BentoRecipe.phone || BentoRecipe.phoneNarrow:
      final narrow = recipe == BentoRecipe.phoneNarrow;
      if (hasHero) rows.add(const ActivityCodeRow(ActivityRowShape.hero, [ActivityCodeSlot(SlotKind.hero, 1, -1)]));
      void cardRow(List<ActivityCodeSlot> slots) {
        if (slots.first.entryIndex < supportingCount) {
          rows.add(
            ActivityCodeRow(
              ActivityRowShape.cards,
              slots,
              height: HomeTokens.activityRowHeight,
              gap: HomeTokens.activityRowGap,
            ),
          );
        }
      }

      if (preset == HomeRowPreset.s && hasHero) return rows;
      cardRow(
        narrow ? [slot(SlotKind.small, 1), slot(SlotKind.small, 1)] : [slot(SlotKind.small, 1), slot(SlotKind.wide, 2)],
      );
      if (preset.index <= HomeRowPreset.m.index) return rows;
      if (preset == HomeRowPreset.xl) {
        cardRow(
          narrow
              ? [slot(SlotKind.small, 1), slot(SlotKind.small, 1)]
              : [slot(SlotKind.wide, 2), slot(SlotKind.small, 1)],
        );
      }
      final strip = slot(SlotKind.strip, 1);
      if (strip.entryIndex < supportingCount) rows.add(ActivityCodeRow(ActivityRowShape.strip, [strip]));
    case BentoRecipe.units:
      break;
  }
  return rows;
}

const int _unlimited = 1 << 30;

/// Supporting entries [preset] seats on [spec] (every slot but the hero's, or every slot without one).
int activityPresetSupportingCount(ActivityBentoSpec spec, {required bool hasHero, required HomeRowPreset preset}) {
  if (spec.recipe == BentoRecipe.units) {
    return activityUnitSlots(
      spec,
      hasHero: hasHero,
      supportingCount: _unlimited,
      preset: preset,
    ).where((s) => s.kind != SlotKind.hero).length;
  }
  return activityCodeRows(
    spec.recipe,
    preset,
    hasHero: hasHero,
    supportingCount: _unlimited,
  ).fold(0, (n, row) => n + row.slots.where((s) => s.entryIndex >= 0).length);
}

/// The supporting entries (indices) [preset] seats on [spec] in a card that
/// draws its cover — every slot but the hero's and the strips (text only), in
/// reading order.
List<int> activityPresetPicturedEntries(
  ActivityBentoSpec spec, {
  required bool hasHero,
  required HomeRowPreset preset,
}) {
  if (spec.recipe == BentoRecipe.units) {
    return [
      for (final s in activityUnitSlots(spec, hasHero: hasHero, supportingCount: _unlimited, preset: preset))
        if (s.kind != SlotKind.hero && s.kind != SlotKind.strip) s.entryIndex,
    ];
  }
  return [
    for (final row in activityCodeRows(spec.recipe, preset, hasHero: hasHero, supportingCount: _unlimited))
      for (final s in row.slots)
        if (s.entryIndex >= 0 && s.kind != SlotKind.strip) s.entryIndex,
  ];
}

/// One Activities composition: the code-built [rows] or the unit [slots] (the
/// other empty), and how many supporting entries it seats.
final class ActivityPresetLayout {
  const ActivityPresetLayout({this.rows = const [], this.slots = const [], required this.seated});
  final List<ActivityCodeRow> rows;
  final List<ActivitySlot> slots;
  final int seated;

  @override
  bool operator ==(Object other) =>
      other is ActivityPresetLayout &&
      other.seated == seated &&
      _listEq(other.rows, rows) &&
      _listEq(other.slots, slots);

  @override
  int get hashCode => Object.hash(seated, Object.hashAll(rows), Object.hashAll(slots));
}

/// The Activities ladder on one screen ([spec], from activityBentoSpec) with
/// [supportingCount] supporting entries on offer. Presets the supply can't
/// tell apart merge, so a short history gets fewer detents.
List<HomeRowStop<ActivityPresetLayout>> activityRowLadder(
  ActivityBentoSpec spec, {
  required bool hasHero,
  required int supportingCount,
}) => homeRowLadder((preset) {
  final seated = math.min(supportingCount, activityPresetSupportingCount(spec, hasHero: hasHero, preset: preset));
  if (spec.recipe == BentoRecipe.units) {
    final slots = activityUnitSlots(spec, hasHero: hasHero, supportingCount: seated, preset: preset);
    final rows = slots.isEmpty ? 0 : slots.map((s) => s.row).reduce(math.max) + 1;
    return (ActivityPresetLayout(slots: slots, seated: seated), rows);
  }
  final rows = activityCodeRows(spec.recipe, preset, hasHero: hasHero, supportingCount: seated);
  return (ActivityPresetLayout(rows: rows, seated: seated), rows.length);
});

// ---------------------------------------------------------------------------
// Jump Back In.

/// Rows of Jump Back In at [preset] on [geometry]. The phone's packed shelf
/// (3 columns): 2 / 3 / 4 (today) / 6. The landscape handset's: 1 / 1 / 2
/// (today) / 3. A templated pane of L = jbiTemplateRows rows: L − 2, L − 1,
/// L, L + 1, never under 1 (so a 2-row wide pane has S = M).
int jbiPresetRows(JbiGeometry geometry, HomeRowPreset preset) {
  if (geometry.templated) {
    return math.max(1, switch (preset) {
      HomeRowPreset.s => geometry.rows - 2,
      HomeRowPreset.m => geometry.rows - 1,
      HomeRowPreset.l => geometry.rows,
      HomeRowPreset.xl => geometry.rows + 1,
    });
  }
  if (geometry.landscape) {
    return switch (preset) {
      HomeRowPreset.s || HomeRowPreset.m => 1,
      HomeRowPreset.l => HomeJbiMetrics.landscapeRows,
      HomeRowPreset.xl => HomeJbiMetrics.landscapeRows + 1,
    };
  }
  return switch (preset) {
    HomeRowPreset.s => HomeJbiMetrics.phoneRowsS,
    HomeRowPreset.m => HomeJbiMetrics.phoneRowsM,
    HomeRowPreset.l => HomeJbiMetrics.phoneRows,
    HomeRowPreset.xl => HomeJbiMetrics.phoneRowsXl,
  };
}

/// The cells of the packed shelf at [preset]: rows × columns (12 on the phone
/// at L, as before). The column grid a templated pane falls back to (too few
/// cards for its template) keeps the phone's 12 at every preset.
int jbiPackedCells(JbiGeometry geometry, HomeRowPreset preset) =>
    geometry.templated ? jbiPhoneShelfCells : jbiPresetRows(geometry, preset) * geometry.columns;

/// One Jump Back In composition: the packed shelf's rows, or a seated template.
sealed class JbiPresetLayout {
  const JbiPresetLayout();
}

/// The phone / landscape shelf ([packWidgetRows]), or a pane's fallback column grid.
final class JbiPackedPreset extends JbiPresetLayout {
  const JbiPackedPreset(this.rows);
  final List<List<HomeWidgetCard>> rows;

  @override
  bool operator ==(Object other) => other is JbiPackedPreset && jbiPackedRowsEqual(other.rows, rows);

  @override
  int get hashCode => Object.hashAll(rows.map((r) => Object.hashAll(r.map((c) => c.stableId))));
}

/// A templated pane (home_jbi_template.dart).
final class JbiTemplatePreset extends JbiPresetLayout {
  const JbiTemplatePreset(this.layout);
  final JbiLayout layout;

  @override
  bool operator ==(Object other) => other is JbiTemplatePreset && other.layout == layout;

  @override
  int get hashCode => layout.hashCode;
}

/// Two packed shelves seat the same cards in the same rows (cards by stable id).
bool jbiPackedRowsEqual(List<List<HomeWidgetCard>> a, List<List<HomeWidgetCard>> b) {
  if (a.length != b.length) return false;
  for (var r = 0; r < a.length; r++) {
    if (a[r].length != b[r].length) return false;
    for (var i = 0; i < a[r].length; i++) {
      if (a[r][i].stableId != b[r][i].stableId) return false;
    }
  }
  return true;
}

/// Jump Back In's ladder on one screen: [base] (the L template) when the pane
/// is templated and the shelf filled it, else the packed shelves.
List<HomeRowStop<JbiPresetLayout>> jbiRowLadder(JbiGeometry geometry, List<HomeWidgetCard> cards, JbiLayout? base) {
  if (base != null) {
    return [
      for (final stop in jbiTemplateLadder(geometry, base, cards))
        HomeRowStop<JbiPresetLayout>(stop.presets, stop.rowCount, JbiTemplatePreset(stop.payload)),
    ];
  }
  return [
    for (final stop in jbiPackedLadder(geometry, cards))
      HomeRowStop<JbiPresetLayout>(stop.presets, stop.rowCount, JbiPackedPreset(stop.payload)),
  ];
}

/// The packed paths' ladder: the phone / landscape shelves at each preset, packed by [packWidgetRows].
List<HomeRowStop<List<List<HomeWidgetCard>>>> jbiPackedLadder(JbiGeometry geometry, List<HomeWidgetCard> cards) =>
    homeRowLadder((preset) {
      final cells = jbiPackedCells(geometry, preset);
      final rows = geometry.templated ? HomeJbiMetrics.phoneRows : jbiPresetRows(geometry, preset);
      // A pane too wide for its covers (the template's fallback) keeps one card, as the template does.
      final sparse = geometry.templated
          ? jbiSparseSignalLimit(cards.where((c) => !c.expanded).length, geometry.columns)
          : _unlimited;
      final shelf = trimToPhoneShelf(cards, cells: cells, maxSignals: math.min(jbiSignalLimit(rows, cells), sparse));
      final packed = packWidgetRows(shelf, geometry.columns);
      return (packed, packed.length);
    }, equals: jbiPackedRowsEqual);

/// The templated ladder: [base] (the L template, jbiLayout as before) grown or
/// shrunk to each preset's rows by [jbiPresetLayout] — L → XL appends a row
/// of covers, L → M → S each trim the bottom row of the one above, so every
/// stop seats the cards of the one below it.
List<HomeRowStop<JbiLayout>> jbiTemplateLadder(JbiGeometry geometry, JbiLayout base, List<HomeWidgetCard> cards) {
  final l = base;
  final m = jbiPresetLayout(l, cards, jbiPresetRows(geometry, HomeRowPreset.m));
  final s = jbiPresetLayout(m, cards, jbiPresetRows(geometry, HomeRowPreset.s));
  final xl = jbiPresetLayout(l, cards, jbiPresetRows(geometry, HomeRowPreset.xl));
  final byPreset = {HomeRowPreset.s: s, HomeRowPreset.m: m, HomeRowPreset.l: l, HomeRowPreset.xl: xl};
  return homeRowLadder((preset) {
    final layout = byPreset[preset]!;
    return (layout, layout.template.rows);
  });
}
