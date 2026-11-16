import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/home/home_bubble_tips.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/licenses.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/settings_strings.dart';
import 'package:yoin/settings/subsonic_setup_screen.dart';
import 'package:yoin/settings/tips.dart';
import 'package:yoin/tips/tip_screen.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/fake_music_source.dart';

void main() {
  late InMemoryStore store;

  List<Override> overrides() => [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(store),
        musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
      ];

  Future<void> frames(WidgetTester tester, Duration total) async {
    for (var t = Duration.zero; t < total; t += const Duration(milliseconds: 16)) {
      await tester.pump(const Duration(milliseconds: 16));
    }
  }

  Future<void> settle() async {
    for (var i = 0; i < 10; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  setUp(() => store = InMemoryStore());

  group('tips', () {
    test('should_persistReadTips_when_marked', () async {
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      c.listen(tipReadProvider, (_, _) {});
      await c.read(tipReadProvider.notifier).setRead(HomeBubbleTip.playModes, true);

      final again = ProviderContainer(overrides: overrides());
      addTearDown(again.dispose);
      again.listen(tipReadProvider, (_, _) {});
      await settle();
      expect(again.read(tipReadProvider), {HomeBubbleTip.playModes});
    });

    test('should_forgetEverything_when_allMarkedUnread', () async {
      final c = ProviderContainer(overrides: overrides());
      addTearDown(c.dispose);
      c.listen(tipReadProvider, (_, _) {});
      final tips = c.read(tipReadProvider.notifier);
      await tips.setRead(HomeBubbleTip.name, true);
      await tips.setAllUnread();
      expect(c.read(tipReadProvider), isEmpty);
      expect(await store.read(TipReadStore.key), isNull);
    });

    test('should_countReadTips_when_summarised', () {
      const strings = SettingsStrings.forLanguage(chinese: false);
      final tips = HomeBubbleTip.available();
      expect(tipsReadSummary(strings, {HomeBubbleTip.name}), '1 of ${tips.length} read');
      expect(tipsReadSummary(strings, tips.toSet()), 'All read');
    });

    testWidgets('should_checkTipAndOfferUnread_when_readOnItsPage', (tester) async {
      tester.view.physicalSize = const Size(800, 4000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(overrides: overrides(), child: const MaterialApp(home: TipsScreen())));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.text('Mark all as unread'), findsNothing);

      // A tap opens the tip on its own page; Read there marks it.
      await tester.tap(find.text('Behind the name'));
      await frames(tester, const Duration(seconds: 1));
      expect(find.byType(TipScreen), findsOneWidget);
      await tester.tap(find.descendant(of: find.byType(TipScreen), matching: find.text('Read')));
      await tester.runAsync(settle);
      await frames(tester, const Duration(milliseconds: 500));
      Navigator.of(tester.element(find.byType(TipScreen))).pop();
      await frames(tester, const Duration(seconds: 1));
      expect(find.byWidgetPredicate((w) => w is Icon && w.semanticLabel == 'Read'), findsOneWidget);
      expect(find.text('Mark all as unread'), findsOneWidget);
    });
  });

  group('licenses', () {
    test('should_listFontFirstThenPackagesByName_when_loaded', () async {
      final entries = Stream<LicenseEntry>.fromIterable([
        const LicenseEntryWithLineBreaks(['zeta'], 'Copyright Z\n\nZ terms'),
        const LicenseEntryWithLineBreaks(['alpha', 'zeta'], 'Copyright A'),
      ]);
      final components = await loadOssComponents(entries: entries);
      expect(components.map((c) => c.name), ['Google Sans Flex', 'alpha', 'zeta']);
      expect(components.first.text, contains('SIL Open Font License'));
      expect(components[2].summary, 'Copyright Z');
      expect(components[2].text, contains('Copyright A'));
    });

    test('should_matchPubspecVersion_when_shown', () {
      final pubspec = File('pubspec.yaml').readAsStringSync();
      final version = RegExp(r'^version:\s*([^+\s]+)(?:\+(\d+))?', multiLine: true).firstMatch(pubspec)!;
      expect(yoinVersionName, version.group(1));
      // Cloud sync writes the build number into every file it uploads.
      expect(yoinVersionCode, int.parse(version.group(2) ?? '1'));
    });

    testWidgets('should_openLicenseText_when_componentTapped', (tester) async {
      await tester.pumpWidget(ProviderScope(overrides: overrides(), child: const MaterialApp(home: LicensesScreen())));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Google Sans Flex'));
      await tester.pumpAndSettle();
      expect(find.byType(LicenseTextScreen), findsOneWidget);
      expect(find.textContaining('Open Font License'), findsWidgets);
    });
  });

  group('settings list', () {
    Future<void> pump(WidgetTester tester, Size size) async {
      tester.view.physicalSize = size;
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ProviderScope(
        overrides: overrides(),
        child: MaterialApp(
          builder: (context, child) => YoinWindowScope.measured(child: child!),
          home: const SettingsScreen(),
        ),
      ));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
    }

    testWidgets('should_showHelpAndAbout_when_opened', (tester) async {
      await pump(tester, const Size(400, 1600));
      expect(find.text('Help and about'), findsOneWidget);
      expect(find.text('Tips'), findsOneWidget);
      expect(find.text('Version $yoinVersionName'), findsOneWidget);
      expect(find.text('Open source licenses'), findsOneWidget);
      expect(find.text('No account selected'), findsNothing);
    });

    testWidgets('should_pushTips_when_narrowWindow', (tester) async {
      await pump(tester, const Size(400, 1600));
      await tester.tap(find.text('Tips'));
      await tester.pumpAndSettle();
      expect(find.byType(TipsScreen), findsOneWidget);
      expect(find.byType(SettingsScreen, skipOffstage: true), findsNothing);
    });

    testWidgets('should_openTipsInPane_when_wideWindow', (tester) async {
      await pump(tester, const Size(1280, 900));
      expect(find.text('No account selected'), findsOneWidget);

      await tester.tap(find.text('Tips'));
      await tester.pumpAndSettle();
      expect(find.byType(TipsScreen), findsOneWidget);
      expect(find.byType(SettingsScreen), findsOneWidget); // still on screen beside it
      expect(find.text('No account selected'), findsNothing);
      expect(find.text('Behind the name'), findsOneWidget);
    });

    testWidgets('should_manageAccountInPane_when_wideWindowAndActiveCardTapped', (tester) async {
      await tester.runAsync(() => ProfileRepository(store, store)
          .add(provider: MediaId.providerSubsonic, displayName: 'ann @ home.test', credentials: _home));
      await pump(tester, const Size(1280, 1000));

      await tester.tap(find.text('ann'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.byType(SubsonicSetupScreen), findsOneWidget);
      expect(find.byType(SettingsScreen), findsOneWidget);

      await tester.tap(find.widgetWithText(FilledButton, 'Save'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      // Saved: the pane goes back to its seal.
      expect(find.byType(SubsonicSetupScreen), findsNothing);
      expect(find.text('No account selected'), findsOneWidget);
    });
  });
}

const _home = SubsonicCredentials(baseUrl: 'https://home.test', username: 'ann', password: 'p');
