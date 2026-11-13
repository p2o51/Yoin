import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:material_color_utilities/material_color_utilities.dart';

import '../theme/color_scheme.dart';
import '../theme/motion.dart';

/// Pixels the seed is read from (decoded small, off the UI isolate).
const _seedDecodeSize = 96;

final _seedCache = <String, Color?>{};
final _seedInFlight = <String, Future<Color?>>{};

/// The SPEC_2025 seed for a cover (Android CoverSeedExtractor): decode the
/// image small, Celebi-quantize, Score; cached by URL. null when the image
/// can't be read.
Future<Color?> coverSeedColor(String url) {
  if (_seedCache.containsKey(url)) return Future.value(_seedCache[url]);
  return _seedInFlight[url] ??= _readSeed(url).then(
    (seed) {
      _seedCache[url] = seed;
      _seedInFlight.remove(url);
      return seed;
    },
    onError: (Object _) {
      _seedInFlight.remove(url);
      return null;
    },
  );
}

Future<Color?> _readSeed(String url) async {
  final provider = ResizeImage(NetworkImage(url), width: _seedDecodeSize, height: _seedDecodeSize);
  final completer = Completer<ui.Image?>();
  final stream = provider.resolve(ImageConfiguration.empty);
  late final ImageStreamListener listener;
  listener = ImageStreamListener(
    (info, _) {
      if (!completer.isCompleted) completer.complete(info.image.clone());
      info.dispose();
      stream.removeListener(listener);
    },
    onError: (_, _) {
      if (!completer.isCompleted) completer.complete(null);
      stream.removeListener(listener);
    },
  );
  stream.addListener(listener);
  final image = await completer.future;
  if (image == null) return null;
  final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
  image.dispose();
  if (data == null) return null;
  final argb = await compute(seedFromRgba, data.buffer.asUint8List());
  return argb == null ? null : Color(argb);
}

/// RGBA bytes → the Score-picked seed ARGB (pure; runs in an isolate).
Future<int?> seedFromRgba(Uint8List rgba) async {
  final pixels = <int>[];
  for (var i = 0; i + 3 < rgba.length; i += 4) {
    final a = rgba[i + 3];
    if (a < 255) continue;
    pixels.add((a << 24) | (rgba[i] << 16) | (rgba[i + 1] << 8) | rgba[i + 2]);
  }
  if (pixels.isEmpty) return null;
  final result = await QuantizerCelebi().quantize(pixels, 128);
  final ranked = Score.score(result.colorToCount, desired: 1);
  return ranked.isEmpty ? null : ranked.first;
}

/// The page colours a detail page draws with (Android DetailPageColors).
@immutable
final class DetailColors {
  const DetailColors({required this.accent, required this.onAccent, required this.wash, required this.ring});

  /// Title, Kind group, equalizer, hearts on: the cover scheme's primary.
  final Color accent;
  final Color onAccent;

  /// The page background's top stop: lerp(surfaceContainer, accent, 0.18).
  final Color wash;

  /// The artist ring's dot (tertiary).
  final Color ring;

  static DetailColors from(ColorScheme scheme, ColorScheme page) => DetailColors(
    accent: scheme.primary,
    onAccent: scheme.onPrimary,
    wash: Color.lerp(page.surfaceContainer, scheme.primary, 0.18)!,
    ring: scheme.tertiary,
  );

  static DetailColors lerp(DetailColors a, DetailColors b, double t) => DetailColors(
    accent: Color.lerp(a.accent, b.accent, t)!,
    onAccent: Color.lerp(a.onAccent, b.onAccent, t)!,
    wash: Color.lerp(a.wash, b.wash, t)!,
    ring: Color.lerp(a.ring, b.ring, t)!,
  );
}

/// Resolves the cover scheme for [coverUrl] and animates the page colours
/// to it on the effects spring — leaf widgets listen to
/// [DetailPaletteScope.colorsOf]; the page Theme is never swapped per frame
/// (theme wash invariant). Until the cover resolves the app theme stands in.
class DetailPalette extends StatefulWidget {
  const DetailPalette({super.key, required this.coverUrl, required this.child});

  final String? coverUrl;
  final Widget child;

  @override
  State<DetailPalette> createState() => _DetailPaletteState();
}

class _DetailPaletteState extends State<DetailPalette> with TickerProviderStateMixin {
  late final SpringValue _t = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  final _colors = ValueNotifier<DetailColors?>(null);
  DetailColors? _from;
  DetailColors? _to;
  Color? _seed;
  ColorScheme? _scheme;

  @override
  void initState() {
    super.initState();
    _t.animation.addListener(_tick);
    _resolve();
  }

  @override
  void didUpdateWidget(DetailPalette old) {
    super.didUpdateWidget(old);
    if (old.coverUrl != widget.coverUrl) _resolve();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _retarget(animate: _to != null);
  }

  void _resolve() {
    final url = widget.coverUrl;
    if (url == null) return;
    coverSeedColor(url).then((seed) {
      if (!mounted || seed == null || widget.coverUrl != url) return;
      setState(() {
        _seed = seed;
        _retarget(animate: true);
      });
    });
  }

  void _retarget({required bool animate}) {
    final page = Theme.of(context).colorScheme;
    final seed = _seed;
    final scheme = seed == null ? page : yoinExpressiveScheme(seed, page.brightness);
    _scheme = seed == null ? null : scheme;
    final target = DetailColors.from(scheme, page);
    final current = _colors.value;
    _to = target;
    if (current == null || !animate) {
      _from = target;
      _t.snapTo(1);
      _colors.value = target;
    } else {
      _from = current;
      _t.snapTo(0);
      _t.animateTo(1);
    }
  }

  void _tick() {
    final from = _from, to = _to;
    if (from == null || to == null) return;
    _colors.value = DetailColors.lerp(from, to, _t.value.clamp(0.0, 1.0));
  }

  @override
  void dispose() {
    _t.dispose();
    _colors.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) =>
      DetailPaletteScope(colors: _colors, target: _to, scheme: _scheme, child: widget.child);
}

/// The page colours for a detail subtree.
class DetailPaletteScope extends InheritedWidget {
  const DetailPaletteScope({
    super.key,
    required this.colors,
    required this.target,
    required this.scheme,
    required super.child,
  });

  /// Animated, for leaves that paint with them.
  final ValueListenable<DetailColors?> colors;

  /// Where the animation is heading (the bar's Play colours).
  final DetailColors? target;

  /// The resolved cover scheme, null until the cover's seed is known.
  final ColorScheme? scheme;

  static DetailPaletteScope? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<DetailPaletteScope>();

  /// Animated colours, falling back to the theme outside a palette.
  static ValueListenable<DetailColors?> colorsOf(BuildContext context) =>
      maybeOf(context)?.colors ?? ValueNotifier<DetailColors?>(null);

  @override
  bool updateShouldNotify(DetailPaletteScope old) =>
      old.colors != colors || old.target != target || old.scheme != scheme;
}

/// Builds with the animated page colours (theme fallback when unresolved).
class DetailColorsBuilder extends StatelessWidget {
  const DetailColorsBuilder({super.key, required this.builder});

  final Widget Function(BuildContext context, DetailColors colors) builder;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final fallback = DetailColors.from(scheme, scheme);
    return ValueListenableBuilder<DetailColors?>(
      valueListenable: DetailPaletteScope.colorsOf(context),
      builder: (context, colors, _) => builder(context, colors ?? fallback),
    );
  }
}
