import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_color_utilities/material_color_utilities.dart' show Hct;
import 'package:yoin/theme/mcu2025/mcu2025.dart';

/// Beyond Yoin's own scheme: the other 2025 variants, the watch platform and
/// non-zero contrast levels, against values printed by the vendored Kotlin
/// MCU. Yoin only ships Expressive/phone/0.0; this keeps the rest of the port
/// honest.
final Map<String, dynamic> _golden =
    jsonDecode(File('test/theme/fixtures/spec2025_variants_golden.json').readAsStringSync()) as Map<String, dynamic>;

const _spec = ColorSpec2025.instance;

final Map<String, DynamicColor Function()> _roles = {
  'surface': () => _spec.surface,
  'surfaceDim': () => _spec.surfaceDim,
  'surfaceBright': () => _spec.surfaceBright,
  'surfaceContainerLowest': () => _spec.surfaceContainerLowest,
  'surfaceContainerLow': () => _spec.surfaceContainerLow,
  'surfaceContainer': () => _spec.surfaceContainer,
  'surfaceContainerHigh': () => _spec.surfaceContainerHigh,
  'surfaceContainerHighest': () => _spec.surfaceContainerHighest,
  'onSurface': () => _spec.onSurface,
  'onSurfaceVariant': () => _spec.onSurfaceVariant,
  'inverseSurface': () => _spec.inverseSurface,
  'inverseOnSurface': () => _spec.inverseOnSurface,
  'outline': () => _spec.outline,
  'outlineVariant': () => _spec.outlineVariant,
  'onBackground': () => _spec.onBackground,
  'primary': () => _spec.primary,
  'primaryDim': () => _spec.primaryDim,
  'onPrimary': () => _spec.onPrimary,
  'primaryContainer': () => _spec.primaryContainer,
  'onPrimaryContainer': () => _spec.onPrimaryContainer,
  'inversePrimary': () => _spec.inversePrimary,
  'secondary': () => _spec.secondary,
  'secondaryDim': () => _spec.secondaryDim,
  'onSecondary': () => _spec.onSecondary,
  'secondaryContainer': () => _spec.secondaryContainer,
  'onSecondaryContainer': () => _spec.onSecondaryContainer,
  'tertiary': () => _spec.tertiary,
  'tertiaryDim': () => _spec.tertiaryDim,
  'onTertiary': () => _spec.onTertiary,
  'tertiaryContainer': () => _spec.tertiaryContainer,
  'onTertiaryContainer': () => _spec.onTertiaryContainer,
  'error': () => _spec.error,
  'errorDim': () => _spec.errorDim,
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

const _variants = {
  'NEUTRAL': Variant.neutral,
  'TONAL_SPOT': Variant.tonalSpot,
  'VIBRANT': Variant.vibrant,
  'EXPRESSIVE': Variant.expressive,
};

String _hex(int argb) => argb.toRadixString(16).toUpperCase().padLeft(8, '0');

void main() {
  final roleNames = (_golden['roles'] as List).cast<String>();
  final schemes = (_golden['schemes'] as List).cast<Map<String, dynamic>>();

  test('should_coverEveryGoldenRole_when_portIsComplete', () {
    expect(roleNames.toSet(), _roles.keys.toSet());
    expect(schemes, hasLength(3 * 4 * 2 * 2 * 3));
  });

  for (final entry in schemes) {
    final seed = int.parse(entry['seed'] as String, radix: 16);
    final variant = _variants[entry['variant']]!;
    final platform = entry['platform'] == 'WATCH' ? SchemePlatform.watch : SchemePlatform.phone;
    final isDark = entry['isDark'] as bool;
    final contrastLevel = (entry['contrastLevel'] as num).toDouble();
    final expected = (entry['argb'] as List).cast<String>();
    final label = '${entry['seed']}_${entry['variant']}_${entry['platform']}_${isDark ? 'dark' : 'light'}_$contrastLevel';

    test('should_matchKotlinForEveryRole_when_$label', () {
      final scheme = DynamicScheme.fromVariant(
        sourceColorHct: Hct.fromInt(seed),
        variant: variant,
        isDark: isDark,
        contrastLevel: contrastLevel,
        platform: platform,
      );
      final mismatches = <String>[];
      for (var i = 0; i < roleNames.length; i++) {
        final actual = _hex(_roles[roleNames[i]]!().getArgb(scheme));
        if (actual != expected[i]) mismatches.add('${roleNames[i]}: dart $actual, kotlin ${expected[i]}');
      }
      expect(mismatches, isEmpty);
    });
  }

  test('should_throwUnsupported_when_variantHasNo2025Rules', () {
    expect(
      () => DynamicScheme.fromVariant(sourceColorHct: Hct.fromInt(0xFF7B3FE4), variant: Variant.content, isDark: false),
      throwsUnsupportedError,
    );
  });

  test('should_resolveSpec2026To2025_when_variantIsExpressive', () {
    final scheme = DynamicScheme.fromVariant(
      sourceColorHct: Hct.fromInt(0xFF7B3FE4),
      variant: Variant.expressive,
      isDark: false,
      specVersion: SpecVersion.spec2026,
    );
    expect(scheme.specVersion, SpecVersion.spec2025);
  });
}
