import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/about/song_about_repository.dart';
import 'package:yoin/data/about/song_about_store.dart';
import 'package:yoin/data/ai/ai_service.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../../support/fake_ai_client.dart';

const _ready = AiSettings(provider: AiProvider.qwen, qwenKey: 'q', targetLanguage: 'English');

const _about = '''
[CREATION_TIME]1971[/CREATION_TIME]
[CREATION_LOCATION]Los Angeles[/CREATION_LOCATION]
[LYRICIST]Joni Mitchell[/LYRICIST]
[COMPOSER]Joni Mitchell[/COMPOSER]
[PRODUCER]Joni Mitchell[/PRODUCER]
[REVIEW]A quiet record. It aged well.[/REVIEW]''';

/// Android YoinRepository's About: the table's keys, the canonical fetch, Ask,
/// regenerate, the listener's edits and own entries.
void main() {
  late InMemoryStore kv;
  late SongAboutStore store;
  late FakeAiClient client;
  late AiSettings settings;
  var now = 1000;

  SongAboutRepository repo() =>
      SongAboutRepository(store: store, settings: () async => settings, ai: AiService(client), clock: () => now);

  setUp(() {
    kv = InMemoryStore();
    store = SongAboutStore(kv);
    client = FakeAiClient(reply: _about);
    settings = _ready;
    now = 1000;
  });

  group('keys', () {
    test('should_collapseCaseSpacesAndFeat_when_normalized', () {
      expect(normalizeAboutKey('  Fake   Love (feat. X) '), 'fake love');
      expect(normalizeAboutKey('FAKE love (Feat. Y & Z)'), 'fake love');
    });

    test('should_keepCanonicalOrderThenNewestFirst_when_shownForDisplay', () {
      SongAboutEntry row(String kind, String key, String answer, int created) => SongAboutEntry(
        titleKey: 't',
        artistKey: 'a',
        albumKey: 'l',
        titleDisplay: 'T',
        artistDisplay: 'A',
        albumDisplay: 'L',
        kind: kind,
        entryKey: key,
        answerText: answer,
        createdAt: created,
        updatedAt: created,
      );
      final rows = aboutRowsForDisplay([
        row(SongAboutKinds.ask, 'old', 'x', 1),
        row(SongAboutKinds.canonical, AboutFields.review, 'r', 5),
        row(SongAboutKinds.ask, 'removed', '  ', 9),
        row(SongAboutKinds.own, 'mine', 'y', 7),
        row(SongAboutKinds.canonical, AboutFields.creationTime, '1971', 5),
      ]);
      expect([for (final r in rows) r.entryKey], [AboutFields.creationTime, AboutFields.review, 'mine', 'old']);
    });
  });

  group('canonical', () {
    test('should_reportKeyMissing_when_noServiceAndNothingCached', () async {
      settings = const AiSettings();
      final result = await repo().ensureCanonicalAbout(title: 'Blue', artist: 'Joni', album: 'Blue');
      expect(result, isA<AboutLoadKeyMissing>());
      expect(client.prompts, isEmpty);
    });

    test('should_writeAllSixRowsOnce_when_fetchedThenOpenedAgain', () async {
      final r = repo();
      expect(await r.ensureCanonicalAbout(title: 'Blue', artist: 'Joni', album: 'Blue'), isA<AboutLoadSuccess>());
      final rows = await store.forSong('blue', 'joni', 'blue');
      expect(rows, hasLength(6));
      expect(rows.firstWhere((e) => e.entryKey == AboutFields.lyricist).answerText, 'Joni Mitchell');
      expect(await r.ensureCanonicalAbout(title: 'Blue', artist: 'Joni', album: 'Blue'), isA<AboutLoadSuccess>());
      expect(client.prompts, hasLength(1));
      expect(client.prompts.single, contains('Write all human-readable\nanswers in English.'));
    });

    test('should_keepCachedRows_when_keyGoesAwayLater', () async {
      await repo().ensureCanonicalAbout(title: 'Blue', artist: 'Joni', album: 'Blue');
      settings = const AiSettings();
      final result = await repo().ensureCanonicalAbout(title: 'Blue', artist: 'Joni', album: 'Blue', retry: true);
      // Retry without a key: nothing is read back as cached, so the key is what's missing.
      expect(result, isA<AboutLoadKeyMissing>());
      expect(await store.forSong('blue', 'joni', 'blue'), hasLength(6));
    });

    test('should_returnTheServicesMessage_when_callFails', () async {
      client.error = Exception('boom');
      final result = await repo().ensureCanonicalAbout(title: 'Blue', artist: 'Joni', album: 'Blue');
      expect(result, isA<AboutLoadError>());
    });
  });

  group('ask', () {
    test('should_keepCreatedAt_when_sameQuestionAskedAgain', () async {
      client.reply = '[TITLE]Where[/TITLE][ANSWER]In LA.[/ANSWER]';
      final r = repo();
      expect(await r.askAboutSong(title: 'Blue', artist: 'Joni', album: 'Blue', question: 'Where?'), isA<AskSuccess>());
      now = 5000;
      client.reply = '[TITLE]Where now[/TITLE][ANSWER]Still LA.[/ANSWER]';
      await r.askAboutSong(title: 'Blue', artist: 'Joni', album: 'Blue', question: '  where?  ');
      final rows = await store.forSong('blue', 'joni', 'blue');
      expect(rows, hasLength(1));
      expect(rows.single.createdAt, 1000);
      expect(rows.single.updatedAt, 5000);
      expect(rows.single.answerText, 'Still LA.');
      expect(rows.single.titleText, 'Where now');
    });

    test('should_reportKeyMissing_when_askedWithoutService', () async {
      settings = const AiSettings();
      final r = await repo().askAboutSong(title: 'Blue', artist: 'Joni', album: 'Blue', question: 'Why?');
      expect(r, isA<AskKeyMissing>());
    });

    test('should_cleanTheHeading_when_titleGenerated', () async {
      client.reply = '"Where it was made."';
      final title = await repo().generateAskTitle(title: 'Blue', artist: 'Joni', album: 'Blue', question: 'Where?');
      expect(title, 'Where it was made');
    });
  });

  group('regenerate and edit', () {
    test('should_returnTheOldText_when_askRegenerated', () async {
      client.reply = '[TITLE]Old[/TITLE][ANSWER]Old answer.[/ANSWER]';
      final r = repo();
      await r.askAboutSong(title: 'Blue', artist: 'Joni', album: 'Blue', question: 'Why?');
      final row = (await store.forSong('blue', 'joni', 'blue')).single;
      client.reply = '[TITLE]New[/TITLE][ANSWER]New answer.[/ANSWER]';
      final result = await r.regenerate(row.ref);
      expect(result, isA<RegenerateSuccess>());
      expect((result as RegenerateSuccess).beforeAnswer, 'Old answer.');
      expect(result.beforeTitle, 'Old');
      expect((await store.get(row.ref))!.answerText, 'New answer.');
      // Undo: the old text back.
      await r.edit(row.ref, title: result.beforeTitle, answer: result.beforeAnswer);
      expect((await store.get(row.ref))!.titleText, 'Old');
    });

    test('should_writeOnlyThatField_when_canonicalRegenerated', () async {
      final r = repo();
      await r.ensureCanonicalAbout(title: 'Blue', artist: 'Joni', album: 'Blue');
      final rows = await store.forSong('blue', 'joni', 'blue');
      final lyricist = rows.firstWhere((e) => e.entryKey == AboutFields.lyricist);
      client.reply = _about.replaceAll('[LYRICIST]Joni Mitchell', '[LYRICIST]J. Mitchell').replaceAll('1971', '1970');
      await r.regenerate(lyricist.ref);
      final after = await store.forSong('blue', 'joni', 'blue');
      expect(after.firstWhere((e) => e.entryKey == AboutFields.lyricist).answerText, 'J. Mitchell');
      expect(after.firstWhere((e) => e.entryKey == AboutFields.creationTime).answerText, '1971');
    });

    test('should_neverRegenerate_when_rowIsTheListenersOwn', () async {
      final r = repo();
      await r.writeOwn(title: 'Blue', artist: 'Joni', album: 'Blue', heading: 'Mine', text: 'My words');
      final own = (await store.forSong('blue', 'joni', 'blue')).single;
      expect(await r.regenerate(own.ref), isA<RegenerateNothingNew>());
      expect(client.prompts, isEmpty);
    });

    test('should_keepABlankRow_when_answerRemoved', () async {
      client.reply = '[TITLE]T[/TITLE][ANSWER]A.[/ANSWER]';
      final r = repo();
      await r.askAboutSong(title: 'Blue', artist: 'Joni', album: 'Blue', question: 'Q?');
      final row = (await store.forSong('blue', 'joni', 'blue')).single;
      await r.edit(row.ref, answer: '   ');
      final kept = await store.get(row.ref);
      expect(kept, isNotNull);
      expect(kept!.answerText, '');
      expect(aboutRowsForDisplay([kept]), isEmpty);
    });

    test('should_keepTheHeading_when_ownEntryEditedWithBlankTitle', () async {
      final r = repo();
      await r.writeOwn(title: 'Blue', artist: 'Joni', album: 'Blue', heading: 'Mine', text: 'Words');
      final row = (await store.forSong('blue', 'joni', 'blue')).single;
      await r.edit(row.ref, title: '  ', answer: 'New words');
      final after = (await store.get(row.ref))!;
      expect(after.titleText, 'Mine');
      expect(after.answerText, 'New words');
    });
  });

  group('own entries', () {
    test('should_replaceText_when_sameHeadingWrittenAgain', () async {
      final r = repo();
      await r.writeOwn(title: 'Blue', artist: 'Joni', album: 'Blue', heading: 'Story', text: 'One');
      now = 2000;
      await r.writeOwn(title: 'Blue', artist: 'Joni', album: 'Blue', heading: ' story ', text: 'Two');
      final rows = await store.forSong('blue', 'joni', 'blue');
      expect(rows, hasLength(1));
      expect(rows.single.answerText, 'Two');
      expect(rows.single.createdAt, 1000);
    });

    test('should_takeANewKey_when_renamedEntrysOldHeadingWrittenAgain', () async {
      final r = repo();
      await r.writeOwn(title: 'Blue', artist: 'Joni', album: 'Blue', heading: 'Story', text: 'One');
      final first = (await store.forSong('blue', 'joni', 'blue')).single;
      await r.edit(first.ref, title: 'Renamed', answer: 'One');
      await r.writeOwn(title: 'Blue', artist: 'Joni', album: 'Blue', heading: 'Story', text: 'Two');
      final rows = await store.forSong('blue', 'joni', 'blue');
      expect({for (final e in rows) e.entryKey}, {'story', 'story #2'});
    });

    test('should_writeNothing_when_headingOrTextBlank', () async {
      await repo().writeOwn(title: 'Blue', artist: 'Joni', album: 'Blue', heading: ' ', text: 'x');
      expect(await store.all(), isEmpty);
    });

    test('should_keepOwnEntries_when_generatedRowsCleared', () async {
      final r = repo();
      await r.ensureCanonicalAbout(title: 'Blue', artist: 'Joni', album: 'Blue');
      await r.writeOwn(title: 'Blue', artist: 'Joni', album: 'Blue', heading: 'Mine', text: 'Words');
      await store.deleteGenerated();
      final rows = await store.all();
      expect(rows, hasLength(1));
      expect(rows.single.kind, SongAboutKinds.own);
    });
  });

  test('should_emitAgain_when_rowWritten', () async {
    final r = repo();
    final seen = <int>[];
    final sub = r.watch('Blue', 'Joni', 'Blue').listen((rows) => seen.add(rows.length));
    await Future<void>.delayed(Duration.zero);
    await r.writeOwn(title: 'Blue', artist: 'Joni', album: 'Blue', heading: 'Mine', text: 'Words');
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    await sub.cancel();
    expect(seen.first, 0);
    expect(seen.last, 1);
  });

  test('should_readTheSameRows_when_storeReopened', () async {
    await repo().writeOwn(title: 'Blue', artist: 'Joni', album: 'Blue', heading: 'Mine', text: 'Words');
    final again = SongAboutStore(kv);
    expect((await again.all()).single.answerText, 'Words');
  });
}
