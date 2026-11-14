import 'package:flutter/material.dart';

import '../../theme/motion.dart';
import 'desktop_input.dart';

/// Press feedback (component/PressFeedback.kt elasticPress): the child
/// scales to [pressedScale] while held and springs back on the standard
/// default spatial spring. No ripple. [onTap] / [onLongPress] fire as usual.
class ElasticPress extends StatefulWidget {
  const ElasticPress({
    super.key,
    required this.child,
    this.onTap,
    this.onLongPress,
    this.pressedScale = 0.97,
    this.semanticsLabel,
  });

  final Widget child;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;
  final double pressedScale;
  final String? semanticsLabel;

  @override
  State<ElasticPress> createState() => _ElasticPressState();
}

class _ElasticPressState extends State<ElasticPress> with TickerProviderStateMixin {
  late final SpringValue _scale = SpringValue(vsync: this, initial: 1, spring: YoinSprings.standardDefaultSpatial);

  @override
  void dispose() {
    _scale.dispose();
    super.dispose();
  }

  void _down() => _scale.animateTo(widget.pressedScale);
  void _up() => _scale.animateTo(1);

  @override
  Widget build(BuildContext context) => Semantics(
    button: widget.onTap != null,
    label: widget.semanticsLabel,
    // Mouse / trackpad / keyboard: hover state layer, click cursor, focus
    // ring + Enter, and the right click as the long-press (its menu).
    child: YoinHover(
      onTap: widget.onTap,
      onSecondaryTap: widget.onLongPress,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: widget.onTap == null ? null : (_) => _down(),
        onTapUp: widget.onTap == null ? null : (_) => _up(),
        onTapCancel: _up,
        onTap: widget.onTap,
        onLongPress: widget.onLongPress,
        child: ScaleTransition(scale: _scale.animation, child: widget.child),
      ),
    ),
  );
}
