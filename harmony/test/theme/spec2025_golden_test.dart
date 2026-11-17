import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_color_utilities/material_color_utilities.dart' show Hct;
import 'package:yoin/theme/color_scheme.dart';
import 'package:yoin/theme/mcu2025/mcu2025.dart';

/// Golden values printed by the vendored Kotlin MCU (Android's colour code):
/// `SchemeExpressive(seed, isDark, 0.0, SPEC_2025, PHONE)` for every role.
final Map<String, dynamic> _golden =
    jsonDecode(File('test/theme/fixtures/spec2025_expressive_golden.json').readAsStringSync()) as Map<String, dynamic>;

const _spec = ColorSpec2025.instance;

/// Golden key → the ported role.
final Map<String, DynamicColor Function()> _roles = {
  'primaryPaletteKeyColor': () => _spec.primaryPaletteKeyColor,
  'secondaryPaletteKeyColor': () => _spec.secondaryPaletteKeyColor,
  'tertiaryPaletteKeyColor': () => _spec.tertiaryPaletteKeyColor,
  'neutralPaletteKeyColor': () => _spec.neutralPaletteKeyColor,
  'neutralVariantPaletteKeyColor': () => _spec.neutralVariantPaletteKeyColor,
  'background': () => _spec.background,
  'onBackground': () => _spec.onBackground,
  'surface': () => _spec.surface,
  'surfaceDim': () => _spec.surfaceDim,
  'surfaceBright': () => _spec.surfaceBright,
  'surfaceContainerLowest': () => _spec.surfaceContainerLowest,
  'surfaceContainerLow': () => _spec.surfaceContainerLow,
  'surfaceContainer': () => _spec.surfaceContainer,
  'surfaceContainerHigh': () => _spec.surfaceContainerHigh,
  'surfaceContainerHighest': () => _spec.surfaceContainerHighest,
  'onSurface': () => _spec.onSurface,
  'surfaceVariant': () => _spec.surfaceVariant,
  'onSurfaceVariant': () => _spec.onSurfaceVariant,
  'inverseSurface': () => _spec.inverseSurface,
  'inverseOnSurface': () => _spec.inverseOnSurface,
  'outline': () => _spec.outline,
  'outlineVariant': () => _spec.outlineVariant,
  'shadow': () => _spec.shadow,
  'scrim': () => _spec.scrim,
  'surfaceTint': () => _spec.surfaceTint,
  'primary': () => _spec.primary,
  'onPrimary': () => _spec.onPrimary,
  'primaryContainer': () => _spec.primaryContainer,
  'onPrimaryContainer': () => _spec.onPrimaryContainer,
  'inversePrimary': () => _spec.inversePrimary,
  'secondary': () => _spec.secondary,
  'onSecondary': () => _spec.onSecondary,
  'secondaryContainer': () => _spec.secondaryContainer,
  'onSecondaryContainer': () => _spec.onSecondaryContainer,
  'tertiary': () => _spec.tertiary,
  'onTertiary': () => _spec.onTertiary,
  'tertiaryContainer': () => _spec.tertiaryContainer,
  'onTertiaryContainer': () => _spec.onTertiaryContainer,
  'error': () => _spec.error,
  'onError': () => _spec.onError,
  'errorContainer': () => _spec.errorContainer,
  'onErrorContainer': () => _spec.onErrorContainer,
  'primaryFixed': () => _spec.primaryFixed,
  'primaryFixedDim': () => _spec.primaryFixedDim,
  'onPrimaryFixed': () => _spec.onPrimaryFixed,
  'onPrimaryFixedVariant': () => _spec.onPrimaryFixedVariant,
  'secondaryFixed': () => _spec.secondaryFixed,
  'secondaryFixedDim': () => _spec.secondaryFixedDim,
  'onSecondaryFixed': () => _spec.onSecondaryFixed,
  'onSecondaryFixedVariant': () => _spec.onSecondaryFixedVariant,
  'tertiaryFixed': () => _spec.tertiaryFixed,
  'tertiaryFixedDim': () => _spec.tertiaryFixedDim,
  'onTertiaryFixed': () => _spec.onTertiaryFixed,
  'onTertiaryFixedVariant': () => _spec.onTertiaryFixedVariant,
};

/// Golden key → the [ColorScheme] field `yoinExpressiveScheme` maps it to.
final Map<String, Color Function(ColorScheme)> _schemeFields = {
  'primary': (c) => c.primary,
  'onPrimary': (c) => c.onPrimary,
  'primaryContainer': (c) => c.primaryContainer,
  'onPrimaryContainer': (c) => c.onPrimaryContainer,
  'primaryFixed': (c) => c.primaryFixed,
  'primaryFixedDim': (c) => c.primaryFixedDim,
  'onPrimaryFixed': (c) => c.onPrimaryFixed,
  'onPrimaryFixedVariant': (c) => c.onPrimaryFixedVariant,
  'secondary': (c) => c.secondary,
  'onSecondary': (c) => c.onSecondary,
  'secondaryContainer': (c) => c.secondaryContainer,
  'onSecondaryContainer': (c) => c.onSecondaryContainer,
  'secondaryFixed': (c) => c.secondaryFixed,
  'secondaryFixedDim': (c) => c.secondaryFixedDim,
  'onSecondaryFixed': (c) => c.onSecondaryFixed,
  'onSecondaryFixedVariant': (c) => c.onSecondaryFixedVariant,
  'tertiary': (c) => c.tertiary,
  'onTertiary': (c) => c.onTertiary,
  'tertiaryContainer': (c) => c.tertiaryContainer,
  'onTertiaryContainer': (c) => c.onTertiaryContainer,
  'tertiaryFixed': (c) => c.tertiaryFixed,
  'tertiaryFixedDim': (c) => c.tertiaryFixedDim,
  'onTertiaryFixed': (c) => c.onTertiaryFixed,
  'onTertiaryFixedVariant': (c) => c.onTertiaryFixedVariant,
  'error': (c) => c.error,
  'onError': (c) => c.onError,
  'errorContainer': (c) => c.errorContainer,
  'onErrorContainer': (c) => c.onErrorContainer,
  'surface': (c) => c.surface,
  'onSurface': (c) => c.onSurface,
  'surfaceDim': (c) => c.surfaceDim,
  'surfaceBright': (c) => c.surfaceBright,
  'surfaceContainerLowest': (c) => c.surfaceContainerLowest,
  'surfaceContainerLow': (c) => c.surfaceContainerLow,
  'surfaceContainer': (c) => c.surfaceContainer,
  'surfaceContainerHigh': (c) => c.surfaceContainerHigh,
  'surfaceContainerHighest': (c) => c.surfaceContainerHighest,
  'onSurfaceVariant': (c) => c.onSurfaceVariant,
  'outline': (c) => c.outline,
  'outlineVariant': (c) => c.outlineVariant,
  'shadow': (c) => c.shadow,
  'scrim': (c) => c.scrim,
  'inverseSurface': (c) => c.inverseSurface,
  'inverseOnSurface': (c) => c.onInverseSurface,
  'inversePrimary': (c) => c.inversePrimary,
  'surfaceTint': (c) => c.surfaceTint,
  // Deprecated in Flutter but still set (2025 remaps them).
  // ignore: deprecated_member_use
  'background': (c) => c.background,
  // ignore: deprecated_member_use
  'onBackground': (c) => c.onBackground,
  // ignore: deprecated_member_use
  'surfaceVariant': (c) => c.surfaceVariant,
};

String _hex(int argb) => argb.toRadixString(16).toUpperCase().padLeft(8, '0');

void main() {
  final schemes = (_golden['schemes'] as List).cast<Map<String, dynamic>>();

  test('should_coverEveryGoldenRole_when_portIsComplete', () {
    for (final entry in schemes) {
      expect((entry['roles'] as Map).keys.toSet(), _roles.keys.toSet());
    }
    expect(schemes, hasLength(14));
  });

  for (final entry in schemes) {
    final seed = int.parse(entry['seed'] as String, radix: 16);
    final isDark = entry['isDark'] as bool;
    final golden = (entry['roles'] as Map).cast<String, String>();
    final label = '${_hex(seed)}_${isDark ? 'dark' : 'light'}';

    test('should_matchKotlinSpec2025ForEveryRole_when_seed_$label', () {
      final scheme = DynamicScheme.fromVariant(
        sourceColorHct: Hct.fromInt(seed),
        variant: Variant.expressive,
        isDark: isDark,
      );
      final mismatches = <String>[];
      for (final MapEntry(key: role, value: expected) in golden.entries) {
        final actual = _hex(_roles[role]!().getArgb(scheme));
        if (actual != expected) mismatches.add('$role: dart $actual, kotlin $expected');
      }
      expect(mismatches, isEmpty);
    });

    test('should_mapEveryColorSchemeFieldToKotlinValue_when_seed_$label', () {
      final colorScheme = yoinExpressiveScheme(Color(seed), isDark ? Brightness.dark : Brightness.light);
      final mismatches = <String>[];
      for (final MapEntry(key: role, value: field) in _schemeFields.entries) {
        final actual = _hex(field(colorScheme).toARGB32());
        if (actual != golden[role]) mismatches.add('$role: dart $actual, kotlin ${golden[role]}');
      }
      expect(mismatches, isEmpty);
    });
  }
}
