import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:material_color_utilities/hct/hct.dart';
import 'package:material_color_utilities/utils/math_utils.dart';

import '../theme/color_scheme.dart';
import '../theme/theme.dart';

// Settings' colour system (SettingsColors.kt), after Pixel Settings: one flat
// page colour, rows lifted on the brighter surface, plain monochrome row
// icons. Colour belongs to services only — each music service wears one fixed
// hue family, harmonised toward the base primary, on its accounts and marks.

/// Pixel's rule, the same in light and dark: page = surfaceContainer, rows = surfaceBright.
Color settingsPageColor(ColorScheme scheme) => scheme.surfaceContainer;
Color settingsRowColor(ColorScheme scheme) => scheme.surfaceBright;

/// One service's colour family. Every pair is 50+ tones apart (≥ 4.5:1).
@immutable
final class SettingsTone {
  const SettingsTone({
    required this.container,
    required this.onContainer,
    required this.accent,
    required this.onAccent,
    required this.iconContainer,
    required this.iconContent,
  });

  /// Large tinted surfaces: the account card in use.
  final Color container;
  final Color onContainer;

  /// Strong fills: account avatars.
  final Color accent;
  final Color onAccent;

  /// Pixel's pastel icon circle (T90 light / T80 dark) and its T30 glyph.
  final Color iconContainer;
  final Color iconContent;
}

/// The fixed hue families, one per music service (Pixel's reference hues).
enum SettingsHue {
  blue(256, 40),
  green(148, 40),
  rose(10, 48),
  amber(60, 44);

  const SettingsHue(this.sourceHue, this.chroma);

  final double sourceHue;
  final double chroma;

  /// This hue's tones for [brightness], leaning toward Yoin's base primary.
  /// The base, not the playing cover's primary: a service's colour must not
  /// drift from song to song (HarmonyOS has no wallpaper colour; Yoin's seed
  /// stands in for it, as the default theme does).
  SettingsTone tone(Brightness brightness) =>
      _tones.putIfAbsent((this, brightness), () => settingsTone(this, _basePrimary(brightness).toARGB32(), brightness == Brightness.dark));

  static final _tones = <(SettingsHue, Brightness), SettingsTone>{};
  static final _primaries = <Brightness, Color>{};
  static Color _basePrimary(Brightness b) => _primaries.putIfAbsent(b, () => yoinExpressiveScheme(yoinSeed, b).primary);
}

SettingsTone settingsTone(SettingsHue hue, int sourceArgb, bool dark) {
  final source = Hct.fromInt(sourceArgb);
  // A near-grey source has no meaningful hue to lean toward.
  final harmonized = source.chroma < _minHarmonizeChroma ? hue.sourceHue : harmonizeHue(hue.sourceHue, source.hue);
  Color t(int tone) => Color(Hct.from(harmonized, hue.chroma, tone.toDouble()).toInt());
  return dark
      ? SettingsTone(
          container: t(30),
          onContainer: t(90),
          accent: t(80),
          onAccent: t(20),
          iconContainer: t(80),
          iconContent: t(30),
        )
      : SettingsTone(
          container: t(90),
          onContainer: t(30),
          accent: t(40),
          onAccent: t(100),
          iconContainer: t(90),
          iconContent: t(30),
        );
}

/// MCU `Blend.harmonize` with a tighter cap: turn [designHue] toward
/// [sourceHue] by half their distance, at most [maxHarmonizeDegrees].
double harmonizeHue(double designHue, double sourceHue) {
  final difference = MathUtils.differenceDegrees(designHue, sourceHue);
  final rotation = math.min(difference * 0.5, maxHarmonizeDegrees);
  return MathUtils.sanitizeDegreesDouble(designHue + rotation * MathUtils.rotationDirection(designHue, sourceHue));
}

/// MCU harmonises up to 15°; services sit ~50° apart, so 10° keeps them ≥ 30° apart.
const double maxHarmonizeDegrees = 10;
const double _minHarmonizeChroma = 6;
