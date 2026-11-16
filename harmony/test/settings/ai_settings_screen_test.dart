import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/ai/ai_client.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/ai_settings_screen.dart';
import 'package:yoin/settings/ai_settings_view_model.dart';
import 'package:yoin/settings/ai_sub_pages.dart';

import '../support/fake_ai_client.dart';

/// Settings › AI features (Android AiSettingsPage, AiSubPages): the service,
/// tier, keys, Custom's endpoint, model, thinking and search are kept as they
/// change; the list row says what is in use; the tier page and 「拿一首歌试试」.
void main() {
  late InMemoryStore plain;
  late InMemoryStore secrets;
  late FakeAiClient ai;
  late List<Uri> opened;

  Future<ProviderContainer> pump(WidgetTester tester, Widget child, {AiTrySong? playing}) async {
    tester.view.physicalSize = const Size(420, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(plain),
          secretStoreProvider.overrideWithValue(secrets),
          aiClientProvider.overrideWithValue(ai),
          aiTryPrefillProvider.overrideWith((ref) => playing),
          aiLinkOpenerProvider.overrideWithValue((url) async {
            opened.add(url);
            return true;
          }),
        ],
        child: MaterialApp(home: Material(child: child)),
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

  testWidgets('should_keepTierKeyAndLanguage_when_changed', (tester) async {
    await pump(tester, const AiSettingsScreen());
    expect(find.text('AI features'), findsWidgets);
    expect(find.text('Answer in English'), findsOneWidget);

    await tap(tester, 'ai-tier-best');
    expect(prefs()['tier'], 'best');

    await tester.enterText(field('ai-key-gemini'), ' AIza-key ');
    await tester.pumpAndSettle();
    expect(secrets.values[AiSettingsStore.geminiKeyKey], 'AIza-key');
    expect(plain.values.values.any((v) => v.contains('AIza')), isFalse, reason: 'the key never leaves the secrets');

    await tap(tester, 'ai-answer-in');
    await tester.tap(find.text('Japanese').last);
    await tester.pumpAndSettle();
    expect(find.text('Answer in Japanese'), findsOneWidget);
    expect(prefs()['targetLanguage'], 'Japanese');
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
    await tap(tester, 'ai-search-exa');
    expect(find.text('Exa key'), findsOneWidget);
    await tester.enterText(field('ai-search-key-exa'), 'exa-k');
    await tester.pumpAndSettle();
    expect(secrets.values[AiSettingsStore.searchExaKeyKey], 'exa-k');
    expect(secrets.values[AiSettingsStore.searchDeepSeekKeyKey], isNull);
    await tap(tester, 'ai-search-link');
    expect(opened.single.toString(), 'https://dashboard.exa.ai/api-keys');
    await tap(tester, 'ai-search-none');
    expect(prefs()['searchService'], 'none');
    expect(find.byKey(const ValueKey('ai-search-key-exa')), findsNothing);
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

    await notifier.setProvider(AiProvider.custom);
    await notifier.setKey(AiProvider.custom, 'c');
    await notifier.setCustomBaseUrl('https://x');
    await notifier.setCustomModel('my-model');
    await tester.pumpAndSettle();
    expect(find.text('Custom'), findsOneWidget);
    expect(find.text('my-model'), findsOneWidget, reason: 'Custom names its model');
  });

  testWidgets('should_showBothTiersFacts_when_tierPageOpens', (tester) async {
    final container = await pump(tester, const AiTiersScreen());
    await container.read(aiSettingsProvider.notifier).setProvider(AiProvider.deepSeek);
    await tester.pumpAndSettle();
    expect(find.text('DeepSeek tiers'), findsOneWidget);
    expect(find.text('DeepSeek Search once, full pages'), findsOneWidget);
    expect(find.text('DeepSeek Search twice, full pages'), findsOneWidget);
    expect(find.text('85%'), findsOneWidget);
    expect(find.text(r'$3.5'), findsOneWidget);
    expect(find.textContaining('Works in mainland China'), findsOneWidget);

    await container.read(aiSettingsProvider.notifier).setProvider(AiProvider.qwen);
    await tester.pumpAndSettle();
    expect(find.text('qwen-plus'), findsOneWidget);
    expect(find.text('—'), findsNWidgets(6), reason: 'Qwen is not tested yet');
  });

  testWidgets('should_askForKey_when_tryRunsWithoutOne', (tester) async {
    await pump(tester, const AiTryScreen(), playing: const AiTrySong('Song', 'Artist', 'Album'));
    expect(
      tester.widget<EditableText>(field('ai-try-song')).controller.text,
      'Song',
      reason: 'opens on the song playing',
    );
    await tap(tester, 'ai-try-run');
    expect(find.text('Paste a key first'), findsOneWidget);
    expect(ai.prompts, isEmpty);
  });

  testWidgets('should_showAboutFields_when_tryRuns', (tester) async {
    ai.reply = '[LYRICIST]Someone[/LYRICIST][REVIEW]A song.[/REVIEW]';
    final container = await pump(tester, const AiTryScreen());
    await container.read(aiSettingsProvider.notifier).setGeminiKey('k');
    final run = find.byKey(const ValueKey('ai-try-run'));
    expect(tester.widget<FilledButton>(run).onPressed, isNull, reason: 'needs a title and an artist');
    await tester.enterText(field('ai-try-song'), 'Song');
    await tester.enterText(field('ai-try-artist'), 'Artist');
    await tester.pumpAndSettle();
    await tap(tester, 'ai-try-run');
    expect(find.text(AiSettings.geminiGood), findsOneWidget);
    expect(find.text('The model searched on its own'), findsOneWidget);
    expect(find.text('Someone'), findsOneWidget);
    expect(find.text('A song.'), findsOneWidget);
    expect(find.text('—'), findsNWidgets(4));

    ai.error = const AiException('Gemini error (429): quota');
    await tap(tester, 'ai-try-run');
    expect(find.text('Failed: Gemini error (429): quota'), findsOneWidget);
  });
}
