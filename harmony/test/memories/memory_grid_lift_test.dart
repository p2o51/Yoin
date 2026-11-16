import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_grid.dart';
import 'package:yoin/data/memory/memory_ledger.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/memories/ledger/memory_grid_lift.dart';
import 'package:yoin/memories/ledger/memory_grid_view.dart';

MemoryLedgerAlbum _album(String id) => MemoryLedgerAlbum(albumId: MediaId.subsonic(id), name: id, artist: 'A');

MemoryGridCell _cell(int marks, {bool future = false}) => MemoryGridCell(
  start: DateTime.utc(2026),
  end: DateTime.utc(2026),
  marks: [for (var i = 0; i < marks; i++) MemoryGridMark(album: _album('a$i'), wrote: i.isEven, count: 1)],
  writings: marks,
  entries: marks,
  future: future,
);

void main() {
  group('barMosaic', () {
    test('should_keepTwoRows_when_fewSquares', () {
      final m = barMosaic(5, 400, 80);
      expect(m.rows, 2);
      expect(m.side, 40);
      expect(m.places, 20); // the bar is filled whatever the count
    });

    test('should_addRows_when_squaresOutnumberPlaces', () {
      final m = barMosaic(100, 400, 80);
      expect(m.rows * (400 / m.side).ceil(), greaterThanOrEqualTo(100));
      expect(m.rows, greaterThan(2));
    });

    test('should_capRows_when_tooManySquares', () {
      expect(barMosaic(100000, 400, 80).rows, 8);
    });
  });

  group('liftPixels', () {
    test('should_takeEachAlbumOfAQuad_when_months', () {
      final grid = MemoryGrid(MemoryGridScale.month, [_cell(3), _cell(0), _cell(4)]);
      final pixels = liftPixels(grid);
      expect(pixels, hasLength(7));
      expect(pixels.first.mark, 0);
      expect(pixels.last.index, 2);
    });

    test('should_skipEmptyAndFutureCells_when_weeks', () {
      final grid = MemoryGrid(MemoryGridScale.week, [_cell(1), _cell(0), _cell(2), _cell(1, future: true)]);
      final pixels = liftPixels(grid);
      expect(pixels.map((p) => p.index), [0, 2]);
      expect(pixels.every((p) => p.mark == -1), isTrue);
    });
  });
}
