import 'dart:math' as math;

import 'package:flutter/material.dart';

/// M3 Expressive contained loading indicator (component/YoinLoadingIndicator.kt):
/// a soft polygon that turns and morphs between a 7-lobe "cookie" and a
/// 4-lobe "pill" inside a round container. Flutter ships none, so this
/// paints it. [contained] = secondaryContainer disc with onSecondaryContainer
/// ink; uncontained = primary ink only.
class YoinLoadingIndicator extends StatefulWidget {
  const YoinLoadingIndicator({super.key, this.size = 48, this.contained = true, this.color, this.containerColor});

  final double size;
  final bool contained;
  final Color? color;
  final Color? containerColor;

  @override
  State<YoinLoadingIndicator> createState() => _YoinLoadingIndicatorState();
}

class _YoinLoadingIndicatorState extends State<YoinLoadingIndicator> with SingleTickerProviderStateMixin {
  // A loop, not a transition: the indicator spins for as long as it shows.
  // Made on first use (never under reduced motion): disposing must not create
  // it, since a ticker can't be made once the element is deactivated.
  AnimationController? _loopOrNull;
  AnimationController get _loop =>
      _loopOrNull ??= AnimationController(vsync: this, duration: const Duration(milliseconds: 4550))..repeat();

  @override
  void dispose() {
    _loopOrNull?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final ink = widget.color ?? (widget.contained ? colors.onSecondaryContainer : colors.primary);
    final container = widget.contained ? (widget.containerColor ?? colors.secondaryContainer) : null;
    final reduce = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    return Semantics(
      label: MaterialLocalizations.of(context).refreshIndicatorSemanticLabel,
      child: RepaintBoundary(
        child: SizedBox.square(
          dimension: widget.size,
          child: CustomPaint(
            painter: _MorphPainter(reduce ? const AlwaysStoppedAnimation(0.2) : _loop, ink, container),
          ),
        ),
      ),
    );
  }
}

class _MorphPainter extends CustomPainter {
  _MorphPainter(this.t, this.ink, this.container) : super(repaint: t);

  final Animation<double> t;
  final Color ink;
  final Color? container;

  @override
  void paint(Canvas canvas, Size size) {
    final c = size.center(Offset.zero);
    final r = size.shortestSide / 2;
    if (container != null) canvas.drawCircle(c, r, Paint()..color = container!);
    final v = t.value;
    // Morph back and forth between 7 and 4 lobes; the shape keeps turning.
    final phase = (math.sin(v * math.pi * 2 * 3) + 1) / 2;
    final lobes = 7 - 3 * phase;
    final spin = v * math.pi * 2 * 2;
    final outer = r * (container != null ? 0.62 : 0.9);
    const steps = 96;
    final path = Path();
    for (var i = 0; i <= steps; i++) {
      final a = i / steps * math.pi * 2;
      final wobble = 1 + 0.12 * math.cos(a * lobes.roundToDouble()) * (1 - (lobes - lobes.round()).abs());
      final rr = outer * (0.86 + 0.14 * wobble);
      final p = c + Offset(math.cos(a + spin), math.sin(a + spin)) * rr;
      if (i == 0) {
        path.moveTo(p.dx, p.dy);
      } else {
        path.lineTo(p.dx, p.dy);
      }
    }
    canvas.drawPath(path..close(), Paint()..color = ink);
  }

  @override
  bool shouldRepaint(_MorphPainter old) => old.ink != ink || old.container != container || old.t != t;
}
