import 'dart:async';

import '../platform/haptics/haptics_host.dart';

/// Yoin's named haptics (Android ui/experience/Haptics.kt `YoinHaptics`).
/// Every control asks for one of these by name; [host] plays it on the
/// device's vibrator (HarmonyOS presets through `yoin/haptics`, chosen in
/// main.dart from the platform bundle; silent until then and in tests).
abstract final class YoinHaptics {
  /// The vibrator. main.dart installs the platform's; tests may swap in a
  /// [RecordingHapticsHost].
  static HapticsHost host = const NoHapticsHost();

  /// Plays every haptic on [platform] from now on, and asks it once what its
  /// motor can do (patterns for the groove award).
  static void install(HapticsHost platform) {
    host = platform;
    if (platform is ChannelHapticsHost) unawaited(platform.init());
  }

  /// PLAY, Home / Library taps, the Play split (KEYBOARD_TAP).
  static void click() => host.perform(YoinHaptic.click);

  /// The pill, the Queue pill (CONTEXT_CLICK).
  static void contextClick() => host.perform(YoinHaptic.contextClick);

  /// NEXT / PREVIOUS, play mode, scrub end, rating steps, un-star (CLOCK_TICK).
  static void tick() => host.perform(YoinHaptic.tick);

  /// A scrub tap's release (TEXT_HANDLE_MOVE).
  static void lightTick() => host.perform(YoinHaptic.lightTick);

  /// Starring (CONFIRM).
  static void confirm() => host.perform(YoinHaptic.confirm);

  static void longPress() => host.perform(YoinHaptic.longPress);

  static void reject() => host.perform(YoinHaptic.reject);

  /// Picking something up to drag it (DRAG_START).
  static void dragStart() => host.perform(YoinHaptic.dragStart);

  /// A dragged item crossing into the next slot (SEGMENT_TICK).
  static void segmentTick() => host.perform(YoinHaptic.segmentTick);

  /// One of many small things landing in quick succession (SEGMENT_FREQUENT_TICK).
  static void frequentTick() => host.perform(YoinHaptic.frequentTick);

  /// A drag passing a limit, the rubber band starts (GESTURE_THRESHOLD_ACTIVATE).
  static void threshold() => host.perform(YoinHaptic.threshold);

  /// A switch flipping (TOGGLE_ON / TOGGLE_OFF).
  static void toggle({required bool on}) => host.perform(on ? YoinHaptic.toggleOn : YoinHaptic.toggleOff);
}
