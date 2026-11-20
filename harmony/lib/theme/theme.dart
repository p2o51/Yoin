import 'package:flutter/material.dart';

import 'color_scheme.dart';
import 'tokens.dart';

/// The icon's violet arm (DefaultColorScheme.kt YoinSeedArgb). HarmonyOS has
/// no public wallpaper-colour API, so Yoin's own seed is the default.
const Color yoinSeed = Color(0xFF7B3FE4);

/// Yoin's theme for one brightness: the SPEC_2025 Expressive scheme from
/// [seed] (identical to Android's), Google Sans Flex, M3.
ThemeData yoinTheme(Brightness brightness, {Color seed = yoinSeed}) {
  final scheme = yoinExpressiveScheme(seed, brightness);
  final textTheme = yoinTextTheme().apply(
    bodyColor: scheme.onSurface,
    displayColor: scheme.onSurface,
  );
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: scheme.surface,
    fontFamily: _family,
    textTheme: textTheme,
    splashFactory: InkSparkle.splashFactory,
  );
}

const String _family = 'GoogleSansFlex';
const List<String> _cjkFallback = ['HarmonyOS Sans SC', 'HarmonyOS Sans', 'sans-serif'];

/// Google Sans Flex at one weight, with opsz following the size (Type.kt).
TextStyle gsf(
  double size, {
  int weight = 400,
  double? height,
  double letterSpacing = TypeTokens.letterSpacing,
  double round = 0,
}) {
  final opsz = size.clamp(TypeTokens.opticalSizeMin, TypeTokens.opticalSizeMax);
  return TextStyle(
    fontFamily: _family,
    fontFamilyFallback: _cjkFallback,
    fontSize: size,
    height: height,
    fontWeight: FontWeight.values.firstWhere(
      (w) => w.value == weight,
      orElse: () => FontWeight.w400,
    ),
    letterSpacing: letterSpacing,
    fontVariations: [
      FontVariation.weight(weight.toDouble()),
      FontVariation('opsz', opsz.toDouble()),
      if (round > 0) FontVariation('ROND', round),
    ],
  );
}

TextStyle _style(TypeStyleToken t) => gsf(
      t.size,
      weight: t.weight,
      height: t.lineHeight / t.size,
      letterSpacing: t.letterSpacing,
    );

TextTheme yoinTextTheme() => TextTheme(
      displayLarge: _style(TypeTokens.displayLarge),
      displayMedium: _style(TypeTokens.displayMedium),
      displaySmall: _style(TypeTokens.displaySmall),
      headlineLarge: _style(TypeTokens.headlineLarge),
      headlineMedium: _style(TypeTokens.headlineMedium),
      headlineSmall: _style(TypeTokens.headlineSmall),
      titleLarge: _style(TypeTokens.titleLarge),
      titleMedium: _style(TypeTokens.titleMedium),
      titleSmall: _style(TypeTokens.titleSmall),
      bodyLarge: _style(TypeTokens.bodyLarge),
      bodyMedium: _style(TypeTokens.bodyMedium),
      bodySmall: _style(TypeTokens.bodySmall),
      labelLarge: _style(TypeTokens.labelLarge),
      labelMedium: _style(TypeTokens.labelMedium),
      labelSmall: _style(TypeTokens.labelSmall),
    );
