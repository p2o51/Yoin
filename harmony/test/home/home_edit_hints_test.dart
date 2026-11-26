import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_edit_hints.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

/// A layout saved before Rediscover existed: on read it is appended hidden, as "New".
List<HomeSectionPref> _legacyPrefs() => [
  for (final s in HomeSection.values)
    if (s != HomeSection.rediscover) HomeSectionPref(id: s.id, enabled: true),
];

String _profileId(InMemoryStore store) =>
    ((jsonDecode(store.values['profiles.v1']!) as List).single as Map)['id'] as String;

void main() {
  group('new sections', () {
    test('should_markAppendedHiddenSectionsNew_when_savedLayoutNeverKnewThem', () {
      final layout = HomeLayout.reconcile(_legacyPrefs());
      expect(layout.newSections, {HomeSection.rediscover});
      expect(hiddenSections(layout), [HomeSection.rediscover]);
      // Appended enabled, or already known: not new.
      expect(HomeLayout.reconcile(HomeLayout.defaultLayout.toPrefs()).newSections, isEmpty);
      expect(HomeLayout.defaultLayout.newSections, isEmpty);
    });

    test('should_keepNewSections_when_layoutEdited', () {
      final layout = HomeLayout.reconcile(_legacyPrefs());
      final hidden = withSectionEnabled(layout, HomeSection.jumpBackIn, false);
      expect(hidden.newSections, {HomeSection.rediscover});
      expect(withSectionMoved(hidden, hidden.visibleV1Sections, HomeSection.yourPlaylists, 0).newSections, {
        HomeSection.rediscover,
      });
      expect(withSectionRows(layout, HomeSection.activities, HomeRowPreset.s).newSections, {HomeSection.rediscover});
      // Reset is the catalogue arrangement: nothing waits in the tray.
      expect(resetLayout(layout).newSections, isEmpty);
    });

    test('should_leaveSeenSectionsOut_when_listingUnseen', () {
      final layout = HomeLayout.reconcile(_legacyPrefs());
      expect(unseenNewSections(layout, const {}), {HomeSection.rediscover});
      expect(unseenNewSections(layout, const {'rediscover'}), isEmpty);
    });

    test('should_showHeaderHintOnlyFirstTwoSessions_when_counted', () {
      expect([for (var i = 0; i < 3; i++) showEditHeaderHint(i)], [true, true, false]);
    });
  });

  group('hint store', () {
    Future<ProviderContainer> container(InMemoryStore store) async {
      final c = ProviderContainer(overrides: [keyValueStoreProvider.overrideWithValue(store)]);
      addTearDown(c.dispose);
      c.listen(homeEditHintsProvider, (_, _) {});
      await settle(() => c.read(homeEditHintsProvider).loaded);
      return c;
    }

    test('should_snapshotHintsBeforeRecording_when_editSessionStarts', () async {
      final store = InMemoryStore()
        ..values[HomeEditHintsNotifier.storageKey] = jsonEncode({'sessions': 1, 'seen': <String>[]});
      final c = await container(store);
      final layout = HomeLayout.reconcile(_legacyPrefs());
      final hints = c.read(homeEditHintsProvider.notifier);

      // One session so far → this one still shows the hint; Rediscover is unseen.
      expect(
        hints.startSession(layout),
        const HomeEditSessionHints(showHeaderHint: true, newBadges: {HomeSection.rediscover}),
      );
      expect(c.read(homeEditHintsProvider).sessions, 2);
      expect(c.read(homeEditHintsProvider).seen, {'rediscover'});
      // Seen once is seen: the next session badges nothing.
      expect(hints.startSession(layout), const HomeEditSessionHints());
      await settle(() => false, rounds: 5);
      final saved = jsonDecode(store.values[HomeEditHintsNotifier.storageKey]!) as Map;
      expect(saved['sessions'], 3);
      expect(saved['seen'], ['rediscover']);
    });

    test('should_keepEarlySessionButBadgeNothing_when_storedRecordLandsLater', () async {
      final store = InMemoryStore()
        ..values[HomeEditHintsNotifier.storageKey] = jsonEncode({
          'sessions': 4,
          'seen': ['memory_grid', 'rediscover'],
        });
      final c = ProviderContainer(overrides: [keyValueStoreProvider.overrideWithValue(store)]);
      addTearDown(c.dispose);
      c.listen(homeEditHintsProvider, (_, _) {});
      // Before the read lands: this device may well have met Rediscover already, so no chip.
      final hints = c.read(homeEditHintsProvider.notifier).startSession(HomeLayout.reconcile(_legacyPrefs()));
      expect(hints.newBadges, isEmpty);
      await settle(() => c.read(homeEditHintsProvider).loaded);
      expect(c.read(homeEditHintsProvider).sessions, 5);
      expect(c.read(homeEditHintsProvider).seen, {'memory_grid', 'rediscover'});
      await settle(() => false, rounds: 5);
      expect((jsonDecode(store.values[HomeEditHintsNotifier.storageKey]!) as Map)['sessions'], 5);
    });

    test('should_leaveSectionUnseen_when_sessionStartsBeforeRecordLands', () async {
      final store = InMemoryStore()..values[HomeEditHintsNotifier.storageKey] = jsonEncode({'sessions': 1, 'seen': []});
      final c = ProviderContainer(overrides: [keyValueStoreProvider.overrideWithValue(store)]);
      addTearDown(c.dispose);
      c.listen(homeEditHintsProvider, (_, _) {});
      final layout = HomeLayout.reconcile(_legacyPrefs());
      expect(c.read(homeEditHintsProvider.notifier).startSession(layout).newBadges, isEmpty);
      await settle(() => c.read(homeEditHintsProvider).loaded);
      // Not marked seen unshown: the next session carries the chip.
      expect(c.read(homeEditHintsProvider).seen, isEmpty);
      expect(c.read(homeEditHintsProvider.notifier).startSession(layout).newBadges, {HomeSection.rediscover});
    });

    test('should_loadHintRecordWithHome_when_editControllerBuilt', () async {
      final store = InMemoryStore()
        ..values[HomeEditHintsNotifier.storageKey] = jsonEncode({
          'sessions': 3,
          'seen': ['rediscover'],
        });
      final c = ProviderContainer(overrides: [keyValueStoreProvider.overrideWithValue(store)]);
      addTearDown(c.dispose);
      // Home holds the edit controller from its first frame; nothing reads the hints yet.
      c.listen(homeEditProvider, (_, _) {});
      await settle(() => c.exists(homeEditHintsProvider) && c.read(homeEditHintsProvider).loaded);
      expect(c.read(homeEditHintsProvider).sessions, 3);
    });

    test('should_startOver_when_storedRecordUnreadable', () async {
      final store = InMemoryStore()..values[HomeEditHintsNotifier.storageKey] = 'not json';
      final c = await container(store);
      expect(c.read(homeEditHintsProvider).sessions, 0);
      expect(c.read(homeEditHintsProvider).seen, isEmpty);
    });
  });

  group('on Home', () {
    setUp(() {
      HomeViewModel.clearProcessCache();
      HomeScreen.resetProcessState();
    });

    Future<(ProviderContainer, InMemoryStore)> pumpHome(WidgetTester tester, {bool legacy = true}) async {
      tester.view.physicalSize = const Size(400, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final store = InMemoryStore();
      final h = await homeContainer(
        store: store,
        source: HomeFakeSource(playlists: [playlist('p1')], recent: [album('r1')]),
        log: MemoryActivityLog([albumEvent('a0', minutesAgo: 1)]),
      );
      final id = _profileId(store);
      store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(
        HomeGridPools(
          cachedAtMs: homeTestNow.millisecondsSinceEpoch,
          albums: [for (var i = 0; i < 6; i++) album('g$i')],
        ).toJson(),
      );
      if (legacy) {
        store.values[HomeLayoutNotifier.keyFor(MediaId.providerSubsonic, id)] = jsonEncode({
          'version': 1,
          'sections': [for (final p in _legacyPrefs()) HomeLayoutCodec.encodeEntry(p)],
        });
      }
      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: h.container,
          child: MaterialApp(
            theme: yoinTheme(Brightness.light),
            builder: (context, child) => YoinWindowScope.measured(child: child!),
            home: const Scaffold(body: HomeScreen()),
          ),
        ),
      );
      await frames(tester, 30);
      return (h.container, store);
    }

    Future<void> done(WidgetTester tester, ProviderContainer c) async {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(seconds: 1));
      c.dispose();
    }

    Finder chipIn(Finder of) => find.descendant(of: of, matching: find.byType(HomeEditNewChip));

    testWidgets('should_badgeFooterAndTrayRow_when_newSectionWaits', (tester) async {
      final (c, store) = await pumpHome(tester);
      await tester.dragUntilVisible(find.byType(HomeEditFooter), find.byType(HomeScreen), const Offset(0, -300));
      expect(chipIn(find.byType(HomeEditFooter)), findsOneWidget);
      expect(find.descendant(of: find.byType(HomeEditFooter), matching: find.text('New')), findsOneWidget);

      await tester.tap(find.text('Edit Home').last);
      await frames(tester, 30);
      expect(c.read(homeEditProvider).editing, isTrue);
      expect(c.read(homeEditProvider).hints.newBadges, {HomeSection.rediscover});
      await tester.dragUntilVisible(find.byType(HomeEditTrayRow), find.byType(HomeScreen), const Offset(0, -300));
      final row = find.widgetWithText(HomeEditTrayRow, 'Rediscover');
      expect(chipIn(row), findsOneWidget);
      // Marked seen at once, on this device.
      await frames(tester, 5);
      expect((jsonDecode(store.values[HomeEditHintsNotifier.storageKey]!) as Map)['seen'], ['rediscover']);

      // The whole row puts the section back.
      await tester.tap(row);
      await frames(tester, 20);
      expect(c.read(homeLayoutProvider).visibleV1Sections, contains(HomeSection.rediscover));
      await done(tester, c);
    });

    testWidgets('should_dropTheChips_when_nextSessionStarts', (tester) async {
      final (c, _) = await pumpHome(tester);
      final edit = c.read(homeEditProvider.notifier);
      edit.enter();
      await frames(tester, 30);
      edit.done();
      await frames(tester, 30);
      // The footer has nothing new to show any more.
      expect(c.read(homeEditFooterNewProvider), isFalse);
      await tester.dragUntilVisible(find.byType(HomeEditFooter), find.byType(HomeScreen), const Offset(0, -300));
      expect(chipIn(find.byType(HomeEditFooter)), findsNothing);

      edit.enter();
      await frames(tester, 30);
      expect(c.read(homeEditProvider).hints.newBadges, isEmpty);
      await tester.dragUntilVisible(find.byType(HomeEditTrayRow), find.byType(HomeScreen), const Offset(0, -300));
      expect(find.byType(HomeEditTrayRow), findsOneWidget, reason: 'Rediscover still waits in the tray');
      expect(find.byType(HomeEditNewChip), findsNothing);
      await done(tester, c);
    });

    testWidgets('should_offerResetOnlyAwayFromDefault_when_trayShown', (tester) async {
      final (c, _) = await pumpHome(tester, legacy: false);
      final edit = c.read(homeEditProvider.notifier);
      edit.enter();
      await frames(tester, 30);
      edit.hide(HomeSection.yourPlaylists);
      await frames(tester, 30);
      TextButton reset() => tester.widget<TextButton>(find.widgetWithText(TextButton, 'Reset Home'));
      await tester.dragUntilVisible(find.text('Reset Home'), find.byType(HomeScreen), const Offset(0, -300));
      expect(reset().onPressed, isNotNull);
      edit.undo();
      await frames(tester, 30);
      expect(c.read(homeLayoutProvider).isDefault, isTrue);
      expect(reset().onPressed, isNull, reason: 'already the default arrangement');
      await done(tester, c);
    });

    testWidgets('should_showNoChip_when_layoutHasNothingNew', (tester) async {
      final (c, _) = await pumpHome(tester, legacy: false);
      await tester.dragUntilVisible(find.byType(HomeEditFooter), find.byType(HomeScreen), const Offset(0, -300));
      expect(find.byType(HomeEditNewChip), findsNothing);
      await done(tester, c);
    });
  });
}

Future<void> frames(WidgetTester tester, int n) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 32));
  }
}
