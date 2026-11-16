import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../../theme/motion.dart';
import '../home_metrics.dart';

/// The cold-start staged reveal ('启幕', StagedReveal.kt): three beats 0 → 1
/// on the Expressive slow spatial spring, started 0 / 90 / 170 ms apart, once
/// per process (returning from a detail page, switching tabs or rotating
/// never replays). Reduced motion: no reveal.
///
/// [StagedReveal.once] is a reveal of its own instead (a detail page's
/// first open, Android rememberStagedReveal("album-…") / ("playlist-…")):
/// it plays once for the object, with its own [delays] (the playlist
/// sleeves' 50 / 100 ms), and is settled from the start when [played].
class StagedReveal {
  StagedReveal({required TickerProvider vsync}) : this._(vsync, HomeTimings.stagedRevealDelays, _processLatch);

  StagedReveal.once({
    required TickerProvider vsync,
    List<Duration> delays = HomeTimings.stagedRevealDelays,
    bool played = false,
  }) : this._(vsync, delays, _Latch(played));

  StagedReveal._(TickerProvider vsync, this._delays, this._latch)
      : beats = List.generate(
          _delays.length,
          (_) => SpringValue(vsync: vsync, initial: _latch.played ? 1 : 0, spring: YoinSprings.slowSpatial),
        ) {
    // The delays run on the frame clock (one ticker), so a beat never starts
    // between frames.
    _clock = vsync.createTicker(_onTick);
  }

  /// The process-wide latch (Android rememberSaveable("home-feed")).
  static final _Latch _processLatch = _Latch(false);

  /// Tests reset it between cases (through HomeScreen.resetProcessState).
  static void resetLatch() => _processLatch.played = false;

  static bool get hasPlayed => _processLatch.played;

  final List<Duration> _delays;
  final _Latch _latch;
  final List<SpringValue> beats;
  late final Ticker _clock;
  int _started = 0;

  /// Whether this reveal has started (or was settled from the start).
  bool get played => _latch.played;

  /// Runs the beats once there is something to reveal.
  void start({required bool reducedMotion}) {
    if (_latch.played) return;
    _latch.played = true;
    if (reducedMotion) {
      for (final b in beats) {
        b.snapTo(1);
      }
      return;
    }
    _clock.start();
  }

  void _onTick(Duration elapsed) {
    while (_started < beats.length && elapsed >= _delays[_started]) {
      beats[_started++].animateTo(1);
    }
    if (_started >= beats.length) _clock.stop();
  }

  void dispose() {
    _clock.dispose();
    for (final b in beats) {
      b.dispose();
    }
  }
}

class _Latch {
  _Latch(this.played);

  bool played;
}

/// A beat this close to 1 has landed (the spring settles within 0.0005 of it).
const double stagedBeatLanded = 0.999;

/// One beat applied in paint only: alpha p, rise (1 − p) × [rise], scale lerp.
class StagedBeat extends StatelessWidget {
  const StagedBeat({
    super.key,
    required this.beat,
    required this.rise,
    this.scaleFrom = 1,
    this.alignment = Alignment.topCenter,
    required this.child,
  });

  final Animation<double> beat;
  final double rise;
  final double scaleFrom;

  /// Where the grow-in scales from (a detail page's cover: its centre, as
  /// Android's graphicsLayer pivot).
  final Alignment alignment;
  final Widget child;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: beat,
        child: child,
        builder: (context, child) {
          // One tree shape at every p, so the child never remounts. The
          // spring settles within its tolerance of 1: landed is exactly 1.
          final raw = beat.value;
          final p = raw >= stagedBeatLanded ? 1.0 : raw.clamp(0.0, 1.0);
          final scale = scaleFrom + (1 - scaleFrom) * p;
          return Opacity(
            opacity: p,
            child: Transform(
              alignment: alignment,
              transform: Matrix4.translationValues(0, (1 - p) * rise, 0)..scaleByDouble(scale, scale, 1, 1),
              child: child,
            ),
          );
        },
      );
}

/// A section that appears after the feed is up (a block landing) grows in:
/// height on the Expressive default spatial spring, alpha on the effects
/// spring (Android animateItem). Reduced motion or [animate] false: a cut.
class SectionAppear extends StatefulWidget {
  const SectionAppear({super.key, required this.animate, required this.child});

  final bool animate;
  final Widget child;

  @override
  State<SectionAppear> createState() => _SectionAppearState();
}

class _SectionAppearState extends State<SectionAppear> with TickerProviderStateMixin {
  late final SpringValue _size = SpringValue(vsync: this, initial: widget.animate ? 0 : 1, spring: YoinSprings.defaultSpatial);
  late final SpringValue _alpha = SpringValue(vsync: this, initial: widget.animate ? 0 : 1, spring: YoinSprings.defaultEffects);

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _size;
    _alpha;
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.disableAnimationsOf(context)) {
      _size.snapTo(1);
      _alpha.snapTo(1);
    } else {
      _size.animateTo(1);
      _alpha.animateTo(1);
    }
  }

  @override
  void dispose() {
    _size.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
        animation: Listenable.merge([_size.animation, _alpha.animation]),
        child: widget.child,
        builder: (context, child) {
          final size = _size.value;
          final settled = size >= 1 && _alpha.value >= 1;
          // One tree shape throughout, so the section never remounts.
          return ClipRect(
            clipBehavior: settled ? Clip.none : Clip.hardEdge,
            child: Align(
              alignment: Alignment.topCenter,
              heightFactor: settled ? 1 : size.clamp(0.0, 1.5),
              child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
            ),
          );
        },
      );
}

/// A horizontal shelf whose sides fade (24 dp) only while more content lies
/// beyond them, the fade's alpha springing on the effects spring
/// (horizontalEdgeFadeOnScroll). [fade] false = a plain shelf.
class EdgeFadeShelf extends StatefulWidget {
  const EdgeFadeShelf({super.key, required this.height, required this.padding, required this.itemCount, required this.itemBuilder, this.fade = true, this.gap = HomeShelfMetrics.itemGap, this.scrollable = true});

  final double height;
  final EdgeInsetsDirectional padding;
  final int itemCount;
  final IndexedWidgetBuilder itemBuilder;
  final bool fade;
  final double gap;

  /// False: the cards sit still (side by side, nothing beyond the edge).
  final bool scrollable;

  @override
  State<EdgeFadeShelf> createState() => _EdgeFadeShelfState();
}

class _EdgeFadeShelfState extends State<EdgeFadeShelf> with TickerProviderStateMixin {
  final ScrollController _scroll = ScrollController();
  late final SpringValue _start = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  late final SpringValue _end = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  @override
  void initState() {
    super.initState();
    // Create eagerly: a lazy field first read in dispose() would build a ticker on a dead element.
    _start;
    _end;
    _scroll.addListener(_update);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _update();
    });
  }

  void _update() {
    if (!_scroll.hasClients) return;
    final p = _scroll.position;
    if (!p.hasContentDimensions) return;
    _start.animateTo(p.pixels > p.minScrollExtent + 0.5 ? 1 : 0);
    _end.animateTo(p.pixels < p.maxScrollExtent - 0.5 ? 1 : 0);
  }

  @override
  void dispose() {
    _scroll.dispose();
    _start.dispose();
    _end.dispose();
    super.dispose();
  }

  /// smoothstep stops of the fade (0, .104, .352, .648, .896, 1).
  static const _stops = [0.0, 0.104, 0.352, 0.648, 0.896, 1.0];

  static double _smooth(double x) => x * x * (3 - 2 * x);

  @override
  Widget build(BuildContext context) {
    final list = SizedBox(
      height: widget.height,
      child: NotificationListener<ScrollMetricsNotification>(
        onNotification: (_) {
          _update();
          return false;
        },
        child: ListView.separated(
          controller: _scroll,
          scrollDirection: Axis.horizontal,
          padding: widget.padding,
          physics: widget.scrollable ? null : const NeverScrollableScrollPhysics(),
          itemCount: widget.itemCount,
          separatorBuilder: (_, _) => SizedBox(width: widget.gap),
          itemBuilder: widget.itemBuilder,
        ),
      ),
    );
    if (!widget.fade) return list;
    final rtl = Directionality.of(context) == TextDirection.rtl;
    return AnimatedBuilder(
      animation: Listenable.merge([_start.animation, _end.animation]),
      child: list,
      builder: (context, child) {
        final start = _start.value.clamp(0.0, 1.0), end = _end.value.clamp(0.0, 1.0);
        if (start == 0 && end == 0) return child!;
        return ShaderMask(
          blendMode: BlendMode.dstIn,
          shaderCallback: (rect) {
            final w = rect.width <= 0 ? 1.0 : rect.width;
            final f = (HomeShelfMetrics.edgeFade / w).clamp(0.0, 0.5);
            final left = rtl ? end : start, right = rtl ? start : end;
            final colors = <Color>[];
            final stops = <double>[];
            for (final s in _stops) {
              stops.add(s * f);
              colors.add(Color.fromRGBO(255, 255, 255, 1 - left * (1 - _smooth(s))));
            }
            for (final s in _stops.reversed) {
              stops.add(1 - s * f);
              colors.add(Color.fromRGBO(255, 255, 255, 1 - right * (1 - _smooth(s))));
            }
            return LinearGradient(colors: colors, stops: stops).createShader(rect);
          },
          child: child,
        );
      },
    );
  }
}
