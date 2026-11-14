/// Every card's album palette for one opening of Memories (Android
/// MemoryCoverPalette, read through Home's [CoverPaletteCache]): the theme
/// fallback until a cover is read, then its palette, handed over on the
/// default effects spring. The deck, the wash, the dots and the emblems all
/// read the same palette through [MemoryPaletteScope].
library;

import 'package:flutter/material.dart';

import '../home/widgets/cover_palette.dart';
import '../theme/motion.dart';
import 'memories_ui_state.dart';
import 'palette/memory_palette.dart';

class MemoryPalettes extends ChangeNotifier {
  MemoryPalettes({required this.vsync, CoverPaletteCache? cache}) : _cache = cache ?? CoverPaletteCache.instance;

  final TickerProvider vsync;
  final CoverPaletteCache _cache;
  final Map<int, _Entry> _entries = {};
  bool _disposed = false;

  /// [memory]'s palette now; [fallback] (the theme's) until its cover is read.
  MemoryPalette paletteOf(MemoryEntry memory, MemoryPalette fallback) {
    final e = _entries[memory.sessionId];
    if (e == null || e.to == null) return fallback;
    final from = e.from ?? fallback;
    final t = e.t.value.clamp(0.0, 1.0);
    if (t >= 1) return e.to!;
    return MemoryPalette(
      base: Color.lerp(from.base, e.to!.base, t)!,
      accent: Color.lerp(from.accent, e.to!.accent, t)!,
      deep: Color.lerp(from.deep, e.to!.deep, t)!,
      soft: Color.lerp(from.soft, e.to!.soft, t)!,
    );
  }

  /// Reads [memory]'s cover at [url] once (cached across openings).
  void request(MemoryEntry memory, String? url, MemoryPalette fallback) {
    if (url == null || _entries.containsKey(memory.sessionId)) return;
    final entry = _Entry(SpringValue(vsync: vsync, initial: 1, spring: YoinSprings.defaultEffects));
    entry.t.animation.addListener(_notify);
    _entries[memory.sessionId] = entry;
    if (_cache.contains(url)) {
      final colors = _cache.peek(url);
      if (colors != null) entry.to = memoryPaletteOf(colors);
      return;
    }
    final image = ResizeImage(NetworkImage(url), width: 200, height: 200);
    _cache.colorsFor(url, image).then((colors) {
      if (_disposed || colors == null) return;
      entry
        ..from = fallback
        ..to = memoryPaletteOf(colors);
      entry.t.snapTo(0);
      entry.t.animateTo(1);
      _notify();
    });
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    for (final e in _entries.values) {
      e.t.dispose();
    }
    super.dispose();
  }
}

class _Entry {
  _Entry(this.t);
  final SpringValue t;
  MemoryPalette? from;
  MemoryPalette? to;
}

class MemoryPaletteScope extends InheritedNotifier<MemoryPalettes> {
  const MemoryPaletteScope({super.key, required MemoryPalettes palettes, required super.child})
    : super(notifier: palettes);

  static MemoryPalettes? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<MemoryPaletteScope>()?.notifier;
}

/// [memory]'s palette (the theme fallback outside a scope or before its cover is read).
MemoryPalette memoryPaletteFor(BuildContext context, MemoryEntry memory) {
  final fallback = MemoryPalette.fallback(_primary(context));
  return MemoryPaletteScope.maybeOf(context)?.paletteOf(memory, fallback) ?? fallback;
}

/// [memory]'s card / diary tones.
MemoryPaletteTones memoryTonesFor(BuildContext context, MemoryEntry memory, bool dark) =>
    memoryPaletteFor(context, memory).tones(dark);

Color _primary(BuildContext context) => Theme.of(context).colorScheme.primary;
