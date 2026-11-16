/// The system's own "less motion" settings, followed while Yoin runs.
///
/// Android (origin/main ui/experience/MotionRuntime.kt, resolveMotionProfile):
/// the system's "Remove animations" (an animator duration scale of 0, 7b8a470f8)
/// and power saving put Yoin in MotionProfile.AdaptiveReduced. The scale is read
/// through a ContentObserver, so turning it on or off while Yoin runs takes
/// effect at once, and it is read synchronously as the app starts, so a cold
/// start is reduced from its first frame. It *reduces* Yoin's motion rather than
/// removing it (owner 2026-11-13); a slowed or sped-up scale (0.5×, 10×) keeps
/// full motion.
///
/// Flutter's AdaptiveReduced is `MediaQuery.disableAnimationsOf` (AGENTS.md
/// "Motion"), but the flutter_ohos embedder never sets that flag, so this seam
/// reads the settings itself and [SystemReduceMotionScope]
/// (`lib/ui/component/system_reduce_motion_scope.dart`) folds them into the
/// app root's MediaQuery. main.dart awaits one [SystemMotionHost.read] before
/// `runApp`, so the first frame already knows.
///
/// HarmonyOS: `yoin/motion` → ArkTS `YoinMotionPlugin`
/// (ohos/entry/src/main/ets/yoin/motion/): Accessibility › 减弱动画
/// (`accessibility.isAnimationReduceEnabledSync` + `onAnimationReduceStateChange`,
/// API 23+), Developer options' animator duration scale
/// (`settings.display.ANIMATOR_DURATION_SCALE` + `settings.registerKeyObserver`)
/// and power saving (`power.getPowerMode` + the POWER_SAVE_MODE_CHANGED common
/// event). Each change is pushed as `motion`. No permission.
///
/// Windows: [NoSystemMotionHost] (the Flutter embedder's own flag still counts).
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// One reading of the system's motion settings.
@immutable
final class SystemMotionSettings {
  const SystemMotionSettings({this.animationReduce = false, this.animatorDurationScale = 1, this.powerSave = false});

  /// Nothing asks for less motion (Windows, tests, a read that failed).
  static const SystemMotionSettings normal = SystemMotionSettings();

  /// From the plugin's map {animationReduce: bool, animatorDurationScale: num, powerSave: bool}; a missing
  /// or unreadable value counts as the system default (off, 1×, not saving).
  factory SystemMotionSettings.fromMap(Map<Object?, Object?> map) {
    final scale = map['animatorDurationScale'];
    return SystemMotionSettings(
      animationReduce: map['animationReduce'] == true,
      animatorDurationScale: scale is num && scale.isFinite && scale >= 0 ? scale.toDouble() : 1,
      powerSave: map['powerSave'] == true,
    );
  }

  /// Accessibility's reduce-motion switch (HarmonyOS 减弱动画).
  final bool animationReduce;

  /// The system's animator duration scale (1 when unset); 0 = animations off.
  final double animatorDurationScale;

  /// The device is saving power (HarmonyOS 省电模式 / 超级省电; Android isPowerSaveMode).
  final bool powerSave;

  /// Whether Yoin should run its reduced motion (Android resolveMotionProfile's system reasons): the
  /// accessibility switch, animations turned off (a scale of exactly 0) or power saving. A slowed or
  /// sped-up scale keeps full motion.
  bool get reducesMotion => animationReduce || animatorDurationScale == 0 || powerSave;

  @override
  bool operator ==(Object other) =>
      other is SystemMotionSettings &&
      other.animationReduce == animationReduce &&
      other.animatorDurationScale == animatorDurationScale &&
      other.powerSave == powerSave;

  @override
  int get hashCode => Object.hash(animationReduce, animatorDurationScale, powerSave);

  @override
  String toString() =>
      'SystemMotionSettings(reduce $animationReduce, scale $animatorDurationScale, powerSave $powerSave)';
}

abstract interface class SystemMotionHost {
  /// The latest reading ([SystemMotionSettings.normal] before the first).
  SystemMotionSettings get current;

  /// Reads the settings now; [current] holds them afterwards. Never errors. main.dart awaits it once
  /// before `runApp`, so a cold start under reduce motion is reduced from its first frame (Android reads
  /// the settings synchronously as it starts).
  Future<SystemMotionSettings> read();

  /// The settings now, then every change. Never errors; [SystemMotionSettings.normal] where there is
  /// nothing to read.
  Stream<SystemMotionSettings> watch();
}

/// HarmonyOS: the `yoin/motion` channel. Reads once when listened, then keeps
/// what the plugin pushes; coming back to the front reads again, in case a
/// change was missed while the app was in the background.
final class ChannelSystemMotionHost implements SystemMotionHost {
  ChannelSystemMotionHost({MethodChannel channel = const MethodChannel(channelName)}) : _channel = channel {
    _controller = StreamController<SystemMotionSettings>.broadcast(onListen: _start, onCancel: _stop);
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'motion' && call.arguments is Map) {
        _push(SystemMotionSettings.fromMap(call.arguments as Map<Object?, Object?>));
      }
    });
  }

  static const channelName = 'yoin/motion';

  final MethodChannel _channel;
  late final StreamController<SystemMotionSettings> _controller;
  SystemMotionSettings? _last;
  AppLifecycleListener? _lifecycle;

  void _push(SystemMotionSettings settings) {
    if (settings == _last) return;
    _last = settings;
    _controller.add(settings);
  }

  @override
  SystemMotionSettings get current => _last ?? SystemMotionSettings.normal;

  @override
  Future<SystemMotionSettings> read() async {
    await _read();
    return current;
  }

  void _start() {
    final last = _last;
    if (last != null) _controller.add(last);
    _lifecycle ??= AppLifecycleListener(onResume: _read);
    _read();
  }

  void _stop() {
    _lifecycle?.dispose();
    _lifecycle = null;
  }

  Future<void> _read() async {
    try {
      final raw = await _channel.invokeMethod<Map<Object?, Object?>>('read');
      _push(raw == null ? SystemMotionSettings.normal : SystemMotionSettings.fromMap(raw));
    } on MissingPluginException {
      _push(SystemMotionSettings.normal);
    } on PlatformException {
      _push(SystemMotionSettings.normal);
    }
  }

  @override
  Stream<SystemMotionSettings> watch() => _controller.stream;
}

/// Nothing to read (Windows, tests): the system asks for nothing; Flutter's own
/// `disableAnimations` (where an embedder reports it) still applies.
final class NoSystemMotionHost implements SystemMotionHost {
  const NoSystemMotionHost();

  @override
  SystemMotionSettings get current => SystemMotionSettings.normal;

  @override
  Future<SystemMotionSettings> read() async => SystemMotionSettings.normal;

  @override
  Stream<SystemMotionSettings> watch() => Stream.value(SystemMotionSettings.normal);
}

/// How long main.dart waits for the first reading before `runApp` (the ArkTS plugin answers synchronously; a
/// plugin that never answers must not hold the launch).
const systemMotionFirstReadTimeout = Duration(milliseconds: 300);

/// The platform's motion-settings host; main.dart wires the platform's, tests a fake.
final systemMotionHostProvider = Provider<SystemMotionHost>((ref) => const NoSystemMotionHost());

/// The live system motion settings: the host's [SystemMotionHost.current] from the first frame (main.dart
/// read it before `runApp`), then every change it reports.
final systemMotionProvider = NotifierProvider<SystemMotionNotifier, SystemMotionSettings>(SystemMotionNotifier.new);

class SystemMotionNotifier extends Notifier<SystemMotionSettings> {
  @override
  SystemMotionSettings build() {
    final host = ref.watch(systemMotionHostProvider);
    final changes = host.watch().listen((settings) => state = settings);
    ref.onDispose(changes.cancel);
    return host.current;
  }
}
