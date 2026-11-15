import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/album_scrapbook.dart';
import 'package:yoin/detail/album_scrapbook_page.dart';
import 'package:yoin/detail/album_spectrum_layer.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

final _album = LibraryFixtures.album(
  'al1',
  name: 'Blue',
  artist: 'Joni',
  tracks: [for (var i = 1; i <= 6; i++) LibraryFixtures.track('t$i', artist: 'Joni', number: i)],
);

MediaId _t(int i) => MediaId.subsonic('t$i');

SongNote _note(String id, int track, {int? at, int created = 0, String text = 'note'}) => SongNote(
  id: id,
  trackId: _t(track),
  albumId: _album.id,
  content: text,
  createdAt: created,
  updatedAt: created,
  positionMs: at,
);

const _now = 1763200000000; // 2025-11-15

void main() {
  group('builder', () {
    test('should_openWithBlankPenAndNotYet_when_nothingLeft', () {
      final pieces = buildAlbumScrapbook(_album, const AlbumScrapbookData(), nowMs: _now);
      expect(pieces.map((p) => p.runtimeType), [ScrapOpening, ScrapNotYet]);
      expect((pieces.first as ScrapOpening).review, isNull);
    });

    test('should_dropNothingYet_when_reviewOrPlaysLeft', () {
      List<Type> kinds(AlbumScrapbookData d) => [
        for (final p in buildAlbumScrapbook(_album, d, nowMs: _now)) p.runtimeType,
      ];
      expect(kinds(const AlbumScrapbookData(review: 'Late-night record.')), [ScrapOpening]);
      expect(kinds(const AlbumScrapbookData(title: 'Winter')), [ScrapOpening]);
      expect(
        kinds(
          AlbumScrapbookData(plays: AlbumPlays(albumId: _album.id, playCount: 1, firstPlayedAt: 1, lastPlayedAt: 1)),
        ),
        [ScrapOpening, ScrapReceipt],
      );
    });

    test('should_pairScoreOnlyTracksAndClusterNotedOnes_when_built', () {
      final pieces = buildAlbumScrapbook(
        _album,
        AlbumScrapbookData(
          ratings: {_t(1): 6, _t(2): 7, _t(3): 0, _t(5): 9.5},
          notes: {
            _t(4): [_note('b', 4, at: 2000), _note('a', 4, at: 1000), _note('c', 4), _note('d', 4, at: 500)],
          },
        ),
        nowMs: _now,
      );
      expect(pieces.map((p) => p.runtimeType), [
        ScrapOpening,
        ScrapContactSheet, // 1, 2
        ScrapContactSheet, // 3 (0.0 is a score)
        ScrapTrackCluster, // 4: notes
        ScrapTrackCluster, // 5: the best score
      ]);
      final sheet = pieces[1] as ScrapContactSheet;
      expect(sheet.tracks.map((t) => t.number), [1, 2]);
      expect(sheet.tilts[0].sign, isNot(sheet.tilts[1].sign), reason: 'neighbours lean opposite ways');
      final noted = pieces[3] as ScrapTrackCluster;
      expect(noted.notes.map((n) => n.id), ['d', 'a', 'b', 'c'], reason: 'timeline order, unanchored last');
      expect(noted.hiddenNotes, 1);
      expect(noted.best, isFalse);
      expect((pieces[4] as ScrapTrackCluster).best, isTrue);
      expect((pieces[1] as ScrapContactSheet).mirror, isNot((pieces[3] as ScrapTrackCluster).mirror));
    });

    test('should_layOutTheSamePage_when_rebuilt', () {
      const data = AlbumScrapbookData(review: 'Late-night record.', reviewAt: 5);
      final a = buildAlbumScrapbook(_album, data, nowMs: _now).first as ScrapOpening;
      final b = buildAlbumScrapbook(_album, data, nowMs: _now).first as ScrapOpening;
      expect(a.coverTilt, b.coverTilt);
      expect(a.reviewTilt, b.reviewTilt);
      expect(ScrapbookRules.coverTilts, contains(a.coverTilt));
      expect(a.review, 'Late-night record.');
      expect(a.reviewAt, 5);
    });

    test('should_printReceipt_when_albumPlayedInYoin', () {
      const day = 86400000;
      final pieces = buildAlbumScrapbook(
        _album,
        AlbumScrapbookData(
          ratings: {_t(1): 8},
          notes: {
            _t(2): [_note('n', 2)],
          },
          plays: AlbumPlays(
            albumId: _album.id,
            playCount: 12,
            firstPlayedAt: _now - 30 * day,
            lastPlayedAt: _now - day,
          ),
        ),
        nowMs: _now,
      );
      final receipt = pieces.last as ScrapReceipt;
      expect(receipt.header, 'BLUE — JONI');
      expect(receipt.lines, [
        ('Plays in Yoin', '12'),
        ('Days since first play', '30'),
        ('Last played', 'Yesterday'),
        ('Rated', '1 / 6'),
        ('Notes', '1'),
      ]);
    });

    test('should_roundLikeMemories_when_scoreWritten', () {
      expect(scrapScoreText(9.95), '10.0');
      expect(scrapScoreText(7), '7.0');
      expect(scrapScoreText(0), '0.0');
      expect(scrapHash('a'), 0xE40C292C, reason: 'FNV-1a, as Android');
      expect(scrapLanes(358), 1);
      expect(scrapLanes(600), 2);
      expect(scrapLanes(2000), 3);
    });
  });

  group('page', () {
    Future<(ProviderContainer, FakePlaybackEngine, MemoryStore)> open(WidgetTester tester) async {
      final engine = FakePlaybackEngine();
      final store = MemoryStore(InMemoryStore(), profileId: 'p');
      final c = ProviderContainer(
        overrides: [
          activeSourceProvider.overrideWith((ref) async => LibraryFakeSource(albumDetails: {_album.id: _album})),
          keyValueStoreProvider.overrideWithValue(InMemoryStore()),
          secretStoreProvider.overrideWithValue(InMemoryStore()),
          activityLogProvider.overrideWith((ref) async => null),
          memoryStoreProvider.overrideWith((ref) async => store),
          playbackEngineProvider.overrideWithValue(engine),
        ],
      );
      addTearDown(c.dispose);
      c.listen(playbackControllerProvider, (_, _) {});
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: c,
          child: MaterialApp(
            theme: yoinTheme(Brightness.light),
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    onPressed: () => DetailRoutes.openAlbum(context, _album.id),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await settle(tester);
      return (c, engine, store);
    }

    testWidgets('should_showScrapbook_when_secondDotTapped', (tester) async {
      final semantics = tester.ensureSemantics();
      await open(tester);
      expect(find.byType(AlbumPageDots), findsOneWidget);
      expect(find.byType(AlbumScrapbookPage), findsNothing);

      await tester.tap(find.bySemanticsLabel('Scrapbook'));
      await settle(tester);

      expect(find.byType(AlbumScrapbookPage), findsOneWidget);
      expect(find.text('Nothing yet'), findsOneWidget);
      // No Memory title (HarmonyOS has no AI title): Last Play follows the blank pen instead.
      expect(find.text('Never'), findsOneWidget);
      semantics.dispose();
      await close(tester);
    });

    testWidgets('should_paintDotsFromOnePalette_when_twoPages', (tester) async {
      await open(tester);
      final dots = tester.widget<AlbumPageDots>(find.byType(AlbumPageDots));
      final scheme = Theme.of(tester.element(find.byType(AlbumPageDots))).colorScheme;
      // Until the cover resolves the page stands on the app scheme: secondary, and onSurfaceVariant at 0.3.
      expect(dots.active, scheme.secondary);
      expect(dots.inactive, scheme.onSurfaceVariant.withValues(alpha: AlbumPageDotsTokens.inactiveAlpha));
      await close(tester);
    });

    testWidgets('should_lightenStatusIcons_when_barFormedUnderThem', (tester) async {
      await open(tester);
      expect(SystemChrome.latestStyle?.statusBarIconBrightness, Brightness.dark, reason: 'light page at rest');

      await tester.drag(find.textContaining('Song\u00A0t2', findRichText: true).first, const Offset(0, -500));
      await settle(tester);
      expect(SystemChrome.latestStyle?.statusBarIconBrightness, Brightness.light, reason: 'over the dark bar');

      await tester.tapAt(tester.getCenter(find.byType(AlbumPageDots)) + const Offset(12, 0));
      await settle(tester);
      expect(SystemChrome.latestStyle?.statusBarIconBrightness, Brightness.dark, reason: 'the bar went with page 0');
      await close(tester);
    });

    testWidgets('should_clipSpectrumToItsLayer_when_painted', (tester) async {
      await open(tester);
      final layer = find.descendant(of: find.byType(AlbumSpectrumLayer), matching: find.byType(CustomPaint)).first;
      expect(tester.renderObject(layer), paints..clipRect());
      await close(tester);
    });

    testWidgets('should_keepHeroTextNodesTheirOwnSize_when_readAloud', (tester) async {
      final semantics = tester.ensureSemantics();
      await open(tester);
      final node = tester.getSemantics(find.bySemanticsLabel(RegExp('tracks')).first);
      expect(node.rect.height, lessThan(60), reason: 'not merged into the hero drag region');
      semantics.dispose();
      await close(tester);
    });

    testWidgets('should_playFromNoteMoment_when_noteTapped', (tester) async {
      final (_, engine, store) = await open(tester);
      await store.putSongNote(_note('n1', 3, at: 61000, text: 'the bridge'));
      await store.setAlbumReview(_album.id, 'Late-night record.', nowMs: 1);
      // On the cover: a sideways drag there still turns the page.
      await tester.drag(find.byType(PageView), const Offset(-600, 0), warnIfMissed: false);
      await settle(tester);
      expect(find.byType(AlbumScrapbookPage), findsOneWidget);

      expect(find.text('Late-night record.'), findsOneWidget);
      expect(find.text('the bridge'), findsOneWidget);
      expect(find.text('1:01'), findsOneWidget);

      await tester.tap(find.text('the bridge'));
      await settle(tester);
      expect(engine.startIndex, 2);
      await close(tester);
    });
  });
}

Future<void> settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> close(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(milliseconds: 1));
  await tester.pump(const Duration(milliseconds: 1));
}
