import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../platform/storage/stores.dart';

/// Which online AI writes About, answers Ask and translates lyrics (Android
/// AiProvider, AI settings v2 2026-11-12). The user picks a service, one of
/// its tiers where it has two, and pastes a key; Custom adds an endpoint, a
/// model, a thinking level and a search service.
///
/// Search is two kinds: the service's own (Gemini's Google Search, Qwen's
/// enable_search), or Yoin calling a search API first and putting the pages in
/// the prompt (DeepSeek, Custom). The second kind works with any model.
enum AiProvider {
  gemini,
  deepSeek,
  qwen,
  custom;

  /// The tiers this service offers. Qwen has one (owner 2026-11-13): on the
  /// 500-song benchmark qwen-flash was more accurate, faster and cheaper than
  /// qwen-plus and qwen3.8-flash, so there was nothing for a second tier to
  /// be. Custom has none: its model is typed.
  List<AiTier> get tiers => switch (this) {
    gemini || deepSeek => AiTier.values,
    qwen => const [AiTier.good],
    custom => const [],
  };
}

/// 「好」/「最佳」. What each means per service is in [AiSettings.modelId] / [AiSettings.searchQueries].
enum AiTier { good, best }

enum AiApiFormat { openAi, anthropic }

enum AiThinking { off, low, medium, high }

/// Custom's search choices, in the order the menu lists them (owner 2026-11-14 added Brave and 豆包).
enum AiSearchService { deepSeek, exa, tavily, brave, doubao, none }

/// Where search comes from for a call that wants it (AiSettings.SearchKind).
enum AiSearchKind { builtIn, external, none }

/// The "Answer in" choices and the default (Android GeminiConfig).
abstract final class AiLanguages {
  static const String defaultTarget = 'English';

  static const List<String> supported = [
    'English',
    'Simplified Chinese',
    'Traditional Chinese',
    'Japanese',
    'Korean',
    'Spanish',
    'French',
    'German',
  ];

  /// An unknown or missing language reads as [defaultTarget] (normalizeTargetLanguage).
  static String normalize(String? language) => supported.contains(language) ? language! : defaultTarget;
}

/// Everything one AI call needs (Android AiSettings + AiProviderPrefs +
/// the Gemini key and answer language): the service and its key, the tier,
/// Custom's endpoint, the search service, the answer language.
@immutable
final class AiSettings {
  const AiSettings({
    this.provider = AiProvider.gemini,
    this.tier = AiTier.good,
    this.geminiKey = '',
    this.deepSeekKey = '',
    this.qwenKey = '',
    this.customFormat = AiApiFormat.openAi,
    this.customBaseUrl = '',
    this.customKey = '',
    this.customModel = '',
    this.customThinking = AiThinking.off,
    this.searchService = AiSearchService.deepSeek,
    this.searchKeyDeepSeek = '',
    this.searchKeyExa = '',
    this.searchKeyTavily = '',
    this.searchKeyBrave = '',
    this.searchKeyDoubao = '',
    this.targetLanguage = AiLanguages.defaultTarget,
  });

  static const String geminiGood = 'gemini-3.1-flash-lite';
  static const String geminiBest = 'gemini-3.5-flash-lite';
  static const String deepSeekModel = 'deepseek-flash';
  static const String qwenModel = 'qwen-flash';
  static const String deepSeekBaseUrl = 'https://api.deepseek.com';
  static const String qwenBaseUrl = 'https://dashscope.aliyuncs.com/compatible-mode/v1';

  final AiProvider provider;
  final AiTier tier;
  final String geminiKey;
  final String deepSeekKey;
  final String qwenKey;
  final AiApiFormat customFormat;
  final String customBaseUrl;
  final String customKey;
  final String customModel;
  final AiThinking customThinking;

  /// Custom's search service. DeepSeek always searches with DeepSeek ([effectiveSearchService]).
  final AiSearchService searchService;

  // One key per search service: a key typed for one is never sent to another.
  final String searchKeyDeepSeek;
  final String searchKeyExa;
  final String searchKeyTavily;
  final String searchKeyBrave;
  final String searchKeyDoubao;
  final String targetLanguage;

  /// The tier in use: [tier] where [provider] offers it, otherwise the one it
  /// has. [tier] itself is kept as chosen, so going to Qwen and back to Gemini
  /// does not lose 「最佳」.
  AiTier get activeTier => provider.tiers.contains(tier) ? tier : provider.tiers.firstOrNull ?? tier;

  /// The key typed for [p], as stored.
  String keyFor(AiProvider p) => switch (p) {
    AiProvider.gemini => geminiKey,
    AiProvider.deepSeek => deepSeekKey,
    AiProvider.qwen => qwenKey,
    AiProvider.custom => customKey,
  };

  /// The key Custom's search [service] was given.
  String searchKeyFor(AiSearchService service) => switch (service) {
    AiSearchService.deepSeek => searchKeyDeepSeek,
    AiSearchService.exa => searchKeyExa,
    AiSearchService.tavily => searchKeyTavily,
    AiSearchService.brave => searchKeyBrave,
    AiSearchService.doubao => searchKeyDoubao,
    AiSearchService.none => '',
  };

  AiSettings withKey(AiProvider p, String key) => switch (p) {
    AiProvider.gemini => copyWith(geminiKey: key),
    AiProvider.deepSeek => copyWith(deepSeekKey: key),
    AiProvider.qwen => copyWith(qwenKey: key),
    AiProvider.custom => copyWith(customKey: key),
  };

  AiSettings withSearchKey(AiSearchService service, String key) => switch (service) {
    AiSearchService.deepSeek => copyWith(searchKeyDeepSeek: key),
    AiSearchService.exa => copyWith(searchKeyExa: key),
    AiSearchService.tavily => copyWith(searchKeyTavily: key),
    AiSearchService.brave => copyWith(searchKeyBrave: key),
    AiSearchService.doubao => copyWith(searchKeyDoubao: key),
    AiSearchService.none => this,
  };

  /// The chosen service's key.
  String get apiKey => keyFor(provider).trim();

  /// A call can be made: the key (and for Custom the endpoint and model) is set.
  bool get isReady =>
      apiKey.isNotEmpty &&
      (provider != AiProvider.custom || (customBaseUrl.trim().isNotEmpty && customModel.trim().isNotEmpty));

  String get modelId => switch (provider) {
    AiProvider.gemini => activeTier == AiTier.best ? geminiBest : geminiGood,
    AiProvider.deepSeek => deepSeekModel,
    AiProvider.qwen => qwenModel,
    AiProvider.custom => customModel.trim(),
  };

  AiSearchKind get searchKind => switch (provider) {
    AiProvider.gemini || AiProvider.qwen => AiSearchKind.builtIn,
    AiProvider.deepSeek => AiSearchKind.external,
    AiProvider.custom =>
      searchService == AiSearchService.none || searchApiKey.isEmpty ? AiSearchKind.none : AiSearchKind.external,
  };

  /// The search service a Yoin-run search goes to.
  AiSearchService get effectiveSearchService =>
      provider == AiProvider.deepSeek ? AiSearchService.deepSeek : searchService;

  String get searchApiKey => provider == AiProvider.deepSeek ? deepSeekKey.trim() : searchKeyFor(searchService).trim();

  /// How many searches Yoin runs per About. 35-song test (2026-11-12): one
  /// search reading full pages in the song's own language got most of the
  /// gain; DeepSeek's 「最佳」 adds a second query on the album.
  int get searchQueries => provider == AiProvider.deepSeek && activeTier == AiTier.best ? 2 : 1;

  AiSettings copyWith({
    AiProvider? provider,
    AiTier? tier,
    String? geminiKey,
    String? deepSeekKey,
    String? qwenKey,
    AiApiFormat? customFormat,
    String? customBaseUrl,
    String? customKey,
    String? customModel,
    AiThinking? customThinking,
    AiSearchService? searchService,
    String? searchKeyDeepSeek,
    String? searchKeyExa,
    String? searchKeyTavily,
    String? searchKeyBrave,
    String? searchKeyDoubao,
    String? targetLanguage,
  }) => AiSettings(
    provider: provider ?? this.provider,
    tier: tier ?? this.tier,
    geminiKey: geminiKey ?? this.geminiKey,
    deepSeekKey: deepSeekKey ?? this.deepSeekKey,
    qwenKey: qwenKey ?? this.qwenKey,
    customFormat: customFormat ?? this.customFormat,
    customBaseUrl: customBaseUrl ?? this.customBaseUrl,
    customKey: customKey ?? this.customKey,
    customModel: customModel ?? this.customModel,
    customThinking: customThinking ?? this.customThinking,
    searchService: searchService ?? this.searchService,
    searchKeyDeepSeek: searchKeyDeepSeek ?? this.searchKeyDeepSeek,
    searchKeyExa: searchKeyExa ?? this.searchKeyExa,
    searchKeyTavily: searchKeyTavily ?? this.searchKeyTavily,
    searchKeyBrave: searchKeyBrave ?? this.searchKeyBrave,
    searchKeyDoubao: searchKeyDoubao ?? this.searchKeyDoubao,
    targetLanguage: targetLanguage ?? this.targetLanguage,
  );

  List<Object> get _fields => [
    provider,
    tier,
    geminiKey,
    deepSeekKey,
    qwenKey,
    customFormat,
    customBaseUrl,
    customKey,
    customModel,
    customThinking,
    searchService,
    searchKeyDeepSeek,
    searchKeyExa,
    searchKeyTavily,
    searchKeyBrave,
    searchKeyDoubao,
    targetLanguage,
  ];

  @override
  bool operator ==(Object other) => other is AiSettings && listEquals(other._fields, _fields);

  @override
  int get hashCode => Object.hashAll(_fields);
}

/// Where the AI settings live: every key in the system's secret store (Android
/// keeps them out of backups too), the rest in the plain store.
class AiSettingsStore {
  AiSettingsStore(this._plain, this._secrets);

  final KeyValueStore _plain;
  final SecretStore _secrets;

  static const String prefsKey = 'yoin.ai.prefs';
  static const String geminiKeyKey = 'yoin.ai.gemini.key';
  static const String deepSeekKeyKey = 'yoin.ai.deepseek.key';
  static const String qwenKeyKey = 'yoin.ai.qwen.key';
  static const String customKeyKey = 'yoin.ai.custom.key';
  static const String searchDeepSeekKeyKey = 'yoin.ai.search.deepseek.key';
  static const String searchExaKeyKey = 'yoin.ai.search.exa.key';
  static const String searchTavilyKeyKey = 'yoin.ai.search.tavily.key';
  static const String searchBraveKeyKey = 'yoin.ai.search.brave.key';
  static const String searchDoubaoKeyKey = 'yoin.ai.search.doubao.key';

  /// Each secret's store key and how to read it from the settings.
  static final Map<String, String Function(AiSettings)> _secretFields = {
    geminiKeyKey: (s) => s.geminiKey,
    deepSeekKeyKey: (s) => s.deepSeekKey,
    qwenKeyKey: (s) => s.qwenKey,
    customKeyKey: (s) => s.customKey,
    searchDeepSeekKeyKey: (s) => s.searchKeyDeepSeek,
    searchExaKeyKey: (s) => s.searchKeyExa,
    searchTavilyKeyKey: (s) => s.searchKeyTavily,
    searchBraveKeyKey: (s) => s.searchKeyBrave,
    searchDoubaoKeyKey: (s) => s.searchKeyDoubao,
  };

  static T _enumOr<T extends Enum>(List<T> values, Object? name, T fallback) =>
      values.where((v) => v.name == name).firstOrNull ?? fallback;

  Future<AiSettings> read() async {
    final secrets = <String, String>{};
    for (final key in _secretFields.keys) {
      try {
        secrets[key] = await _secrets.read(key) ?? '';
      } catch (_) {
        secrets[key] = '';
      }
    }
    Map<Object?, Object?> json = const {};
    try {
      final raw = await _plain.read(prefsKey);
      final decoded = raw == null ? null : jsonDecode(raw);
      if (decoded is Map) json = decoded;
    } catch (_) {}
    const d = AiSettings();
    String str(String name) => json[name] is String ? json[name] as String : '';
    return AiSettings(
      provider: _enumOr(AiProvider.values, json['provider'], d.provider),
      tier: _enumOr(AiTier.values, json['tier'], d.tier),
      geminiKey: secrets[geminiKeyKey]!,
      deepSeekKey: secrets[deepSeekKeyKey]!,
      qwenKey: secrets[qwenKeyKey]!,
      customFormat: _enumOr(AiApiFormat.values, json['customFormat'], d.customFormat),
      customBaseUrl: str('customBaseUrl'),
      customKey: secrets[customKeyKey]!,
      customModel: str('customModel'),
      customThinking: _enumOr(AiThinking.values, json['customThinking'], d.customThinking),
      searchService: _enumOr(AiSearchService.values, json['searchService'], d.searchService),
      searchKeyDeepSeek: secrets[searchDeepSeekKeyKey]!,
      searchKeyExa: secrets[searchExaKeyKey]!,
      searchKeyTavily: secrets[searchTavilyKeyKey]!,
      searchKeyBrave: secrets[searchBraveKeyKey]!,
      searchKeyDoubao: secrets[searchDoubaoKeyKey]!,
      targetLanguage: AiLanguages.normalize(json['targetLanguage'] as String?),
    );
  }

  /// Writes [settings]; with [previous] (what the store holds), only the
  /// secrets that changed — a key field saves on every keystroke.
  Future<void> write(AiSettings settings, {AiSettings? previous}) async {
    for (final MapEntry(:key, value: field) in _secretFields.entries) {
      final value = field(settings).trim();
      if (previous != null && field(previous).trim() == value) continue;
      await _secrets.write(key, value.isEmpty ? null : value);
    }
    await _plain.write(
      prefsKey,
      jsonEncode({
        'provider': settings.provider.name,
        'tier': settings.tier.name,
        'customFormat': settings.customFormat.name,
        'customBaseUrl': settings.customBaseUrl.trim(),
        'customModel': settings.customModel.trim(),
        'customThinking': settings.customThinking.name,
        'searchService': settings.searchService.name,
        'targetLanguage': AiLanguages.normalize(settings.targetLanguage),
      }),
    );
  }
}

/// The store; null in tests without storage (AI then reads as not set up).
final aiSettingsStoreProvider = Provider<AiSettingsStore?>((ref) {
  try {
    return AiSettingsStore(ref.watch(keyValueStoreProvider), ref.watch(secretStoreProvider));
  } catch (_) {
    return null;
  }
});

/// The AI settings as stored, kept in step with the settings page (Android
/// AiSettingsViewModel's setters). Every change is kept at once; keys,
/// Custom's address and model are kept trimmed.
class AiSettingsNotifier extends AsyncNotifier<AiSettings> {
  @override
  Future<AiSettings> build() async => await ref.watch(aiSettingsStoreProvider)?.read() ?? const AiSettings();

  Future<void> _writes = Future.value();

  /// What the store holds once the queued writes are done.
  AiSettings? _stored;

  Future<void> _update(AiSettings Function(AiSettings) transform) async {
    final current = state.value ?? await future;
    _stored ??= current;
    final next = transform(current);
    if (next == current) return;
    state = AsyncData(next);
    final store = ref.read(aiSettingsStoreProvider);
    if (store == null) return;
    // A key field saves on every keystroke: writes go one at a time, each
    // with the settings as they are when its turn comes, so an earlier,
    // slower write never lands last and leaves half a key stored.
    final write = _writes.then((_) async {
      if (!ref.mounted) return;
      final latest = state.value ?? next;
      await store.write(latest, previous: _stored);
      _stored = latest;
    });
    _writes = write.then((_) {}, onError: (Object _) {});
    await write;
  }

  Future<void> setProvider(AiProvider provider) => _update((s) => s.copyWith(provider: provider));

  Future<void> setTier(AiTier tier) => _update((s) => s.copyWith(tier: tier));

  /// The key for [provider], as typed.
  Future<void> setKey(AiProvider provider, String key) => _update((s) => s.withKey(provider, key.trim()));

  Future<void> setGeminiKey(String key) => setKey(AiProvider.gemini, key);

  Future<void> setCustomFormat(AiApiFormat format) => _update((s) => s.copyWith(customFormat: format));

  Future<void> setCustomBaseUrl(String url) => _update((s) => s.copyWith(customBaseUrl: url.trim()));

  Future<void> setCustomModel(String model) => _update((s) => s.copyWith(customModel: model.trim()));

  Future<void> setCustomThinking(AiThinking thinking) => _update((s) => s.copyWith(customThinking: thinking));

  Future<void> setSearchService(AiSearchService service) => _update((s) => s.copyWith(searchService: service));

  /// The key for search [service], as typed. Each service keeps its own.
  Future<void> setSearchKey(AiSearchService service, String key) =>
      _update((s) => s.withSearchKey(service, key.trim()));

  Future<void> setTargetLanguage(String language) =>
      _update((s) => s.copyWith(targetLanguage: AiLanguages.normalize(language)));
}

final aiSettingsProvider = AsyncNotifierProvider<AiSettingsNotifier, AiSettings>(AiSettingsNotifier.new);
