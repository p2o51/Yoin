import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/nowplaying/now_playing_pill.dart';
import 'package:yoin/playback/playback_context.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_bottom_bar.dart';
import 'package:yoin/shell/yoin_edge_split_group.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/fake_music_source.dart';
import '../support/fake_playback_engine.dart';
import '../support/nowplaying_fakes.dart';

Widget _app(List<Override> overrides) => ProviderScope(
  overrides: overrides,
  child: MaterialApp(
    navigatorKey: appNavigatorKey,
    builder: (context, child) => YoinWindowScope.measured(child: AppChrome(child: child!)),
    home: const Scaffold(body: SizedBox.expand()),
  ),
);

void _window(WidgetTester tester, Size dp) {
  tester.view.physicalSize = dp * 2;
  tester.view.devicePixelRatio = 2;
  addTearDown(tester.view.reset);
}

final _album = AlbumPlaybackContext(albumId: MediaId.subsonic('al'), name: 'Album');

void main() {
  late NowPlayingFakeController controller;

  Future<void> pump(WidgetTester tester, Size window) async {
    _window(tester, window);
    controller = NowPlayingFakeController(playingState(nowPlayingTracks(2), context: _album));
    await tester.pumpWidget(_app(nowPlayingOverrides(controller: controller)));
    await tester.pump(const Duration(seconds: 1));
  }

  testWidgets('should_standGroupOnTheEdge_when_windowIsShort', (tester) async {
    await pump(tester, const Size(844, 390));
    expect(find.byType(YoinEdgeSplitGroup), findsOneWidget);
    expect(find.byType(YoinBottomBar), findsNothing);
    expect(find.byType(NowPlayingPillVertical), findsOneWidget);
    expect(find.bySemanticsLabel(RegExp('Song 0, Open player')), findsOneWidget);
  });

  testWidgets('should_openPlayerFromUprightPillAndComeBack_when_pillTapped', (tester) async {
    await pump(tester, const Size(844, 390));
    await tester.tap(find.byType(NowPlayingPillVertical));
    for (var i = 0; i < 90; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(find.text('Album'), findsWidgets, reason: 'the landscape player is up');
    expect(find.byType(NowPlayingPillVertical), findsNothing, reason: 'the capsules left over their edge');
    expect(tester.takeException(), isNull);
    await appNavigatorKey.currentState!.maybePop();
    for (var i = 0; i < 90; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(find.byType(NowPlayingPillVertical), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_crossfadeForms_when_windowRotates', (tester) async {
    await pump(tester, const Size(390, 844));
    expect(find.byType(YoinBottomBar), findsOneWidget);
    tester.view.physicalSize = const Size(844, 390) * 2;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 32));
    // Mid-fade both forms are on screen, each in its own pose.
    expect(find.byType(YoinBottomBar), findsOneWidget);
    expect(find.byType(YoinEdgeSplitGroup), findsOneWidget);
    for (var i = 0; i < 60; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(find.byType(YoinBottomBar), findsNothing);
    expect(find.byType(YoinEdgeSplitGroup), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_keepSnackbarOutOfCapsuleBand_when_edgeSplit', (tester) async {
    _window(tester, const Size(600, 390));
    final engine = FakePlaybackEngine();
    final c = ProviderContainer(
      overrides: [
        playbackEngineProvider.overrideWithValue(engine),
        activeSourceProvider.overrideWith((ref) async => FakeMusicSource()),
      ],
    );
    addTearDown(c.dispose);
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          home: YoinWindowScope.measured(
            child: Stack(
              fit: StackFit.expand,
              children: [
                const SizedBox.expand(),
                PlaybackSnackbarHost(barAway: ValueNotifier(0.0), barAwayValue: () => 0),
              ],
            ),
          ),
        ),
      ),
    );
    c.read(playbackControllerProvider.notifier).showMessage('Added 12 songs to the queue');
    await tester.pump();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
    final card = tester.getRect(find.byType(Material).last);
    expect(card.left, greaterThanOrEqualTo(EdgeSplitTokens.contentStart + SnackbarTokens.hostSideInset));
    await tester.pump(snackbarShortDuration);
    await tester.pumpAndSettle();
  });
}
