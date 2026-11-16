import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_grid_pool_store.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/shell/bar_scroll_hide.dart';
import 'package:yoin/shell/shell_screen.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../home/home_fakes.dart';

Future<void> _seedPools(InMemoryStore store, HomeGridPools pools) async {
  final profiles = jsonDecode(store.values['profiles.v1']!) as List;
  final id = (profiles.single as Map)['id'] as String;
  store.values[HomeGridPoolStore.keyFor(id, MediaId.providerSubsonic)] = jsonEncode(pools.toJson());
}

Future<void> _frames(WidgetTester tester, [int n = 60]) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

void main() {
  setUp(() {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
  });

  testWidgets('should_hideOnFeedAndRiseBack_when_pageOrLibraryTakesOver', (tester) async {
    tester.view.physicalSize = const Size(1080, 2400);
    tester.view.devicePixelRatio = 2.625;
    addTearDown(tester.view.reset);
    final store = InMemoryStore();
    final source = HomeFakeSource(
      playlists: [for (var i = 0; i < 8; i++) playlist('p$i', songs: 4)],
      recent: [for (var i = 0; i < 8; i++) album('r$i')],
    );
    final h = await homeContainer(
      store: store,
      source: source,
      log: MemoryActivityLog([for (var i = 0; i < 6; i++) albumEvent('a$i', minutesAgo: 3 + i)]),
    );
    await _seedPools(
      store,
      HomeGridPools(
        cachedAtMs: homeTestNow.millisecondsSinceEpoch,
        albums: [for (var i = 0; i < 8; i++) album('g$i')],
        tracks: [for (var i = 0; i < 8; i++) track('t$i')],
      ),
    );
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: h.container,
        child: MaterialApp(
          theme: yoinTheme(Brightness.light),
          navigatorKey: appNavigatorKey,
          builder: (context, child) => YoinWindowScope.measured(child: AppChrome(child: child!)),
          home: const ShellScreen(),
        ),
      ),
    );
    await _frames(tester);
    final hide = h.container.read(shellBarScrollHideProvider);
    final feed = find.descendant(of: find.byType(HomeScreen), matching: find.byType(Scrollable)).first;

    await tester.drag(feed, const Offset(0, -400));
    await _frames(tester);
    expect(hide.fraction.value, 1, reason: 'the feed scrolled the bar away');

    // A page over Home: the bar rises back with it, and stays when the page leaves.
    appNavigatorKey.currentState!.push(MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Text('page'))));
    await _frames(tester);
    expect(hide.fraction.value, 0);
    appNavigatorKey.currentState!.pop();
    await _frames(tester);
    expect(hide.fraction.value, 0);

    await tester.drag(feed, const Offset(0, -400));
    await _frames(tester);
    expect(hide.fraction.value, 1);
    h.container.read(shellSectionProvider.notifier).select(YoinSection.library);
    await _frames(tester);
    expect(hide.fraction.value, 0, reason: 'Library keeps the bar');

    await tester.pumpWidget(const SizedBox.shrink());
    h.container.dispose();
  });
}
