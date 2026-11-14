import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/memory_grid.dart';
import 'package:yoin/data/memory/memory_ledger.dart';
import 'package:yoin/data/memory/memory_ledger_list.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/playback/local_ratings.dart';

int _at(int y, int m, int d) => DateTime.utc(y, m, d, 12).millisecondsSinceEpoch;

DateTime _dayOf(int ms) {
  final t = DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true);
  return DateTime.utc(t.year, t.month, t.day);
}

final _a = MemoryLedgerAlbum(albumId: MediaId.subsonic('a'), name: 'Glass Orchard', artist: 'The Meridians');
final _b = MemoryLedgerAlbum(albumId: MediaId.subsonic('b'), name: 'Night Bus', artist: 'Lumen');

MemoryLedgerEntry _entry(
  String id,
  MemoryLedgerKind kind,
  int at, {
  MemoryLedgerAlbum? album,
  double? score,
  String? text,
}) => MemoryLedgerEntry(id: id, kind: kind, at: at, album: album, score: score, text: text);

void main() {
  group('ledger', () {
    test('should_turnRowsIntoEntries_when_accountHasMemories', () {
      final rows = MemoryLedgerRows(
        albumRatings: {
          _a.albumId: AlbumRatingRow(
            albumId: _a.albumId,
            rating: 8.5,
            review: 'Rain again.',
            updatedAt: _at(2026, 11, 2),
            reviewUpdatedAt: _at(2026, 11, 1),
          ),
          // An album Yoin can't name is left out.
          MediaId.subsonic('x'): AlbumRatingRow(albumId: MediaId.subsonic('x'), rating: 6, updatedAt: _at(2026, 11, 3)),
        },
        songNotes: [
          SongNote(
            id: 'n1',
            trackId: MediaId.subsonic('a-t1'),
            albumId: _a.albumId,
            content: '雨声',
            createdAt: _at(2026, 11, 4),
            updatedAt: _at(2026, 11, 4),
            title: 'Satellites',
            positionMs: 90000,
          ),
        ],
        titles: {_a.albumId: MemoryTitleRow(albumId: _a.albumId, title: 'The rain year', updatedAt: _at(2026, 11, 5))},
        trackRatings: {
          MediaId.subsonic('b-t2'): LocalRating(
            profileId: 'p',
            songId: 'b-t2',
            provider: MediaId.providerSubsonic,
            rating: 9,
            serverRating: 5,
            updatedAt: _at(2026, 11, 6),
          ),
          MediaId.subsonic('z-t1'): LocalRating(
            profileId: 'p',
            songId: 'z-t1',
            provider: MediaId.providerSubsonic,
            rating: 7,
            serverRating: 4,
            updatedAt: _at(2026, 10, 6),
          ),
        },
      );
      final entries = buildMemoryLedger(
        rows,
        album: (id) => id == _a.albumId ? _a : (id == _b.albumId ? _b : null),
        track: (id) => id == MediaId.subsonic('b-t2') ? (album: _b, title: 'Lights') : null,
      );
      expect(entries.map((e) => e.kind), [
        MemoryLedgerKind.trackRating,
        MemoryLedgerKind.title,
        MemoryLedgerKind.songNote,
        MemoryLedgerKind.albumRating,
        MemoryLedgerKind.review,
        MemoryLedgerKind.trackRating,
      ]);
      expect(entries.first.trackTitle, 'Lights');
      expect(entries.first.album, _b);
      expect(entries.last.album, isNull);

      final stats = memoryLedgerStats(entries);
      expect(stats.albums, 2);
      expect(stats.writings, 2);
      expect(stats.characters, 'Rain again.'.length + 2);
      expect(stats.ratedTracks, 2);
      expect(stats.firstAt, _at(2026, 10, 6));
    });
  });

  group('grid', () {
    final today = DateTime.utc(2026, 11, 15);

    test('should_useMonths_when_fewDaysWritten', () {
      final grid = buildMemoryGrid(
        [_entry('r', MemoryLedgerKind.review, _at(2026, 11, 1), album: _a)],
        today: today,
        dayOf: _dayOf,
      );
      expect(grid.scale, MemoryGridScale.month);
      expect(grid.cells.length, MemoryGrid.minMonths);
      expect(grid.cells.last.start, DateTime.utc(2026, 11, 1));
      expect(grid.cells.last.end, DateTime.utc(2026, 11, 30));
      expect(grid.cells.last.marks.single.wrote, isTrue);
    });

    test('should_useDaysInWeekColumns_when_writingDaily', () {
      final entries = [
        for (var d = 0; d < 30; d++)
          _entry(
            's$d',
            MemoryLedgerKind.albumRating,
            DateTime.utc(2026, 11, 15 - d, 12).millisecondsSinceEpoch,
            album: _a,
          ),
      ];
      final grid = buildMemoryGrid(entries, today: today, dayOf: _dayOf);
      expect(grid.scale, MemoryGridScale.day);
      expect(grid.cells.length, MemoryGrid.dayWeeks * 7);
      // 2026-11-15 is a Sunday: a Monday week ends on it, nothing in the future.
      expect(grid.cells.first.start.weekday, DateTime.monday);
      expect(grid.cells.last.start, today);
      expect(grid.cells.where((c) => c.future), isEmpty);
    });

    test('should_rankWrittenAlbumFirst_when_cellShared', () {
      final marks = rankMarks([
        _entry('1', MemoryLedgerKind.albumRating, 3, album: _b),
        _entry('2', MemoryLedgerKind.trackRating, 2, album: _b),
        _entry('3', MemoryLedgerKind.songNote, 1, album: _a),
      ], 2);
      expect(marks.map((m) => m.album), [_a, _b]);
      expect(marks.last.count, 2);
    });
  });

  group('views', () {
    final entries = [
      _entry('title:a', MemoryLedgerKind.title, _at(2026, 11, 5), album: _a, text: 'The rain year'),
      _entry('rating:a', MemoryLedgerKind.albumRating, _at(2026, 11, 2), album: _a, score: 8.5),
      MemoryLedgerEntry(
        id: 'note:1',
        kind: MemoryLedgerKind.songNote,
        at: _at(2026, 11, 4),
        album: _b,
        text: 'the bridge',
        trackTitle: 'Lights',
        songId: MediaId.subsonic('b-t2'),
      ),
      MemoryLedgerEntry(
        id: 'tr:z',
        kind: MemoryLedgerKind.trackRating,
        at: _at(2026, 8, 3),
        score: 7,
        songId: MediaId.subsonic('z-t1'),
      ),
    ];

    test('should_foldQuietMonths_when_timelineBuilt', () {
      final rows = memoryTimelineRows(entries, today: DateTime.utc(2026, 11, 15), dayOf: _dayOf);
      expect(rows.map((r) => r.runtimeType), [
        TimelineMonth,
        TimelineAlbum,
        TimelineNote,
        TimelineQuiet,
        TimelineMonth,
        TimelineScores,
      ]);
      final album = rows[1] as TimelineAlbum;
      expect(album.item.title, 'The rain year');
      expect(album.item.score, 8.5);
      final quiet = rows[3] as TimelineQuiet;
      expect(quiet.from, DateTime.utc(2026, 10));
      expect(quiet.to, DateTime.utc(2026, 9));
    });

    test('should_keepOrphanScore_when_cardsBuilt', () {
      final items = memoryLedgerItems(entries);
      expect(items.map((i) => i.key), [
        'album:${_a.key}',
        'song:${MediaId.subsonic('b-t2')}',
        'song:${MediaId.subsonic('z-t1')}',
      ]);
      final byScore = sortLedgerItems(items, MemoryLedgerSort.score);
      expect(byScore.first.score, 8.5);
      expect(byScore.last.score, isNull);
      final byName = sortLedgerItems(items, MemoryLedgerSort.name);
      expect(byName.map((i) => i.name), ['Glass Orchard', 'Lights', '']);
    });
  });
}
