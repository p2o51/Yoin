import 'package:flutter/painting.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/library/library_cells.dart';

/// [librarySparseColumns] (Android LibrarySparseGridTest): a short Library on a
/// wide window grows its cells; [libraryViewGridColumns] decides where it may.
void main() {
  // Android's numbers: a 44dp cell extra, 16 / 12 gaps.
  int sparse(int itemCount, int base, double width, double height) =>
      librarySparseColumns(itemCount: itemCount, baseColumns: base, width: width, height: height, cellExtra: 44);

  group('librarySparseColumns', () {
    test('should_growCells_when_fewItemsOnALandscapeTablet', () {
      // 1280dp landscape tablet: 11 columns of ~100dp; 8 albums sat in one row.
      expect(sparse(8, 11, 1248, 640), 6);
    });

    test('should_keepUsualCells_when_theShelfFillsThePage', () {
      expect(sparse(200, 11, 1248, 640), 11);
    });

    test('should_takeFewestColumnsThatStillFit_when_inBetween', () {
      // 40 items: half the columns would scroll, so the count rises until the rows fit.
      final columns = sparse(40, 11, 1248, 1000);
      expect(columns, inInclusiveRange(7, 11));
      final cell = (1248 - 12 * (columns - 1)) / columns;
      final rows = (40 + columns - 1) ~/ columns;
      expect((cell + 44) * rows + 16 * (rows - 1), lessThanOrEqualTo(1000));
      // One column fewer would not fit.
      final fewer = columns - 1;
      final fewerCell = (1248 - 12 * (fewer - 1)) / fewer;
      final fewerRows = (40 + fewer - 1) ~/ fewer;
      expect((fewerCell + 44) * fewerRows + 16 * (fewerRows - 1), greaterThan(1000));
    });

    test('should_neverGoBelowThreeOrHalf_when_veryFewItems', () {
      expect(sparse(1, 5, 568, 900), 3);
      expect(sparse(1, 11, 1248, 640), 6);
      // Phone-sized grids are left alone.
      expect(sparse(1, 3, 360, 640), 3);
    });

    test('should_countTheSortRow_when_itLeads', () {
      // 12 albums on a 1248 × 640 page: three rows of six fit without the sort row, not with it.
      final cell6 = (1248 - 12 * 5) / 6;
      final height = (cell6 + 44) * 2 + 16 + 20; // two rows and a little
      expect(librarySparseColumns(itemCount: 12, baseColumns: 11, width: 1248, height: height, cellExtra: 44), 6);
      expect(
        librarySparseColumns(itemCount: 12, baseColumns: 11, width: 1248, height: height, cellExtra: 44, leading: 48),
        greaterThan(6),
      );
    });

    test('should_keepBase_when_noItems', () {
      expect(sparse(0, 11, 1248, 640), 11);
    });
  });

  group('libraryViewGridColumns', () {
    int columns(double width, double height, int items, {bool compactHeight = false}) => libraryViewGridColumns(
      width: width,
      height: height,
      itemCount: items,
      bottom: 108,
      sortRow: true,
      compactHeight: compactHeight,
    );

    test('should_keepThreeColumns_when_compactWidth', () {
      expect(columns(360, 700, 2), 3);
      expect(columns(599, 900, 2), 3);
    });

    test('should_growCells_when_shortShelfAtSeveralWidths', () {
      // Usual counts: 600 → 5, 800 → 6, 1280 → 11, 1600 → 14.
      expect(libraryGridColumns(600), 5);
      expect(libraryGridColumns(800), 6);
      expect(libraryGridColumns(1280), 11);
      expect(libraryGridColumns(1600), 14);
      expect(columns(600, 1000, 4), 3);
      expect(columns(800, 1200, 4), 3);
      expect(columns(1280, 760, 8), 6);
      expect(columns(1600, 1000, 8), 7);
    });

    test('should_keepUsualCells_when_shelfFillsThePage', () {
      expect(columns(800, 1200, 300), 6);
      expect(columns(1280, 760, 300), 11);
    });

    test('should_keepUsualCells_when_landscapeHandset', () {
      expect(columns(900, 400, 4, compactHeight: true), libraryGridColumns(900));
    });

    test('should_growLess_when_textIsLarger', () {
      // Larger titles make every row taller: the shelf fills the page sooner.
      final plain = columns(1280, 900, 14);
      final large = libraryViewGridColumns(
        width: 1280,
        height: 900,
        itemCount: 14,
        bottom: 108,
        sortRow: true,
        compactHeight: false,
        scaler: const TextScaler.linear(2),
      );
      expect(large, greaterThanOrEqualTo(plain));
    });
  });
}
