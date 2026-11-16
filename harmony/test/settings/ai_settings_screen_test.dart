import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/ai_settings_screen.dart';

/// Settings › AI features (Android AiSettingsPage, Gemini on HarmonyOS): the
/// tier, the key and "Answer in" are kept as they change; the list row says
/// what is in use once a key is set.
void main() {
  late InMemoryStore plain;
  late InMemoryStore secrets;

  Future<void> pump(WidgetTester tester, Widget child) async {
    tester.view.physicalSize = const Size(420, 900);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    plain = InMemoryStore();
    secrets = InMemoryStore();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [keyValueStoreProvider.overrideWithValue(plain), secretStoreProvider.overrideWithValue(secrets)],
        child: MaterialApp(home: Material(child: child)),
      ),
    );
    await tester.pumpAndSettle();
  }

  Map<String, Object?> prefs() => jsonDecode(plain.values[AiSettingsStore.prefsKey]!) as Map<String, Object?>;

  testWidgets('should_keepTierKeyAndLanguage_when_changed', (tester) async {
    await pump(tester, const AiSettingsScreen());
    expect(find.text('AI features'), findsWidgets);
    expect(find.text('Answer in English'), findsOneWidget);

    await tester.tap(find.byKey(const ValueKey('ai-tier-best')));
    await tester.pumpAndSettle();
    expect(prefs()['tier'], 'best');

    await tester.enterText(
      find.descendant(of: find.byKey(const ValueKey('ai-gemini-key')), matching: find.byType(EditableText)),
      ' AIza-key ',
    );
    await tester.pumpAndSettle();
    expect(secrets.values[AiSettingsStore.geminiKeyKey], 'AIza-key');
    expect(
      plain.values.values.any((v) => v.contains('AIza')),
      isFalse,
      reason: 'the key never leaves the secret store',
    );

    await tester.tap(find.byKey(const ValueKey('ai-answer-in')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Japanese').last);
    await tester.pumpAndSettle();
    expect(find.text('Answer in Japanese'), findsOneWidget);
    expect(prefs()['targetLanguage'], 'Japanese');
  });

  testWidgets('should_nameServiceAndTier_when_keyIsSet', (tester) async {
    await pump(tester, const AiSettingsRow());
    expect(find.text('Song info, Ask Gemini, translation and Memory titles'), findsOneWidget);
    final container = ProviderScope.containerOf(tester.element(find.byType(AiSettingsRow)));
    await container.read(aiSettingsProvider.notifier).setGeminiKey('k');
    await container.read(aiSettingsProvider.notifier).setTier(AiTier.best);
    await tester.pumpAndSettle();
    expect(find.text('Gemini'), findsOneWidget);
    expect(find.text('Best'), findsOneWidget);
  });
}
