import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_feed_density.dart';
import 'package:yoin/home/home_jbi_template.dart';
import 'package:yoin/home/home_row_presets.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/home/home_selection.dart';
import 'package:yoin/home/home_ui_state.dart';
import 'package:yoin/theme/tokens.dart';

/// Port of Android's HomeRowPresetsTest: L is today's composition on every
/// screen, the ladder table matches d1-rows.md §2, and neighbouring presets
/// nest (the larger seats every card of the smaller).
void main() {
  HomeWidgetCard cover(int i) =>
      albumCard(Album(id: MediaId(MediaId.providerSubsonic, 'c$i'), name: 'Cover $i', artist: 'Artist'));
  HomeWidgetCard signal(int i) => HomeWidgetCard(
    stableId: 's$i',
    kind: HomeCardKind.album,
    title: 'Signal $i',
    subtitle: 'Artist',
    target: HomeOpenMemory(i),
    signal: JbiSignal(scoreText: '8.0', comment: 'Note $i'),
  );
  List<HomeWidgetCard> shelf({int signals = 2, int covers = 28}) => [
    for (var i = 1; i <= signals; i++) signal(i),
    for (var i = 1; i <= covers; i++) cover(i),
  ];
  const phoneJbi = JbiGeometry(columns: 3, rows: 4, columnWidth: 100, coverSide: 100);
  const landscapeJbi = JbiGeometry(columns: 6, rows: 2, columnWidth: 100, coverSide: 100, landscape: true);
  JbiGeometry templated(int columns) =>
      JbiGeometry(columns: columns, rows: jbiTemplateRows(columns), columnWidth: 120, coverSide: 100, templated: true);
  Set<String> ids(List<List<HomeWidgetCard>> rows) => {for (final r in rows) ...r.map((c) => c.stableId)};
  Set<String> layoutIds(JbiLayout layout) => {for (final c in layout.cells) c.card.stableId};

  group('ladder', () {
    test('should_storeTheKeptOrNearestPreset_when_landingOnAMergedStop', () {
      const merged = [HomeRowPreset.s, HomeRowPreset.m, HomeRowPreset.l];
      expect(homeRowPresetFrom(merged, HomeRowPreset.m), HomeRowPreset.m);
      expect(homeRowPresetFrom(merged, HomeRowPreset.xl), HomeRowPreset.l);
      expect(homeRowPresetFrom(const [HomeRowPreset.s, HomeRowPreset.m], HomeRowPreset.l), HomeRowPreset.m);
      expect(homeRowPresetFrom(const [HomeRowPreset.xl], HomeRowPreset.s), HomeRowPreset.xl);
    });

    test('should_mergeConsecutivePresets_when_compositionsAreEqual', () {
      final ladder = homeRowLadder((p) => (p.index < 2 ? 'small' : p.id, p.index));
      expect(ladder.map((s) => s.presets), [
        [HomeRowPreset.s, HomeRowPreset.m],
        [HomeRowPreset.l],
        [HomeRowPreset.xl],
      ]);
      expect(ladder.first.rowCount, 0);
      expect(homeRowStopIndexOf(ladder, HomeRowPreset.m), 0);
      expect(homeRowStopIndexOf(ladder, HomeRowPreset.xl), 2);
    });
  });

  group('activities', () {
    test('should_reproduceTodaysPhoneBento_when_presetIsDefault', () {
      for (final narrow in [false, true]) {
        final recipe = narrow ? BentoRecipe.phoneNarrow : BentoRecipe.phone;
        final rows = activityCodeRows(recipe, HomeRowPreset.l, hasHero: true, supportingCount: 5);
        expect(rows.map((r) => r.shape), [ActivityRowShape.hero, ActivityRowShape.cards, ActivityRowShape.strip]);
        final card = rows[1];
        expect(card.height, HomeTokens.activityRowHeight);
        expect(card.gap, HomeTokens.activityRowGap);
        expect(
          card.slots.map((s) => (s.kind, s.weight)),
          narrow ? [(SlotKind.small, 1.0), (SlotKind.small, 1.0)] : [(SlotKind.small, 1.0), (SlotKind.wide, 2.0)],
        );
        expect(card.slots.map((s) => s.entryIndex), [0, 1]);
        expect(rows[2].slots.single.entryIndex, 2);
      }
    });

    test('should_degradeFromTheBottom_when_phoneSupplyIsShort', () {
      final two = activityCodeRows(BentoRecipe.phone, HomeRowPreset.l, hasHero: true, supportingCount: 2);
      expect(two.map((r) => r.shape), [ActivityRowShape.hero, ActivityRowShape.cards]);
      final none = activityCodeRows(BentoRecipe.phone, HomeRowPreset.l, hasHero: true, supportingCount: 0);
      expect(none.map((r) => r.shape), [ActivityRowShape.hero]);
    });

    test('should_matchThePhoneTable_when_resolvingEachPreset', () {
      final counts = [
        for (final p in HomeRowPreset.values)
          activityCodeRows(
            BentoRecipe.phone,
            p,
            hasHero: true,
            supportingCount: 10,
          ).fold<int>(0, (n, r) => n + r.slots.length),
      ];
      expect(counts, [1, 3, 4, 6]);
      final xl = activityCodeRows(BentoRecipe.phone, HomeRowPreset.xl, hasHero: true, supportingCount: 10);
      expect(xl[2].slots.map((s) => s.kind), [SlotKind.wide, SlotKind.small]);
      // L's strip entry is XL's wide card: it grows into it.
      expect(xl[2].slots.first.entryIndex, 2);
      expect(xl[3].slots.single.entryIndex, 4);
    });

    test('should_keepOneRow_when_landscapeBelowXl', () {
      final l = activityCodeRows(BentoRecipe.landscape, HomeRowPreset.l, hasHero: true, supportingCount: 10);
      expect(l.single.slots.map((s) => (s.kind, s.weight)), [
        (SlotKind.hero, 2.0),
        (SlotKind.small, 1.0),
        (SlotKind.wide, 1.4),
      ]);
      for (final p in [HomeRowPreset.s, HomeRowPreset.m]) {
        expect(activityCodeRows(BentoRecipe.landscape, p, hasHero: true, supportingCount: 10), l);
      }
      final xl = activityCodeRows(BentoRecipe.landscape, HomeRowPreset.xl, hasHero: true, supportingCount: 10);
      expect(xl[1].slots.map((s) => (s.kind, s.weight)), [
        (SlotKind.wide, 1.4),
        (SlotKind.small, 1.0),
        (SlotKind.small, 1.0),
      ]);
      final spec = activityBentoSpec(feedUnits: 2, compactHeight: true, seed: 0);
      final ladder = activityRowLadder(spec, hasHero: true, supportingCount: 10);
      expect(ladder.map((s) => s.presets), [
        [HomeRowPreset.s, HomeRowPreset.m, HomeRowPreset.l],
        [HomeRowPreset.xl],
      ]);
      expect(ladder.map((s) => s.rowCount), [1, 2]);
    });

    test('should_reproduceTodaysUnitSlots_when_presetIsDefault', () {
      for (var units = 3; units <= 8; units++) {
        for (var seed = 0; seed <= 5; seed++) {
          for (final hasHero in [true, false]) {
            final spec = activityBentoSpec(feedUnits: units, compactHeight: false, seed: seed, hasHero: hasHero);
            for (final supply in [0, 3, 7, 12, 30]) {
              expect(
                activityUnitSlots(spec, hasHero: hasHero, supportingCount: supply, preset: HomeRowPreset.l),
                activityUnitSlots(spec, hasHero: hasHero, supportingCount: supply),
              );
            }
            expect(
              activityPresetSupportingCount(spec, hasHero: hasHero, preset: HomeRowPreset.l),
              seatedSupportingCount(spec, hasHero: hasHero),
            );
          }
        }
      }
    });

    test('should_addOneUnitRowBeforeTheStrips_when_unitsAtXl', () {
      final spec = activityBentoSpec(feedUnits: 4, compactHeight: false, seed: 0);
      final l = activityUnitSlots(spec, hasHero: true, supportingCount: 30);
      final xl = activityUnitSlots(spec, hasHero: true, supportingCount: 30, preset: HomeRowPreset.xl);
      expect(l, hasLength(7));
      expect(xl, hasLength(9));
      expect(xl.where((s) => s.row == 2).map((s) => s.span), [2, 2]);
      expect(xl.where((s) => s.row == 3).every((s) => s.kind == SlotKind.strip), isTrue);
      // L's strips are XL's third row: the same entries, a new kind.
      expect(
        xl.where((s) => s.row == 2).map((s) => s.entryIndex),
        l.where((s) => s.kind == SlotKind.strip).map((s) => s.entryIndex),
      );
      expect(
        activityUnitSlots(spec, hasHero: true, supportingCount: 30, preset: HomeRowPreset.s).map((s) => s.row).toSet(),
        {0},
      );
      expect(
        activityUnitSlots(spec, hasHero: true, supportingCount: 30, preset: HomeRowPreset.m).map((s) => s.row).toSet(),
        {0, 1},
      );
    });

    test('should_mirrorRowTwo_when_choosingTheExtraRow', () {
      expect(activityExtraRow(3, const [1, 2]), [2, 1]);
      expect(activityExtraRow(4, const [1, 2, 1]), [2, 2]);
      expect(activityExtraRow(5, const [1, 2, 2]), [2, 2, 1]);
      expect(activityExtraRow(5, const [2, 1, 2]), [2, 2, 1]);
      for (var units = 3; units <= 8; units++) {
        for (var seed = 0; seed <= 5; seed++) {
          final spec = activityBentoSpec(feedUnits: units, compactHeight: false, seed: seed);
          final extra = activityExtraRow(units, spec.row2);
          expect(extra.fold<int>(0, (a, b) => a + b), units);
          expect(extra.every((s) => s == 1 || s == 2), isTrue);
        }
      }
    });

    test('should_nestEveryPreset_when_climbingTheActivitiesLadder', () {
      final specs = [
        activityBentoSpec(feedUnits: 2, compactHeight: false, seed: 0),
        activityBentoSpec(feedUnits: 1, compactHeight: false, seed: 0),
        activityBentoSpec(feedUnits: 2, compactHeight: true, seed: 0),
        for (var n = 3; n <= 8; n++) activityBentoSpec(feedUnits: n, compactHeight: false, seed: n),
      ];
      for (final spec in specs) {
        for (final hasHero in [true, false]) {
          final ladder = activityRowLadder(spec, hasHero: hasHero, supportingCount: 30);
          for (var i = 0; i + 1 < ladder.length; i++) {
            expect(ladder[i].payload.seated, lessThanOrEqualTo(ladder[i + 1].payload.seated), reason: '$spec');
            expect(ladder[i].rowCount, lessThanOrEqualTo(ladder[i + 1].rowCount));
          }
          expect([for (final s in ladder) ...s.presets], HomeRowPreset.values);
        }
      }
    });

    test('should_mergeStops_when_historyIsShort', () {
      final spec = activityBentoSpec(feedUnits: 2, compactHeight: false, seed: 0);
      // A hero and two supporting entries: S (hero), M = L (+ small | wide), XL can seat no more.
      final ladder = activityRowLadder(spec, hasHero: true, supportingCount: 2);
      expect(ladder.map((s) => s.presets), [
        [HomeRowPreset.s],
        [HomeRowPreset.m, HomeRowPreset.l, HomeRowPreset.xl],
      ]);
      expect(activityRowLadder(spec, hasHero: true, supportingCount: 0), hasLength(1));
    });

    test('should_listPicturedEntries_when_stripsAreTextOnly', () {
      final spec = activityBentoSpec(feedUnits: 2, compactHeight: false, seed: 0);
      expect(activityPresetPicturedEntries(spec, hasHero: true, preset: HomeRowPreset.l), [0, 1]);
      expect(activityPresetPicturedEntries(spec, hasHero: true, preset: HomeRowPreset.xl), [0, 1, 2, 3]);
    });
  });

  group('jump back in', () {
    test('should_matchTheJbiRowTable_when_resolvingPresets', () {
      expect([for (final p in HomeRowPreset.values) jbiPresetRows(phoneJbi, p)], [2, 3, 4, 6]);
      expect([for (final p in HomeRowPreset.values) jbiPresetRows(landscapeJbi, p)], [1, 1, 2, 3]);
      for (var columns = 3; columns <= 10; columns++) {
        final rows = jbiTemplateRows(columns);
        expect(
          [for (final p in HomeRowPreset.values) jbiPresetRows(templated(columns), p)],
          [if (rows - 2 < 1) 1 else rows - 2, rows - 1, rows, rows + 1],
          reason: 'K=$columns',
        );
      }
    });

    test('should_reproduceTodaysPhoneShelf_when_presetIsDefault', () {
      for (final geometry in [phoneJbi, landscapeJbi]) {
        for (var signals = 0; signals <= 2; signals++) {
          final cards = shelf(signals: signals);
          final ladder = jbiPackedLadder(geometry, cards);
          final l = ladder[homeRowStopIndexOf(ladder, HomeRowPreset.l)].payload;
          final today = packWidgetRows(
            trimToPhoneShelf(cards, cells: geometry.cells, maxSignals: jbiSignalLimit(geometry.rows, geometry.cells)),
            geometry.columns,
          );
          expect(jbiPackedRowsEqual(l, today), isTrue, reason: '${geometry.columns} columns, $signals signals');
        }
      }
    });

    test('should_matchThePhoneShelfCounts_when_resolvingPresets', () {
      final ladder = jbiPackedLadder(phoneJbi, shelf());
      final counts = [
        for (final stop in ladder)
          (
            stop.payload.expand((r) => r).where((c) => c.expanded).length,
            stop.payload.expand((r) => r).where((c) => !c.expanded).length,
          ),
      ];
      expect(counts, [(1, 4), (2, 5), (2, 8), (2, 14)]);
      expect(ladder.map((s) => s.rowCount), [2, 3, 4, 6]);
      for (var i = 0; i + 1 < ladder.length; i++) {
        expect(ids(ladder[i + 1].payload).containsAll(ids(ladder[i].payload)), isTrue);
      }
    });

    test('should_mergeStops_when_landscapeShelfRowsMatch', () {
      final ladder = jbiPackedLadder(landscapeJbi, shelf());
      expect(ladder.map((s) => s.presets), [
        [HomeRowPreset.s, HomeRowPreset.m],
        [HomeRowPreset.l],
        [HomeRowPreset.xl],
      ]);
      final s = ladder.first.payload.expand((r) => r).toList();
      expect((s.where((c) => c.expanded).length, s.where((c) => !c.expanded).length), (1, 4));
    });

    test('should_keepTheLTemplate_when_presetIsDefault', () {
      for (var columns = 3; columns <= 10; columns++) {
        for (var seed = 0; seed < 20; seed++) {
          final geometry = templated(columns);
          final cards = shelf();
          final base = jbiLayout(cards, columns: geometry.columns, rows: geometry.rows, seed: seed)!;
          final ladder = jbiTemplateLadder(geometry, base, cards);
          expect(identical(ladder[homeRowStopIndexOf(ladder, HomeRowPreset.l)].payload, base), isTrue);
        }
      }
    });

    test('should_growFromTheBottomAndNest_when_templatePresetsChange', () {
      for (var columns = 3; columns <= 10; columns++) {
        for (var signals = 0; signals <= 2; signals++) {
          for (var seed = 0; seed < 40; seed++) {
            final geometry = templated(columns);
            final cards = shelf(signals: signals, covers: 40);
            final base = jbiLayout(cards, columns: geometry.columns, rows: geometry.rows, seed: seed)!;
            final ladder = jbiTemplateLadder(geometry, base, cards);
            final at = 'K=$columns s=$signals seed=$seed';
            for (final stop in ladder) {
              final layout = stop.payload;
              expect(layout.cells.map((c) => c.piece).toList(), layout.template.pieces, reason: at);
              expect(layoutIds(layout), hasLength(layout.cells.length), reason: at);
              final seen = List.generate(layout.template.rows, (_) => List.filled(columns, 0));
              for (final p in layout.template.pieces) {
                for (var r = p.row; r < p.endRow; r++) {
                  for (var c = p.column; c < p.endColumn; c++) {
                    seen[r][c]++;
                  }
                }
              }
              expect(seen.every((row) => row.every((n) => n <= 1)), isTrue, reason: at);
              expect(
                layout.cells.where((c) => c.piece.kind.isSignal).every((c) => c.card.expanded),
                isTrue,
                reason: at,
              );
            }
            for (var i = 0; i + 1 < ladder.length; i++) {
              expect(layoutIds(ladder[i + 1].payload).containsAll(layoutIds(ladder[i].payload)), isTrue, reason: at);
            }
            // XL: L's pieces where they were, then a row of covers.
            final xl = ladder.last.payload;
            if (!identical(xl, base)) {
              expect(JbiLayout(base.template, xl.cells.take(base.cells.length).toList()) == base, isTrue, reason: at);
              expect(xl.template.rows, base.template.rows + 1, reason: at);
              expect(
                xl.cells.skip(base.cells.length).every((c) => c.piece.row == base.template.rows),
                isTrue,
                reason: at,
              );
            }
          }
        }
      }
    });

    test('should_matchTheTabletTable_when_templatedPresetsResolve', () {
      // 800 dp portrait, 5 lanes: S 1 + 3 · M 2 + 6 · L 2 + 11 · XL 2 + 16 (Android's answer).
      final geometry = templated(5);
      final cards = shelf();
      final base = jbiLayout(cards, columns: geometry.columns, rows: geometry.rows, seed: 3)!;
      final counts = [
        for (final stop in jbiTemplateLadder(geometry, base, cards))
          (
            stop.payload.cells.where((c) => c.piece.kind.isSignal).length,
            stop.payload.cells.where((c) => !c.piece.kind.isSignal).length,
          ),
      ];
      expect(counts, [(1, 3), (2, 6), (2, 11), (2, 16)]);
    });

    test('should_stopGrowing_when_theShelfRunsOut', () {
      final geometry = templated(5);
      final cards = shelf(signals: 2, covers: 13);
      final base = jbiLayout(cards, columns: geometry.columns, rows: geometry.rows, seed: 1)!;
      final ladder = jbiTemplateLadder(geometry, base, cards);
      // 11 covers seat L; two spare can't fill a row of five, so XL is L: one stop.
      expect(ladder.last.presets, [HomeRowPreset.l, HomeRowPreset.xl]);
    });

    test('should_standNewSignalCards_when_growingWithSpareSignals', () {
      final geometry = templated(6);
      final cards = shelf(signals: 4, covers: 40);
      final base = jbiLayout(cards, columns: geometry.columns, rows: geometry.rows, seed: 5)!;
      final xl = jbiPresetLayout(base, cards, geometry.rows + 1);
      expect(xl.template.rows, base.template.rows + 1);
      final signals = xl.cells.where((c) => c.piece.kind.isSignal).toList();
      expect(signals.length, greaterThan(base.cells.where((c) => c.piece.kind.isSignal).length));
      // Every card L seated is still on the shelf.
      expect(layoutIds(xl).containsAll(layoutIds(base)), isTrue);
      expect(jbiGrowSignals(1, 6), 1);
      expect(jbiGrowSignals(2, 9), 3);
    });

    test('should_routeToTemplateOrPacked_when_buildingTheLadder', () {
      final cards = shelf();
      final geometry = templated(5);
      final base = jbiLayout(cards, columns: geometry.columns, rows: geometry.rows, seed: 0);
      expect(jbiRowLadder(geometry, cards, base).every((s) => s.payload is JbiTemplatePreset), isTrue);
      expect(jbiRowLadder(phoneJbi, cards, null).every((s) => s.payload is JbiPackedPreset), isTrue);
      // A templated pane's fallback keeps the phone's 12 cells at every preset: one stop.
      expect(jbiRowLadder(geometry, [cover(1), cover(2)], null), hasLength(1));
    });
  });
}
