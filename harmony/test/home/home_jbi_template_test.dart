import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_feed_density.dart';
import 'package:yoin/home/home_jbi_template.dart';
import 'package:yoin/home/home_row_presets.dart';
import 'package:yoin/home/home_selection.dart';
import 'package:yoin/home/home_ui_state.dart';

/// Port of Android's HomeJbiTemplateTest for the covers-only template; the
/// known answers come from Kotlin (kotlin.random.Random, the same template code).
void main() {
  List<HomeWidgetCard> shelf(int covers) => [
        for (var i = 1; i <= covers; i++) albumCard(Album(id: MediaId(MediaId.providerSubsonic, 'c$i'), name: 'Cover $i', artist: 'Artist')),
      ];

  void expectTiles(JbiTemplate t) {
    final seen = List.generate(t.rows, (_) => List.filled(t.columns, 0));
    for (final p in t.pieces) {
      seen[p.row][p.column]++;
    }
    for (var r = 0; r < t.rows; r++) {
      for (var c = 0; c < t.columns; c++) {
        expect(seen[r][c], 1, reason: 'cell $c,$r');
      }
    }
    final order = [for (final p in t.pieces) (t.topOf(p), p.column)];
    final sorted = [...order]..sort((a, b) => a.$1 != b.$1 ? a.$1.compareTo(b.$1) : a.$2.compareTo(b.$2));
    expect(order, sorted);
  }

  group('kotlin random', () {
    test('should_matchKotlinSequence_when_seeded', () {
      final r = KotlinRandom(42);
      expect([for (var i = 0; i < 6; i++) r.nextInt()], [972016666, 1740578880, -408207414, -112774692, 1162768683, 649964064]);
      final m = KotlinRandom(-7);
      expect([for (var i = 0; i < 4; i++) m.nextInt()], [493578350, -2123338769, -1218139981, -1791905413]);
      expect([for (var i = 0; i < 4; i++) m.nextIntBelow(3)], [2, 1, 0, 0]);
      expect([for (var i = 0; i < 4; i++) m.nextIntBelow(2)], [1, 1, 0, 0]);
      expect([for (var i = 0; i < 3; i++) m.nextIntBelow(1)], [0, 0, 0]);
      expect(m.nextIntBelow(1000000007), 434114640);
    });
  });

  group('template', () {
    test('should_matchAndroidOffsets_when_sameSeed', () {
      expect(jbiTemplate(5, 3, 0).offsets, [0, 0.5, 0.25, 0.5, 0]);
      expect(jbiTemplate(9, 2, 42).offsets, [0, 0.5, 0, 0.25, 0, 0.25, 0, 0.25, 0]);
      expect(jbiTemplate(4, 3, 123456789).offsets, [0, 0.5, 0.25, 0]);
      expect(jbiTemplate(10, 2, 2147483647).offsets, [0, 0.25, 0, 0.5, 0.25, 0, 0.25, 0.5, 0, 0.5]);
      expect(jbiTemplate(3, 4, 7).offsets, [0, 0.5, 0]);
    });

    test('should_tileAndSeatEveryCardOnce_when_anyWidthAndSeed', () {
      final cards = shelf(28);
      for (var columns = 3; columns <= 10; columns++) {
        final rows = jbiTemplateRows(columns);
        for (var seed = 0; seed < 120; seed++) {
          final layout = jbiLayout(cards, columns: columns, rows: rows, seed: seed)!;
          expect(layout.template.rows, rows);
          expectTiles(layout.template);
          expect(layout.cells.map((c) => c.piece), layout.template.pieces);
          final ids = layout.cells.map((c) => c.card.stableId).toList();
          expect(ids.toSet(), hasLength(ids.length));
        }
      }
    });

    test('should_driftEveryLaneFromItsNeighbour_when_staggered', () {
      for (var columns = 3; columns <= 10; columns++) {
        for (var seed = 0; seed < 150; seed++) {
          final offsets = jbiTemplate(columns, jbiTemplateRows(columns), seed).offsets;
          expect(offsets.first, 0);
          for (var c = 0; c < columns - 1; c++) {
            expect(offsets[c], isNot(offsets[c + 1]), reason: 'K=$columns seed=$seed');
          }
          expect(offsets.every((o) => o == 0 || o == 0.25 || o == 0.5), isTrue);
        }
      }
    });

    test('should_returnSameLayout_when_sameInputs', () {
      for (var columns = 3; columns <= 10; columns++) {
        final a = jbiLayout(shelf(28), columns: columns, rows: jbiTemplateRows(columns), seed: 42)!;
        final b = jbiLayout(shelf(28), columns: columns, rows: jbiTemplateRows(columns), seed: 42)!;
        expect(a.template.offsets, b.template.offsets);
        expect(a.cells.map((c) => (c.card.stableId, c.piece)), b.cells.map((c) => (c.card.stableId, c.piece)));
      }
    });

    test('should_varyTemplates_when_seedsDiffer', () {
      for (var columns = 4; columns <= 10; columns++) {
        final distinct = {for (var seed = 0; seed < 40; seed++) jbiTemplate(columns, jbiTemplateRows(columns), seed).offsets.join(',')};
        expect(distinct.length, greaterThanOrEqualTo(8), reason: 'K=$columns');
      }
    });

    test('should_seatCoversInRankingOrder_when_readingOrder', () {
      final cards = shelf(28);
      final layout = jbiLayout(cards, columns: 9, rows: 2, seed: 3)!;
      expect(layout.cells.map((c) => c.card), cards.take(18));
    });

    test('should_dropARow_when_shelfTooShort', () {
      final layout = jbiLayout(shelf(11), columns: 5, rows: 3, seed: 1)!;
      expect(layout.template.rows, 2);
      expectTiles(layout.template);
    });

    test('should_returnNull_when_notEvenOneRowFits', () {
      expect(jbiLayout(shelf(3), columns: 9, rows: 2, seed: 1), isNull);
    });

    test('should_seedFromLeadingCover_when_shelfGiven', () {
      final cards = shelf(4);
      expect(jbiLayoutSeed(cards), activityLayoutSeed(cards.first.stableId));
      expect(jbiLayoutSeed(const []), 0);
    });
  });

  group('signal cards', () {
    HomeWidgetCard signal(int i) => HomeWidgetCard(
          stableId: 's$i',
          kind: HomeCardKind.album,
          title: 'Signal $i',
          subtitle: 'Artist',
          target: HomeOpenMemory(i),
          signal: JbiSignal(scoreText: '8.0', comment: 'Note $i'),
        );
    List<HomeWidgetCard> rich(int signals, {int covers = 24}) => [for (var i = 1; i <= signals; i++) signal(i), ...shelf(covers)];

    void expectSpanTiles(JbiTemplate t) {
      final seen = List.generate(t.rows, (_) => List.filled(t.columns, 0));
      for (final p in t.pieces) {
        for (var r = p.row; r < p.endRow; r++) {
          for (var c = p.column; c < p.endColumn; c++) {
            seen[r][c]++;
          }
        }
      }
      for (var r = 0; r < t.rows; r++) {
        for (var c = 0; c < t.columns; c++) {
          expect(seen[r][c], 1, reason: 'cell $c,$r');
        }
      }
      for (final p in t.pieces.where((p) => p.kind == JbiPieceKind.wideSignal)) {
        expect(t.offsets[p.column], t.offsets[p.column + 1]);
      }
      final order = [for (final p in t.pieces) (t.topOf(p), p.column)];
      final sorted = [...order]..sort((a, b) => a.$1 != b.$1 ? a.$1.compareTo(b.$1) : a.$2.compareTo(b.$2));
      expect(order, sorted);
    }

    test('should_tileAndSeatEveryCardOnce_when_anyWidthSignalsAndSeed', () {
      for (var columns = 3; columns <= 10; columns++) {
        final rows = jbiTemplateRows(columns);
        for (var signals = 0; signals <= 2; signals++) {
          for (var seed = 0; seed < 120; seed++) {
            final layout = jbiLayout(rich(signals), columns: columns, rows: rows, seed: seed)!;
            expect(layout.template.rows, rows);
            expectSpanTiles(layout.template);
            expect(layout.cells.map((c) => c.piece), layout.template.pieces);
            final ids = layout.cells.map((c) => c.card.stableId).toList();
            expect(ids.toSet(), hasLength(ids.length));
            expect(layout.cells.where((c) => c.piece.kind.isSignal), hasLength(signals));
            expect(layout.cells.every((c) => c.piece.kind.isSignal == c.card.expanded), isTrue);
          }
        }
      }
    });

    test('should_matchAndroidCardTemplates_when_sameSeed', () {
      // Known answers from origin/main's HomeJbiTemplate.kt (kotlinc, same seeds).
      void expectTemplate(int columns, int seed, List<double> offsets, List<(int, int)> cards) {
        final t = jbiTemplate(columns, jbiTemplateRows(columns), seed, signals: 2);
        expect(t.offsets, offsets, reason: 'K=$columns seed=$seed');
        expect(
          [for (final p in t.pieces.where((p) => p.kind.isSignal)) (p.column, p.row)],
          cards,
          reason: 'K=$columns seed=$seed',
        );
      }

      expectTemplate(3, 7, [0, 0.5, 0.5], [(1, 1), (1, 3)]);
      expectTemplate(3, 42, [0, 0.25, 0.25], [(1, 0), (1, 3)]);
      expectTemplate(4, 7, [0, 0.5, 0.25, 0.25], [(2, 0), (2, 2)]);
      expectTemplate(5, 7, [0, 0, 0.5, 0, 0], [(0, 0), (3, 1)]);
      expectTemplate(5, 42, [0, 0, 0.5, 0, 0], [(0, 0), (3, 0)]);
      expectTemplate(6, 7, [0, 0, 0.5, 0, 0.5, 0.5], [(0, 0), (4, 0)]);
      expectTemplate(9, 42, [0, 0.25, 0.5, 0, 0, 0.5, 0, 0.25, 0.25], [(3, 0), (7, 0)]);
    });

    test('should_dropBesideLyingCopyAndDriftElsewhere_when_drifting', () {
      for (var columns = 3; columns <= 10; columns++) {
        for (var signals = 0; signals <= 2; signals++) {
          for (var seed = 0; seed < 150; seed++) {
            final t = jbiTemplate(columns, jbiTemplateRows(columns), seed, signals: signals);
            final at = 'K=$columns s=$signals seed=$seed';
            final o = t.offsets;
            expect(o.first, 0, reason: at);
            // Boundaries inside a lying card's lane stay level.
            final level = <int>{};
            // Boundaries that may stay level: left of a lying card (its cover side).
            final mayLevel = <int>{};
            for (final p in t.pieces.where((p) => p.kind == JbiPieceKind.wideSignal)) {
              level.add(p.column);
              if (p.column > 0) mayLevel.add(p.column - 1);
            }
            // A lying card's right neighbour: half a row lower, and the card on the top row.
            for (final p in t.pieces.where((p) => p.kind == JbiPieceKind.wideSignal && p.endColumn < columns)) {
              expect(o[p.endColumn], o[p.column] + 0.5, reason: at);
              expect(p.row, 0, reason: at);
            }
            for (final c in level) {
              expect(o[c], o[c + 1], reason: at);
            }
            // Drift: neighbouring lanes never start level.
            for (var c = 0; c < columns - 1; c++) {
              if (level.contains(c) || mayLevel.contains(c)) continue;
              expect(o[c], isNot(o[c + 1]), reason: '$at | $c');
            }
            expect(o.every((x) => const [0.0, 0.25, 0.5, 0.75, 1.0].contains(x)), isTrue, reason: at);
          }
        }
      }
    });

    test('should_keepTheTwoCardsApart_when_bothPresent', () {
      for (var columns = 3; columns <= 10; columns++) {
        for (var seed = 0; seed < 150; seed++) {
          final t = jbiTemplate(columns, jbiTemplateRows(columns), seed, signals: 2);
          final s = t.pieces.where((p) => p.kind.isSignal).toList();
          expect(s, hasLength(2));
          final (a, b) = (s[0], s[1]);
          final at = 'K=$columns seed=$seed';
          if (a.column == b.column) {
            // Too narrow for two a lane apart (3 and 4 lanes): stacked in the
            // right-hand lane with a row of covers between them.
            expect(columns, lessThan(5), reason: at);
            expect(a.column, columns - 2, reason: at);
            expect((a.row - b.row).abs(), greaterThanOrEqualTo(2), reason: at);
          } else {
            // Side by side only with a lane of covers between them.
            expect(a.endColumn < b.column || b.endColumn < a.column, isTrue, reason: at);
          }
        }
      }
    });

    test('should_keepARightHandCardOffTheBottomRow_when_moreRowsThanOne', () {
      for (var columns = 3; columns <= 10; columns++) {
        final rows = jbiTemplateRows(columns);
        for (var seed = 0; seed < 150; seed++) {
          final t = jbiTemplate(columns, rows, seed, signals: 1);
          final card = t.pieces.singleWhere((p) => p.kind.isSignal);
          if (card.endColumn == columns && rows > 1) {
            expect(card.row, lessThan(rows - 1), reason: 'K=$columns seed=$seed');
          }
        }
      }
    });

    test('should_neverStandACard_when_anyWidthPresetOrSeed', () {
      // Owner 2026-11-14: no memory under its album's title — every card lies, cover beside copy.
      expect(JbiPieceKind.values, [JbiPieceKind.cover, JbiPieceKind.wideSignal]);
      for (var columns = 3; columns <= 10; columns++) {
        final geometry = JbiGeometry(
          columns: columns,
          rows: jbiTemplateRows(columns),
          columnWidth: 120,
          coverSide: 100,
          templated: true,
        );
        for (var seed = 0; seed < 40; seed++) {
          final cards = rich(6, covers: 40);
          final base = jbiLayout(cards, columns: columns, rows: geometry.rows, seed: seed);
          if (base == null) continue;
          for (final stop in jbiTemplateLadder(geometry, base, cards)) {
            final layout = stop.payload;
            expectSpanTiles(layout.template);
            for (final cell in layout.cells.where((c) => c.piece.kind.isSignal)) {
              expect(cell.piece.kind, JbiPieceKind.wideSignal);
              // Its right neighbour (if any) still sits half a row lower, the card on top.
              if (cell.piece.endColumn < columns) {
                expect(cell.piece.row, 0);
                expect(layout.template.offsets[cell.piece.endColumn], layout.template.offsets[cell.piece.column] + 0.5);
              }
            }
          }
        }
      }
    });

    test('should_varyCardTemplates_when_seedsDiffer', () {
      for (var columns = 3; columns <= 10; columns++) {
        final distinct = {
          for (var seed = 0; seed < 40; seed++) jbiTemplate(columns, jbiTemplateRows(columns), seed, signals: 2),
        };
        // Every card lies, one in the right-hand lane above its bottom row:
        // four and five lanes leave a single placement, so only that card's
        // row and the drift vary there.
        final floor = switch (columns) {
          4 => 2,
          5 => 4,
          _ => 6,
        };
        expect(distinct.length, greaterThanOrEqualTo(floor), reason: 'K=$columns');
      }
    });

    test('should_layCardsDown_when_oneRow', () {
      final t = jbiTemplate(9, 1, 4, signals: 2);
      expect(t.pieces.where((p) => p.kind.isSignal).every((p) => p.kind == JbiPieceKind.wideSignal), isTrue);
    });

    test('should_keepSeed_when_signalCardArrives', () {
      final covers = shelf(24);
      expect(jbiLayoutSeed([signal(1), signal(2), ...covers]), jbiLayoutSeed(covers));
    });

    test('should_seatOneSignal_when_coversCannotFillARow', () {
      final layout = jbiLayout(rich(2, covers: 3), columns: 4, rows: 2, seed: 5)!;
      expect(layout.cells.where((c) => c.piece.kind.isSignal), hasLength(1));
      expect(layout.template.covers, greaterThanOrEqualTo(1));
      expectSpanTiles(layout.template);
      expect(jbiSparseSignalLimit(5, 6), 1);
      expect(jbiSparseSignalLimit(6, 6), 2);
    });

    test('should_dropARow_when_richShelfTooShort', () {
      final layout = jbiLayout(rich(2, covers: 8), columns: 5, rows: 3, seed: 1)!;
      expect(layout.template.rows, 2);
      expectSpanTiles(layout.template);
    });

    test('should_keepPhoneShelfAtTwelveCells_when_trimming', () {
      int units(List<HomeWidgetCard> l) => l.fold(0, (n, c) => n + (c.expanded ? 2 : 1));
      expect(units(trimToPhoneShelf(rich(2))), 12);
      expect(units(trimToPhoneShelf(rich(1))), 12);
      expect(trimToPhoneShelf(rich(0)), hasLength(12));
      expect(trimToPhoneShelf(rich(0, covers: 5)), hasLength(5));
      expect(trimToPhoneShelf(rich(2)).take(3).map((c) => c.stableId), ['s1', 's2', 'grid-album:c1']);
    });

    test('should_packAsAndroidPhone_when_threeColumns', () {
      final cards = rich(2, covers: 8);
      final rows = packWidgetRows(trimToPhoneShelf(cards), 3);
      // The second wide row alternates to the right and spreads down (owner 2026-11-14).
      expect(
        [for (final r in rows) r.map((c) => c.stableId).toList()],
        [
          ['s1', 'grid-album:c1'],
          ['grid-album:c2', 'grid-album:c3', 'grid-album:c4'],
          ['grid-album:c5', 's2'],
          ['grid-album:c6', 'grid-album:c7', 'grid-album:c8'],
        ],
      );
      // No signals: plain rows of three, as before.
      expect(packWidgetRows(shelf(7), 3).map((r) => r.length), [3, 3, 1]);
    });

    test('should_spreadWideRowsEvenly_when_phoneShelfHasMoreRows', () {
      int units(List<HomeWidgetCard> row) => row.fold(0, (n, c) => n + (c.expanded ? 2 : 1));
      // The phone presets (2 / 3 / 4 / 6 rows) and a few longer shelves.
      for (var rowCount = 2; rowCount <= 10; rowCount++) {
        for (var k = 1; k <= (rowCount < 4 ? rowCount : 4); k++) {
          final wides = [for (var i = 1; i <= k; i++) signal(i)];
          final covers = shelf(rowCount * 3 - 2 * k);
          final rows = packWidgetRows([...wides, ...covers], 3);
          final at = 'R=$rowCount k=$k';
          expect(rows, hasLength(rowCount), reason: at);
          final wideRows = [
            for (var r = 0; r < rows.length; r++)
              if (rows[r].any((c) => c.expanded)) r,
          ];
          expect(wideRows, jbiSpreadRows(k, rowCount), reason: at);
          expect(wideRows.first, 0, reason: at);
          // Gaps differ by at most one row: even, not front-loaded.
          final marks = [...wideRows, rowCount];
          final gaps = [for (var i = 0; i + 1 < marks.length; i++) marks[i + 1] - marks[i]];
          expect(
            gaps.reduce((a, b) => a > b ? a : b) - gaps.reduce((a, b) => a < b ? a : b),
            lessThanOrEqualTo(1),
            reason: '$at $gaps',
          );
          // Never two wide rows touching while the shelf has room to part them.
          if (2 * k <= rowCount) {
            expect(gaps.take(gaps.length - 1).every((g) => g >= 2), isTrue, reason: '$at $wideRows');
          }
          // Every card once, covers and wides each in their ranking order.
          final flat = rows.expand((r) => r).toList();
          expect(flat.where((c) => !c.expanded).map((c) => c.stableId), covers.map((c) => c.stableId), reason: at);
          expect(flat.where((c) => c.expanded).map((c) => c.stableId), wides.map((c) => c.stableId), reason: at);
          for (final row in rows) {
            expect(units(row), 3, reason: at);
          }
        }
      }
    });

    test('should_spreadByCeiling_when_pickingRows', () {
      expect(jbiSpreadRows(1, 2), [0]);
      expect(jbiSpreadRows(2, 3), [0, 2]);
      expect(jbiSpreadRows(2, 4), [0, 2]);
      expect(jbiSpreadRows(3, 6), [0, 2, 4]);
      expect(jbiSpreadRows(3, 7), [0, 3, 5]);
      expect(jbiSpreadRows(2, 2), [0, 1]);
      expect(jbiSpreadRows(3, 2), [0, 1, 2]);
    });

    test('should_leaveOnlyTheLastRowShort_when_tooFewCovers', () {
      int units(List<HomeWidgetCard> row) => row.fold(0, (n, c) => n + (c.expanded ? 2 : 1));
      // Two memories, three covers, three lanes: no partial row mid-shelf, no wide card alone at the bottom.
      final rows = packWidgetRows([signal(1), signal(2), ...shelf(3)], 3);
      expect(
        [for (final r in rows) r.map((c) => c.stableId).toList()],
        [
          ['s1', 'grid-album:c1'],
          ['grid-album:c2', 's2'],
          ['grid-album:c3'],
        ],
      );
      // Any short supply: a row is short only once the covers ran out — every
      // row after it is a lone wide card or the last row.
      for (var k = 1; k <= 4; k++) {
        for (var n = 0; n <= 12; n++) {
          final packed = packWidgetRows([for (var i = 1; i <= k; i++) signal(i), ...shelf(n)], 3);
          final firstShort = packed.indexWhere((r) => units(r) < 3);
          if (firstShort < 0) continue;
          for (final row in packed.skip(firstShort + 1)) {
            expect(row.every((c) => c.expanded), isTrue, reason: 'k=$k n=$n');
          }
        }
      }
    });

    test('should_layNewSignalsInTheGrownRows_when_aTemplateHasSpareSignals', () {
      final grewWithSignals = List.filled(11, 0);
      for (var columns = 3; columns <= 10; columns++) {
        final geometry = JbiGeometry(
          columns: columns,
          rows: jbiTemplateRows(columns),
          columnWidth: 120,
          coverSide: 100,
          templated: true,
        );
        for (var seed = 0; seed < 30; seed++) {
          final cards = rich(6, covers: 40);
          final base = jbiLayout(cards, columns: columns, rows: geometry.rows, seed: seed)!;
          final xl = jbiTemplateLadder(geometry, base, cards).last.payload;
          final at = 'K=$columns seed=$seed';
          if (identical(xl, base)) continue;
          final baseIds = {for (final c in base.cells) c.card.stableId};
          final xlIds = [for (final c in xl.cells) c.card.stableId];
          // Every card of L is still seated, once; the grid tiles once.
          expect(xlIds.toSet().containsAll(baseIds), isTrue, reason: at);
          expect(xlIds.toSet(), hasLength(xlIds.length), reason: at);
          expectSpanTiles(xl.template);
          expect(xl.cells.map((c) => c.piece), xl.template.pieces, reason: at);
          // The grown rows brought signal cards of their own, lying in the
          // right-hand lane under a lying card already there, a row apart.
          final added = [
            for (final c in xl.cells)
              if (c.piece.kind.isSignal && !baseIds.contains(c.card.stableId)) c,
          ];
          if (added.isNotEmpty) grewWithSignals[columns]++;
          for (final cell in added) {
            expect(cell.piece.kind, JbiPieceKind.wideSignal, reason: at);
            expect(cell.piece.column, columns - 2, reason: at);
            expect(cell.piece.row, greaterThanOrEqualTo(base.template.rows), reason: at);
            expect(cell.card.expanded, isTrue, reason: at);
          }
          final laneRows = [
            for (final c in xl.cells)
              if (c.piece.kind.isSignal && c.piece.column == columns - 2) c.piece.row,
          ]..sort();
          for (var i = 0; i + 1 < laneRows.length; i++) {
            expect(laneRows[i + 1] - laneRows[i], greaterThanOrEqualTo(2), reason: '$at $laneRows');
          }
        }
      }
      // The right-hand lane holds a lying card on most shelves, so a longer
      // shelf keeps its memories coming at every width. Four lanes are the
      // exception: its L template already stacks both cards in the right-hand
      // lane, the second on its bottom row, so XL's one row can't seat a third.
      for (var columns = 3; columns <= 10; columns++) {
        if (columns == 4) continue;
        expect(grewWithSignals[columns], greaterThan(0), reason: 'K=$columns');
      }
    });

    test('should_limitSignalsByCells_when_rowsAndCellsGiven', () {
      expect(jbiSignalLimit(1, 6), 1);
      expect(jbiSignalLimit(2, 8), 1);
      expect(jbiSignalLimit(4, 12), 2);
      expect(jbiSignalLimit(5, 18), 3);
    });
  });
}
