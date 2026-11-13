import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/library/library_sort.dart';

import '../support/library_fake_source.dart';

LibraryListInputs _inputs({
  List<Artist>? artists,
  List<Album>? albums,
  List<Playlist>? playlists,
  Map<LibraryTab, LibrarySort> sorts = const {},
  Map<String, int> lastSeen = const {},
  int? nowMs,
}) => LibraryListInputs(
  artists: artists,
  albums: albums,
  playlists: playlists,
  sorts: sorts,
  lastSeenMs: lastSeen,
  nowMs: nowMs ?? DateTime(2026, 11, 13, 12).millisecondsSinceEpoch,
);

List<String> _names(SortedView? v) => [for (final i in v!.items) i.name];

void main() {
  group('stripLeadingArticle', () {
    test('should_dropOneArticle_when_followedByWord', () {
      expect(stripLeadingArticle('The Beatles'), 'Beatles');
      expect(stripLeadingArticle('  the  Who '), 'Who');
      expect(stripLeadingArticle('Los Lobos'), 'Lobos');
    });

    test('should_keepName_when_articleIsWholeNameOrPrefix', () {
      expect(stripLeadingArticle('The'), 'The');
      expect(stripLeadingArticle('Theory'), 'Theory');
      expect(stripLeadingArticle('A'), 'A');
    });
  });

  group('parseLibraryDate', () {
    test('should_readZonedAndLocalForms_when_parsing', () {
      expect(parseLibraryDate('2024-03-01T10:00:00Z'), DateTime.utc(2024, 3, 1, 10).millisecondsSinceEpoch);
      expect(parseLibraryDate('2024-03-01T10:00:00+02:00'), DateTime.utc(2024, 3, 1, 8).millisecondsSinceEpoch);
      expect(parseLibraryDate('2024-03-01T10:00:00'), DateTime.utc(2024, 3, 1, 10).millisecondsSinceEpoch);
      expect(parseLibraryDate('2024-03-01'), DateTime.utc(2024, 3, 1).millisecondsSinceEpoch);
    });

    test('should_returnNull_when_blankOrGarbage', () {
      expect(parseLibraryDate(null), isNull);
      expect(parseLibraryDate('  '), isNull);
      expect(parseLibraryDate('yesterday'), isNull);
    });
  });

  group('collation', () {
    test('should_foldDiacritics_when_bucketing', () {
      expect(bucketLabelOf('Édith Piaf'), 'E');
      expect(bucketLabelOf('Ólafur Arnalds'), 'O');
      expect(bucketLabelOf('The Beatles'), 'B');
    });

    test('should_fileDigitsUnderHash_and_otherScriptsUnderEllipsis', () {
      expect(bucketLabelOf('2Pac'), '#');
      expect(bucketLabelOf('!!!'), '#');
      expect(bucketLabelOf('周杰伦'), '…');
    });
  });

  test('should_sortAlphabeticallyIgnoringArticles_when_alphabetical', () {
    final sorted = sortLibrary(
      _inputs(
        artists: [
          LibraryFixtures.artist('1', name: 'The Zombies'),
          LibraryFixtures.artist('2', name: 'ABBA'),
          LibraryFixtures.artist('3', name: '2Pac'),
          LibraryFixtures.artist('4', name: 'Beck'),
        ],
        sorts: {LibraryTab.artists: LibrarySort.alphabetical},
      ),
    );
    expect(_names(sorted.artists), ['ABBA', 'Beck', 'The Zombies', '2Pac']);
    expect([for (final s in sorted.artists!.sections) (s.label as LetterLabel).letter], ['A', 'B', 'Z', '#']);
    expect([for (final s in sorted.artists!.sections) s.startIndex], [0, 1, 2, 3]);
  });

  test('should_putNewestFirstThenUndated_when_recentlyAdded', () {
    final sorted = sortLibrary(
      _inputs(
        albums: [
          LibraryFixtures.album('a', name: 'Old', addedAt: '2020-01-01'),
          LibraryFixtures.album('b', name: 'Undated'),
          LibraryFixtures.album('c', name: 'New', addedAt: '2025-06-01'),
        ],
        sorts: {LibraryTab.albums: LibrarySort.recentlyAdded},
      ),
    );
    expect(_names(sorted.albums), ['New', 'Old', 'Undated']);
  });

  test('should_orderByLastSeenThenRecentlyAdded_when_recents', () {
    final sorted = sortLibrary(
      _inputs(
        albums: [
          LibraryFixtures.album('a', name: 'Seen long ago', addedAt: '2020-01-01'),
          LibraryFixtures.album('b', name: 'Never seen new', addedAt: '2025-01-01'),
          LibraryFixtures.album('c', name: 'Seen today', addedAt: '2019-01-01'),
        ],
        lastSeen: {
          'album:a': DateTime(2026, 1, 1).millisecondsSinceEpoch,
          'album:c': DateTime(2026, 11, 13, 9).millisecondsSinceEpoch,
        },
      ),
    );
    expect(_names(sorted.albums), ['Seen today', 'Seen long ago', 'Never seen new']);
    final labels = sorted.albums!.sections.map((s) => s.label).toList();
    expect(labels.first, isA<RecentsLabel>().having((l) => l.bucket, 'bucket', RecentsBucket.today));
    expect(labels.last, isA<RecentsLabel>().having((l) => l.bucket, 'bucket', RecentsBucket.notOpened));
  });

  test('should_mixArtistsAlbumsPlaylists_when_all', () {
    final sorted = sortLibrary(
      _inputs(
        artists: [LibraryFixtures.artist('1', name: 'Mid')],
        albums: [LibraryFixtures.album('2', name: 'Alpha')],
        playlists: [Playlist(id: MediaId.subsonic('3'), name: 'Zulu')],
        sorts: {LibraryTab.all: LibrarySort.alphabetical},
      ),
    );
    expect(_names(sorted.all), ['Alpha', 'Mid', 'Zulu']);
    expect(sorted.all!.items.map((i) => i.runtimeType), [LibraryAlbumItem, LibraryArtistItem, LibraryPlaylistItem]);
  });

  test('should_dropDuplicates_when_sameIdListedTwice', () {
    final a = LibraryFixtures.album('dup');
    final sorted = sortLibrary(_inputs(albums: [a, a]));
    expect(sorted.albums!.items, hasLength(1));
  });

  test('should_sortByCreatorNullsLast_when_creator', () {
    final sorted = sortLibrary(
      _inputs(
        albums: [
          LibraryFixtures.album('1', name: 'X', artist: 'Zed'),
          LibraryFixtures.album('2', name: 'Y'),
          LibraryFixtures.album('3', name: 'Z', artist: 'Abe'),
        ],
        sorts: {LibraryTab.albums: LibrarySort.creator},
      ),
    );
    expect(_names(sorted.albums), ['Z', 'X', 'Y']);
  });

  group('timelineSections', () {
    test('should_returnNone_when_tooManyUndated', () {
      final d = DateTime(2024).millisecondsSinceEpoch;
      expect(timelineSections([d, null, null, d]), isEmpty);
    });

    test('should_returnNone_when_oneSegmentDominates', () {
      final d = DateTime(2024, 3).millisecondsSinceEpoch;
      expect(timelineSections([d, d, d, DateTime(2020).millisecondsSinceEpoch]), isEmpty);
    });

    test('should_cutByMonth_when_spanUnderTwoYears', () {
      final sections = timelineSections([
        DateTime(2025, 6, 2).millisecondsSinceEpoch,
        DateTime(2025, 3, 1).millisecondsSinceEpoch,
        DateTime(2024, 12, 1).millisecondsSinceEpoch,
      ]);
      final labels = sections.map((s) => s.label as MonthLabel).toList();
      expect(labels.map((l) => (l.year, l.month)), [(2025, 6), (2025, 3), (2024, 12)]);
      // The year stands in for the month wherever a new year starts.
      expect(labels.map((l) => l.tickIsMonth), [false, true, false]);
    });
  });
}
