import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/landing/landing_models.dart';
import 'package:yoin/landing/landing_motion.dart';

void main() {
  testWidgets('should_neverFlashTheLineItLeft_when_aMoveIsInterrupted', (tester) async {
    final motion = LandingMotion(const TestVSync());
    addTearDown(motion.dispose);
    final shown = <LandingStep>[];
    motion.onBubbleChanged = () => shown.add(motion.bubbleStep);
    motion.snap(LandingStep.pick);
    shown.clear();

    motion.go(LandingStep.about, LandingSprings.spatial);
    // Past the midpoint the bubble already says About's line.
    while (motion.t.value < 0.6) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(motion.bubbleStep, LandingStep.about);
    expect(shown, [LandingStep.about]);

    motion.go(LandingStep.subsonic, LandingSprings.spatial);
    // The interrupted move starts from About; Pick's line never comes back.
    expect(motion.from, LandingStep.about);
    expect(motion.bubbleStep, LandingStep.about);
    await tester.pumpAndSettle();
    expect(shown, [LandingStep.about, LandingStep.subsonic]);
    expect(motion.settled, isTrue);
  });
}
