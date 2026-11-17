import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../theme/motion.dart';

/// The "now playing" mark (Android's animated equalizer painter): three
/// rounded bars that keep moving while [playing] and sink to dots when
/// paused (the sink rides the effects spring).
class EqualizerGlyph extends StatefulWidget {
  const EqualizerGlyph({super.key, required this.playing, this.size = 16, this.color, this.semanticsLabel});

  final bool playing;
  final double size;
  final Color? color;
  final String? semanticsLabel;

  @override
  State<EqualizerGlyph> createState() => _EqualizerGlyphState();
}

class _EqualizerGlyphState extends State<EqualizerGlyph> with TickerProviderStateMixin {
  // The bars' motion is a loop (owner: the wave must always flow while playing).
  late final AnimationController _loop = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200));
  late final SpringValue _level = SpringValue(
    vsync: this,
    initial: widget.playing ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void initState() {
    super.initState();
    if (widget.playing) _loop.repeat();
  }

  @override
  void didUpdateWidget(EqualizerGlyph old) {
    super.didUpdateWidget(old);
    if (old.playing != widget.playing) {
      _level.animateTo(widget.playing ? 1 : 0);
      widget.playing ? _loop.repeat() : _loop.stop();
    }
  }

  @override
  void dispose() {
    _loop.dispose();
    _level.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final reduce = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    return Semantics(
      label: widget.semanticsLabel,
      child: RepaintBoundary(
        child: SizedBox.square(
          dimension: widget.size,
          child: CustomPaint(
            painter: _BarsPainter(
              reduce ? const AlwaysStoppedAnimation(0.25) : _loop,
              _level.animation,
              widget.color ?? Theme.of(context).colorScheme.primary,
            ),
          ),
        ),
      ),
    );
  }
}

class _BarsPainter extends CustomPainter {
  _BarsPainter(this.t, this.level, this.color) : super(repaint: Listenable.merge([t, level]));

  final Animation<double> t;
  final Animation<double> level;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final barW = size.width / 5;
    final paint = Paint()..color = color;
    final lv = level.value.clamp(0.0, 1.0);
    for (var i = 0; i < 3; i++) {
      final phase = t.value * math.pi * 2 + i * 2.1;
      final h = barW + (size.height - barW) * lv * (0.35 + 0.65 * (math.sin(phase) + 1) / 2);
      final x = barW * (i * 2);
      final rect = Rect.fromLTWH(x, (size.height - h) / 2, barW, h);
      canvas.drawRRect(RRect.fromRectAndRadius(rect, Radius.circular(barW / 2)), paint);
    }
  }

  @override
  bool shouldRepaint(_BarsPainter old) => old.color != color || old.t != t || old.level != level;
}
