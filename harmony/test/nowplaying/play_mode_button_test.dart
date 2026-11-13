import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/play_mode_cycle.dart';
import 'package:yoin/nowplaying/transport.dart';
import 'package:yoin/playback/play_mode.dart';
import 'package:yoin/theme/tokens.dart';

/// Hosts the button the way Now Playing does: the mode comes back from the
/// player after each choice.
class _Host extends StatefulWidget {
  const _Host({required this.initial, required this.log, this.showSplitOnFullCycle = false, this.nowMs});

  final PlayMode initial;
  final List<PlayMode> log;
  final bool showSplitOnFullCycle;
  final int Function()? nowMs;

  @override
  State<_Host> createState() => _HostState();
}

class _HostState extends State<_Host> {
  late PlayMode mode = widget.initial;

  @override
  Widget build(BuildContext context) => MaterialApp(
    home: Scaffold(
      body: Center(
        child: PlayModeButton(
          mode: mode,
          showSplitOnFullCycle: widget.showSplitOnFullCycle,
          nowMs: widget.nowMs,
          onSetPlayMode: (m) {
            widget.log.add(m);
            setState(() => mode = m);
          },
        ),
      ),
    ),
  );
}

PlayModeButtonState _state(WidgetTester tester) => tester.state(find.byType(PlayModeButton));

void main() {
  group('PlayModeCycle', () {
    test('should_closeCycle_when_fourQuickTapsReturnToStart', () {
      final cycle = PlayModeCycle();
      var mode = PlayMode.repeatAll;
      final closed = <bool>[];
      for (var i = 0; i < 4; i++) {
        final next = mode.next();
        closed.add(cycle.onTap(mode, next, i * 1000));
        mode = next;
      }
      expect(closed, [false, false, false, true]);
    });

    test('should_notCloseCycle_when_aPauseExceedsTheGap', () {
      final cycle = PlayModeCycle();
      var mode = PlayMode.repeatAll;
      final times = [0, 1000, 1000 + playModeCycleGapMs + 1, 4000];
      final closed = <bool>[];
      for (final t in times) {
        final next = mode.next();
        closed.add(cycle.onTap(mode, next, t));
        mode = next;
      }
      expect(closed, everyElement(isFalse));
    });
  });

  testWidgets('should_stepRoundCycle_when_circleTapped', (tester) async {
    final log = <PlayMode>[];
    await tester.pumpWidget(_Host(initial: PlayMode.repeatAll, log: log));
    await tester.tap(find.byType(PlayModeButton));
    await tester.pumpAndSettle();
    expect(log, [PlayMode.shuffle]);
    expect(_state(tester).isSplit, isFalse);
    expect(tester.getSize(find.byType(PlayModeButton)).width, NowPlayingTokens.controlSize);
  });

  testWidgets('should_splitIntoShuffleAndRepeat_when_longPressed', (tester) async {
    final log = <PlayMode>[];
    await tester.pumpWidget(_Host(initial: PlayMode.repeatAll, log: log));
    await tester.longPress(find.byType(PlayModeButton));
    await tester.pumpAndSettle();
    expect(_state(tester).isSplit, isTrue);
    expect(log, isEmpty, reason: 'a long-press changes no mode');
    expect(
      tester.getSize(find.byType(PlayModeButton)).width,
      moreOrLessEquals(playModeNaturalWidth(NowPlayingTokens.controlSize), epsilon: 0.01),
    );

    final box = tester.getRect(find.byType(PlayModeButton));
    // The left segment toggles shuffle only.
    await tester.tapAt(box.centerLeft + const Offset(16, 0));
    await tester.pump();
    // The right segment steps repeat all → one → off, shuffle kept.
    await tester.tapAt(box.centerRight - const Offset(16, 0));
    await tester.pump();
    await tester.tapAt(box.centerRight - const Offset(16, 0));
    await tester.pump();
    expect(log, [PlayMode.shuffle, PlayMode.shuffleRepeatOne, PlayMode.shuffleRepeatOff]);
    await tester.pumpAndSettle(const Duration(milliseconds: 100));
  });

  testWidgets('should_mergeBack_when_leftAloneForTheHold', (tester) async {
    await tester.pumpWidget(_Host(initial: PlayMode.repeatAll, log: []));
    await tester.longPress(find.byType(PlayModeButton));
    await tester.pump();
    expect(_state(tester).isSplit, isTrue);

    // longPress returns a little after the split began (its own pointer-up pump).
    await tester.pump(const Duration(milliseconds: playModeSplitHoldMs - 200));
    expect(_state(tester).isSplit, isTrue);
    await tester.pump(const Duration(milliseconds: 300));
    await tester.pumpAndSettle();
    expect(_state(tester).isSplit, isFalse);
    expect(tester.getSize(find.byType(PlayModeButton)).width, moreOrLessEquals(NowPlayingTokens.controlSize, epsilon: 0.01));
  });

  testWidgets('should_merge_when_segmentLongPressed', (tester) async {
    await tester.pumpWidget(_Host(initial: PlayMode.repeatAll, log: []));
    await tester.longPress(find.byType(PlayModeButton));
    await tester.pumpAndSettle();
    final box = tester.getRect(find.byType(PlayModeButton));
    await tester.longPressAt(box.centerRight - const Offset(16, 0));
    await tester.pumpAndSettle();
    expect(_state(tester).isSplit, isFalse);
  });

  testWidgets('should_splitOnce_when_tapsRunRoundTheCycleWithHintPending', (tester) async {
    var now = 0;
    final log = <PlayMode>[];
    await tester.pumpWidget(_Host(initial: PlayMode.repeatAll, log: log, showSplitOnFullCycle: true, nowMs: () => now));
    for (var i = 0; i < 4; i++) {
      now += 500;
      await tester.tap(find.byType(PlayModeButton));
      await tester.pump(const Duration(milliseconds: 50));
    }
    await tester.pumpAndSettle(const Duration(milliseconds: 100));
    expect(log.last, PlayMode.repeatAll);
    expect(_state(tester).isSplit, isTrue);
    await tester.pump(const Duration(milliseconds: playModeSplitHoldMs + 100));
    await tester.pumpAndSettle();
  });

  testWidgets('should_stayRound_when_tapsRunRoundTheCycleWithoutHint', (tester) async {
    var now = 0;
    await tester.pumpWidget(_Host(initial: PlayMode.repeatAll, log: [], nowMs: () => now));
    for (var i = 0; i < 4; i++) {
      now += 500;
      await tester.tap(find.byType(PlayModeButton));
      await tester.pump(const Duration(milliseconds: 50));
    }
    await tester.pumpAndSettle();
    expect(_state(tester).isSplit, isFalse);
  });
}
