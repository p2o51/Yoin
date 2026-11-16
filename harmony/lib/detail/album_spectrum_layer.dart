import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import 'album_spectrum.dart';
import 'detail_palette.dart';
import 'pull_up_reveal.dart';

/// The bands land a frame or so apart: one tick per this many ms, never a buzz (SpectrumTickGapMillis).
const _tickGapMs = 22;

/// The cover → spectrum bar's spectrum (AlbumSpectrumLayer): rides the
/// page's one bar progress ([reveal], 0 = cover, 1 = bar: the pull-up, or the
/// Medium overview's scroll) and paints the cover's strips flying from
/// [cover] (carried up by the scroll on Medium) into [bar]. The theme's
/// stand-in spectrum (the cover scheme's primary / secondary / tertiary)
/// hands over to the cover's own on the effects spring — colours never snap.
/// A small tick as each band lands on the way up, and as each leaves again.
/// Everything per frame is read at paint time.
class AlbumSpectrumLayer extends StatefulWidget {
  const AlbumSpectrumLayer({
    super.key,
    required this.reveal,
    required this.coverUrl,
    required this.cover,
    required this.bar,
    this.reduce = false,
  });

  final BarMorph reveal;
  final String? coverUrl;

  /// The resting cover (Medium: at scroll 0) and the header band, in this layer's space.
  final Rect cover;
  final Rect bar;
  final bool reduce;

  @override
  State<AlbumSpectrumLayer> createState() => _AlbumSpectrumLayerState();
}

class _AlbumSpectrumLayerState extends State<AlbumSpectrumLayer> with TickerProviderStateMixin {
  late final SpringValue _swap = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  AlbumSpectrumSource? _loaded;
  AlbumSpectrum? _previous;
  AlbumSpectrumSource? _fallback;
  List<Color>? _fallbackColors;
  int _landed = 0;
  int _lastTickMs = 0;
  final _clock = Stopwatch()..start();

  @override
  void initState() {
    super.initState();
    widget.reveal.addListener(_onReveal);
    _resolve();
  }

  @override
  void didUpdateWidget(AlbumSpectrumLayer old) {
    super.didUpdateWidget(old);
    if (old.reveal != widget.reveal) {
      old.reveal.removeListener(_onReveal);
      widget.reveal.addListener(_onReveal);
    }
    if (old.coverUrl != widget.coverUrl) _resolve();
  }

  @override
  void dispose() {
    widget.reveal.removeListener(_onReveal);
    _swap.dispose();
    super.dispose();
  }

  void _resolve() {
    final url = widget.coverUrl;
    if (url == null) {
      _loaded = null;
      return;
    }
    final cached = cachedAlbumSpectrumSource(url);
    if (cached != null) {
      _loaded = cached;
      return;
    }
    albumSpectrumSource(url).then((source) {
      if (!mounted || source == null || widget.coverUrl != url) return;
      setState(() {
        _previous = _shown.spectrum;
        _loaded = source;
      });
      if (widget.reduce) {
        _swap.snapTo(1);
      } else {
        _swap.snapTo(0);
        _swap.animateTo(1);
      }
    });
  }

  AlbumSpectrumSource get _shown => _loaded ?? _fallback ?? AlbumSpectrumSource(AlbumSpectrum.fallback(const []), null);

  void _onReveal() {
    if (widget.reduce) return;
    final now = albumSpectrumLanded(_shown.spectrum, widget.reveal.barProgress);
    if (now != _landed) {
      final at = _clock.elapsedMilliseconds;
      if (at - _lastTickMs >= _tickGapMs) {
        YoinHaptics.frequentTick();
        _lastTickMs = at;
      }
      _landed = now;
    }
  }

  @override
  Widget build(BuildContext context) {
    final target = DetailPaletteScope.maybeOf(context)?.target;
    final scheme = Theme.of(context).colorScheme;
    final colors = target == null
        ? [scheme.primary, scheme.secondary, scheme.tertiary]
        : [target.accent, target.secondary, target.ring];
    if (!listEquals(colors, _fallbackColors)) {
      _fallbackColors = colors;
      _fallback = AlbumSpectrumSource(AlbumSpectrum.fallback(colors), null);
    }
    return IgnorePointer(
      child: RepaintBoundary(
        child: CustomPaint(
          painter: _SpectrumPainter(
            repaint: Listenable.merge([widget.reveal, _swap.animation]),
            reveal: widget.reveal,
            source: _shown,
            previous: _previous,
            swap: () => _swap.value.clamp(0.0, 1.0),
            cover: widget.cover,
            bar: widget.bar,
            reduce: widget.reduce,
            rtl: Directionality.of(context) == TextDirection.rtl,
          ),
          size: Size.infinite,
        ),
      ),
    );
  }
}

class _SpectrumPainter extends CustomPainter {
  _SpectrumPainter({
    required Listenable repaint,
    required this.reveal,
    required this.source,
    required this.previous,
    required this.swap,
    required this.cover,
    required this.bar,
    required this.reduce,
    required this.rtl,
  }) : super(repaint: repaint);

  final BarMorph reveal;
  final AlbumSpectrumSource source;
  final AlbumSpectrum? previous;
  final double Function() swap;
  final Rect cover;
  final Rect bar;
  final bool reduce;
  final bool rtl;

  @override
  void paint(Canvas canvas, Size size) {
    // The strips' 1.5px overdraw never leaves the layer: slid off with page 0, nothing of the bar stays
    // along the next page's edge.
    canvas.clipRect(Offset.zero & size);
    final t = swap();
    paintAlbumSpectrum(
      canvas,
      source: source,
      cover: cover.translate(0, -reveal.coverLift),
      bar: bar,
      progress: reveal.barProgress.clamp(0.0, 1.0),
      previous: t >= 1 ? null : previous,
      swap: t,
      reduced: reduce,
      rtl: rtl,
    );
  }

  @override
  bool shouldRepaint(_SpectrumPainter old) =>
      old.source != source ||
      old.previous != previous ||
      old.cover != cover ||
      old.bar != bar ||
      old.reduce != reduce ||
      old.rtl != rtl ||
      old.reveal != reveal;
}
