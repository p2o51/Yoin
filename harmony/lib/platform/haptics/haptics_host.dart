/// Real haptics (Android ui/experience/Haptics.kt `YoinHaptics` + the
/// composition path of memories/emblem/GrooveHaptics.kt) behind one seam.
///
/// HarmonyOS: `yoin/haptics` → ArkTS `YoinHapticsPlugin`
/// (ohos/entry/src/main/ets/yoin/haptics/), which plays each named haptic as a
/// `@ohos.vibrator` preset (`haptic.effect.soft`, `haptic.clock.timer`,
/// `haptic.notice.success` …, usage `touch` so the system's touch-feedback
/// switch mutes it) and a beat plan as one custom `VibratorPattern` where the
/// motor supports it. Needs ohos.permission.VIBRATE (module.json5).
///
/// Windows: [NoHapticsHost] (no motor).
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Yoin's named haptics: one per Android `YoinHaptics.performX`.
enum YoinHaptic {
  /// KEYBOARD_TAP.
  click,

  /// CLOCK_TICK.
  tick,

  /// TEXT_HANDLE_MOVE.
  lightTick,

  /// CONFIRM.
  confirm,

  /// REJECT.
  reject,

  /// LONG_PRESS.
  longPress,

  /// CONTEXT_CLICK.
  contextClick,

  /// DRAG_START: picking something up to drag it.
  dragStart,

  /// SEGMENT_TICK: a dragged item crossing into the next slot.
  segmentTick,

  /// SEGMENT_FREQUENT_TICK: one of many small things landing in quick succession.
  frequentTick,

  /// GESTURE_THRESHOLD_ACTIVATE: a drag passing a limit.
  threshold,

  /// TOGGLE_ON.
  toggleOn,

  /// TOGGLE_OFF.
  toggleOff,
}

/// One pulse of a composed pattern (Android `Composition.addPrimitive`,
/// HarmonyOS `VibratorPatternBuilder`). Times count from the call.
@immutable
final class HapticPulse {
  const HapticPulse({
    required this.atMs,
    required this.intensity,
    required this.frequency,
    this.durationMs = 0,
    this.rise = false,
  });

  /// Start, in ms from the call.
  final int atMs;

  /// 0…1 strength.
  final double intensity;

  /// 0…1 sharpness: low = a soft thud, high = a crisp tick.
  final double frequency;

  /// 0 = a transient (short) event; otherwise a continuous event this long.
  final int durationMs;

  /// A continuous event that swells from nothing to [intensity].
  final bool rise;

  Map<String, Object> toMap() => {
    'at': atMs,
    'intensity': (intensity.clamp(0.0, 1.0) * 100).round(),
    'frequency': (frequency.clamp(0.0, 1.0) * 100).round(),
    'duration': durationMs,
    'rise': rise,
  };

  @override
  bool operator ==(Object other) =>
      other is HapticPulse &&
      other.atMs == atMs &&
      other.intensity == intensity &&
      other.frequency == frequency &&
      other.durationMs == durationMs &&
      other.rise == rise;

  @override
  int get hashCode => Object.hash(atMs, intensity, frequency, durationMs, rise);

  @override
  String toString() => 'HapticPulse($atMs ms, i $intensity, f $frequency, d $durationMs${rise ? ', rise' : ''})';
}

/// The device's vibrator.
abstract interface class HapticsHost {
  /// Plays one named haptic now (fire and forget; silent without a motor or
  /// with touch feedback off).
  void perform(YoinHaptic haptic);

  /// Whether [playPattern] reaches a motor that can play custom patterns
  /// (HarmonyOS: HD haptics). False until the host has answered.
  bool get supportsPatterns;

  /// Plays [pulses] as ONE pattern started now. Only call when
  /// [supportsPatterns]; otherwise play named haptics at their times.
  void playPattern(List<HapticPulse> pulses);

  /// Stops a pattern still playing.
  void cancelPattern();
}

/// HarmonyOS: the `yoin/haptics` channel. Asks once whether the motor plays
/// custom patterns; every call is fire and forget, and a host without the
/// plugin (tests) stays silent.
final class ChannelHapticsHost implements HapticsHost {
  ChannelHapticsHost({MethodChannel channel = const MethodChannel(channelName)}) : _channel = channel;

  static const channelName = 'yoin/haptics';

  final MethodChannel _channel;
  bool _patterns = false;
  bool _asked = false;

  /// Reads the motor's capabilities (once). Call at start-up; until it answers
  /// [supportsPatterns] is false and beat plans play as named haptics.
  Future<void> init() async {
    if (_asked) return;
    _asked = true;
    try {
      final caps = await _channel.invokeMapMethod<String, Object?>('capabilities');
      _patterns = caps?['patterns'] == true;
    } on MissingPluginException {
      _patterns = false;
    } on PlatformException {
      _patterns = false;
    }
  }

  @override
  bool get supportsPatterns => _patterns;

  @override
  void perform(YoinHaptic haptic) => _send('perform', {'name': haptic.name});

  @override
  void playPattern(List<HapticPulse> pulses) {
    if (pulses.isEmpty) return;
    _send('pattern', {
      'pulses': [for (final p in pulses) p.toMap()],
    });
  }

  @override
  void cancelPattern() => _send('cancel', null);

  void _send(String method, Object? args) {
    // A host without the plugin (tests) or a motor that refused: silent.
    _channel.invokeMethod<void>(method, args).catchError((Object _) {});
  }
}

/// No motor (Windows, tests).
final class NoHapticsHost implements HapticsHost {
  const NoHapticsHost();

  @override
  void perform(YoinHaptic haptic) {}

  @override
  bool get supportsPatterns => false;

  @override
  void playPattern(List<HapticPulse> pulses) {}

  @override
  void cancelPattern() {}
}

/// Records every call (tests).
final class RecordingHapticsHost implements HapticsHost {
  RecordingHapticsHost({this.supportsPatterns = false});

  @override
  bool supportsPatterns;

  final List<YoinHaptic> performed = [];
  final List<List<HapticPulse>> patterns = [];
  int cancels = 0;

  @override
  void perform(YoinHaptic haptic) => performed.add(haptic);

  @override
  void playPattern(List<HapticPulse> pulses) => patterns.add(pulses);

  @override
  void cancelPattern() => cancels++;
}
