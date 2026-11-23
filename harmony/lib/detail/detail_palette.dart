import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:material_color_utilities/material_color_utilities.dart';

import '../data/cache/cover_cache.dart';
import '../data/cache/cover_color_cache.dart';
import '../theme/color_scheme.dart';
import '../theme/motion.dart';
import '../theme/playback_theme.dart' show pickCoverSeed;
import '../ui/component/artwork_image.dart';
import '../ui/component/artwork_retry.dart';

/// Pixels the seed is read from (decoded small, off the UI isolate).
const _seedDecodeSize = 96;
const _seedCacheCapacity = 256;

final _seedCache = <String, Color?>{};
final _seedInFlight = <String, Future<Color?>>{};

void _putSeedMemory(String key, Color? seed) {
  _seedCache.remove(key);
  _seedCache[key] = seed;
  while (_seedCache.length > _seedCacheCapacity) {
    _seedCache.remove(_seedCache.keys.first);
  }
}

/// Records an already-extracted seed (e.g. from Now Playing's [PlaybackTheme])
/// so opening Album Detail for that cover has its colour on Frame 1.
void rememberCoverSeed(String url, Color seed) {
  final key = coverCacheKey(url);
  _putSeedMemory(key, seed);
  CoverColorStore.instance.putSeed(key, seed.toARGB32());
}

/// The SPEC_2025 seed for a cover (Android CoverSeedExtractor): decode the
/// image small, Celebi-quantize, Score; cached by normalized cover key and
/// persisted across launches. null when the image can't be read. A cover that
/// failed to load is not remembered: [onLoadFailed] hears why, and the next
/// call reads it again.
Future<Color?> coverSeedColor(String url, {ValueChanged<Object>? onLoadFailed}) {
  final key = coverCacheKey(url);
  if (_seedCache.containsKey(key)) {
    final hit = _seedCache.remove(key);
    _seedCache[key] = hit;
    return SynchronousFuture(hit);
  }
  final stored = CoverColorStore.instance.get(key)?.seedArgb;
  if (stored != null) {
    final color = Color(stored);
    _putSeedMemory(key, color);
    return SynchronousFuture(color);
  }
  final read = _seedInFlight[key] ??= _readSeed(url).then(
    (seed) {
      _putSeedMemory(key, seed);
      if (seed != null) CoverColorStore.instance.putSeed(key, seed.toARGB32());
      _seedInFlight.remove(key);
      return seed;
    },
    onError: (Object error) {
      _seedInFlight.remove(key);
      throw error;
    },
  );
  return read.then<Color?>(
    (seed) => seed,
    onError: (Object error) {
      onLoadFailed?.call(error);
      return null;
    },
  );
}

/// The seed for [url] when it has been read already (no wait, no frame with
/// the app's colours first); null otherwise.
Color? cachedCoverSeed(String url) {
  final key = coverCacheKey(url);
  if (_seedCache.containsKey(key)) {
    final hit = _seedCache.remove(key);
    _seedCache[key] = hit;
    return hit;
  }
  final stored = CoverColorStore.instance.get(key)?.seedArgb;
  if (stored != null) {
    final color = Color(stored);
    _putSeedMemory(key, color);
    return color;
  }
  return null;
}

Future<Color?> _readSeed(String url) async {
  final provider = artworkImageProvider(url, cacheWidth: _seedDecodeSize, cacheHeight: _seedDecodeSize);
  final completer = Completer<ui.Image?>();
  final stream = provider.resolve(ImageConfiguration.empty);
  late final ImageStreamListener listener;
  listener = ImageStreamListener(
    (info, _) {
      if (!completer.isCompleted) completer.complete(info.image.clone());
      info.dispose();
      stream.removeListener(listener);
    },
    onError: (error, _) {
      if (!completer.isCompleted) completer.completeError(error);
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
  final total = rgba.length ~/ 4;
  if (total == 0) return null;
  final step = math.max(1, total ~/ 12544);
  final pixels = <int>[];
  for (var p = 0; p < total; p += step) {
    final i = p * 4;
    final a = rgba[i + 3];
    if (a < 255) continue;
    pixels.add((a << 24) | (rgba[i] << 16) | (rgba[i + 1] << 8) | rgba[i + 2]);
  }
  if (pixels.isEmpty) return null;
  final result = await QuantizerCelebi().quantize(pixels, 128);
  return pickCoverSeed(result.colorToCount);
}

/// The page colours a detail page draws with (Android DetailPageColors).
@immutable
final class DetailColors {
  const DetailColors({
    required this.accent,
    required this.onAccent,
    required this.wash,
    required this.ring,
    required this.secondary,
    required this.muted,
  });

  /// Title, Kind group, equalizer, hearts on: the cover scheme's primary.
  final Color accent;
  final Color onAccent;

  /// The page background's top stop: lerp(surfaceContainer, accent, 0.18).
  final Color wash;

  /// The artist ring's dot (tertiary).
  final Color ring;

  /// The album butterfly's second block (the cover scheme's secondary;
  /// the first block is [accent]).
  final Color secondary;

  /// The cover scheme's onSurfaceVariant (the page dots' resting ink).
  final Color muted;

  static DetailColors from(ColorScheme scheme, ColorScheme page) => DetailColors(
    accent: scheme.primary,
    onAccent: scheme.onPrimary,
    wash: Color.lerp(page.surfaceContainer, scheme.primary, 0.18)!,
    ring: scheme.tertiary,
    secondary: scheme.secondary,
    muted: scheme.onSurfaceVariant,
  );

  static DetailColors lerp(DetailColors a, DetailColors b, double t) => DetailColors(
    accent: Color.lerp(a.accent, b.accent, t)!,
    onAccent: Color.lerp(a.onAccent, b.onAccent, t)!,
    wash: Color.lerp(a.wash, b.wash, t)!,
    ring: Color.lerp(a.ring, b.ring, t)!,
    secondary: Color.lerp(a.secondary, b.secondary, t)!,
    muted: Color.lerp(a.muted, b.muted, t)!,
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
    final url = widget.coverUrl;
    if (url != null) _seed = cachedCoverSeed(url);
    _resolve();
  }

  @override
  void didUpdateWidget(DetailPalette old) {
    super.didUpdateWidget(old);
    if (old.coverUrl != widget.coverUrl) {
      _retry.reset();
      final url = widget.coverUrl;
      final cached = url == null ? null : cachedCoverSeed(url);
      if (cached != null) {
        _seed = cached;
        _retarget(animate: _to != null);
      } else {
        _resolve();
      }
    }
  }

  /// A cover that failed to load is read again when failed artwork is.
  final _retry = ArtworkReadRetry();

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _retarget(animate: _to != null);
  }

  void _resolve() {
    final url = widget.coverUrl;
    if (url == null) return;
    if (_seed != null && cachedCoverSeed(url) == _seed) return;
    _retry.requested();
    void failed(Object error) {
      if (!mounted || widget.coverUrl != url) return;
      _retry.failed(error, () {
        if (mounted && widget.coverUrl == url) _resolve();
      });
    }

    coverSeedColor(url, onLoadFailed: failed).then((seed) {
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
    _retry.reset();
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
