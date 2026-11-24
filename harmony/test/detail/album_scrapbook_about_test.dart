import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/notes/note_markup_text.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/about/song_about_store.dart';
import 'package:yoin/data/ai/ai_service.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/notes/note_markup.dart';
import 'package:yoin/detail/album_scrapbook.dart';
import 'package:yoin/detail/album_scrapbook_page.dart';
import 'package:yoin/detail/album_scrapbook_sheet_model.dart';
import 'package:yoin/detail/album_scrapbook_track_sheet.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/playback/local_ratings.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

final _album = LibraryFixtures.album(
  'al1',
  name: 'Blue',
  artist: 'Joni',
  tracks: [for (var i = 1; i <= 4; i++) LibraryFixtures.track('t$i', artist: 'Joni', number: i)],
);

MediaId _t(int i) => MediaId.subsonic('t$i');

const _now = 1763200000000;

SongAboutEntry _row(int track, String kind, String key, String answer, {String? prompt, String? title, int at = 1}) =>
    SongAboutEntry(
      titleKey: normalizeAboutKey('Song t$track'),
      artistKey: 'joni',
      albumKey: 'blue',
      titleDisplay: 'Song t$track',
      artistDisplay: 'Joni',
      albumDisplay: 'Blue',
      kind: kind,
      entryKey: key,
      promptText: prompt,
      titleText: title,
      answerText: answer,
      createdAt: at,
      updatedAt: at,
    );

SongNote _note(String id, int track, {int? at, String text = 'note'}) =>
    SongNote(id: id, trackId: _t(track), albumId: _album.id, content: text, createdAt: 0, updatedAt: 0, positionMs: at);

/// Android AlbumScrapbook's Ask / About pieces, matchAboutToTracks,
/// answerExcerpt, the Note tab's rows, and the track sheet.
void main() {
  group('builder', () {
    test('should_matchRowsByTitleAndArtist_when_aboutRead', () {
      final rows = [
        _row(1, SongAboutKinds.ask, 'why', 'Because.', prompt: 'Why?'),
        // Another artist spelling: falls back to the one track with that title.
        _row(2, SongAboutKinds.canonical, AboutFields.lyricist, 'Joni'),
        SongAboutEntry(
          titleKey: normalizeAboutKey('Song t3'),
          artistKey: 'joni mitchell & co',
          albumKey: 'blue',
          titleDisplay: 'Song t3',
          artistDisplay: 'Joni Mitchell & Co',
          albumDisplay: 'Blue',
          kind: SongAboutKinds.canonical,
          entryKey: AboutFields.producer,
          answerText: 'Henry',
          createdAt: 1,
          updatedAt: 1,
        ),
        _row(9, SongAboutKinds.ask, 'none', 'No track.'),
      ];
      final about = matchAboutToTracks(rows, _album.tracks);
      expect(about.keys, unorderedEquals([_t(1), _t(2), _t(3)]));
      expect(about[_t(1)]!.asks.single.question, 'Why?');
      expect(about[_t(3)]!.facts.single.key, AboutFields.producer);
    });

    test('should_showNewestQuestionAndTags_when_trackHasAbout', () {
      final about = matchAboutToTracks([
        _row(1, SongAboutKinds.ask, 'old', 'Old.', prompt: 'Old?', at: 1),
        _row(1, SongAboutKinds.ask, 'new', 'New answer. Second sentence.', prompt: 'New?', title: 'News', at: 5),
        for (final k in [AboutFields.creationTime, AboutFields.lyricist, AboutFields.composer, AboutFields.producer])
          _row(1, SongAboutKinds.canonical, k, 'v-$k'),
        _row(1, SongAboutKinds.canonical, AboutFields.review, 'A review.'),
        _row(1, SongAboutKinds.canonical, AboutFields.creationLocation, ''),
      ], _album.tracks);
      final pieces = buildAlbumScrapbook(_album, AlbumScrapbookData(about: about), nowMs: _now);
      final cluster = pieces.whereType<ScrapTrackCluster>().single;
      expect(cluster.ask!.question, 'New?');
      expect(cluster.ask!.title, 'News');
      expect(cluster.moreAsks, 1);
      expect(cluster.aboutLine, isNull);
      expect(
        [for (final t in cluster.tags) t.key],
        [AboutFields.creationTime, AboutFields.lyricist, AboutFields.composer],
      );
      expect(cluster.hiddenTags, 1);
      expect(cluster.sheet!.facts, hasLength(5));
      expect(cluster.cardTilt, isNot(0));
      expect(pieces.whereType<ScrapNotYet>(), isEmpty);
    });

    test('should_showReviewsFirstSentence_when_noQuestion', () {
      final about = matchAboutToTracks([
        _row(2, SongAboutKinds.canonical, AboutFields.review, 'First sentence here. Then a second.'),
      ], _album.tracks);
      final cluster = buildAlbumScrapbook(
        _album,
        AlbumScrapbookData(about: about),
        nowMs: _now,
      ).whereType<ScrapTrackCluster>().single;
      expect(cluster.aboutLine, 'First sentence here.');
      expect(cluster.tags, isEmpty);
    });

    test('should_countOnlyAiQuestions_when_receiptPrinted', () {
      final about = matchAboutToTracks([
        _row(1, SongAboutKinds.ask, 'q', 'A.', prompt: 'Q?'),
        _row(1, SongAboutKinds.own, 'mine', 'Mine.', title: 'Mine'),
      ], _album.tracks);
      final pieces = buildAlbumScrapbook(
        _album,
        AlbumScrapbookData(
          about: about,
          plays: AlbumPlays(albumId: _album.id, playCount: 2, firstPlayedAt: _now, lastPlayedAt: _now),
        ),
        nowMs: _now,
      );
      expect((pieces.last as ScrapReceipt).lines.last, ('Asked', '1'));
    });

    test('should_titleWithAiTitle_when_userGaveNone', () {
      final opening =
          buildAlbumScrapbook(_album, const AlbumScrapbookData(aiTitle: 'Blue hours'), nowMs: _now).first
              as ScrapOpening;
      expect(opening.title, 'Blue hours');
      final mine =
          buildAlbumScrapbook(
                _album,
                const AlbumScrapbookData(aiTitle: 'Blue hours', title: 'Mine'),
                nowMs: _now,
              ).first
              as ScrapOpening;
      expect(mine.title, 'Mine');
    });

    test('should_keepWholeSentencesWithinBudget_when_excerpted', () {
      expect(answerExcerpt('One. Two. Three.', 6).text, 'One.');
      expect(answerExcerpt('One. Two. Three.', 6).truncated, isTrue);
      expect(answerExcerpt('One. Two.', 100).text, 'One. Two.');
      expect(answerExcerpt('One. Two.', 100).truncated, isFalse);
      // CJK counts twice; the first sentence always shows.
      expect(answerExcerpt('很长的一句话。第二句。', 4).text, '很长的一句话。');
    });
  });

  group('note rows', () {
    final lines = [for (var i = 0; i < 12; i++) ScrapLyricLine(1000 * (i + 1), 'line')];

    test('should_showThreeLinesAroundEachNote_when_collapsed', () {
      final rows = scrapNoteRows(lines, [_note('a', 1, at: 2500), _note('b', 1, at: 11500)], expanded: false);
      final shown = [
        for (final r in rows)
          switch (r) {
            ScrapLyricRow(:final index) => 'L$index',
            ScrapNoteCardRow(:final note) => 'N${note.id}',
            ScrapGapRow() => 'gap',
            ScrapDraftRow() => 'draft',
          },
      ];
      expect(shown, ['L0', 'L1', 'Na', 'L2', 'L3', 'L4', 'gap', 'L7', 'L8', 'L9', 'L10', 'Nb', 'L11']);
      expect((rows[1] as ScrapLyricRow).noted, isTrue);
    });

    test('should_mergePassages_when_notesClose', () {
      final rows = scrapNoteRows(lines, [_note('a', 1, at: 3000), _note('b', 1, at: 8000)], expanded: false);
      expect(rows.whereType<ScrapGapRow>(), isEmpty);
    });

    test('should_listLooseNotesAfterAGap_when_noMoment', () {
      final rows = scrapNoteRows(lines, [_note('a', 1)], expanded: true, draft: const ScrapDraftRow(5500));
      expect(rows.whereType<ScrapLyricRow>(), hasLength(12));
      expect(rows.last, isA<ScrapNoteCardRow>());
      expect(rows[rows.length - 2], isA<ScrapGapRow>());
      final draftAt = rows.indexWhere((r) => r is ScrapDraftRow);
      expect((rows[draftAt - 1] as ScrapLyricRow).index, 4);
    });

    test('should_listNotesAlone_when_noTimedLyrics', () {
      final rows = scrapNoteRows(const [], [_note('a', 1, at: 10)], expanded: false);
      expect(rows.single, isA<ScrapNoteCardRow>());
      expect(lyricIndexAt(lines, 500), -1);
      expect(lyricIndexAt(lines, 12000), 11);
    });
  });

  group('sheet', () {
    Future<(ProviderContainer, MemoryStore, SongAboutStore)> openPage2(
      WidgetTester tester, {
      String noteText = 'the bridge',
    }) async {
      final engine = FakePlaybackEngine();
      final kv = InMemoryStore();
      final store = MemoryStore(InMemoryStore(), profileId: 'p');
      final about = SongAboutStore.shared(kv);
      await store.setAlbumReview(_album.id, 'Late-night record.', nowMs: 1);
      await store.putSongNote(_note('n1', 2, text: noteText));
      await about.upsertAll([
        _row(1, SongAboutKinds.ask, 'who', 'Joni wrote it alone.', prompt: 'Who wrote it?', title: 'The writer'),
        _row(1, SongAboutKinds.canonical, AboutFields.lyricist, 'Joni Mitchell'),
      ]);
      final c = ProviderContainer(
        overrides: [
          activeSourceProvider.overrideWith((ref) async => LibraryFakeSource(albumDetails: {_album.id: _album})),
          keyValueStoreProvider.overrideWithValue(kv),
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
      await _settle(tester);
      await tester.drag(find.byType(PageView), const Offset(-600, 0), warnIfMissed: false);
      await _settle(tester);
      expect(find.byType(AlbumScrapbookPage), findsOneWidget);
      return (c, store, about);
    }

    testWidgets('should_showQuestionCardAndTag_when_trackWasAskedAbout', (tester) async {
      await openPage2(tester);
      expect(find.text('Who wrote it?'), findsOneWidget);
      expect(find.text('The writer'), findsOneWidget);
      expect(find.text('Q'), findsOneWidget);
      expect(find.text('Joni Mitchell'), findsOneWidget);
      expect(find.text('Lyricist'), findsOneWidget);
      await _close(tester);
    });

    testWidgets('should_openTrackSheet_when_ticketLongPressed', (tester) async {
      await openPage2(tester);
      await tester.longPress(find.text('Song t2'));
      await _settle(tester);
      expect(find.byType(ScrapTrackSheet), findsOneWidget);
      expect(find.text('About'), findsWidgets);
      expect(find.text('Note · 1'), findsOneWidget);
      // No About yet: the AI's About in one tap.
      expect(find.text('Nothing about this song yet.'), findsOneWidget);
      expect(find.text('Generate with Gemini'), findsOneWidget);
      await _close(tester);
    });

    testWidgets('should_writeOwnEntry_when_titleWrittenAndSaved', (tester) async {
      final (_, _, about) = await openPage2(tester);
      await tester.longPress(find.text('Song t2'));
      await _settle(tester);
      await tester.enterText(find.widgetWithText(TextField, 'A title, like: what is this song about'), 'My story');
      await tester.pump();
      await tester.tap(find.text('Write'));
      await _settle(tester);
      await tester.enterText(find.widgetWithText(TextField, 'Write what you know…'), 'Recorded at night.');
      await tester.pump();
      await tester.tap(find.text('Save'));
      await _settle(tester);
      final own = (await about.all()).where((r) => r.isOwn).single;
      expect(own.titleText, 'My story');
      expect(own.answerText, 'Recorded at night.');
      expect(own.titleKey, normalizeAboutKey('Song t2'));
      expect(own.albumKey, 'blue');
      await _close(tester);
    });

    testWidgets('should_removeAndUndo_when_factDeletedInSheet', (tester) async {
      final (_, _, about) = await openPage2(tester);
      await tester.tap(find.text('Who wrote it?'));
      await _settle(tester);
      expect(find.byType(ScrapTrackSheet), findsOneWidget);
      // The fact's pencil opens its editor.
      await tester.tap(find.byTooltip('Edit').first);
      await _settle(tester);
      await tester.tap(find.text('Delete'));
      await _settle(tester);
      final lyricist = _row(1, SongAboutKinds.canonical, AboutFields.lyricist, '').ref;
      expect((await about.get(lyricist))!.answerText, '');
      expect(find.text('Deleted “Lyricist”'), findsOneWidget);
      await tester.tap(find.text('Undo'));
      await _settle(tester);
      expect((await about.get(lyricist))!.answerText, 'Joni Mitchell');
      await _close(tester);
    });

    testWidgets('should_writeNoteWithoutMoment_when_songHasNoTimedLyrics', (tester) async {
      final (_, store, _) = await openPage2(tester);
      await tester.longPress(find.text('Song t2'));
      await _settle(tester);
      await tester.tap(find.text('Note · 1'));
      await _settle(tester);
      expect(find.text('the bridge'), findsWidgets);
      await tester.tap(find.text('New note'));
      await _settle(tester);
      await tester.enterText(find.widgetWithText(TextField, 'A note…'), 'second thought');
      await tester.tap(find.text('Done'));
      await _settle(tester);
      final notes = await store.notesForTracks({_t(2)});
      expect(notes.map((n) => n.content), containsAll(['the bridge', 'second thought']));
      expect(notes.firstWhere((n) => n.content == 'second thought').positionMs, isNull);
      await _close(tester);
    });

    // An Android note: bold, a mention of this album, a done and an empty to-do (its trailing space included).
    const androidNote = '**Bold** see [@Blue](yoin:album/subsonic:al1?a=Joni)\n- [x] done\n- [ ] ';

    Future<void> openNoteEditor(WidgetTester tester) async {
      await tester.longPress(find.text('Song t2'));
      await _settle(tester);
      await tester.tap(find.text('Note · 1'));
      await _settle(tester);
      await tester.tap(find.textContaining('Bold see', findRichText: true).last);
      await _settle(tester);
      await tester.tap(find.text('Edit'));
      await _settle(tester);
    }

    testWidgets('should_drawMarkupNotRawLinks_when_androidNoteShown', (tester) async {
      await openPage2(tester, noteText: androidNote);
      // The mention is a pill (cover + name) in the words.
      expect(find.textContaining('Bold see', findRichText: true), findsWidgets);
      expect(find.widgetWithText(MentionPill, 'Blue'), findsWidgets);
      expect(find.textContaining('yoin:', findRichText: true), findsNothing);
      expect(find.textContaining('**', findRichText: true), findsNothing);
      await tester.longPress(find.text('Song t2'));
      await _settle(tester);
      await tester.tap(find.text('Note · 1'));
      await _settle(tester);
      expect(find.textContaining('☑  done', findRichText: true), findsWidgets);
      expect(find.textContaining('yoin:', findRichText: true), findsNothing);
      await _close(tester);
    });

    testWidgets('should_keepStoredTextExactly_when_noteEditedAndLeftAsItWas', (tester) async {
      final (_, store, _) = await openPage2(tester, noteText: androidNote);
      await openNoteEditor(tester);
      // The editor is plain text: it holds the stored markup as written.
      final field = tester.widget<TextField>(find.byType(TextField).last);
      expect(field.controller!.text, androidNote);
      await tester.tap(find.text('Done'));
      await _settle(tester);
      final note = (await store.notesForTracks({_t(2)})).single;
      expect(note.content, androidNote);
      expect(note.updatedAt, 0, reason: 'nothing written');
      await _close(tester);
    });

    testWidgets('should_keepEveryMark_when_noteWordsEdited', (tester) async {
      final (_, store, _) = await openPage2(tester, noteText: androidNote);
      await openNoteEditor(tester);
      final edited = androidNote.replaceFirst('done', 'done twice');
      await tester.enterText(find.byType(TextField).last, edited);
      await tester.tap(find.text('Done'));
      await _settle(tester);
      final note = (await store.notesForTracks({_t(2)})).single;
      expect(note.content, edited.trim());
      expect(NoteMarkup.mentions(note.content).single.mediaId, 'subsonic:al1');
      expect(note.content, startsWith('**Bold** see [@Blue](yoin:album/subsonic:al1?a=Joni)\n- [x] done twice\n'));
      await _close(tester);
    });

    testWidgets('should_clearScoreWithUndo_when_clearTapped', (tester) async {
      final (c, _, _) = await openPage2(tester);
      await tester.longPress(find.text('Song t2'));
      await _settle(tester);
      expect(find.text('Rate'), findsOneWidget);
      await tester.tap(find.text('Rate'));
      await _settle(tester);
      expect(find.text('Tap or slide to rate'), findsOneWidget);
      await tester.tap(find.text('Tap or slide to rate'));
      await _settle(tester);
      expect(find.text('Clear rating'), findsOneWidget);
      await tester.tap(find.text('Clear rating'));
      await _settle(tester);
      expect(find.text('Rating cleared'), findsOneWidget);
      expect(c.read(localRatingsProvider)[_t(2)]?.rating, isNull);
      await _close(tester);
    });
  });
}

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> _close(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(milliseconds: 1));
  await tester.pump(const Duration(milliseconds: 1));
}
