import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/playhead_clock.dart';
import 'package:yoin/nowplaying/stage_budget.dart';
import 'package:yoin/nowplaying/transport.dart';
import 'package:yoin/playback/play_mode.dart';
import 'package:yoin/playback/playhead.dart';
import 'package:yoin/theme/tokens.dart';

Future<void> _pump(WidgetTester tester, double width) async {
  final clock = PlayheadClock(now: () => 0);
  clock.update(const Playhead(entryId: 'e', positionMs: 57000, sampledAtMs: 0, durationMs: 213000));
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Align(
          alignment: Alignment.topLeft,
          child: SizedBox(
            width: width,
            child: TransportControls(
              isPlaying: false,
              isWavePlaying: false,
              playMode: PlayMode.repeatAll,
              clock: clock,
              durationMs: 213000,
              labelsUnder: width - 2 * NowPlayingTokens.columnPadding < transportLabelsInlineMinWidth,
              waveVisible: false,
              onPlayPause: () {},
              onNext: () {},
              onPrevious: () {},
              onSetPlayMode: (_) {},
              onSeek: (_) {},
              pressed: ValueNotifier(TransportKey.none),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pump();
}

void main() {
  testWidgets('should_putTimesBesideTheWave_when_mate70AirWidth', (tester) async {
    await _pump(tester, 406);
    final elapsed = tester.getRect(find.text('0:57'));
    final remaining = tester.getRect(find.text('-2:36'));
    final previous = tester.getRect(find.bySemanticsLabel('Skip previous'));

    // One row: PREVIOUS, elapsed, wave, -remaining (Android PlaybackControls).
    expect(elapsed.center.dy, closeTo(previous.center.dy + 6, 1), reason: 'labels ride 6 below the row centre');
    expect(remaining.center.dy, closeTo(elapsed.center.dy, 0.5));
    expect(elapsed.left, closeTo(previous.right + NowPlayingTokens.transportGap, 0.5));
    expect(remaining.right, closeTo(406 - NowPlayingTokens.columnPadding, 0.5));
  });

  testWidgets('should_dropTimesUnderTheWave_when_paneIsNarrow', (tester) async {
    await _pump(tester, 360);
    final elapsed = tester.getRect(find.text('0:57'));
    final previous = tester.getRect(find.bySemanticsLabel('Skip previous'));
    expect(elapsed.top, greaterThan(previous.bottom - 1));
  });
}
