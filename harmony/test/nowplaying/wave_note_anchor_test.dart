import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/wave_progress_bar.dart';

void main() {
  test('should_floorWindowAtOnePercent_when_longSong', () {
    expect(noteWindowFraction(100000), closeTo(0.015, 1e-9));
    expect(noteWindowFraction(1000000), 0.01);
    expect(noteWindowFraction(0), 0);
  });

  test('should_stickToAnchor_when_insideInnerHalf', () {
    final anchors = noteAnchorFractions([50000], 100000);
    expect(anchors, [0.5]);
    expect(magnetizeToNotes(0.505, anchors, 0.015), 0.5);
  });

  test('should_returnToFinger_when_outsideWindow', () {
    expect(magnetizeToNotes(0.6, const [0.5], 0.015), 0.6);
  });

  test('should_easeContinuously_when_inOuterHalf', () {
    final mid = magnetizeToNotes(0.5 + 0.015 * 0.75, const [0.5], 0.015);
    expect(mid, greaterThan(0.5));
    expect(mid, lessThan(0.5 + 0.015 * 0.75));
    expect(magnetizeToNotes(0.5 + 0.015 - 1e-9, const [0.5], 0.015), closeTo(0.515, 1e-6));
  });
}
