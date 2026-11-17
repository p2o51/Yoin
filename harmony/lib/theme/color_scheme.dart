import 'package:flutter/material.dart';
import 'package:material_color_utilities/material_color_utilities.dart' show Hct;

import 'mcu2025/mcu2025.dart';

/// Builds Yoin's Expressive [ColorScheme] from one seed, the same way Android
/// does: MCU `SchemeExpressive(seed, isDark, contrastLevel = 0,
/// SPEC_2025, PHONE)` (ExpressiveColorSchemeFactory.kt), through the Dart
/// port in `mcu2025/`. Every role equals the Kotlin value bit for bit for the
/// golden seeds in `test/theme/fixtures/spec2025_expressive_golden.json`.
///
/// Flutter's deprecated `background`, `onBackground` and `surfaceVariant`
/// are still set: SPEC_2025 remaps them to surface, onSurface and
/// surfaceContainerHighest, but Flutter's own default for `surfaceVariant`
/// is not surfaceContainerHighest, and older widgets still read it.
ColorScheme yoinExpressiveScheme(Color seed, Brightness brightness) {
  final scheme = DynamicScheme.fromVariant(
    sourceColorHct: Hct.fromInt(seed.toARGB32()),
    variant: Variant.expressive,
    isDark: brightness == Brightness.dark,
    contrastLevel: 0.0,
    platform: SchemePlatform.phone,
    specVersion: SpecVersion.spec2025,
  );
  const spec = ColorSpec2025.instance;
  Color c(DynamicColor role) => Color(role.getArgb(scheme));
  return ColorScheme(
    brightness: brightness,
    primary: c(spec.primary),
    onPrimary: c(spec.onPrimary),
    primaryContainer: c(spec.primaryContainer),
    onPrimaryContainer: c(spec.onPrimaryContainer),
    primaryFixed: c(spec.primaryFixed),
    primaryFixedDim: c(spec.primaryFixedDim),
    onPrimaryFixed: c(spec.onPrimaryFixed),
    onPrimaryFixedVariant: c(spec.onPrimaryFixedVariant),
    secondary: c(spec.secondary),
    onSecondary: c(spec.onSecondary),
    secondaryContainer: c(spec.secondaryContainer),
    onSecondaryContainer: c(spec.onSecondaryContainer),
    secondaryFixed: c(spec.secondaryFixed),
    secondaryFixedDim: c(spec.secondaryFixedDim),
    onSecondaryFixed: c(spec.onSecondaryFixed),
    onSecondaryFixedVariant: c(spec.onSecondaryFixedVariant),
    tertiary: c(spec.tertiary),
    onTertiary: c(spec.onTertiary),
    tertiaryContainer: c(spec.tertiaryContainer),
    onTertiaryContainer: c(spec.onTertiaryContainer),
    tertiaryFixed: c(spec.tertiaryFixed),
    tertiaryFixedDim: c(spec.tertiaryFixedDim),
    onTertiaryFixed: c(spec.onTertiaryFixed),
    onTertiaryFixedVariant: c(spec.onTertiaryFixedVariant),
    error: c(spec.error),
    onError: c(spec.onError),
    errorContainer: c(spec.errorContainer),
    onErrorContainer: c(spec.onErrorContainer),
    surface: c(spec.surface),
    onSurface: c(spec.onSurface),
    surfaceDim: c(spec.surfaceDim),
    surfaceBright: c(spec.surfaceBright),
    surfaceContainerLowest: c(spec.surfaceContainerLowest),
    surfaceContainerLow: c(spec.surfaceContainerLow),
    surfaceContainer: c(spec.surfaceContainer),
    surfaceContainerHigh: c(spec.surfaceContainerHigh),
    surfaceContainerHighest: c(spec.surfaceContainerHighest),
    onSurfaceVariant: c(spec.onSurfaceVariant),
    outline: c(spec.outline),
    outlineVariant: c(spec.outlineVariant),
    shadow: c(spec.shadow),
    scrim: c(spec.scrim),
    inverseSurface: c(spec.inverseSurface),
    onInverseSurface: c(spec.inverseOnSurface),
    inversePrimary: c(spec.inversePrimary),
    surfaceTint: c(spec.surfaceTint),
    // ignore: deprecated_member_use
    background: c(spec.background),
    // ignore: deprecated_member_use
    onBackground: c(spec.onBackground),
    // ignore: deprecated_member_use
    surfaceVariant: c(spec.surfaceVariant),
  );
}
