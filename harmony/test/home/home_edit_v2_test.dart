import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin_symbols/yoin_symbols.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_edit_list.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/nowplaying/haptics.dart';
import 'package:yoin/platform/haptics/haptics_host.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

/// Home edit v2 (parity p4 C1; Android PR #292, design.md §主页 › 就地编辑): the in-place drag, its edge
/// scroll and rollback, and the pinch into the compact list and back.
void main() {
  setUp(() {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
  });

  Future<ProviderContainer> pumpHome(WidgetTester tester, {Size size = const Size(400, 1400)}) async {
    tester.view.physicalSize = size;
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
    return h.container;
  }

  Future<void> done(WidgetTester tester, ProviderContainer c) async {
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(const Duration(seconds: 1));
    c.dispose();
  }

  Finder plate(HomeSection s) => find.byWidgetPredicate((w) => w is HomeEditPlate && w.section == s);
  Finder slotFrame() => find.byWidgetPredicate((w) => w is CustomPaint && w.painter is HomeDropSlotPainter);
  Finder inList(String text) => find.descendant(of: find.byType(HomeEditList), matching: find.text(text));
  // The list's own switch (the header's, under the list, says the same).
  Finder listToggle(String tooltip) =>
      find.descendant(of: find.byType(HomeEditList), matching: find.byTooltip(tooltip));
  List<HomeSection> order(ProviderContainer c) => c.read(homeLayoutProvider).visibleV1Sections;

  testWidgets('should_reorderInPlace_when_aBlockIsHeldThenMovedBy', (tester) async {
    final haptics = RecordingHapticsHost();
    YoinHaptics.host = haptics;
    addTearDown(() => YoinHaptics.host = const NoHapticsHost());
    final c = await pumpHome(tester);
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    expect(order(c).indexOf(HomeSection.jumpBackIn), 1);
    final gesture = await tester.startGesture(tester.getCenter(plate(HomeSection.jumpBackIn)));
    // The body hold: max(150 ms, T/2), then it lifts.
    await tester.pump(const Duration(milliseconds: 300));
    await frames(tester, 2);
    expect(haptics.performed, contains(YoinHaptic.dragStart));
    for (var i = 0; i < 20; i++) {
      await gesture.moveBy(const Offset(0, 25));
      await tester.pump(const Duration(milliseconds: 16));
    }
    // Mid-drag: the layout keeps its shape, the drop slot is framed, nothing is written yet.
    expect(c.read(homeEditProvider.notifier).carrying, isTrue);
    expect(slotFrame(), findsOneWidget);
    expect(haptics.performed, contains(YoinHaptic.segmentTick));
    expect(order(c).indexOf(HomeSection.jumpBackIn), 1);
    expect(c.read(homeEditProvider).undo, isEmpty);
    await gesture.up();
    await frames(tester, 40);
    // Written once on the drop: one Undo step, the confirm haptic, the slot frame gone after the glide.
    expect(order(c).indexOf(HomeSection.jumpBackIn), greaterThan(1));
    expect(c.read(homeEditProvider).undo, hasLength(1));
    expect(haptics.performed, contains(YoinHaptic.confirm));
    expect(slotFrame(), findsNothing);
    expect(c.read(homeEditProvider).editing, isTrue);
    expect(tester.takeException(), isNull);
    await done(tester, c);
  });

  testWidgets('should_foldIntoTheListAndBack_when_pinchedInThenOut', (tester) async {
    final c = await pumpHome(tester);
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    expect(inList('Jump Back In'), findsNothing);
    // Two fingers on the feed before anything lifts, closing in.
    final a = await tester.startGesture(const Offset(200, 400), pointer: 1);
    final b = await tester.startGesture(const Offset(200, 800), pointer: 2);
    for (var i = 0; i < 10; i++) {
      await a.moveBy(const Offset(0, 15));
      await b.moveBy(const Offset(0, -15));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await b.up();
    await a.up();
    await frames(tester, 30);
    // The compact list: one strip per section, the switch back above them.
    expect(inList('Jump Back In'), findsOneWidget);
    expect(inList('Recently Played'), findsOneWidget);
    expect(listToggle('Full layout'), findsOneWidget);
    expect(c.read(homeEditProvider).editing, isTrue);
    // Two fingers on the list, opening out: back to the full layout.
    final d = await tester.startGesture(const Offset(200, 500), pointer: 3);
    final e = await tester.startGesture(const Offset(200, 600), pointer: 4);
    for (var i = 0; i < 10; i++) {
      await d.moveBy(const Offset(0, -12));
      await e.moveBy(const Offset(0, 12));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await e.up();
    await d.up();
    await frames(tester, 30);
    expect(inList('Jump Back In'), findsNothing);
    expect(plate(HomeSection.jumpBackIn), findsOneWidget);
    expect(c.read(homeEditProvider).editing, isTrue);
    expect(tester.takeException(), isNull);
    await done(tester, c);
  });

  testWidgets('should_switchToTheListAndBack_when_theHeaderToggleIsTapped', (tester) async {
    final c = await pumpHome(tester);
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    await tester.tap(find.byTooltip('Compact list'));
    await frames(tester, 30);
    expect(inList('Your Playlists'), findsOneWidget);
    await tester.tap(listToggle('Full layout'));
    await frames(tester, 30);
    expect(inList('Your Playlists'), findsNothing);
    expect(c.read(homeEditProvider).editing, isTrue);
    await done(tester, c);
  });

  testWidgets('should_reorderAStrip_when_itsHandleIsDraggedInTheList', (tester) async {
    final c = await pumpHome(tester);
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    await tester.tap(find.byTooltip('Compact list'));
    await frames(tester, 30);
    final handle = find.descendant(of: find.byType(HomeEditList), matching: find.byIcon(YoinSymbols.dragHandle)).first;
    final gesture = await tester.startGesture(tester.getCenter(handle));
    for (var i = 0; i < 12; i++) {
      await gesture.moveBy(const Offset(0, 20));
      await tester.pump(const Duration(milliseconds: 16));
    }
    await gesture.up();
    await frames(tester, 40);
    expect(order(c).first, isNot(HomeSection.activities));
    expect(c.read(homeEditProvider).undo, hasLength(1));
    expect(tester.takeException(), isNull);
    await done(tester, c);
  });

  testWidgets('should_scrollAndDropLast_when_heldInTheBottomBand', (tester) async {
    // A short window: most of the feed is below the fold.
    final c = await pumpHome(tester, size: const Size(400, 700));
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    expect(order(c).first, HomeSection.activities);
    final position = tester.state<ScrollableState>(find.byType(Scrollable).first).position;
    final before = position.pixels;
    final handle = find.byIcon(YoinSymbols.dragHandle).first;
    final start = tester.getCenter(handle);
    final gesture = await tester.startGesture(start);
    await gesture.moveBy(const Offset(0, 30));
    await tester.pump(const Duration(milliseconds: 16));
    // Into the band above the bar, then held there while the frames run.
    final bottom = tester.getBottomLeft(find.byType(CustomScrollView).first).dy;
    await gesture.moveTo(Offset(start.dx, bottom - 110));
    for (var i = 0; i < 300; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(position.pixels, greaterThan(before + 200));
    expect(position.pixels, closeTo(position.maxScrollExtent, 1));
    await gesture.up();
    await frames(tester, 40);
    expect(order(c).last, HomeSection.activities);
    expect(c.read(homeEditProvider).undo, hasLength(1));
    expect(tester.takeException(), isNull);
    await done(tester, c);
  });

  testWidgets('should_rollTheDragBack_when_theWindowIsResizedMidDrag', (tester) async {
    final c = await pumpHome(tester);
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    final before = order(c);
    final gesture = await tester.startGesture(tester.getCenter(find.byIcon(YoinSymbols.dragHandle).first));
    for (var i = 0; i < 16; i++) {
      await gesture.moveBy(const Offset(0, 25));
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(c.read(homeEditProvider.notifier).carrying, isTrue);
    // A window resize takes the drag's geometry away (rule 5): the order before the lift, nothing written.
    tester.view.physicalSize = const Size(560, 1400);
    await frames(tester, 20);
    expect(c.read(homeEditProvider.notifier).carrying, isFalse);
    expect(slotFrame(), findsNothing);
    expect(order(c), before);
    expect(c.read(homeEditProvider).undo, isEmpty);
    // The finger still down is the gesture's to swallow: lifting it writes nothing.
    await gesture.moveBy(const Offset(0, 100));
    await gesture.up();
    await frames(tester, 20);
    expect(order(c), before);
    expect(c.read(homeEditProvider).editing, isTrue);
    expect(tester.takeException(), isNull);
    await done(tester, c);
  });

  testWidgets('should_offerMoveAndHideActions_when_aStripIsRead', (tester) async {
    final semantics = tester.ensureSemantics();
    final c = await pumpHome(tester);
    c.read(homeEditProvider.notifier).enter();
    await frames(tester, 30);
    await tester.tap(find.byTooltip('Compact list'));
    await frames(tester, 30);
    final strip = tester.getSemantics(find.bySemanticsLabel(RegExp(r'^Jump Back In, Section 2 of \d+$')));
    final data = strip.getSemanticsData();
    final labels = {
      for (final id in data.customSemanticsActionIds ?? const <int>[]) CustomSemanticsAction.getAction(id)!.label,
    };
    expect(labels, containsAll(<String>['Move up', 'Move down', 'Hide']));
    expect(find.bySemanticsLabel('Hide Jump Back In'), findsWidgets);
    semantics.dispose();
    await done(tester, c);
  });
}

Future<void> frames(WidgetTester tester, int n) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 32));
  }
}
