// Roles name themselves inside closures (a ToneDeltaPair's roleA is the role
// being built), as in Kotlin; the getter is only re-entered when the closure
// runs, so the "recursion" is lazy and terminates.
// ignore_for_file: recursive_getters

import 'dart:math' as math;

import 'package:material_color_utilities/material_color_utilities.dart' show Hct;

import 'contrast.dart';
import 'dynamic_color.dart';
import 'dynamic_scheme.dart';
import 'tonal_palette.dart';

/// Material colour spec 2025: every role's rules, the tone solver, and the
/// palettes per variant. A line-by-line port of the vendored Kotlin
/// `dynamiccolor/ColorSpec2025.kt` that Android's `ExpressiveColorSchemeFactory`
/// runs (`SchemeExpressive`, `SPEC_2025`, `PHONE`).
///
/// Kotlin layers 2025 over 2021 with `extendSpecVersion`; since every scheme
/// here is 2025, each getter returns the 2025 colour directly. Getters build a
/// fresh [DynamicColor] on each call, as the Kotlin getters do, so roles can
/// reference each other (primary ⇄ primaryContainer) without init cycles.
final class ColorSpec2025 {
  const ColorSpec2025._();

  static const ColorSpec2025 instance = ColorSpec2025._();

  // ---------------------------------------------------------------------------
  // Shared rules
  // ---------------------------------------------------------------------------

  static bool _phone(DynamicScheme s) => s.platform == SchemePlatform.phone;

  /// The surface accents and text are measured against: surfaceBright in dark
  /// mode, surfaceDim in light mode (phone); surfaceContainerHigh (watch).
  DynamicColor? _accentBackground(DynamicScheme s) =>
      _phone(s) ? (s.isDark ? surfaceBright : surfaceDim) : surfaceContainerHigh;

  /// Same as [_accentBackground] on phone; containers have none on watch.
  DynamicColor? _containerBackground(DynamicScheme s) =>
      _phone(s) ? (s.isDark ? surfaceBright : surfaceDim) : null;

  /// Containers only get a contrast floor when contrast is raised.
  ContrastCurve? _containerContrast(DynamicScheme s) =>
      _phone(s) && s.contrastLevel > 0 ? _curve(1.5) : null;

  /// Chroma boost shared by onSurface, onSurfaceVariant, outline and
  /// outlineVariant.
  static double _onSurfaceChroma(DynamicScheme s) {
    if (!_phone(s)) return 1.0;
    return switch (s.variant) {
      Variant.neutral => 2.2,
      Variant.tonalSpot => 1.7,
      Variant.expressive => HueBands.isYellow(s.neutralPalette.hue) ? (s.isDark ? 3.0 : 2.3) : 1.6,
      _ => 1.0,
    };
  }

  /// Light-mode surface tone: yellow goes lighter, Vibrant darker.
  static double _lightSurfaceTone(DynamicScheme s, {required double yellow, required double vibrant, required double other}) {
    if (HueBands.isYellow(s.neutralPalette.hue)) return yellow;
    if (s.variant == Variant.vibrant) return vibrant;
    return other;
  }

  // ---------------------------------------------------------------------------
  // Palette key colours
  // ---------------------------------------------------------------------------

  DynamicColor get primaryPaletteKeyColor => DynamicColor(
    name: 'primary_palette_key_color',
    palette: (s) => s.primaryPalette,
    tone: (s) => s.primaryPalette.keyColor.tone,
  );

  DynamicColor get secondaryPaletteKeyColor => DynamicColor(
    name: 'secondary_palette_key_color',
    palette: (s) => s.secondaryPalette,
    tone: (s) => s.secondaryPalette.keyColor.tone,
  );

  DynamicColor get tertiaryPaletteKeyColor => DynamicColor(
    name: 'tertiary_palette_key_color',
    palette: (s) => s.tertiaryPalette,
    tone: (s) => s.tertiaryPalette.keyColor.tone,
  );

  DynamicColor get neutralPaletteKeyColor => DynamicColor(
    name: 'neutral_palette_key_color',
    palette: (s) => s.neutralPalette,
    tone: (s) => s.neutralPalette.keyColor.tone,
  );

  DynamicColor get neutralVariantPaletteKeyColor => DynamicColor(
    name: 'neutral_variant_palette_key_color',
    palette: (s) => s.neutralVariantPalette,
    tone: (s) => s.neutralVariantPalette.keyColor.tone,
  );

  DynamicColor get errorPaletteKeyColor => DynamicColor(
    name: 'error_palette_key_color',
    palette: (s) => s.errorPalette,
    tone: (s) => s.errorPalette.keyColor.tone,
  );

  // ---------------------------------------------------------------------------
  // Surfaces
  // ---------------------------------------------------------------------------

  /// Remapped to surface in 2025.
  DynamicColor get background => surface.copyWith(name: 'background');

  /// Remapped to onSurface in 2025 (white on watch).
  DynamicColor get onBackground {
    final base = onSurface;
    return base.copyWith(
      name: 'on_background',
      tone: (s) => s.platform == SchemePlatform.watch ? 100.0 : base.getTone(s),
    );
  }

  DynamicColor get surface => DynamicColor(
    name: 'surface',
    palette: (s) => s.neutralPalette,
    tone: (s) {
      if (!_phone(s)) return 0.0;
      if (s.isDark) return 4.0;
      return _lightSurfaceTone(s, yellow: 99, vibrant: 97, other: 98);
    },
    isBackground: true,
  );

  DynamicColor get surfaceDim => DynamicColor(
    name: 'surface_dim',
    palette: (s) => s.neutralPalette,
    tone: (s) => s.isDark ? 4.0 : _lightSurfaceTone(s, yellow: 90, vibrant: 85, other: 87),
    isBackground: true,
    chromaMultiplier: (s) {
      if (s.isDark) return 1.0;
      return switch (s.variant) {
        Variant.neutral => 2.5,
        Variant.tonalSpot => 1.7,
        Variant.expressive => HueBands.isYellow(s.neutralPalette.hue) ? 2.7 : 1.75,
        Variant.vibrant => 1.36,
        _ => 1.0,
      };
    },
  );

  DynamicColor get surfaceBright => DynamicColor(
    name: 'surface_bright',
    palette: (s) => s.neutralPalette,
    tone: (s) => s.isDark ? 18.0 : _lightSurfaceTone(s, yellow: 99, vibrant: 97, other: 98),
    isBackground: true,
    chromaMultiplier: (s) {
      if (!s.isDark) return 1.0;
      return switch (s.variant) {
        Variant.neutral => 2.5,
        Variant.tonalSpot => 1.7,
        Variant.expressive => HueBands.isYellow(s.neutralPalette.hue) ? 2.7 : 1.75,
        Variant.vibrant => 1.36,
        _ => 1.0,
      };
    },
  );

  DynamicColor get surfaceContainerLowest => DynamicColor(
    name: 'surface_container_lowest',
    palette: (s) => s.neutralPalette,
    tone: (s) => s.isDark ? 0.0 : 100.0,
    isBackground: true,
  );

  DynamicColor get surfaceContainerLow => DynamicColor(
    name: 'surface_container_low',
    palette: (s) => s.neutralPalette,
    tone: (s) {
      if (!_phone(s)) return 15.0;
      return s.isDark ? 6.0 : _lightSurfaceTone(s, yellow: 98, vibrant: 95, other: 96);
    },
    isBackground: true,
    chromaMultiplier: (s) {
      if (!_phone(s)) return 1.0;
      return switch (s.variant) {
        Variant.neutral => 1.3,
        Variant.tonalSpot => 1.25,
        Variant.expressive => HueBands.isYellow(s.neutralPalette.hue) ? 1.3 : 1.15,
        Variant.vibrant => 1.08,
        _ => 1.0,
      };
    },
  );

  DynamicColor get surfaceContainer => DynamicColor(
    name: 'surface_container',
    palette: (s) => s.neutralPalette,
    tone: (s) {
      if (!_phone(s)) return 20.0;
      return s.isDark ? 9.0 : _lightSurfaceTone(s, yellow: 96, vibrant: 92, other: 94);
    },
    isBackground: true,
    chromaMultiplier: (s) {
      if (!_phone(s)) return 1.0;
      return switch (s.variant) {
        Variant.neutral => 1.6,
        Variant.tonalSpot => 1.4,
        Variant.expressive => HueBands.isYellow(s.neutralPalette.hue) ? 1.6 : 1.3,
        Variant.vibrant => 1.15,
        _ => 1.0,
      };
    },
  );

  DynamicColor get surfaceContainerHigh => DynamicColor(
    name: 'surface_container_high',
    palette: (s) => s.neutralPalette,
    tone: (s) {
      if (!_phone(s)) return 25.0;
      return s.isDark ? 12.0 : _lightSurfaceTone(s, yellow: 94, vibrant: 90, other: 92);
    },
    isBackground: true,
    chromaMultiplier: (s) {
      if (!_phone(s)) return 1.0;
      return switch (s.variant) {
        Variant.neutral => 1.9,
        Variant.tonalSpot => 1.5,
        Variant.expressive => HueBands.isYellow(s.neutralPalette.hue) ? 1.95 : 1.45,
        Variant.vibrant => 1.22,
        _ => 1.0,
      };
    },
  );

  DynamicColor get surfaceContainerHighest => DynamicColor(
    name: 'surface_container_highest',
    palette: (s) => s.neutralPalette,
    tone: (s) => s.isDark ? 15.0 : _lightSurfaceTone(s, yellow: 92, vibrant: 88, other: 90),
    isBackground: true,
    chromaMultiplier: (s) => switch (s.variant) {
      Variant.neutral => 2.2,
      Variant.tonalSpot => 1.7,
      Variant.expressive => HueBands.isYellow(s.neutralPalette.hue) ? 2.3 : 1.6,
      Variant.vibrant => 1.29,
      _ => 1.0,
    },
  );

  DynamicColor get onSurface => DynamicColor(
    name: 'on_surface',
    palette: (s) => s.neutralPalette,
    tone: (s) => s.variant == Variant.vibrant
        ? _tMaxC(s.neutralPalette, 0, 100, 1.1)
        : DynamicColor.initialToneFromBackground(_accentBackground)(s),
    chromaMultiplier: _onSurfaceChroma,
    background: _accentBackground,
    contrastCurve: (s) => s.isDark && _phone(s) ? _curve(11) : _curve(9),
  );

  /// Remapped to surfaceContainerHighest in 2025.
  DynamicColor get surfaceVariant => surfaceContainerHighest.copyWith(name: 'surface_variant');

  DynamicColor get onSurfaceVariant => DynamicColor(
    name: 'on_surface_variant',
    palette: (s) => s.neutralPalette,
    chromaMultiplier: _onSurfaceChroma,
    background: _accentBackground,
    contrastCurve: (s) => _phone(s) ? (s.isDark ? _curve(6) : _curve(4.5)) : _curve(7),
  );

  DynamicColor get inverseSurface => DynamicColor(
    name: 'inverse_surface',
    palette: (s) => s.neutralPalette,
    tone: (s) => s.isDark ? 98.0 : 4.0,
    isBackground: true,
  );

  DynamicColor get inverseOnSurface => DynamicColor(
    name: 'inverse_on_surface',
    palette: (s) => s.neutralPalette,
    background: (_) => inverseSurface,
    contrastCurve: (_) => _curve(7),
  );

  DynamicColor get outline => DynamicColor(
    name: 'outline',
    palette: (s) => s.neutralPalette,
    chromaMultiplier: _onSurfaceChroma,
    background: _accentBackground,
    contrastCurve: (s) => _phone(s) ? _curve(3) : _curve(4.5),
  );

  DynamicColor get outlineVariant => DynamicColor(
    name: 'outline_variant',
    palette: (s) => s.neutralPalette,
    chromaMultiplier: _onSurfaceChroma,
    background: _accentBackground,
    contrastCurve: (s) => _phone(s) ? _curve(1.5) : _curve(3),
  );

  /// Unchanged from 2021: neutral T0.
  DynamicColor get shadow => DynamicColor(name: 'shadow', palette: (s) => s.neutralPalette, tone: (_) => 0.0);

  /// Unchanged from 2021: neutral T0.
  DynamicColor get scrim => DynamicColor(name: 'scrim', palette: (s) => s.neutralPalette, tone: (_) => 0.0);

  /// Remapped to primary in 2025.
  DynamicColor get surfaceTint => primary.copyWith(name: 'surface_tint');

  // ---------------------------------------------------------------------------
  // Primaries
  // ---------------------------------------------------------------------------

  DynamicColor get primary => DynamicColor(
    name: 'primary',
    palette: (s) => s.primaryPalette,
    tone: (s) {
      final p = s.primaryPalette;
      switch (s.variant) {
        case Variant.neutral:
          return _phone(s) ? (s.isDark ? 80.0 : 40.0) : 90.0;
        case Variant.tonalSpot:
          if (_phone(s)) return s.isDark ? 80.0 : _tMaxC(p);
          return _tMaxC(p, 0, 90);
        case Variant.expressive:
          if (!_phone(s)) return _tMaxC(p);
          return _tMaxC(p, 0, HueBands.isYellow(p.hue) ? 25 : (HueBands.isCyan(p.hue) ? 88 : 98));
        default: // vibrant
          if (!_phone(s)) return _tMaxC(p);
          return _tMaxC(p, 0, HueBands.isCyan(p.hue) ? 88 : 98);
      }
    },
    isBackground: true,
    background: _accentBackground,
    contrastCurve: (s) => _phone(s) ? _curve(4.5) : _curve(7),
    toneDeltaPair: (s) => _phone(s)
        ? ToneDeltaPair(
            roleA: primaryContainer,
            roleB: primary,
            delta: 5,
            polarity: TonePolarity.relativeLighter,
            constraint: DeltaConstraint.farther,
          )
        : null,
  );

  /// Watch-only accent the watch containers pair with.
  DynamicColor get primaryDim => DynamicColor(
    name: 'primary_dim',
    palette: (s) => s.primaryPalette,
    tone: (s) => switch (s.variant) {
      Variant.neutral => 85.0,
      Variant.tonalSpot => _tMaxC(s.primaryPalette, 0, 90),
      _ => _tMaxC(s.primaryPalette),
    },
    isBackground: true,
    background: (_) => surfaceContainerHigh,
    contrastCurve: (_) => _curve(4.5),
    toneDeltaPair: (_) => ToneDeltaPair(
      roleA: primaryDim,
      roleB: primary,
      delta: 5,
      polarity: TonePolarity.darker,
      constraint: DeltaConstraint.farther,
    ),
  );

  DynamicColor get onPrimary => DynamicColor(
    name: 'on_primary',
    palette: (s) => s.primaryPalette,
    background: (s) => _phone(s) ? primary : primaryDim,
    contrastCurve: (s) => _phone(s) ? _curve(6) : _curve(7),
  );

  DynamicColor get primaryContainer => DynamicColor(
    name: 'primary_container',
    palette: (s) => s.primaryPalette,
    tone: (s) {
      final p = s.primaryPalette;
      if (s.platform == SchemePlatform.watch) return 30.0;
      return switch (s.variant) {
        Variant.neutral => s.isDark ? 30.0 : 90.0,
        Variant.tonalSpot => s.isDark ? _tMinC(p, 35, 93) : _tMaxC(p, 0, 90),
        Variant.expressive => s.isDark ? _tMaxC(p, 30, 93) : _tMaxC(p, 78, HueBands.isCyan(p.hue) ? 88 : 90),
        _ => s.isDark ? _tMinC(p, 66, 93) : _tMaxC(p, 66, HueBands.isCyan(p.hue) ? 88 : 93),
      };
    },
    isBackground: true,
    background: _containerBackground,
    toneDeltaPair: (s) => s.platform == SchemePlatform.watch
        ? ToneDeltaPair(
            roleA: primaryContainer,
            roleB: primaryDim,
            delta: 10,
            polarity: TonePolarity.darker,
            constraint: DeltaConstraint.farther,
          )
        : null,
    contrastCurve: _containerContrast,
  );

  DynamicColor get onPrimaryContainer => DynamicColor(
    name: 'on_primary_container',
    palette: (s) => s.primaryPalette,
    background: (_) => primaryContainer,
    contrastCurve: (s) => _phone(s) ? _curve(6) : _curve(7),
  );

  DynamicColor get inversePrimary => DynamicColor(
    name: 'inverse_primary',
    palette: (s) => s.primaryPalette,
    tone: (s) => _tMaxC(s.primaryPalette),
    background: (_) => inverseSurface,
    contrastCurve: (s) => _phone(s) ? _curve(6) : _curve(7),
  );

  // ---------------------------------------------------------------------------
  // Secondaries
  // ---------------------------------------------------------------------------

  DynamicColor get secondary => DynamicColor(
    name: 'secondary',
    palette: (s) => s.secondaryPalette,
    tone: (s) {
      final p = s.secondaryPalette;
      if (s.platform == SchemePlatform.watch) {
        return s.variant == Variant.neutral ? 90.0 : _tMaxC(p, 0, 90);
      }
      return switch (s.variant) {
        Variant.neutral => s.isDark ? _tMinC(p, 0, 98) : _tMaxC(p),
        Variant.vibrant => _tMaxC(p, 0, s.isDark ? 90 : 98),
        _ => s.isDark ? 80.0 : _tMaxC(p), // expressive, tonal spot
      };
    },
    isBackground: true,
    background: _accentBackground,
    contrastCurve: (s) => _phone(s) ? _curve(4.5) : _curve(7),
    toneDeltaPair: (s) => _phone(s)
        ? ToneDeltaPair(
            roleA: secondaryContainer,
            roleB: secondary,
            delta: 5,
            polarity: TonePolarity.relativeLighter,
            constraint: DeltaConstraint.farther,
          )
        : null,
  );

  DynamicColor get secondaryDim => DynamicColor(
    name: 'secondary_dim',
    palette: (s) => s.secondaryPalette,
    tone: (s) => s.variant == Variant.neutral ? 85.0 : _tMaxC(s.secondaryPalette, 0, 90),
    isBackground: true,
    background: (_) => surfaceContainerHigh,
    contrastCurve: (_) => _curve(4.5),
    toneDeltaPair: (_) => ToneDeltaPair(
      roleA: secondaryDim,
      roleB: secondary,
      delta: 5,
      polarity: TonePolarity.darker,
      constraint: DeltaConstraint.farther,
    ),
  );

  DynamicColor get onSecondary => DynamicColor(
    name: 'on_secondary',
    palette: (s) => s.secondaryPalette,
    background: (s) => _phone(s) ? secondary : secondaryDim,
    contrastCurve: (s) => _phone(s) ? _curve(6) : _curve(7),
  );

  DynamicColor get secondaryContainer => DynamicColor(
    name: 'secondary_container',
    palette: (s) => s.secondaryPalette,
    tone: (s) {
      final p = s.secondaryPalette;
      if (s.platform == SchemePlatform.watch) return 30.0;
      return switch (s.variant) {
        Variant.vibrant => s.isDark ? _tMinC(p, 30, 40) : _tMaxC(p, 84, 90),
        Variant.expressive => s.isDark ? 15.0 : _tMaxC(p, 90, 95),
        _ => s.isDark ? 25.0 : 90.0,
      };
    },
    isBackground: true,
    background: _containerBackground,
    toneDeltaPair: (s) => s.platform == SchemePlatform.watch
        ? ToneDeltaPair(
            roleA: secondaryContainer,
            roleB: secondaryDim,
            delta: 10,
            polarity: TonePolarity.darker,
            constraint: DeltaConstraint.farther,
          )
        : null,
    contrastCurve: _containerContrast,
  );

  DynamicColor get onSecondaryContainer => DynamicColor(
    name: 'on_secondary_container',
    palette: (s) => s.secondaryPalette,
    background: (_) => secondaryContainer,
    contrastCurve: (s) => _phone(s) ? _curve(6) : _curve(7),
  );

  // ---------------------------------------------------------------------------
  // Tertiaries
  // ---------------------------------------------------------------------------

  DynamicColor get tertiary => DynamicColor(
    name: 'tertiary',
    palette: (s) => s.tertiaryPalette,
    tone: (s) {
      final p = s.tertiaryPalette;
      if (s.platform == SchemePlatform.watch) {
        return s.variant == Variant.tonalSpot ? _tMaxC(p, 0, 90) : _tMaxC(p);
      }
      return switch (s.variant) {
        Variant.expressive || Variant.vibrant => _tMaxC(p, 0, HueBands.isCyan(p.hue) ? 88 : (s.isDark ? 98 : 100)),
        _ => s.isDark ? _tMaxC(p, 0, 98) : _tMaxC(p), // neutral, tonal spot
      };
    },
    isBackground: true,
    background: _accentBackground,
    contrastCurve: (s) => _phone(s) ? _curve(4.5) : _curve(7),
    toneDeltaPair: (s) => _phone(s)
        ? ToneDeltaPair(
            roleA: tertiaryContainer,
            roleB: tertiary,
            delta: 5,
            polarity: TonePolarity.relativeLighter,
            constraint: DeltaConstraint.farther,
          )
        : null,
  );

  DynamicColor get tertiaryDim => DynamicColor(
    name: 'tertiary_dim',
    palette: (s) => s.tertiaryPalette,
    tone: (s) => s.variant == Variant.tonalSpot ? _tMaxC(s.tertiaryPalette, 0, 90) : _tMaxC(s.tertiaryPalette),
    isBackground: true,
    background: (_) => surfaceContainerHigh,
    contrastCurve: (_) => _curve(4.5),
    toneDeltaPair: (_) => ToneDeltaPair(
      roleA: tertiaryDim,
      roleB: tertiary,
      delta: 5,
      polarity: TonePolarity.darker,
      constraint: DeltaConstraint.farther,
    ),
  );

  DynamicColor get onTertiary => DynamicColor(
    name: 'on_tertiary',
    palette: (s) => s.tertiaryPalette,
    background: (s) => _phone(s) ? tertiary : tertiaryDim,
    contrastCurve: (s) => _phone(s) ? _curve(6) : _curve(7),
  );

  DynamicColor get tertiaryContainer => DynamicColor(
    name: 'tertiary_container',
    palette: (s) => s.tertiaryPalette,
    tone: (s) {
      final p = s.tertiaryPalette;
      if (s.platform == SchemePlatform.watch) {
        return s.variant == Variant.tonalSpot ? _tMaxC(p, 0, 90) : _tMaxC(p);
      }
      return switch (s.variant) {
        Variant.neutral => s.isDark ? _tMaxC(p, 0, 93) : _tMaxC(p, 0, 96),
        Variant.tonalSpot => _tMaxC(p, 0, s.isDark ? 93 : 100),
        Variant.expressive => _tMaxC(p, 75, HueBands.isCyan(p.hue) ? 88 : (s.isDark ? 93 : 100)),
        _ => s.isDark ? _tMaxC(p, 0, 93) : _tMaxC(p, 72, 100), // vibrant
      };
    },
    isBackground: true,
    background: _containerBackground,
    toneDeltaPair: (s) => s.platform == SchemePlatform.watch
        ? ToneDeltaPair(
            roleA: tertiaryContainer,
            roleB: tertiaryDim,
            delta: 10,
            polarity: TonePolarity.darker,
            constraint: DeltaConstraint.farther,
          )
        : null,
    contrastCurve: _containerContrast,
  );

  DynamicColor get onTertiaryContainer => DynamicColor(
    name: 'on_tertiary_container',
    palette: (s) => s.tertiaryPalette,
    background: (_) => tertiaryContainer,
    contrastCurve: (s) => _phone(s) ? _curve(6) : _curve(7),
  );

  // ---------------------------------------------------------------------------
  // Errors
  // ---------------------------------------------------------------------------

  DynamicColor get error => DynamicColor(
    name: 'error',
    palette: (s) => s.errorPalette,
    tone: (s) {
      if (!_phone(s)) return _tMinC(s.errorPalette);
      return s.isDark ? _tMinC(s.errorPalette, 0, 98) : _tMaxC(s.errorPalette);
    },
    isBackground: true,
    background: _accentBackground,
    contrastCurve: (s) => _phone(s) ? _curve(4.5) : _curve(7),
    toneDeltaPair: (s) => _phone(s)
        ? ToneDeltaPair(
            roleA: errorContainer,
            roleB: error,
            delta: 5,
            polarity: TonePolarity.relativeLighter,
            constraint: DeltaConstraint.farther,
          )
        : null,
  );

  DynamicColor get errorDim => DynamicColor(
    name: 'error_dim',
    palette: (s) => s.errorPalette,
    tone: (s) => _tMinC(s.errorPalette),
    isBackground: true,
    background: (_) => surfaceContainerHigh,
    contrastCurve: (_) => _curve(4.5),
    toneDeltaPair: (_) => ToneDeltaPair(
      roleA: errorDim,
      roleB: error,
      delta: 5,
      polarity: TonePolarity.darker,
      constraint: DeltaConstraint.farther,
    ),
  );

  DynamicColor get onError => DynamicColor(
    name: 'on_error',
    palette: (s) => s.errorPalette,
    background: (s) => _phone(s) ? error : errorDim,
    contrastCurve: (s) => _phone(s) ? _curve(6) : _curve(7),
  );

  DynamicColor get errorContainer => DynamicColor(
    name: 'error_container',
    palette: (s) => s.errorPalette,
    tone: (s) {
      if (s.platform == SchemePlatform.watch) return 30.0;
      return s.isDark ? _tMinC(s.errorPalette, 30, 93) : _tMaxC(s.errorPalette, 0, 90);
    },
    isBackground: true,
    background: _containerBackground,
    toneDeltaPair: (s) => s.platform == SchemePlatform.watch
        ? ToneDeltaPair(
            roleA: errorContainer,
            roleB: errorDim,
            delta: 10,
            polarity: TonePolarity.darker,
            constraint: DeltaConstraint.farther,
          )
        : null,
    contrastCurve: _containerContrast,
  );

  DynamicColor get onErrorContainer => DynamicColor(
    name: 'on_error_container',
    palette: (s) => s.errorPalette,
    background: (_) => errorContainer,
    contrastCurve: (s) => _phone(s) ? _curve(4.5) : _curve(7),
  );

  // ---------------------------------------------------------------------------
  // Fixed accents: the light-mode container tone in both modes.
  // ---------------------------------------------------------------------------

  DynamicColor _fixed(String name, SchemeFn<TonalPalette> palette, DynamicColor Function() container) => DynamicColor(
    name: name,
    palette: palette,
    tone: (s) => container().getTone(DynamicScheme.from(s, isDark: false, contrastLevel: 0.0)),
    isBackground: true,
    background: _containerBackground,
    contrastCurve: _containerContrast,
  );

  DynamicColor _fixedDim(String name, SchemeFn<TonalPalette> palette, DynamicColor Function() fixed,
      DynamicColor Function() self) => DynamicColor(
    name: name,
    palette: palette,
    tone: (s) => fixed().getTone(s),
    isBackground: true,
    toneDeltaPair: (_) => ToneDeltaPair(
      roleA: self(),
      roleB: fixed(),
      delta: 5,
      polarity: TonePolarity.darker,
      constraint: DeltaConstraint.exact,
    ),
  );

  DynamicColor _onFixed(String name, SchemeFn<TonalPalette> palette, DynamicColor Function() fixedDim, double ratio) =>
      DynamicColor(name: name, palette: palette, background: (_) => fixedDim(), contrastCurve: (_) => _curve(ratio));

  DynamicColor get primaryFixed => _fixed('primary_fixed', (s) => s.primaryPalette, () => primaryContainer);
  DynamicColor get primaryFixedDim =>
      _fixedDim('primary_fixed_dim', (s) => s.primaryPalette, () => primaryFixed, () => primaryFixedDim);
  DynamicColor get onPrimaryFixed => _onFixed('on_primary_fixed', (s) => s.primaryPalette, () => primaryFixedDim, 7);
  DynamicColor get onPrimaryFixedVariant =>
      _onFixed('on_primary_fixed_variant', (s) => s.primaryPalette, () => primaryFixedDim, 4.5);

  DynamicColor get secondaryFixed => _fixed('secondary_fixed', (s) => s.secondaryPalette, () => secondaryContainer);
  DynamicColor get secondaryFixedDim =>
      _fixedDim('secondary_fixed_dim', (s) => s.secondaryPalette, () => secondaryFixed, () => secondaryFixedDim);
  DynamicColor get onSecondaryFixed =>
      _onFixed('on_secondary_fixed', (s) => s.secondaryPalette, () => secondaryFixedDim, 7);
  DynamicColor get onSecondaryFixedVariant =>
      _onFixed('on_secondary_fixed_variant', (s) => s.secondaryPalette, () => secondaryFixedDim, 4.5);

  DynamicColor get tertiaryFixed => _fixed('tertiary_fixed', (s) => s.tertiaryPalette, () => tertiaryContainer);
  DynamicColor get tertiaryFixedDim =>
      _fixedDim('tertiary_fixed_dim', (s) => s.tertiaryPalette, () => tertiaryFixed, () => tertiaryFixedDim);
  DynamicColor get onTertiaryFixed => _onFixed('on_tertiary_fixed', (s) => s.tertiaryPalette, () => tertiaryFixedDim, 7);
  DynamicColor get onTertiaryFixedVariant =>
      _onFixed('on_tertiary_fixed_variant', (s) => s.tertiaryPalette, () => tertiaryFixedDim, 4.5);

  // ---------------------------------------------------------------------------
  // Solving
  // ---------------------------------------------------------------------------

  /// The role's colour: its solved tone at the palette's chroma (times the
  /// role's multiplier). Finding the tone first and then re-applying chroma
  /// lets high-contrast tones recover chroma a T90 could not hold.
  Hct getHct(DynamicScheme scheme, DynamicColor color) {
    final palette = color.palette(scheme);
    final tone = getTone(scheme, color);
    final chromaMultiplier = color.chromaMultiplier?.call(scheme) ?? 1.0;
    if (chromaMultiplier == 1.0) return palette.getHct(tone);
    final chroma = palette.chroma * chromaMultiplier;
    if (tone == 99.0 && HueBands.isYellow(palette.hue)) {
      return TonalPalette.fromHueAndChroma(palette.hue, chroma).getHct(tone);
    }
    return Hct.from(palette.hue, chroma, tone);
  }

  /// The role's tone after tone pairing, contrast against its background(s)
  /// and the background-tone dead zone (T50–T64 is avoided). Memoised per
  /// scheme by role name.
  double getTone(DynamicScheme scheme, DynamicColor color) {
    final cached = scheme.toneCache[color.name];
    if (cached != null) return cached;
    final tone = _solveTone(scheme, color);
    scheme.toneCache[color.name] = tone;
    return tone;
  }

  double _solveTone(DynamicScheme scheme, DynamicColor color) {
    final toneDeltaPair = color.toneDeltaPair?.call(scheme);

    // Case 0: tone delta pair.
    if (toneDeltaPair != null) {
      final roleA = toneDeltaPair.roleA;
      final roleB = toneDeltaPair.roleB;
      final polarity = toneDeltaPair.polarity;
      final absoluteDelta = polarity == TonePolarity.darker ||
              (polarity == TonePolarity.relativeLighter && scheme.isDark) ||
              (polarity == TonePolarity.relativeDarker && !scheme.isDark)
          ? -toneDeltaPair.delta
          : toneDeltaPair.delta;

      final amRoleA = color.name == roleA.name;
      final selfRole = amRoleA ? roleA : roleB;
      final referenceRole = amRoleA ? roleB : roleA;
      var selfTone = selfRole.tone(scheme);
      final referenceTone = referenceRole.getTone(scheme);
      final relativeDelta = absoluteDelta * (amRoleA ? 1 : -1);

      switch (toneDeltaPair.constraint) {
        case DeltaConstraint.exact:
          selfTone = _coerce(referenceTone + relativeDelta, 0, 100);
        case DeltaConstraint.nearer:
          selfTone = relativeDelta > 0
              ? _coerce(_coerce(selfTone, referenceTone, referenceTone + relativeDelta), 0, 100)
              : _coerce(_coerce(selfTone, referenceTone + relativeDelta, referenceTone), 0, 100);
        case DeltaConstraint.farther:
          selfTone = relativeDelta > 0
              ? _coerce(selfTone, referenceTone + relativeDelta, 100)
              : _coerce(selfTone, 0, referenceTone + relativeDelta);
      }

      final background = color.background?.call(scheme);
      final contrastCurve = color.contrastCurve?.call(scheme);
      if (background != null && contrastCurve != null) {
        final bgTone = background.getTone(scheme);
        final selfContrast = contrastCurve.get(scheme.contrastLevel);
        if (!(Contrast.ratioOfTones(bgTone, selfTone) >= selfContrast && scheme.contrastLevel >= 0)) {
          selfTone = DynamicColor.foregroundTone(bgTone, selfContrast);
        }
      }

      // Keep backgrounds out of the awkward middle; fixed-dim roles exempt.
      if (color.isBackground && !color.name.endsWith('_fixed_dim')) {
        selfTone = selfTone >= 57 ? _coerce(selfTone, 65, 100) : _coerce(selfTone, 0, 49);
      }
      return selfTone;
    }

    // Case 1: no tone delta pair; solve for itself.
    var answer = color.tone(scheme);
    final background = color.background?.call(scheme);
    final contrastCurve = color.contrastCurve?.call(scheme);
    if (background == null || contrastCurve == null) return answer;

    final bgTone = background.getTone(scheme);
    final desiredRatio = contrastCurve.get(scheme.contrastLevel);
    // Re-solve when contrast falls short, or when contrast is being lowered.
    if (!(Contrast.ratioOfTones(bgTone, answer) >= desiredRatio && scheme.contrastLevel >= 0)) {
      answer = DynamicColor.foregroundTone(bgTone, desiredRatio);
    }
    if (color.isBackground && !color.name.endsWith('_fixed_dim')) {
      answer = answer >= 57 ? _coerce(answer, 65, 100) : _coerce(answer, 0, 49);
    }

    final secondBackground = color.secondBackground?.call(scheme);
    if (secondBackground == null) return answer;

    // Case 2: two backgrounds.
    final bgTone1 = background.getTone(scheme);
    final bgTone2 = secondBackground.getTone(scheme);
    final upper = math.max(bgTone1, bgTone2);
    final lower = math.min(bgTone1, bgTone2);
    if (Contrast.ratioOfTones(upper, answer) >= desiredRatio && Contrast.ratioOfTones(lower, answer) >= desiredRatio) {
      return answer;
    }
    final lightOption = Contrast.lighter(upper, desiredRatio);
    final darkOption = Contrast.darker(lower, desiredRatio);
    final availables = [?lightOption, ?darkOption];
    final prefersLight =
        DynamicColor.tonePrefersLightForeground(bgTone1) || DynamicColor.tonePrefersLightForeground(bgTone2);
    if (prefersLight) return lightOption ?? 100.0;
    if (availables.length == 1) return availables[0];
    return darkOption ?? 0.0;
  }

  // ---------------------------------------------------------------------------
  // Palettes
  // ---------------------------------------------------------------------------

  static const List<double> _expressiveAccentBreakpoints = [0, 105, 140, 204, 253, 278, 300, 333, 360];
  static const List<double> _vibrantBreakpoints = [0, 38, 105, 140, 333, 360];
  static const List<double> _vibrantRotations = [-14, 10, -14, 10, -14];

  TonalPalette getPrimaryPalette(
    Variant variant,
    Hct source,
    bool isDark,
    SchemePlatform platform,
    double contrastLevel,
  ) {
    final phone = platform == SchemePlatform.phone;
    return switch (variant) {
      Variant.neutral => TonalPalette.fromHueAndChroma(
        source.hue,
        phone ? (HueBands.isBlue(source.hue) ? 12 : 8) : (HueBands.isBlue(source.hue) ? 16 : 12),
      ),
      Variant.tonalSpot => TonalPalette.fromHueAndChroma(source.hue, phone && isDark ? 26 : 32),
      Variant.expressive => TonalPalette.fromHueAndChroma(source.hue, phone ? (isDark ? 36 : 48) : 40),
      Variant.vibrant => TonalPalette.fromHueAndChroma(source.hue, phone ? 74 : 56),
      _ => throw _unsupported(variant),
    };
  }

  TonalPalette getSecondaryPalette(
    Variant variant,
    Hct source,
    bool isDark,
    SchemePlatform platform,
    double contrastLevel,
  ) {
    final phone = platform == SchemePlatform.phone;
    return switch (variant) {
      Variant.neutral => TonalPalette.fromHueAndChroma(
        source.hue,
        phone ? (HueBands.isBlue(source.hue) ? 6 : 4) : (HueBands.isBlue(source.hue) ? 10 : 6),
      ),
      Variant.tonalSpot => TonalPalette.fromHueAndChroma(source.hue, 16),
      Variant.expressive => TonalPalette.fromHueAndChroma(
        DynamicScheme.getRotatedHue(source, _expressiveAccentBreakpoints, const [-160, 155, -100, 96, -96, -156, -165, -160]),
        phone ? (isDark ? 16 : 24) : 24,
      ),
      Variant.vibrant => TonalPalette.fromHueAndChroma(
        DynamicScheme.getRotatedHue(source, _vibrantBreakpoints, _vibrantRotations),
        phone ? 56 : 36,
      ),
      _ => throw _unsupported(variant),
    };
  }

  TonalPalette getTertiaryPalette(
    Variant variant,
    Hct source,
    bool isDark,
    SchemePlatform platform,
    double contrastLevel,
  ) {
    final phone = platform == SchemePlatform.phone;
    return switch (variant) {
      Variant.neutral => TonalPalette.fromHueAndChroma(
        DynamicScheme.getRotatedHue(source, const [0, 38, 105, 161, 204, 278, 333, 360], const [-32, 26, 10, -39, 24, -15, -32]),
        phone ? 20 : 36,
      ),
      Variant.tonalSpot => TonalPalette.fromHueAndChroma(
        DynamicScheme.getRotatedHue(source, const [0, 20, 71, 161, 333, 360], const [-40, 48, -32, 40, -32]),
        phone ? 28 : 32,
      ),
      Variant.expressive => TonalPalette.fromHueAndChroma(
        DynamicScheme.getRotatedHue(source, _expressiveAccentBreakpoints, const [-165, 160, -105, 101, -101, -160, -170, -165]),
        48,
      ),
      Variant.vibrant => TonalPalette.fromHueAndChroma(
        DynamicScheme.getRotatedHue(
          source,
          const [0, 38, 71, 105, 140, 161, 253, 333, 360],
          const [-72, 35, 24, -24, 62, 50, 62, -72],
        ),
        56,
      ),
      _ => throw _unsupported(variant),
    };
  }

  TonalPalette getNeutralPalette(
    Variant variant,
    Hct source,
    bool isDark,
    SchemePlatform platform,
    double contrastLevel,
  ) {
    final phone = platform == SchemePlatform.phone;
    return switch (variant) {
      Variant.neutral => TonalPalette.fromHueAndChroma(source.hue, phone ? 1.4 : 6),
      Variant.tonalSpot => TonalPalette.fromHueAndChroma(source.hue, phone ? 5 : 10),
      Variant.expressive => TonalPalette.fromHueAndChroma(
        _expressiveNeutralHue(source),
        _expressiveNeutralChroma(source, isDark, platform),
      ),
      Variant.vibrant => TonalPalette.fromHueAndChroma(_vibrantNeutralHue(source), _vibrantNeutralChroma(source, platform)),
      _ => throw _unsupported(variant),
    };
  }

  TonalPalette getNeutralVariantPalette(
    Variant variant,
    Hct source,
    bool isDark,
    SchemePlatform platform,
    double contrastLevel,
  ) {
    final phone = platform == SchemePlatform.phone;
    switch (variant) {
      case Variant.neutral:
        return TonalPalette.fromHueAndChroma(source.hue, (phone ? 1.4 : 6.0) * 2.2);
      case Variant.tonalSpot:
        return TonalPalette.fromHueAndChroma(source.hue, (phone ? 5.0 : 10.0) * 1.7);
      case Variant.expressive:
        final hue = _expressiveNeutralHue(source);
        final chroma = _expressiveNeutralChroma(source, isDark, platform);
        return TonalPalette.fromHueAndChroma(hue, chroma * (hue >= 105 && hue < 125 ? 1.6 : 2.3));
      case Variant.vibrant:
        return TonalPalette.fromHueAndChroma(_vibrantNeutralHue(source), _vibrantNeutralChroma(source, platform) * 1.29);
      default:
        throw _unsupported(variant);
    }
  }

  TonalPalette getErrorPalette(
    Variant variant,
    Hct source,
    bool isDark,
    SchemePlatform platform,
    double contrastLevel,
  ) {
    final phone = platform == SchemePlatform.phone;
    final errorHue = DynamicScheme.getPiecewiseValue(
      source,
      const [0, 3, 13, 23, 33, 43, 153, 273, 360],
      const [12, 22, 32, 12, 22, 32, 22, 12],
    );
    return switch (variant) {
      Variant.neutral => TonalPalette.fromHueAndChroma(errorHue, phone ? 50 : 40),
      Variant.tonalSpot => TonalPalette.fromHueAndChroma(errorHue, phone ? 60 : 48),
      Variant.expressive => TonalPalette.fromHueAndChroma(errorHue, phone ? 64 : 48),
      Variant.vibrant => TonalPalette.fromHueAndChroma(errorHue, phone ? 80 : 60),
      _ => throw _unsupported(variant),
    };
  }

  static double _expressiveNeutralHue(Hct source) =>
      DynamicScheme.getRotatedHue(source, const [0, 71, 124, 253, 278, 300, 360], const [10, 0, 10, 0, 10, 0]);

  static double _expressiveNeutralChroma(Hct source, bool isDark, SchemePlatform platform) {
    if (platform != SchemePlatform.phone) return 12;
    if (!isDark) return 18;
    return HueBands.isYellow(_expressiveNeutralHue(source)) ? 6 : 14;
  }

  static double _vibrantNeutralHue(Hct source) =>
      DynamicScheme.getRotatedHue(source, _vibrantBreakpoints, _vibrantRotations);

  static double _vibrantNeutralChroma(Hct source, SchemePlatform platform) {
    if (platform == SchemePlatform.phone) return 28;
    return HueBands.isBlue(_vibrantNeutralHue(source)) ? 28 : 20;
  }

  static UnsupportedError _unsupported(Variant variant) =>
      UnsupportedError('$variant has no SPEC_2025 palettes (Kotlin falls back to 2021).');

  // ---------------------------------------------------------------------------
  // Tone search
  // ---------------------------------------------------------------------------

  /// The tone in [lowerBound, upperBound] where the palette (chroma scaled by
  /// [chromaMultiplier]) is most saturated, searching down from T100.
  static double _tMaxC(TonalPalette palette, [double lowerBound = 0, double upperBound = 100, double chromaMultiplier = 1]) {
    final answer = _findBestToneForChroma(palette.hue, palette.chroma * chromaMultiplier, 100, true);
    return _coerce(answer, lowerBound, upperBound);
  }

  /// The tone in [lowerBound, upperBound] where the palette is most
  /// saturated, searching up from T0.
  static double _tMinC(TonalPalette palette, [double lowerBound = 0, double upperBound = 100]) {
    final answer = _findBestToneForChroma(palette.hue, palette.chroma, 0, false);
    return _coerce(answer, lowerBound, upperBound);
  }

  static double _findBestToneForChroma(double hue, double chroma, double tone, bool byDecreasingTone) {
    var answer = tone;
    var bestCandidate = Hct.from(hue, chroma, answer);
    while (bestCandidate.chroma < chroma) {
      if (tone < 0 || tone > 100) break;
      tone += byDecreasingTone ? -1.0 : 1.0;
      final newCandidate = Hct.from(hue, chroma, tone);
      if (bestCandidate.chroma < newCandidate.chroma) {
        bestCandidate = newCandidate;
        answer = tone;
      }
    }
    return answer;
  }

  static ContrastCurve _curve(double defaultContrast) => switch (defaultContrast) {
    1.5 => const ContrastCurve(1.5, 1.5, 3.0, 5.5),
    3.0 => const ContrastCurve(3.0, 3.0, 4.5, 7.0),
    4.5 => const ContrastCurve(4.5, 4.5, 7.0, 11.0),
    6.0 => const ContrastCurve(6.0, 6.0, 7.0, 11.0),
    7.0 => const ContrastCurve(7.0, 7.0, 11.0, 21.0),
    9.0 => const ContrastCurve(9.0, 9.0, 11.0, 21.0),
    11.0 => const ContrastCurve(11.0, 11.0, 21.0, 21.0),
    21.0 => const ContrastCurve(21.0, 21.0, 21.0, 21.0),
    _ => ContrastCurve(defaultContrast, defaultContrast, 7.0, 21.0),
  };

  /// Kotlin `Double.coerceIn`: throws when the range is empty, as Kotlin does.
  static double _coerce(double value, double min, double max) {
    if (min > max) throw ArgumentError('Cannot coerce $value to an empty range $min..$max.');
    return value < min ? min : (value > max ? max : value);
  }
}
