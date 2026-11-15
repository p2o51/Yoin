import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/component/corner_mask.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/media_id.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/detail/detail_page.dart' show DetailBackButton;
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/settings/license_packages.dart';
import 'package:yoin/settings/licenses.dart';
import 'package:yoin/settings/settings_screen.dart';
import 'package:yoin/settings/stream_quality_screen.dart';
import 'package:yoin/settings/subsonic_setup_screen.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

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
}
