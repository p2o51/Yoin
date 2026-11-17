import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/about/song_about_store.dart';
import 'package:yoin/data/ai/ai_client.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/ai_settings_screen.dart';
import 'package:yoin/settings/ai_settings_view_model.dart';
import 'package:yoin/settings/ai_sub_pages.dart';
import 'package:yoin/ui/component/yoin_loading_indicator.dart';

import '../support/fake_ai_client.dart';

/// Settings › AI features (Android AiSettingsPage, AiSubPages): the service,
/// tier, keys, Custom's endpoint, model, thinking and search are kept as they
/// change; the list row says what is in use; the tier page and 「拿一首歌试试」.
void main() {
  late InMemoryStore plain;
  late InMemoryStore secrets;
  late FakeAiClient ai;
  late List<Uri> opened;

  Future<ProviderContainer> pump(
    WidgetTester tester,
    Widget child, {
    List<AiRecentSong> songs = const [],
    Future<List<AiRecentSong>> Function()? loadSongs,
    Locale? locale,
  }) async {
    tester.view.physicalSize = const Size(420, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(plain),
          secretStoreProvider.overrideWithValue(secrets),
          aiClientProvider.overrideWithValue(ai),
          aiRecentSongsProvider.overrideWith((ref) => loadSongs?.call() ?? Future.value(songs)),
          aiLinkOpenerProvider.overrideWithValue((url) async {
            opened.add(url);
            return true;
          }),
        ],
        child: MaterialApp(
          locale: locale,
          supportedLocales: const [Locale('en'), Locale('zh')],
          localizationsDelegates: GlobalMaterialLocalizations.delegates,
          home: Material(child: child),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return ProviderScope.containerOf(tester.element(find.byWidget(child)));
  }

  setUp(() {
    plain = InMemoryStore();
    secrets = InMemoryStore();
    ai = FakeAiClient();
    opened = [];
  });

  Map<String, Object?> prefs() => jsonDecode(plain.values[AiSettingsStore.prefsKey]!) as Map<String, Object?>;

  Finder field(String key) => find.descendant(of: find.byKey(ValueKey(key)), matching: find.byType(EditableText));

  Future<void> tap(WidgetTester tester, String key) async {
    await tester.ensureVisible(find.byKey(ValueKey(key)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(ValueKey(key)));
    await tester.pumpAndSettle();
  }

  /// Opens the value row [key] and picks [option] from its menu.
  Future<void> pick(WidgetTester tester, String key, String option) async {
    await tap(tester, key);
    await tester.tap(find.text(option).last);
    await tester.pumpAndSettle();
  }

  /// The text a value row shows on its right.
  Finder rowValue(String key, String value) =>
      find.descendant(of: find.byKey(ValueKey(key)), matching: find.text(value));

  testWidgets('should_keepTierKeyAndLanguage_when_changed', (tester) async {
    await pump(tester, const AiSettingsScreen());
    expect(find.text('AI features'), findsWidgets);
    expect(find.text('Answer language'), findsOneWidget);
    expect(rowValue('ai-answer-language', 'English'), findsOneWidget);

    await tap(tester, 'ai-tier-best');
    expect(prefs()['tier'], 'best');

    await tester.enterText(field('ai-key-gemini'), ' AIza-key ');
    await tester.pumpAndSettle();
    expect(secrets.values[AiSettingsStore.geminiKeyKey], 'AIza-key');
    expect(plain.values.values.any((v) => v.contains('AIza')), isFalse, reason: 'the key never leaves the secrets');

    await pick(tester, 'ai-answer-language', 'Japanese');
    expect(rowValue('ai-answer-language', 'Japanese'), findsOneWidget);
    expect(prefs()['targetLanguage'], 'Japanese');
  });

  testWidgets('should_clearAiAboutButKeepOwn_when_answerLanguageChanged', (tester) async {
    SongAboutEntry row(String kind, String key) => SongAboutEntry(
      titleKey: 'blue',
      artistKey: 'joni',
      albumKey: 'blue',
      titleDisplay: 'Blue',
      artistDisplay: 'Joni',
      albumDisplay: 'Blue',
      kind: kind,
      entryKey: key,
      titleText: kind == SongAboutKinds.own ? 'Mine' : null,
      answerText: 'text',
      createdAt: 1,
      updatedAt: 1,
    );
    final about = SongAboutStore.shared(plain);
    await about.upsertAll([
      row(SongAboutKinds.canonical, 'review'),
      row(SongAboutKinds.ask, 'why'),
      row(SongAboutKinds.own, 'mine'),
    ]);
    await pump(tester, const AiSettingsScreen());

    await pick(tester, 'ai-answer-language', 'Japanese');

    final left = await about.all();
    expect(left.map((r) => r.kind), [SongAboutKinds.own]);
  });

  testWidgets('should_keepEachServicesKey_when_switchingServices', (tester) async {
    await pump(tester, const AiSettingsScreen());
    await tester.enterText(field('ai-key-gemini'), 'AIza-g');
    await tap(tester, 'ai-provider-deepSeek');
    expect(prefs()['provider'], 'deepSeek');
    expect(find.text('Get a key on the DeepSeek platform'), findsOneWidget);
    await tester.enterText(field('ai-key-deepSeek'), 'sk-d');
    await tester.pumpAndSettle();
    expect(secrets.values[AiSettingsStore.deepSeekKeyKey], 'sk-d');
    expect(secrets.values[AiSettingsStore.geminiKeyKey], 'AIza-g');

    await tap(tester, 'ai-key-link');
    expect(opened.single.toString(), 'https://platform.deepseek.com/api_keys');

    await tap(tester, 'ai-provider-gemini');
    expect(tester.widget<EditableText>(field('ai-key-gemini')).controller.text, 'AIza-g');
  });

  testWidgets('should_showEndpointModelThinkingAndSearch_when_customIsChosen', (tester) async {
    await pump(tester, const AiSettingsScreen());
    expect(find.byKey(const ValueKey('ai-custom-url')), findsNothing);
    await tap(tester, 'ai-provider-custom');
    expect(find.byKey(const ValueKey('ai-tier-best')), findsNothing, reason: 'Custom has no tiers');
    expect(find.text('Thinking'), findsNothing, reason: 'thinking waits for a model');

    await tap(tester, 'ai-format-anthropic');
    expect(prefs()['customFormat'], 'anthropic');
    await tester.enterText(field('ai-custom-url'), 'https://gw.example.com/ ');
    await tester.enterText(field('ai-key-custom'), 'sk-c');
    await tester.enterText(field('ai-custom-model'), 'claude-x');
    await tester.pumpAndSettle();
    expect(prefs()['customBaseUrl'], 'https://gw.example.com/');
    expect(prefs()['customModel'], 'claude-x');
    expect(secrets.values[AiSettingsStore.customKeyKey], 'sk-c');
    expect(find.text('Thinking'), findsOneWidget);
    await tap(tester, 'ai-thinking-high');
    expect(prefs()['customThinking'], 'high');

    // Each search service keeps its own key; 「不搜索」 folds the field away.
    expect(find.text('DeepSeek key'), findsOneWidget);
    expect(rowValue('ai-search-service', 'DeepSeek'), findsOneWidget);
    await pick(tester, 'ai-search-service', 'Exa');
    expect(rowValue('ai-search-service', 'Exa'), findsOneWidget);
    expect(find.text('Exa key'), findsOneWidget);
    await tester.enterText(field('ai-search-key-exa'), 'exa-k');
    await tester.pumpAndSettle();
    expect(secrets.values[AiSettingsStore.searchExaKeyKey], 'exa-k');
    expect(secrets.values[AiSettingsStore.searchDeepSeekKeyKey], isNull);
    expect(find.text('Get an Exa key'), findsOneWidget, reason: "English's article follows the name");
    await tap(tester, 'ai-search-link');
    expect(opened.single.toString(), 'https://dashboard.exa.ai/api-keys');
    await pick(tester, 'ai-search-service', 'No search');
    expect(prefs()['searchService'], 'none');
    expect(find.byKey(const ValueKey('ai-search-key-exa')), findsNothing);
  });

  testWidgets('should_listEverySearchServiceInTheMenu_when_theSearchRowIsTapped', (tester) async {
    await pump(tester, const AiSettingsScreen());
    await tap(tester, 'ai-provider-custom');
    await tap(tester, 'ai-search-service');
    for (final name in ['DeepSeek', 'Exa', 'Tavily', 'Brave', 'Doubao', 'No search']) {
      expect(find.text(name), findsWidgets, reason: name);
    }
    await tester.tap(find.text('Brave').last);
    await tester.pumpAndSettle();
    expect(prefs()['searchService'], 'brave');
    expect(find.text('Brave key'), findsOneWidget);
    await tester.enterText(field('ai-search-key-brave'), 'br-key');
    await tester.pumpAndSettle();
    await tap(tester, 'ai-search-link');
    expect(opened.last.toString(), 'https://api-dashboard.search.brave.com/app/keys');

    await pick(tester, 'ai-search-service', 'Doubao');
    expect(find.text('Doubao key'), findsOneWidget);
    expect(find.text('Get a Doubao key'), findsOneWidget);
    await tester.enterText(field('ai-search-key-doubao'), 'db-key');
    await tester.pumpAndSettle();
    await tap(tester, 'ai-search-link');
    expect(opened.last.toString(), 'https://console.volcengine.com/search-infinity/api-key');
    expect(secrets.values[AiSettingsStore.searchBraveKeyKey], 'br-key');
    expect(secrets.values[AiSettingsStore.searchDoubaoKeyKey], 'db-key');
  });

  testWidgets('should_nameDoubaoInChinese_when_theAppUsesSimplifiedChinese', (tester) async {
    await pump(tester, const AiSettingsScreen(), locale: const Locale('zh'));
    await tap(tester, 'ai-provider-custom');
    await pick(tester, 'ai-search-service', '豆包');
    expect(rowValue('ai-search-service', '豆包'), findsOneWidget);
    expect(find.text('获取豆包密钥'), findsOneWidget);
    expect(find.text('回答语言'), findsOneWidget);
  });

  testWidgets('should_offerNoTierChoice_when_theServiceHasOneTier', (tester) async {
    await pump(tester, const AiSettingsScreen());
    await tap(tester, 'ai-tier-best');
    await tap(tester, 'ai-provider-qwen');
    expect(find.byKey(const ValueKey('ai-tier-best')), findsNothing, reason: 'Qwen has one tier');
    expect(find.byKey(const ValueKey('ai-tier-info')), findsNothing);
    expect(find.text('Get a key in Alibaba Cloud Model Studio'), findsOneWidget);
    expect(prefs()['tier'], 'best', reason: 'the choice is kept for the services with two');

    await tap(tester, 'ai-provider-deepSeek');
    expect(find.byKey(const ValueKey('ai-tier-best')), findsOneWidget);
    expect(tester.widget<AiTierCard>(find.byKey(const ValueKey('ai-tier-best'))).selected, isTrue);
  });

  testWidgets('should_chartTheSearchServicesWithANote_onlyWhileCustomIsChosen', (tester) async {
    await pump(tester, const AiSettingsScreen());
    final note = find.byKey(const ValueKey('ai-search-chart-note'));
    expect(note.hitTestable(), findsNothing);
    expect(find.byKey(const ValueKey('ai-legend-tiers')), findsOneWidget);

    await tap(tester, 'ai-provider-custom');
    expect(note.hitTestable(), findsOneWidget);
    expect(find.byKey(const ValueKey('ai-legend-search')), findsOneWidget);
    expect(find.byKey(const ValueKey('ai-legend-tiers')), findsNothing, reason: 'the old legend faded away');
    for (final name in ['Exa', 'Tavily', 'Brave', 'Doubao', 'No search']) {
      expect(
        find.descendant(of: find.byKey(const ValueKey('ai-legend-search')), matching: find.text(name)),
        findsOneWidget,
        reason: name,
      );
    }

    // 「换个角度」 steps the views on either set.
    await tap(tester, 'ai-flip');
    await tap(tester, 'ai-provider-gemini');
    expect(note.hitTestable(), findsNothing);
    expect(find.byKey(const ValueKey('ai-legend-search')), findsNothing);
  });

  testWidgets('should_listModelsToPick_when_fetchIsTapped', (tester) async {
    ai.models = ['model-a', 'model-b'];
    await pump(tester, const AiSettingsScreen());
    await tap(tester, 'ai-provider-custom');
    final pull = find.byKey(const ValueKey('ai-custom-pull'));
    expect(tester.widget<TextButton>(pull).onPressed, isNull, reason: 'needs the address and the key first');

    await tester.enterText(field('ai-custom-url'), 'https://gw.example.com/v1');
    await tester.enterText(field('ai-key-custom'), 'sk-c');
    await tester.pumpAndSettle();
    await tap(tester, 'ai-custom-pull');
    expect(find.text('model-a'), findsOneWidget);
    expect(prefs()['customModel'], '', reason: 'fetching only lists them');

    await tap(tester, 'ai-model-model-b');
    expect(prefs()['customModel'], 'model-b');
    expect(tester.widget<EditableText>(field('ai-custom-model')).controller.text, 'model-b');

    ai.error = const AiException('Models error (401): bad key');
    await tap(tester, 'ai-custom-pull');
    expect(find.text("Couldn't fetch models: Models error (401): bad key"), findsOneWidget);
  });

  test('should_narrowLongListsByWhatIsTyped_when_offeringModels', () {
    final ids = [for (var i = 0; i < 40; i++) 'vendor/model-$i'];
    expect(modelChips(ids.take(5).toList(), 'x'), hasLength(5));
    expect(modelChips(ids, ''), hasLength(24));
    expect(modelChips(ids, 'MODEL-3'), ['vendor/model-3', for (var i = 30; i < 40; i++) 'vendor/model-$i']);
  });

  testWidgets('should_nameServiceAndTier_when_keyIsSet', (tester) async {
    final container = await pump(tester, const AiSettingsRow());
    expect(find.text('Song info, Ask Gemini, translation and Memory titles'), findsOneWidget);
    final notifier = container.read(aiSettingsProvider.notifier);
    await notifier.setGeminiKey('k');
    await notifier.setTier(AiTier.best);
    await tester.pumpAndSettle();
    expect(find.text('Gemini'), findsOneWidget);
    expect(find.text('Best'), findsOneWidget);

    // One tier: nothing beside the service.
    await notifier.setProvider(AiProvider.qwen);
    await notifier.setKey(AiProvider.qwen, 'q');
    await tester.pumpAndSettle();
    expect(find.text('Qwen'), findsOneWidget);
    expect(find.text('Good'), findsNothing);
    expect(find.text('Best'), findsNothing);

    await notifier.setProvider(AiProvider.custom);
    await notifier.setKey(AiProvider.custom, 'c');
    await notifier.setCustomBaseUrl('https://x');
    await notifier.setCustomModel('my-model');
    await tester.pumpAndSettle();
    expect(find.text('Custom'), findsOneWidget);
    expect(find.text('my-model'), findsOneWidget, reason: 'Custom names its model');
  });

  testWidgets('should_showEachTiersFacts_when_tierPageOpens', (tester) async {
    final container = await pump(tester, const AiTiersScreen());
    await container.read(aiSettingsProvider.notifier).setProvider(AiProvider.deepSeek);
    await tester.pumpAndSettle();
    expect(find.text('DeepSeek tiers'), findsOneWidget);
    expect(find.text('DeepSeek Search once, full pages'), findsOneWidget);
    expect(find.text('DeepSeek Search twice, full pages'), findsOneWidget);
    expect(find.text('92%'), findsOneWidget);
    expect(find.text(r'$2.9'), findsOneWidget);
    // Best was not run on the 500 songs: every number is marked an estimate.
    expect(find.text('93% (est.)'), findsOneWidget);
    expect(find.text('4.4 s (est.)'), findsOneWidget);
    expect(find.text(r'$3.9 (est.)'), findsOneWidget);
    expect(find.textContaining('Works in mainland China'), findsOneWidget);
    expect(find.text('Speed and accuracy from a 500-song test; cost at current prices.'), findsOneWidget);

    await container.read(aiSettingsProvider.notifier).setProvider(AiProvider.qwen);
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('ai-facts-good')), findsOneWidget);
    expect(find.byKey(const ValueKey('ai-facts-best')), findsNothing, reason: 'Qwen has one tier');
    expect(find.text('qwen-flash'), findsOneWidget);
    expect(find.text('83%'), findsOneWidget);
    expect(find.text('—'), findsNothing, reason: 'Qwen was measured');
  });

  final lemon = AiRecentSong(
    key: MediaId.subsonic('1'),
    song: const AiTrySong('Lemon', 'Kenshi Yonezu', 'STRAY SHEEP'),
  );
  final plastic = AiRecentSong(
    key: MediaId.subsonic('2'),
    song: const AiTrySong('Plastic Love', 'Mariya Takeuchi', 'Variety'),
  );

  testWidgets('should_sayToPlayASongFirst_when_thereIsNoPlayHistory', (tester) async {
    await pump(tester, const AiTryScreen());
    expect(find.text('Play a song first'), findsOneWidget);

    await pump(tester, const AiTryScreen(), locale: const Locale('zh'));
    expect(find.text('先播一首歌'), findsOneWidget);
  });

  testWidgets('should_askForKey_when_tryRunsWithoutOne', (tester) async {
    await pump(tester, const AiTryScreen(), songs: [lemon, plastic]);
    expect(find.text('Lemon'), findsOneWidget);
    expect(find.text('Plastic Love'), findsOneWidget);
    await tap(tester, 'ai-try-${lemon.key}');
    expect(find.text('Paste a key first'), findsOneWidget);
    expect(ai.prompts, isEmpty);
  });

  testWidgets('should_showAboutFields_when_aRecentSongIsTapped', (tester) async {
    ai.reply = '[LYRICIST]Someone[/LYRICIST][REVIEW]A song.[/REVIEW]';
    final container = await pump(tester, const AiTryScreen(), songs: [lemon, plastic]);
    await container.read(aiSettingsProvider.notifier).setGeminiKey('k');
    await tap(tester, 'ai-try-${plastic.key}');
    expect(ai.prompts.single, contains('Plastic Love'));
    expect(ai.prompts.single, contains('Mariya Takeuchi'));
    expect(find.text(AiSettings.geminiGood), findsOneWidget);
    expect(find.text('The model searched on its own'), findsOneWidget);
    expect(find.text('Someone'), findsOneWidget);
    expect(find.text('A song.'), findsOneWidget);
    expect(find.text('—'), findsNWidgets(4));

    ai.error = const AiException('Gemini error (429): quota');
    await tap(tester, 'ai-try-${lemon.key}');
    expect(find.text('Failed: Gemini error (429): quota'), findsOneWidget);
  });

  testWidgets('should_startNoSecondRun_when_aSongIsTappedWhileOneRuns', (tester) async {
    final gated = _GatedAiClient()..reply = '[REVIEW]First.[/REVIEW]';
    ai = gated;
    final container = await pump(tester, const AiTryScreen(), songs: [lemon, plastic]);
    await container.read(aiSettingsProvider.notifier).setGeminiKey('k');
    await tester.tap(find.byKey(ValueKey('ai-try-${lemon.key}')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
    expect(container.read(aiTryProvider), isA<AiTryRunning>());
    expect(find.text('Searching…'), findsOneWidget);
    expect(
      find.descendant(of: find.byKey(ValueKey('ai-try-${lemon.key}')), matching: find.byType(YoinLoadingIndicator)),
      findsOneWidget,
      reason: 'waiting shows on the song row',
    );

    // A tap on the row, or a second run asked of the notifier, starts nothing.
    await tester.tap(find.byKey(ValueKey('ai-try-${plastic.key}')));
    unawaited(container.read(aiTryProvider.notifier).run(plastic.song));
    await tester.pump();
    gated.gate.complete();
    await tester.pumpAndSettle();
    expect(gated.prompts, hasLength(1));
    expect((container.read(aiTryProvider) as AiTryDone).song, lemon.song);
    expect(find.text('First.'), findsOneWidget);
  });

  testWidgets('should_keepTheSongsOnScreen_when_aNewPlayReloadsThem', (tester) async {
    var loads = 0;
    final next = Completer<List<AiRecentSong>>();
    final container = await pump(
      tester,
      const AiTryScreen(),
      loadSongs: () => ++loads == 1 ? Future.value([lemon]) : next.future,
    );
    expect(find.text('Lemon'), findsOneWidget);

    container.invalidate(aiRecentSongsProvider);
    await tester.pump();
    expect(container.read(aiRecentSongsProvider).isLoading, isTrue);
    expect(find.text('Lemon'), findsOneWidget, reason: 'the list stays while the new play is read');

    next.complete([plastic, lemon]);
    await tester.pumpAndSettle();
    expect(find.text('Plastic Love'), findsOneWidget);
    expect(find.text('Lemon'), findsOneWidget);
  });

  test('should_bringANewPlayToTheTop_when_theAccountPlaysASong', () async {
    final store = MemoryStore(InMemoryStore(), profileId: 'p');
    final container = ProviderContainer(overrides: [memoryStoreProvider.overrideWith((ref) async => store)]);
    addTearDown(container.dispose);
    final sub = container.listen(aiRecentSongsProvider, (_, _) {});
    addTearDown(sub.close);
    await store.recordTrackPlay(Track(id: MediaId.subsonic('1'), title: 'Lemon', artist: 'Kenshi Yonezu'), atMs: 10);
    expect((await container.read(aiRecentSongsProvider.future)).map((s) => s.song.title), ['Lemon']);

    await store.recordTrackPlay(Track(id: MediaId.subsonic('2'), title: 'Plastic Love'), atMs: 20);
    await pumpEventQueue();
    expect((await container.read(aiRecentSongsProvider.future)).map((s) => s.song.title), ['Plastic Love', 'Lemon']);
  });

  group('recentTestSongs', () {
    TrackPlays play(String id, int at, {String title = '', int count = 1, String? cover}) => TrackPlays(
      trackId: MediaId.subsonic(id),
      playCount: count,
      lastPlayedAt: at,
      title: title.isEmpty ? 'Song $id' : title,
      artist: 'Artist $id',
      album: 'Album $id',
      coverArtKey: cover,
    );

    test('should_offerSixSongsNewestFirstEachOnce_when_readFromThePlays', () {
      final songs = recentTestSongs([
        play('old', 1, count: 40),
        play('a', 90, cover: 'https://img/cover-a'),
        play('b', 80, cover: 'cov-b'),
        play('c', 70),
        play('d', 60),
        play('e', 50),
        play('f', 40),
        play('g', 30),
        TrackPlays(trackId: MediaId.subsonic('untitled'), playCount: 3, lastPlayedAt: 100, title: '  '),
      ]);
      expect(songs.map((s) => s.key.rawId), ['a', 'b', 'c', 'd', 'e', 'f']);
      expect(songs.first.song, const AiTrySong('Song a', 'Artist a', 'Album a'));
      expect(songs.first.cover, const CoverUrl('https://img/cover-a'));
      expect(songs[1].cover, const CoverSourceRelative('cov-b'));
      expect(songs[2].cover, isNull);
    });

    test('should_offerNoSongs_when_thereIsNoPlayHistory', () {
      expect(recentTestSongs(const []), isEmpty);
    });
  });
}

/// A client whose answers wait for [gate].
class _GatedAiClient extends FakeAiClient {
  final gate = Completer<void>();

  @override
  Future<String> generate(
    AiSettings ai,
    String prompt, {
    required double temperature,
    required bool useSearch,
    Duration? timeout,
  }) async {
    prompts.add(prompt);
    await gate.future;
    return reply;
  }
}
