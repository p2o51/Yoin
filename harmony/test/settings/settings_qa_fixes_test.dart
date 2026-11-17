import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/corner_mask.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/home/home_bubble_tips.dart';
import 'package:yoin/home/home_metrics.dart' show HomeSeamMetrics;
import 'package:yoin/home/widgets/seam.dart' show SeamFade;
import 'package:yoin/detail/detail_page.dart' show DetailBackButton;
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/license_packages.dart';
import 'package:yoin/settings/licenses.dart';
import 'package:yoin/settings/settings_components.dart';
import 'package:yoin/settings/settings_metrics.dart';
import 'package:yoin/settings/settings_strings.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/stream_quality_screen.dart';
import 'package:yoin/settings/subsonic_setup_screen.dart';
import 'package:yoin/settings/tips.dart';
import 'package:yoin/tips/tip_screen.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/seam/chrome_seam.dart';

import '../support/fake_music_source.dart';
import '../support/shipped_packages.dart';

const _home = SubsonicCredentials(baseUrl: 'https://home.test', username: 'ann', password: 'p');

void main() {
  late InMemoryStore store;

  List<Override> overrides() => [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(store),
        musicSourceFactoryProvider.overrideWithValue((_) => FakeMusicSource()),
      ];

  Future<void> settle() async {
    for (var i = 0; i < 10; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }

  setUp(() => store = InMemoryStore());

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

  Future<void> openAccount(WidgetTester tester, Size size) async {
    await tester.runAsync(() => ProfileRepository(store, store)
        .add(provider: MediaId.providerSubsonic, displayName: 'ann @ home.test', credentials: _home));
    await pump(tester, size);
    await tester.tap(find.text('ann'));
    await tester.runAsync(settle);
    await tester.pumpAndSettle();
  }

  group('stream quality', () {
    testWidgets('should_openInSettingsPane_when_wideWindow', (tester) async {
      await openAccount(tester, const Size(1920, 1200));
      expect(find.byType(SubsonicSetupScreen), findsOneWidget);

      await tester.ensureVisible(find.text('Streaming quality'));
      await tester.tap(find.text('Streaming quality'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();

      expect(find.byType(StreamQualityScreen), findsOneWidget);
      expect(find.byType(SettingsScreen), findsOneWidget, reason: 'the list stays beside it');
      expect(find.byType(SubsonicSetupScreen), findsNothing);
      // Width-capped by the pane: well under the window's width.
      final row = tester.getRect(find.text('Original').first);
      final pane = tester.getRect(find.byType(SettingsPanePanel));
      expect(pane.width, lessThan(1920 * 0.7));
      expect(row.left, greaterThan(pane.left));
      expect(row.right, lessThanOrEqualTo(pane.right));
    });

    Future<void> openQualityInPane(WidgetTester tester) async {
      await openAccount(tester, const Size(1920, 1200));
      await tester.ensureVisible(find.text('Streaming quality'));
      await tester.tap(find.text('Streaming quality'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.byType(StreamQualityScreen), findsOneWidget);
    }

    testWidgets('should_returnToAccountPage_when_paneBackButtonTapped', (tester) async {
      await openQualityInPane(tester);
      final back = find.descendant(of: find.byType(StreamQualityScreen), matching: find.byType(DetailBackButton));
      expect(back, findsOneWidget);
      await tester.tap(back);
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.byType(StreamQualityScreen), findsNothing);
      expect(find.byType(SubsonicSetupScreen), findsOneWidget);
      expect(find.byType(SettingsScreen), findsOneWidget);
    });

    testWidgets('should_returnToAccountPageNotLeaveSettings_when_systemBack', (tester) async {
      await openQualityInPane(tester);
      await tester.binding.handlePopRoute();
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.byType(StreamQualityScreen), findsNothing);
      expect(find.byType(SubsonicSetupScreen), findsOneWidget);
      expect(find.byType(SettingsScreen), findsOneWidget);
      // Back on a page the list opened, back is Settings' own again.
      final route = ModalRoute.of(tester.element(find.byType(SettingsScreen)))!;
      expect(route.popDisposition, isNot(RoutePopDisposition.doNotPop));
    });

    testWidgets('should_showNoPaneBack_when_accountPageInPane', (tester) async {
      await openAccount(tester, const Size(1920, 1200));
      expect(find.descendant(of: find.byType(SubsonicSetupScreen), matching: find.byType(DetailBackButton)), findsNothing);
    });

    testWidgets('should_pushPage_when_narrowWindow', (tester) async {
      await openAccount(tester, const Size(400, 1600));
      await tester.ensureVisible(find.text('Streaming quality'));
      await tester.tap(find.text('Streaming quality'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.byType(StreamQualityScreen), findsOneWidget);
      expect(find.byType(SettingsPanePanel), findsNothing);
    });
  });

  group('licenses pane', () {
    testWidgets('should_clipPageToPanelCorners_when_licensesInPane', (tester) async {
      await pump(tester, const Size(1280, 900));
      // Features and Sync and storage push Help and about below the fold.
      await tester.ensureVisible(find.text('Open source licenses'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Open source licenses'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      expect(find.byType(LicensesScreen), findsOneWidget);
      // Corners are painted over (CornerMask), never clipped (HarmonyOS clip seams).
      final clip = find.ancestor(of: find.byType(LicensesScreen), matching: find.byType(CornerMask));
      expect(clip, findsOneWidget);
      // The page colour is painted under the mask.
      expect(find.ancestor(of: clip, matching: find.byType(ColoredBox)), findsWidgets);
      // The scroll clips square to the pane (hard edge, no seam); the mask rounds it.
      final list = tester.widget<ListView>(
        find.descendant(of: find.byType(LicensesScreen), matching: find.byType(ListView)),
      );
      expect(list.clipBehavior, Clip.hardEdge);
    });
  });

  group('licenses list', () {
    test('should_leaveOutDevOnlyPackages_when_loaded', () async {
      final entries = Stream<LicenseEntry>.fromIterable([
        const LicenseEntryWithLineBreaks(['analyzer'], 'Copyright Dart'),
        const LicenseEntryWithLineBreaks(['_fe_analyzer_shared', 'riverpod'], 'Copyright R'),
        const LicenseEntryWithLineBreaks(['skia'], 'Copyright Skia'),
      ]);
      final components = await loadOssComponents(entries: entries);
      expect(components.map((c) => c.name), ['Google Sans Flex', 'riverpod', 'skia']);
    });

    test('should_matchLockMinusImportClosure_when_dependenciesChange', () {
      final notShipped = lockedDartPackages().difference(shippedDartPackages());
      final sorted = notShipped.toList()..sort();
      expect(
        notShippedDartPackages,
        notShipped,
        reason: 'regenerate lib/settings/license_packages.dart with: ${sorted.map((n) => "'$n'").join(', ')}',
      );
      expect(notShippedDartPackages, isNot(contains('flutter')));
      expect(notShippedDartPackages, containsAll(['analyzer', '_fe_analyzer_shared']));
    });
  });

  group('tips count', () {
    testWidgets('should_countTipAsRead_when_tippedInTipsAndBackInSettings', (tester) async {
      await pump(tester, const Size(400, 1600));
      const strings = SettingsStrings.forLanguage(chinese: false);
      expect(find.text(tipsReadSummary(strings, const {})), findsOneWidget);
      // Opening the page alone reads nothing (Android: a tip is read on its own page).
      await tester.tap(find.text('Tips'));
      await tester.pumpAndSettle();
      final tip = HomeBubbleTip.available().first;
      await tester.tap(find.text(tip.title(zh: false)));
      for (var i = 0; i < 60; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      await tester.tap(find.descendant(of: find.byType(TipScreen), matching: find.text('Read')));
      await tester.runAsync(settle);
      await tester.pump(const Duration(milliseconds: 500));
      Navigator.of(tester.element(find.byType(TipScreen))).pop();
      for (var i = 0; i < 60; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      Navigator.of(tester.element(find.byType(TipsScreen))).pop();
      await tester.pumpAndSettle();
      expect(find.text(tipsReadSummary(strings, {tip})), findsOneWidget);
    });
  });

  group('seam', () {
    test('should_growBandWithScroll_when_scrolledFromRest', () {
      expect(SettingsSeam.reveal(0), 0);
      expect(SettingsSeam.reveal(SettingsMetrics.seamRevealDistance / 2), 0.5);
      expect(SettingsSeam.reveal(400), 1);
    });

    testWidgets('should_drawTideAndFadeText_when_accountPageScrolled', (tester) async {
      await tester.runAsync(() => ProfileRepository(store, store)
          .add(provider: MediaId.providerSubsonic, displayName: 'ann @ home.test', credentials: _home));
      // A short portrait window (under 480 high the page splits into two columns).
      await pump(tester, const Size(400, 500));
      await tester.tap(find.text('ann'));
      await tester.runAsync(settle);
      await tester.pumpAndSettle();
      final page = find.byType(SubsonicSetupScreen);
      Iterable<ShaderMaskLayer> masks() sync* {
        Iterable<Layer> walk(Layer? l) sync* {
          for (var c = l; c != null; c = c.nextSibling) {
            yield c;
            if (c is ContainerLayer) yield* walk(c.firstChild);
          }
        }
        yield* walk(tester.layers.first).whereType<ShaderMaskLayer>();
      }

      expect(find.descendant(of: page, matching: find.byType(SettingsSeam)), findsOneWidget);
      final tide = find.descendant(
        of: page,
        matching: find.byWidgetPredicate((w) => w is CustomPaint && w.foregroundPainter is ChromeTidePainter),
      );
      final painter = tester.widget<CustomPaint>(tide).foregroundPainter! as ChromeTidePainter;
      void paintTide(Canvas canvas) => painter.paint(canvas, const Size(400, 600));
      expect(painter.seam.reveal, 0);
      expect(masks(), isEmpty, reason: 'nothing masked at rest');
      expect(paintTide, isNot(paints..path()), reason: 'no tide at rest');

      // Scroll so a text sits just under the water line: it fades (seamFade), and the tide is drawn.
      final seamTop = tester.getTopLeft(find.descendant(of: page, matching: find.byType(SettingsSeam))).dy;
      final position = tester.state<ScrollableState>(find.descendant(of: page, matching: find.byType(Scrollable)).first).position;
      final reach = [
        for (final e in find.descendant(of: page, matching: find.byType(SeamFade)).evaluate())
          tester.getTopLeft(find.byWidget(e.widget)).dy - seamTop - chromeTideTextSeam(1) - 1,
      ].firstWhere((d) => d > HomeSeamMetrics.revealDistance && d <= position.maxScrollExtent);
      position.jumpTo(reach);
      await tester.pumpAndSettle();
      expect(painter.seam.reveal, greaterThan(0));
      expect(paintTide, paints..path()..path(), reason: 'two waves, the back one half clear');
      expect(masks(), isNotEmpty, reason: 'text under the line fades in the item');
      expect(tester.takeException(), isNull);
    });
  });
}
