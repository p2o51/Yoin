import 'package:material_color_utilities/material_color_utilities.dart' show Hct, MathUtils;

import 'color_spec_2025.dart';
import 'contrast.dart';
import 'dynamic_scheme.dart';
import 'tonal_palette.dart';

/// A contrast ratio that depends on the scheme's contrast level
/// (vendored Kotlin `dynamiccolor/ContrastCurve.kt`).
final class ContrastCurve {
  const ContrastCurve(this.low, this.normal, this.medium, this.high);

  /// Ratios at contrast level -1, 0, 0.5 and 1.
  final double low, normal, medium, high;

  /// The ratio at [contrastLevel], linearly interpolated between the stops.
  double get(double contrastLevel) {
    if (contrastLevel <= -1.0) return low;
    if (contrastLevel < 0.0) return MathUtils.lerp(low, normal, contrastLevel + 1.0);
    if (contrastLevel < 0.5) return MathUtils.lerp(normal, medium, contrastLevel / 0.5);
    if (contrastLevel < 1.0) return MathUtils.lerp(medium, high, (contrastLevel - 0.5) / 0.5);
    return high;
  }
}

/// How a [ToneDeltaPair] holds its delta.
enum DeltaConstraint {
  /// roleA sits exactly `delta` away from roleB.
  exact,

  /// roleA sits at most `delta` away from roleB.
  nearer,

  /// roleA sits at least `delta` away from roleB.
  farther,
}

/// Which way a [ToneDeltaPair]'s roleA moves relative to roleB.
enum TonePolarity {
  /// roleA is always darker.
  darker,

  /// roleA is always lighter.
  lighter,

  /// roleA is darker in light mode and lighter in dark mode.
  relativeDarker,

  /// roleA is lighter in light mode and darker in dark mode.
  relativeLighter,
}

/// A tone relation between two roles, e.g. a container at least 5 tones
/// lighter than its accent (vendored Kotlin `dynamiccolor/ToneDeltaPair.kt`).
final class ToneDeltaPair {
  const ToneDeltaPair({
    required this.roleA,
    required this.roleB,
    required this.delta,
    required this.polarity,
    this.stayTogether = true,
    this.constraint = DeltaConstraint.exact,
  });

  final DynamicColor roleA;
  final DynamicColor roleB;
  final double delta;
  final TonePolarity polarity;
  final bool stayTogether;
  final DeltaConstraint constraint;
}

typedef SchemeFn<T> = T Function(DynamicScheme scheme);

/// One colour role, resolved against a [DynamicScheme]
/// (vendored Kotlin `dynamiccolor/DynamicColor.kt`).
///
/// Holds the rules — palette, tone, background, contrast, tone pairing —
/// and leaves the solving to [ColorSpec2025].
final class DynamicColor {
  DynamicColor({
    required this.name,
    required this.palette,
    this.isBackground = false,
    this.chromaMultiplier,
    this.background,
    SchemeFn<double>? tone,
    this.secondBackground,
    this.contrastCurve,
    this.toneDeltaPair,
    this.opacity,
  }) : tone = tone ?? initialToneFromBackground(background) {
    if (background == null && secondBackground != null) {
      throw ArgumentError('Color $name has secondBackground defined, but background is not defined.');
    }
    if (background == null && contrastCurve != null) {
      throw ArgumentError('Color $name has contrastCurve defined, but background is not defined.');
    }
    if (background != null && contrastCurve == null) {
      throw ArgumentError('Color $name has background defined, but contrastCurve is not defined.');
    }
  }

  /// Snake-case role name; [ToneDeltaPair] matching and the `_fixed_dim`
  /// exemption go by it.
  final String name;
  final SchemeFn<TonalPalette> palette;
  final bool isBackground;
  final SchemeFn<double>? chromaMultiplier;
  final SchemeFn<DynamicColor?>? background;

  /// The tone before contrast and pairing adjustments.
  final SchemeFn<double> tone;
  final SchemeFn<DynamicColor?>? secondBackground;
  final SchemeFn<ContrastCurve?>? contrastCurve;
  final SchemeFn<ToneDeltaPair?>? toneDeltaPair;
  final SchemeFn<double?>? opacity;

  /// A copy under a new [name] (and optionally a new raw [tone]), for the
  /// 2025 remaps such as background → surface.
  DynamicColor copyWith({required String name, SchemeFn<double>? tone}) => DynamicColor(
    name: name,
    palette: palette,
    isBackground: isBackground,
    chromaMultiplier: chromaMultiplier,
    background: background,
    tone: tone ?? this.tone,
    secondBackground: secondBackground,
    contrastCurve: contrastCurve,
    toneDeltaPair: toneDeltaPair,
    opacity: opacity,
  );

  /// The resolved colour as ARGB, with [opacity] applied when set.
  int getArgb(DynamicScheme scheme) {
    final argb = getHct(scheme).toInt();
    final opacityPercentage = opacity?.call(scheme);
    if (opacityPercentage == null) return argb;
    final alpha = (opacityPercentage * 255).round().clamp(0, 255);
    return (argb & 0x00ffffff) | (alpha << 24);
  }

  /// The resolved colour.
  Hct getHct(DynamicScheme scheme) => scheme.spec.getHct(scheme, this);

  /// The resolved tone, after contrast and pairing.
  double getTone(DynamicScheme scheme) => scheme.spec.getTone(scheme, this);

  /// A role with no background: its colour is [argb]'s, fixed.
  static DynamicColor fromArgb(String name, int argb) {
    final hct = Hct.fromInt(argb);
    final palette = TonalPalette.fromInt(argb);
    return DynamicColor(name: name, palette: (_) => palette, tone: (_) => hct.tone);
  }

  /// A foreground tone on [bgTone] that gets as close to [ratio] as it can,
  /// preferring light text on tones below ~T60.
  static double foregroundTone(double bgTone, double ratio) {
    final lighterTone = Contrast.lighterUnsafe(bgTone, ratio);
    final darkerTone = Contrast.darkerUnsafe(bgTone, ratio);
    final lighterRatio = Contrast.ratioOfTones(lighterTone, bgTone);
    final darkerRatio = Contrast.ratioOfTones(darkerTone, bgTone);
    if (tonePrefersLightForeground(bgTone)) {
      // Both fail a very high ratio by about the same amount: stay light.
      final negligibleDifference =
          (lighterRatio - darkerRatio).abs() < 0.1 && lighterRatio < ratio && darkerRatio < ratio;
      return lighterRatio >= ratio || lighterRatio >= darkerRatio || negligibleDifference
          ? lighterTone
          : darkerTone;
    }
    return darkerRatio >= ratio || darkerRatio >= lighterRatio ? darkerTone : lighterTone;
  }

  /// Moves a tone that prefers light text but cannot hold it at 4.5 down to T49.
  static double enableLightForeground(double tone) =>
      tonePrefersLightForeground(tone) && !toneAllowsLightForeground(tone) ? 49.0 : tone;

  /// Tones below T60 read better with light text (APCA research).
  static bool tonePrefersLightForeground(double tone) => tone.round() < 60;

  /// Tones up to T49 hold white at 4.5.
  static bool toneAllowsLightForeground(double tone) => tone.round() <= 49;

  /// The default raw tone: the background's resolved tone, or T50.
  static SchemeFn<double> initialToneFromBackground(SchemeFn<DynamicColor?>? background) {
    if (background == null) return (_) => 50.0;
    return (scheme) => background(scheme)?.getTone(scheme) ?? 50.0;
  }

  @override
  String toString() => 'DynamicColor($name)';
}
