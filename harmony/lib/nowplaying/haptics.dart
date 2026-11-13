import 'package:flutter/services.dart';

/// Yoin's named haptics (Android ui/experience/YoinHaptics.kt). Every control
/// asks for one of these by name.
///
/// TODO(harmony): back these with a `yoin/haptics` ArkTS channel mapped to
/// HarmonyOS vibrator presets (spec gap); until then Flutter's
/// [HapticFeedback], a no-op where the embedder doesn't implement it.
abstract final class YoinHaptics {
  /// PLAY, Home / Library taps, the Play split.
  static void click() => HapticFeedback.lightImpact();

  /// The pill, the Queue pill.
  static void contextClick() => HapticFeedback.mediumImpact();

  /// NEXT / PREVIOUS, play mode, scrub end, rating steps, un-star.
  static void tick() => HapticFeedback.selectionClick();

  /// A scrub tap's release.
  static void lightTick() => HapticFeedback.selectionClick();

  /// Starring.
  static void confirm() => HapticFeedback.mediumImpact();

  static void longPress() => HapticFeedback.heavyImpact();

  static void reject() => HapticFeedback.vibrate();
}
