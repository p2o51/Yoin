/// The Memories deck's aurora (MemoriesAuroraBackground.kt + MemoriesShowcase
/// DeckAurora): three soft radial blooms in the current memory's palette,
/// each on its own orbit (the parallax gives the field a layered, 3D-ish
/// depth), the hue sliding between the base and accent tones.
///
/// The blooms move with the finger, not the clock (gradient lab, owner
/// 2026-11-03): their orbit is driven by the pager's position, and their two
/// colours are a direct lerp between the cards either side of it, so a swipe
/// turns and re-tints the field frame by frame and a deck at rest draws
/// nothing new. Only the painter repaints (never a rebuild per frame), and it
/// paints nothing once Memories has retreated.
library;

import 'dart:math' as math;

import 'package:flutter/widgets.dart';

import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'palette/memory_palette.dart';

const double _tau = math.pi * 2;

/// The wash's base and accent at deck [position] (page + offset): the two
/// neighbouring palettes, lerped (deckAuroraColors).
(Color, Color) deckAuroraColors(List<MemoryPalette> palettes, double position) {
  if (palettes.isEmpty) return (const Color(0x00000000), const Color(0x00000000));
  final at = position.clamp(0.0, (palettes.length - 1).toDouble());
  final from = at.floor();
  final to = math.min(from + 1, palettes.length - 1);
  final f = at - from;
  return (
    Color.lerp(palettes[from].base, palettes[to].base, f)!,
    Color.lerp(palettes[from].accent, palettes[to].accent, f)!,
  );
}

/// One bloom of the field: a radial gradient from [color] at [alpha] in the
/// centre to clear at [radius].
typedef AuroraBloom = ({Offset center, double radius, Color color, double alpha});

/// The three blooms over a deck of [size] at pager [position], in [base] /
/// [accent], at [presence] (0..1); light or [dark] (screen-blended, a little
/// stronger).
List<AuroraBloom> auroraBlooms({
  required Size size,
  required double position,
  required Color base,
  required Color accent,
  required bool dark,
  required double presence,
}) {
  final w = size.width, h = size.height;
  final maxDim = math.max(w, h);
  final alphaCap = dark ? MemoriesAuroraTokens.alphaDark : MemoriesAuroraTokens.alphaLight;
  final flow = position * MemoriesAuroraTokens.driftPerPage;
  final breath = 0.5 + 0.5 * math.sin(position * MemoriesAuroraTokens.breathPerPage);
  final colors = [base, accent, Color.lerp(base, accent, 0.5)!];
  return [
    for (var i = 0; i < colors.length; i++)
      () {
        final (ax, ay) = MemoriesAuroraTokens.anchors[i];
        final angle = _tau * flow * (0.6 + 0.4 * i) + i * _tau / 3;
        final center = Offset(
          w * ax + math.cos(angle) * w * MemoriesAuroraTokens.orbitX,
          h * ay + math.sin(angle * MemoriesAuroraTokens.orbitYRate) * h * MemoriesAuroraTokens.orbitY,
        );
        final wobble = math.sin(_tau * breath + i);
        final depth = 1 - i * MemoriesAuroraTokens.depthStep;
        final cycled = Color.lerp(colors[i], colors[(i + 1) % colors.length], 0.5 + 0.5 * math.sin(_tau * flow + i))!;
        final radius = maxDim * (MemoriesAuroraTokens.radius + MemoriesAuroraTokens.radiusWobble * wobble) * depth;
        final alpha = alphaCap * presence * (0.8 + 0.2 * wobble) * (0.72 + 0.28 * depth);
        return (center: center, radius: radius, color: cycled, alpha: alpha);
      }(),
  ];
}

/// The deck's aurora, behind the cards. [position] reads the pager's page
/// (and offset) at paint time; [repaint] fires when it moves. [visible]:
/// whether the deck is on screen at all — the field fades in and out on the
/// slow effects spring (a long, soft ambient envelope).
class MemoriesAurora extends StatefulWidget {
  const MemoriesAurora({
    super.key,
    required this.palettes,
    required this.position,
    required this.repaint,
    required this.visible,
    required this.dark,
    required this.ground,
  });

  final List<MemoryPalette> palettes;
  final double Function() position;
  final Listenable repaint;
  final bool visible;
  final bool dark;

  /// The page colour under the field. Painted here so the dark field's screen
  /// blend meets it inside this layer (the field repaints on its own).
  final Color ground;

  @override
  State<MemoriesAurora> createState() => _MemoriesAuroraState();
}

class _MemoriesAuroraState extends State<MemoriesAurora> with SingleTickerProviderStateMixin {
  static final SpringDescription _slowEffects = YoinSprings.of(MotionTokens.expressiveSlowEffects);

  late final SpringValue _presence = SpringValue(vsync: this, initial: 0, spring: _slowEffects);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sync();
  }

  @override
  void didUpdateWidget(MemoriesAurora oldWidget) {
    super.didUpdateWidget(oldWidget);
    _sync();
  }

  void _sync() {
    final target = widget.visible ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _presence.snapTo(target);
    } else {
      _presence.animateTo(target);
    }
  }

  @override
  void dispose() {
    _presence.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => IgnorePointer(
    child: RepaintBoundary(
      child: CustomPaint(
        size: Size.infinite,
        painter: MemoriesAuroraPainter(
          palettes: widget.palettes,
          position: widget.position,
          presence: _presence.animation,
          dark: widget.dark,
          ground: widget.ground,
          repaint: Listenable.merge([widget.repaint, _presence.animation]),
        ),
      ),
    ),
  );
}

class MemoriesAuroraPainter extends CustomPainter {
  MemoriesAuroraPainter({
    required this.palettes,
    required this.position,
    required this.presence,
    required this.dark,
    required this.ground,
    required Listenable repaint,
  }) : super(repaint: repaint);

  final List<MemoryPalette> palettes;
  final double Function() position;
  final Animation<double> presence;
  final bool dark;
  final Color ground;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = ground);
    final frac = presence.value.clamp(0.0, 1.0);
    if (frac < MemoriesAuroraTokens.minPresence || size.isEmpty || palettes.isEmpty) return;
    final at = position();
    final (base, accent) = deckAuroraColors(palettes, at);
    final blend = dark ? BlendMode.screen : BlendMode.srcOver;
    for (final bloom in auroraBlooms(
      size: size,
      position: at,
      base: base,
      accent: accent,
      dark: dark,
      presence: frac,
    )) {
      final core = bloom.color.withValues(alpha: bloom.alpha);
      canvas.drawCircle(
        bloom.center,
        bloom.radius,
        Paint()
          ..blendMode = blend
          ..shader = RadialGradient(
            colors: [core, core.withValues(alpha: 0)],
          ).createShader(Rect.fromCircle(center: bloom.center, radius: bloom.radius)),
      );
    }
  }

  @override
  bool shouldRepaint(MemoriesAuroraPainter old) =>
      !identical(old.palettes, palettes) || old.dark != dark || old.ground != ground || old.position != position;
}
