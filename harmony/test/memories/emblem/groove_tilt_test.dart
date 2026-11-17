import 'dart:async';
import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/memories/emblem/groove_tilt.dart';
import 'package:yoin/platform/sensors/motion_sensors.dart';

/// A sensor the test feeds by hand; counts registrations.
final class FakeSensors implements MotionSensors {
  final StreamController<RotationSample> controller = StreamController.broadcast();
  int listens = 0;
  int cancels = 0;

  FakeSensors() {
    controller.onListen = () => listens++;
    controller.onCancel = () => cancels++;
  }

  @override
  Stream<RotationSample> rotationVector() => controller.stream;
}

/// The quaternion of a device pitched by [pitchDeg] about its x axis (top edge toward / away from the user).
RotationSample pitched(double pitchDeg, {int rotation = 0}) {
  final h = pitchDeg * math.pi / 360;
  return RotationSample(x: math.sin(h), y: 0, z: 0, w: math.cos(h), displayRotation: rotation, atMs: 0);
}

void main() {
  group('grooveTiltVars', () {
    test('should_showNothing_when_atRest', () {
      final v = grooveTiltVars(0, 0);
      expect(v.tm, 0);
      expect(v.li, closeTo(0.8, 1e-9));
    });

    test('should_capToUnitDisc_when_tiltTooLarge', () {
      final v = grooveTiltVars(3, 4);
      expect(v.tm, closeTo(1, 1e-9));
      expect(v.tx, closeTo(0.6, 1e-9));
      expect(v.ta, closeTo(math.atan2(4, 3) * 180 / math.pi, 1e-9));
    });

    test('should_readZero_when_nan', () {
      expect(grooveTiltVars(double.nan, double.nan).tm, 0);
    });
  });

  group('GrooveTiltFilter', () {
    test('should_driftToNeutral_when_postureHeld', () {
      final f = GrooveTiltFilter();
      f.onSample(0, 0, 0);
      var o = f.onSample(9, 0, 20);
      expect(o.dy, lessThan(-0.3)); // a fresh tilt reads
      for (var t = 40; t < 8000; t += 20) {
        o = f.onSample(9, 0, t);
      }
      expect(o.dy.abs(), lessThan(0.01)); // the baseline caught up
    });

    test('should_rebase_when_samplesPaused', () {
      final f = GrooveTiltFilter();
      f.onSample(0, 0, 0);
      final o = f.onSample(30, 10, 0 + GrooveTiltFilter.rebaseAfterMs + 1);
      expect(o, Offset.zero);
    });
  });

  group('GrooveTiltSpring', () {
    test('should_settleAtRest_when_goalZero', () {
      final s = GrooveTiltSpring();
      for (var i = 0; i < 30; i++) {
        s.step(const Offset(0.5, -0.5), 0.016);
      }
      expect(s.x, greaterThan(0.2));
      var rested = false;
      for (var i = 0; i < 400 && !rested; i++) {
        rested = s.step(Offset.zero, 0.016);
      }
      expect(rested, isTrue);
      expect(s.x, 0);
    });
  });

  group('orientationOf', () {
    test('should_readFlat_when_identity', () {
      final o = orientationOf(const RotationSample(x: 0, y: 0, z: 0, w: 1, atMs: 0));
      expect(o.beta, closeTo(0, 1e-9));
      expect(o.gamma, closeTo(0, 1e-9));
    });

    test('should_readPitchAsBeta_when_portrait', () {
      final o = orientationOf(pitched(20));
      expect(o.beta.abs(), closeTo(20, 1e-6));
      expect(o.gamma, closeTo(0, 1e-6));
    });

    test('should_readPitchAsGamma_when_landscape', () {
      final o = orientationOf(pitched(20, rotation: 1));
      expect(o.beta, closeTo(0, 1e-6));
      expect(o.gamma.abs(), closeTo(20, 1e-6));
    });
  });

  group('GrooveTiltController', () {
    testWidgets('should_listenOnlyWhileActive_when_toggled', (tester) async {
      final sensors = FakeSensors();
      final c = GrooveTiltController(sensors: sensors, vsync: const TestVSync(), observeLifecycle: false);
      expect(c.isListening, isFalse);
      c.update(active: true, reducedMotion: false);
      expect(c.isListening, isTrue);
      c.update(active: false, reducedMotion: false);
      expect(c.isListening, isFalse);
      c.update(active: true, reducedMotion: true);
      expect(c.isListening, isFalse); // reduced motion parks the sensor
      c.dispose();
      expect(sensors.listens, 1);
    });

    testWidgets('should_tiltThenReturnToRest_when_samplesStop', (tester) async {
      var now = 0;
      final c = GrooveTiltController(sensors: FakeSensors(), vsync: tester, clock: () => now, observeLifecycle: false);
      c.update(active: true, reducedMotion: false);
      c.onSample(pitched(0));
      for (var i = 1; i <= 15; i++) {
        now += 20;
        c.onSample(pitched(10));
        await tester.pump(const Duration(milliseconds: 20));
      }
      expect(c.value.distance, greaterThan(0.1));
      // the sensor goes quiet: the tilt goes back to rest
      for (var i = 0; i < 200; i++) {
        now += 16;
        await tester.pump(const Duration(milliseconds: 16));
      }
      expect(c.value, Offset.zero);
      c.dispose();
    });
  });
}
