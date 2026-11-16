import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/playback/playback_engine.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/playback_controller.dart';
import 'package:yoin/playback/playback_strings.dart';
import 'package:yoin/shell/app_chrome.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/fake_music_source.dart';
import '../support/fake_playback_engine.dart';
import '../support/playback_fakes.dart';

void main() {
  test('should_mapNoticesToAndroidCopy_when_noticeRaised', () {
    const en = PlaybackStrings.en;
    final id = MediaId.subsonic('s');
    expect(en.noticeMessage(FavoriteWriteFailed(id, favorite: true)), "Couldn't save to favorites");
    expect(en.noticeMessage(FavoriteWriteFailed(id, favorite: false)), "Couldn't remove from favorites");
    expect(en.noticeMessage(const PlaybackFailed(PlaybackErrorKind.interrupted)), 'Playback was interrupted.');
    expect(PlaybackStrings.zhHans.noticeMessage(const PlaybackFailed(PlaybackErrorKind.playFailed)), '无法播放此歌曲');
    expect(en.noticeMessage(RatingWriteFailed(id)), isNull, reason: 'Android has no copy for it');
  });

  group('the shell snackbar', () {
    late FakePlaybackEngine engine;
    late ProviderContainer c;
    late ValueNotifier<double> barAway;
    late ValueNotifier<double> playerUp;

    Future<void> pumpHost(WidgetTester tester, {YoinWindowInfo? window}) async {
      engine = FakePlaybackEngine();
      barAway = ValueNotifier(0);
      playerUp = ValueNotifier(0);
      c = ProviderContainer(
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
            home: YoinWindowScope(
              info: window ?? const YoinWindowInfo(360, 800),
              child: Stack(
                fit: StackFit.expand,
                children: [
                  const SizedBox.expand(),
                  PlaybackSnackbarHost(
                    barAway: Listenable.merge([barAway, playerUp]),
                    barAwayValue: () => barAway.value,
                    playerUpValue: () => playerUp.value,
                  ),
                ],
              ),
            ),
          ),
        ),
      );
    }

    Future<void> failPlayback(WidgetTester tester) async {
      await tester.runAsync(() => c.read(playbackControllerProvider.notifier).playTracks(playbackTracks(1)));
      engine.emit(const EngineState(phase: PlaybackPhase.error, isPlaying: false, index: 0, errorMessage: 'raw'));
      await tester.pump();
      await tester.pump();
    }

    testWidgets('should_showAboveBarThenLeave_when_playbackFails', (tester) async {
      await pumpHost(tester);
      await failPlayback(tester);
      await tester.pump(const Duration(milliseconds: 600));

      final message = find.text(PlaybackStrings.en.playbackInterrupted);
      expect(message, findsOneWidget);
      expect(find.text('raw'), findsNothing, reason: 'never the engine text');
      final screen = tester.getSize(find.byType(Stack).first);
      const band = BarTokens.height + 2 * BarTokens.portraitVerticalMargin;
      final card = tester.getRect(find.byType(Material).last);
      expect(card.bottom, lessThanOrEqualTo(screen.height - band));

      await tester.pump(snackbarShortDuration);
      await tester.pumpAndSettle();
      expect(message, findsNothing);
    });

    testWidgets('should_dropToTheBottom_when_barLeaves', (tester) async {
      await pumpHost(tester);
      await failPlayback(tester);
      await tester.pump(const Duration(milliseconds: 600));
      final up = tester.getRect(find.byType(Material).last).bottom;
      barAway.value = 1;
      await tester.pump();
      final down = tester.getRect(find.byType(Material).last).bottom;
      expect(down - up, moreOrLessEquals(BarTokens.height + 2 * BarTokens.portraitVerticalMargin, epsilon: 0.5));
      await tester.pump(snackbarShortDuration);
      await tester.pumpAndSettle();
    });

    testWidgets('should_keepAboveTheTools_when_playerIsUpAndBarAway', (tester) async {
      // Android keeps the bar's reserve under Now Playing too: the player's
      // tools row sits inside it, under the snackbar ("已复制 N 行" over Expanded).
      await pumpHost(tester, window: const YoinWindowInfo(1280, 800));
      final reserve = snackbarBottomReserve(const YoinWindowInfo(1280, 800));
      expect(
        reserve,
        BarTokens.centeredHeight + BarTokens.centeredBottomMarginWide + BarTokens.floatingContentGap,
        reason: 'the centred bar reserve on a wide window, not the portrait band',
      );
      playerUp.value = 1;
      barAway.value = 1;
      c.read(playbackControllerProvider.notifier).showMessage('Copied 2 lines');
      await tester.pump();
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      final screen = tester.getSize(find.byType(Stack).first);
      final card = tester.getRect(find.byType(Material).last);
      expect(card.bottom, lessThanOrEqualTo(screen.height - reserve));
      await tester.pump(snackbarShortDuration);
      await tester.pumpAndSettle();
    });

    testWidgets('should_runActionAndLeave_when_actionPressed', (tester) async {
      await pumpHost(tester);
      var switched = 0;
      c
          .read(playbackControllerProvider.notifier)
          .showMessage(
            'NetEase translation is available',
            actionLabel: 'Switch',
            long: true,
            onAction: () => switched++,
          );
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 600));
      // A Long snackbar outlasts a Short one.
      await tester.pump(snackbarShortDuration);
      expect(find.text('Switch'), findsOneWidget);
      await tester.tap(find.text('Switch'));
      await tester.pumpAndSettle();
      expect(switched, 1);
      expect(find.text('NetEase translation is available'), findsNothing);
    });

    testWidgets('should_leaveAfterTenSeconds_when_longWithoutAction', (tester) async {
      await pumpHost(tester);
      c.read(playbackControllerProvider.notifier).showMessage('kept', long: true);
      await tester.pump();
      await tester.pump(snackbarLongDuration - const Duration(milliseconds: 100));
      expect(find.text('kept'), findsOneWidget);
      await tester.pump(const Duration(milliseconds: 200));
      await tester.pumpAndSettle();
      expect(find.text('kept'), findsNothing);
    });
  });

  test('should_nameTheContentRestriction_when_appleRefusedWith3082', () {
    expect(
      errorKindOf('5400103: apple_music_content_restricted (3082)'),
      PlaybackErrorKind.appleMusicContentRestricted,
    );
    expect(errorKindOf('5400103: segment 3 fetch failed'), PlaybackErrorKind.interrupted);
    expect(
      PlaybackStrings.zhHans.noticeMessage(const PlaybackFailed(PlaybackErrorKind.appleMusicContentRestricted)),
      contains('家长控制'),
    );
  });
}
