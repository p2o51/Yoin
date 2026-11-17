import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

/// Compose basicMarquee for one line of text (MarqueeText.kt): when the text
/// is wider than its box it waits [initialDelay], scrolls one lap at 30 dp/s
/// with a gap of a third of the box, waits [repeatDelay], and repeats
/// forever; when it fits it simply shows. Scrolling is linear by nature
/// (basicMarquee's velocity), driven by a ticker that respects TickerMode and
/// repaints only the text (no rebuild per frame).
class Marquee extends StatefulWidget {
  const Marquee({
    super.key,
    required this.text,
    required this.style,
    this.initialDelay = const Duration(milliseconds: 1500),
    this.repeatDelay = const Duration(milliseconds: 2000),
    this.edgeFade = 0,
    this.enabled = true,
    this.probe,
  });

  final String text;
  final TextStyle style;
  final Duration initialDelay;
  final Duration repeatDelay;

  /// Horizontal fade mask on both edges (the hero title's 28 dp).
  final double edgeFade;

  /// False draws a clipped, still line (idle / error texts use ellipsis).
  final bool enabled;

  /// Mirrors the live scroll, for a proxy that draws this same line (the
  /// shared flight leaves the hero title from where it has scrolled to).
  final MarqueeProbe? probe;

  /// basicMarquee's default velocity.
  static const double velocity = 30;

  @override
  State<Marquee> createState() => _MarqueeState();
}

class _MarqueeState extends State<Marquee> with SingleTickerProviderStateMixin {
  late final Ticker _ticker = createTicker(_onTick);
  late final ValueNotifier<double> _offset = ValueNotifier(0)..addListener(_report);
  double _lap = 0;

  void _report() => widget.probe?.offset = _offset.value;
  Duration? _start;

  /// The line's current scroll, for tests.
  @visibleForTesting
  double get debugOffset => _offset.value;

  @override
  void dispose() {
    widget.probe?.offset = 0;
    _ticker.dispose();
    _offset.dispose();
    super.dispose();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Muted (a hidden flight slot): rest at the start of the line, and run
    // the initial delay again once shown, as a freshly shown line does.
    if (!TickerMode.valuesOf(context).enabled) _restart();
  }

  @override
  void didUpdateWidget(Marquee oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.probe != widget.probe) {
      oldWidget.probe?.offset = 0;
      _report();
    }
    if (oldWidget.text != widget.text || oldWidget.style != widget.style) _restart();
  }

  void _restart() {
    _start = null;
    _offset.value = 0;
  }

  void _onTick(Duration elapsed) {
    if (_lap <= 0) return;
    _start ??= elapsed;
    final t = (elapsed - _start!).inMicroseconds / 1e6;
    final initial = widget.initialDelay.inMicroseconds / 1e6;
    final repeat = widget.repeatDelay.inMicroseconds / 1e6;
    final scroll = _lap / Marquee.velocity;
    if (t < initial) {
      _offset.value = 0;
      return;
    }
    final cycle = scroll + repeat;
    final inCycle = (t - initial) % cycle;
    _offset.value = inCycle < scroll ? inCycle * Marquee.velocity : 0;
  }

  void _sync(bool overflows, double lap) {
    _lap = lap;
    if (overflows && widget.enabled) {
      if (!_ticker.isActive) {
        _start = null;
        _ticker.start();
      }
    } else if (_ticker.isActive) {
      _ticker.stop();
      _offset.value = 0;
    }
  }

  @override
  Widget build(BuildContext context) {
    final direction = Directionality.of(context);
    final scaler = MediaQuery.textScalerOf(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final painter = TextPainter(
          text: TextSpan(text: widget.text, style: widget.style),
          maxLines: 1,
          textDirection: direction,
          textScaler: scaler,
        )..layout();
        final width = constraints.maxWidth;
        final overflows = widget.enabled && width.isFinite && painter.width > width;
        final gap = width.isFinite ? width / 3 : 0.0;
        final lap = painter.width + gap;
        // Ticker start/stop must not happen mid-build of a parent: post-frame.
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _sync(overflows, lap);
        });
        if (!overflows) {
          return Text(
            widget.text,
            style: widget.style,
            maxLines: 1,
            softWrap: false,
            overflow: widget.enabled ? TextOverflow.clip : TextOverflow.ellipsis,
          );
        }
        Widget line = SizedBox(
          width: width,
          height: painter.height,
          child: ClipRect(child: CustomPaint(painter: _MarqueePainter(painter, _offset, lap))),
        );
        if (widget.edgeFade > 0) {
          final stop = (widget.edgeFade / width).clamp(0.0, 0.5);
          line = ShaderMask(
            blendMode: BlendMode.dstIn,
            shaderCallback: (rect) => LinearGradient(
              colors: const [Color(0x00000000), Color(0xFF000000), Color(0xFF000000), Color(0x00000000)],
              stops: [0, stop, 1 - stop, 1],
            ).createShader(rect),
            child: line,
          );
        }
        return Semantics(
          label: widget.text,
          child: ExcludeSemantics(child: line),
        );
      },
    );
  }
}

/// A read-only window on one [Marquee]'s scroll: [offset] is how far the
/// line has scrolled left right now (0 at rest, while muted, or unmounted).
class MarqueeProbe {
  double offset = 0;
}

class _MarqueePainter extends CustomPainter {
  _MarqueePainter(this.text, this.offset, this.lap) : super(repaint: offset);

  final TextPainter text;
  final ValueListenable<double> offset;
  final double lap;

  @override
  void paint(Canvas canvas, Size size) {
    final dx = -offset.value;
    text.paint(canvas, Offset(dx, 0));
    text.paint(canvas, Offset(dx + lap, 0));
  }

  @override
  bool shouldRepaint(_MarqueePainter old) => old.text != text || old.lap != lap;
}
