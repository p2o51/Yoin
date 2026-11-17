import 'package:material_color_utilities/material_color_utilities.dart' show ColorUtils;

/// Contrast-ratio helpers as the vendored Kotlin MCU (`contrast/Contrast.kt`)
/// computes them.
///
/// The Dart package's `Contrast` differs in two ways that move tones: it
/// clamps both tones to 0–100 before [ratioOfTones], and [lighter] / [darker]
/// do not reject an out-of-range target luminance. Ported so contrast
/// adjustments land on the same tone as Android.
abstract final class Contrast {
  static const double _contrastRatioEpsilon = 0.04;
  static const double _luminanceGamutMapTolerance = 0.4;

  /// Contrast ratio of two relative luminances (0–100).
  static double ratioOfYs(double y1, double y2) {
    final lighter = y1 > y2 ? y1 : y2;
    final darker = lighter == y2 ? y1 : y2;
    return (lighter + 5.0) / (darker + 5.0);
  }

  /// Contrast ratio of two L* tones. Unlike the Dart package, no clamping.
  static double ratioOfTones(double t1, double t2) =>
      ratioOfYs(ColorUtils.yFromLstar(t1), ColorUtils.yFromLstar(t2));

  /// The darkest tone lighter than [tone] that reaches [ratio], or null.
  static double? lighter(double tone, double ratio) {
    if (tone < 0.0 || tone > 100.0) return null;
    final darkY = ColorUtils.yFromLstar(tone);
    final lightY = ratio * (darkY + 5.0) - 5.0;
    if (lightY < 0.0 || lightY > 100.0) return null;
    final realContrast = ratioOfYs(lightY, darkY);
    final delta = (realContrast - ratio).abs();
    if (realContrast < ratio && delta > _contrastRatioEpsilon) return null;
    final value = ColorUtils.lstarFromY(lightY) + _luminanceGamutMapTolerance;
    return (value < 0.0 || value > 100.0) ? null : value;
  }

  /// [lighter], falling back to white (100).
  static double lighterUnsafe(double tone, double ratio) => lighter(tone, ratio) ?? 100.0;

  /// The lightest tone darker than [tone] that reaches [ratio], or null.
  static double? darker(double tone, double ratio) {
    if (tone < 0.0 || tone > 100.0) return null;
    final lightY = ColorUtils.yFromLstar(tone);
    final darkY = (lightY + 5.0) / ratio - 5.0;
    if (darkY < 0.0 || darkY > 100.0) return null;
    final realContrast = ratioOfYs(lightY, darkY);
    final delta = (realContrast - ratio).abs();
    if (realContrast < ratio && delta > _contrastRatioEpsilon) return null;
    final value = ColorUtils.lstarFromY(darkY) - _luminanceGamutMapTolerance;
    return (value < 0.0 || value > 100.0) ? null : value;
  }

  /// [darker], falling back to black (0).
  static double darkerUnsafe(double tone, double ratio) => darker(tone, ratio) ?? 0.0;
}
