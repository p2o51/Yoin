import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin_symbols/yoin_symbols.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/detail/album_page_flight.dart';
import 'package:yoin/detail/album_scrapbook_page.dart';
import 'package:yoin/detail/detail_routes.dart';
import 'package:yoin/detail/flowing_titles.dart';
import 'package:yoin/memories/memory_rate_sheet.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/component/media_artwork.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

final _album = LibraryFixtures.album(
  'al1',
  name: 'Blue',
  artist: 'Joni',
  tracks: [for (var i = 1; i <= 4; i++) LibraryFixtures.track('t$i', artist: 'Joni', number: i)],
);

/// A phone: 411 × 914 dp.
const _phone = Size(1080, 2400);
const _dpr = 2.625;

Future<void> _open(WidgetTester tester) async {
  tester.view
    ..physicalSize = _phone
    ..devicePixelRatio = _dpr;
  addTearDown(tester.view.reset);
  final c = ProviderContainer(
    overrides: [
      activeSourceProvider.overrideWith((ref) async => LibraryFakeSource(albumDetails: {_album.id: _album})),
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      activityLogProvider.overrideWith((ref) async => null),
      memoryStoreProvider.overrideWith((ref) async => MemoryStore(InMemoryStore(), profileId: 'p')),
      playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
    ],
  );
  addTearDown(c.dispose);
  c.listen(playbackControllerProvider, (_, _) {});
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: c,
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: TextButton(onPressed: () => DetailRoutes.openAlbum(context, _album.id), child: const Text('open')),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await _settle(tester);
}

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> _close(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox());
  await tester.pump(const Duration(milliseconds: 1));
  await tester.pump(const Duration(milliseconds: 1));
}

/// The reshape's own cover (decoded at 640), the one that flies.
final _flying = find.byWidgetPredicate((w) => w is MediaArtwork && w.decodeWidth == 640);

/// The scrapbook's cover.
Finder get _pageCover => find.descendant(
  of: find.byType(AlbumScrapbookPage),
  matching: find.byWidgetPredicate((w) => w is MediaArtwork && w.fallbackIcon == YoinSymbols.album),
);

/// The opacity [f] is drawn at: every [Opacity] above it (the first open's staged beat is one).
double _opacityAbove(WidgetTester tester, Finder f) => tester
    .widgetList<Opacity>(find.ancestor(of: f, matching: find.byType(Opacity)))
    .fold(1.0, (alpha, o) => alpha * o.opacity);

double _page(WidgetTester tester) => tester.widget<PageView>(find.byType(PageView)).controller!.page!;

Future<TestGesture> _dragPagerBy(WidgetTester tester, double dx) async {
  final g = await tester.startGesture(tester.getCenter(find.byType(PageView)));
  // Past the touch slop first, then the rest in steps (the pager follows 1:1).
  await g.moveBy(const Offset(-24, 0));
  await tester.pump();
  for (var moved = 0.0; moved < dx; moved += 40) {
    await g.moveBy(const Offset(-40, 0));
    await tester.pump();
  }
  return g;
}

void main() {
  group('choreography', () {
    test('should_slideWithPageZero_when_noTarget', () {
      const from = Rect.fromLTWH(50, 100, 300, 300);
      final pose = AlbumFlightPose.of(from: from, target: null, t: 0.4, shift: -160);
      expect(pose.rect, from.shift(const Offset(-160, 0)));
      expect(pose.tiltDegrees, 0);
      expect(pose.landed, isFalse);
    });

    test('should_lerpToTargetAndTilt_when_targetKnown', () {
      const from = Rect.fromLTWH(50, 100, 300, 300);
      const target = Rect.fromLTWH(16, 200, 148, 148);
      final half = AlbumFlightPose.of(from: from, target: target, t: 0.5, shift: -200, targetTilt: -3);
      expect(half.rect, lerpRect(from, target, 0.5), reason: 'the pager shift is the target lerp, not a slide');
      expect(half.tiltDegrees, -1.5);
      expect(half.landed, isFalse);
      final landed = AlbumFlightPose.of(from: from, target: target, t: 1, shift: -411, targetTilt: -3);
      expect(landed.rect, target);
      expect(landed.landed, isTrue);
      expect(AlbumFlightPose.of(from: from, target: target, t: 0, shift: 0).rect, from);
    });

    test('should_fadeHeroTextByAThird_when_coverFlies', () {
      expect(albumHeroPageFade(0, flying: true), 1);
      expect(albumHeroPageFade(0.1, flying: true), closeTo(0.65, 1e-9));
      expect(albumHeroPageFade(1 / DetailTokens.albumFlightTextFade, flying: true), 0);
      expect(albumHeroPageFade(0.6, flying: true), 0);
      expect(albumHeroPageFade(0.6, flying: false), 1, reason: 'nothing flies: the text slides with its page');
    });
  });

  group('target', () {
    /// A page box (pageKey) of 400 × 600 with the cover box (coverKey) at [coverTop].
    Future<(AlbumPageFlight, ValueNotifier<double>, ValueNotifier<double>)> harness(WidgetTester tester) async {
      final page = ValueNotifier<double>(0);
      final coverTop = ValueNotifier<double>(100);
      addTearDown(page.dispose);
      addTearDown(coverTop.dispose);
      final flight = AlbumPageFlight(page: page)..pageOrigin = const Offset(0, 50);
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: Align(
            alignment: Alignment.topLeft,
            child: SizedBox(
              key: flight.pageKey,
              width: 400,
              height: 600,
              child: ValueListenableBuilder<double>(
                valueListenable: coverTop,
                builder: (context, top, _) => Stack(
                  children: [
                    Positioned(
                      left: 16,
                      top: top,
                      width: 148,
                      height: 148,
                      child: SizedBox.expand(key: flight.coverKey),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      );
      return (flight, page, coverTop);
    }

    testWidgets('should_followTheCover_when_itMovesMidFlight', (tester) async {
      final (flight, page, coverTop) = await harness(tester);
      page.value = 0.3;
      expect(flight.coverTarget(), const Rect.fromLTWH(16, 150, 148, 148));
      // The paper scrolls on under the header mid-flight: followed, not dropped (no jump back to page 0).
      coverTop.value = -60;
      await tester.pump();
      page.value = 0.6;
      expect(flight.coverTarget(), const Rect.fromLTWH(16, -10, 148, 148));
    });

    testWidgets('should_notFly_when_pageScrollsToAFocusAsTheFlightStarts', (tester) async {
      final (flight, page, _) = await harness(tester);
      var scrolling = true;
      flight.pageScrolling = () => scrolling;
      page.value = 0.2;
      expect(flight.coverTarget(), isNull);
      // Still not mid-flight once the scroll ends: the cover went with page 0 and stays with it.
      scrolling = false;
      page.value = 0.5;
      expect(flight.coverTarget(), isNull);
      // At rest the next flight decides again.
      page.value = 1;
      expect(flight.coverTarget(), isNotNull);
    });

    testWidgets('should_slideBack_when_coverScrolledUnderHeaderWhileOnScrapbook', (tester) async {
      final (flight, page, coverTop) = await harness(tester);
      page.value = 1;
      expect(flight.coverTarget(), isNotNull);
      coverTop.value = -40;
      await tester.pump();
      // The first frame back from the scrapbook sees where the paper is now.
      page.value = 0.98;
      expect(flight.coverTarget(), isNull);
      expect(flight.coverFlying(), isFalse);
    });
  });

  group('page', () {
    testWidgets('should_flyCoverToScrapbook_when_pagerDragged', (tester) async {
      await _open(tester);
      final rest = tester.getRect(_flying);

      // Past half way, so the release settles on the scrapbook.
      final g = await _dragPagerBy(tester, 240);
      final t = _page(tester);
      expect(t, inExclusiveRange(0.5, 0.9));
      expect(find.byType(AlbumScrapbookPage), findsOneWidget);
      // The page's own cover and emblem wait; the emblem rides over the flying cover.
      expect(_opacityAbove(tester, _pageCover), 0);
      expect(find.byType(CompositedTransformFollower), findsOneWidget);
      expect(
        find.descendant(of: find.byType(CompositedTransformFollower), matching: find.byType(AlbumScoreEmblem)),
        findsOneWidget,
      );
      // The hero text is gone a third of the way in.
      if (t > 1 / DetailTokens.albumFlightTextFade) {
        expect(_opacityAbove(tester, find.byType(FlowingTitles)), 0);
      }
      final mid = tester.getRect(_flying);
      // On its way, not slid off with page 0: it shrinks toward the scrapbook's cover.
      expect(mid.center.dx, greaterThan(rest.center.dx - t * 411 + 40));
      expect(mid.shortestSide, lessThan(rest.shortestSide));

      await g.up();
      await _settle(tester);
      expect(_page(tester), closeTo(1, 1e-3));
      // Landed: the page's cover shows, the flying one stands down where it lies, and takes no taps.
      expect(_opacityAbove(tester, _pageCover), 1);
      expect(_opacityAbove(tester, _flying), 0);
      expect(
        tester.widget<IgnorePointer>(find.ancestor(of: _flying, matching: find.byType(IgnorePointer)).first).ignoring,
        isTrue,
      );
      expect(find.byType(CompositedTransformFollower), findsNothing);
      final landed = tester.getRect(_flying);
      final page = tester.getRect(_pageCover);
      expect(landed.center.dx, closeTo(page.center.dx, 0.5));
      expect(landed.center.dy, closeTo(page.center.dy, 0.5));
      expect(landed.width, closeTo(page.width, 0.5));
      await _close(tester);
    });

    testWidgets('should_flyBackToTheHero_when_draggedBackToPageZero', (tester) async {
      await _open(tester);
      final rest = tester.getRect(_flying);
      await tester.tap(find.bySemanticsLabel('Scrapbook'));
      await _settle(tester);
      expect(_page(tester), closeTo(1, 1e-3));

      final g = await tester.startGesture(tester.getCenter(find.byType(PageView)));
      await g.moveBy(const Offset(24, 0));
      await tester.pump();
      for (var i = 0; i < 6; i++) {
        await g.moveBy(const Offset(40, 0));
        await tester.pump();
      }
      expect(_page(tester), inExclusiveRange(0.1, 0.5));
      expect(_opacityAbove(tester, _flying), 1, reason: 'flying back');
      expect(_opacityAbove(tester, _pageCover), 0);

      await g.up();
      await _settle(tester);
      expect(_page(tester), closeTo(0, 1e-3));
      expect(tester.getRect(_flying), rest);
      expect(_opacityAbove(tester, find.byType(FlowingTitles)), 1);
      await _close(tester);
    });

    testWidgets('should_slideCoverAwayAndKeepPageCover_when_reducedMotion', (tester) async {
      tester.platformDispatcher.accessibilityFeaturesTestValue = const FakeAccessibilityFeatures(
        disableAnimations: true,
      );
      addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
      await _open(tester);
      final rest = tester.getRect(_flying);

      final g = await _dragPagerBy(tester, 160);
      final t = _page(tester);
      expect(t, inExclusiveRange(0.2, 0.8));
      // Nothing flies: the hero cover goes with page 0, the scrapbook keeps its own.
      expect(tester.getRect(_flying).center.dx, closeTo(rest.center.dx - t * 411, 1));
      expect(tester.getRect(_flying).size, rest.size);
      expect(_opacityAbove(tester, _pageCover), 1);
      expect(find.byType(CompositedTransformFollower), findsNothing);
      await g.up();
      await _settle(tester);
      await _close(tester);
    });
  });
}
