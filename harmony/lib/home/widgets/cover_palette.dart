import 'dart:async';
import 'dart:collection';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../../data/cache/cover_cache.dart';
import '../../data/cache/cover_color_cache.dart';
import '../../theme/motion.dart';
import '../../ui/component/artwork_retry.dart';

/// A cover's backdrop colours (Android ExpressiveBackdropPalette BackdropColors).
@immutable
final class BackdropColors {
  const BackdropColors(this.base, this.accent);
  final Color base;
  final Color accent;

  @override
  bool operator ==(Object other) => other is BackdropColors && other.base == base && other.accent == accent;
  @override
  int get hashCode => Object.hash(base, accent);
}

/// Extracts and caches backdrop colours per cover (keyed by the cover URL).
/// Decoding reuses the image the card shows (same provider key, so the image
/// cache serves it); quantising runs off the UI thread, at most
/// [PalettePasses.maxRunning] at once.
///
/// [capacity]: an entry is two colours, so the cache holds a big Library's
/// whole shelf (Android BackdropPaletteCacheSize 1024, PR #343): scrolling
/// back over covers already read never reads them again.
///
/// TODO(harmony): Now Playing needs the same extractor (CoverSeedExtractor);
/// move this to lib/ui once that area agrees on one service.
class CoverPaletteCache {
  CoverPaletteCache({this.capacity = 1024, CoverColorStore? persistent}) : _persistent = persistent;

  static final CoverPaletteCache instance = CoverPaletteCache();

  final int capacity;
  final CoverColorStore? _persistent;
  final LinkedHashMap<String, BackdropColors?> _entries = LinkedHashMap();
  final Map<String, Future<BackdropColors?>> _inFlight = {};

  CoverColorStore? get _disk => _persistent ?? (identical(this, instance) ? CoverColorStore.instance : null);

  static String _norm(String key) => coverCacheKey(key);

  bool contains(String key) {
    final k = _norm(key);
    if (_entries.containsKey(k)) return true;
    final stored = _disk?.get(k);
    if (stored != null && stored.hasBackdrop) {
      _putMemory(k, BackdropColors(Color(stored.baseArgb!), Color(stored.accentArgb!)));
      return true;
    }
    return false;
  }

  BackdropColors? peek(String key) {
    final k = _norm(key);
    if (_entries.containsKey(k)) {
      final value = _entries.remove(k);
      _entries[k] = value;
      return value;
    }
    final stored = _disk?.get(k);
    if (stored != null && stored.hasBackdrop) {
      final colors = BackdropColors(Color(stored.baseArgb!), Color(stored.accentArgb!));
      _putMemory(k, colors);
      return colors;
    }
    return null;
  }

  void _putMemory(String k, BackdropColors? colors) {
    _entries.remove(k);
    _entries[k] = colors;
    while (_entries.length > capacity) {
      _entries.remove(_entries.keys.first);
    }
  }

  /// Colours for [image]; null when it can't be read. A cover with no usable
  /// colours is remembered; one that failed to load is not: [onLoadFailed]
  /// hears why, and the next call reads it again (an [ArtworkReadRetry]
  /// decides when, like failed artwork).
  Future<BackdropColors?> colorsFor(String key, ImageProvider image, {ValueChanged<Object>? onLoadFailed}) {
    final k = _norm(key);
    if (contains(k)) return SynchronousFuture(peek(k));
    final read = _inFlight[k] ??= _extract(image).then(
      (colors) {
        _inFlight.remove(k);
        _putMemory(k, colors);
        if (colors != null) {
          _disk?.putBackdrop(k, colors.base.toARGB32(), colors.accent.toARGB32());
        }
        return colors;
      },
      onError: (Object error) {
        _inFlight.remove(k);
        throw error;
      },
    );
    return read.then<BackdropColors?>(
      (colors) => colors,
      onError: (Object error) {
        onLoadFailed?.call(error);
        return null;
      },
    );
  }

  static Future<BackdropColors?> _extract(ImageProvider provider) async {
    final completer = Completer<ui.Image?>();
    final stream = provider.resolve(ImageConfiguration.empty);
    late final ImageStreamListener listener;
    listener = ImageStreamListener(
      (info, _) {
        if (!completer.isCompleted) completer.complete(info.image.clone());
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
    try {
      // Never more than two passes at once: a Library grid asks for a screenful of covers the moment a
      // fling settles (Android BackdropPaletteDispatcher, limitedParallelism(2)).
      return await PalettePasses.instance.run(() async {
        final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
        if (data == null) return null;
        final result = await compute(extractBackdropArgb, data.buffer.asUint8List());
        return result == null ? null : BackdropColors(Color(result.$1), Color(result.$2));
      });
    } catch (_) {
      return null;
    } finally {
      image.dispose();
    }
  }
}

/// Runs palette passes at most [maxRunning] at a time, the rest waiting in order.
class PalettePasses {
  PalettePasses({this.maxRunning = 2});

  static final PalettePasses instance = PalettePasses();

  final int maxRunning;
  int _running = 0;
  final Queue<Completer<void>> _waiting = Queue();

  /// Passes running now (for tests).
  @visibleForTesting
  int get running => _running;

  Future<T> run<T>(Future<T> Function() pass) async {
    if (_running >= maxRunning) {
      final turn = Completer<void>();
      _waiting.add(turn);
      await turn.future;
    } else {
      _running++;
    }
    try {
      return await pass();
    } finally {
      if (_waiting.isNotEmpty) {
        // The slot passes straight to the next pass in line.
        _waiting.removeFirst().complete();
      } else {
        _running--;
      }
    }
  }
}

// ---------------------------------------------------------------------------
// The extractor (pure, isolate-safe). A compact port of Android Palette's
// default pass (16 colours, black/white/I-line filter, vibrant → dominant →
// muted seed) and Yoin's toning (toneBackdropBase / accent).

final class _Swatch {
  _Swatch(this.r, this.g, this.b, this.population);
  double r, g, b;
  int population;
  late final List<double> hsl = _toHsl(r / 255, g / 255, b / 255);

  void absorb(double r2, double g2, double b2, int count) {
    final total = population + count;
    r = (r * population + r2 * count) / total;
    g = (g * population + g2 * count) / total;
    b = (b * population + b2 * count) / total;
    population = total;
  }
}

/// (baseArgb, accentArgb) from raw RGBA bytes, or null when nothing usable.
(int, int)? extractBackdropArgb(Uint8List rgba) {
  final seedPass = _swatches(rgba, maxColors: 16, filtered: true);
  final swatches = seedPass.isNotEmpty ? seedPass : _swatches(rgba, maxColors: 12, filtered: false);
  if (swatches.isEmpty) return null;
  final maxPop = swatches.fold<int>(0, (m, s) => math.max(m, s.population));

  _Swatch? best(double satTarget, double satMin, double satMax, double lTarget, double lMin, double lMax) {
    _Swatch? pick;
    var bestScore = -1.0;
    for (final s in swatches) {
      final sat = s.hsl[1], l = s.hsl[2];
      if (sat < satMin || sat > satMax || l < lMin || l > lMax) continue;
      final score = 0.24 * (1 - (sat - satTarget).abs()) + 0.52 * (1 - (l - lTarget).abs()) + 0.24 * (s.population / maxPop);
      if (score > bestScore) {
        bestScore = score;
        pick = s;
      }
    }
    return pick;
  }

  final vibrant = best(1, 0.35, 1, 0.5, 0.3, 0.7);
  final lightVibrant = best(1, 0.35, 1, 0.74, 0.55, 1);
  final muted = best(0.3, 0, 0.4, 0.5, 0.3, 0.7);
  final lightMuted = best(0.3, 0, 0.4, 0.74, 0.55, 1);
  final dominant = swatches.reduce((a, b) => a.population >= b.population ? a : b);
  final seed = vibrant ?? dominant;

  final baseHsl = [
    seed.hsl[0],
    (seed.hsl[1] * 0.82).clamp(0.18, 0.78),
    (seed.hsl[2] * 0.72 + 0.12).clamp(0.24, 0.62),
  ];
  final base = _fromHsl(baseHsl[0], baseHsl[1], baseHsl[2]);

  bool near(_Swatch s) {
    if (s.hsl[1] < 0.1 || seed.hsl[1] < 0.1) return true; // greys match
    final d = (s.hsl[0] - seed.hsl[0]).abs() % 360;
    return math.min(d, 360 - d) <= 45;
  }

  int accent;
  final match = [lightVibrant, vibrant, lightMuted, muted].whereType<_Swatch>().where(near).firstOrNull;
  if (match != null) {
    accent = _fromHsl(match.hsl[0], (match.hsl[1] * 1.08).clamp(0.24, 0.92), (match.hsl[2] + 0.14).clamp(0.36, 0.82));
  } else {
    accent = _fromHsl(baseHsl[0], baseHsl[1], (baseHsl[2] + 0.18).clamp(0.0, 1.0));
  }
  return (base, accent);
}

List<_Swatch> _swatches(Uint8List rgba, {required int maxColors, required bool filtered}) {
  final pixels = rgba.length ~/ 4;
  if (pixels == 0) return const [];
  final step = math.max(1, pixels ~/ 12000);
  final histogram = <int, List<int>>{}; // 5-bit key → [count, rSum, gSum, bSum]
  for (var p = 0; p < pixels; p += step) {
    final i = p * 4;
    if (rgba[i + 3] < 128) continue;
    final r = rgba[i], g = rgba[i + 1], b = rgba[i + 2];
    if (filtered) {
      final hsl = _toHsl(r / 255, g / 255, b / 255);
      if (hsl[2] <= 0.05 || hsl[2] >= 0.95) continue;
      if (hsl[0] >= 10 && hsl[0] <= 37 && hsl[1] <= 0.82 && hsl[2] > 0.82) continue; // the I-line (skin)
    }
    final key = ((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3);
    final bucket = histogram[key] ??= [0, 0, 0, 0];
    bucket[0]++;
    bucket[1] += r;
    bucket[2] += g;
    bucket[3] += b;
  }
  final buckets = histogram.values.toList()..sort((a, b) => b[0].compareTo(a[0]));
  final out = <_Swatch>[];
  for (final bucket in buckets) {
    final n = bucket[0];
    final r = bucket[1] / n, g = bucket[2] / n, b = bucket[3] / n;
    _Swatch? into;
    var nearest = double.infinity;
    for (final s in out) {
      final d = (s.r - r) * (s.r - r) + (s.g - g) * (s.g - g) + (s.b - b) * (s.b - b);
      if (d < nearest) {
        nearest = d;
        into = s;
      }
    }
    if (into != null && (nearest < 40 * 40 || out.length >= maxColors)) {
      into.absorb(r, g, b, n);
    } else {
      out.add(_Swatch(r, g, b, n));
    }
  }
  return out;
}

List<double> _toHsl(double r, double g, double b) {
  final maxC = math.max(r, math.max(g, b));
  final minC = math.min(r, math.min(g, b));
  final l = (maxC + minC) / 2;
  if (maxC == minC) return [0, 0, l];
  final d = maxC - minC;
  final s = l > 0.5 ? d / (2 - maxC - minC) : d / (maxC + minC);
  double h;
  if (maxC == r) {
    h = ((g - b) / d) % 6;
  } else if (maxC == g) {
    h = (b - r) / d + 2;
  } else {
    h = (r - g) / d + 4;
  }
  return [(h * 60 + 360) % 360, s, l];
}

int _fromHsl(double h, double s, double l) => HSLColor.fromAHSL(1, h % 360, s.clamp(0.0, 1.0), l.clamp(0.0, 1.0)).toColor().toARGB32();

// ---------------------------------------------------------------------------
// The scroll gate and the colour builder.

/// Holds palette work while the feed scrolls and until a little after the
/// first scroll-idle (HomeBackdropPaletteWarmupDelayMillis). Absent = open.
class HomePaletteGate extends InheritedNotifier<ValueNotifier<bool>> {
  const HomePaletteGate({super.key, required ValueNotifier<bool> open, required super.child}) : super(notifier: open);

  static ValueNotifier<bool>? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<HomePaletteGate>()?.notifier;
}

/// Gives [builder] the cover's backdrop base colour (or [fallback] until it
/// resolves), springing between colours on the default effects spring. A
/// changed cover keeps its old colour until the new one resolves.
class BackdropColorBuilder extends StatefulWidget {
  const BackdropColorBuilder({
    super.key,
    required this.cacheKey,
    required this.image,
    required this.fallback,
    required this.builder,
    this.child,
    this.cache,
    this.accent = false,
  });

  /// Builds on the palette's accent tone instead of its base (a score on a dark surface).
  final bool accent;

  /// The cover URL (null = no cover: [fallback] for good).
  final String? cacheKey;
  final ImageProvider? image;
  final Color fallback;
  final Widget Function(BuildContext context, Color base, Widget? child) builder;
  final Widget? child;
  final CoverPaletteCache? cache;

  @override
  State<BackdropColorBuilder> createState() => _BackdropColorBuilderState();
}

class _BackdropColorBuilderState extends State<BackdropColorBuilder> with SingleTickerProviderStateMixin {
  late final SpringValue _t = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  Color? _from;
  Color? _to;
  String? _requested;
  ValueNotifier<bool>? _gate;

  CoverPaletteCache get _cache => widget.cache ?? CoverPaletteCache.instance;

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _t;
    final key = widget.cacheKey;
    if (key != null && _cache.contains(key)) {
      _to = _pick(_cache.peek(key));
      _requested = key;
    }
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final gate = HomePaletteGate.maybeOf(context);
    if (!identical(gate, _gate)) {
      _gate?.removeListener(_maybeRequest);
      _gate = gate?..addListener(_maybeRequest);
    }
    _maybeRequest();
  }

  @override
  void didUpdateWidget(BackdropColorBuilder oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.cacheKey != widget.cacheKey) {
      _retry.reset();
      _maybeRequest();
    }
  }

  /// A cover that failed to load is read again when failed artwork is.
  final _retry = ArtworkReadRetry();

  void _maybeRequest() {
    final key = widget.cacheKey;
    final image = widget.image;
    if (key == null || image == null || key == _requested) return;
    if (_cache.contains(key)) {
      _requested = key;
      _apply(_cache.peek(key));
      return;
    }
    if (!(_gate?.value ?? true)) return;
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
              _maybeRequest();
            });
          },
        )
        .then((colors) {
          if (mounted && widget.cacheKey == key) _apply(colors);
        });
  }

  Color? _pick(BackdropColors? colors) => widget.accent ? colors?.accent : colors?.base;

  void _apply(BackdropColors? colors) {
    final next = _pick(colors);
    if (next == null || next == _to) return;
    setState(() {
      _from = _current;
      _to = next;
    });
    if (_from == null) {
      _t.snapTo(1);
    } else {
      _t.snapTo(0);
      _t.animateTo(1);
    }
  }

  Color get _current {
    final to = _to ?? widget.fallback;
    final from = _from ?? widget.fallback;
    return Color.lerp(from, to, _t.value.clamp(0.0, 1.0))!;
  }

  @override
  void dispose() {
    _gate?.removeListener(_maybeRequest);
    _retry.reset();
    _t.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: _t.animation,
        child: widget.child,
        builder: (context, child) => widget.builder(context, _current, child),
      );
}
