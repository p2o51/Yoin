import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/focus_idle.dart';
import 'package:yoin/theme/tokens.dart';

void main() {
  testWidgets('should_goIdle_when_stillForIdleMsWhileEligible', (tester) async {
    final idle = FocusIdle();
    addTearDown(idle.dispose);
    idle.track(true);
    await tester.pump(const Duration(milliseconds: NowPlayingFocusTokens.idleMs - 100));
    expect(idle.idle, isFalse);
    await tester.pump(const Duration(milliseconds: 200));
    expect(idle.idle, isTrue);
  });

  testWidgets('should_wakeAndRearm_when_touched', (tester) async {
    final idle = FocusIdle();
    addTearDown(idle.dispose);
    idle.track(true);
    await tester.pump(const Duration(milliseconds: NowPlayingFocusTokens.idleMs + 10));
    expect(idle.idle, isTrue);
    idle.onInteraction();
    expect(idle.idle, isFalse);
    await tester.pump(const Duration(milliseconds: NowPlayingFocusTokens.idleMs - 100));
    expect(idle.idle, isFalse);
    await tester.pump(const Duration(milliseconds: 200));
    expect(idle.idle, isTrue);
  });

  testWidgets('should_stayAwake_when_heldAndRearm_when_released', (tester) async {
    final idle = FocusIdle();
    addTearDown(idle.dispose);
    idle.track(true);
    idle.hold(true);
    await tester.pump(const Duration(milliseconds: NowPlayingFocusTokens.idleMs * 2));
    expect(idle.idle, isFalse, reason: 'a focused key holds them');
    idle.onInteraction();
    await tester.pump(const Duration(milliseconds: NowPlayingFocusTokens.idleMs * 2));
    expect(idle.idle, isFalse);
    idle.hold(false);
    await tester.pump(const Duration(milliseconds: NowPlayingFocusTokens.idleMs - 100));
    expect(idle.idle, isFalse, reason: 'a fresh count from the release');
    await tester.pump(const Duration(milliseconds: 200));
    expect(idle.idle, isTrue);
  });

  testWidgets('should_neverIdle_when_notEligible', (tester) async {
    final idle = FocusIdle();
    addTearDown(idle.dispose);
    idle.track(true);
    idle.track(false);
    await tester.pump(const Duration(milliseconds: NowPlayingFocusTokens.idleMs * 2));
    expect(idle.idle, isFalse);
  });

  testWidgets('should_swallowFirstTap_when_atEase', (tester) async {
    final ease = AnimationController(vsync: tester, value: 1);
    addTearDown(ease.dispose);
    var taps = 0;
    await tester.pumpWidget(
      Directionality(
        textDirection: TextDirection.ltr,
        child: Center(
          child: AtEaseWakeGuard(
            atEase: ease,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => taps++,
              child: const SizedBox.square(dimension: 48),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.byType(SizedBox), warnIfMissed: false);
    expect(taps, 0, reason: 'the first tap only wakes the hidden buttons');
    ease.value = 0;
    await tester.pump();
    await tester.tap(find.byType(SizedBox));
    expect(taps, 1);
  });
}
