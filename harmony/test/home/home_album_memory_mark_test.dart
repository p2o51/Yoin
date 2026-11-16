import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/album_memory_candidate.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_album_memory_mark.dart';
import 'package:yoin/home/home_strings.dart';
import 'package:yoin/home/home_ui_state.dart';
import 'package:yoin/memories/memories_ui_state.dart';
import 'package:yoin/theme/theme.dart';

AlbumMemoryCandidate _candidate(
  String id, {
  double? albumRating,
  double? average,
  int rated = 0,
  int notes = 0,
  bool review = false,
  String provider = MediaId.providerSubsonic,
}) => AlbumMemoryCandidate(
  profileId: 'p',
  albumId: MediaId(provider, id),
  albumName: 'Album $id',
  totalTracks: 10,
  ratedTrackCount: rated,
  ratingCoverage: rated / 10,
  averageSongRating: average,
  albumRating: albumRating,
  hasAlbumReview: review,
  noteCount: notes,
  isMemoryEligible: false,
);

const _en = HomeStrings.forLanguage(chinese: false);

void main() {
  group('marks', () {
    test('should_preferAlbumRatingThenAverageThenNotes_when_marking', () {
      expect(
        albumMemoryMarkOf(_candidate('a', albumRating: 9, average: 6, rated: 3, notes: 2)),
        const HomeAlbumMemoryMark(kind: MemoryScoreKind.albumRating, scoreText: '9.0', noteCount: 2),
      );
      expect(
        albumMemoryMarkOf(_candidate('b', average: 8.44, rated: 2)),
        const HomeAlbumMemoryMark(kind: MemoryScoreKind.averageTrackRating, scoreText: '8.4'),
      );
      // A 0.0 average is a score.
      expect(albumMemoryMarkOf(_candidate('z', average: 0, rated: 1))!.kind, MemoryScoreKind.averageTrackRating);
      expect(
        albumMemoryMarkOf(_candidate('c', notes: 3)),
        const HomeAlbumMemoryMark(kind: MemoryScoreKind.none, noteCount: 3),
      );
      // Reviews and plays alone don't count; a cleared (0) album score is none.
      expect(albumMemoryMarkOf(_candidate('d', review: true)), isNull);
      expect(albumMemoryMarkOf(_candidate('e', albumRating: 0)), isNull);
    });

    test('should_mergeToTheStrongerKind_when_oneAlbumComesTwice', () {
      final marks = homeAlbumMemoryMarks([
        _candidate('a', notes: 4),
        _candidate('a', average: 7, rated: 2, notes: 1),
        _candidate('b', review: true),
      ]);
      expect(marks.keys, [MediaId.subsonic('a')]);
      expect(
        marks[MediaId.subsonic('a')],
        const HomeAlbumMemoryMark(kind: MemoryScoreKind.averageTrackRating, scoreText: '7.0', noteCount: 4),
      );
    });

    test('should_markPlainAlbumCoversOnly_when_seatingJbi', () {
      final album = Album(id: MediaId.subsonic('a'), name: 'A');
      HomeWidgetCard card({HomeCardKind kind = HomeCardKind.album, HomeTarget? target, JbiSignal? signal}) =>
          HomeWidgetCard(
            stableId: 'x',
            kind: kind,
            title: 'A',
            subtitle: '',
            target: target ?? HomeOpenAlbum(album),
            signal: signal,
          );
      expect(jbiMarkAlbum(card()), album.id);
      expect(jbiMarkAlbum(card(signal: const JbiSignal(scoreText: '8.0'))), isNull);
      expect(
        jbiMarkAlbum(
          card(
            kind: HomeCardKind.song,
            target: HomePlaySong(Track(id: MediaId.subsonic('t'))),
          ),
        ),
        isNull,
      );
      final spotify = Album(id: MediaId(MediaId.providerSpotify, 's'), name: 'S');
      expect(jbiMarkAlbum(card(target: HomeOpenAlbum(spotify))), isNull);
      expect(shelfMarkAlbum(spotify), isNull);
      expect(shelfMarkAlbum(album), album.id);
    });

    test('should_sayScoreAndNotes_when_read', () {
      expect(
        albumMemoryMarkDescription(
          const HomeAlbumMemoryMark(kind: MemoryScoreKind.albumRating, scoreText: '9.0', noteCount: 3),
          _en,
        ),
        'Your rating 9.0, 3 notes',
      );
      expect(
        albumMemoryMarkDescription(
          const HomeAlbumMemoryMark(kind: MemoryScoreKind.averageTrackRating, scoreText: '8.4'),
          _en,
        ),
        'Track average 8.4',
      );
      expect(
        albumMemoryMarkDescription(const HomeAlbumMemoryMark(kind: MemoryScoreKind.none, noteCount: 2), _en),
        '2 notes',
      );
    });

    test('should_closeDashesIntoOneLine_when_solid', () {
      final loop = Path()..addOval(const Rect.fromLTWH(0, 0, 10.75, 10.75));
      final dashed = dashedRuns(loop, 0);
      // ~33.8 around at a 3.5 period: whole dashes round the loop.
      expect(dashed.length, inInclusiveRange(9, 11));
      expect(dashedRuns(loop, 1), hasLength(1));
      final half = dashedRuns(loop, 0.5);
      double length(List<Path> runs) =>
          runs.fold(0.0, (sum, p) => sum + p.computeMetrics().fold(0.0, (s, m) => s + m.length));
      expect(length(half), greaterThan(length(dashed)));
    });
  });

  group('sticker', () {
    Future<void> pump(WidgetTester tester, HomeAlbumMemoryMark? mark) => tester.pumpWidget(
      MaterialApp(
        theme: yoinTheme(Brightness.light),
        home: Center(child: AlbumMemoryMarkSticker(mark: mark)),
      ),
    );

    Finder painted() => find.byWidgetPredicate((w) => w is CustomPaint && w.painter is AlbumMemoryMarkPainter);

    testWidgets('should_layNothingOut_when_coverNeverHadAMark', (tester) async {
      await pump(tester, null);
      expect(painted(), findsNothing);
    });

    testWidgets('should_fadeInAndOutAsItself_when_markComesAndGoes', (tester) async {
      final semantics = tester.ensureSemantics();
      await pump(tester, null);
      const mark = HomeAlbumMemoryMark(kind: MemoryScoreKind.averageTrackRating, scoreText: '8.4', noteCount: 1);
      await pump(tester, mark);
      expect(painted(), findsOneWidget);
      AlbumMemoryMarkPainter painter() => tester.widget<CustomPaint>(painted()).painter! as AlbumMemoryMarkPainter;
      // It lands faded out, inked as itself (a line, no fill), and springs in.
      expect(painter().appear.value, lessThan(0.5));
      expect(painter().line.value, 1);
      expect(painter().fill.value, 0);
      await tester.pumpAndSettle();
      expect(painter().appear.value, closeTo(1, 0.01));
      expect(tester.getSize(painted()), const Size.square(16));
      expect(find.bySemanticsLabel('Track average 8.4, 1 note'), findsOneWidget);
      // A score turns the line into a fill on the same spring.
      await pump(tester, const HomeAlbumMemoryMark(kind: MemoryScoreKind.albumRating, scoreText: '9.0'));
      await tester.pumpAndSettle();
      expect(painter().fill.value, closeTo(1, 0.01));
      expect(painter().line.value, closeTo(0, 0.01));
      // Gone: still painted while it leaves, silent to the reader, then faded out.
      await pump(tester, null);
      expect(painted(), findsOneWidget);
      expect(find.bySemanticsLabel(RegExp('Your rating')), findsNothing);
      await tester.pumpAndSettle();
      expect(painter().appear.value, closeTo(0, 0.01));
      semantics.dispose();
    });

    testWidgets('should_snap_when_reducedMotion', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          theme: yoinTheme(Brightness.dark),
          home: MediaQuery(
            data: const MediaQueryData(disableAnimations: true),
            child: Center(child: AlbumMemoryMarkSticker(mark: null)),
          ),
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          theme: yoinTheme(Brightness.dark),
          home: const MediaQuery(
            data: MediaQueryData(disableAnimations: true),
            child: Center(
              child: AlbumMemoryMarkSticker(mark: HomeAlbumMemoryMark(kind: MemoryScoreKind.none, noteCount: 2)),
            ),
          ),
        ),
      );
      final painter =
          tester
                  .widget<CustomPaint>(
                    find.byWidgetPredicate((w) => w is CustomPaint && w.painter is AlbumMemoryMarkPainter),
                  )
                  .painter!
              as AlbumMemoryMarkPainter;
      expect(painter.appear.value, 1);
      expect(painter.solidity.value, 0);
    });
  });
}
