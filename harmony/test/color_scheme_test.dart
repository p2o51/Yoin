import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_color_utilities/material_color_utilities.dart';
import 'package:yoin/theme/color_scheme.dart';
import 'package:yoin/theme/theme.dart';

double _hueGap(Color a, Color b) {
  final d = (Hct.fromInt(a.toARGB32()).hue - Hct.fromInt(b.toARGB32()).hue).abs() % 360;
  return d > 180 ? 360 - d : d;
}

void main() {
  for (final brightness in Brightness.values) {
    test('should_keepPrimaryOnSeedHue_when_${brightness.name}', () {
      final scheme = yoinExpressiveScheme(yoinSeed, brightness);
      // SPEC_2025 Expressive puts primary on the seed hue (2021 rotated Yoin violet to mint).
      expect(_hueGap(scheme.primary, yoinSeed), lessThan(10));
      expect(_hueGap(scheme.primaryContainer, yoinSeed), lessThan(10));
    });
  }
}
