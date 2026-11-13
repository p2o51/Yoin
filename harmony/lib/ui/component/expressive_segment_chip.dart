import 'package:flutter/material.dart';

import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import 'elastic_press.dart';

/// ExpressiveSegmentChip (component/ExpressiveComponents.kt): FilledTonalButton
/// look, 44 high, 18 corner, h16 v10, labelLarge. Selected =
/// secondaryContainer / onSecondaryContainer, else surfaceContainerHigh /
/// onSurfaceVariant; colours on the effects spring, press 0.97.
class ExpressiveSegmentChip extends StatefulWidget {
  const ExpressiveSegmentChip({super.key, required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<ExpressiveSegmentChip> createState() => _ExpressiveSegmentChipState();
}

class _ExpressiveSegmentChipState extends State<ExpressiveSegmentChip> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(ExpressiveSegmentChip old) {
    super.didUpdateWidget(old);
    _on.animateTo(widget.selected ? 1 : 0);
  }

  @override
  void dispose() {
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelLarge;
    return Semantics(
      selected: widget.selected,
      button: true,
      child: ElasticPress(
        onTap: widget.onTap,
        child: AnimatedBuilder(
          animation: _on.animation,
          builder: (context, _) {
            final t = _on.value.clamp(0.0, 1.0);
            return Container(
              constraints: const BoxConstraints(minHeight: LibraryTokens.chipHeight),
              padding: const EdgeInsets.symmetric(
                horizontal: LibraryTokens.chipPaddingH,
                vertical: LibraryTokens.chipPaddingV,
              ),
              decoration: ShapeDecoration(
                color: Color.lerp(colors.surfaceContainerHigh, colors.secondaryContainer, t),
                shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(LibraryTokens.chipCorner)),
              ),
              alignment: Alignment.center,
              child: Text(
                widget.label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: style?.copyWith(color: Color.lerp(colors.onSurfaceVariant, colors.onSecondaryContainer, t)),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// Fades a horizontally scrolling [child]'s edges (PressFeedback.kt
/// horizontalEdgeFadeOnScroll): a 24dp eased ramp on an edge only while more
/// content lies beyond it, each side's strength on the effects spring.
class HorizontalEdgeFade extends StatefulWidget {
  const HorizontalEdgeFade({super.key, required this.controller, required this.child, this.width = 24});

  final ScrollController controller;
  final Widget child;
  final double width;

  @override
  State<HorizontalEdgeFade> createState() => _HorizontalEdgeFadeState();
}

class _HorizontalEdgeFadeState extends State<HorizontalEdgeFade> with TickerProviderStateMixin {
  late final SpringValue _start = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  late final SpringValue _end = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  static const _ramp = [0.0, .104, .352, .648, .896, 1.0];

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_sync);
    WidgetsBinding.instance.addPostFrameCallback((_) => _sync());
  }

  @override
  void didUpdateWidget(HorizontalEdgeFade old) {
    super.didUpdateWidget(old);
    if (old.controller != widget.controller) {
      old.controller.removeListener(_sync);
      widget.controller.addListener(_sync);
    }
  }

  void _sync() {
    if (!mounted || !widget.controller.hasClients) return;
    final p = widget.controller.position;
    if (!p.hasContentDimensions) return;
    _start.animateTo(p.pixels > p.minScrollExtent + 0.5 ? 1 : 0);
    _end.animateTo(p.pixels < p.maxScrollExtent - 0.5 ? 1 : 0);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_sync);
    _start.dispose();
    _end.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => NotificationListener<ScrollMetricsNotification>(
    onNotification: (_) {
      _sync();
      return false;
    },
    child: AnimatedBuilder(
      animation: Listenable.merge([_start.animation, _end.animation]),
      child: widget.child,
      builder: (context, child) {
        final s = _start.value.clamp(0.0, 1.0);
        final e = _end.value.clamp(0.0, 1.0);
        if (s == 0 && e == 0) return child!;
        return ShaderMask(
          blendMode: BlendMode.dstIn,
          shaderCallback: (rect) {
            final w = rect.width <= 0 ? 1.0 : rect.width;
            final f = (widget.width / w).clamp(0.0, 0.5);
            final colors = <Color>[];
            final stops = <double>[];
            for (final (i, a) in _ramp.indexed) {
              colors.add(Colors.black.withValues(alpha: 1 - s * (1 - a)));
              stops.add(f * i / (_ramp.length - 1));
            }
            for (final (i, a) in _ramp.reversed.indexed) {
              colors.add(Colors.black.withValues(alpha: 1 - e * (1 - a)));
              stops.add(1 - f + f * i / (_ramp.length - 1));
            }
            return LinearGradient(colors: colors, stops: stops).createShader(rect);
          },
          child: child,
        );
      },
    ),
  );
}
