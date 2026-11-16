import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../app/providers.dart';
import '../../platform/storage/stores.dart';

/// 「好」/「最佳」(Android AiTier): Gemini 3.1 Flash-Lite or 3.5 Flash-Lite.
enum AiTier { good, best }

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

/// Everything one AI call needs (Android AiSettings, Gemini only on
/// HarmonyOS): the key, the tier and the answer language.
@immutable
final class AiSettings {
  const AiSettings({this.geminiKey = '', this.tier = AiTier.good, this.targetLanguage = AiLanguages.defaultTarget});

  static const String geminiGood = 'gemini-3.1-flash-lite';
  static const String geminiBest = 'gemini-3.5-flash-lite';

  final String geminiKey;
  final AiTier tier;
  final String targetLanguage;

  String get apiKey => geminiKey.trim();

  /// A call can be made: the key is set.
  bool get isReady => apiKey.isNotEmpty;

  String get modelId => tier == AiTier.best ? geminiBest : geminiGood;

  AiSettings copyWith({String? geminiKey, AiTier? tier, String? targetLanguage}) => AiSettings(
    geminiKey: geminiKey ?? this.geminiKey,
    tier: tier ?? this.tier,
    targetLanguage: targetLanguage ?? this.targetLanguage,
  );

  @override
  bool operator ==(Object other) =>
      other is AiSettings &&
      other.geminiKey == geminiKey &&
      other.tier == tier &&
      other.targetLanguage == targetLanguage;

  @override
  int get hashCode => Object.hash(geminiKey, tier, targetLanguage);
}

/// Where the AI settings live: the key in the system's secret store (Android
/// keeps it out of backups too), the tier and language in the plain store.
class AiSettingsStore {
  AiSettingsStore(this._plain, this._secrets);

  final KeyValueStore _plain;
  final SecretStore _secrets;

  static const String prefsKey = 'yoin.ai.prefs';
  static const String geminiKeyKey = 'yoin.ai.gemini.key';

  Future<AiSettings> read() async {
    final key = await _secrets.read(geminiKeyKey) ?? '';
    var tier = AiTier.good;
    String? language;
    try {
      final raw = await _plain.read(prefsKey);
      final json = raw == null ? null : jsonDecode(raw);
      if (json is Map) {
        tier = AiTier.values.where((t) => t.name == json['tier']).firstOrNull ?? AiTier.good;
        language = json['targetLanguage'] as String?;
      }
    } catch (_) {}
    return AiSettings(geminiKey: key, tier: tier, targetLanguage: AiLanguages.normalize(language));
  }

  Future<void> write(AiSettings settings) async {
    await _secrets.write(geminiKeyKey, settings.apiKey.isEmpty ? null : settings.apiKey);
    await _plain.write(
      prefsKey,
      jsonEncode({'tier': settings.tier.name, 'targetLanguage': AiLanguages.normalize(settings.targetLanguage)}),
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

/// The AI settings as stored, kept in step with the settings page.
class AiSettingsNotifier extends AsyncNotifier<AiSettings> {
  @override
  Future<AiSettings> build() async => await ref.watch(aiSettingsStoreProvider)?.read() ?? const AiSettings();

  Future<void> _update(AiSettings Function(AiSettings) transform) async {
    final current = state.value ?? await future;
    final next = transform(current);
    if (next == current) return;
    state = AsyncData(next);
    await ref.read(aiSettingsStoreProvider)?.write(next);
  }

  Future<void> setGeminiKey(String key) => _update((s) => s.copyWith(geminiKey: key.trim()));
  Future<void> setTier(AiTier tier) => _update((s) => s.copyWith(tier: tier));
  Future<void> setTargetLanguage(String language) =>
      _update((s) => s.copyWith(targetLanguage: AiLanguages.normalize(language)));
}

final aiSettingsProvider = AsyncNotifierProvider<AiSettingsNotifier, AiSettings>(AiSettingsNotifier.new);
