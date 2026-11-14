import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin_symbols/yoin_symbols.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/home/jump_back_in.dart';
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
      expect(visible, [HomeSection.activities, HomeSection.jumpBackIn, HomeSection.rediscover, HomeSection.recentlyPlayed, HomeSection.yourPlaylists, HomeSection.memoryGrid]);
      final moved = withSectionMoved(hidden, visible, HomeSection.yourPlaylists, 0);
      expect(moved.visibleV1Sections.first, HomeSection.yourPlaylists);
      // The hidden section keeps its own slot and flag.
      expect(moved.sections.indexWhere((p) => p.id == 'recently_added'), hidden.sections.indexWhere((p) => p.id == 'recently_added'));
      expect(moved.sections.firstWhere((p) => p.id == 'recently_added').enabled, isFalse);
      expect(moved.sections.map((p) => p.id).toSet(), layout.sections.map((p) => p.id).toSet());
    });

    test('should_keepSettingsWithTheSection_when_moving', () {
      final withRows = HomeLayout([
        for (final p in layout.sections) p.id == 'jump_back_in' ? HomeSectionPref(id: p.id, enabled: true, config: const {'rows': 'xl'}) : p,
      ]);
      final moved = withSectionMoved(withRows, withRows.visibleV1Sections, HomeSection.jumpBackIn, 4);
      expect(moved.sections.firstWhere((p) => p.id == 'jump_back_in').config, {'rows': 'xl'});
      expect(moved.visibleV1Sections[4], HomeSection.jumpBackIn);
    });

    test('should_ignoreMove_when_outOfRangeOrSamePlace', () {
      expect(identical(withSectionMoved(layout, layout.visibleV1Sections, HomeSection.activities, 0), layout), isTrue);
      expect(identical(withSectionMoved(layout, layout.visibleV1Sections, HomeSection.activities, 99), layout), isTrue);
    });

    test('should_offerOnlyBuiltSections_when_listingHidden', () {
      final hidden = withSectionEnabled(withSectionEnabled(layout, HomeSection.jumpBackIn, false), HomeSection.lyricHighlight, false);
      expect(hiddenSections(hidden), [HomeSection.jumpBackIn]);
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
      final saved = HomeLayout.fromJson((jsonDecode(h.store.values[key]!) as Map).cast<String, Object?>());
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
      h.store.values[HomeLayoutNotifier.keyFor(MediaId.providerSubsonic, id)] = jsonEncode(saved.toJson());
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

    Future<ProviderContainer> pumpHome(WidgetTester tester) async {
      tester.view.physicalSize = const Size(400, 1400);
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
      expect(find.text('Done'), findsOneWidget);
      expect(find.text('Hidden'), findsOneWidget);
      expect(find.text('No hidden sections'), findsOneWidget);
      // Cards take no taps while editing.
      final jbi = tester.widget<IgnorePointer>(find.ancestor(of: find.byType(JumpBackInSection), matching: find.byType(IgnorePointer)).first);
      expect(jbi.ignoring, isTrue);
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
      await tester.tap(find.byTooltip('Undo'));
      await frames(tester, 20);
      expect(find.byType(JumpBackInSection), findsOneWidget);
      await tester.tap(find.byTooltip('Hide Jump Back In'));
      await frames(tester, 20);
      await tester.tap(find.byTooltip('Show Jump Back In'));
      await frames(tester, 20);
      expect(find.byType(JumpBackInSection), findsOneWidget);
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
      await tester.tap(find.text('Done'));
      await frames(tester, 30);
      expect(c.read(homeEditProvider).editing, isFalse);
      expect(find.text('Done'), findsNothing);
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
  });
}

Future<void> frames(WidgetTester tester, int n) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 32));
  }
}
