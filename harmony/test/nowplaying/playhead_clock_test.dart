import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/playhead_clock.dart';
import 'package:yoin/playback/playhead.dart';

void main() {
  group('reanchor', () {
    const playing = PlayheadAnchor(trackKey: 'e1', positionMs: 10000, atMs: 0, isPlaying: true);

    test('should_keepDrawnFront_when_sampleSlightlyBehind', () {
      // At t = 1000 the front is 11000; a sample says 10700 (300 ms behind).
      final next = reanchor(
        playing,
        const Playhead(entryId: 'e1', positionMs: 10700, sampledAtMs: 1000, isPlaying: true),
        1000,
      );
      expect(next.positionAt(1000), 11000);
    });

    test('should_snap_when_sampleFarBehind', () {
      final next = reanchor(
        playing,
        const Playhead(entryId: 'e1', positionMs: 2000, sampledAtMs: 1000, isPlaying: true),
        1000,
      );
      expect(next.positionAt(1000), 2000);
    });

    test('should_snap_when_sampleAhead', () {
      final next = reanchor(
        playing,
        const Playhead(entryId: 'e1', positionMs: 11300, sampledAtMs: 1000, isPlaying: true),
        1000,
      );
      expect(next.positionAt(1000), 11300);
    });

    test('should_snap_when_trackChanges', () {
      final next = reanchor(
        playing,
        const Playhead(entryId: 'e2', positionMs: 0, sampledAtMs: 1000, isPlaying: true),
        1000,
      );
      expect(next.positionAt(1000), 0);
    });

    test('should_freeze_when_paused', () {
      final next = reanchor(playing, const Playhead(entryId: 'e1', positionMs: 10800, sampledAtMs: 1000), 1000);
      expect(next.positionAt(5000), 10800);
    });
  });

  test('should_extrapolateAndClampToDuration_when_playing', () {
    var now = 0;
    final clock = PlayheadClock(now: () => now);
    clock.update(const Playhead(entryId: 'e', positionMs: 1000, sampledAtMs: 0, isPlaying: true, durationMs: 3000));
    now = 1500;
    expect(clock.positionMs(), 2500);
    expect(clock.progress(), closeTo(2500 / 3000, 1e-9));
    now = 9000;
    expect(clock.positionMs(), 3000);
    clock.dispose();
  });

  test('should_holdTheFront_when_noSampleArrivesPastTheLead', () {
    var now = 0;
    final clock = PlayheadClock(now: () => now);
    clock.update(const Playhead(entryId: 'e', positionMs: 1000, sampledAtMs: 0, isPlaying: true, durationMs: 60000));
    now = 1500;
    expect(clock.positionMs(), 2500);
    now = 10000;
    expect(clock.positionMs(), 1000 + playheadMaxLeadMs);
    clock.dispose();
  });

  test('should_shareOneWavePhase_when_sameInstant', () {
    final clock = PlayheadClock(now: () => 1500);
    expect(clock.wavePhase(), closeTo(3.14159, 1e-4));
    clock.dispose();
  });

  test('should_formatMinutesAndSeconds_when_overAnHour', () {
    expect(formatPlaybackTime(3725000), '62:05');
    expect(formatPlaybackTime(59999), '0:59');
    expect(formatPlaybackTime(-5), '0:00');
  });
}
