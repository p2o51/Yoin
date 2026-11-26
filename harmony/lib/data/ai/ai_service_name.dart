/// Whose words the generated text is, and who a question goes to: the AI service chosen in Settings ›
/// AI features (Android ui/component/AiProviderName.kt; 51, 2026-11-25: "Ask Gemini" while DeepSeek
/// answers is wrong). Gemini and DeepSeek by name, Qwen as 千问 in Chinese, a Custom endpoint as plain
/// "AI". On-device Gemini Nano copy is not this: it is always Gemini.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../albumabout/yoin_album_about.dart';
import 'ai_settings.dart';

/// The AI service in use; Gemini until the settings are read (Android LocalAiProvider's default).
final activeAiProviderProvider = Provider<AiProvider>(
  (ref) => ref.watch(aiSettingsProvider).value?.provider ?? AiProvider.gemini,
);

/// The service's name as copy reads it (ai_provider_gemini / _deepseek / _qwen / ai_provider_generic_name).
String aiServiceName(AiProvider provider, {required bool zh}) => switch (provider) {
  AiProvider.gemini => 'Gemini', // ai_provider_gemini (not translatable)
  AiProvider.deepSeek => 'DeepSeek', // ai_provider_deepseek (not translatable)
  AiProvider.qwen => zh ? '千问' : 'Qwen', // ai_provider_qwen
  AiProvider.custom => 'AI', // ai_provider_generic_name (not translatable)
};

/// [template] with the service's name filled in (`%1$s`), the space beside a Chinese name dropped
/// (问千问, 千问正在写…; 问 Gemini keeps its space; :shared's rule, as on Android and iOS).
String aiServiceCopy(String template, AiProvider provider, {required bool zh}) =>
    YoinAlbumAbout.aiCopy(template, aiServiceName(provider, zh: zh));
