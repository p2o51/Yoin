import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/playhead_clock.dart';
import 'package:yoin/nowplaying/wave_progress_bar.dart';
import 'package:yoin/playback/playhead.dart';

void main() {
  const size = Size(300, WaveBarMetrics.indicatorHeight);
  const stroke = WaveBarMetrics.stroke;
  const cap = stroke / 2;

  group('capWidth', () {
    test('should_beHalfTheStroke_when_barIsWiderThanTall', () {
      expect(WaveGeometry.capWidth(size), closeTo(1.84, 1e-9));
    });

    test('should_beZero_when_barIsTallerThanWide', () {
      expect(WaveGeometry.capWidth(const Size(10, 18)), 0);
    });
  });

  group('indicator', () {
    test('should_drawNothing_when_progressIsZero', () {
      expect(WaveGeometry.indicator(300, 0, cap), isNull);
    });

    test('should_drawNothing_when_headIsInsideTheStartCap', () {
      // 0.5 % of 300 = 1.5 < 1.84: a zero-length segment, nothing drawn.
      expect(WaveGeometry.indicator(300, 0.005, cap), isNull);
    });

    test('should_runFromCapToHead_when_midSong', () {
      expect(WaveGeometry.indicator(300, 0.5, cap), const WaveSpan(cap, 150));
    });

    test('should_stopACapShortOfTheEnd_when_complete', () {
      expect(WaveGeometry.indicator(300, 1, cap), const WaveSpan(cap, 300 - cap));
    });
  });

  group('track', () {
    test('should_spanCapToEndLessCap_when_progressIsZero', () {
      expect(WaveGeometry.track(300, 0, cap), const WaveSpan(cap, 300 - cap));
    });

    test('should_startAfterHeadGapAndTwoCaps_when_midSong', () {
      final track = WaveGeometry.track(300, 0.5, cap)!;
      expect(track.start, closeTo(150 + WaveBarMetrics.gap + stroke, 1e-9));
      expect(track.end, closeTo(300 - cap, 1e-9));
    });

    test('should_shrinkTheGap_when_headJustLeftTheStartCap', () {
      // Head at 2.1: gap = min(2.1 − 1.84, 1) = 0.26.
      final track = WaveGeometry.track(300, 2.1 / 300, cap)!;
      expect(track.start, closeTo(2.1 + 0.26 + stroke, 1e-9));
    });

    test('should_vanish_when_complete', () {
      expect(WaveGeometry.track(300, 1, cap), isNull);
    });
  });

  group('fullWave', () {
    const h = WaveBarMetrics.indicatorHeight;
    const crest = (h - stroke) / 2;

    test('should_crestDownAtHalfTheControlHeight_when_quarterWavelength', () {
      expect(WaveGeometry.fullWave(6, height: h), closeTo(crest, 1e-9));
    });

    test('should_crestUp_when_threeQuarterWavelength', () {
      expect(WaveGeometry.fullWave(18, height: h), closeTo(-crest, 1e-9));
    });

    test('should_crossTheCentre_when_halfWavelengths', () {
      for (final u in [0.0, 12.0, 24.0, 36.0]) {
        expect(WaveGeometry.fullWave(u, height: h), closeTo(0, 1e-9));
      }
    });

    test('should_crestAbout2dp_when_seekAmplitude', () {
      expect(WaveBarMetrics.amplitudeFraction * crest, closeTo(2.0048, 1e-4));
    });
  });

  group('shiftAt', () {
    test('should_travelOneWavelengthPerSecond_when_playing', () {
      expect(WaveGeometry.shiftAt(0), 0);
      expect(WaveGeometry.shiftAt(250), closeTo(6, 1e-9));
      expect(WaveGeometry.shiftAt(1250), closeTo(6, 1e-9));
    });

    test('should_stand_when_speedIsZero', () {
      expect(WaveGeometry.shiftAt(1234, speed: 0), 0);
    });
  });

  group('addWave', () {
    test('should_followTheFullWaveShiftedLeft_when_travelling', () {
      final path = Path();
      WaveGeometry.addWave(path, const WaveSpan(cap, 150), height: 18, amplitude: 1, shift: 6);
      final metric = path.computeMetrics().single;
      // Sample along the path: every point sits on fullWave(x + shift).
      for (var d = 0.0; d < metric.length; d += 3) {
        final p = metric.getTangentForOffset(d)!.position;
        expect(p.dy, closeTo(9 + WaveGeometry.fullWave(p.dx + 6, height: 18), 1e-3));
      }
      expect(path.getBounds().left, closeTo(cap, 1e-6));
      expect(path.getBounds().right, closeTo(150, 1e-6));
    });

    test('should_drawAStraightLine_when_amplitudeIsZero', () {
      final path = Path();
      WaveGeometry.addWave(path, const WaveSpan(cap, 150), height: 18, amplitude: 0, shift: 6);
      final bounds = path.getBounds();
      expect(bounds.top, 9);
      expect(bounds.bottom, 9);
    });
  });

  group('headFraction', () {
    test('should_showSettled_when_pausedEvenUnderAFinger', () {
      expect(WaveGeometry.headFraction(isPlaying: false, preview: 0.8, settled: 0.2), 0.2);
    });

    test('should_followTheFinger_when_playing', () {
      expect(WaveGeometry.headFraction(isPlaying: true, preview: 0.8, settled: 0.2), 0.8);
      expect(WaveGeometry.headFraction(isPlaying: true, preview: null, settled: 0.2), 0.2);
    });
  });

  testWidgets('should_seekOnRelease_when_tapped', (tester) async {
    final clock = PlayheadClock(now: () => 0);
    clock.update(const Playhead(entryId: 'e', positionMs: 0, sampledAtMs: 0, durationMs: 200000));
    double? seeked;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Center(
            child: SizedBox(
              width: 200,
              child: WaveProgressBar(clock: clock, isPlaying: false, durationMs: 200000, onSeek: (f) => seeked = f),
            ),
          ),
        ),
      ),
    );
    final box = tester.getRect(find.byType(WaveProgressBar));
    await tester.tapAt(Offset(box.left + 50, box.center.dy));
    expect(seeked, closeTo(0.25, 1e-9));
    await tester.pump(WaveBarMetrics.bubbleLinger);
    clock.dispose();
  });
}
