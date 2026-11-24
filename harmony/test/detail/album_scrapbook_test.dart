import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/detail/album_scrapbook_track_sheet.dart';
import 'package:yoin/data/notes/note_markup.dart';
import 'package:yoin/notes/editor/note_editor_controller.dart';
import 'package:yoin/notes/note_markup_text.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/album_scrapbook.dart';
import 'package:yoin/detail/album_scrapbook_page.dart';
import 'package:yoin/detail/album_scrapbook_sheet_model.dart';
import 'package:yoin/detail/album_spectrum_layer.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/component/seam/seam.dart';

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

AlbumNote _albumNote(String id, String text, {int created = 1}) => AlbumNote(
  id: id,
  albumId: _album.id,
  content: text,
  createdAt: created,
  updatedAt: created,
  albumName: 'Blue',
  artist: 'Joni',
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
        // Android's receipt counts the questions asked too (AlbumScrapbook receiptLines).
        ('Asked', '0'),
      ]);
    });

    test('should_pressEveryAlbumNoteAsAStickyOldestFirst_when_built', () {
      final data = AlbumScrapbookData(
        review: 'Late-night record.',
        albumNotes: [
          _albumNote('b', '  second  ', created: 20),
          _albumNote('blank', '   ', created: 5),
          _albumNote('a', 'first', created: 10),
          _albumNote('c', 'third', created: 30),
        ],
      );
      final opening = buildAlbumScrapbook(_album, data, nowMs: _now).first as ScrapOpening;

      expect([for (final s in opening.stickies) (s.noteId, s.text)], [('a', 'first'), ('b', 'second'), ('c', 'third')]);
      // The first leans against the review, each next one against the one above; never 0.
      expect(opening.stickies.first.tilt.sign, -opening.reviewTilt.sign);
      for (var i = 1; i < opening.stickies.length; i++) {
        expect(opening.stickies[i].tilt.sign, -opening.stickies[i - 1].tilt.sign);
      }
      expect(opening.stickies.every((s) => ScrapbookRules.paperTilts.contains(s.tilt)), isTrue);
      // The same page every time.
      expect((buildAlbumScrapbook(_album, data, nowMs: _now).first as ScrapOpening).stickies, opening.stickies);
    });

    test('should_dropNothingYetAndCountStickies_when_albumNotesLeft', () {
      final pieces = buildAlbumScrapbook(
        _album,
        AlbumScrapbookData(
          albumNotes: [_albumNote('a', 'first'), _albumNote('b', 'second')],
          notes: {
            _t(2): [_note('n', 2)],
          },
          plays: AlbumPlays(albumId: _album.id, playCount: 1, firstPlayedAt: 1, lastPlayedAt: 1),
        ),
        nowMs: _now,
      );
      expect(pieces.whereType<ScrapNotYet>(), isEmpty);
      expect(
        buildAlbumScrapbook(_album, AlbumScrapbookData(albumNotes: [_albumNote('a', 'x')]), nowMs: _now).length,
        1,
      );
      // Every album note counts with the track notes (D3's receipt).
      expect((pieces.last as ScrapReceipt).lines, contains(('Notes', '3')));
    });

    test('should_saveTheOpenSticky_when_asked', () {
      expect(stickySave(StickyEditing.newSticky, '  '), isNull);
      final created = stickySave(StickyEditing.newSticky, '  whole album  ') as ScrapNewAlbumNoteEdit;
      expect(created.text, 'whole album');
      final edited = stickySave('n1', ' new words ') as ScrapAlbumNoteTextEdit;
      expect((edited.noteId, edited.text), ('n1', 'new words'));
      final deleted = stickySave('n1', '   ') as ScrapAlbumNoteTextEdit;
      expect(deleted.text, isEmpty);

      final editing = StickyEditing();
      var told = 0;
      editing.addListener(() => told++);
      editing.startNew();
      expect((editing.open, editing.text), (StickyEditing.newSticky, ''));
      editing.startEdit(const ScrapSticky(noteId: 'n1', text: 'words', tilt: 1));
      expect((editing.open, editing.text), ('n1', 'words'));
      editing.close();
      expect(editing.open, isNull);
      expect(told, 3);
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

    testWidgets('should_scrollUnderTheHeaderThroughTheSeam_when_scrapbookShown', (tester) async {
      await open(tester);
      await tester.tap(find.bySemanticsLabel('Scrapbook'));
      await settle(tester);
      final scroll = find.descendant(of: find.byType(AlbumScrapbookPage), matching: find.byType(SingleChildScrollView));
      // AlbumScrapbookPage.kt: seamDissolveViewport at the page's top edge, the pieces faded or dissolved into it.
      expect(find.ancestor(of: scroll.first, matching: find.byType(ChromeSeam)), findsOneWidget);
      expect(find.descendant(of: scroll.first, matching: find.byType(SeamFade)), findsWidgets);
      expect(find.descendant(of: scroll.first, matching: find.byType(SeamDissolve)), findsWidgets);
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

    Future<void> toScrapbook(WidgetTester tester) async {
      await tester.tap(find.bySemanticsLabel('Scrapbook'));
      await settle(tester);
      expect(find.byType(AlbumScrapbookPage), findsOneWidget);
    }

    testWidgets('should_writeAStickyInPlace_when_dashedStickyTapped', (tester) async {
      final semantics = tester.ensureSemantics();
      final (_, _, store) = await open(tester);
      await toScrapbook(tester);

      await tester.tap(find.bySemanticsLabel('Write an album note'));
      await settle(tester);
      expect(find.text('Write about the whole album'), findsOneWidget);
      expect(find.bySemanticsLabel('Write an album note'), findsNothing, reason: 'the editor took its place');

      await tester.enterText(find.byType(TextField), '  The whole record is one long night.  ');
      await tester.tap(find.text('Save'));
      await settle(tester);

      final notes = await store.albumNotes(_album.id);
      expect(notes.single.content, 'The whole record is one long night.');
      expect(notes.single.albumName, 'Blue');
      expect(notes.single.artist, 'Joni');
      expect(
        notes.single.id,
        matches(RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$')),
      );
      expect(find.text('The whole record is one long night.'), findsOneWidget);
      expect(find.bySemanticsLabel('Album note: The whole record is one long night.'), findsOneWidget);
      expect(find.bySemanticsLabel('Write an album note'), findsOneWidget, reason: 'a dashed sticky for the next');
      semantics.dispose();
      await close(tester);
    });

    testWidgets('should_openANewSticky_when_barepaperTapped_andWriteNothing_when_cancelled', (tester) async {
      final (_, _, store) = await open(tester);
      await toScrapbook(tester);
      final scroll = find.descendant(of: find.byType(AlbumScrapbookPage), matching: find.byType(SingleChildScrollView));

      await tester.tapAt(tester.getBottomLeft(scroll.first) + const Offset(40, -200));
      await settle(tester);
      expect(find.text('Write about the whole album'), findsOneWidget);

      await tester.tap(find.text('Cancel'));
      await settle(tester);
      expect(find.byType(TextField), findsNothing);
      expect(await store.albumNotes(_album.id), isEmpty);

      // A blank new sticky writes nothing either.
      await tester.tapAt(tester.getBottomLeft(scroll.first) + const Offset(40, -200));
      await settle(tester);
      await tester.tap(find.text('Save'));
      await settle(tester);
      expect(await store.albumNotes(_album.id), isEmpty);
      await close(tester);
    });

    testWidgets('should_editAndDeleteWithUndo_when_stickyTapped', (tester) async {
      final (c, _, store) = await open(tester);
      final notices = <TextNotice>[];
      final sub = c.read(playbackControllerProvider.notifier).notices.listen((n) {
        if (n is TextNotice) notices.add(n);
      });
      addTearDown(sub.cancel);
      await store.putAlbumNote(_albumNote('an1', 'first words', created: 10));
      await store.putAlbumNote(_albumNote('an2', 'second sticky', created: 20));
      await toScrapbook(tester);
      expect(find.text('first words'), findsOneWidget);
      expect(find.text('second sticky'), findsOneWidget);

      await tester.tap(find.text('first words'));
      await settle(tester);
      expect(tester.widget<TextField>(find.byType(TextField)).controller!.text, 'first words');
      await tester.enterText(find.byType(TextField), 'better words');
      await tester.tap(find.text('Save'));
      await settle(tester);
      var notes = await store.albumNotes(_album.id);
      expect([for (final n in notes) (n.id, n.content)], [('an1', 'better words'), ('an2', 'second sticky')]);
      expect(notes.first.createdAt, 10);
      expect(notes.first.updatedAt, greaterThan(10));

      await tester.tap(find.text('second sticky'));
      await settle(tester);
      await tester.enterText(find.byType(TextField), '   ');
      await tester.tap(find.text('Save'));
      await settle(tester);
      expect([for (final n in await store.albumNotes(_album.id)) n.id], ['an1']);
      expect(find.text('second sticky'), findsNothing);
      expect(notices.single.text, 'Album note deleted');
      expect(notices.single.actionLabel, 'Undo');

      notices.single.onAction!();
      await settle(tester);
      notes = await store.albumNotes(_album.id);
      expect(
        [for (final n in notes) (n.id, n.content, n.createdAt)],
        [('an1', 'better words', 10), ('an2', 'second sticky', 20)],
      );
      expect(notes.last.updatedAt, greaterThan(20));
      expect(find.text('second sticky'), findsOneWidget);
      await close(tester);
    });

    testWidgets('should_tickAStickysTodo_without_openingIt', (tester) async {
      final (_, _, store) = await open(tester);
      await store.putAlbumNote(_albumNote('an1', '- [ ] find the vinyl', created: 10));
      await toScrapbook(tester);
      final words = find.descendant(of: find.byType(NoteMarkupText), matching: find.byType(RichText));
      final sticky = words.evaluate().firstWhere((e) => (e.widget as RichText).text.toPlainText().contains('find the vinyl'));
      await tester.tapAt(tester.getTopLeft(find.byElementPredicate((e) => e == sticky)) + const Offset(4, 8));
      await settle(tester);
      expect((await store.albumNotes(_album.id)).single.content, '- [x] find the vinyl');
      expect(find.byType(TextField), findsNothing, reason: 'the box ticks; the sticky stays shut');
      await close(tester);
    });

    testWidgets('should_saveAMention_when_writtenOnASticky', (tester) async {
      final (_, _, store) = await open(tester);
      await toScrapbook(tester);
      await tester.tap(find.bySemanticsLabel('Write an album note'));
      await settle(tester);
      final field = tester.widget<TextField>(find.byType(TextField));
      final controller = field.controller! as NoteEditorController;
      controller.text = 'like ';
      controller.insertMention(
        NoteMention(kind: MentionKind.album, provider: 'subsonic', id: 'al9', title: 'Court and Spark'),
      );
      await settle(tester);
      expect(find.text('@Court and Spark'), findsOneWidget, reason: 'one chip in the editor');
      await tester.tap(find.text('Save'));
      await settle(tester);
      expect((await store.albumNotes(_album.id)).single.content, 'like [@Court and Spark](yoin:album/subsonic:al9)');
      await close(tester);
    });

    testWidgets('should_closeTheEditor_when_itsStickyIsDeletedElsewhere', (tester) async {
      final (_, _, store) = await open(tester);
      await store.putAlbumNote(_albumNote('an1', 'synced sticky'));
      await toScrapbook(tester);
      await tester.tap(find.text('synced sticky'));
      await settle(tester);
      expect(find.byType(TextField), findsOneWidget);

      await store.deleteAlbumNote('an1');
      await settle(tester);

      expect(find.byType(TextField), findsNothing);
      expect(find.bySemanticsLabel('Write an album note'), findsOneWidget);
      await close(tester);
    });

    testWidgets('should_tuckStickiesUnderTheReview_when_wideOrPhone', (tester) async {
      for (final size in const [Size(1280, 800), Size(400, 860)]) {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final (_, _, store) = await open(tester);
        await store.setAlbumReview(_album.id, 'Late-night record.', nowMs: 1);
        await store.putAlbumNote(_albumNote('an1', 'one sticky', created: 10));
        await toScrapbook(tester);

        final review = tester.getRect(find.text('Late-night record.'));
        final sticky = tester.getRect(find.text('one sticky'));
        expect(sticky.top, greaterThan(review.top), reason: '$size: under the review');
        expect(sticky.width, lessThanOrEqualTo(ScrapbookLayout.stickyMaxWidth), reason: '$size');
        expect(find.bySemanticsLabel('Write an album note'), findsOneWidget);
        expect(tester.takeException(), isNull);
        await close(tester);
      }
    });

    testWidgets('should_openTheNoteInTheTrackSheet_notPlay_when_noteTapped', (tester) async {
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
      // The track sheet on its Note tab, the note in view; nothing started playing.
      expect(find.byType(ScrapTrackSheet), findsOneWidget);
      expect(tester.widget<ScrapTrackSheet>(find.byType(ScrapTrackSheet)).focusNote, 'n1');
      expect(find.descendant(of: find.byType(ScrapTrackSheet), matching: find.text('the bridge')), findsOneWidget);
      expect(engine.startIndex, -1);
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
