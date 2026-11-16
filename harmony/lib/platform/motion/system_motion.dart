/// The system's own "less motion" settings, followed while Yoin runs.
///
/// Android (origin/main 7b8a470f8, ui/experience/MotionRuntime.kt): the
/// system's "Remove animations" (an animator duration scale of 0) puts Yoin in
/// MotionProfile.AdaptiveReduced, read through a ContentObserver so turning it
/// on or off while Yoin runs takes effect at once. It *reduces* Yoin's motion
/// rather than removing it (owner 2026-11-13); a slowed or sped-up scale (0.5×,
/// 10×) keeps full motion.
///
/// Flutter's AdaptiveReduced is `MediaQuery.disableAnimationsOf` (AGENTS.md
/// "Motion"), but the flutter_ohos embedder never sets that flag, so this seam
/// reads the settings itself and [SystemReduceMotionScope]
/// (`lib/ui/component/system_reduce_motion_scope.dart`) folds them into the
/// app root's MediaQuery.
///
/// HarmonyOS: `yoin/motion` → ArkTS `YoinMotionPlugin`
/// (ohos/entry/src/main/ets/yoin/motion/): Accessibility › 减弱动画
/// (`accessibility.isAnimationReduceEnabledSync` + `onAnimationReduceStateChange`,
/// API 23+) and Developer options' animator duration scale
/// (`settings.display.ANIMATOR_DURATION_SCALE` + `settings.registerKeyObserver`).
/// Each change is pushed as `motion`. No permission.
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
  const SystemMotionSettings({this.animationReduce = false, this.animatorDurationScale = 1});

  /// Nothing asks for less motion (Windows, tests, a read that failed).
  static const SystemMotionSettings normal = SystemMotionSettings();

  /// From the plugin's map {animationReduce: bool, animatorDurationScale: num}; a missing or
  /// unreadable value counts as the system default (off, 1×).
  factory SystemMotionSettings.fromMap(Map<Object?, Object?> map) {
    final scale = map['animatorDurationScale'];
    return SystemMotionSettings(
      animationReduce: map['animationReduce'] == true,
      animatorDurationScale: scale is num && scale.isFinite && scale >= 0 ? scale.toDouble() : 1,
    );
  }

  /// Accessibility's reduce-motion switch (HarmonyOS 减弱动画).
  final bool animationReduce;

  /// The system's animator duration scale (1 when unset); 0 = animations off.
  final double animatorDurationScale;

  /// Whether Yoin should run its reduced motion (Android resolveMotionProfile's system reasons): the
  /// accessibility switch, or animations turned off (a scale of exactly 0). A slowed or sped-up scale keeps
  /// full motion.
  bool get reducesMotion => animationReduce || animatorDurationScale == 0;

  @override
  bool operator ==(Object other) =>
      other is SystemMotionSettings &&
      other.animationReduce == animationReduce &&
      other.animatorDurationScale == animatorDurationScale;

  @override
  int get hashCode => Object.hash(animationReduce, animatorDurationScale);

  @override
  String toString() => 'SystemMotionSettings(reduce $animationReduce, scale $animatorDurationScale)';
}

abstract interface class SystemMotionHost {
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
  Stream<SystemMotionSettings> watch() => Stream.value(SystemMotionSettings.normal);
}

/// The platform's motion-settings host; main.dart wires the platform's, tests a fake.
final systemMotionHostProvider = Provider<SystemMotionHost>((ref) => const NoSystemMotionHost());

/// The live system motion settings ([SystemMotionSettings.normal] until the first reading).
final systemMotionProvider = StreamProvider<SystemMotionSettings>((ref) => ref.watch(systemMotionHostProvider).watch());
