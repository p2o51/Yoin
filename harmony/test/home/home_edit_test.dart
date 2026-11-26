import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin_symbols/yoin_symbols.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_carry_stack.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/home/home_shelves.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/home/jump_back_in.dart';
import 'package:yoin/nowplaying/haptics.dart';
import 'package:yoin/platform/haptics/haptics_host.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

void main() {
  group('layout edits', () {
    final layout = HomeLayout.defaultLayout;

    test('should_permuteOnlyVisibleSlots_when_moving', () {
      final hidden = withSectionEnabled(layout, HomeSection.recentlyAdded, false);
      final visible = hidden.visibleV1Sections;
      expect(visible, [
        HomeSection.activities,
        HomeSection.jumpBackIn,
        HomeSection.rediscover,
        HomeSection.recentlyPlayed,
        HomeSection.yourPlaylists,
        HomeSection.otherProfiles,
        HomeSection.lyricHighlight,
        HomeSection.memoryGrid,
        HomeSection.madeForYou,
      ]);
      final moved = withSectionMoved(hidden, visible, HomeSection.yourPlaylists, 0);
      expect(moved.visibleV1Sections.first, HomeSection.yourPlaylists);
      // The hidden section keeps its own slot and flag.
      expect(moved.sections.indexWhere((p) => p.id == 'recently_added'), hidden.sections.indexWhere((p) => p.id == 'recently_added'));
      expect(moved.sections.firstWhere((p) => p.id == 'recently_added').enabled, isFalse);
      expect(moved.sections.map((p) => p.id).toSet(), layout.sections.map((p) => p.id).toSet());
    });

    test('should_keepSettingsWithTheSection_when_moving', () {
      final withRows = withSectionRows(layout, HomeSection.jumpBackIn, HomeRowPreset.xl);
      final moved = withSectionMoved(withRows, withRows.visibleV1Sections, HomeSection.jumpBackIn, 4);
      // Its rows go with it; away from Activities it is no longer half of a pair.
      expect(moved.sections.firstWhere((p) => p.id == 'jump_back_in').config(), {'rows': 'xl'});
      expect(moved.visibleV1Sections[4], HomeSection.jumpBackIn);
    });

    test('should_ignoreMove_when_outOfRangeOrSamePlace', () {
      expect(identical(withSectionMoved(layout, layout.visibleV1Sections, HomeSection.activities, 0), layout), isTrue);
      expect(identical(withSectionMoved(layout, layout.visibleV1Sections, HomeSection.activities, 99), layout), isTrue);
    });

    test('should_offerOnlyBuiltSections_when_listingHidden', () {
      // Lyric Cards is built (the user's own picks, no Gemini Nano); an unknown id is not offered.
      final hidden = HomeLayout.reconcile([
        ...withSectionEnabled(withSectionEnabled(layout, HomeSection.jumpBackIn, false), HomeSection.lyricHighlight, false)
            .toPrefs(),
        const HomeSectionPref(id: 'future_section', enabled: false),
      ]);
      expect(hiddenSections(hidden), [HomeSection.jumpBackIn, HomeSection.lyricHighlight]);
    });
  });

  group('controller', () {
    test('should_saveEachStepPerAccountAndUndo_when_editing', () async {
      final h = await homeContainer();
      final c = h.container;
      // Watched, as the screen does (an unwatched provider pauses).
      c.listen(homeLayoutProvider, (_, _) {});
      c.listen(homeEditProvider, (_, _) {});
      await settle(() => false, rounds: 10);
      final edit = c.read(homeEditProvider.notifier);
      edit.enter();
      edit.hide(HomeSection.jumpBackIn);
      edit.move(HomeSection.yourPlaylists, 0);
      expect(c.read(homeLayoutProvider).visibleV1Sections.first, HomeSection.yourPlaylists);
      expect(c.read(homeLayoutProvider).visibleV1Sections, isNot(contains(HomeSection.jumpBackIn)));
      await settle(() => false, rounds: 5);
      final key = h.store.values.keys.firstWhere((k) => k.startsWith('home_layout.v1.subsonic|'));
      final saved = decodeHomeLayout(h.store.values[key]!);
      expect(saved.visibleV1Sections.first, HomeSection.yourPlaylists);

      expect(c.read(homeEditProvider).undo, hasLength(2));
      edit.undo();
      expect(c.read(homeLayoutProvider).visibleV1Sections.first, HomeSection.activities);
      expect(c.read(homeLayoutProvider).visibleV1Sections, isNot(contains(HomeSection.jumpBackIn)));
      edit.reset();
      expect(c.read(homeLayoutProvider).visibleV1Sections, HomeLayout.defaultLayout.visibleV1Sections);
      edit.exit();
      expect(c.read(homeEditProvider).undo, isEmpty);
      c.dispose();
    });

    test('should_addNoUndoStep_when_restoringDefaultOnDefaultLayout', () async {
      final h = await homeContainer();
      final c = h.container;
      c.listen(homeLayoutProvider, (_, _) {});
      c.listen(homeEditProvider, (_, _) {});
      await settle(() => false, rounds: 10);
      final edit = c.read(homeEditProvider.notifier);
      edit.enter();
      edit.reset();
      expect(c.read(homeEditProvider).undo, isEmpty);
      edit.hide(HomeSection.jumpBackIn);
      edit.reset();
      expect(c.read(homeEditProvider).undo, hasLength(2));
      c.dispose();
    });

    test('should_foldAway_when_libraryComesToFront', () async {
      final h = await homeContainer();
      final c = h.container;
      c.listen(homeEditProvider, (_, _) {});
      await settle(() => false, rounds: 10);
      c.read(homeEditProvider.notifier).enter();
      expect(c.read(homeEditProvider).editing, isTrue);
      c.read(shellSectionProvider.notifier).select(YoinSection.library);
      expect(c.read(homeEditProvider).editing, isFalse);
      c.dispose();
    });

    test('should_readSavedLayout_when_homeStarts', () async {
      final h = await homeContainer();
      final profiles = jsonDecode(h.store.values['profiles.v1']!) as List;
      final id = (profiles.single as Map)['id'] as String;
      final saved = withSectionEnabled(HomeLayout.defaultLayout, HomeSection.recentlyPlayed, false);
      h.store.values[HomeLayoutNotifier.keyFor(MediaId.providerSubsonic, id)] = saved.encode();
      h.container.listen(homeLayoutProvider, (_, _) {});
      await settle(() => !h.container.read(homeLayoutProvider).visibleV1Sections.contains(HomeSection.recentlyPlayed));
      expect(h.container.read(homeLayoutProvider).visibleV1Sections, isNot(contains(HomeSection.recentlyPlayed)));
      h.container.dispose();
    });
  });

  group('on Home', () {
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
        HomeGridPools(cachedAtMs: homeTestNow.millisecondsSinceEpoch, albums: [for (var i = 0; i < 6; i++) album('g$i')]).toJson(),
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

    testWidgets('should_enterEditMode_when_feedIsLongPressed', (tester) async {
      final c = await pumpHome(tester);
      expect(find.text('Edit Home'), findsOneWidget); // the footer
      await tester.longPress(find.text('Jump Back In'));
      await frames(tester, 30);
      expect(c.read(homeEditProvider).editing, isTrue);
      // Undo and Done are the bar's now (BarEditPose), not the header's.
      expect(find.text('Edit Home'), findsWidgets);
      // The tray sits under every section's plate (On Your Other Accounts' placeholder among them).
      await tester.dragUntilVisible(find.text('Hidden'), find.byType(HomeScreen), const Offset(0, -300));
      expect(find.text('Hidden'), findsOneWidget);
      expect(find.text('No hidden sections'), findsOneWidget);
      // Cards take no taps while editing — but Jump Back In's, which open their "not now" menu.
      final shelf = tester.widget<IgnorePointer>(find.ancestor(of: find.byType(RecentlyPlayedSection), matching: find.byType(IgnorePointer)).first);
      expect(shelf.ignoring, isTrue);
      final jbi = tester.widget<IgnorePointer>(find.ancestor(of: find.byType(JumpBackInSection), matching: find.byType(IgnorePointer)).first);
      expect(jbi.ignoring, isFalse);
      await done(tester, c);
    });

    testWidgets('should_hideToTrayAndShowBack_when_buttonsTapped', (tester) async {
      final c = await pumpHome(tester);
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      await tester.tap(find.byTooltip('Hide Jump Back In'));
      await frames(tester, 20);
      expect(find.byType(JumpBackInSection), findsNothing);
      expect(find.byTooltip('Show Jump Back In'), findsOneWidget);
      // The bar's left slot: Undo while there is history.
      c.read(homeEditProvider.notifier).barLeftSlot();
      await frames(tester, 20);
      expect(find.byType(JumpBackInSection), findsOneWidget);
      await tester.tap(find.byTooltip('Hide Jump Back In'));
      await frames(tester, 20);
      // Below every section's plate (Made For You's placeholder among them): scrolled to first.
      await tester.ensureVisible(find.byTooltip('Show Jump Back In'));
      await frames(tester, 10);
      await tester.tap(find.byTooltip('Show Jump Back In'));
      await frames(tester, 20);
      expect(find.byType(JumpBackInSection), findsOneWidget);
      await done(tester, c);
    });

    testWidgets('should_toggleHapticAndHoldUndo_when_hiddenOrCarrying', (tester) async {
      final haptics = RecordingHapticsHost();
      YoinHaptics.host = haptics;
      addTearDown(() => YoinHaptics.host = const NoHapticsHost());
      final c = await pumpHome(tester);
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      await tester.tap(find.byTooltip('Hide Jump Back In'));
      await frames(tester, 20);
      // Hide / show are Android's TOGGLE_OFF / TOGGLE_ON.
      expect(haptics.performed.last, YoinHaptic.toggleOff);
      // Mid-carry the bar's Undo waits (Android barLeftSlotClick).
      final edit = c.read(homeEditProvider.notifier)..carrying = true;
      edit.barLeftSlot();
      await frames(tester, 20);
      expect(c.read(homeEditProvider).canUndo, isTrue);
      expect(find.byType(JumpBackInSection), findsNothing);
      edit.carrying = false;
      edit.barLeftSlot();
      await frames(tester, 20);
      expect(find.byType(JumpBackInSection), findsOneWidget);
      await tester.tap(find.byTooltip('Hide Jump Back In'));
      await frames(tester, 20);
      // Below every section's plate (Made For You's placeholder among them): scrolled to first.
      await tester.ensureVisible(find.byTooltip('Show Jump Back In'));
      await frames(tester, 10);
      await tester.tap(find.byTooltip('Show Jump Back In'));
      await frames(tester, 20);
      expect(haptics.performed.last, YoinHaptic.toggleOn);
      await done(tester, c);
    });

    testWidgets('should_scrollToTheTray_when_barAddsWithNothingToUndo', (tester) async {
      final c = await pumpHome(tester, size: const Size(400, 700));
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      await tester.tap(find.byTooltip('Hide Jump Back In'));
      await frames(tester, 20);
      // A new session: nothing to undo, one hidden section — the bar offers Add.
      c.read(homeEditProvider.notifier).done();
      await frames(tester, 30);
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      expect(c.read(homeEditProvider).canUndo, isFalse);
      c.read(homeEditProvider.notifier).barLeftSlot();
      await frames(tester, 60);
      expect(c.read(homeEditProvider).trayRequests, 1);
      final position = tester.state<ScrollableState>(find.byType(Scrollable).first).position;
      expect(position.pixels, closeTo(position.maxScrollExtent, 2));
      expect(find.byTooltip('Show Jump Back In'), findsOneWidget);
      await done(tester, c);
    });

    testWidgets('should_leaveEditMode_when_doneOrSystemBack', (tester) async {
      final c = await pumpHome(tester);
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      await tester.binding.handlePopRoute();
      await frames(tester, 30);
      expect(c.read(homeEditProvider).editing, isFalse);
      expect(find.byType(HomeScreen), findsOneWidget);
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      // The bar's Done.
      c.read(homeEditProvider.notifier).done();
      await frames(tester, 30);
      expect(c.read(homeEditProvider).editing, isFalse);
      await done(tester, c);
    });

    testWidgets('should_moveSection_when_handleIsDraggedPastANeighbour', (tester) async {
      final c = await pumpHome(tester);
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      final before = c.read(homeLayoutProvider).visibleV1Sections;
      expect(before.indexOf(HomeSection.jumpBackIn), 1);
      final handle = find.byIcon(YoinSymbols.dragHandle).at(1);
      final gesture = await tester.startGesture(tester.getCenter(handle));
      for (var i = 0; i < 12; i++) {
        await gesture.moveBy(const Offset(0, 60));
        await tester.pump(const Duration(milliseconds: 16));
      }
      await gesture.up();
      await frames(tester, 30);
      final after = c.read(homeLayoutProvider).visibleV1Sections;
      expect(after.indexOf(HomeSection.jumpBackIn), greaterThan(1));
      expect(tester.takeException(), isNull);
      await done(tester, c);
    });

    testWidgets('should_moveSection_when_otherSectionsAreOffScreen', (tester) async {
      // Short window: the lazy feed has not built the sections below the fold.
      final c = await pumpHome(tester, size: const Size(400, 640));
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      final before = c.read(homeLayoutProvider).visibleV1Sections;
      expect(before.first, HomeSection.activities);
      expect(find.byType(YourPlaylistsSection), findsNothing); // off screen, not built
      final handle = find.byIcon(YoinSymbols.dragHandle).first;
      final gesture = await tester.startGesture(tester.getCenter(handle));
      await gesture.moveBy(const Offset(0, 30));
      await tester.pump(const Duration(milliseconds: 16));
      // Past one neighbour on screen.
      for (var i = 0; i < 6; i++) {
        await gesture.moveBy(const Offset(0, 50));
        await tester.pump(const Duration(milliseconds: 16));
      }
      await gesture.up();
      await frames(tester, 30);
      final after = c.read(homeLayoutProvider).visibleV1Sections;
      expect(after.indexOf(HomeSection.activities), greaterThan(0));
      expect(tester.takeException(), isNull);
      await done(tester, c);
    });

    testWidgets('should_moveToTheEnd_when_carriedPastTheLastStrip', (tester) async {
      // Short window: the strips fold the whole order onto the screen, no scrolling needed.
      final c = await pumpHome(tester, size: const Size(400, 640));
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      final before = c.read(homeLayoutProvider).visibleV1Sections;
      final handle = find.byIcon(YoinSymbols.dragHandle).first;
      final start = tester.getCenter(handle);
      final gesture = await tester.startGesture(start);
      await gesture.moveBy(const Offset(0, 30));
      await frames(tester, 10);
      // Every section is a strip now, its title on it.
      for (final title in ['Your Playlists', 'Made For You']) {
        expect(find.descendant(of: find.byType(HomeCarryStack), matching: find.text(title)), findsOneWidget);
      }
      final scrolled = tester.state<ScrollableState>(find.byType(Scrollable).first).position.pixels;
      // Down past the last strip.
      final bottom = tester.getBottomLeft(find.byType(CustomScrollView)).dy;
      await gesture.moveTo(Offset(start.dx, bottom - 5));
      await frames(tester, 5);
      expect(tester.state<ScrollableState>(find.byType(Scrollable).first).position.pixels, scrolled);
      await gesture.up();
      await frames(tester, 60);
      final after = c.read(homeLayoutProvider).visibleV1Sections;
      expect(after.last, HomeSection.activities, reason: 'was $before');
      expect(c.read(homeEditProvider).undo, hasLength(1));
      expect(tester.takeException(), isNull);
      await done(tester, c);
    });

    testWidgets('should_enterWithTheBlockLiftedAndCarryIt_when_aSectionIsLongPressedAndMoved', (tester) async {
      final haptics = RecordingHapticsHost();
      YoinHaptics.host = haptics;
      addTearDown(() => YoinHaptics.host = const NoHapticsHost());
      final c = await pumpHome(tester);
      List<HomeSection> order() => c.read(homeLayoutProvider).visibleV1Sections;
      expect(order().indexOf(HomeSection.jumpBackIn), 1);
      final gesture = await tester.startGesture(tester.getCenter(find.byType(JumpBackInSection)));
      // T/2 charges, T enters with the block lifted.
      await tester.pump(const Duration(milliseconds: 300));
      expect(c.read(homeEditProvider).editing, isFalse);
      await tester.pump(const Duration(milliseconds: 250));
      expect(c.read(homeEditProvider).editing, isTrue);
      expect(haptics.performed, contains(YoinHaptic.longPress));
      await frames(tester, 5);
      // The same finger carries it: down two strips.
      for (var i = 0; i < 10; i++) {
        await gesture.moveBy(const Offset(0, 20));
        await tester.pump(const Duration(milliseconds: 16));
      }
      expect(find.descendant(of: find.byType(HomeCarryStack), matching: find.text('Jump Back In')), findsOneWidget);
      expect(haptics.performed, contains(YoinHaptic.segmentTick));
      await gesture.up();
      await frames(tester, 60);
      expect(order().indexOf(HomeSection.jumpBackIn), greaterThan(1));
      expect(haptics.performed, contains(YoinHaptic.confirm));
      // Still editing after the drop.
      expect(c.read(homeEditProvider).editing, isTrue);
      expect(tester.takeException(), isNull);
      await done(tester, c);
    });

    testWidgets('should_stayInNormalMode_when_tappedOrScrolledBeforeT', (tester) async {
      final c = await pumpHome(tester);
      await tester.tap(find.text('Recently Played'));
      await frames(tester, 10);
      expect(c.read(homeEditProvider).editing, isFalse);
      // A drag scrolls instead of entering.
      final gesture = await tester.startGesture(tester.getCenter(find.byType(JumpBackInSection)));
      await gesture.moveBy(const Offset(0, -60));
      await tester.pump(const Duration(milliseconds: 700));
      await gesture.up();
      await frames(tester, 10);
      expect(c.read(homeEditProvider).editing, isFalse);
      await done(tester, c);
    });

    testWidgets('should_liftAfterTheBodyHoldAndPaintAboveItsNeighbour_when_editing', (tester) async {
      final c = await pumpHome(tester);
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      Finder plate(HomeSection s) => find.byWidgetPredicate((w) => w is HomeEditPlate && w.section == s);
      // Painted by the feed's viewport, or over it (an overlay entry)?
      bool inFeed(HomeSection s) {
        for (RenderObject? r = tester.renderObject(plate(s)); r != null; r = r.parent) {
          if (r is RenderViewport) return true;
        }
        return false;
      }

      final restSize = tester.getSize(plate(HomeSection.activities));
      final gesture = await tester.startGesture(tester.getCenter(plate(HomeSection.activities)));
      // Before max(150 ms, T/2) nothing lifts.
      await tester.pump(const Duration(milliseconds: 100));
      expect(inFeed(HomeSection.activities), isTrue);
      await tester.pump(const Duration(milliseconds: 200));
      await frames(tester, 3);
      // Lifted: painted in the overlay, over the whole feed, at the list's size.
      expect(inFeed(HomeSection.activities), isFalse);
      expect(tester.getSize(plate(HomeSection.activities)), restSize);
      expect(inFeed(HomeSection.jumpBackIn), isTrue);
      // Let go without moving: it drops where it was, nothing written.
      await gesture.up();
      await frames(tester, 40);
      expect(inFeed(HomeSection.activities), isTrue);
      expect(c.read(homeEditProvider).undo, isEmpty);
      expect(c.read(homeEditProvider).editing, isTrue);
      expect(tester.takeException(), isNull);
      await done(tester, c);
    });

    testWidgets('should_leave_when_blankIsTapped_andStay_when_aPlateIsTapped', (tester) async {
      final c = await pumpHome(tester);
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      Finder plate(HomeSection s) => find.byWidgetPredicate((w) => w is HomeEditPlate && w.section == s);
      await tester.tap(plate(HomeSection.activities));
      await frames(tester, 10);
      expect(c.read(homeEditProvider).editing, isTrue);
      // The page margin beside a padded section, past its plate, is blank.
      final activities = tester.getRect(plate(HomeSection.activities));
      await tester.tapAt(Offset(1, activities.center.dy));
      await frames(tester, 10);
      expect(c.read(homeEditProvider).editing, isFalse);
      await done(tester, c);
    });

    testWidgets('should_waitForTheDrop_when_doneArrivesMidCarry', (tester) async {
      final c = await pumpHome(tester);
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      final gesture = await tester.startGesture(tester.getCenter(find.byIcon(YoinSymbols.dragHandle).first));
      for (var i = 0; i < 8; i++) {
        await gesture.moveBy(const Offset(0, 20));
        await tester.pump(const Duration(milliseconds: 16));
      }
      final edit = c.read(homeEditProvider.notifier);
      expect(edit.carrying, isTrue);
      edit.done();
      // The carry lets go and settles first.
      expect(c.read(homeEditProvider).editing, isTrue);
      await frames(tester, 60);
      expect(c.read(homeEditProvider).editing, isFalse);
      expect(c.read(homeLayoutProvider).visibleV1Sections.first, isNot(HomeSection.activities));
      await gesture.up();
      await frames(tester, 10);
      expect(tester.takeException(), isNull);
      await done(tester, c);
    });

    testWidgets('should_labelHideAndHandle_when_editing', (tester) async {
      final semantics = tester.ensureSemantics();
      final c = await pumpHome(tester);
      c.read(homeEditProvider.notifier).enter();
      await frames(tester, 30);
      expect(find.bySemanticsLabel('Hide Jump Back In'), findsOneWidget);
      expect(find.bySemanticsLabel('Reorder Jump Back In'), findsOneWidget);
      semantics.dispose();
      await done(tester, c);
    });
  });
}

Future<void> frames(WidgetTester tester, int n) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 32));
  }
}
