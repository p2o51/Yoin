import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart' show ValueListenable;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/highlight/lyric_highlight.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_edit.dart';
import 'package:yoin/home/home_lyric_count_handle.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/home/lyric_highlight_section.dart';
import 'package:yoin/home/lyric_highlight_view_model.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/navigation/yoin_push_route.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

Track _song(String id, {MediaId? songId}) => Track(
  id: songId ?? MediaId.subsonic(id),
  title: 'Song $id',
  artist: 'Artist $id',
  album: 'Album $id',
  albumId: MediaId.subsonic('al-$id'),
  coverArt: CoverSourceRelative('c-$id'),
  durationSec: 200,
);

String _profileId(InMemoryStore store) =>
    ((jsonDecode(store.values['profiles.v1']!) as List).single as Map)['id'] as String;

Future<void> _frames(WidgetTester tester, [int n = 20]) async {
  for (var i = 0; i < n; i++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
}

void main() {
  setUp(() {
    HomeViewModel.clearProcessCache();
    HomeScreen.resetProcessState();
  });

  group('keeping the user pick', () {
    test('should_storeTheLinesAsYourPick_when_sharedOrSaved', () async {
      final h = await homeContainer();
      final c = h.container;
      await c
          .read(lyricHighlightKeeperProvider)
          .keepUserPick(track: _song('1'), lines: [' first ', '', 'second'], startMs: 12000);
      final row = (await LyricHighlightStore(h.store).usable(_profileId(h.store), MediaId.providerSubsonic)).single;
      expect(row.songId, '1');
      expect(row.text, 'first\nsecond');
      expect(row.startMs, 12000);
      expect(row.isUserPick, isTrue);
      expect(row.coverArtId, 'c-1');
      expect(row.albumId, 'al-1');
      expect(row.durationMs, 200000);
      c.dispose();
    });

    test('should_keepWhenItWasShown_when_theSongIsPickedAgain', () async {
      final h = await homeContainer();
      final c = h.container;
      final store = LyricHighlightStore(h.store);
      final id = _profileId(h.store);
      await c.read(lyricHighlightKeeperProvider).keepUserPick(track: _song('1'), lines: ['old'], startMs: 1000);
      await store.markShown(id, MediaId.providerSubsonic, ['1'], 42);
      await c.read(lyricHighlightKeeperProvider).keepUserPick(track: _song('1'), lines: ['new'], startMs: 2000);
      final row = (await store.get(id, MediaId.providerSubsonic, '1'))!;
      expect(row.text, 'new');
      expect(row.lastShownAt, 42);
      c.dispose();
    });

    test('should_keepNothing_when_theSongIsAnotherProviders', () async {
      final h = await homeContainer();
      final c = h.container;
      await c
          .read(lyricHighlightKeeperProvider)
          .keepUserPick(
            track: _song('1', songId: MediaId(MediaId.providerSpotify, '1')),
            lines: ['x'],
          );
      await c.read(lyricHighlightKeeperProvider).keepUserPick(track: _song('2'), lines: ['  ']);
      expect(h.store.values.keys.where((k) => k.startsWith('lyric_highlights.v1.')), isEmpty);
      c.dispose();
    });

    test('should_drawTheNewPick_when_keptWhileHomeIsUp', () async {
      final h = await homeContainer();
      final c = h.container;
      c.listen(lyricHighlightCardsProvider, (_, _) {});
      await settle(() => false, rounds: 20);
      expect(c.read(lyricHighlightCardsProvider), isEmpty);
      await c.read(lyricHighlightKeeperProvider).keepUserPick(track: _song('1'), lines: ['a line'], startMs: 5000);
      await settle(() => c.read(lyricHighlightCardsProvider).isNotEmpty);
      final card = c.read(lyricHighlightCardsProvider).single;
      expect(card.songId, MediaId.subsonic('1'));
      expect(card.kind, LyricHighlightBucket.user);
      // Put on show, so the next set prefers the others.
      final row = await LyricHighlightStore(h.store).get(_profileId(h.store), MediaId.providerSubsonic, '1');
      expect(row!.lastShownAt, homeTestNow.millisecondsSinceEpoch);
      c.dispose();
    });

    test('should_playTheSongFromJustBeforeTheLine_when_cardPlayed', () async {
      final h = await homeContainer();
      final c = h.container;
      c.listen(playbackControllerProvider, (_, _) {});
      final card = HomeLyricHighlight.fromRow(
        MediaId.providerSubsonic,
        LyricHighlightRow(
          songId: '1',
          title: 'Song 1',
          artist: 'A',
          album: 'B',
          albumId: '',
          coverArtId: null,
          durationMs: 200000,
          text: 'x',
          startMs: 30000,
          model: lyricHighlightUserPick,
          attemptedAt: 0,
        ),
        LyricHighlightBucket.user,
      );
      await c.read(lyricHighlightCardsProvider.notifier).play(card);
      await settle(() => h.engine.calls.contains('setQueue'));
      expect(h.engine.calls, contains('setQueue'));
      final state = c.read(playbackControllerProvider);
      expect(state.queue.map((t) => t.id), [MediaId.subsonic('1')]);
      expect(state.positionMs, 30000 - lyricHighlightSeekLeadMs);
      c.dispose();
    });
  });

  group('on Home', () {
    Future<ProviderContainer> pumpHome(WidgetTester tester, {bool introSeen = true, int picks = 3}) async {
      tester.view.physicalSize = const Size(400, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final h = await homeContainer(lyricIntroSeen: introSeen);
      for (var i = 0; i < picks; i++) {
        await h.container
            .read(lyricHighlightKeeperProvider)
            .keepUserPick(track: _song('$i'), lines: ['line of song $i'], startMs: 30000);
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
      await _frames(tester);
      return h.container;
    }

    Future<void> done(WidgetTester tester, ProviderContainer c) async {
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump(const Duration(seconds: 1));
      c.dispose();
    }

    testWidgets('should_showEachPickAsACardWithDots_when_picksAreKept', (tester) async {
      final c = await pumpHome(tester);
      expect(find.byType(LyricHighlightCard), findsNWidgets(3));
      expect(find.text('Your pick'), findsNWidgets(3));
      expect(find.text('line of song 0'), findsOneWidget);
      expect(find.byType(LyricPageDots), findsOneWidget);
      // No title on Home: the cards say what they are.
      expect(find.text('Lyric Cards'), findsNothing);
      await done(tester, c);
    });

    testWidgets('should_fillTheWidthWithoutDots_when_oneCard', (tester) async {
      final c = await pumpHome(tester, picks: 1);
      expect(find.byType(LyricHighlightCard), findsOneWidget);
      expect(find.byType(LyricPageDots), findsNothing);
      // The phone's content width (400 less the feed's margins), not the 290 of a shelf card.
      expect(tester.getSize(find.byType(LyricHighlightCard)).width, greaterThan(300));
      await done(tester, c);
    });

    testWidgets('should_showNothing_when_noPickAndIntroAnswered', (tester) async {
      final c = await pumpHome(tester, picks: 0);
      expect(find.byType(LyricHighlightSection), findsNothing);
      expect(find.byType(LyricHighlightIntroCard), findsNothing);
      await done(tester, c);
    });

    testWidgets('should_showTheIntroUntilGotIt_when_firstUse', (tester) async {
      final c = await pumpHome(tester, introSeen: false);
      expect(find.text('You found a beta feature'), findsOneWidget);
      expect(find.byType(LyricHighlightSection), findsNothing, reason: 'the intro stands in for the cards');
      await tester.ensureVisible(find.text('Got it'));
      await tester.tap(find.text('Got it'));
      await _frames(tester);
      expect(find.text('You found a beta feature'), findsNothing);
      expect(find.byType(LyricHighlightCard), findsNWidgets(3));
      expect(await LyricHighlightStore(c.read(keyValueStoreProvider)).introSeen(), isTrue);
      await done(tester, c);
    });

    testWidgets('should_playFromTheLine_when_cardTapped', (tester) async {
      final c = await pumpHome(tester, picks: 1);
      await tester.tap(find.byType(LyricHighlightCard));
      await _frames(tester, 10);
      final state = c.read(playbackControllerProvider);
      expect(state.current?.id, MediaId.subsonic('0'));
      expect(state.positionMs, 30000 - lyricHighlightSeekLeadMs);
      await done(tester, c);
    });

    testWidgets('should_showTwoCards_when_countHandlePulledUpTwoSteps', (tester) async {
      final c = await pumpHome(tester, picks: 4);
      expect(find.byType(LyricHighlightCard), findsNWidgets(4));
      c.read(homeEditProvider.notifier).enter();
      await _frames(tester, 30);
      final handle = find.byKey(homeLyricCountHandleKey);
      expect(handle, findsOneWidget);
      final gesture = await tester.startGesture(tester.getCenter(handle));
      // The first 40 open the dots; then every 36 is a card.
      await gesture.moveBy(const Offset(0, 20));
      await tester.pump();
      await gesture.moveBy(const Offset(0, 20));
      await tester.pump();
      expect(find.byType(HomeLyricCountPicker), findsOneWidget);
      expect(find.text('4 cards'), findsOneWidget);
      await gesture.moveBy(const Offset(0, -40));
      await tester.pump();
      await gesture.moveBy(const Offset(0, -40));
      await tester.pump();
      expect(find.text('2 cards'), findsOneWidget);
      await gesture.up();
      await _frames(tester, 30);
      expect(c.read(homeLayoutProvider).rowsOf(HomeSection.lyricHighlight), HomeRowPreset.s);
      expect(find.byType(HomeLyricCountPicker), findsNothing);
      expect(c.read(homeEditProvider).canUndo, isTrue);
      c.read(homeEditProvider.notifier).done();
      await _frames(tester, 30);
      expect(find.byType(LyricHighlightCard), findsNWidgets(2));
      await done(tester, c);
    });

    testWidgets('should_keepTheCount_when_handleLetGoBeforeTheDotsOpen', (tester) async {
      final c = await pumpHome(tester, picks: 4);
      c.read(homeEditProvider.notifier).enter();
      await _frames(tester, 30);
      final gesture = await tester.startGesture(tester.getCenter(find.byKey(homeLyricCountHandleKey)));
      await gesture.moveBy(const Offset(0, 20));
      await tester.pump();
      await gesture.up();
      await _frames(tester, 20);
      expect(c.read(homeLayoutProvider).rowsOf(HomeSection.lyricHighlight), HomeRowPreset.l);
      expect(c.read(homeEditProvider).canUndo, isFalse);
      await done(tester, c);
    });
  });

  group('another set', () {
    Set<String> shown(ProviderContainer c) => {for (final card in c.read(lyricHighlightCardsProvider)) card.songId.rawId};

    testWidgets('should_drawAnotherSet_when_aPageOverHomeGoesAfterAWhile', (tester) async {
      var now = homeTestNow;
      final nav = GlobalKey<NavigatorState>();
      final c = await _pumpHomeForSets(tester, clock: () => now, navigatorKey: nav);
      final first = shown(c);
      expect(first, hasLength(4));
      now = now.add(lyricHighlightRotateAfter + const Duration(minutes: 1));
      // A sheet over Home is not a page leaving: the set stays.
      unawaited(
        showModalBottomSheet<void>(
          context: tester.element(find.byType(HomeScreen)),
          builder: (_) => const SizedBox(height: 100),
        ),
      );
      await _frames(tester, 30);
      nav.currentState!.pop();
      await _frames(tester, 30);
      expect(shown(c), first);
      nav.currentState!.push(YoinPushRoute<void>(builder: (_) => const Scaffold(body: SizedBox.expand())));
      await _frames(tester, 40);
      nav.currentState!.pop();
      await _frames(tester, 40);
      // The two never put on show come first.
      expect(shown(c), containsAll({for (var i = 0; i < 6; i++) '$i'}.difference(first)));
      await _doneHome(tester, c);
    });

    // The shell mutes a hidden page's tickers (shell_screen _Page): Library in front, Home's are off.
    testWidgets('should_drawAnotherSet_when_backFromLibraryAfterAWhile', (tester) async {
      var now = homeTestNow;
      final visible = ValueNotifier(true);
      addTearDown(visible.dispose);
      final c = await _pumpHomeForSets(tester, clock: () => now, visible: visible);
      final first = shown(c);
      visible.value = false;
      await _frames(tester, 5);
      now = now.add(lyricHighlightRotateAfter + const Duration(minutes: 1));
      visible.value = true;
      await _frames(tester, 20);
      expect(shown(c), containsAll({for (var i = 0; i < 6; i++) '$i'}.difference(first)));
      await _doneHome(tester, c);
    });

    testWidgets('should_keepTheSet_when_backFromLibrarySoon', (tester) async {
      var now = homeTestNow;
      final visible = ValueNotifier(true);
      addTearDown(visible.dispose);
      final c = await _pumpHomeForSets(tester, clock: () => now, visible: visible);
      final first = shown(c);
      visible.value = false;
      await _frames(tester, 5);
      now = now.add(const Duration(minutes: 5));
      visible.value = true;
      await _frames(tester, 20);
      expect(shown(c), first);
      await _doneHome(tester, c);
    });
  });

  group('count engine', () {
    test('should_countWholeStepsFromWhereTheDotsOpened_when_dragged', () {
      expect(lyricCountAt(start: 4, dy: 40, anchor: 40), 4);
      expect(lyricCountAt(start: 4, dy: 40 - 35, anchor: 40), 4);
      expect(lyricCountAt(start: 4, dy: 40 - 36, anchor: 40), 3);
      expect(lyricCountAt(start: 2, dy: 40 + 80, anchor: 40), 4);
      expect(lyricCountAt(start: 3, dy: -200, anchor: 40), 2);
    });

    test('should_tickPerCountAndConfirmOnce_when_pulledAndDropped', () {
      final feedback = CountingLyricCountFeedback();
      final commits = <HomeRowPreset>[];
      var saved = 4;
      final engine = HomeLyricCountEngine(
        vsync: const TestVSync(),
        editing: () => true,
        blocked: () => false,
        saved: () => saved,
        commit: (p) {
          commits.add(p);
          final changed = lyricCardsFor(p) != saved;
          saved = lyricCardsFor(p);
          return changed;
        },
        reduced: () => true,
        feedback: feedback,
      );
      expect(engine.grab(100), isTrue);
      expect(engine.grab(100), isFalse, reason: 'one finger at a time');
      engine.dragTo(140);
      expect(engine.reveal.value, 1);
      engine.dragTo(140 - 36);
      engine.dragTo(140 - 72);
      expect(engine.count, 2);
      expect(feedback.ticks, 2);
      engine.release();
      expect(commits, [HomeRowPreset.s]);
      expect(feedback.confirms, 1);
      expect(engine.held, isFalse);
      // A keyboard step.
      expect(engine.step(1), isTrue);
      expect(saved, 3);
      engine.dispose();
    });

    test('should_keepTheCount_when_editModeClosedUnderTheFinger', () {
      var editing = true;
      final commits = <HomeRowPreset>[];
      final feedback = CountingLyricCountFeedback();
      final engine = HomeLyricCountEngine(
        vsync: const TestVSync(),
        editing: () => editing,
        blocked: () => false,
        saved: () => 4,
        commit: (p) {
          commits.add(p);
          return true;
        },
        reduced: () => true,
        feedback: feedback,
      );
      expect(engine.grab(100), isTrue);
      engine.dragTo(140);
      engine.dragTo(140 - 72);
      expect(engine.count, 2);
      // The app went to the background: edit mode closed, the finger still down.
      editing = false;
      engine.release();
      expect(commits, isEmpty);
      expect(feedback.confirms, 0);
      expect(engine.held, isFalse);
      engine.dispose();
    });

    test('should_takeNoFinger_when_notEditingOrBlocked', () {
      var editing = false;
      var blocked = false;
      final engine = HomeLyricCountEngine(
        vsync: const TestVSync(),
        editing: () => editing,
        blocked: () => blocked,
        saved: () => 4,
        commit: (_) => true,
        reduced: () => true,
        feedback: CountingLyricCountFeedback(),
      );
      expect(engine.grab(0), isFalse);
      editing = true;
      blocked = true;
      expect(engine.grab(0), isFalse);
      blocked = false;
      expect(engine.grab(0), isTrue);
      engine.release(cancelled: true);
      engine.dispose();
    });
  });

  group('shelf', () {
    test('should_peekTheNextCard_when_severalOnAPhone', () {
      expect(lyricCardWidth(368, single: false), HomeLyricCardTokens.width);
      expect(lyricCardWidth(300, single: false), 260);
      expect(lyricCardWidth(368, single: true), 368);
    });

    test('should_markTheLastCard_when_theShelfRanOut', () {
      expect(lyricShelfPage(pixels: 0, maxScroll: 500, pitch: 298, count: 3), 0);
      expect(lyricShelfPage(pixels: 290, maxScroll: 500, pitch: 298, count: 3), 1);
      expect(lyricShelfPage(pixels: 500, maxScroll: 500, pitch: 298, count: 3), 2);
    });

    test('should_settleOnACardStart_when_released', () {
      double to(double pixels, double velocity) =>
          LyricShelfPhysics.target(pixels: pixels, velocity: velocity, pitch: 298, min: 0, max: 500, minVelocity: 1);
      expect(to(100, 0), 0);
      expect(to(200, 0), 298);
      expect(to(100, 500), 298, reason: 'a fling goes one card on');
      expect(to(450, 0), 500, reason: 'the last stops where the shelf runs out');
    });

    test('should_settleByTheCurrentPitch_when_theWindowResized', () {
      var pitch = 298.0;
      final physics = LyricShelfPhysics(pitchOf: () => pitch);
      ScrollMetrics at(double pixels) => FixedScrollMetrics(
        minScrollExtent: 0,
        maxScrollExtent: 1000,
        pixels: pixels,
        viewportDimension: 400,
        axisDirection: AxisDirection.right,
        devicePixelRatio: 1,
      );
      expect(physics.createBallisticSimulation(at(140), 0)!.x(10), closeTo(0, 1));
      // Narrower cards after a resize: the same physics object settles on the new starts.
      pitch = 250;
      expect(physics.createBallisticSimulation(at(140), 0)!.x(10), closeTo(250, 1));
    });
  });

  test('should_offerTheSectionOnEveryDevice_when_listed', () {
    expect(HomeSection.lyricHighlight.availableInV1, isTrue);
  });
}

/// Home with six picks (four on show), for the tests that draw another set.
Future<ProviderContainer> _pumpHomeForSets(
  WidgetTester tester, {
  required DateTime Function() clock,
  GlobalKey<NavigatorState>? navigatorKey,
  ValueListenable<bool>? visible,
}) async {
  tester.view.physicalSize = const Size(400, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final h = await homeContainer(clock: clock);
  for (var i = 0; i < 6; i++) {
    await h.container
        .read(lyricHighlightKeeperProvider)
        .keepUserPick(track: _song('$i'), lines: ['line of song $i'], startMs: 30000);
  }
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: h.container,
      child: MaterialApp(
        navigatorKey: navigatorKey,
        theme: yoinTheme(Brightness.light),
        builder: (context, child) => YoinWindowScope.measured(child: child!),
        home: Scaffold(
          body: ValueListenableBuilder<bool>(
            valueListenable: visible ?? const AlwaysStoppedAnimation(true),
            child: const HomeScreen(),
            builder: (context, on, child) => TickerMode(enabled: on, child: child!),
          ),
        ),
      ),
    ),
  );
  await _frames(tester);
  return h.container;
}

Future<void> _doneHome(WidgetTester tester, ProviderContainer c) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump(const Duration(seconds: 1));
  c.dispose();
}
