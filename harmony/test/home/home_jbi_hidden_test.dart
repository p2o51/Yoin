import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_jbi_hidden.dart';
import 'package:yoin/home/home_memory_signals.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_selection.dart';
import 'package:yoin/home/home_ui_state.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/home/jump_back_in.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

/// Jump Back In's "not now" (HomeJbiHidden.kt): edit mode's tap on a card
/// hides it for three days or a week, per account.
void main() {
  group('keys and expiry', () {
    test('should_keyCardsByWhatTheyOpen_when_hiding', () {
      expect(jbiHideKey(albumCard(album('a1'))), 'album:a1');
      expect(jbiHideKey(songCard(track('t1'))), 'song:t1');
      expect(
        jbiHideKey(
          HomeWidgetCard(
            stableId: '${memoryCardPrefix}subsonic:al:9',
            kind: HomeCardKind.album,
            title: 'M',
            subtitle: '',
            target: const HomeOpenMemory(4),
            signal: const JbiSignal(),
          ),
        ),
        'album:al:9',
      );
      expect(
        jbiHideKey(
          const HomeWidgetCard(
            stableId: 'x',
            kind: HomeCardKind.album,
            title: 'M',
            subtitle: '',
            target: HomeOpenMemory(4),
          ),
        ),
        'memory:4',
      );
    });

    test('should_dropHiddenCards_when_filteringTheShelf', () {
      final cards = [albumCard(album('a1')), albumCard(album('a2')), songCard(track('t1'))];
      expect(withoutHiddenJbi(cards, const {}), same(cards));
      expect(withoutHiddenJbi(cards, const {'album:a2', 'song:t1'}).map((c) => c.stableId), [cards.first.stableId]);
    });

    test('should_letTheNextCardIn_when_oneIsHidden', () {
      HomeWidgetCard memory(String raw) => HomeWidgetCard(
        stableId: '${memoryCardPrefix}subsonic:$raw',
        kind: HomeCardKind.album,
        title: raw,
        subtitle: '',
        target: const HomeOpenMemory(1),
        signal: const JbiSignal(),
      );
      final covers = [for (var i = 0; i < 30; i++) albumCard(album('c$i'))];
      final signals = HomeMemorySignals(scope: 's', memoryCards: [memory('m1'), memory('m2')]);
      List<String> compacts(List<HomeWidgetCard> shelf) => [
        for (final c in shelf)
          if (!c.expanded) c.stableId,
      ];
      final all = jbiShelfCards(covers, signals, const {});
      expect(all.first.stableId, '${memoryCardPrefix}subsonic:m1');
      expect(compacts(all), [for (final c in covers.take(homeGridMaxCompacts)) c.stableId]);
      // The memory and the first cover hidden: the next memory leads, the 29th cover steps up.
      final shelf = jbiShelfCards(covers, signals, const {'album:m1', 'album:c0'});
      expect(shelf.first.stableId, '${memoryCardPrefix}subsonic:m2');
      expect(compacts(shelf), [for (final c in covers.skip(1).take(homeGridMaxCompacts)) c.stableId]);
      // Another account's signals (a switch in flight): covers only.
      expect(jbiShelfCards(covers, null, const {}).every((c) => !c.expanded), isTrue);
    });

    test('should_expireAndScopePerAccount_when_timePasses', () {
      const day = Duration.millisecondsPerDay;
      final hidden = const JbiHidden()
          .withHidden('subsonic|p1', 'album:a1', 3 * day, nowMs: 0)
          .withHidden('subsonic|p2', 'album:a2', 7 * day, nowMs: 0);
      expect(hidden.keysAt('subsonic|p1', day), {'album:a1'});
      expect(hidden.keysAt('subsonic|p2', day), {'album:a2'});
      expect(hidden.keysAt('subsonic|p1', 3 * day), isEmpty);
      // A later write prunes what has run out.
      final later = hidden.withHidden('subsonic|p2', 'song:t1', 10 * day, nowMs: 4 * day);
      expect(later.until['subsonic|p1'], isNull);
      expect(JbiHidden.fromJson(later.toJson()).until, later.until);
      expect(JbiHidden.fromJson('[]').until, isEmpty);
      expect(
        const JbiHidden({
              'a': {'k': 1},
            })
            .mergedWith(
              const JbiHidden({
                'a': {'j': 2},
              }),
            )
            .until,
        {
          'a': {'k': 1, 'j': 2},
        },
      );
      expect(JbiHideSpan.threeDays.millis, 3 * day);
    });
  });

  group('on Home', () {
    setUp(() {
      HomeViewModel.clearProcessCache();
      HomeScreen.resetProcessState();
    });

    Future<ProviderContainer> pumpShelf(WidgetTester tester, InMemoryStore store) async {
      tester.view.physicalSize = const Size(400, 2000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final h = await homeContainer(
        store: store,
        source: HomeFakeSource(playlists: [playlist('p1')], recent: [album('r1')]),
        log: MemoryActivityLog([albumEvent('a0', minutesAgo: 1)]),
      );
      final profiles = jsonDecode(store.values['profiles.v1']!) as List;
      final id = (profiles.single as Map)['id'] as String;
      store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(
        HomeGridPools(
          cachedAtMs: homeTestNow.millisecondsSinceEpoch,
          albums: [for (var i = 0; i < 6; i++) album('g$i')],
        ).toJson(),
      );
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
      return h.container;
    }

    testWidgets('should_keepTheShelfAndFoldTheMenu_when_editModeComesAndGoes', (tester) async {
      final c = await pumpShelf(tester, InMemoryStore());
      final shelf = tester.element(find.byType(JumpBackInSection));
      final covers = find.byType(JbiCoverCell).evaluate().length;
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      // One structure in both modes: the shelf (and its state) stays.
      expect(identical(tester.element(find.byType(JumpBackInSection)), shelf), isTrue);
      await tester.tap(find.byType(JbiCoverCell).first);
      await frames(tester, 20);
      expect(find.text('Hide for 3 days'), findsOneWidget);
      // Edit mode ending folds the menu away; nothing is hidden.
      c.read(homeEditProvider.notifier).exit();
      await frames(tester, 30);
      expect(find.text('Hide for 3 days'), findsNothing);
      expect(find.byType(JbiCoverCell), findsNWidgets(covers));
      expect(identical(tester.element(find.byType(JumpBackInSection)), shelf), isTrue);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(seconds: 1));
      c.dispose();
    });

    testWidgets('should_hideACardForThreeDays_when_tappedWhileEditing', (tester) async {
      tester.view.physicalSize = const Size(400, 2000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final store = InMemoryStore();
      final h = await homeContainer(
        store: store,
        source: HomeFakeSource(playlists: [playlist('p1')], recent: [album('r1')]),
        log: MemoryActivityLog([albumEvent('a0', minutesAgo: 1)]),
      );
      final profiles = jsonDecode(store.values['profiles.v1']!) as List;
      final id = (profiles.single as Map)['id'] as String;
      store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(
        HomeGridPools(
          cachedAtMs: homeTestNow.millisecondsSinceEpoch,
          albums: [for (var i = 0; i < 6; i++) album('g$i')],
        ).toJson(),
      );
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
      final c = h.container;
      final before = find.byType(JbiCoverCell).evaluate().length;
      expect(before, greaterThan(1));
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      await tester.tap(find.byType(JbiCoverCell).first);
      await frames(tester, 20);
      expect(find.text('Hide for 3 days'), findsOneWidget);
      expect(find.text('Hide for a week'), findsOneWidget);
      await tester.tap(find.text('Hide for 3 days'));
      await frames(tester, 30);
      expect(find.byType(JbiCoverCell), findsNWidgets(before - 1));
      // Still in edit mode, and saved.
      expect(c.read(homeEditProvider).editing, isTrue);
      expect(store.values[JbiHiddenNotifier.storeKey], contains('album:'));
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(seconds: 1));
      c.dispose();
    });
  });
}

Future<void> frames(WidgetTester tester, int n) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 32));
  }
}
