import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart' show Ticker, TickerCallback;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/system_requests.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/shell/bar_scroll_hide.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_edge_split_group.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/navigation/pane/detail_pane.dart';

import '../support/nowplaying_fakes.dart';

/// A scroll position as the feed would report it.
ScrollMetrics _metrics(double pixels, {double max = 1000}) => FixedScrollMetrics(
  minScrollExtent: 0,
  maxScrollExtent: max,
  pixels: pixels,
  viewportDimension: 800,
  axisDirection: AxisDirection.down,
  devicePixelRatio: 1,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('ShellBarScrollHide', () {
    late ShellBarScrollHide hide;
    setUp(() => hide = ShellBarScrollHide());
    tearDown(() => hide.dispose());

    test('should_moveOneToOneWithContent_when_feedScrolls', () {
      hide.scrolled(48);
      expect(hide.fraction.value, closeTo(0.5, 1e-9));
      hide.scrolled(200);
      expect(hide.fraction.value, 1, reason: 'clamped once the bar is out');
      hide.scrolled(-24);
      expect(hide.fraction.value, closeTo(0.75, 1e-9));
    });

    test('should_settleToNearerEnd_when_releasedSlowly', () {
      hide.scrolled(60); // 0.625
      hide.released(0);
      expect(hide.fraction.value, 1, reason: 'no ticker: a settle lands at once');
      hide.scrolled(-60); // 0.375
      hide.released(100);
      expect(hide.fraction.value, 0);
    });

    test('should_followFlingDirection_when_releasedFast', () {
      hide.scrolled(20); // nearer shown, but flung toward hiding
      hide.released(BarTokens.scrollHideFlingDecisionPx + 1);
      expect(hide.fraction.value, 1);
      hide.scrolled(-80); // nearer hidden, flung toward showing
      hide.released(-BarTokens.scrollHideFlingDecisionPx - 1);
      expect(hide.fraction.value, 0);
    });

    test('should_stayPut_when_releasedAtAnEnd', () {
      hide.scrolled(500);
      hide.released(-5000);
      expect(hide.fraction.value, 1, reason: 'Android onPostFling leaves an end alone');
    });

    test('should_bringBarBackAndIgnoreScroll_when_shellOrChromeStopsAllowing', () {
      hide.scrolled(96);
      hide.shellAllows = false;
      expect(hide.fraction.value, 0);
      hide.scrolled(96);
      expect(hide.fraction.value, 0, reason: 'disabled: the feed no longer moves it');
      hide.shellAllows = true;
      hide.scrolled(96);
      expect(hide.fraction.value, 1);
      hide.chromeAllows = false;
      expect(hide.fraction.value, 0);
    });

    test('should_keepSuccessorsTicker_when_previousChromeDetachesLate', () {
      final first = _Vsync();
      final second = _Vsync();
      hide.attach(first);
      hide.attach(second);
      hide.detach(first);
      hide.scrolled(60); // 0.625
      hide.released(0);
      expect(hide.fraction.value, closeTo(0.625, 1e-9), reason: 'still on a spring, not snapped: the ticker stayed');
      hide.detach(second);
      hide.released(0);
      expect(hide.fraction.value, 1, reason: 'its own chrome let go: a settle lands at once');
    });

    test('should_returnUnseen_when_windowGoesAway', () {
      hide.scrolled(96);
      hide.showUnseen();
      expect(hide.fraction.value, 0);
    });
  });

  group('scrollHideDeltaOf', () {
    test('should_countWholeDelta_when_insideContent', () {
      expect(scrollHideDeltaOf(_metrics(300), 40), 40);
      expect(scrollHideDeltaOf(_metrics(300), -40), -40);
    });

    test('should_countOnlyPullPastTop_when_bouncingAtTop', () {
      expect(scrollHideDeltaOf(_metrics(-10), -30), -30, reason: '20 down to the top, 10 past it: all reveal');
      expect(scrollHideDeltaOf(_metrics(0), 10), 0, reason: 'springing back from past the top is no scroll');
    });

    test('should_ignoreBounce_when_pastBottomOrFeedTooShort', () {
      expect(scrollHideDeltaOf(_metrics(1030, max: 1000), 40), 10, reason: 'only the 10 inside the content');
      expect(scrollHideDeltaOf(_metrics(30, max: 0), 30), 0, reason: 'a feed too short to scroll never hides the bar');
    });
  });

  group('the bar under a scrolling feed', () {
    Widget app(List<Override> overrides, {int rows = 60}) => ProviderScope(
      overrides: overrides,
      child: MaterialApp(
        navigatorKey: appNavigatorKey,
        builder: (context, child) => YoinWindowScope.measured(child: AppChrome(child: child!)),
        home: Scaffold(body: _Feed(rows: rows)),
      ),
    );

    void phone(WidgetTester tester) {
      tester.view.physicalSize = const Size(1080, 2400);
      tester.view.devicePixelRatio = 2.625;
      addTearDown(tester.view.reset);
    }

    double libraryTop(WidgetTester tester) => tester.getTopLeft(find.text('Library')).dy;

    testWidgets('should_hideBarThenBringItBack_when_feedScrollsDownThenUp', (tester) async {
      phone(tester);
      final controller = NowPlayingFakeController(NowPlayingState.idle);
      await tester.pumpWidget(app(nowPlayingOverrides(controller: controller)));
      await tester.pumpAndSettle();
      final rest = libraryTop(tester);

      await tester.drag(find.byType(ListView), const Offset(0, -300));
      await tester.pumpAndSettle();
      final container = ProviderScope.containerOf(tester.element(find.byType(ListView)));
      expect(container.read(shellBarScrollHideProvider).fraction.value, 1);
      expect(libraryTop(tester), closeTo(rest + MemoriesTokens.barHideTravel, 0.5));

      await tester.drag(find.byType(ListView), const Offset(0, 200));
      await tester.pumpAndSettle();
      expect(container.read(shellBarScrollHideProvider).fraction.value, 0);
      expect(libraryTop(tester), closeTo(rest, 0.5));
    });

    testWidgets('should_settleBack_when_releasedLessThanHalfway', (tester) async {
      phone(tester);
      final controller = NowPlayingFakeController(NowPlayingState.idle);
      await tester.pumpWidget(app(nowPlayingOverrides(controller: controller)));
      await tester.pumpAndSettle();
      final container = ProviderScope.containerOf(tester.element(find.byType(ListView)));
      final gesture = await tester.startGesture(tester.getCenter(find.byType(ListView)));
      // Slowly, so the release carries no fling.
      for (var i = 0; i < 10; i++) {
        await gesture.moveBy(const Offset(0, -3));
        await tester.pump(const Duration(milliseconds: 100));
      }
      expect(container.read(shellBarScrollHideProvider).fraction.value, inExclusiveRange(0, 0.5));
      await gesture.up();
      await tester.pump(const Duration(milliseconds: 16));
      await tester.pumpAndSettle();
      expect(container.read(shellBarScrollHideProvider).fraction.value, 0);
    });

    testWidgets('should_keepBar_when_feedTooShortToScroll', (tester) async {
      phone(tester);
      final controller = NowPlayingFakeController(NowPlayingState.idle);
      await tester.pumpWidget(app(nowPlayingOverrides(controller: controller), rows: 2));
      await tester.pumpAndSettle();
      await tester.drag(find.byType(ListView), const Offset(0, -300));
      await tester.pumpAndSettle();
      final container = ProviderScope.containerOf(tester.element(find.byType(ListView)));
      expect(container.read(shellBarScrollHideProvider).fraction.value, 0);
    });

    testWidgets('should_riseBack_when_pageIsPushedOverTheFeed', (tester) async {
      phone(tester);
      final controller = NowPlayingFakeController(NowPlayingState.idle);
      await tester.pumpWidget(app(nowPlayingOverrides(controller: controller)));
      await tester.pumpAndSettle();
      final container = ProviderScope.containerOf(tester.element(find.byType(ListView)));
      final hide = container.read(shellBarScrollHideProvider);
      await tester.drag(find.byType(ListView), const Offset(0, -300));
      await tester.pumpAndSettle();
      expect(hide.fraction.value, 1);

      // ShellScreen's side: a page over the shell stops allowing it; the bar rises on its spring.
      hide.shellAllows = false;
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(hide.fraction.value, inExclusiveRange(0, 1), reason: 'a spring, not a cut');
      await tester.pumpAndSettle();
      expect(hide.fraction.value, 0);
    });

    testWidgets('should_riseOnSpring_when_detailColumnOpensOverScrolledFeed', (tester) async {
      phone(tester);
      addTearDown(() => DetailPaneController.columnOpen.value = false);
      final controller = NowPlayingFakeController(NowPlayingState.idle);
      await tester.pumpWidget(app(nowPlayingOverrides(controller: controller)));
      await tester.pumpAndSettle();
      final rest = libraryTop(tester);
      final container = ProviderScope.containerOf(tester.element(find.byType(ListView)));
      final hide = container.read(shellBarScrollHideProvider);
      await tester.drag(find.byType(ListView), const Offset(0, -300));
      await tester.pumpAndSettle();
      expect(hide.fraction.value, 1);

      // The column opens (ShellScreen: its pane has entries, so the shell stops allowing it).
      DetailPaneController.columnOpen.value = true;
      hide.shellAllows = false;
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      final midway = libraryTop(tester);
      expect(midway, greaterThan(rest + 1), reason: 'the bar rises with the column, not in one frame');
      expect(midway, lessThan(rest + MemoriesTokens.barHideTravel));
      await tester.pumpAndSettle();
      expect(libraryTop(tester), closeTo(rest, 0.5));
    });

    testWidgets('should_keepEdgeCapsules_when_feedScrollsInShortWindow', (tester) async {
      tester.view.physicalSize = const Size(844, 390) * 2;
      tester.view.devicePixelRatio = 2;
      addTearDown(tester.view.reset);
      final controller = NowPlayingFakeController(NowPlayingState.idle);
      await tester.pumpWidget(app(nowPlayingOverrides(controller: controller)));
      await tester.pump(const Duration(seconds: 1));
      final group = find.byType(YoinEdgeSplitGroup);
      final rest = tester.getRect(group);
      await tester.drag(find.byType(ListView), const Offset(0, -300));
      await tester.pump(const Duration(seconds: 1));
      final container = ProviderScope.containerOf(tester.element(find.byType(ListView)));
      expect(container.read(shellBarScrollHideProvider).fraction.value, 0, reason: 'the edge capsules stay');
      expect(tester.getRect(group), rest);
    });

    testWidgets('should_bringBarBack_when_nowPlayingOpens', (tester) async {
      phone(tester);
      final controller = NowPlayingFakeController(playingState(nowPlayingTracks(2)));
      await tester.pumpWidget(app(nowPlayingOverrides(controller: controller)));
      await tester.pump(const Duration(seconds: 1));
      await tester.drag(find.byType(ListView), const Offset(0, -300));
      await tester.pump(const Duration(seconds: 1));
      final container = ProviderScope.containerOf(tester.element(find.byType(ListView)));
      final hide = container.read(shellBarScrollHideProvider);
      expect(hide.fraction.value, 1);
      container.read(nowPlayingOpenRequestsProvider.notifier).request();
      for (var i = 0; i < 90; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      expect(hide.fraction.value, 0, reason: 'the player keeps the bar (YoinNavHost !showNowPlaying)');
    });
  });
}

/// A ticker source of its own (two chromes are two of these).
class _Vsync implements TickerProvider {
  @override
  Ticker createTicker(TickerCallback onTick) => Ticker(onTick);
}

/// The Home feed's wiring: its own scroll notifications feed the window's scroll-hide.
class _Feed extends ConsumerWidget {
  const _Feed({required this.rows});

  final int rows;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hide = ref.read(shellBarScrollHideProvider);
    return NotificationListener<ScrollNotification>(
      onNotification: (n) {
        hide.onScrollNotification(n, devicePixelRatio: MediaQuery.devicePixelRatioOf(context));
        return false;
      },
      child: ListView(
        physics: const ClampingScrollPhysics(),
        children: [for (var i = 0; i < rows; i++) SizedBox(height: 80, child: Text('row $i'))],
      ),
    );
  }
}
