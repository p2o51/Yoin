import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';

/// The layer stack behind [CoverSwap], kept pure so it can be unit tested.
///
/// Layers are drawn bottom to top. A new URL goes on top at alpha 0; once the
/// top layer is fully revealed every layer under it is dropped. A skip that
/// arrives mid-reveal freezes the half-revealed layer (the widget stops its
/// spring) and stacks the new one over it, so nothing ever cuts.
class CoverSwapLayers {
  /// The most layers kept: the settled base, one frozen half-reveal and the
  /// one revealing now. A fourth drops the bottom one.
  static const maxLayers = 3;

  final List<CoverSwapLayer> _layers = [];
  int _nextId = 0;

  List<CoverSwapLayer> get layers => List.unmodifiable(_layers);
  CoverSwapLayer? get top => _layers.isEmpty ? null : _layers.last;

  /// Puts [url] on top unless it is already the top. Returns the new layer,
  /// or null when nothing changed.
  CoverSwapLayer? push(String url) {
    if (top?.url == url) return null;
    final layer = CoverSwapLayer(id: _nextId++, url: url, hasBase: _layers.isNotEmpty);
    _layers.add(layer);
    while (_layers.length > maxLayers) {
      _layers.removeAt(0);
    }
    return layer;
  }

  /// [id] is fully shown: drop everything under it. Returns the dropped layers.
  List<CoverSwapLayer> revealed(int id) {
    final i = _layers.indexWhere((l) => l.id == id);
    if (i <= 0) return const [];
    final dropped = _layers.sublist(0, i);
    _layers.removeRange(0, i);
    return dropped;
  }

  void clear() => _layers.clear();

  /// Whether [layer]'s first frame snaps in instead of revealing: a memory
  /// cache hit (Correction 3), or the very first image, which has nothing
  /// under it to dissolve from (ArtworkSwap.kt: `settled == null` snaps).
  /// Revealing a first image on the slow NP spring showed the empty cover
  /// box for most of a second right after the player opened.
  static bool revealsInstantly(CoverSwapLayer layer, {required bool synchronous}) => synchronous || !layer.hasBase;
}

@immutable
class CoverSwapLayer {
  const CoverSwapLayer({required this.id, required this.url, required this.hasBase});

  final int id;
  final String url;

  /// Whether an older image sits under this one when it is pushed.
  final bool hasBase;
}

/// A cover that cross-fades old → new on a song change (spec item 7,
/// Correction 3): the old image stays drawn under the new one until the new
/// one is fully revealed, the reveal runs on [spring], and an image that
/// comes from the memory cache snaps in. Now Playing passes the slow
/// artworkDissolve spring; the pill the default effects spring.
///
/// Callers draw their own placeholder when [cover] is null.
class CoverSwap extends ConsumerStatefulWidget {
  const CoverSwap({
    super.key,
    required this.cover,
    required this.size,
    required this.radius,
    required this.spring,
    this.requestSize,
    this.fallbackRequestSize,
  });

  final CoverRef cover;
  final double size;
  final double radius;
  final SpringDescription spring;

  /// Pixel size to ask the server for; defaults to [size] × device pixel ratio.
  final int? requestSize;

  /// Another decode of the same cover that is likely already in memory (Now
  /// Playing passes the pill's). Drawn under the first image until that one
  /// has a frame, so a cold load never shows an empty box where the flying
  /// cover just landed.
  final int? fallbackRequestSize;

  /// The NP cover's spring (1.0 / 45).
  static final artworkDissolve = YoinSprings.of(MotionTokens.artworkDissolve);

  @override
  ConsumerState<CoverSwap> createState() => _CoverSwapState();
}

class _CoverSwapState extends ConsumerState<CoverSwap> with TickerProviderStateMixin {
  final _stack = CoverSwapLayers();
  final _alphas = <int, SpringValue>{};
  int? _cacheWidth;

  /// Some layer has drawn a frame: the fallback is no longer needed.
  bool _shownOnce = false;

  @override
  void dispose() {
    for (final a in _alphas.values) {
      a.dispose();
    }
    super.dispose();
  }

  void _sync(String url, int cacheWidth) {
    _cacheWidth = cacheWidth;
    final previousTop = _stack.top;
    final layer = _stack.push(url);
    if (layer == null) return;
    // Freeze a half-revealed layer where it is.
    final frozen = previousTop == null ? null : _alphas[previousTop.id];
    if (frozen != null && frozen.value < 1) frozen.snapTo(frozen.value);
    final alpha = SpringValue(vsync: this, initial: 0, spring: widget.spring);
    alpha.animation.addListener(() {
      if (alpha.value >= 0.999) _schedulePrune(layer.id);
    });
    _alphas[layer.id] = alpha;
    // A fourth layer pushed the bottom one out: dispose its spring after
    // this frame, once its FadeTransition has unmounted.
    final live = {for (final l in _stack.layers) l.id};
    final gone = [
      for (final id in _alphas.keys)
        if (!live.contains(id)) id,
    ];
    if (gone.isNotEmpty) {
      final springs = [for (final id in gone) _alphas.remove(id)!];
      SchedulerBinding.instance.addPostFrameCallback((_) {
        for (final a in springs) {
          a.dispose();
        }
      });
    }
  }

  bool _pruneScheduled = false;

  void _schedulePrune(int id) {
    if (_pruneScheduled) return;
    _pruneScheduled = true;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      _pruneScheduled = false;
      if (!mounted) return;
      final dropped = _stack.revealed(id);
      if (dropped.isEmpty) return;
      setState(() {
        for (final l in dropped) {
          _alphas.remove(l.id)?.dispose();
        }
      });
    });
  }

  void _onFrame(CoverSwapLayer layer, bool synchronous) {
    final alpha = _alphas[layer.id];
    _shownOnce = true;
    // Only the top layer reveals; a frozen layer under it stays put.
    if (alpha == null || alpha.value >= 1 || _stack.top?.id != layer.id) return;
    if (CoverSwapLayers.revealsInstantly(layer, synchronous: synchronous)) {
      alpha.snapTo(1);
    } else {
      alpha.animateTo(1);
    }
  }

  @override
  Widget build(BuildContext context) {
    final source = ref.watch(activeSourceProvider).value;
    final dpr = MediaQuery.devicePixelRatioOf(context);
    final px = widget.requestSize ?? (widget.size * dpr).round();
    final url = source?.resolveCoverUrl(widget.cover, size: px);
    if (url != null) _sync(url, px);
    final cacheWidth = _cacheWidth ?? px;
    final fallbackPx = widget.fallbackRequestSize;
    final fallbackUrl = _shownOnce || fallbackPx == null || fallbackPx == px
        ? null
        : source?.resolveCoverUrl(widget.cover, size: fallbackPx);
    return SizedBox.square(
      dimension: widget.size,
      child: ClipRSuperellipse(
        borderRadius: BorderRadius.circular(widget.radius),
        child: ColoredBox(
          color: Theme.of(context).colorScheme.surfaceContainerHigh,
          child: Stack(
            fit: StackFit.expand,
            children: [
              if (fallbackUrl != null)
                Image.network(
                  fallbackUrl,
                  fit: BoxFit.cover,
                  cacheWidth: fallbackPx,
                  gaplessPlayback: true,
                  errorBuilder: (_, _, _) => const SizedBox.expand(),
                ),
              for (final layer in _stack.layers)
                KeyedSubtree(
                  key: ValueKey(layer.id),
                  child: FadeTransition(
                    opacity: _alphas[layer.id]!.animation,
                    child: Image.network(
                      layer.url,
                      fit: BoxFit.cover,
                      cacheWidth: cacheWidth,
                      gaplessPlayback: true,
                      errorBuilder: (_, _, _) => const SizedBox.expand(),
                      frameBuilder: (context, child, frame, wasSynchronouslyLoaded) {
                        if (wasSynchronouslyLoaded || frame != null) _onFrame(layer, wasSynchronouslyLoaded);
                        return child;
                      },
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
