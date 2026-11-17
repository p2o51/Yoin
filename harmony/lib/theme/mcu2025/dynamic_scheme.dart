import 'package:material_color_utilities/material_color_utilities.dart' show Hct, MathUtils;

import 'color_spec_2025.dart';
import 'dynamic_color.dart';
import 'tonal_palette.dart';

/// Scheme styles (vendored Kotlin `dynamiccolor/Variant.kt`). Only
/// [neutral], [tonalSpot], [vibrant] and [expressive] have 2025 rules; the
/// rest fall back to the 2021 spec, which this port does not carry.
enum Variant { monochrome, neutral, tonalSpot, vibrant, expressive, fidelity, content, rainbow, fruitSalad, cmf }

/// The device class a scheme targets. Watch schemes are darker and drop
/// the container pairings.
enum SchemePlatform { phone, watch }

/// Material colour spec revisions. Yoin uses [spec2025] (Android's
/// `SPEC_2025`); [spec2026] resolves to 2025 for the variants this port has,
/// as it does in Kotlin.
enum SpecVersion { spec2021, spec2025, spec2026 }

/// The inputs a [DynamicColor] resolves against: source colour, variant,
/// light/dark, contrast level, platform, spec, and six palettes
/// (vendored Kotlin `dynamiccolor/DynamicScheme.kt`).
class DynamicScheme {
  DynamicScheme({
    required this.sourceColorHct,
    required this.variant,
    required this.isDark,
    required this.contrastLevel,
    this.platform = SchemePlatform.phone,
    SpecVersion specVersion = SpecVersion.spec2025,
    required this.primaryPalette,
    required this.secondaryPalette,
    required this.tertiaryPalette,
    required this.neutralPalette,
    required this.neutralVariantPalette,
    required this.errorPalette,
  }) : specVersion = _resolveSpecVersion(specVersion, variant);

  /// The scheme Android's `SchemeExpressive`/`SchemeVibrant`/… constructors
  /// build: palettes from [ColorSpec2025] for [variant].
  factory DynamicScheme.fromVariant({
    required Hct sourceColorHct,
    required Variant variant,
    required bool isDark,
    double contrastLevel = 0.0,
    SchemePlatform platform = SchemePlatform.phone,
    SpecVersion specVersion = SpecVersion.spec2025,
  }) {
    final spec = ColorSpec2025.instance;
    TonalPalette p(TonalPalette Function(Variant, Hct, bool, SchemePlatform, double) f) =>
        f(variant, sourceColorHct, isDark, platform, contrastLevel);
    return DynamicScheme(
      sourceColorHct: sourceColorHct,
      variant: variant,
      isDark: isDark,
      contrastLevel: contrastLevel,
      platform: platform,
      specVersion: specVersion,
      primaryPalette: p(spec.getPrimaryPalette),
      secondaryPalette: p(spec.getSecondaryPalette),
      tertiaryPalette: p(spec.getTertiaryPalette),
      neutralPalette: p(spec.getNeutralPalette),
      neutralVariantPalette: p(spec.getNeutralVariantPalette),
      errorPalette: p(spec.getErrorPalette),
    );
  }

  /// [other] with a different mode and contrast level but the same palettes —
  /// how the fixed roles read their light-mode tone inside a dark scheme.
  factory DynamicScheme.from(DynamicScheme other, {required bool isDark, double? contrastLevel}) => DynamicScheme(
    sourceColorHct: other.sourceColorHct,
    variant: other.variant,
    isDark: isDark,
    contrastLevel: contrastLevel ?? other.contrastLevel,
    platform: other.platform,
    specVersion: other.specVersion,
    primaryPalette: other.primaryPalette,
    secondaryPalette: other.secondaryPalette,
    tertiaryPalette: other.tertiaryPalette,
    neutralPalette: other.neutralPalette,
    neutralVariantPalette: other.neutralVariantPalette,
    errorPalette: other.errorPalette,
  );

  final Hct sourceColorHct;
  final Variant variant;
  final bool isDark;

  /// -1 (least) … 0 (as specified) … 1 (most contrast).
  final double contrastLevel;
  final SchemePlatform platform;
  final SpecVersion specVersion;
  final TonalPalette primaryPalette;
  final TonalPalette secondaryPalette;
  final TonalPalette tertiaryPalette;
  final TonalPalette neutralPalette;
  final TonalPalette neutralVariantPalette;
  final TonalPalette errorPalette;

  /// Resolved tones by role name. Resolution is deterministic per scheme, and
  /// roles reference each other many times over (every accent reads the
  /// surface, every "on" role its container), so this keeps one scheme to a
  /// few hundred HCT solves.
  final Map<String, double> toneCache = {};

  int get sourceColorArgb => sourceColorHct.toInt();

  /// The rules every role resolves through. Only 2025 is ported.
  ColorSpec2025 get spec => ColorSpec2025.instance;

  int getArgb(DynamicColor color) => color.getArgb(this);

  Hct getHct(DynamicColor color) => color.getHct(this);

  /// The value for the first `[breakpoints[i], breakpoints[i+1])` band the
  /// source hue falls in, else the source hue.
  static double getPiecewiseValue(Hct sourceColorHct, List<double> hueBreakpoints, List<double> hues) {
    final size = hueBreakpoints.length - 1 < hues.length ? hueBreakpoints.length - 1 : hues.length;
    final sourceHue = sourceColorHct.hue;
    for (var i = 0; i < size; i++) {
      if (sourceHue >= hueBreakpoints[i] && sourceHue < hueBreakpoints[i + 1]) {
        return MathUtils.sanitizeDegreesDouble(hues[i]);
      }
    }
    return sourceHue;
  }

  /// The source hue rotated by the first band's rotation it falls in.
  ///
  /// Mirrors Kotlin exactly, including its quirk: with no matching band the
  /// piecewise value is the source hue itself, so the hue is doubled
  /// (mod 360). The Expressive bands cover 0–360, so it never happens there.
  static double getRotatedHue(Hct sourceColorHct, List<double> hueBreakpoints, List<double> rotations) {
    var rotation = getPiecewiseValue(sourceColorHct, hueBreakpoints, rotations);
    final size = hueBreakpoints.length - 1 < rotations.length ? hueBreakpoints.length - 1 : rotations.length;
    if (size <= 0) rotation = 0.0;
    return MathUtils.sanitizeDegreesDouble(sourceColorHct.hue + rotation);
  }

  static SpecVersion _resolveSpecVersion(SpecVersion specVersion, Variant variant) {
    final resolved = switch (variant) {
      Variant.cmf => specVersion,
      Variant.expressive || Variant.vibrant || Variant.tonalSpot || Variant.neutral =>
        specVersion == SpecVersion.spec2026 ? SpecVersion.spec2025 : specVersion,
      _ => SpecVersion.spec2021,
    };
    if (resolved != SpecVersion.spec2025) {
      throw UnsupportedError(
        '$variant at $specVersion resolves to $resolved; only SPEC_2025 is ported '
        '(use package:material_color_utilities for 2021).',
      );
    }
    return resolved;
  }

  @override
  String toString() =>
      'DynamicScheme(variant: ${variant.name}, ${isDark ? 'dark' : 'light'}, '
      'platform: ${platform.name}, contrast: $contrastLevel, seed: $sourceColorHct, spec: ${specVersion.name})';
}
