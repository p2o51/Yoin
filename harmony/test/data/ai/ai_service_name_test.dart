import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/ai/ai_service_name.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/detail/detail_strings.dart';
import 'package:yoin/nowplaying/nowplaying_strings.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/settings_strings.dart';
import 'package:yoin/ui/component/ai_service_scope.dart';

/// Pumps [lang] and hands back the three string tables.
Future<(NowPlayingStrings, DetailStrings, SettingsStrings)> _strings(WidgetTester tester, String lang) async {
  late (NowPlayingStrings, DetailStrings, SettingsStrings) out;
  await tester.pumpWidget(
    MaterialApp(
      locale: Locale(lang),
      supportedLocales: const [Locale('en'), Locale('zh')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      home: Builder(
        builder: (context) {
          out = (NowPlayingStrings.of(context), DetailStrings.of(context), SettingsStrings.of(context));
          return const SizedBox();
        },
      ),
    ),
  );
  return out;
}

void main() {
  test('should_nameEachService_when_copyNamesIt', () {
    expect(aiServiceName(AiProvider.gemini, zh: true), 'Gemini');
    expect(aiServiceName(AiProvider.deepSeek, zh: true), 'DeepSeek');
    expect(aiServiceName(AiProvider.qwen, zh: true), '千问');
    expect(aiServiceName(AiProvider.qwen, zh: false), 'Qwen');
    expect(aiServiceName(AiProvider.custom, zh: true), 'AI');
    // A Chinese name sits flush, a Latin one keeps its spaces.
    expect(aiServiceCopy(r'问 %1$s', AiProvider.qwen, zh: true), '问千问');
    expect(aiServiceCopy(r'%1$s 正在写…', AiProvider.qwen, zh: true), '千问正在写…');
    expect(aiServiceCopy(r'问 %1$s', AiProvider.gemini, zh: true), '问 Gemini');
    expect(aiServiceCopy(r'%1$s 正在写…', AiProvider.custom, zh: true), 'AI 正在写…');
  });

  testWidgets('should_nameTheServiceInEveryAiLine_when_chinese', (tester) async {
    final (np, detail, settings) = await _strings(tester, 'zh');
    expect(np.aiAskProvider(AiProvider.qwen), '问千问');
    expect(np.aiWriting(AiProvider.deepSeek), 'DeepSeek 正在写…');
    expect(np.aiRegenerateWith(AiProvider.qwen), '用千问重新生成');
    expect(np.aiNothingNew(AiProvider.qwen), '千问没有新的内容');
    expect(np.aiFailed(AiProvider.custom), 'AI 没能回答，再试一次');
    expect(np.aiKeyMissing(AiProvider.qwen), '缺少千问 API 密钥');
    expect(np.aiNoKey(AiProvider.deepSeek), '未设置 DeepSeek API 密钥');
    expect(np.shareAiSign(AiProvider.qwen), '由千问整理');
    expect(np.shareAiSign(AiProvider.gemini), '由 Gemini 整理');
    expect(detail.aiWriting(AiProvider.qwen), '千问正在写…');
    expect(detail.aiGenerateWith(AiProvider.qwen), '让千问生成');
    expect(detail.aiByline(AiProvider.custom), 'AI');
    expect(settings.aiSummaryEmpty(AiProvider.qwen), '歌曲信息、问千问、歌词翻译与 Memories 标⁠题');
  });

  testWidgets('should_nameTheServiceInEveryAiLine_when_english', (tester) async {
    final (np, detail, settings) = await _strings(tester, 'en');
    expect(np.aiAskProvider(AiProvider.deepSeek), 'Ask DeepSeek');
    expect(np.aiWriting(AiProvider.qwen), 'Qwen is writing…');
    expect(np.aiRegenerateWith(AiProvider.custom), 'Regenerate with AI');
    expect(np.aiNoKey(AiProvider.gemini), 'No Gemini API key');
    expect(np.shareAiSign(AiProvider.qwen), 'By Qwen');
    expect(detail.aiAskProvider(AiProvider.qwen), 'Ask Qwen');
    expect(settings.aiSummaryEmpty(AiProvider.deepSeek), 'Song info, Ask DeepSeek, translation and Memory titles');
  });

  testWidgets('should_followSettings_when_serviceChanges', (tester) async {
    final kv = InMemoryStore();
    final secrets = InMemoryStore();
    await AiSettingsStore(kv, secrets).write(const AiSettings(provider: AiProvider.deepSeek));
    final c = ProviderContainer(
      overrides: [keyValueStoreProvider.overrideWithValue(kv), secretStoreProvider.overrideWithValue(secrets)],
    );
    addTearDown(c.dispose);
    AiProvider? seen;
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: ActiveAiServiceScope(
          child: Builder(
            builder: (context) {
              seen = AiServiceScope.of(context);
              return const SizedBox();
            },
          ),
        ),
      ),
    );
    expect(seen, AiProvider.gemini, reason: 'Gemini until the settings are read');
    await tester.pump();
    await tester.pump();
    expect(seen, AiProvider.deepSeek);
    await c.read(aiSettingsProvider.notifier).setProvider(AiProvider.qwen);
    await tester.pump();
    expect(seen, AiProvider.qwen);
  });
}
