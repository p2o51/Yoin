import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/detail/album_detail_screen.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/playback/local_ratings.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/playback_context.dart';
import 'package:yoin/playback/system_requests.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/shell/bar_suppression.dart';
import 'package:yoin/shell/detail_chrome.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/nowplaying_fakes.dart';
import '../support/playback_fakes.dart';

Widget _app(List<Override> overrides) => ProviderScope(
  overrides: overrides,
  child: MaterialApp(
    navigatorKey: appNavigatorKey,
    builder: (context, child) => YoinWindowScope.measured(child: AppChrome(child: child!)),
    home: const Scaffold(body: SizedBox.expand()),
  ),
);

void _phone(WidgetTester tester) {
  tester.view.physicalSize = const Size(1080, 2400);
  tester.view.devicePixelRatio = 2.625;
  addTearDown(tester.view.reset);
}

Finder get _player => find.text('PLAYING FROM ALBUM');

final _album = AlbumPlaybackContext(albumId: MediaId.subsonic('al'), name: 'Album');

void main() {
  late NowPlayingFakeController controller;

  Future<void> pump(WidgetTester tester, NowPlayingState state, {NowPlayingFakeSource? source}) async {
    _phone(tester);
    controller = NowPlayingFakeController(state);
    await tester.pumpWidget(_app(nowPlayingOverrides(controller: controller, source: source)));
    await tester.pumpAndSettle();
  }

  Future<void> openPlayer(WidgetTester tester) async {
    await tester.tap(find.bySemanticsLabel(RegExp('Open player')));
    await tester.pumpAndSettle();
  }

  testWidgets('should_showNoPill_when_nothingPlays', (tester) async {
    await pump(tester, NowPlayingState.idle);
    expect(find.bySemanticsLabel(RegExp('Open player')), findsNothing);
    expect(find.text('Home'), findsOneWidget);
    expect(find.text('Library'), findsOneWidget);
  });

  testWidgets('should_morphIdleBarIntoNavPose_when_firstTrackStarts', (tester) async {
    await pump(tester, NowPlayingState.idle);
    controller.emit(playingState(nowPlayingTracks(1), context: _album));
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    await tester.pumpAndSettle();
    expect(find.bySemanticsLabel(RegExp('Song 0, Open player')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_pushNewTrackIntoPill_when_trackChanges', (tester) async {
    final tracks = nowPlayingTracks(2);
    await pump(tester, playingState(tracks, context: _album));
    controller.emit(playingState(tracks, index: 1, context: _album));
    await tester.pump(const Duration(milliseconds: 50));
    expect(find.bySemanticsLabel(RegExp('Song 0, Open player')), findsOneWidget, reason: 'old row still pushing out');
    await tester.pumpAndSettle();
    expect(find.bySemanticsLabel(RegExp('Song 1, Open player')), findsOneWidget);
  });

  testWidgets('should_layOutWithoutOverflow_when_shortNarrowWindow', (tester) async {
    for (final size in const [Size(360, 560), Size(360, 640), Size(400, 900), Size(700, 1000)]) {
      controller = NowPlayingFakeController(playingState(nowPlayingTracks(2), context: _album));
      tester.view.physicalSize = size * 2;
      tester.view.devicePixelRatio = 2;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(_app(nowPlayingOverrides(controller: controller)), duration: Duration.zero);
      await tester.pump(const Duration(seconds: 1));
      await tester.tap(find.bySemanticsLabel(RegExp('Open player')));
      // A squeezed hero title marquees forever: pump a fixed time, don't settle.
      for (var i = 0; i < 90; i++) {
        await tester.pump(const Duration(milliseconds: 16));
      }
      expect(_player, findsOneWidget, reason: '$size');
      expect(tester.takeException(), isNull, reason: '$size');
      await tester.pumpWidget(const SizedBox());
    }
  });

  testWidgets('should_showPillWithTrack_when_trackExists', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    expect(find.bySemanticsLabel(RegExp('Song 0, Open player')), findsOneWidget);
    expect(_player, findsNothing);
  });

  testWidgets('should_openPlayerAndHideBar_when_pillTapped', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await openPlayer(tester);
    expect(_player, findsOneWidget);
    expect(find.text('Album'), findsOneWidget);
    expect(
      find.bySemanticsLabel(RegExp('Open player')),
      findsNothing,
      reason: 'the bar is not composed while the player is up',
    );
  });

  testWidgets('should_openPlayer_when_systemRequestsIt', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(1), context: _album));
    final container = ProviderScope.containerOf(tester.element(find.byType(AppChrome)));
    container.read(nowPlayingOpenRequestsProvider.notifier).request();
    await tester.pumpAndSettle();
    expect(_player, findsOneWidget);
  });

  testWidgets('should_openPlayerOnceQueueArrives_when_requestCameBeforeRehydrate', (tester) async {
    await pump(tester, NowPlayingState.idle);
    final container = ProviderScope.containerOf(tester.element(find.byType(AppChrome)));
    container.read(nowPlayingOpenRequestsProvider.notifier).request();
    await tester.pumpAndSettle();
    expect(_player, findsNothing);
    // The queue rebuilt from the native host lands.
    controller.emit(playingState(nowPlayingTracks(1), context: _album));
    await tester.pumpAndSettle();
    expect(_player, findsOneWidget);
  });

  testWidgets('should_closePlayerAndOpenAlbum_when_playingFromTapped', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(1), context: _album));
    await openPlayer(tester);
    await tester.tap(find.text('Album').first);
    await tester.pumpAndSettle();
    expect(_player, findsNothing);
    expect(find.byType(AlbumDetailScreen), findsOneWidget);
    // Back now belongs to the page again (the chrome's entry detached).
    expect(await appNavigatorKey.currentState!.maybePop(), isTrue);
    await tester.pumpAndSettle();
    expect(find.byType(AlbumDetailScreen), findsNothing);
  });

  testWidgets('should_hideBar_when_barlessPageIsUp', (tester) async {
    await pump(tester, NowPlayingState.idle);
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(
        builder: (_) => const ChromeBarSuppression(child: Scaffold(body: Text('setup page'))),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Library'), findsNothing);
    appNavigatorKey.currentState!.pop();
    await tester.pumpAndSettle();
    expect(find.text('Library'), findsOneWidget);
  });

  testWidgets('should_showBar_when_pageIsPushedOverBarlessPage', (tester) async {
    await pump(tester, NowPlayingState.idle);
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(
        builder: (_) => const ChromeBarSuppression(child: Scaffold(body: Text('search page'))),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Library'), findsNothing);

    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Text('detail page'))),
    );
    await tester.pumpAndSettle();
    expect(find.text('Library'), findsOneWidget, reason: 'the search page stays mounted under the detail page');

    appNavigatorKey.currentState!.pop();
    await tester.pumpAndSettle();
    expect(find.text('Library'), findsNothing, reason: 'back on the search page the bar leaves again');

    appNavigatorKey.currentState!.pop();
    await tester.pumpAndSettle();
    expect(find.text('Library'), findsOneWidget);
  });

  testWidgets('should_keepBarAway_when_menuOpensOverBarlessPage', (tester) async {
    await pump(tester, NowPlayingState.idle);
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(
        builder: (_) => const ChromeBarSuppression(child: Scaffold(body: Text('search page'))),
      ),
    );
    await tester.pumpAndSettle();
    showDialog<void>(context: appNavigatorKey.currentContext!, builder: (_) => const Text('menu'));
    await tester.pumpAndSettle();
    expect(find.text('Library'), findsNothing);
  });

  test('should_notSuppress_when_barlessPageIsCoveredOrLeaving', () {
    expect(barSuppressedFor(animation: AnimationStatus.completed, secondaryAnimation: AnimationStatus.dismissed), isTrue);
    expect(barSuppressedFor(animation: AnimationStatus.forward, secondaryAnimation: AnimationStatus.dismissed), isTrue);
    expect(barSuppressedFor(animation: AnimationStatus.completed, secondaryAnimation: AnimationStatus.reverse), isTrue);
    expect(barSuppressedFor(animation: AnimationStatus.completed, secondaryAnimation: AnimationStatus.forward), isFalse);
    expect(barSuppressedFor(animation: AnimationStatus.completed, secondaryAnimation: AnimationStatus.completed), isFalse);
    expect(barSuppressedFor(animation: AnimationStatus.reverse, secondaryAnimation: AnimationStatus.dismissed), isFalse);
  });

  testWidgets('should_closePlayerNotPopRoute_when_systemBack', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(builder: (_) => const Scaffold(body: Text('detail page'))),
    );
    await tester.pumpAndSettle();
    await openPlayer(tester);
    expect(_player, findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(_player, findsNothing);
    expect(find.text('detail page'), findsOneWidget, reason: 'back closed the overlay, not the page under it');

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('detail page'), findsNothing, reason: 'with the overlay closed back pops again');
  });

  testWidgets('should_closePlayer_when_draggedDownPastThreshold', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await openPlayer(tester);
    await tester.drag(_player, const Offset(0, 300));
    await tester.pumpAndSettle();
    expect(_player, findsNothing);
  });

  testWidgets('should_stayOpen_when_draggedDownBriefly', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await openPlayer(tester);
    await tester.timedDrag(_player, const Offset(0, 40), const Duration(milliseconds: 500));
    await tester.pumpAndSettle();
    expect(_player, findsOneWidget);
  });

  testWidgets('should_closePlayer_when_chevronTapped', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await openPlayer(tester);
    await tester.tap(find.bySemanticsLabel('Close Now Playing'));
    await tester.pumpAndSettle();
    expect(_player, findsNothing);
  });

  testWidgets('should_stepBackThroughStages_when_lyricsExpanded', (tester) async {
    await pump(
      tester,
      playingState(nowPlayingTracks(1), context: _album),
      source: NowPlayingFakeSource(
        lyrics: {
          't0': const SyncedLyrics([
            LyricLine(0, 'first line'),
            LyricLine(5000, 'second line'),
            LyricLine(9000, 'third line'),
          ]),
        },
      ),
    );
    await openPlayer(tester);
    await tester.tap(find.text('first line').first);
    await tester.pumpAndSettle();
    expect(find.bySemanticsLabel('Back to Now Playing'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.bySemanticsLabel('Close Now Playing'), findsOneWidget);
    expect(_player, findsOneWidget, reason: 'back from Expanded goes to Compact, not out');

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(_player, findsNothing);
  });

  testWidgets('should_pauseAndSkip_when_transportTapped', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(2), context: _album));
    await openPlayer(tester);
    await tester.tap(find.text('PAUSE'));
    await tester.pump();
    await tester.tap(find.bySemanticsLabel('Skip next'));
    await tester.tap(find.bySemanticsLabel('Skip previous'));
    await tester.tap(find.bySemanticsLabel('Play mode'));
    await tester.pumpAndSettle();
    expect(controller.calls, ['pause', 'next', 'previous', 'setMode:shuffle']);
    expect(find.text('PLAY'), findsOneWidget);
  });

  testWidgets('should_toggleFavorite_when_heartTapped', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(1), context: _album));
    await openPlayer(tester);
    await tester.tap(find.bySemanticsLabel('Add to favorites'));
    await tester.pumpAndSettle();
    expect(controller.calls, contains('toggleFavorite'));
    expect(find.bySemanticsLabel('Remove from favorites'), findsOneWidget);
  });

  testWidgets('should_skipToRow_when_queueRowTapped', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(3), context: _album));
    await openPlayer(tester);
    await tester.tap(find.bySemanticsLabel('Queue'));
    await tester.pumpAndSettle();
    expect(find.text('Next from: Album'), findsOneWidget);
    await tester.tap(find.text('Song 2'));
    await tester.pumpAndSettle();
    expect(controller.calls, contains('skipTo:2'));
    expect(find.text('Next from: Album'), findsNothing);
  });

  testWidgets('should_closeQueueSheetFirst_when_systemBack', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(3), context: _album));
    await openPlayer(tester);
    await tester.tap(find.bySemanticsLabel('Queue'));
    await tester.pumpAndSettle();
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('Next from: Album'), findsNothing);
    expect(_player, findsOneWidget);
  });

  testWidgets('should_showPlaySplit_when_detailPageRegistersActions', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(1), context: _album));
    var played = 0;
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(
        builder: (_) => DetailChromeRegistration(
          actions: DetailPlayActions(onPlay: () => played++, onShuffle: () {}),
          child: const Scaffold(body: SizedBox.expand()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Play'));
    expect(played, 1);
    expect(find.bySemanticsLabel(RegExp('Open player')), findsOneWidget, reason: 'the pill stays beside the split');

    appNavigatorKey.currentState!.pop();
    await tester.pumpAndSettle();
    expect(find.text('Play'), findsNothing);
  });

  testWidgets('should_closeMenuNotPopPage_when_backWhileSplitMenuOpen', (tester) async {
    await pump(tester, playingState(nowPlayingTracks(1), context: _album));
    var shuffled = 0;
    appNavigatorKey.currentState!.push(
      MaterialPageRoute<void>(
        builder: (_) => DetailChromeRegistration(
          actions: DetailPlayActions(onPlay: () {}, onShuffle: () => shuffled++),
          child: const Scaffold(body: SizedBox.expand()),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.bySemanticsLabel(RegExp('More play options')));
    await tester.pumpAndSettle();
    expect(find.text('Shuffle play'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('Shuffle play'), findsNothing, reason: 'back closes the menu');
    expect(find.text('Play'), findsOneWidget, reason: 'the detail page is still there');

    await tester.tap(find.bySemanticsLabel(RegExp('More play options')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Shuffle play'));
    await tester.pumpAndSettle();
    expect(shuffled, 1);
  });

  testWidgets('should_resendPendingRating_when_appStartsWithoutOpeningPlayer', (tester) async {
    _phone(tester);
    final values = InMemoryStore();
    final repo = ProfileRepository(values, InMemoryStore());
    final profile = await repo.add(
      provider: MediaId.providerSubsonic,
      displayName: 'Home',
      credentials: const SubsonicCredentials(baseUrl: 'https://m.test', username: 'u', password: 'p'),
    );
    final song = MediaId.subsonic('s1');
    await LocalRatingStore(values).write(profile.id, {
      song: LocalRating(
        profileId: profile.id,
        songId: song.rawId,
        provider: song.provider,
        rating: 9,
        serverRating: 5,
        needsSync: true,
        updatedAt: 1,
      ),
    });
    final source = PlaybackFakeSource();
    controller = NowPlayingFakeController(NowPlayingState.idle);
    await tester.pumpWidget(
      _app([
        ...nowPlayingOverrides(controller: controller, source: source),
        keyValueStoreProvider.overrideWithValue(values),
        profileRepositoryProvider.overrideWithValue(repo),
      ]),
    );
    await tester.pumpAndSettle();

    expect(_player, findsNothing, reason: 'the player was never opened');
    expect(source.ratingWrites, [(song, 5)]);
  });
}
