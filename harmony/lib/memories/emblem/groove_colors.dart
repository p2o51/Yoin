/// 唱片刻纹 · colour (Android GrooveColors.kt, from groove.js v4 `colours()`).
/// Direct palette lerp, never hue-rotated; every fill and stroke is ONE flat
/// colour (no gloss, sheen, glow, gradient or shadow — owner, 2026-10-04).
library;

import 'package:flutter/material.dart';

import '../palette/memory_palette.dart';
import 'groove_geometry.dart';

/// Fixed dark ends the palette lerps toward (M3 baseline background / surface-container in dark).
const Color _backgroundDark = Color(0xFF141218);
const Color _surfaceDark = Color(0xFF1D1B20);
const Color _white = Color(0xFFFFFFFF);
const Color _none = Color(0x00000000);

/// The neutral theme tokens an unrated emblem is drawn with ("the album's
/// colour is earned, not given"). [tiltHigh] / [tiltLow] are the neutral tilt
/// tones of the hairline.
@immutable
final class GrooveNeutrals {
  const GrooveNeutrals({
    required this.outline,
    required this.surface,
    required this.onSurfaceVariant,
    required this.dark,
    this.tiltHigh = _white,
    this.tiltLow = _none,
  });

  factory GrooveNeutrals.from(ColorScheme scheme) {
    final dark = scheme.surface.computeLuminance() < 0.5;
    return GrooveNeutrals(
      outline: scheme.outline,
      surface: scheme.surface,
      onSurfaceVariant: scheme.onSurfaceVariant,
      dark: dark,
      tiltHigh: dark ? scheme.onSurface : _white,
      tiltLow: dark ? scheme.outlineVariant : scheme.onSurface,
    );
  }

  final Color outline;
  final Color surface;
  final Color onSurfaceVariant;
  final bool dark;
  final Color tiltHigh;
  final Color tiltLow;

  @override
  bool operator ==(Object other) =>
      other is GrooveNeutrals &&
      other.outline == outline &&
      other.surface == surface &&
      other.onSurfaceVariant == onSurfaceVariant &&
      other.dark == dark &&
      other.tiltHigh == tiltHigh &&
      other.tiltLow == tiltLow;
  @override
  int get hashCode => Object.hash(outline, surface, onSurfaceVariant, dark, tiltHigh, tiltLow);
}

/// The two tones a ring line takes under tilt: lighter (accent-ward) on the
/// side the tilt points to, deeper on the far side.
@immutable
final class GrooveTint {
  const GrooveTint({required this.high, required this.low});
  final Color high;
  final Color low;
}

/// Every colour one emblem uses; fields a kind does not draw are transparent.
///
/// Album / average: [ground] the opaque disc, [cut] the rated grooves, [under]
/// the faint band beneath a cut, [dot] the pre-cut lattice, [rim] / [rim2] the
/// outer lines, [label] / [labelRing] (album) or [labelFill] / [labelStroke]
/// (average) the centre, [ink] / [caption] its type, [lit] a ring lit by the
/// award, [flare] the 10.0 rim pulse. Unrated: [ground], [dot], [tinyDot],
/// [slot] / [slotFill] (the dashed mould), [hairline] (the cover rim).
@immutable
final class GrooveColors {
  const GrooveColors({
    required this.ground,
    this.cut = _none,
    this.under = _none,
    required this.dot,
    this.tinyDot = _none,
    this.rim = _none,
    this.rim2 = _none,
    this.label = _none,
    this.labelRing = _none,
    this.labelFill = _none,
    this.labelStroke = _none,
    required this.ink,
    this.caption = _none,
    this.lit = _none,
    this.flare = _none,
    this.slot = _none,
    this.slotFill = _none,
    this.hairline = _none,
    this.hairlineStroke = _none,
    this.tiltHigh = _none,
    this.tiltLow = _none,
  });

  final Color ground;
  final Color cut;
  final Color under;
  final Color dot;
  final Color tinyDot;
  final Color rim;
  final Color rim2;
  final Color label;
  final Color labelRing;
  final Color labelFill;
  final Color labelStroke;
  final Color ink;
  final Color caption;
  final Color lit;
  final Color flare;
  final Color slot;
  final Color slotFill;
  final Color hairline;

  /// The unrated hairline's opaque base (its tilt tones mix from it).
  final Color hairlineStroke;

  /// The tilt's targets: the lighter tone and the deeper one.
  final Color tiltHigh;
  final Color tiltLow;

  /// Prototype `c.tint(base)`: half way to the accent-ward / deep-ward target, flat.
  GrooveTint tint(Color base) => GrooveTint(high: mixSrgb(base, tiltHigh, 0.55), low: mixSrgb(base, tiltLow, 0.5));
}

Color _a(Color c, double alpha) => c.withValues(alpha: alpha);

/// Prototype `colours(m, kind, dark, on)`.
GrooveColors grooveColors(
  MemoryPalette palette,
  GrooveScoreKind kind, {
  required bool dark,
  required GrooveSurface surface,
  required GrooveNeutrals neutrals,
}) {
  final p = palette;
  Color mix(Color a, Color b, double t) => mixSrgb(a, b, t);
  switch (kind) {
    case GrooveScoreKind.album:
      return dark
          ? GrooveColors(
              ground: mix(mix(p.base, _backgroundDark, 0.34), mix(p.base, p.soft, 0.06), 0.6),
              cut: mix(mix(p.soft, p.base, 0.12), mix(p.accent, p.soft, 0.15), 0.4),
              under: _a(p.soft, 0.16),
              dot: _a(p.soft, 0.46),
              rim: p.accent,
              rim2: _a(p.accent, 0.5),
              label: mix(mix(p.base, p.soft, 0.66), mix(p.base, p.soft, 0.42), 0.5),
              labelRing: mix(p.deep, p.base, 0.3),
              ink: p.deep,
              caption: mix(p.deep, p.base, 0.35),
              tiltHigh: mix(p.accent, _white, 0.45),
              tiltLow: mix(p.base, _backgroundDark, 0.25),
              lit: mix(p.accent, _white, 0.3),
              flare: mix(p.accent, _white, 0.15),
            )
          : GrooveColors(
              ground: mix(mix(p.soft, _white, 0.62), mix(p.soft, p.base, 0.06), 0.6),
              cut: mix(mix(p.base, p.deep, 0.2), mix(p.base, p.accent, 0.32), 0.4),
              under: _a(p.base, 0.1),
              dot: _a(p.base, 0.42),
              rim: p.base,
              rim2: mix(p.accent, p.base, 0.25),
              label: mix(mix(p.base, _white, 0.14), mix(p.base, p.deep, 0.3), 0.5),
              labelRing: p.accent,
              ink: mix(p.soft, _white, 0.72),
              caption: mix(p.soft, _white, 0.35),
              tiltHigh: p.accent,
              tiltLow: p.deep,
              lit: p.accent,
              flare: mix(p.accent, p.base, 0.1),
            );
    case GrooveScoreKind.average:
      return dark
          ? GrooveColors(
              ground: mix(mix(p.deep, _surfaceDark, 0.45), mix(p.deep, _surfaceDark, 0.25), 0.6),
              cut: mix(mix(p.base, p.soft, 0.45), mix(p.soft, p.accent, 0.4), 0.4),
              under: _a(p.soft, 0.08),
              dot: _a(p.soft, 0.42),
              rim: mix(p.base, p.soft, 0.5),
              labelFill: mix(p.deep, _surfaceDark, 0.15),
              labelStroke: mix(p.base, p.soft, 0.6),
              ink: mix(p.soft, _white, 0.5),
              caption: _a(p.soft, 0.82),
              tiltHigh: mix(p.accent, _white, 0.45),
              tiltLow: mix(p.deep, _surfaceDark, 0.2),
              lit: mix(p.accent, _white, 0.3),
              flare: mix(p.accent, _white, 0.15),
            )
          : GrooveColors(
              ground: mix(mix(p.soft, _white, 0.82), mix(p.soft, _white, 0.6), 0.6),
              cut: mix(mix(p.base, p.soft, 0.08), mix(p.base, p.accent, 0.34), 0.4),
              under: _a(p.base, 0.06),
              dot: _a(p.base, 0.38),
              rim: p.base,
              labelFill: mix(p.soft, _white, 0.4),
              labelStroke: p.base,
              ink: p.deep,
              caption: _a(p.deep, 0.74),
              tiltHigh: p.accent,
              tiltLow: p.deep,
              lit: p.accent,
              flare: mix(p.accent, p.base, 0.1),
            );
    case GrooveScoreKind.none:
      final n = neutrals.outline;
      return GrooveColors(
        // opaque on a cover so the silhouette separates from the artwork; a faint ground elsewhere
        ground: surface == GrooveSurface.cover ? neutrals.surface : _a(n, dark ? 0.1 : 0.06),
        dot: _a(n, dark ? 0.82 : 0.6),
        tinyDot: _a(n, dark ? 0.95 : 0.8),
        slot: n,
        slotFill: _a(n, dark ? 0.14 : 0.08),
        ink: neutrals.onSurfaceVariant,
        hairline: _a(n, dark ? 0.7 : 0.45),
        hairlineStroke: n,
        tiltHigh: neutrals.tiltHigh,
        tiltLow: neutrals.tiltLow,
      );
  }
}
