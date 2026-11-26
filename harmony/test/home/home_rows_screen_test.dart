import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_rows_handle.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/home/jump_back_in.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

/// Edit Home's row presets on the phone (N = 2): the ⌟ handles, a drag that
/// writes a preset, the reader's steps, Undo, and a saved preset on load.
void main() {
  setUp(() {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
  });

  Future<({ProviderContainer container, InMemoryStore store, String profileId})> pumpHome(
    WidgetTester tester, {
    HomeLayout? saved,
    Size size = const Size(400, 2400),
    int grid = 20,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final store = InMemoryStore();
    final h = await homeContainer(
      store: store,
      source: HomeFakeSource(playlists: [playlist('p1')], recent: [album('r1')]),
      log: MemoryActivityLog([for (var i = 0; i < 8; i++) albumEvent('a$i', minutesAgo: i + 1)]),
    );
    final profiles = jsonDecode(store.values['profiles.v1']!) as List;
    final id = (profiles.single as Map)['id'] as String;
    store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(
      HomeGridPools(
        cachedAtMs: homeTestNow.millisecondsSinceEpoch,
        albums: [for (var i = 0; i < grid; i++) album('g$i')],
      ).toJson(),
    );
    if (saved != null) {
      store.values[HomeLayoutNotifier.keyFor(MediaId.providerSubsonic, id)] = saved.encode();
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
    return (container: h.container, store: store, profileId: id);
  }

  Future<void> done(WidgetTester tester, ProviderContainer c) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 1));
    c.dispose();
  }

  HomeEditPlate plateOf(WidgetTester tester, HomeSection section) =>
      tester.widget<HomeEditPlate>(find.byWidgetPredicate((w) => w is HomeEditPlate && w.section == section));

  int covers(WidgetTester tester) => find.byType(JbiCoverCell).evaluate().length;

  testWidgets('should_showRowsHandles_when_editing', (tester) async {
    final h = await pumpHome(tester);
    expect(find.byKey(homeRowsHandleKey(HomeSection.jumpBackIn)), findsNothing);
    h.container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    expect(find.byKey(homeRowsHandleKey(HomeSection.jumpBackIn)), findsOneWidget);
    expect(find.byKey(homeRowsHandleKey(HomeSection.activities)), findsOneWidget);
    // Shelves without presets have none.
    expect(find.byKey(homeRowsHandleKey(HomeSection.recentlyPlayed)), findsNothing);
    // The phone shelf at L: 4 rows.
    expect(plateOf(tester, HomeSection.jumpBackIn).rowCount, 4);
    expect(plateOf(tester, HomeSection.jumpBackIn).onMoreRows, isNotNull);
    expect(plateOf(tester, HomeSection.jumpBackIn).onFewerRows, isNotNull);
    await done(tester, h.container);
  });

  testWidgets('should_writeFewerRowsAndUndo_when_handleDraggedUp', (tester) async {
    final h = await pumpHome(tester);
    final c = h.container;
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    expect(covers(tester), 12);
    final handle = find.byKey(homeRowsHandleKey(HomeSection.jumpBackIn));
    final gesture = await tester.startGesture(tester.getCenter(handle));
    await tester.pump(const Duration(milliseconds: 16));
    // Up a row and a half, in small steps so each neighbouring stop is built and measured.
    for (var i = 0; i < 12; i++) {
      await gesture.moveBy(const Offset(0, -20));
      await tester.pump(const Duration(milliseconds: 16));
    }
    // A still finger: no fling.
    await tester.pump(const Duration(milliseconds: 200));
    await gesture.up();
    await frames(tester, 40);
    final rows = c.read(homeLayoutProvider).rowsOf(HomeSection.jumpBackIn);
    expect(rows.index, lessThan(HomeRowPreset.l.index));
    expect(covers(tester), lessThan(12));
    expect(tester.takeException(), isNull);
    // Saved for the account at once.
    final saved = decodeHomeLayout(h.store.values[HomeLayoutNotifier.keyFor(MediaId.providerSubsonic, h.profileId)]!);
    expect(saved.rowsOf(HomeSection.jumpBackIn), rows);
    // Undo springs it back.
    c.read(homeEditProvider.notifier).barLeftSlot(); // the bar's Undo
    await frames(tester, 40);
    expect(c.read(homeLayoutProvider).rowsOf(HomeSection.jumpBackIn), HomeRowPreset.l);
    expect(covers(tester), 12);
    await done(tester, c);
  });

  testWidgets('should_grabTheHandle_when_fingerLandsOnItsStripBelowTheSection', (tester) async {
    final h = await pumpHome(tester);
    final c = h.container;
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    final handle = tester.getRect(find.byKey(homeRowsHandleKey(HomeSection.jumpBackIn)));
    final plate = tester.getRect(
      find.byWidgetPredicate((w) => w is HomeEditPlate && w.section == HomeSection.jumpBackIn),
    );
    // The ⌟'s bottom stroke: inside the touch box, below the section's own box.
    final at = Offset(handle.right - 18, handle.bottom - 4);
    expect(at.dy, greaterThan(plate.bottom));
    final gesture = await tester.startGesture(at);
    await tester.pump(const Duration(milliseconds: 16));
    for (var i = 0; i < 12; i++) {
      await gesture.moveBy(const Offset(0, -20));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await tester.pump(const Duration(milliseconds: 200));
    await gesture.up();
    await frames(tester, 40);
    // Still editing (not a tap on the page), and the shelf took fewer rows.
    expect(c.read(homeEditProvider).editing, isTrue);
    expect(c.read(homeLayoutProvider).rowsOf(HomeSection.jumpBackIn).index, lessThan(HomeRowPreset.l.index));
    expect(tester.takeException(), isNull);
    await done(tester, c);
  });

  testWidgets('should_stepOnePreset_when_readerAsksForMoreRows', (tester) async {
    final h = await pumpHome(tester);
    final c = h.container;
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    plateOf(tester, HomeSection.jumpBackIn).onMoreRows!();
    await frames(tester, 40);
    expect(c.read(homeLayoutProvider).rowsOf(HomeSection.jumpBackIn), HomeRowPreset.xl);
    // 20 covers on offer: XL seats 18.
    expect(covers(tester), 18);
    expect(plateOf(tester, HomeSection.jumpBackIn).rowCount, 6);
    expect(plateOf(tester, HomeSection.jumpBackIn).onMoreRows, isNull);
    plateOf(tester, HomeSection.activities).onFewerRows!();
    await frames(tester, 40);
    expect(c.read(homeLayoutProvider).rowsOf(HomeSection.activities), HomeRowPreset.m);
    // Reset puts every preset back.
    // At the tray's foot, past every section's plate (Made For You's placeholder among them).
    await tester.dragUntilVisible(find.text('Reset Home'), find.byType(HomeScreen), const Offset(0, -300));
    await frames(tester, 10);
    await tester.ensureVisible(find.text('Reset Home'));
    await frames(tester, 10);
    await tester.tap(find.text('Reset Home'));
    await frames(tester, 40);
    expect(c.read(homeLayoutProvider).rowsOf(HomeSection.jumpBackIn), HomeRowPreset.l);
    expect(c.read(homeLayoutProvider).rowsOf(HomeSection.activities), HomeRowPreset.l);
    expect(covers(tester), 12);
    await done(tester, c);
  });

  testWidgets('should_sayTheRows_when_readerFocusesThePlate', (tester) async {
    final semantics = tester.ensureSemantics();
    final h = await pumpHome(tester);
    h.container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    // The plate's label leads with its place and its rows (its cards' text follows).
    expect(find.bySemanticsLabel(RegExp(r'^Section 1 of \d+, 3 rows')), findsOneWidget);
    expect(find.bySemanticsLabel(RegExp(r'^Section 2 of \d+, 4 rows')), findsOneWidget);
    semantics.dispose();
    await done(tester, h.container);
  });

  testWidgets('should_renderTheSavedPreset_when_homeLoads', (tester) async {
    final saved = withSectionRows(HomeLayout.defaultLayout, HomeSection.jumpBackIn, HomeRowPreset.s);
    final h = await pumpHome(tester, saved: saved);
    // S on the phone: two rows of three.
    expect(covers(tester), 6);
    await done(tester, h.container);
  });

  testWidgets('should_resizeTheTemplatedShelfAndUnitBento_when_wide', (tester) async {
    final h = await pumpHome(tester, size: const Size(1280, 2400), grid: 28);
    final c = h.container;
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    final before = covers(tester);
    expect(find.byKey(homeRowsHandleKey(HomeSection.jumpBackIn)), findsOneWidget);
    plateOf(tester, HomeSection.jumpBackIn).onMoreRows!();
    await frames(tester, 10);
    plateOf(tester, HomeSection.activities).onFewerRows?.call();
    await frames(tester, 40);
    expect(c.read(homeLayoutProvider).rowsOf(HomeSection.jumpBackIn), HomeRowPreset.xl);
    expect(covers(tester), greaterThan(before));
    expect(tester.takeException(), isNull);
    // A drag on the wide shelf's handle, down a little and back: lands where it started.
    final gesture = await tester.startGesture(tester.getCenter(find.byKey(homeRowsHandleKey(HomeSection.jumpBackIn))));
    for (var i = 0; i < 3; i++) {
      await gesture.moveBy(const Offset(0, 10));
      await tester.pump(const Duration(milliseconds: 16));
    }
    for (var i = 0; i < 3; i++) {
      await gesture.moveBy(const Offset(0, -10));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await tester.pump(const Duration(milliseconds: 200));
    await gesture.up();
    await frames(tester, 40);
    expect(c.read(homeLayoutProvider).rowsOf(HomeSection.jumpBackIn), HomeRowPreset.xl);
    expect(tester.takeException(), isNull);
    await done(tester, c);
  });
}

Future<void> frames(WidgetTester tester, int n) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 32));
  }
}
