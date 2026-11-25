import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/about/album_about_store.dart';
import 'package:yoin/data/ai/ai_client.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/albumabout/album_about_models.dart';
import 'package:yoin/data/albumabout/album_credits.dart';
import 'package:yoin/data/albumabout/yoin_album_about.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/detail/album_about_sheet.dart';
import 'package:yoin/detail/album_scrapbook.dart';
import 'package:yoin/detail/album_scrapbook_editor.dart';
import 'package:yoin/detail/album_scrapbook_page.dart';
import 'package:yoin/detail/album_scrapbook_sheet_model.dart';
import 'package:yoin/detail/detail_cache.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/detail/detail_strings.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';

import '../support/fake_ai_client.dart';
import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

final _album = Album(
  id: MediaId.subsonic('al1'),
  name: 'Blue',
  artist: 'Joni Mitchell',
  coverArt: const CoverSourceRelative('al1'),
  releaseDate: '1971-06-22',
  recordLabels: const ['Reprise'],
  tracks: [for (var i = 1; i <= 4; i++) LibraryFixtures.track('t$i', artist: 'Joni Mitchell', number: i)],
);

const _now = 1763200000000;

const _blurb =
    'Blue is the fourth studio album by Joni Mitchell, recorded at A&M Studios in Los Angeles in 1971. '
    'Spare and confessional, it set piano, guitar and Appalachian dulcimer around songs written after '
    'a long journey through Europe. It became one of the defining records of the singer-songwriter era, '
    'and its candour shaped the generations that followed. Mitchell later said she had no defences when she '
    'made it.';

const _reply =
    '[CREDITS]\n{"producers": {"names": ["Joni Mitchell"], "confidence": "high"}, '
    '"recorded": {"time": "1971", "studios": ["A&M Studios, Los Angeles"], "confidence": "high"}}\n[/CREDITS]\n'
    '[ABOUT]\n$_blurb\n[/ABOUT]';

AlbumAboutEntry _row(String kind, String key, String answer, {String? prompt, String? title, int at = 1}) =>
    AlbumAboutEntry(
      provider: 'subsonic',
      albumId: 'al1',
      albumDisplay: 'Blue',
      artistDisplay: 'Joni Mitchell',
      kind: kind,
      entryKey: key,
      promptText: prompt,
      titleText: title,
      answerText: answer,
      createdAt: at,
      updatedAt: at,
    );

List<AlbumAboutEntry> _generated({String blurb = _blurb, AlbumAiCredits? credits}) => [
  _row(
    'canonical',
    'credits',
    (credits ??
            const AlbumAiCredits(
              producers: ['Joni Mitchell'],
              recordedTime: '1971',
              recordedStudios: ['A&M Studios, Los Angeles'],
            ))
        .encode(),
  ),
  _row('canonical', 'blurb', blurb),
];

AlbumAboutEntry _ask(String q, String answer, {int at = 5}) =>
    _row('ask', YoinAlbumAbout.questionKey(q), answer, prompt: q, title: 'Why blue', at: at);

AlbumAboutContent _content(List<AlbumAboutEntry> rows) => YoinAlbumAbout.content([for (final r in rows) r.row]);

void main() {
  group('builder', () {
    List<ScrapPiece> build(AlbumScrapbookData data, {Album? album}) =>
        buildAlbumScrapbook(album ?? _album, data, nowMs: _now);

    test('should_leaveNoAboutPiece_when_neverAskedAndNothingPending', () {
      final pieces = build(const AlbumScrapbookData());
      expect(pieces.whereType<ScrapAlbumAbout>(), isEmpty);
      // The service's label alone makes the credits line (no AI).
      final opening = pieces.first as ScrapOpening;
      expect(opening.credits, [
        const AlbumCreditItem(AlbumCreditKind.label, names: ['Reprise']),
      ]);
    });

    test('should_putTheParagraphRightAfterTheOpening_when_generated', () {
      final pieces = build(AlbumScrapbookData(albumAbout: _content(_generated())));
      expect(pieces[1], isA<ScrapAlbumAbout>());
      final about = pieces[1] as ScrapAlbumAbout;
      expect(about.text, _blurb);
      expect(about.pending, isFalse);
      expect(about.fullLine, isTrue);
      final credits = (pieces.first as ScrapOpening).credits;
      expect(
        [for (final c in credits) c.kind],
        [AlbumCreditKind.label, AlbumCreditKind.producer, AlbumCreditKind.recorded],
      );
    });

    test('should_showPendingCard_when_theAiIsWritingItTheFirstTime', () {
      final about = build(const AlbumScrapbookData(albumAboutPending: true)).whereType<ScrapAlbumAbout>().single;
      expect(about.text, isNull);
      expect(about.pending, isTrue);
    });

    test('should_keepTheOldParagraphOnTheCard_when_regenerating', () {
      final about = build(
        AlbumScrapbookData(albumAbout: _content(_generated()), albumAboutPending: true),
      ).whereType<ScrapAlbumAbout>().single;
      expect(about.text, _blurb);
      expect(about.pending, isFalse);
    });

    test('should_keepAQuietPiece_when_theAiKnewNothing', () {
      final about = build(
        AlbumScrapbookData(
          albumAbout: _content(_generated(blurb: '', credits: AlbumAiCredits.none)),
        ),
      ).whereType<ScrapAlbumAbout>().single;
      expect(about.text, isNull);
      expect(about.pending, isFalse);
      expect(about.asks, isEmpty);
    });

    test('should_keepThePieceWithItsQuestions_when_onlyQuestionsWereAsked', () {
      final about = build(
        AlbumScrapbookData(albumAbout: _content([_ask('Why blue?', 'Because.', at: 1), _ask('Who?', 'Joni.', at: 9)])),
      ).whereType<ScrapAlbumAbout>().single;
      expect(about.text, isNull);
      expect([for (final a in about.asks) a.question], ['Who?', 'Why blue?']);
    });

    test('should_countAlbumQuestionsInAsked_when_receiptPrinted', () {
      final pieces = build(
        AlbumScrapbookData(
          plays: AlbumPlays(albumId: _album.id, playCount: 2, firstPlayedAt: _now - 1, lastPlayedAt: _now),
          albumAbout: _content([..._generated(), _ask('Why blue?', 'Because.'), _ask('Who?', 'Joni.')]),
        ),
      );
      final receipt = pieces.whereType<ScrapReceipt>().single;
      expect(receipt.lines.firstWhere((l) => l.$1 == 'Asked').$2, '2');
    });

    test('should_writeTheCreditsLine_when_labelsGivenInEachLanguage', () {
      final items = (build(AlbumScrapbookData(albumAbout: _content(_generated()))).first as ScrapOpening).credits;
      expect(
        albumCreditsLineFor(items, DetailStrings.en)!.plain,
        'Label Reprise · Producer Joni Mitchell · Recorded 1971, A&M Studios, Los Angeles',
      );
      final zh = albumCreditsLineFor(items, DetailStrings.zhHans)!;
      expect(zh.plain, '厂牌 Reprise · 制作 Joni Mitchell · 录制 1971，A&M Studios, Los Angeles');
      expect(albumCreditsLineFor(const [], DetailStrings.en), isNull);
    });

    test('should_writeTheServiceDatedFirstReleaseInItsOwnWords_when_reissue', () {
      const strings = DetailStrings.en;
      expect(strings.releaseDateText('2014-05-20'), 'May 20, 2014');
      expect(strings.releaseDateText('2014-05'), 'May 2014');
      expect(strings.releaseDateText('2014'), '2014');
      expect(DetailStrings.zhHans.releaseDateText('2014-05-20'), '2014年5月20日');
      final reissue = Album(
        id: MediaId.subsonic('al2'),
        name: 'Blue (Remastered)',
        releaseDate: '2022',
        originalReleaseDate: '1971-06-22',
      );
      final items = (build(const AlbumScrapbookData(), album: reissue).first as ScrapOpening).credits;
      expect(albumCreditsLineFor(items, strings)!.plain, 'First released June 22, 1971');
    });
  });

  group('ai copy', () {
    test('should_nameTheServiceInUse_when_copyFilledIn', () {
      const en = DetailStrings.en;
      final zh = DetailStrings.zhHans;
      expect(en.aiAskProvider(AiProvider.gemini), 'Ask Gemini');
      expect(en.aiAskProvider(AiProvider.qwen), 'Ask Qwen');
      expect(zh.aiAskProvider(AiProvider.qwen), '问千问');
      expect(zh.aiAskProvider(AiProvider.deepSeek), '问 DeepSeek');
      expect(en.aiGenerateWith(AiProvider.custom), 'Generate with AI');
      expect(zh.aiNothingNew(AiProvider.qwen), '千问没有新的内容');
      expect(en.aiFailed(AiProvider.deepSeek), "DeepSeek couldn't answer. Try again.");
      expect(zh.aiKeyMissing(AiProvider.gemini), '缺少 Gemini API 密钥');
    });
  });

  group('editor', () {
    late InMemoryStore kv;
    late InMemoryStore secrets;
    late FakeAiClient client;

    Future<ProviderContainer> container({bool ready = true}) async {
      kv = InMemoryStore();
      secrets = InMemoryStore();
      if (ready) {
        await AiSettingsStore(kv, secrets).write(const AiSettings(provider: AiProvider.qwen, qwenKey: 'q'));
      }
      final c = ProviderContainer(
        overrides: [
          activeSourceProvider.overrideWith((ref) async => LibraryFakeSource(albumDetails: {_album.id: _album})),
          keyValueStoreProvider.overrideWithValue(kv),
          secretStoreProvider.overrideWithValue(secrets),
          activityLogProvider.overrideWith((ref) async => null),
          memoryStoreProvider.overrideWith((ref) async => MemoryStore(InMemoryStore(), profileId: 'p')),
          aiClientProvider.overrideWithValue(client),
        ],
      );
      addTearDown(c.dispose);
      c.listen(albumScrapbookEditorProvider(_album.id), (_, _) {});
      await c.read(detailCacheProvider).album(_album.id);
      return c;
    }

    AlbumAboutStore store() => AlbumAboutStore.shared(kv);

    Future<void> idle() => Future<void>.delayed(const Duration(milliseconds: 20));

    setUp(() => client = FakeAiClient(reply: _reply));

    test('should_writeTheAboutOnce_when_thePageSettlesTwice', () async {
      final c = await container();
      final editor = c.read(albumScrapbookEditorProvider(_album.id).notifier);
      editor.edit(const ScrapEnsureAlbumAboutEdit());
      editor.edit(const ScrapEnsureAlbumAboutEdit());
      await idle();
      expect(client.prompts, hasLength(1));
      expect(await store().canonical('subsonic', 'al1'), hasLength(2));
      expect(c.read(albumScrapbookEditorProvider(_album.id)).albumAbout, isFalse);
    });

    test('should_askNothingAndShowNoDots_when_noKey', () async {
      final c = await container(ready: false);
      final states = <bool>[];
      c.listen(albumScrapbookEditorProvider(_album.id), (_, next) => states.add(next.albumAbout));
      c.read(albumScrapbookEditorProvider(_album.id).notifier).edit(const ScrapEnsureAlbumAboutEdit());
      await idle();
      expect(client.prompts, isEmpty);
      expect(states, isNot(contains(true)));
    });

    test('should_askNothing_when_alreadyWrittenOrSyncedIn', () async {
      final c = await container();
      await store().upsertAll(_generated());
      c.read(albumScrapbookEditorProvider(_album.id).notifier).edit(const ScrapEnsureAlbumAboutEdit());
      await idle();
      expect(client.prompts, isEmpty);
    });

    test('should_storeNothingAndStayQuiet_when_theCallFails', () async {
      final c = await container();
      client.error = const AiException('down');
      final events = <ScrapEvent>[];
      c.read(albumScrapbookEditorProvider(_album.id).notifier).events.listen(events.add);
      c.read(albumScrapbookEditorProvider(_album.id).notifier).edit(const ScrapEnsureAlbumAboutEdit());
      await idle();
      expect(await store().all(), isEmpty);
      expect(events, isEmpty);
      expect(c.read(albumScrapbookEditorProvider(_album.id)).albumAbout, isFalse);
    });

    test('should_offerUndo_when_regenerated', () async {
      final c = await container();
      await store().upsertAll(_generated(blurb: 'Old words.'));
      final editor = c.read(albumScrapbookEditorProvider(_album.id).notifier);
      final events = <ScrapEvent>[];
      editor.events.listen(events.add);
      editor.edit(const ScrapRegenerateAlbumAboutEdit());
      expect(c.read(albumScrapbookEditorProvider(_album.id)).albumAbout, isTrue);
      await idle();
      expect(c.read(albumScrapbookEditorProvider(_album.id)).albumAbout, isFalse);
      final blurb = (await store().canonical('subsonic', 'al1')).firstWhere((r) => r.entryKey == 'blurb');
      expect(blurb.answerText, _blurb);
      final undo = (events.single as ScrapRegenerated).restore;
      expect(undo, isA<ScrapRestoreAlbumAboutEdit>());
      editor.edit(undo);
      await idle();
      final back = (await store().canonical('subsonic', 'al1')).firstWhere((r) => r.entryKey == 'blurb');
      expect(back.answerText, 'Old words.');
    });

    test('should_sayNothingNew_when_regenerateComesBackEmpty', () async {
      final c = await container();
      await store().upsertAll(_generated(blurb: 'Old words.'));
      client.reply = '[CREDITS]{}[/CREDITS][ABOUT]N/A[/ABOUT]';
      final editor = c.read(albumScrapbookEditorProvider(_album.id).notifier);
      final events = <ScrapEvent>[];
      editor.events.listen(events.add);
      editor.edit(const ScrapRegenerateAlbumAboutEdit());
      await idle();
      expect((events.single as ScrapAlbumAboutFailed).failure, ScrapFailure.nothingNew);
      expect(
        (await store().canonical('subsonic', 'al1')).firstWhere((r) => r.entryKey == 'blurb').answerText,
        'Old words.',
      );
    });

    test('should_holdTheQuestionPendingThenStoreIt_when_askedAboutTheAlbum', () async {
      final gate = Completer<void>();
      client = _GatedAiClient(gate, '[TITLE]Why blue[/TITLE][ANSWER]Because.[/ANSWER]');
      final c = await container();
      final editor = c.read(albumScrapbookEditorProvider(_album.id).notifier);
      editor.edit(const ScrapAskAlbumEdit('  Why is it called Blue? '));
      await idle();
      expect(c.read(albumScrapbookEditorProvider(_album.id)).albumAsking, ['Why is it called Blue?']);
      gate.complete();
      await idle();
      expect(c.read(albumScrapbookEditorProvider(_album.id)).albumAsking, isEmpty);
      final asks = [
        for (final r in await store().all())
          if (r.kind == 'ask') r,
      ];
      expect(asks.single.answerText, 'Because.');
    });
  });

  group('page', () {
    Future<ProviderContainer> open(
      WidgetTester tester, {
      List<AlbumAboutEntry> rows = const [],
      Locale locale = const Locale('en'),
    }) async {
      final kv = InMemoryStore();
      await AlbumAboutStore(kv).upsertAll(rows);
      final c = ProviderContainer(
        overrides: [
          activeSourceProvider.overrideWith((ref) async => LibraryFakeSource(albumDetails: {_album.id: _album})),
          keyValueStoreProvider.overrideWithValue(kv),
          secretStoreProvider.overrideWithValue(InMemoryStore()),
          activityLogProvider.overrideWith((ref) async => null),
          memoryStoreProvider.overrideWith((ref) async => MemoryStore(InMemoryStore(), profileId: 'p')),
          playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
          aiClientProvider.overrideWithValue(FakeAiClient(reply: _reply)),
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
      await tester.tap(find.bySemanticsLabel('Scrapbook'));
      await settle(tester);
      return c;
    }

    testWidgets('should_showTheCreditsLineAndTheCard_when_generated', (tester) async {
      await open(tester, rows: _generated());
      expect(find.textContaining(RegExp('Label Reprise')), findsOneWidget);
      expect(find.text(_blurb), findsOneWidget);
      // A paragraph longer than four lines on a phone column: Read all.
      expect(find.text('Read all'), findsOneWidget);
      expect(find.byKey(const ValueKey('albumAboutEmpty')), findsNothing);
      await close(tester);
    });

    testWidgets('should_showTheDashedSparkle_when_theAiKnewNothing', (tester) async {
      await open(
        tester,
        rows: _generated(blurb: '', credits: AlbumAiCredits.none),
      );
      expect(find.byKey(const ValueKey('albumAboutEmpty')), findsOneWidget);
      // Only the service's label on the line.
      expect(find.textContaining(RegExp('Label Reprise')), findsOneWidget);
      // The circle opens the sheet, which offers to generate and to ask.
      await tester.tap(find.byKey(const ValueKey('albumAboutEmpty')));
      await settle(tester);
      expect(find.byType(AlbumAboutSheet), findsOneWidget);
      expect(find.text('Generate with Gemini'), findsOneWidget);
      expect(find.text('Ask Gemini'), findsWidgets);
      await close(tester);
    });

    testWidgets('should_showTheNewestQuestion_when_onlyQuestionsWereAsked', (tester) async {
      await open(
        tester,
        rows: [_ask('Why is it called Blue?', 'Because.', at: 1), _ask('Who plays dulcimer?', 'Joni.', at: 9)],
      );
      expect(find.byKey(const ValueKey('albumAboutEmpty')), findsNothing);
      expect(find.text('Who plays dulcimer?'), findsOneWidget);
      expect(find.text('Why is it called Blue?'), findsNothing);
      expect(find.text('+1'), findsOneWidget);
      await close(tester);
    });

    testWidgets('should_openTheSheetWithReceiptQuestionsAndAsk_when_cardTapped', (tester) async {
      await open(tester, rows: [..._generated(), _ask('Why is it called Blue?', 'Because.')]);
      await tester.tap(find.text(_blurb));
      await settle(tester);
      final sheet = find.byType(AlbumAboutSheet);
      expect(sheet, findsOneWidget);
      Finder inSheet(Finder f) => find.descendant(of: sheet, matching: f);
      // The receipt: label left, value right.
      expect(inSheet(find.text('Label')), findsOneWidget);
      expect(inSheet(find.text('Reprise')), findsOneWidget);
      expect(inSheet(find.text('Producer')), findsOneWidget);
      expect(inSheet(find.text('1971, A&M Studios, Los Angeles')), findsOneWidget);
      expect(inSheet(find.text(_blurb)), findsOneWidget);
      expect(inSheet(find.text('Regenerate')), findsOneWidget);
      expect(inSheet(find.text('Why is it called Blue?')), findsOneWidget);
      expect(inSheet(find.text('Because.')), findsOneWidget);
      await close(tester);
    });

    testWidgets('should_regenerateWithUndoInTheSheet_when_regenerateTapped', (tester) async {
      final c = await open(tester, rows: _generated(blurb: 'Old words.'));
      // A key, so Regenerate reaches the (fake) AI.
      await AiSettingsStore(
        c.read(keyValueStoreProvider),
        c.read(secretStoreProvider),
      ).write(const AiSettings(provider: AiProvider.qwen, qwenKey: 'q'));
      c.invalidate(aiSettingsProvider);
      await tester.tap(find.text('Old words.'));
      await settle(tester);
      await tester.tap(find.text('Regenerate'));
      await settle(tester);
      final sheet = find.byType(AlbumAboutSheet);
      expect(find.descendant(of: sheet, matching: find.text(_blurb)), findsOneWidget);
      expect(find.text('Regenerated'), findsOneWidget);
      // The byline and the field name the service in use.
      expect(find.descendant(of: sheet, matching: find.text('Qwen')), findsOneWidget);
      expect(find.descendant(of: sheet, matching: find.text('Ask Qwen')), findsOneWidget);
      await tester.tap(find.text('Undo'));
      await settle(tester);
      expect(find.descendant(of: sheet, matching: find.text('Old words.')), findsOneWidget);
      await close(tester);
    });

    testWidgets('should_sendTheQuestion_when_askedFromTheSheet', (tester) async {
      final c = await open(tester, rows: _generated());
      await AiSettingsStore(
        c.read(keyValueStoreProvider),
        c.read(secretStoreProvider),
      ).write(const AiSettings(provider: AiProvider.qwen, qwenKey: 'q'));
      c.invalidate(aiSettingsProvider);
      await tester.tap(find.text(_blurb));
      await settle(tester);
      final send = find.byKey(const ValueKey('albumAboutAskSend'));
      expect(tester.widget<IconButton>(send).onPressed, isNull);
      await tester.enterText(find.byKey(const ValueKey('albumAboutAskField')), 'Who plays dulcimer?');
      await tester.pump();
      expect(tester.widget<IconButton>(send).onPressed, isNotNull);
      await tester.ensureVisible(send);
      await tester.pump();
      await tester.tap(send);
      await settle(tester);
      final asks = [
        for (final r in await AlbumAboutStore.shared(c.read(keyValueStoreProvider)).all())
          if (r.kind == 'ask') r,
      ];
      expect(asks.single.promptText, 'Who plays dulcimer?');
      await close(tester);
    });
  });
}

/// An AI client that answers only once [gate] completes.
class _GatedAiClient extends FakeAiClient {
  _GatedAiClient(this.gate, String reply) : super(reply: reply);
  final Completer<void> gate;

  @override
  Future<String> generate(
    AiSettings ai,
    String prompt, {
    required double temperature,
    required bool useSearch,
    Duration? timeout,
  }) async {
    await gate.future;
    return super.generate(ai, prompt, temperature: temperature, useSearch: useSearch, timeout: timeout);
  }
}

Future<void> settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> close(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(milliseconds: 1));
}
