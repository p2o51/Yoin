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
}
