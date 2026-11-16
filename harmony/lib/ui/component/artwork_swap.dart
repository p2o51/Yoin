/// How a cover comes in and changes (Android ArtworkSwap.kt, and
/// ExpressiveComponents.kt ExpressiveMediaArtwork for [ArtworkContent]).
///
/// - The old cover stays drawn under the new one until the new one has fully
///   revealed; then it goes.
/// - [ArtworkReveal.crossfade] (every cover but Now Playing's) fades the new
///   one in on the default effects spring; [ArtworkReveal.dotDissolve] (Now
///   Playing's cover) prints it in through the screen-print halftone
///   ([ArtworkHalftone]) on the slow artworkDissolve spring, entering from the
///   side the song came from.
/// - The first image snaps in (there is nothing under it to reveal from),
///   except over the fallback icon after a failure or a late url, where it
///   reveals so a recovery never cuts. A crossfaded image that comes from the
///   memory cache in the same frame snaps in too.
/// - A change that interrupts a reveal freezes the half-revealed cover where
///   it is and reveals the next one over it.
library;

import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../../data/cache/cover_cache.dart';
import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import 'artwork_halftone.dart';
import 'artwork_image.dart';
import 'artwork_retry.dart';

enum ArtworkReveal { crossfade, dotDissolve }

/// The layer stack behind [ArtworkSwap], kept pure so it can be unit tested.
///
/// Layers are drawn bottom to top. A new image goes on top; once the top layer
/// is fully revealed every layer under it is dropped. A change that arrives
/// mid-reveal freezes the half-revealed layer and stacks the new one over it;
/// a layer that hasn't loaded yet has nothing to freeze and goes.
class ArtworkSwapLayers {
  /// The most layers kept: the settled base, one frozen half-reveal and the
  /// one revealing now. A fourth drops the bottom one.
  static const maxLayers = 3;

  final List<ArtworkSwapLayer> _layers = [];
  final Set<int> _loaded = {};
  int _nextId = 0;

  List<ArtworkSwapLayer> get layers => List.unmodifiable(_layers);
  ArtworkSwapLayer? get top => _layers.isEmpty ? null : _layers.last;

  bool isLoaded(int id) => _loaded.contains(id);

  /// Puts [key] on top unless it already is. Returns the new layer (null when
  /// nothing new goes on) and the layers that went.
  ({ArtworkSwapLayer? layer, List<ArtworkSwapLayer> dropped}) push(String key) {
    final dropped = <ArtworkSwapLayer>[];
    if (top?.key == key) return (layer: null, dropped: dropped);
    while (_layers.isNotEmpty && !_loaded.contains(_layers.last.id)) {
      dropped.add(_layers.removeLast());
    }
    if (top?.key == key) return (layer: null, dropped: dropped);
    final layer = ArtworkSwapLayer(id: _nextId++, key: key);
    _layers.add(layer);
    while (_layers.length > maxLayers) {
      dropped.add(_layers.removeAt(0));
    }
    for (final l in dropped) {
      _loaded.remove(l.id);
    }
    return (layer: layer, dropped: dropped);
  }

  /// [id] has its image. Returns whether a loaded image sits under it.
  bool loaded(int id) {
    _loaded.add(id);
    final i = _layers.indexWhere((l) => l.id == id);
    return i > 0 && _layers.take(i).any((l) => _loaded.contains(l.id));
  }

  /// [id] is fully shown: drop everything under it. Returns the dropped layers.
  List<ArtworkSwapLayer> revealed(int id) {
    final i = _layers.indexWhere((l) => l.id == id);
    if (i <= 0) return const [];
    final dropped = _layers.sublist(0, i);
    _layers.removeRange(0, i);
    for (final l in dropped) {
      _loaded.remove(l.id);
    }
    return dropped;
  }

  /// Whether a just-loaded image snaps in instead of revealing (ArtworkSwap.kt):
  /// the first image (nothing under it), and a crossfaded memory-cache hit —
  /// unless it comes in over the fallback icon ([revealFirstLoad]).
  static bool revealsInstantly({
    required ArtworkReveal reveal,
    required bool synchronous,
    required bool hasBase,
    bool revealFirstLoad = false,
  }) => !revealFirstLoad && (!hasBase || (reveal == ArtworkReveal.crossfade && synchronous));
}

@immutable
class ArtworkSwapLayer {
  const ArtworkSwapLayer({required this.id, required this.key});

  final int id;

  /// The url and decode size.
  final String key;
}

/// The default spring of [reveal]: Now Playing's slow artworkDissolve for the
/// halftone, the default effects spring for a crossfade.
SpringDescription artworkRevealSpring(ArtworkReveal reveal) => switch (reveal) {
  ArtworkReveal.dotDissolve => YoinSprings.of(MotionTokens.artworkDissolve),
  ArtworkReveal.crossfade => YoinSprings.defaultEffects,
};

/// Shows [url]'s image filling its box ([BoxFit.cover]) and swaps to the next
/// url as the library comment describes. Draws nothing until an image has
/// loaded; [onError] reports a failed load (the caller shows its fallback and
/// retries), [onRevealed] each image that has fully come in, [onShown] the
/// first image this widget draws.
class ArtworkSwap extends StatefulWidget {
  const ArtworkSwap({
    super.key,
    required this.url,
    this.decodeWidth,
    this.reveal = ArtworkReveal.crossfade,
    this.direction = 1,
    this.spring,
    this.revealFirstLoad = false,
    this.onRevealed,
    this.onError,
    this.onShown,
  });

  final String url;

  /// The px the image is decoded at (null: as served).
  final int? decodeWidth;
  final ArtworkReveal reveal;

  /// +1: a skip forward (the halftone enters from the right), −1: back. Each
  /// image keeps the direction it arrived with.
  final int direction;

  /// The reveal's spring; [artworkRevealSpring] by default.
  final SpringDescription? spring;

  /// A retry after a failure, or a url that arrived after the fallback icon:
  /// the first image reveals over the fallback instead of snapping in.
  final bool revealFirstLoad;
  final VoidCallback? onRevealed;
  final ValueChanged<Object>? onError;
  final VoidCallback? onShown;

  @override
  State<ArtworkSwap> createState() => _ArtworkSwapState();
}

class _LiveLayer {
  _LiveLayer({required this.direction, required this.progress});

  final int direction;
  final SpringValue progress;
  ImageStream? stream;
  ImageStreamListener? listener;
  ImageInfo? info;

  /// The reveal and halftone colours a frozen layer was stopped with.
  ArtworkReveal? frozenReveal;
  Color? frozenPrimary;
  Color? frozenTertiary;

  void dispose() {
    final l = listener;
    if (l != null) stream?.removeListener(l);
    listener = null;
    info?.dispose();
    info = null;
    progress.dispose();
  }
}

class _ArtworkSwapState extends State<ArtworkSwap> with TickerProviderStateMixin {
  final _model = ArtworkSwapLayers();
  final _live = <int, _LiveLayer>{};
  final _halftone = ArtworkHalftone();
  bool _shown = false;
  bool _pruneScheduled = false;
  Color _primary = const Color(0x00000000);
  Color _tertiary = const Color(0x00000000);

  /// A layer's key: the cover (its [coverCacheKey], so the same cover signed
  /// again by a rebuilt source is no swap) and its decode size.
  static String _keyOf(String url, int? width) => '${coverCacheKey(url)}#$width';

  @override
  void initState() {
    super.initState();
    _push();
  }

  @override
  void didUpdateWidget(ArtworkSwap oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.url != widget.url || oldWidget.decodeWidth != widget.decodeWidth) _push();
  }

  @override
  void dispose() {
    for (final l in _live.values) {
      l.dispose();
    }
    _live.clear();
    super.dispose();
  }

  void _push() {
    final previousTop = _model.top;
    final (:layer, :dropped) = _model.push(_keyOf(widget.url, widget.decodeWidth));
    for (final l in dropped) {
      _live.remove(l.id)?.dispose();
    }
    if (layer == null) {
      // Back to the cover that was revealing before a skip froze it (the
      // skip's own cover never loaded and went): it carries on revealing.
      final top = _model.top;
      final resumed = top == null ? null : _live[top.id];
      if (resumed != null && resumed.frozenReveal != null) {
        resumed
          ..frozenReveal = null
          ..frozenPrimary = null
          ..frozenTertiary = null;
        resumed.progress.animateTo(1);
      }
      return;
    }
    // Freeze a half-revealed cover where it is, with the colours it had.
    final frozen = previousTop == null ? null : _live[previousTop.id];
    if (frozen != null && frozen.info != null && frozen.progress.value < _revealedAt) {
      frozen.progress.snapTo(frozen.progress.value);
      frozen
        ..frozenReveal = widget.reveal
        ..frozenPrimary = _primary
        ..frozenTertiary = _tertiary;
    }
    final live = _LiveLayer(
      // A later skip can change direction while this image is still loading:
      // the image keeps the direction it arrived with.
      direction: widget.direction,
      progress: SpringValue(vsync: this, initial: 0, spring: widget.spring ?? artworkRevealSpring(widget.reveal)),
    );
    _live[layer.id] = live;
    live.progress.animation.addListener(() {
      if (live.progress.value >= _revealedAt) _onRevealed(layer.id);
    });
    final stream = artworkImageProvider(widget.url, cacheWidth: widget.decodeWidth).resolve(ImageConfiguration.empty);
    final listener = ImageStreamListener(
      (info, synchronous) => _onImage(layer.id, info, synchronous),
      onError: (error, _) => _onError(layer.id, error),
    );
    live
      ..stream = stream
      ..listener = listener;
    stream.addListener(listener);
  }

  /// Springs end within their tolerance, a hair under 1.
  static const double _revealedAt = 0.999;

  void _onImage(int id, ImageInfo info, bool synchronous) {
    final live = _live[id];
    if (live == null) {
      info.dispose();
      return;
    }
    final again = live.info != null;
    live.info?.dispose();
    live.info = info;
    if (again) {
      // A later frame of the same image (an animated cover): just repaint.
      if (mounted) setState(() {});
      return;
    }
    final hasBase = _model.loaded(id);
    final instant = ArtworkSwapLayers.revealsInstantly(
      reveal: widget.reveal,
      synchronous: synchronous,
      hasBase: hasBase,
      revealFirstLoad: widget.revealFirstLoad,
    );
    // Reaching 1 reports the reveal (the progress listener).
    if (instant) {
      live.progress.snapTo(1);
    } else {
      live.progress.animateTo(1);
    }
    if (!_shown) {
      _shown = true;
      final onShown = widget.onShown;
      if (onShown != null) _later(onShown);
    }
    // Called from inside resolve() when the image was in memory: setState is
    // still allowed (this element is building), it only marks it dirty again.
    if (mounted && !synchronous) setState(() {});
  }

  void _onError(int id, Object error) {
    if (_model.top?.id != id) return;
    final onError = widget.onError;
    if (onError != null) _later(() => onError(error));
  }

  void _onRevealed(int id) {
    if (_model.top?.id != id) return;
    final onRevealed = widget.onRevealed;
    if (onRevealed != null) _later(onRevealed);
    if (_pruneScheduled) return;
    _pruneScheduled = true;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      _pruneScheduled = false;
      if (!mounted) return;
      final top = _model.top;
      if (top == null || (_live[top.id]?.progress.value ?? 0) < _revealedAt) return;
      final dropped = _model.revealed(top.id);
      if (dropped.isEmpty) return;
      setState(() {
        for (final l in dropped) {
          _live.remove(l.id)?.dispose();
        }
      });
    });
  }

  /// Calls back after the current frame's work: a load can land inside
  /// resolve() during build, where the caller can't setState.
  void _later(VoidCallback callback) => scheduleMicrotask(() {
    if (mounted) callback();
  });

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    _primary = colors.primary;
    _tertiary = colors.tertiary;
    // Reduced motion: the cover fades in place instead of a front travelling across.
    final reveal = MediaQuery.maybeDisableAnimationsOf(context) ?? false ? ArtworkReveal.crossfade : widget.reveal;
    final layers = [
      for (final l in _model.layers)
        if (_live[l.id] case final live? when live.info != null)
          _PaintLayer(
            image: live.info!.image,
            progress: live.progress.animation,
            direction: live.direction,
            reveal: live.frozenReveal ?? reveal,
            primary: live.frozenPrimary ?? _primary,
            tertiary: live.frozenTertiary ?? _tertiary,
          ),
    ];
    return SizedBox.expand(
      child: CustomPaint(
        painter: _ArtworkSwapPainter(
          layers: layers,
          halftone: _halftone,
          pixelRatio: MediaQuery.maybeDevicePixelRatioOf(context) ?? 1,
        ),
      ),
    );
  }
}

@immutable
class _PaintLayer {
  const _PaintLayer({
    required this.image,
    required this.progress,
    required this.direction,
    required this.reveal,
    required this.primary,
    required this.tertiary,
  });

  final ui.Image image;
  final Animation<double> progress;
  final int direction;
  final ArtworkReveal reveal;
  final Color primary;
  final Color tertiary;
}

class _ArtworkSwapPainter extends CustomPainter {
  _ArtworkSwapPainter({required this.layers, required this.halftone, required this.pixelRatio})
    : super(repaint: Listenable.merge([for (final l in layers) l.progress]));

  final List<_PaintLayer> layers;
  final ArtworkHalftone halftone;
  final double pixelRatio;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    for (final layer in layers) {
      final amount = layer.progress.value.clamp(0.0, 1.0);
      if (amount <= 0) continue;
      void image(Canvas c, double opacity) => paintImage(
        canvas: c,
        rect: rect,
        image: layer.image,
        fit: BoxFit.cover,
        filterQuality: FilterQuality.medium,
        isAntiAlias: true,
        opacity: opacity,
      );
      if (amount >= _ArtworkSwapState._revealedAt) {
        image(canvas, 1);
      } else if (layer.reveal == ArtworkReveal.crossfade) {
        image(canvas, amount);
      } else {
        halftone.layout(
          size,
          amount,
          layer.direction,
          pitch: ArtworkTokens.halftonePitch,
          stripOverlap: 1 / pixelRatio,
        );
        halftone.paint(
          canvas,
          size,
          primary: layer.primary,
          tertiary: layer.tertiary,
          content: (c) => image(c, ArtworkHalftone.opacity(amount)),
        );
      }
    }
  }

  @override
  bool shouldRepaint(_ArtworkSwapPainter old) => !listEquals(old.layers, layers) || old.pixelRatio != pixelRatio;
}

/// The type icon a missing or failed cover shows ([ArtworkTokens.fallbackIcon]
/// on onSurfaceVariant), centred.
class ArtworkFallbackIcon extends StatelessWidget {
  const ArtworkFallbackIcon(this.icon, {super.key, this.size = ArtworkTokens.fallbackIcon});

  final IconData icon;
  final double size;

  @override
  Widget build(BuildContext context) => Center(
    child: Icon(icon, size: size, color: Theme.of(context).colorScheme.onSurfaceVariant),
  );
}

/// A cover's content inside its box (ExpressiveMediaArtwork without the
/// container): [fallback] (the type icon) for no url, and under a failed
/// cover while it retries — on a backoff for transient failures, and whenever
/// [ArtworkRetrySignal] moves — until a retried image has fully revealed over
/// it. A url that arrives after the fallback was shown (a cover resolved late)
/// reveals over it the same way. [underlay] (Now Playing: the pill's decode,
/// already in memory) is drawn under the first image until that one shows.
class ArtworkContent extends StatefulWidget {
  const ArtworkContent({
    super.key,
    required this.url,
    this.fallback,
    this.underlay,
    this.decodeWidth,
    this.reveal = ArtworkReveal.crossfade,
    this.direction = 1,
    this.spring,
  });

  /// The cover's url; null or blank is no artwork.
  final String? url;
  final Widget? fallback;
  final Widget? underlay;
  final int? decodeWidth;
  final ArtworkReveal reveal;
  final int direction;
  final SpringDescription? spring;

  @override
  State<ArtworkContent> createState() => _ArtworkContentState();
}

class _ArtworkContentState extends State<ArtworkContent> {
  late ArtworkRetryState _retry;
  ArtworkRetryWait? _wait;
  bool _shown = false;

  String? get _url {
    final url = widget.url;
    return url == null || url.trim().isEmpty ? null : url;
  }

  /// Whether [url] is still this artwork's cover (signed with any salt).
  bool _isCurrent(String url) {
    final current = _url;
    return current != null && coverCacheKey(current) == coverCacheKey(url);
  }

  @override
  void initState() {
    super.initState();
    _retry = ArtworkRetryState(requestSignal: ArtworkRetrySignal.generation.value);
  }

  @override
  void didUpdateWidget(ArtworkContent oldWidget) {
    super.didUpdateWidget(oldWidget);
    final old = oldWidget.url == null || oldWidget.url!.trim().isEmpty ? null : oldWidget.url;
    // The same cover signed again (a rebuilt source's new salt) keeps its state.
    if (old == _url || (old != null && _url != null && coverCacheKey(old) == coverCacheKey(_url!))) return;
    _wait?.cancel();
    _wait = null;
    _retry = ArtworkRetryState(
      requestSignal: ArtworkRetrySignal.generation.value,
      fallbackUnder: _url != null && old == null,
    );
  }

  @override
  void dispose() {
    _wait?.cancel();
    super.dispose();
  }

  void _failed(String url, Object error) {
    // A failure that lands after the url moved on belongs to the old cover.
    if (!mounted || !_isCurrent(url)) return;
    setState(() => _retry = _retry.failed(error));
    _wait?.cancel();
    _wait = ArtworkRetryWait(_retry, (next) {
      if (!mounted || !_isCurrent(url)) return;
      setState(() => _retry = next);
    });
  }

  @override
  Widget build(BuildContext context) {
    final url = _url;
    final fallback = widget.fallback;
    final underlay = widget.underlay;
    return Stack(
      fit: StackFit.expand,
      children: [
        if (underlay != null && !_shown && url != null) underlay,
        if (fallback != null && (url == null || _retry.fallbackUnder)) fallback,
        if (url != null && _retry.requesting)
          ArtworkSwap(
            url: url,
            decodeWidth: widget.decodeWidth,
            reveal: widget.reveal,
            direction: widget.direction,
            spring: widget.spring,
            revealFirstLoad: _retry.fallbackUnder,
            onError: (error) => _failed(url, error),
            onRevealed: () {
              if (_retry.fallbackUnder) setState(() => _retry = _retry.revealed());
            },
            onShown: () {
              if (!_shown) setState(() => _shown = true);
            },
          ),
      ],
    );
  }
}
