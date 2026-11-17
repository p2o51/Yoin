import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/activity_bento.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/home/home_shelves.dart';
import 'package:yoin/home/jump_back_in.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

/// Home on Medium / Wide containers: the density compositions lay out at
/// every resting width and re-seat in place when the width class changes.
void main() {
  setUp(() {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
  });

  Future<ProviderContainer> richHome(WidgetTester tester, Size logical) async {
    tester.view.physicalSize = logical;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final store = InMemoryStore();
    final starred = Starred(
      tracks: [for (var i = 0; i < 16; i++) track('s$i', starred: '2026-11-1${i % 3}T00:00:00Z')],
      albums: [for (var i = 0; i < 20; i++) album('n$i', starred: '2026-11-0${1 + i % 9}T00:00:00Z')],
    );
    final h = await homeContainer(
      store: store,
      source: HomeFakeSource(starred: starred, playlists: [playlist('p1')], recent: [album('r1')]),
      log: MemoryActivityLog([
        albumEvent('a0', minutesAgo: 1),
        for (var i = 1; i < 14; i++) i.isEven ? albumEvent('a$i', minutesAgo: i * 3) : playlistEvent('p$i', minutesAgo: i * 3),
      ]),
    );
    final profiles = jsonDecode(store.values['profiles.v1']!) as List;
    final id = (profiles.single as Map)['id'] as String;
    store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(
      HomeGridPools(cachedAtMs: homeTestNow.millisecondsSinceEpoch, albums: [for (var i = 0; i < 14; i++) album('g$i')], tracks: [
        for (var i = 0; i < 10; i++) track('t$i'),
      ]).toJson(),
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
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    return h.container;
  }

  Future<void> tearDown(WidgetTester tester, ProviderContainer container) async {
    await tester.pumpWidget(const SizedBox.shrink());
    container.dispose();
  }

  double leftOf(WidgetTester tester, Finder f) => tester.getTopLeft(f.first).dx;

  for (final (width, height, heroLeft) in [
    (360.0, 800.0, 16.0),
    (600.0, 960.0, 16.0),
    (800.0, 1280.0, 56.0),
    (1280.0, 800.0, 32.0),
    (1440.0, 900.0, 32.0),
  ]) {
    testWidgets('should_layOutWithoutOverflow_when_${width.toInt()}x${height.toInt()}', (tester) async {
      final container = await richHome(tester, Size(width, height));
      expect(tester.takeException(), isNull);
      expect(find.byType(ActivityHeroCard), findsOneWidget);
      expect(leftOf(tester, find.byType(ActivityHeroCard)), closeTo(heroLeft, 0.01));
      expect(find.byType(JumpBackInSection), findsOneWidget);
      await tester.scrollUntilVisible(find.byType(RecentlyAddedSection), 300, scrollable: find.byType(Scrollable).first);
      expect(tester.takeException(), isNull);
      await tearDown(tester, container);
    });
  }

  testWidgets('should_seatUnitGridCards_when_wide', (tester) async {
    final container = await richHome(tester, const Size(1280, 800));
    // N = 8: hero + 12 supporting cards (13), the hero picked from the album / playlist entries.
    final cards = find.byType(ActivityWideCard).evaluate().length +
        find.byType(ActivitySmallCard).evaluate().length +
        find.byType(ActivityStripCard).evaluate().length +
        find.byType(ActivityHeroCard).evaluate().length;
    expect(cards, 13);
    // Jump Back In: 9 lanes × 2 rows of covers, all the same size.
    final covers = find.descendant(of: find.byType(JumpBackInSection), matching: find.byType(JbiCoverCell));
    expect(covers, findsNWidgets(18));
    final sizes = covers.evaluate().map((e) => tester.getSize(find.byWidget(e.widget)).width).toSet();
    expect(sizes, hasLength(1));
    // Lanes stagger: not every cover in the first row starts at the same height.
    final tops = covers.evaluate().map((e) => tester.getTopLeft(find.byWidget(e.widget)).dy).toSet();
    expect(tops.length, greaterThan(2));
    await tearDown(tester, container);
  });

  testWidgets('should_keepPhoneComposition_when_phoneWidth', (tester) async {
    final container = await richHome(tester, const Size(393, 852));
    // Hero + small + wide + strip; 3 × 4 Jump Back In.
    expect(find.byType(ActivitySmallCard), findsOneWidget);
    expect(find.byType(ActivityWideCard), findsOneWidget);
    expect(find.byType(ActivityStripCard), findsOneWidget);
    final covers = find.descendant(of: find.byType(JumpBackInSection), matching: find.byType(JbiCoverCell));
    expect(covers.evaluate().length, lessThanOrEqualTo(12));
    await tearDown(tester, container);
  });

  testWidgets('should_reseatInPlace_when_widthClassChanges', (tester) async {
    final container = await richHome(tester, const Size(1280, 800));
    expect(leftOf(tester, find.byType(ActivityHeroCard)), closeTo(32, 0.01));
    // Rotate to portrait: Wide → Capped, N 8 → 4; margins blend, then rest at 56.
    tester.view.physicalSize = const Size(800, 1280);
    await tester.pump();
    expect(tester.takeException(), isNull);
    expect(find.byType(ActivityHeroCard), findsOneWidget);
    for (var i = 0; i < 40; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    expect(tester.takeException(), isNull);
    expect(leftOf(tester, find.byType(ActivityHeroCard)), closeTo(56, 0.01));
    final covers = find.descendant(of: find.byType(JumpBackInSection), matching: find.byType(JbiCoverCell));
    expect(covers, findsNWidgets(15)); // 5 lanes × 3 rows
    await tearDown(tester, container);
  });
}
