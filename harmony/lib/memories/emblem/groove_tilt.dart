/// 唱片刻纹 · tilt (Android GrooveTilt.kt). v4 reads the tilt as COLOUR, not
/// light: the outer ring lines turn lighter (toward the accent) on the side the
/// tilt points to and deeper on the far side; at rest nothing shows. Ported
/// from groove.js v4 `tiltVars` / `tiltDriver` (sensor path): relative tilt
/// around a slow baseline (τ 1.6 s, so a holding posture becomes neutral),
/// ±18° → ±1, capped to the unit disc, followed by one spring (dampingRatio
/// .9, stiffness 90).
///
/// The sensor (rotation vector, through `lib/platform/sensors`) runs only
/// while [GrooveTiltController.update] says the emblem is shown at rest, the
/// app is resumed and motion is not reduced; any one of them dropping stops it
/// at once and the tilt springs back to rest.
library;

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';

import '../../platform/sensors/motion_sensors.dart';

const double _deg = math.pi / 180;

/// Resting light direction of the v3 sheen (kept for the `rot` / `li` outputs).
const double _restAngle = -128.0;

/// Prototype `tiltVars(x, y)`: [tx], [ty] in −1…1 are the tilt in screen axes
/// (+x right, +y down), capped to the unit disc. v4 draws only from [ta]
/// (direction, degrees) and [tm] (size, 0 at rest); [rot] and [li] are the v3
/// light angle swing and intensity, kept so the port stays complete.
@immutable
final class GrooveTiltVars {
  const GrooveTiltVars({
    required this.rot,
    required this.tx,
    required this.ty,
    required this.li,
    required this.ta,
    required this.tm,
  });

  final double rot;
  final double tx;
  final double ty;
  final double li;
  final double ta;
  final double tm;
}

GrooveTiltVars grooveTiltVars(double x, double y) {
  var tx = x.isNaN ? 0.0 : x;
  var ty = y.isNaN ? 0.0 : y;
  final mg = math.sqrt(tx * tx + ty * ty);
  if (mg > 1) {
    tx /= mg;
    ty /= mg;
  }
  final ux = math.cos(_restAngle * _deg);
  final uy = math.sin(_restAngle * _deg);
  const a = 0.53; // asin(.53) ≈ 32° at most
  var d = math.atan2(uy + a * ty, ux + a * tx) / _deg - _restAngle;
  d = ((d + 540) % 360) - 180;
  return GrooveTiltVars(
    rot: d,
    tx: tx,
    ty: ty,
    li: 0.8 + 0.2 * (tx * ux + ty * uy),
    ta: math.atan2(ty, tx) / _deg,
    tm: math.min(1.0, math.sqrt(tx * tx + ty * ty)),
  );
}

/// Caps a tilt to the unit disc (prototype `unit`).
Offset capToUnitDisc(Offset o) {
  final mg = o.distance;
  return mg > 1 ? o / mg : o;
}

/// The sensor half of `tiltDriver` (`onOri`), in W3C DeviceOrientation terms:
/// [onSample] takes beta (front-back, top edge up = +) and gamma (left-right,
/// right edge down = +) in degrees, relative to the CURRENT display
/// orientation, and returns the raw tilt (uncapped; the follow step caps it).
final class GrooveTiltFilter {
  static const double baselineTauS = 1.6;
  static const double fullTiltDeg = 18.0;
  static const int rebaseAfterMs = 1500;

  double _baseBeta = 0;
  double _baseGamma = 0;
  bool _hasBase = false;
  int? _lastAtMs;

  Offset onSample(double betaDeg, double gammaDeg, int nowMs) {
    final last = _lastAtMs;
    if (!_hasBase || (last != null && nowMs - last > rebaseAfterMs)) {
      _baseBeta = betaDeg;
      _baseGamma = gammaDeg;
      _hasBase = true;
    }
    final dt = last == null ? 0.25 : ((nowMs - last) / 1000.0).clamp(0.0, 0.25);
    final a = 1 - math.exp(-dt / baselineTauS);
    _baseBeta += (betaDeg - _baseBeta) * a;
    _baseGamma += (gammaDeg - _baseGamma) * a;
    _lastAtMs = nowMs;
    // the light leans away from gravity
    final x = -(gammaDeg - _baseGamma) / fullTiltDeg;
    final y = -(betaDeg - _baseBeta) / fullTiltDeg;
    return Offset(x, y);
  }

  void reset() {
    _hasBase = false;
    _lastAtMs = null;
  }
}

/// The follow spring of `tiltDriver.step`: semi-implicit Euler on
/// (dampingRatio .9, stiffness 90), dt capped at 34ms. [step] returns true
/// once it has come to rest on a rest goal.
final class GrooveTiltSpring {
  static const double stiffness = 90.0;
  static final double damping = 2 * 0.9 * math.sqrt(stiffness);
  static const double restEpsilon = 2e-3;

  double x = 0;
  double y = 0;
  double _vx = 0;
  double _vy = 0;

  bool step(Offset goal, double dtS) {
    final dt = dtS.clamp(0.001, 0.034);
    _vx += (stiffness * (goal.dx - x) - damping * _vx) * dt;
    x += _vx * dt;
    _vy += (stiffness * (goal.dy - y) - damping * _vy) * dt;
    y += _vy * dt;
    if (goal == Offset.zero && x.abs() + y.abs() + _vx.abs() + _vy.abs() < restEpsilon) {
      snapToRest();
      return true;
    }
    return false;
  }

  void snapToRest() {
    x = 0;
    y = 0;
    _vx = 0;
    _vy = 0;
  }
}

// ---------------------------------------------------------------- rotation vector → beta / gamma

/// Android `SensorManager` axis codes for [remapCoordinateSystem].
abstract final class SensorAxis {
  static const int x = 1;
  static const int y = 2;
  static const int z = 3;
  static const int minusX = x | 0x80;
  static const int minusY = y | 0x80;
  static const int minusZ = z | 0x80;
}

/// Android `SensorManager.getRotationMatrixFromVector` (3×3, row-major).
List<double> rotationMatrixFromVector(double q1, double q2, double q3, double q0) {
  final sqQ1 = 2 * q1 * q1, sqQ2 = 2 * q2 * q2, sqQ3 = 2 * q3 * q3;
  final q1q2 = 2 * q1 * q2, q3q0 = 2 * q3 * q0, q1q3 = 2 * q1 * q3;
  final q2q0 = 2 * q2 * q0, q2q3 = 2 * q2 * q3, q1q0 = 2 * q1 * q0;
  return [
    1 - sqQ2 - sqQ3, q1q2 - q3q0, q1q3 + q2q0, //
    q1q2 + q3q0, 1 - sqQ1 - sqQ3, q2q3 - q1q0, //
    q1q3 - q2q0, q2q3 + q1q0, 1 - sqQ1 - sqQ2,
  ];
}

/// Android `SensorManager.remapCoordinateSystem` for a 3×3 matrix.
List<double> remapCoordinateSystem(List<double> inR, int axisX, int axisY) {
  var zAxis = axisX ^ axisY;
  final x = (axisX & 0x3) - 1;
  final y = (axisY & 0x3) - 1;
  final z = (zAxis & 0x3) - 1;
  final axisYOf = (z + 1) % 3;
  final axisZOf = (z + 2) % 3;
  if (((x ^ axisYOf) | (y ^ axisZOf)) != 0) zAxis ^= 0x80;
  final sx = axisX >= 0x80, sy = axisY >= 0x80, sz = zAxis >= 0x80;
  final out = List<double>.filled(9, 0);
  for (var j = 0; j < 3; j++) {
    final offset = j * 3;
    for (var i = 0; i < 3; i++) {
      if (x == i) out[offset + i] = sx ? -inR[offset] : inR[offset];
      if (y == i) out[offset + i] = sy ? -inR[offset + 1] : inR[offset + 1];
      if (z == i) out[offset + i] = sz ? -inR[offset + 2] : inR[offset + 2];
    }
  }
  return out;
}

/// The device's (beta, gamma) in degrees for a rotation-vector sample, relative
/// to the display's current rotation: rotation matrix → remap for the display
/// → `getOrientation` pitch / roll, turned to the W3C sign convention the
/// prototype was tuned on (Android pitch is + when the top edge dips, roll +
/// when the left edge dips).
({double beta, double gamma}) orientationOf(RotationSample s) {
  final r = rotationMatrixFromVector(s.x, s.y, s.z, s.w);
  final (ax, ay) = switch (s.displayRotation & 3) {
    1 => (SensorAxis.y, SensorAxis.minusX),
    2 => (SensorAxis.minusX, SensorAxis.minusY),
    3 => (SensorAxis.minusY, SensorAxis.x),
    _ => (SensorAxis.x, SensorAxis.y),
  };
  final m = remapCoordinateSystem(r, ax, ay);
  final pitch = math.asin((-m[7]).clamp(-1.0, 1.0));
  final roll = math.atan2(-m[6], m[8]);
  return (beta: -pitch / _deg, gamma: -roll / _deg);
}

// ---------------------------------------------------------------- the controller

/// The tilt one emblem reads ([value], −1…1 screen axes; read it in paint
/// only, so tilting repaints and never rebuilds).
///
/// Sensor samples older than [freshMs] no longer steer: the tilt returns to
/// rest. The follow loop sleeps at rest.
class GrooveTiltController extends ChangeNotifier implements ValueListenable<Offset> {
  GrooveTiltController({
    required MotionSensors sensors,
    required TickerProvider vsync,
    @visibleForTesting int Function()? clock,
    @visibleForTesting bool observeLifecycle = true,
  }) : _sensors = sensors,
       _clock = clock ?? _monotonicMs {
    _ticker = vsync.createTicker(_onTick);
    if (observeLifecycle) {
      final state = SchedulerBinding.instance.lifecycleState;
      _resumed = state == null || state == AppLifecycleState.resumed;
      _lifecycle = AppLifecycleListener(onStateChange: (s) => _setResumed(s == AppLifecycleState.resumed));
    }
  }

  static const int freshMs = 700;

  static final Stopwatch _watch = Stopwatch()..start();
  static int _monotonicMs() => _watch.elapsedMilliseconds;

  final MotionSensors _sensors;
  final int Function() _clock;
  late final Ticker _ticker;
  AppLifecycleListener? _lifecycle;

  final GrooveTiltFilter _filter = GrooveTiltFilter();
  final GrooveTiltSpring _spring = GrooveTiltSpring();
  StreamSubscription<RotationSample>? _subscription;

  Offset _goal = Offset.zero;
  int? _goalAtMs;
  Duration? _lastTick;

  bool _active = false;
  bool _reducedMotion = false;
  bool _resumed = true;
  Offset _value = Offset.zero;

  @override
  Offset get value => _value;

  /// The sensor is registered.
  bool get isListening => _subscription != null;

  bool get _enabled => _active && !_reducedMotion && _resumed;

  /// [active]: the emblem is shown at rest (the owner decides); [reducedMotion]:
  /// the tilt stays at rest and the sensor stays off.
  void update({required bool active, required bool reducedMotion}) {
    if (active == _active && reducedMotion == _reducedMotion) return;
    _active = active;
    final wasReduced = _reducedMotion;
    _reducedMotion = reducedMotion;
    if (reducedMotion && !wasReduced) {
      _ticker.stop();
      _spring.snapToRest();
      _set(Offset.zero);
    }
    _apply();
  }

  void _setResumed(bool resumed) {
    if (resumed == _resumed) return;
    _resumed = resumed;
    _apply();
  }

  void _apply() {
    if (_enabled) {
      _subscription ??= _sensors.rotationVector().listen(onSample);
    } else if (_subscription != null) {
      _subscription!.cancel();
      _subscription = null;
      _filter.reset();
      _goalAtMs = null;
      _kick();
    }
  }

  /// One sensor sample (public for tests).
  @visibleForTesting
  void onSample(RotationSample sample) {
    final o = orientationOf(sample);
    final now = _clock();
    _goal = capToUnitDisc(_filter.onSample(o.beta, o.gamma, now));
    _goalAtMs = now;
    _kick();
  }

  Offset _currentGoal() {
    final at = _goalAtMs;
    return at != null && _clock() - at < freshMs ? _goal : Offset.zero;
  }

  void _kick() {
    if (_reducedMotion || _ticker.isActive) return;
    _lastTick = null;
    _ticker.start();
  }

  void _onTick(Duration elapsed) {
    final last = _lastTick;
    final dt = last == null ? 0.016 : (elapsed - last).inMicroseconds / 1e6;
    _lastTick = elapsed;
    final atRest = _spring.step(_currentGoal(), dt);
    _set(Offset(_spring.x, _spring.y));
    if (atRest) _ticker.stop();
  }

  void _set(Offset v) {
    if (v == _value) return;
    _value = v;
    notifyListeners();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _subscription = null;
    _lifecycle?.dispose();
    _ticker.dispose();
    super.dispose();
  }
}
