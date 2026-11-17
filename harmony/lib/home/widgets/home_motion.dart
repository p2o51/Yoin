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

/// A horizontal Home shelf. It bleeds to the screen edge and the edge simply
/// cuts it, with no fade, at every size (owner 2026-11-14: 「大屏好像也可以直接
/// 搂到右边」「可参考 recently added」); at rest its first card sits on the page
/// margin ([padding]).
class HomeShelf extends StatelessWidget {
  const HomeShelf({
    super.key,
    required this.height,
    required this.padding,
    required this.itemCount,
    required this.itemBuilder,
    this.gap = HomeShelfMetrics.itemGap,
    this.scrollable = true,
  });

  final double height;
  final EdgeInsetsDirectional padding;
  final int itemCount;
  final IndexedWidgetBuilder itemBuilder;
  final double gap;

  /// False: the cards sit still (side by side, nothing beyond the edge).
  final bool scrollable;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: height,
    child: ListView.separated(
      scrollDirection: Axis.horizontal,
      padding: padding,
      physics: scrollable ? null : const NeverScrollableScrollPhysics(),
      itemCount: itemCount,
      separatorBuilder: (_, _) => SizedBox(width: gap),
      itemBuilder: itemBuilder,
    ),
  );
}
