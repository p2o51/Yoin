import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/home/home_bubble_tips.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/tips.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/tips/demo_phone.dart';
import 'package:yoin/tips/tip_illustration.dart';
import 'package:yoin/tips/tip_screen.dart';
import 'package:yoin/ui/component/artwork_image.dart';

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

  setUp(() => store = InMemoryStore());

  group('read tips', () {
    test('should_keepTipRead_when_itsDayIsUnchanged', () {
      final read = {HomeBubbleTip.name, HomeBubbleTip.lyricsTools};
      expect(decodeReadTips(encodeReadTips(read)), read);
      expect(encodeReadTips({HomeBubbleTip.name}), ['name@2026-10-17']);
    });

    test('should_bringTipBackUnread_when_itChangedAfterItWasRead', () {
      expect(decodeReadTips(['name@2026-10-01', 'downloads@2026-11-04']), {HomeBubbleTip.downloads});
    });

    test('should_readBareNamesAsRead_when_tipsPredateDays', () {
      expect(decodeReadTips(['playModes', 'lyricsTools', 'gone']), {HomeBubbleTip.playModes});
    });

    test('should_findNextUnreadAfterCurrent_when_somePagesAreRead', () {
      const tips = [HomeBubbleTip.name, HomeBubbleTip.editHome, HomeBubbleTip.playModes];
      expect(nextUnreadTip(HomeBubbleTip.name, {HomeBubbleTip.editHome}, tips), HomeBubbleTip.playModes);
      expect(nextUnreadTip(HomeBubbleTip.playModes, const {}, tips), HomeBubbleTip.name);
      expect(nextUnreadTip(HomeBubbleTip.name, {HomeBubbleTip.editHome, HomeBubbleTip.playModes}, tips), isNull);
      expect(unreadTips({HomeBubbleTip.editHome}, tips), [HomeBubbleTip.name, HomeBubbleTip.playModes]);
    });

    test('should_leaveServiceWidgetsOut_when_onADesktop', () {
      expect(HomeBubbleTip.available('ohos'), HomeBubbleTip.values);
      expect(HomeBubbleTip.available('windows'), isNot(contains(HomeBubbleTip.homeScreenWidgets)));
      expect(HomeBubbleTip.available('windows').length, HomeBubbleTip.values.length - 1);
    });

    test('should_writeBothLanguages_when_anyTipIsShown', () {
      for (final tip in HomeBubbleTip.values) {
        for (final zh in [false, true]) {
          expect(tip.title(zh: zh), isNotEmpty);
          expect(tip.text(zh: zh), isNotEmpty);
        }
      }
    });

    test('should_paintDemoCovers_when_urlIsADemoCover', () {
      expect(DemoArtwork.parse(DemoArtwork.url(3)), const DemoArtwork(3));
      expect(DemoArtwork.parse('https://example.com/cover.jpg'), isNull);
    });
  });

  group('tip page', () {
    Future<void> pumpPage(WidgetTester tester, List<HomeBubbleTip> tips, HomeBubbleTip start) async {
      tester.view.physicalSize = const Size(800, 1600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(),
          child: MaterialApp(
            theme: yoinTheme(Brightness.light),
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => TipScreen(startTip: start, tips: tips),
                      ),
                    ),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('open'));
      await frames(tester, const Duration(seconds: 1));
    }

    testWidgets('should_markReadAndMoveToTheNextUnread_when_nextIsTapped', (tester) async {
      const tips = [HomeBubbleTip.name, HomeBubbleTip.editHome, HomeBubbleTip.playModes];
      await pumpPage(tester, tips, HomeBubbleTip.name);
      expect(find.text('1 / 3'), findsOneWidget);
      expect(find.text('Next'), findsOneWidget);
      await tester.tap(find.text('Next'));
      await frames(tester, const Duration(seconds: 2));
      expect(find.text('2 / 3'), findsOneWidget);
      final container = ProviderScope.containerOf(tester.element(find.byType(TipScreen)));
      expect(container.read(tipReadProvider), {HomeBubbleTip.name});
    });

    testWidgets('should_closeThePage_when_doneOnTheLastUnreadTip', (tester) async {
      const tips = [HomeBubbleTip.name, HomeBubbleTip.playModes];
      await pumpPage(tester, tips, HomeBubbleTip.playModes);
      final container = ProviderScope.containerOf(tester.element(find.byType(TipScreen)));
      await container.read(tipReadProvider.notifier).setRead(HomeBubbleTip.name, true);
      await frames(tester, const Duration(milliseconds: 300));
      expect(find.text('Done'), findsOneWidget);
      await tester.tap(find.text('Done'));
      await frames(tester, const Duration(seconds: 1));
      expect(find.byType(TipScreen), findsNothing);
      expect(container.read(tipReadProvider), {HomeBubbleTip.name, HomeBubbleTip.playModes});
    });

    testWidgets('should_toggleReadInPlace_when_readIsTapped', (tester) async {
      const tips = [HomeBubbleTip.name, HomeBubbleTip.editHome];
      await pumpPage(tester, tips, HomeBubbleTip.name);
      final container = ProviderScope.containerOf(tester.element(find.byType(TipScreen)));
      await tester.tap(find.text('Read'));
      await frames(tester, const Duration(milliseconds: 300));
      expect(container.read(tipReadProvider), {HomeBubbleTip.name});
      await tester.tap(find.text('Read'));
      await frames(tester, const Duration(milliseconds: 300));
      expect(container.read(tipReadProvider), isEmpty);
      expect(find.text('Updated Oct 17, 2026'), findsOneWidget);
    });
  });

  group('demo phone', () {
    Future<void> pumpDemo(WidgetTester tester, HomeBubbleTip tip, {bool zh = false}) async {
      tester.view.physicalSize = const Size(800, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        ProviderScope(
          overrides: overrides(),
          child: MaterialApp(
            theme: yoinTheme(Brightness.light),
            locale: zh ? const Locale('zh') : const Locale('en'),
            supportedLocales: const [Locale('en'), Locale('zh')],
            localizationsDelegates: GlobalMaterialLocalizations.delegates,
            home: Scaffold(
              body: Center(child: TipIllustration(tip: tip, playing: true)),
            ),
          ),
        ),
      );
    }

    for (final (tip, zh) in [
      for (final tip in HomeBubbleTip.values) ...[(tip, false), (tip, true)],
    ]) {
      testWidgets('should_playItsScriptWithoutErrors_when_${tip.name}DemoIsShown${zh ? 'InChinese' : ''}', (
        tester,
      ) async {
        await pumpDemo(tester, tip, zh: zh);
        // Two runs of the script: every step finds its part and every state change lands.
        await frames(tester, const Duration(seconds: 14));
        expect(tester.takeException(), isNull);
        // Leaving the page stops the demo cleanly.
        await tester.pumpWidget(const SizedBox());
        await frames(tester, const Duration(milliseconds: 500));
        expect(tester.takeException(), isNull);
      });
    }

    testWidgets('should_stepAside_when_theUserTouchesThePhone', (tester) async {
      await pumpDemo(tester, HomeBubbleTip.name);
      await frames(tester, const Duration(seconds: 1));
      final phone = find.byType(DemoPhone);
      await tester.tapAt(tester.getTopLeft(phone) + const Offset(20, 20));
      await frames(tester, const Duration(seconds: 2));
      expect(tester.takeException(), isNull);
      // Left alone again, the demo comes back.
      await frames(tester, const Duration(seconds: 6));
      expect(tester.takeException(), isNull);
    });
  });
}
