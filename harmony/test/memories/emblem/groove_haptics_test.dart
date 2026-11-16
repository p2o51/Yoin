import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/emblem/groove_award.dart';
import 'package:yoin/memories/emblem/groove_emblem.dart';
import 'package:yoin/memories/emblem/groove_geometry.dart';
import 'package:yoin/memories/emblem/groove_haptics.dart';
import 'package:yoin/memories/emblem/groove_script.dart';
import 'package:yoin/memories/palette/memory_palette.dart';
import 'package:yoin/platform/haptics/haptics_host.dart';

Widget host(GrooveAwardController award) => MaterialApp(
  home: Scaffold(
    body: Center(
      child: GrooveEmblem(
        size: 96,
        ratedTracks: List.generate(10, (i) => i < 5),
        kind: GrooveScoreKind.album,
        scoreText: '10.0',
        palette: MemoryPaletteSamples.m1,
        award: award,
      ),
    ),
  ),
);

Future<void> runFrames(WidgetTester tester, int ms) async {
  for (var t = 0; t < ms; t += 16) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

GrooveBeat beat(int at, GroovePrimitive p, [double scale = 1]) =>
    GrooveBeat(atMs: at, primitive: p, scale: scale, fallback: p.defaultFallback, what: 'test');

void main() {
  group('grooveHapticPulses', () {
    test('should_startRightAfterPredecessor_when_beatsOverlap', () {
      final pulses = grooveHapticPulses([
        beat(0, GroovePrimitive.click, 0.6),
        beat(20, GroovePrimitive.tick, 0.35),
        beat(300, GroovePrimitive.thud),
      ]);
      expect(pulses.map((p) => p.atMs), [0, GroovePulseShapes.transientMs, 300]);
      expect(pulses.first.intensity, 0.6);
      expect(pulses.last.frequency, lessThan(pulses.first.frequency)); // a thud is softer than a click
    });

    test('should_swell_when_quickRise', () {
      final p = grooveHapticPulses([beat(10, GroovePrimitive.quickRise, 0.5)]).single;
      expect(p.rise, isTrue);
      expect(p.durationMs, GroovePulseShapes.quickRiseMs);
    });

    test('should_keepOnlyBeatsWithStandIns_when_fallback', () {
      final kept = fallbackBeats([beat(0, GroovePrimitive.click), beat(40, GroovePrimitive.quickRise)]);
      expect(kept.single.primitive, GroovePrimitive.click);
    });
  });

  group('award routes', () {
    testWidgets('should_playOnePattern_when_motorSupportsPatterns', (tester) async {
      final motor = RecordingHapticsHost(supportsPatterns: true);
      final beats = <GrooveBeat>[];
      final award = GrooveAwardController(onBeat: beats.add, haptics: motor)..setPending(true);
      await tester.pumpWidget(host(award));
      award.play();
      await runFrames(tester, 2500);
      expect(motor.patterns, hasLength(1));
      expect(motor.patterns.single.length, inInclusiveRange(2, 7));
      expect(beats, isEmpty); // no per-beat stand-ins on the pattern route
      expect(award.isAwarded, isTrue);
      award.dispose();
    });

    testWidgets('should_cancelPattern_when_paused', (tester) async {
      final motor = RecordingHapticsHost(supportsPatterns: true);
      final award = GrooveAwardController(haptics: motor);
      await tester.pumpWidget(host(award));
      award.play();
      await tester.pump();
      award.pause();
      expect(motor.cancels, 1);
      award.resume();
      expect(motor.patterns, hasLength(2)); // the later beats, from now
      award.dispose();
    });

    testWidgets('should_playStandIns_when_motorHasNoPatterns', (tester) async {
      final motor = RecordingHapticsHost();
      final beats = <GrooveBeat>[];
      final award = GrooveAwardController(onBeat: beats.add, haptics: motor);
      await tester.pumpWidget(host(award));
      award.play();
      await runFrames(tester, 2500);
      expect(motor.patterns, isEmpty);
      expect(beats, isNotEmpty);
      award.dispose();
    });
  });
}
