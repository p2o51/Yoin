import 'dart:convert';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/home/home_grid_rules.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_edit_list.dart';
import 'package:yoin/home/home_feed_density.dart';
import 'package:yoin/home/home_feed_rows.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_grid_resize.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

/// Two-column Home (parity p4 C2; Android HomeFeedRows.kt, design.md §主页「两栏」): the tier, rows of full and
/// half sections at exact grid heights, the drag beside a section, the ⌟'s span and units, the draft following
/// the width, and Reset to the large-screen default.
void main() {
  setUp(() {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
  });

  group('tier', () {
    test('should_layOutInTwoColumns_when_wideAndTallAndFlat', () {
      // MatePad landscape, a Mate XT fully open, a PC window.
      expect(homeFeedTwoColumn(const YoinWindowInfo(1280, 800)), isTrue);
      expect(homeFeedTwoColumn(const YoinWindowInfo(1440, 1000)), isTrue);
      expect(homeFeedTwoColumn(const YoinWindowInfo(840, 480)), isTrue);
      // A Mate X inner screen and a portrait tablet (Medium), a landscape handset, tabletop.
      expect(homeFeedTwoColumn(const YoinWindowInfo(800, 1280)), isFalse);
      expect(homeFeedTwoColumn(const YoinWindowInfo(839, 900)), isFalse);
      expect(homeFeedTwoColumn(const YoinWindowInfo(915, 412)), isFalse);
      expect(homeFeedTwoColumn(const YoinWindowInfo(1280, 800, isTabletop: true, hingeBounds: Rect.zero)), isFalse);
    });

    test('should_readItsOwnWidth_when_aCellIsHalfTheRow', () {
      // A half of a 1280 tablet (598) is a Medium column: 4 units; a full row is Wide.
      expect(homeColumnDensity(598).units, 4);
      expect(homeColumnDensity(1216).units, feedUnitsFor(1216, wide: true));
      expect(homeColumnDensity(1216).frameClass, FeedFrameClass.wide);
    });
  });

  Future<({ProviderContainer container, InMemoryStore store})> pumpHome(
    WidgetTester tester, {
    Size size = const Size(1280, 800),
    bool tabletop = false,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final store = InMemoryStore();
    final h = await homeContainer(
      store: store,
      source: HomeFakeSource(playlists: [playlist('p1'), playlist('p2')], recent: [album('r1'), album('r2')]),
      log: MemoryActivityLog([for (var i = 0; i < 5; i++) albumEvent('a$i', minutesAgo: 1 + i)]),
    );
    final profiles = jsonDecode(store.values['profiles.v1']!) as List;
    final id = (profiles.single as Map)['id'] as String;
    store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(
      HomeGridPools(
        cachedAtMs: homeTestNow.millisecondsSinceEpoch,
        albums: [for (var i = 0; i < 8; i++) album('g$i')],
      ).toJson(),
    );
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: h.container,
        child: MaterialApp(
          theme: yoinTheme(Brightness.light),
          builder: (context, child) => tabletop
              ? LayoutBuilder(
                  builder: (context, c) => YoinWindowScope(
                    info: YoinWindowInfo(
                      c.maxWidth,
                      c.maxHeight,
                      isTabletop: true,
                      hingeBounds: Rect.fromLTWH(0, c.maxHeight / 2 - 10, c.maxWidth, 20),
                    ),
                    child: child!,
                  ),
                )
              : YoinWindowScope.measured(child: child!),
          home: const Scaffold(body: HomeScreen()),
        ),
      ),
    );
    await frames(tester, 30);
    return (container: h.container, store: h.store);
  }

  Future<void> done(WidgetTester tester, ProviderContainer c) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 1));
    c.dispose();
  }

  Finder plate(HomeSection s) => find.byWidgetPredicate((w) => w is HomeEditPlate && w.section == s);
  HomeLayout shown(ProviderContainer c) => c.read(homeLayoutProvider).shownAt(true);

  // The feed's own scroll (not a shelf's).
  Future<void> scrollFeed(WidgetTester tester, double by) async {
    final feed = find.descendant(of: find.byType(CustomScrollView), matching: find.byType(Scrollable)).first;
    final position = tester.state<ScrollableState>(feed).position;
    position.jumpTo(position.pixels + by);
    // Long enough after (Home's own clock is real time) that a press isn't one stopping a fling.
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 500)));
    await frames(tester, 4);
  }

  testWidgets('should_showTheLargeScreenDefaultInRowsOfExactHeight_when_aTabletIsLandscape', (tester) async {
    final (:container, store: _) = await pumpHome(tester);
    container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    // Activities | Jump Back In share the first row: the same top, side by side, one grid height tall.
    final a = tester.getRect(plate(HomeSection.activities));
    final j = tester.getRect(plate(HomeSection.jumpBackIn));
    expect(a.top, j.top);
    expect(a.right, lessThanOrEqualTo(j.left));
    expect(a.height, closeTo(homeGridHeightDp(2), .01));
    expect(j.height, closeTo(a.height, .01));
    // All Memories takes a full row under them, its own unit tall.
    final m = tester.getRect(plate(HomeSection.memoryGrid));
    expect(m.width, greaterThan(a.width * 1.8));
    expect(m.height, closeTo(homeGridHeightDp(1), .01));
    // Every block has the two-column ⌟; nothing was written by showing it.
    expect(find.byKey(homeGridHandleKey(HomeSection.activities)), findsOneWidget);
    expect(container.read(homeLayoutProvider).isDefault, isTrue);
    expect(tester.takeException(), isNull);
    await done(tester, container);
  });

  testWidgets('should_pairBesideAFullRow_when_droppedAt85PercentAcrossIt', (tester) async {
    final (:container, store: _) = await pumpHome(tester);
    container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    expect(shown(container).partnerOf(HomeSection.madeForYou), isNull);
    await scrollFeed(tester, 200);
    final from = tester.getCenter(plate(HomeSection.madeForYou));
    final memory = tester.getRect(plate(HomeSection.memoryGrid));
    final gesture = await tester.startGesture(from);
    await tester.pump(const Duration(milliseconds: 300));
    await frames(tester, 2);
    // Across its own row to 85% of the content, then up over the middle of All Memories' row: a full row's middle
    // pairs at its sides (rule ②).
    final across = Offset(memory.left + memory.width * .85, from.dy);
    for (var i = 1; i <= 12; i++) {
      await gesture.moveTo(Offset.lerp(from, across, i / 12)!);
      await tester.pump(const Duration(milliseconds: 16));
    }
    await gesture.moveTo(Offset(across.dx, memory.center.dy));
    await tester.pump(const Duration(milliseconds: 16));
    await frames(tester, 6);
    await gesture.up();
    await frames(tester, 40);
    final layout = shown(container);

    expect(layout.pairs[HomeSection.memoryGrid], HomeSection.madeForYou);
    expect(layout.widePairs[HomeSection.memoryGrid], HomeSection.madeForYou);
    expect(container.read(homeEditProvider).undo, hasLength(1));
    // Laid out side by side.
    expect(tester.getRect(plate(HomeSection.madeForYou)).top, tester.getRect(plate(HomeSection.memoryGrid)).top);
    expect(tester.takeException(), isNull);
    await done(tester, container);
  });

  testWidgets('should_stackInOneColumnInTheSameOrder_when_resizedTo700', (tester) async {
    final (:container, store: _) = await pumpHome(tester);
    container.read(homeEditProvider.notifier)
      ..enter()
      // An edited draft (not the untouched default, which each width shows as its own default).
      ..setFull(HomeSection.activities);
    await frames(tester, 30);
    final order = shown(container).visibleV1Sections;
    tester.view.physicalSize = const Size(700, 1000);
    await frames(tester, 30);
    // One column: no ⌟ of the grid, the blocks stacked in the order two columns showed.
    expect(find.byKey(homeGridHandleKey(HomeSection.activities)), findsNothing);
    final a = tester.getRect(plate(HomeSection.activities));
    final j = tester.getRect(plate(HomeSection.jumpBackIn));
    expect(j.top, greaterThan(a.bottom));
    expect(container.read(homeLayoutProvider).shownAt(false).visibleV1Sections, order);
    expect(tester.takeException(), isNull);
    await done(tester, container);
  });

  testWidgets('should_rollBackTheDrag_when_theWidthChangesMidDrag', (tester) async {
    final (:container, store: _) = await pumpHome(tester);
    container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    final before = shown(container).visibleV1Sections;
    await scrollFeed(tester, 200);
    final gesture = await tester.startGesture(tester.getCenter(plate(HomeSection.madeForYou)));
    await tester.pump(const Duration(milliseconds: 300));
    for (var i = 0; i < 12; i++) {
      await gesture.moveBy(const Offset(0, -25));
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(container.read(homeEditProvider.notifier).carrying, isTrue);
    tester.view.physicalSize = const Size(1100, 800);
    await frames(tester, 10);
    await gesture.up();
    await frames(tester, 30);
    expect(container.read(homeEditProvider.notifier).carrying, isFalse);
    expect(shown(container).visibleV1Sections, before);
    expect(container.read(homeEditProvider).undo, isEmpty);
    await done(tester, container);
  });

  testWidgets('should_stayOneColumn_when_tabletop', (tester) async {
    final (:container, store: _) = await pumpHome(tester, tabletop: true);
    container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    expect(find.byKey(homeGridHandleKey(HomeSection.activities)), findsNothing);
    final a = tester.getRect(plate(HomeSection.activities));
    final j = tester.getRect(plate(HomeSection.jumpBackIn));
    expect(j.top, greaterThan(a.bottom));
    await done(tester, container);
  });

  testWidgets('should_snapHalfToFull_when_theSpanHandleIsDraggedPastTheMidpoint', (tester) async {
    final (:container, store: _) = await pumpHome(tester);
    container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    expect(shown(container).partnerOf(HomeSection.activities), HomeSection.jumpBackIn);
    final handle = find.byKey(homeGridHandleKey(HomeSection.activities));
    // Short of the midpoint: it springs back, nothing written.
    var g = await tester.startGesture(tester.getCenter(handle));
    for (var i = 0; i < 10; i++) {
      await g.moveBy(const Offset(10, 0));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await g.up();
    await frames(tester, 40);
    expect(shown(container).partnerOf(HomeSection.activities), HomeSection.jumpBackIn);
    expect(container.read(homeEditProvider).undo, isEmpty);
    // Past it: the dashed outline says full, the release writes it (Jump Back In on its own row after).
    g = await tester.startGesture(tester.getCenter(handle));
    for (var i = 0; i < 20; i++) {
      await g.moveBy(const Offset(20, 0));
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(find.byWidgetPredicate((w) => w is CustomPaint && w.painter is HomeSpanOutlinePainter), findsOneWidget);
    await g.up();
    await frames(tester, 60);
    final layout = shown(container);
    expect(layout.partnerOf(HomeSection.activities), isNull);
    expect(layout.partnerOf(HomeSection.jumpBackIn), isNull);
    expect(container.read(homeEditProvider).undo, hasLength(1));
    expect(tester.getRect(plate(HomeSection.jumpBackIn)).top, greaterThan(tester.getRect(plate(HomeSection.activities)).bottom));
    // Dragged back left past the midpoint: Activities pulls Jump Back In up beside it again.
    g = await tester.startGesture(tester.getCenter(handle));
    for (var i = 0; i < 25; i++) {
      await g.moveBy(const Offset(-20, 0));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await g.up();
    await frames(tester, 60);
    expect(shown(container).partnerOf(HomeSection.activities), HomeSection.jumpBackIn);
    expect(tester.takeException(), isNull);
    await done(tester, container);
  });

  testWidgets('should_growTheRowsUnits_when_theHandleIsDraggedDown', (tester) async {
    final (:container, store: _) = await pumpHome(tester);
    container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    expect(shown(container).unitsOf(HomeSection.memoryGrid), 1);
    final handle = find.byKey(homeGridHandleKey(HomeSection.memoryGrid));
    final g = await tester.startGesture(tester.getCenter(handle));
    for (var i = 0; i < 16; i++) {
      await g.moveBy(const Offset(0, 12));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await g.up();
    await frames(tester, 60);
    expect(shown(container).unitsOf(HomeSection.memoryGrid), 2);
    expect(tester.getRect(plate(HomeSection.memoryGrid)).height, closeTo(homeGridHeightDp(2), .01));
    expect(tester.takeException(), isNull);
    await done(tester, container);
  });

  testWidgets('should_stepSpanAndUnits_when_arrowKeysPressedOnAFocusedHandle', (tester) async {
    final (:container, store: _) = await pumpHome(tester);
    container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    FocusNode node() => tester
        .widget<Focus>(find.ancestor(of: find.byKey(homeGridHandleKey(HomeSection.activities)), matching: find.byType(Focus)).first)
        .focusNode!;
    // → on a focused left half: full width.
    node().requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await frames(tester, 20);
    expect(shown(container).partnerOf(HomeSection.activities), isNull);
    // ↓: one more unit (Activities 2 → 3); ← back to a half beside Jump Back In.
    node().requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await frames(tester, 20);
    expect(shown(container).unitsOf(HomeSection.activities), 3);
    node().requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    await frames(tester, 20);
    expect(shown(container).partnerOf(HomeSection.activities), HomeSection.jumpBackIn);
    await done(tester, container);
  });

  testWidgets('should_resetToTheLargeScreenDefaultAndClearTheRow_when_resetInTwoColumns', (tester) async {
    final (:container, :store) = await pumpHome(tester);
    final edit = container.read(homeEditProvider.notifier)..enter();
    await frames(tester, 30);
    edit.setFull(HomeSection.activities);
    await frames(tester, 10);
    expect(shown(container).isWideDefault, isFalse);
    expect(store.values.keys.where((k) => k.startsWith('home_layout.v1.')), isNotEmpty);
    edit.reset();
    await frames(tester, 10);
    expect(shown(container).isWideDefault, isTrue);
    // Either default is "never customized": the row goes.
    expect(store.values.keys.where((k) => k.startsWith('home_layout.v1.')), isEmpty);
    await done(tester, container);
  });

  testWidgets('should_offerTwoColumnReaderActions_when_editingInTwoColumns', (tester) async {
    final handle = tester.ensureSemantics();
    final (:container, store: _) = await pumpHome(tester);
    container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    final jbi = tester.widget<HomeEditPlate>(plate(HomeSection.jumpBackIn));
    expect(jbi.onMakeFull, isNotNull);
    final memory = tester.widget<HomeEditPlate>(plate(HomeSection.memoryGrid));
    expect(memory.onMakeFull, isNull);
    // All Memories (full) can go beside nothing above it (Jump Back In is a half); Made For You can go beside it.
    expect(memory.onPlaceBeside, isNull);
    final mfy = tester.widget<HomeEditPlate>(plate(HomeSection.madeForYou));
    mfy.onPlaceBeside!();
    await frames(tester, 10);
    expect(shown(container).pairs[HomeSection.memoryGrid], HomeSection.madeForYou);
    handle.dispose();
    await done(tester, container);
  });

  testWidgets('should_foldIntoTheList_when_aTrackpadPinchesOrCtrlWheelScales', (tester) async {
    final (:container, store: _) = await pumpHome(tester);
    container.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    Finder strip(String text) => find.descendant(of: find.byType(HomeEditList), matching: find.text(text));
    expect(strip('Jump Back In'), findsNothing);
    // A PC trackpad's pinch in (PointerPanZoom): the compact list.
    final pad = await tester.createGesture(kind: PointerDeviceKind.trackpad);
    await pad.panZoomStart(const Offset(640, 400));
    for (var i = 1; i <= 8; i++) {
      await pad.panZoomUpdate(const Offset(640, 400), scale: 1 - .06 * i);
      await tester.pump(const Duration(milliseconds: 16));
    }
    await pad.panZoomEnd();
    await frames(tester, 30);
    expect(strip('Jump Back In'), findsOneWidget);
    // Ctrl + wheel out (a PointerScaleEvent): back to the full layout.
    tester.binding.handlePointerEvent(const PointerScaleEvent(position: Offset(640, 400), scale: 1.2));
    await frames(tester, 30);
    expect(strip('Jump Back In'), findsNothing);
    expect(container.read(homeEditProvider).editing, isTrue);
    await done(tester, container);
  });
}

Future<void> frames(WidgetTester tester, int n) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 32));
  }
}
