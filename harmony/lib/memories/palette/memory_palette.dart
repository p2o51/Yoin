/// One album's Memories colours (Android ui/memories/showcase MemoryPalette.kt
/// + MemoryCoverPalette.kt). Every Memories colour is a direct sRGB lerp
/// between four anchors and a few fixed ends — never a hue rotation, never
/// fromSeed. Mixes round to 8 bits at every step (JS `Math.round`), exactly
/// like the prototype's `#rrggbb` strings they were tuned on.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../home/widgets/cover_palette.dart';
import '../../theme/motion.dart';
import '../../ui/component/artwork_retry.dart';

/// One album's four palette anchors (prototype `m.palette`).
@immutable
final class MemoryPalette {
  const MemoryPalette({required this.base, required this.accent, required this.deep, required this.soft});

  final Color base;
  final Color accent;
  final Color deep;
  final Color soft;

  /// OKLab L of the dark playhead highlight: on-surface (#E7E0E8) is .915.
  static const double darkHighlightLightness = 0.92;

  /// HSL lightness window of a backdrop-built base.
  static const double backdropBaseMinLightness = 0.22;
  static const double backdropBaseMaxLightness = 0.46;

  /// Where deep / soft sit relative to base (m1: deep ≈ base × .45, soft ≈ .8 white).
  static const double backdropDeepMix = 0.55;
  static const double backdropSoftMix = 0.8;

  /// A palette from a cover's two colours: [base] held inside the ink
  /// lightness window, deep / soft lerped from it toward black / white.
  factory MemoryPalette.fromBackdrop(Color base, Color accent) {
    final held = holdLightness(base, backdropBaseMinLightness, backdropBaseMaxLightness);
    return MemoryPalette(
      base: held,
      accent: accent.withValues(alpha: 1),
      deep: mixSrgb(held, const Color(0xFF000000), backdropDeepMix),
      soft: mixSrgb(held, const Color(0xFFFFFFFF), backdropSoftMix),
    );
  }

  /// The neutral stand-in while a cover is read: the theme's [primary] and an
  /// accent of its own family (Android `rememberMemoryCoverColors` placeholder).
  factory MemoryPalette.fallback(Color primary) =>
      MemoryPalette.fromBackdrop(primary, memoryAccentFor(primary, const []));

  /// The card / diary tones (prototype `pal(m, dark)`).
  MemoryPaletteTones tones(bool dark) {
    if (!dark) {
      return MemoryPaletteTones(
        ink: base,
        highlight: base,
        button: base,
        onButton: const Color(0xFFFFFFFF),
        dot: base,
        tint: base.withValues(alpha: 0.06),
      );
    }
    final lift = mixSrgb(base, soft, 0.6);
    return MemoryPaletteTones(
      ink: lift,
      highlight: liftHue(base, darkHighlightLightness),
      button: lift,
      onButton: deep,
      dot: lift,
      tint: lift.withValues(alpha: 0.1),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is MemoryPalette &&
      other.base == base &&
      other.accent == accent &&
      other.deep == deep &&
      other.soft == soft;

  @override
  int get hashCode => Object.hash(base, accent, deep, soft);

  @override
  String toString() => 'MemoryPalette(base: $base, accent: $accent, deep: $deep, soft: $soft)';
}

/// [ink] text / rule colour in the album's hue; [highlight] the playing line
/// (lifted to on-surface lightness in dark); [button] the filled pill and
/// [onButton] its label.
@immutable
final class MemoryPaletteTones {
  const MemoryPaletteTones({
    required this.ink,
    required this.highlight,
    required this.button,
    required this.onButton,
    required this.dot,
    required this.tint,
  });

  final Color ink;
  final Color highlight;
  final Color button;
  final Color onButton;
  final Color dot;
  final Color tint;

  @override
  bool operator ==(Object other) =>
      other is MemoryPaletteTones &&
      other.ink == ink &&
      other.highlight == highlight &&
      other.button == button &&
      other.onButton == onButton &&
      other.dot == dot &&
      other.tint == tint;

  @override
  int get hashCode => Object.hash(ink, highlight, button, onButton, dot, tint);
}

/// The handoff's sample albums (data.js m1–m4, twostate4's m5), for previews and tests.
abstract final class MemoryPaletteSamples {
  static const m1 = MemoryPalette(
    base: Color(0xFF3B2D8F),
    accent: Color(0xFFE2C27A),
    deep: Color(0xFF1B1554),
    soft: Color(0xFFD9D2F4),
  );
  static const m2 = MemoryPalette(
    base: Color(0xFF7A5A14),
    accent: Color(0xFF9CC7E8),
    deep: Color(0xFF3D2E07),
    soft: Color(0xFFEFE4C4),
  );
  static const m3 = MemoryPalette(
    base: Color(0xFF1F4F8F),
    accent: Color(0xFFE5A07C),
    deep: Color(0xFF0B2448),
    soft: Color(0xFFD3E2F6),
  );
  static const m4 = MemoryPalette(
    base: Color(0xFF8F1D3A),
    accent: Color(0xFF7FE0C4),
    deep: Color(0xFF4A0B1C),
    soft: Color(0xFFF5D4DC),
  );
  static const m5 = MemoryPalette(
    base: Color(0xFF2F6A4F),
    accent: Color(0xFFE8B86A),
    deep: Color(0xFF123826),
    soft: Color(0xFFD3EADC),
  );
}

// ---------------------------------------------------------------------------
// The prototype's colour maths.

/// 8-bit sRGB channels of a colour (alpha ignored).
List<int> rgb8(Color c) {
  final v = c.toARGB32();
  return [(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF];
}

/// JS `Math.round`: halves go up (toward +∞).
double jsRound(double x) => (x + 0.5).floorToDouble();

Color _colorOf8(double r, double g, double b) => Color.fromARGB(
  255,
  jsRound(r.clamp(0.0, 255.0)).toInt(),
  jsRound(g.clamp(0.0, 255.0)).toInt(),
  jsRound(b.clamp(0.0, 255.0)).toInt(),
);

/// The prototype's `mix(a, b, t)`: a per-channel sRGB lerp rounded back to 8
/// bits. Alpha is dropped (inputs are opaque).
Color mixSrgb(Color a, Color b, double t) {
  final x = rgb8(a);
  final y = rgb8(b);
  return _colorOf8(x[0] + (y[0] - x[0]) * t, x[1] + (y[1] - x[1]) * t, x[2] + (y[2] - x[2]) * t);
}

/// [color] moved straight toward black or white until its HSL lightness is
/// inside [min]…[max].
Color holdLightness(Color color, double min, double max) {
  final c = color.withValues(alpha: 1);
  final hi = math.max(c.r, math.max(c.g, c.b));
  final lo = math.min(c.r, math.min(c.g, c.b));
  final lightness = (hi + lo) / 2;
  if (lightness > max) return mixSrgb(c, const Color(0xFF000000), 1 - max / lightness);
  if (lightness < min) return mixSrgb(c, const Color(0xFFFFFFFF), (min - lightness) / (1 - lightness));
  return c;
}

/// The palette's own hue at OKLab [lightness], with as much chroma as sRGB
/// allows up to .075 (prototype `liftHue`, line for line).
Color liftHue(Color base, double lightness) {
  final c = rgb8(base).map((v) => _lin(v.toDouble())).toList();
  final r = c[0], g = c[1], b = c[2];
  final l = _cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  final m = _cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  final s = _cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  final hue = math.atan2(
    0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
  );
  List<double> rgbAt(double chroma) {
    final aa = chroma * math.cos(hue);
    final bb = chroma * math.sin(hue);
    final x = math.pow(lightness + 0.3963377774 * aa + 0.2158037573 * bb, 3).toDouble();
    final y = math.pow(lightness - 0.1055613458 * aa - 0.0638541728 * bb, 3).toDouble();
    final z = math.pow(lightness - 0.0894841775 * aa - 1.2914855480 * bb, 3).toDouble();
    return [
      4.0767416621 * x - 3.3077115913 * y + 0.2309699292 * z,
      -1.2684380046 * x + 2.6097574011 * y - 0.3413193965 * z,
      -0.0041960863 * x - 0.7034186147 * y + 1.7076147010 * z,
    ];
  }

  var lo = 0.0;
  var hi = 0.075;
  for (var i = 0; i < 20; i++) {
    final mid = (lo + hi) / 2;
    if (rgbAt(mid).every((v) => v >= 0 && v <= 1)) {
      lo = mid;
    } else {
      hi = mid;
    }
  }
  final out = rgbAt(lo).map(_gam).toList();
  return _colorOf8(out[0], out[1], out[2]);
}

double _cbrt(double v) => v < 0 ? -math.pow(-v, 1 / 3).toDouble() : math.pow(v, 1 / 3).toDouble();

double _lin(double v8) {
  final v = v8 / 255.0;
  return v <= 0.04045 ? v / 12.92 : math.pow((v + 0.055) / 1.055, 2.4).toDouble();
}

double _gam(double v) => 255.0 * (v <= 0.0031308 ? 12.92 * v : 1.055 * math.pow(v, 1 / 2.4) - 0.055);

// ---------------------------------------------------------------------------
// A cover's palette (MemoryCoverPalette.kt).

/// How far (HSL hue degrees) the accent may sit from the seed.
const double memoryAccentHueWindow = 45;

/// Below this HSL saturation a colour has no hue worth matching.
const double memoryAccentGreyBelow = 0.12;

/// The accent's HSL lightness window (the prototype's accents sit at .66–.76).
const double memoryAccentMinLightness = 0.6;
const double memoryAccentMaxLightness = 0.78;

/// With no swatch near the seed's hue, the accent is the seed lifted toward white by this much.
const double memoryAccentLift = 0.45;

/// The accent for [seed]: the first of [candidates] within the seed's hue
/// family (any of them when either is grey), else the seed lifted toward
/// white; then held in the accent lightness window.
Color memoryAccentFor(Color seed, List<Color> candidates) {
  final picked =
      candidates.where((c) => c.toARGB32() != seed.toARGB32() && _sameHueFamily(seed, c)).firstOrNull ??
      mixSrgb(seed, const Color(0xFFFFFFFF), memoryAccentLift);
  return holdLightness(picked, memoryAccentMinLightness, memoryAccentMaxLightness);
}

bool _sameHueFamily(Color a, Color b) {
  final ha = HSLColor.fromColor(a);
  final hb = HSLColor.fromColor(b);
  if (ha.saturation < memoryAccentGreyBelow || hb.saturation < memoryAccentGreyBelow) return true;
  final d = (ha.hue - hb.hue).abs() % 360;
  return math.min(d, 360 - d) <= memoryAccentHueWindow;
}

/// The palette of a cover's extracted colours. HarmonyOS reads covers through
/// Home's [CoverPaletteCache] (Android's 16-colour seed pass, but its base is
/// already toned); [MemoryPalette.fromBackdrop] holds it in the ink window
/// anyway, and the accent is held in the Memories accent window.
MemoryPalette memoryPaletteOf(BackdropColors colors) =>
    MemoryPalette.fromBackdrop(colors.base, memoryAccentFor(colors.base, [colors.accent]));

/// Gives [builder] the [MemoryPalette] of a cover: [MemoryPalette.fallback]
/// (the theme's primary) until the cover is read, then the cover's palette,
/// handed over on the default effects spring. Cached per [cacheKey] (the
/// cover URL) in [CoverPaletteCache]; a failed cover keeps the fallback.
class MemoryPaletteBuilder extends StatefulWidget {
  const MemoryPaletteBuilder({
    super.key,
    required this.cacheKey,
    required this.image,
    required this.builder,
    this.fixture,
    this.cache,
  });

  /// The cover URL (null = no cover: the fallback for good).
  final String? cacheKey;
  final ImageProvider? image;
  final Widget Function(BuildContext context, MemoryPalette palette) builder;

  /// A fixed palette (previews, tests): used as is, nothing is read.
  final MemoryPalette? fixture;
  final CoverPaletteCache? cache;

  @override
  State<MemoryPaletteBuilder> createState() => _MemoryPaletteBuilderState();
}

class _MemoryPaletteBuilderState extends State<MemoryPaletteBuilder> with SingleTickerProviderStateMixin {
  late final SpringValue _t = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);

  /// Seed / accent pairs being handed over, accents already held in the
  /// Memories window (null = the theme fallback).
  BackdropColors? _from;
  BackdropColors? _to;
  String? _requested;

  CoverPaletteCache get _cache => widget.cache ?? CoverPaletteCache.instance;

  @override
  void initState() {
    super.initState();
    _t;
    final key = widget.cacheKey;
    if (key != null && _cache.contains(key)) {
      _to = _held(_cache.peek(key));
      _requested = key;
    } else {
      _request();
    }
  }

  @override
  void didUpdateWidget(MemoryPaletteBuilder oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.cacheKey != widget.cacheKey) {
      _retry.reset();
      _request();
    }
  }

  /// A cover that failed to load is read again when failed artwork is.
  final _retry = ArtworkReadRetry();

  void _request() {
    final key = widget.cacheKey;
    final image = widget.image;
    if (widget.fixture != null || key == null || image == null || key == _requested) return;
    _requested = key;
    _retry.requested();
    _cache
        .colorsFor(
          key,
          image,
          onLoadFailed: (error) {
            if (!mounted || widget.cacheKey != key) return;
            _retry.failed(error, () {
              if (!mounted || widget.cacheKey != key) return;
              _requested = null;
              _request();
            });
          },
        )
        .then((colors) {
          if (mounted && widget.cacheKey == key) _apply(colors);
        });
  }

  void _apply(BackdropColors? colors) {
    final held = _held(colors);
    if (held == null || held == _to) return;
    final current = _current(Theme.of(context).colorScheme.primary);
    setState(() {
      _from = current;
      _to = held;
    });
    _t.snapTo(0);
    _t.animateTo(1);
  }

  /// The seed / accent pair this frame (both already in Memories' windows).
  BackdropColors _current(Color primary) {
    final fallback = BackdropColors(primary, memoryAccentFor(primary, const []));
    final to = _to ?? fallback;
    final from = _from ?? fallback;
    final t = _t.value.clamp(0.0, 1.0);
    return BackdropColors(Color.lerp(from.base, to.base, t)!, Color.lerp(from.accent, to.accent, t)!);
  }

  BackdropColors? _held(BackdropColors? c) =>
      c == null ? null : BackdropColors(c.base, memoryAccentFor(c.base, [c.accent]));

  @override
  void dispose() {
    _retry.reset();
    _t.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final fixture = widget.fixture;
    if (fixture != null) return widget.builder(context, fixture);
    final primary = Theme.of(context).colorScheme.primary;
    return AnimatedBuilder(
      animation: _t.animation,
      builder: (context, _) {
        final c = _current(primary);
        return widget.builder(context, MemoryPalette.fromBackdrop(c.base, c.accent));
      },
    );
  }
}
