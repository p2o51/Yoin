import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/library/library_grid_anchor.dart';

/// The Library grids' column-change anchor (Android LibraryGridAnchor.kt).
void main() {
  group('libraryGridPlaceAt', () {
    test('should_placeInTheHead_when_aboveTheFirstRow', () {
      final place = libraryGridPlaceAt(pixels: 30, leading: 56, stride: 150, columns: 6, itemCount: 40);
      expect(place, (index: -1, offset: 30));
    });

    test('should_returnTheRowsFirstItem_when_scrolledIntoARow', () {
      final place = libraryGridPlaceAt(pixels: 56 + 150 * 3 + 40, leading: 56, stride: 150, columns: 6, itemCount: 40);
      expect(place.index, 18);
      expect(place.offset, closeTo(40, 1e-9));
    });

    test('should_stopAtTheLastRow_when_pastTheEnd', () {
      final place = libraryGridPlaceAt(pixels: 56 + 150 * 20, leading: 56, stride: 150, columns: 6, itemCount: 40);
      expect(place.index, 36);
    });
  });

  group('libraryJumpAnchor', () {
    test('should_takeTheSectionStartingOnTheRow_when_oneDoes', () {
      // Row 12..17; sections at 0, 14, 16: the last one on the row names it.
      expect(libraryJumpAnchor(rowStart: 12, columns: 6, sectionStarts: [0, 14, 16, 30]), 16);
    });

    test('should_keepTheRow_when_itsSectionStartedAbove', () {
      expect(libraryJumpAnchor(rowStart: 12, columns: 6, sectionStarts: [0, 10, 30]), 12);
    });

    test('should_keepTheHead_when_jumpedToTheTop', () {
      expect(libraryJumpAnchor(rowStart: -1, columns: 6, sectionStarts: [0, 10]), -1);
    });

    test('should_keepTheRow_when_noSections', () {
      expect(libraryJumpAnchor(rowStart: 24, columns: 6, sectionStarts: const []), 24);
    });
  });

  group('LibraryGridAnchor', () {
    const wide = LibraryGridLayout(width: 1280, columns: 11, stride: 150, top: 8);
    const narrow = LibraryGridLayout(width: 800, columns: 7, stride: 160, top: 8);

    LibraryGridAnchor laidOut(LibraryGridAnchor anchor, LibraryGridLayout layout, {int items = 300}) {
      anchor.layOut(layout, items);
      anchor.measureHead(48);
      anchor.restoreTarget();
      return anchor;
    }

    test('should_stay_when_theLayoutIsUnchanged', () {
      final anchor = laidOut(LibraryGridAnchor(), wide);
      anchor.record(48 + 150 * 4 + 30);
      expect(anchor.restoreTarget(), isNull);
    });

    test('should_bringTheRowFlushToTheTop_when_columnsChange', () {
      final anchor = laidOut(LibraryGridAnchor(), wide);
      anchor.record(48 + 150 * 4 + 30); // row 4 → item 44
      expect(anchor.index, 44);
      anchor.layOut(narrow, 300);
      // Item 44 sits on row 6 of seven columns.
      expect(anchor.restoreTarget(), 48 + 160 * 6);
      expect(anchor.restoreTarget(), isNull, reason: 'settled');
    });

    test('should_returnToTheTop_when_theHeadWasShowing', () {
      final anchor = laidOut(LibraryGridAnchor(), wide);
      anchor.record(20);
      anchor.layOut(narrow, 300);
      expect(anchor.restoreTarget(), 0);
    });

    test('should_ignoreScrolls_when_theLayoutIsNotYetPlaced', () {
      final anchor = laidOut(LibraryGridAnchor(), wide);
      anchor.record(48 + 150 * 2);
      anchor.layOut(narrow, 300);
      anchor.record(48 + 160 * 9); // the old pixels under the new layout: not the user's place
      expect(anchor.index, 22);
    });

    test('should_moveToTheSection_when_fastScrollerJumped', () {
      final anchor = laidOut(LibraryGridAnchor(), wide);
      anchor.jumped(48 + 150 * 2 + 70, [0, 25, 40]); // row 2 = items 22..32; section at 25
      expect(anchor.index, 25);
      anchor.layOut(narrow, 300);
      expect(anchor.restoreTarget(), 48 + 160 * 3, reason: 'item 25 on row 3 of seven');
    });

    test('should_landExactly_when_backAtTheWidthItWasLeftAt', () {
      final anchor = laidOut(LibraryGridAnchor(exact: true), wide);
      anchor.record(48 + 150 * 4 + 30);
      anchor.layOut(narrow, 300);
      // Another width: the same share of the row, 30 / 150 of 160.
      expect(anchor.restoreTarget(), closeTo(48 + 160 * 6 + 32, 1e-9));
      anchor.layOut(wide, 300);
      expect(anchor.restoreTarget(), 48 + 150 * 4 + 30);
    });

    test('should_restoreExactly_when_builtAgain', () {
      final anchor = laidOut(LibraryGridAnchor(exact: true), wide);
      anchor.record(48 + 150 * 4 + 30);
      // A type switched away from and back: a new position over the same layout.
      anchor.layOut(wide, 300);
      expect(anchor.restoreTarget(), isNull, reason: 'the same position: nothing changed');
      expect(anchor.restoreTarget(fresh: true), 48 + 150 * 4 + 30, reason: 'a new position lands where it was left');
    });

    test('should_keepTheRestoredPixels_when_aPlainGridIsBuiltAgainAtTheSameLayout', () {
      final anchor = laidOut(LibraryGridAnchor(), wide);
      anchor.record(48 + 150 * 4 + 30);
      expect(anchor.restoreTarget(fresh: true), isNull);
    });

    test('should_startAtTheTop_when_builtAgainAfterAReset', () {
      final anchor = laidOut(LibraryGridAnchor(exact: true), wide);
      anchor.record(48 + 150 * 4 + 30);
      anchor.reset();
      expect(anchor.index, -1);
      expect(anchor.restoreTarget(fresh: true), isNull, reason: 'no place to go back to');
    });

    test('should_takeTheGivenAnchor_when_aControllerIsMadeForIt', () {
      final anchor = LibraryGridAnchor(exact: true);
      final controller = LibraryGridScrollController(exact: true, anchor: anchor);
      addTearDown(controller.dispose);
      expect(controller.anchor, same(anchor));
    });
  });
}
