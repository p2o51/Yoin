import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

class _PushCounter extends NavigatorObserver {
  int pushes = 0;
  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) => pushes++;
}

Future<void> _pumpHome(WidgetTester tester, ProviderContainer container, {NavigatorObserver? observer}) async {
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        navigatorObservers: [?observer],
        builder: (context, child) => YoinWindowScope.measured(child: child!),
        home: const Scaffold(body: HomeScreen()),
      ),
    ),
  );
  // Load, publish, splice, then let the entrance springs run.
  for (var i = 0; i < 12; i++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
}

Future<void> _tearDown(WidgetTester tester, ProviderContainer container) async {
  await tester.pumpWidget(const SizedBox.shrink());
  container.dispose();
}

Future<void> _seedPools(InMemoryStore store, HomeGridPools pools) async {
  final profiles = jsonDecode(store.values['profiles.v1']!) as List;
  final id = (profiles.single as Map)['id'] as String;
  store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(pools.toJson());
}

void main() {
  setUp(() {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
  });

  testWidgets('should_showHeaderAndEmptyActivitiesCard_when_noHistory', (tester) async {
    final h = await homeContainer();
    await _pumpHome(tester, h.container);

    expect(find.text('Home'), findsOneWidget);
    expect(find.text('Good morning'), findsOneWidget);
    expect(find.text('No activity yet'), findsOneWidget);
    expect(find.text('Activities'), findsNothing);
    expect(find.bySemanticsLabel('Account: u'), findsOneWidget, reason: 'the active card title (username), as Android');

    await _tearDown(tester, h.container);
  });

  testWidgets('should_showSettingsButton_when_noAccount', (tester) async {
    final h = await homeContainer(withAccount: false);
    await _pumpHome(tester, h.container);

    expect(find.byTooltip('Settings'), findsOneWidget);
    expect(find.text('No activity yet'), findsOneWidget);

    await _tearDown(tester, h.container);
  });

  testWidgets('should_showSectionsInLayoutOrder_when_contentLoaded', (tester) async {
    final store = InMemoryStore();
    final source = HomeFakeSource(playlists: [playlist('p1', songs: 4)], recent: [album('r1')]);
    final h = await homeContainer(
      store: store,
      source: source,
      log: MemoryActivityLog([albumEvent('a1', minutesAgo: 3), artistEvent('ar', minutesAgo: 90)]),
    );
    await _seedPools(store, HomeGridPools(cachedAtMs: homeTestNow.millisecondsSinceEpoch, albums: [album('g1')], tracks: [track('t1')]));
    await _pumpHome(tester, h.container);

    expect(find.text('Activities'), findsOneWidget);
    expect(find.text('Album a1'), findsOneWidget);
    expect(find.text('3m ago'), findsOneWidget);
    expect(find.text('Jump Back In'), findsOneWidget);
    expect(find.text('Song t1'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Your Playlists'), 200, scrollable: find.byType(Scrollable).first);
    expect(find.text('Recently Played'), findsOneWidget);
    expect(find.text('4 songs'), findsOneWidget);
    final played = tester.getTopLeft(find.text('Recently Played')).dy;
    final playlists = tester.getTopLeft(find.text('Your Playlists')).dy;
    expect(played, lessThan(playlists));

    await _tearDown(tester, h.container);
  });

  testWidgets('should_playThatSongAlone_when_jumpBackInSongTapped', (tester) async {
    final store = InMemoryStore();
    final h = await homeContainer(store: store);
    await _seedPools(store, HomeGridPools(cachedAtMs: homeTestNow.millisecondsSinceEpoch, tracks: [track('t1'), track('t2')]));
    await _pumpHome(tester, h.container);

    await tester.tap(find.text('Song t1'));
    await tester.pump(const Duration(milliseconds: 50));

    expect(h.engine.queue.map((i) => i.id), ['subsonic:t1']);

    await _tearDown(tester, h.container);
  });

  testWidgets('should_pushDetailPage_when_albumCardTapped', (tester) async {
    final store = InMemoryStore();
    final h = await homeContainer(store: store);
    await _seedPools(store, HomeGridPools(cachedAtMs: homeTestNow.millisecondsSinceEpoch, albums: [album('g1')]));
    final pushes = _PushCounter();
    await _pumpHome(tester, h.container, observer: pushes);
    final before = pushes.pushes;

    await tester.tap(find.text('Album g1'));
    await tester.pump(const Duration(milliseconds: 50));

    expect(pushes.pushes, before + 1);
    expect(h.engine.calls, isEmpty);

    await _tearDown(tester, h.container);
  });

  testWidgets('should_showRetryAndSettings_when_firstLoadFails', (tester) async {
    final log = MemoryActivityLog()..failReads = true;
    final h = await homeContainer(log: log);
    await _pumpHome(tester, h.container);

    expect(find.text('Failed to load home content'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
    expect(find.text('Settings'), findsOneWidget);

    log.failReads = false;
    await tester.tap(find.text('Retry'));
    for (var i = 0; i < 8; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    expect(find.text('Retry'), findsNothing);
    expect(find.text('No activity yet'), findsOneWidget);

    await _tearDown(tester, h.container);
  });

  testWidgets('should_layOutWithoutOverflow_when_landscapeHandset', (tester) async {
    tester.view.physicalSize = const Size(2400, 1080);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    final store = InMemoryStore();
    final h = await homeContainer(
      store: store,
      log: MemoryActivityLog([albumEvent('a1'), albumEvent('a2', minutesAgo: 9), artistEvent('r')]),
      source: HomeFakeSource(playlists: [playlist('p1')]),
    );
    await _seedPools(store, HomeGridPools(cachedAtMs: homeTestNow.millisecondsSinceEpoch, albums: [for (var i = 0; i < 6; i++) album('g$i')]));
    await _pumpHome(tester, h.container);

    expect(tester.takeException(), isNull);
    expect(find.text('Album a1'), findsOneWidget);

    await _tearDown(tester, h.container);
  });

  testWidgets('should_renderWithoutMotion_when_animationsDisabled', (tester) async {
    final store = InMemoryStore();
    final h = await homeContainer(store: store, log: MemoryActivityLog([albumEvent('a1')]));
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: h.container,
        child: MaterialApp(
          theme: yoinTheme(Brightness.dark),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(disableAnimations: true),
            child: YoinWindowScope.measured(child: child!),
          ),
          home: const Scaffold(body: HomeScreen()),
        ),
      ),
    );
    for (var i = 0; i < 4; i++) {
      await tester.pump(const Duration(milliseconds: 50));
    }
    expect(find.text('Album a1'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await _tearDown(tester, h.container);
  });
}
