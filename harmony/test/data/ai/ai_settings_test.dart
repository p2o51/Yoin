import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/platform/storage/stores.dart';

/// Android AiSettings / FileAiProviderStore: what each service and tier
/// means, when a call can be made, where search comes from, and where the
/// keys are kept.
void main() {
  group('AiSettings', () {
    test('should_pickModelPerServiceAndTier_when_asked', () {
      expect(const AiSettings().modelId, AiSettings.geminiGood);
      expect(const AiSettings(tier: AiTier.best).modelId, AiSettings.geminiBest);
      expect(const AiSettings(provider: AiProvider.deepSeek, tier: AiTier.best).modelId, AiSettings.deepSeekModel);
      expect(const AiSettings(provider: AiProvider.qwen).modelId, AiSettings.qwenGood);
      expect(const AiSettings(provider: AiProvider.qwen, tier: AiTier.best).modelId, AiSettings.qwenBest);
      expect(const AiSettings(provider: AiProvider.custom, customModel: ' gpt-x ').modelId, 'gpt-x');
    });

    test('should_needEndpointAndModel_when_customIsChosen', () {
      expect(const AiSettings(geminiKey: ' k ').isReady, isTrue);
      expect(const AiSettings(geminiKey: '  ').isReady, isFalse);
      expect(const AiSettings(provider: AiProvider.deepSeek, geminiKey: 'k').isReady, isFalse);
      const custom = AiSettings(provider: AiProvider.custom, customKey: 'k');
      expect(custom.isReady, isFalse);
      expect(custom.copyWith(customBaseUrl: 'https://x').isReady, isFalse);
      expect(custom.copyWith(customBaseUrl: 'https://x', customModel: 'm').isReady, isTrue);
    });

    test('should_searchWhereEachServiceSearches_when_asked', () {
      expect(const AiSettings().searchKind, AiSearchKind.builtIn);
      expect(const AiSettings(provider: AiProvider.qwen).searchKind, AiSearchKind.builtIn);
      const deepSeek = AiSettings(
        provider: AiProvider.deepSeek,
        deepSeekKey: ' ds ',
        searchService: AiSearchService.exa,
      );
      expect(deepSeek.searchKind, AiSearchKind.external);
      expect(deepSeek.effectiveSearchService, AiSearchService.deepSeek);
      expect(deepSeek.searchApiKey, 'ds', reason: 'DeepSeek searches with its own key');
      expect(deepSeek.searchQueries, 1);
      expect(deepSeek.copyWith(tier: AiTier.best).searchQueries, 2);

      const custom = AiSettings(provider: AiProvider.custom, searchService: AiSearchService.exa);
      expect(custom.searchKind, AiSearchKind.none, reason: 'no key for the search service');
      expect(custom.copyWith(searchKeyExa: 'e').searchKind, AiSearchKind.external);
      expect(custom.copyWith(searchKeyTavily: 't').searchKind, AiSearchKind.none, reason: 'keys never cross services');
      expect(custom.copyWith(searchService: AiSearchService.none, searchKeyExa: 'e').searchKind, AiSearchKind.none);
    });
  });

  group('AiSettingsStore', () {
    test('should_keepEveryKeyInSecrets_when_stored', () async {
      final plain = InMemoryStore();
      final secrets = InMemoryStore();
      final store = AiSettingsStore(plain, secrets);
      const settings = AiSettings(
        provider: AiProvider.custom,
        tier: AiTier.best,
        geminiKey: 'g',
        deepSeekKey: 'd',
        qwenKey: 'q',
        customFormat: AiApiFormat.anthropic,
        customBaseUrl: 'https://api.example.com',
        customKey: 'c',
        customModel: 'claude-x',
        customThinking: AiThinking.high,
        searchService: AiSearchService.tavily,
        searchKeyDeepSeek: 'sd',
        searchKeyExa: 'se',
        searchKeyTavily: 'st',
        targetLanguage: 'Japanese',
      );
      await store.write(settings);
      expect(await store.read(), settings);
      expect(secrets.values, {
        AiSettingsStore.geminiKeyKey: 'g',
        AiSettingsStore.deepSeekKeyKey: 'd',
        AiSettingsStore.qwenKeyKey: 'q',
        AiSettingsStore.customKeyKey: 'c',
        AiSettingsStore.searchDeepSeekKeyKey: 'sd',
        AiSettingsStore.searchExaKeyKey: 'se',
        AiSettingsStore.searchTavilyKeyKey: 'st',
      });
      final prefs = jsonDecode(plain.values[AiSettingsStore.prefsKey]!) as Map<String, Object?>;
      expect(prefs['provider'], 'custom');
      expect(prefs['customModel'], 'claude-x');
      for (final key in ['"g"', '"d"', '"q"', '"c"', '"sd"', '"se"', '"st"']) {
        expect(plain.values[AiSettingsStore.prefsKey], isNot(contains(key)));
      }
    });

    test('should_writeOnlyChangedSecrets_when_previousIsKnown', () async {
      final secrets = _CountingStore();
      final store = AiSettingsStore(InMemoryStore(), secrets);
      const before = AiSettings(geminiKey: 'g');
      await store.write(before.copyWith(deepSeekKey: 'd'), previous: before);
      expect(secrets.writes, [AiSettingsStore.deepSeekKeyKey]);
    });

    test('should_fallBackToDefaults_when_storedValuesAreUnknown', () async {
      final plain = InMemoryStore();
      await plain.write(
        AiSettingsStore.prefsKey,
        jsonEncode({'provider': 'Grok', 'tier': 'Ultra', 'targetLanguage': 'Klingon'}),
      );
      final read = await AiSettingsStore(plain, InMemoryStore()).read();
      expect(read, const AiSettings());
    });
  });
}

class _CountingStore implements SecretStore {
  final _inner = InMemoryStore();
  final writes = <String>[];

  @override
  Future<String?> read(String key) => _inner.read(key);

  @override
  Future<void> write(String key, String? value) {
    writes.add(key);
    return _inner.write(key, value);
  }
}
