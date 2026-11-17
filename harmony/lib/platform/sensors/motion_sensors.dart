/// The device's rotation-vector sensor (Android `Sensor.TYPE_GAME_ROTATION_VECTOR`
/// for the groove emblem's tilt colour, memories/emblem/GrooveTilt.kt).
///
/// HarmonyOS: `yoin/sensors` → ArkTS `YoinSensorsPlugin`
/// (ohos/entry/src/main/ets/yoin/sensors/): `@ohos.sensor` ROTATION_VECTOR at
/// the `game` rate, registered only while Dart listens (`start` / `stop`), each
/// sample pushed as `rotation` with the display's rotation. No permission.
///
/// Windows: [NoMotionSensors] (no samples, the tilt stays at rest).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// One rotation-vector sample: the unit quaternion (x, y, z, w) of the device
/// in its natural axes, plus the display's rotation then (0…3 = 0°, 90°,
/// 180°, 270°, as HarmonyOS `display.rotation` / Android `Surface.ROTATION_*`).
@immutable
final class RotationSample {
  const RotationSample({
    required this.x,
    required this.y,
    required this.z,
    required this.w,
    this.displayRotation = 0,
    required this.atMs,
  });

  factory RotationSample.fromMap(Map<Object?, Object?> map) {
    double read(String key) => (map[key] as num?)?.toDouble() ?? 0;
    return RotationSample(
      x: read('x'),
      y: read('y'),
      z: read('z'),
      w: read('w'),
      displayRotation: (map['rotation'] as num?)?.toInt() ?? 0,
      atMs: (map['t'] as num?)?.toInt() ?? 0,
    );
  }

  final double x;
  final double y;
  final double z;
  final double w;
  final int displayRotation;

  /// Monotonic time of the sample, in ms.
  final int atMs;
}

abstract interface class MotionSensors {
  /// Rotation-vector samples while listened to; the sensor is off with no
  /// listener. Empty where there is none.
  Stream<RotationSample> rotationVector();
}

/// HarmonyOS: the `yoin/sensors` channel.
final class ChannelMotionSensors implements MotionSensors {
  ChannelMotionSensors({MethodChannel channel = const MethodChannel(channelName)}) : _channel = channel {
    _controller = StreamController<RotationSample>.broadcast(onListen: _start, onCancel: _stop);
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'rotation' && call.arguments is Map) {
        _controller.add(RotationSample.fromMap(call.arguments as Map<Object?, Object?>));
      }
    });
  }

  static const channelName = 'yoin/sensors';

  final MethodChannel _channel;
  late final StreamController<RotationSample> _controller;

  void _start() => _channel.invokeMethod<void>('start').catchError((Object _) {});

  void _stop() => _channel.invokeMethod<void>('stop').catchError((Object _) {});

  @override
  Stream<RotationSample> rotationVector() => _controller.stream;
}

/// No sensor (Windows, tests).
final class NoMotionSensors implements MotionSensors {
  const NoMotionSensors();

  @override
  Stream<RotationSample> rotationVector() => const Stream.empty();
}

/// The device's motion sensors; main.dart overrides it with the platform's.
final motionSensorsProvider = Provider<MotionSensors>((ref) => const NoMotionSensors());
