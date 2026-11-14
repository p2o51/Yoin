import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_feed_density.dart';
import 'package:yoin/home/home_jbi_template.dart';
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

    test('should_levelBesideStandingCopyAndDropBesideLyingCopy_when_drifting', () {
      for (var columns = 3; columns <= 10; columns++) {
        for (var signals = 0; signals <= 2; signals++) {
          for (var seed = 0; seed < 150; seed++) {
            final t = jbiTemplate(columns, jbiTemplateRows(columns), seed, signals: signals);
            final at = 'K=$columns s=$signals seed=$seed';
            final o = t.offsets;
            expect(o.first, 0, reason: at);
            final level = <int>{};
            final mayLevel = <int>{};
            final dropped = <int>{};
            for (final p in t.pieces) {
              switch (p.kind) {
                case JbiPieceKind.tallSignal:
                  if (p.column > 0) level.add(p.column - 1);
                  if (p.column < columns - 1) level.add(p.column);
                case JbiPieceKind.wideSignal:
                  level.add(p.column);
                  if (p.column > 0) mayLevel.add(p.column - 1);
                  if (p.endColumn < columns) dropped.add(p.endColumn - 1);
                case JbiPieceKind.cover:
                  break;
              }
            }
            for (final p in t.pieces.where((p) => p.kind == JbiPieceKind.wideSignal && p.endColumn < columns)) {
              final right = p.endColumn;
              if (dropped.contains(right - 1) && !level.contains(right - 1)) {
                expect(o[right], o[p.column] + 0.5, reason: at);
                expect(p.row, 0, reason: at);
              }
            }
            for (final c in level) {
              expect(o[c], o[c + 1], reason: at);
            }
            for (var c = 0; c < columns - 1; c++) {
              if (level.contains(c) || mayLevel.contains(c)) continue;
              expect(o[c], isNot(o[c + 1]), reason: '$at | $c');
            }
          }
        }
      }
    });

    test('should_keepTheTwoCardsInDifferentLanes_when_bothPresent', () {
      for (var columns = 3; columns <= 10; columns++) {
        for (var seed = 0; seed < 150; seed++) {
          final t = jbiTemplate(columns, jbiTemplateRows(columns), seed, signals: 2);
          final s = t.pieces.where((p) => p.kind.isSignal).toList();
          expect(s, hasLength(2));
          final (a, b) = (s[0], s[1]);
          expect(a.endColumn <= b.column || b.endColumn <= a.column, isTrue);
          if (columns >= 5) expect(a.endColumn < b.column || b.endColumn < a.column, isTrue, reason: 'K=$columns seed=$seed');
        }
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
      expect([for (final r in rows) r.map((c) => c.stableId).toList()], [
        ['s1', 'grid-album:c1'],
        ['grid-album:c2', 's2'],
        ['grid-album:c3', 'grid-album:c4', 'grid-album:c5'],
        ['grid-album:c6', 'grid-album:c7', 'grid-album:c8'],
      ]);
      // No signals: plain rows of three, as before.
      expect(packWidgetRows(shelf(7), 3).map((r) => r.length), [3, 3, 1]);
    });

    test('should_limitSignalsByCells_when_rowsAndCellsGiven', () {
      expect(jbiSignalLimit(1, 6), 1);
      expect(jbiSignalLimit(2, 8), 1);
      expect(jbiSignalLimit(4, 12), 2);
      expect(jbiSignalLimit(5, 18), 3);
    });
  });
}
